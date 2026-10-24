package dev.aj.miniapp

import org.json.JSONObject

/**
 * 路由大脑（App 本体版），和网页版的 modelbrain.py 是同一套机制：
 *
 *  - 清单不内置：每次从"用户自己配的服务商 + 模型"现生成。第一个服务商算主用档（free），
 *    其余算备用档（paid）。用户加/删模型，这里立刻跟上，不需要改任何文件。
 *  - 排序：主用档恒在备用档之前（绝不自动跨档升级）；档内按健康度
 *    ok > limited/unknown > dead，冷却中的沉底但仍留在链里；任务偏好只在同档同健康度内前移。
 *  - 健康度不是盲测出来的，是每次真实调用反手记一笔（成功清零复活 / 失败累加并抬冷却），
 *    存在 SharedPreferences 的 routeHealth 里。零额外请求、零额外花费。
 *  - 场景不符的模型不进链：生图/视频/语音模型只在"画图"类任务里出现。
 */
object RouteBrain {

    /** 清单里的一条：模型名 + 档位 + 所属服务商 + 该服务商的地址和 key */
    data class Entry(val name: String, val tier: String, val role: String, val base: String, val key: String)

    private const val HEALTH_KEY = "routeHealth"
    private val COOLDOWN = longArrayOf(60_000, 180_000, 600_000, 1_800_000, 3_600_000)
    private const val DEAD_COOLDOWN = 1_800_000L   // 判死也只锁 30 分钟

    private val MEDIA = listOf(
        "video", "image", "seedream", "cogview", "imagen", "tts", "asr",
        "voice", "speech", "kling", "wan2", "flux", "kolors"
    )

    /** 任务偏好：命中的关键词 → 优先挑名字里带这些片段的模型（只在同档同健康度内前移） */
    private val PREFS: List<Pair<String, List<String>>> = listOf(
        "写代码" to listOf("deepseek-flash", "deepseek-v4-flash", "v4-1-flash", "deepseek-v4-pro"),
        "编码" to listOf("deepseek-flash", "deepseek-v4-flash", "v4-1-flash", "deepseek-v4-pro"),
        "脚本" to listOf("deepseek-flash", "deepseek-v4-flash", "v4-1-flash", "deepseek-v4-pro"),
        "排障" to listOf("deepseek-flash", "deepseek-v4-flash", "v4-1-flash", "deepseek-v4-pro"),
        "改配方" to listOf("deepseek-flash", "deepseek-v4-flash", "v4-1-flash", "deepseek-v4-pro"),
        "合同" to listOf("deepseek-v4-pro", "deepseek-flash"),
        "审查" to listOf("deepseek-v4-pro", "deepseek-flash"),
        "阅读" to listOf("deepseek-v4-pro", "deepseek-flash"),
        "精读" to listOf("deepseek-v4-pro", "deepseek-flash"),
        "批判" to listOf("deepseek-v4-pro", "deepseek-flash"),
        "标题" to listOf("glm-4.7-flash"),
        "文案" to listOf("glm-4.7-flash"),
        "小红书" to listOf("glm-4.7-flash"),
        "公众号" to listOf("glm-4.7-flash"),
        "写手" to listOf("glm-4.7-flash"),
        "小说" to listOf("glm-4.7-flash", "scnet-max", "GLM-5.2"),
        "网文" to listOf("glm-4.7-flash", "scnet-max", "GLM-5.2"),
        "长文" to listOf("glm-4.7-flash", "GLM-5.2", "scnet-max"),
        "续写" to listOf("glm-4.7-flash", "scnet-max"),
        "看图" to listOf("glm-4.6v-flash", "glm-4v-flash", "glm-4.1v-thinking-flash"),
        "视觉" to listOf("glm-4.6v-flash", "glm-4v-flash", "glm-4.1v-thinking-flash"),
        "图片" to listOf("glm-4.6v-flash", "glm-4v-flash", "glm-4.1v-thinking-flash"),
        "画图" to listOf("__MEDIA__"), "生图" to listOf("__MEDIA__"), "插图" to listOf("__MEDIA__")
    )

