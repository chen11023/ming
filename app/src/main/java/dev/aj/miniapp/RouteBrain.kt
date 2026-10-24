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
        "画图" to listOf("__MEDIA__"), "生图" to listOf("__MEDIA__"), "插图" to listOf("__MEDIA__"),
        "写代码" to listOf("deepseek", "code", "coder"),
        "代码" to listOf("deepseek", "code", "coder"),
        "脚本" to listOf("deepseek", "code", "coder"),
        "排障" to listOf("deepseek", "reasoner"),
        "推理" to listOf("reasoner", "r1", "thinking", "v4-pro", "pro"),
        "长文" to listOf("pro", "large", "122b", "397b"),
        "看图" to listOf("v-flash", "vl", "vision"),
        "视觉" to listOf("v-flash", "vl", "vision"),
        "翻译" to listOf("flash", "qwen", "glm"),
        "标题" to listOf("flash"),
        "文案" to listOf("flash")
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

    private fun hkey(e: Entry) = "${e.tier}/${e.name}"

    private fun rec(h: JSONObject, e: Entry): JSONObject =
        runCatching { h.optJSONObject(hkey(e)) ?: JSONObject() }.getOrDefault(JSONObject())

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
                "模型不存在", "does not exist").any { s.contains(it) }) return "dead"
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
                r.put("lastErr", err.take(160))
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
                if (le.isNotEmpty()) append(" ｜ ").append(le.take(70))
            }
            "$icon [${e.tier}] ${e.name}  $s$extra"
        }
    }

    // ---------------------------------------------------------------- 排序

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
        return runCatching {
            val es = entries(st)
            if (es.isEmpty()) return emptyList<Entry>()
            val prefs = prefKeys(taskText)
            val mediaTask = prefs == listOf("__MEDIA__")
            val h = load(st)
            val now = System.currentTimeMillis()
            val rows = ArrayList<Triple<Int, Long, Entry>>()
            es.forEachIndexed { i, e ->
                val media = isMedia(e.name)
                if (mediaTask && !media) return@forEachIndexed
                if (media && !mediaTask) return@forEachIndexed
                val r = rec(h, e)
                val s = status(r)
                val cool = (r.optLong("coolUntil", 0L) - now).coerceAtLeast(0L)
                var health = when (s) {
                    "ok" -> 0
                    "limited", "unknown" -> 2
                    "dead" -> 3
                    else -> 2
                }
                if (cool > 0 && s != "dead") health = 3
                var tierRank = if (e.tier == "free") 0 else 1
                if (s == "dead") tierRank += 2        // 死掉的免费模型排到付费后面
                var pref = 99
                if (mediaTask) pref = 0
                else if (prefs != null) {
                    val pi = prefs.indexOfFirst { e.name.lowercase().contains(it) }
                    if (pi >= 0) pref = if (health == 0) pi else pi + 10
                }
                // 打包成一个可排序的整数：档位 * 100000 + 健康 * 10000 + 偏好 * 100 + 清单位次
                val sortKey = tierRank * 100000 + health * 10000 + pref * 100 + i.coerceAtMost(99)
                rows += Triple(sortKey, now - i, e)
            }
            rows.sortBy { it.first }
            rows.map { it.third }.take(limit)
        }.getOrDefault(emptyList())
    }
}