    /** 每轮任务文本，由 AgentLoop 在 send() 开头设置，只用于排序偏好 */
    @Volatile
    var task: String = ""

    // ---------------------------------------------------------------- 清单

    /** 用 App 里现成的服务商/模型现生成清单（不内置任何模型名） */
    fun entries(st: Settings): List<Entry> {
        val out = mutableListOf<Entry>()
        runCatching {
            st.providers().forEachIndexed { i, p ->
                val base = p.baseUrl.trim()
                if (base.isBlank()) return@forEachIndexed
                val key = runCatching { p.key }.getOrDefault("")
                for (m in p.models) {
                    val n = m.trim()
                    if (n.isEmpty()) continue
                    if (out.any { it.name == n && it.base == base }) continue
                    out += Entry(n, if (i == 0) "free" else "paid", p.name, base, key)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 台账

    private fun load(st: Settings): JSONObject =
        runCatching { JSONObject(st.routeHealthRaw().ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun save(st: Settings, o: JSONObject) {
        runCatching { st.setRouteHealthRaw(o.toString()) }
    }

    private fun hkey(e: Entry) = "${e.role}|${e.name}"   // 沙箱用 entry_id，这里用 服务商|模型

    private fun rec(h: JSONObject, e: Entry): JSONObject =
        runCatching { h.optJSONObject(hkey(e)) ?: h.optJSONObject("${e.tier}/${e.name}") ?: JSONObject() }.getOrDefault(JSONObject())

    private fun status(r: JSONObject) = r.optString("status", "unknown").ifBlank { "unknown" }

    private fun coolLeft(r: JSONObject): Long {
        val until = r.optLong("coolUntil", 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 把一次失败归成 dead / limited / unknown —— 决定冷却多久 */
    private fun classify(err: String): String {
        val s = err.lowercase()
        if (listOf("rate limit", "rate_limit", "429", "too many", "quota", "frequency",
                "insufficient balance", "402", "balance", "限流").any { s.contains(it) }) return "limited"
        if (listOf("invalid api key", "unauthorized", "401", "403", "authentication", "无权限")
                .any { s.contains(it) }) return "unknown"
        // 只有明确「模型不存在」才判死；单纯一个 404（接口路径/网关）不算，
        // 否则同账号能用的服务商会被整条锁死（2026-10-24 实测踩过）
        if (listOf("no such model", "model_not_found", "invalid model", "model not found",
                "模型不存在", "does not exist", "supported api model names", "supported model",
                "not supported").any { s.contains(it) }) return "dead"
        if (listOf("not found", "404", "不存在").any { s.contains(it) }) return "limited"
        return "unknown"
    }

    /** 真实调用成功 → 清零复活 */
    fun markOk(st: Settings, name: String, base: String) {
        runCatching {
            val list = entries(st).filter { it.name == name && (base.isBlank() || it.base == base) }
            if (list.isEmpty()) return@runCatching
            val h = load(st)
            for (e in list) {
                val r = rec(h, e)
                r.put("status", "ok")
                r.put("fails", 0)
                r.put("coolUntil", 0L)
                r.put("okAt", System.currentTimeMillis())
                r.put("tier", e.tier)
                r.put("name", e.name)
                h.put(hkey(e), r)
            }
            save(st, h)
        }
    }

    /** 真实调用失败 → 累加连败数、按次数抬冷却（404 直接躺 2 小时） */
    /** 把服务商的长报错压成一句能看懂的话（尤其「模型名不支持」会把支持列表带回来） */
    private fun tidy(err: String): String {
        val s = err.replace("\n", " ").replace("\r", " ").trim()
        val i = s.indexOf("supported API model names", ignoreCase = true)
        if (i >= 0) return "模型名不被支持 →" + s.substring(i).take(220)
        return s.take(260)
    }

    fun markFail(st: Settings, name: String, base: String, err: String) {
        runCatching {
            val list = entries(st).filter { it.name == name && (base.isBlank() || it.base == base) }
            if (list.isEmpty()) return@runCatching
            val st2 = classify(err)
            val h = load(st)
            val now = System.currentTimeMillis()
            for (e in list) {
                val r = rec(h, e)
                val fails = r.optInt("fails", 0) + 1
                val cool = if (st2 == "dead") DEAD_COOLDOWN else COOLDOWN[(fails - 1).coerceIn(0, COOLDOWN.size - 1)]
                r.put("status", st2)
                r.put("fails", fails)
                r.put("failAt", now)
                r.put("coolUntil", now + cool)
                r.put("lastErr", tidy(err))
                r.put("tier", e.tier)
                r.put("name", e.name)
                h.put(hkey(e), r)
            }
            save(st, h)
        }
    }

    /** 健康台账一眼看：给设置页用 */
    fun summary(st: Settings): String {
        val es = entries(st)
        if (es.isEmpty()) return "还没配模型。"
        val h = load(st)
        return es.joinToString("\n") { e ->
            val r = rec(h, e)
            val s = status(r)
            val cool = coolLeft(r)
            val icon = when (s) {
                "ok" -> "✅"
                "limited" -> "🔶"
                "dead" -> "❌"
                else -> "⚠️"
            }
            val extra = buildString {
                if (cool > 0) append(" 冷却${cool / 1000}s")
                val f = r.optInt("fails", 0)
                if (f > 0) append(" 连败$f")
                val le = r.optString("lastErr", "").replace("\n", " ").trim()
                if (le.isNotEmpty()) append(" ｜ ").append(le.take(200))
            }
            "$icon [${e.tier}] ${e.name}  $s$extra"
        }
    }

    // ---------------------------------------------------------------- 排序

    private fun matchPref(e: Entry, kw: String): Boolean {
        val k = kw.lowercase()
        return k in e.name.lowercase() || k in e.role.lowercase()
    }

    private fun isMedia(n: String): Boolean {
        val l = n.lowercase()
        return MEDIA.any { l.contains(it) }
    }

    private fun prefKeys(t: String): List<String>? {
        if (t.isBlank()) return null
        val tl = t.lowercase()
        return PREFS.filter { tl.contains(it.first) }
            .maxByOrNull { it.first.length }?.second
    }

    /**
     * 排好的候选链（第一个就是这次用的）。limit 控制长度，够用就行。
     * 清单为空 / 任何异常 → 返回空列表，调用方自己回退到当前模型。
     */
    fun chain(st: Settings, taskText: String = task, limit: Int = 6): List<Entry> {
        // 照沙箱 model-brain.py rank()：排序键只有 (档位, 健康, 任务偏好, 名字)，
        // 不用"模型在清单里排第几" —— 那会把用户选中的模型永久压在后面。
        return runCatching {
            val es = entries(st)
            if (es.isEmpty()) return emptyList<Entry>()
            val prefs = prefKeys(taskText)
            val mediaTask = prefs == listOf("__MEDIA__")
            val h = load(st)
            val now = System.currentTimeMillis()
            data class Row(val t: Int, val s: Int, val p: Int, val name: String, val e: Entry)
            val rows = ArrayList<Row>()
            es.forEach { e ->
                val media = isMedia(e.name)
                if (mediaTask && !media) return@forEach
                if (media && !mediaTask) return@forEach
                val r = rec(h, e)
                val st0 = status(r)
                val cool = (r.optLong("coolUntil", 0L) - now).coerceAtLeast(0L)
                val s = when (st0) {
                    "ok" -> 0
                    "limited", "invisible", "timeout", "unknown" -> if (cool > 0) 2 else (if (st0 == "limited" || st0 == "invisible") 1 else 2)
                    "dead" -> 3
                    else -> 2
                }
                var t = if (e.tier == "free") 0 else 1
                if (st0 == "dead") t += 2          // 死掉的免费排到付费后面
                var pp = 99
                if (mediaTask) pp = 0
                else if (prefs != null) {
                    val pi = prefs.indexOfFirst { matchPref(e, it) }
                    if (pi >= 0) pp = if (s == 0) pi else pi + 10
                }
                rows += Row(t, s, pp, e.name, e)
            }
            rows.sortWith(compareBy({ it.t }, { it.s }, { it.p }, { it.name }))
            rows.map { it.e }.take(limit)
        }.getOrDefault(emptyList())
    }
}
