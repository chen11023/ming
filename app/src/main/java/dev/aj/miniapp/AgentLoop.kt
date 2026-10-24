package dev.aj.miniapp

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * 工具循环：模型要命令 → 我们执行 → 结果回传 → 继续，直到模型给出最终回答。
 *
 * 这是 Minis 的核心，也是「我掌控」的关键：
 *  - 每条命令执行前可被钩子拦截
 *  - 钩子放 /host/hooks/before.sh，返回非 0 就跳过这条命令
 */
class AgentLoop(
    private val ctx: Context,
    private val settings: Settings,
    private val envStore: EnvStore,
    private val onEvent: (Event) -> Unit
) {

    sealed class Event {
        class Thinking(val text: String) : Event()
        class Command(val cmd: String) : Event()
        class Output(val text: String) : Event()
        class Answer(val text: String) : Event()
        class Error(val msg: String) : Event()
        /** 流式增量，界面拿它实时刷新 */
        class Delta(val text: String) : Event()
        /** 一轮跑完的用量：模型调用次数 / 字符数 / 耗时 */
        class Stat(val calls: Int, val chars: Long, val ms: Long) : Event()
    }

    private val client = LlmClient(settings)

    /** 本轮实际用的模型（主模型挂了会自动切到同服务商的备用模型） */
    fun lastModel(): String = client.lastUsedModel
    private val mcp by lazy { McpManager(ctx) }

    @Volatile
    private var stopFlag = false

    /** 界面提供的确认器：返回 true 才动手。没挂或设置里关了就直接放行 */
    var onConfirm: ((String) -> Boolean)? = null
    /** 停下来问用户：入参是问题，返回用户打的字（界面负责弹框等回答） */
    var onAsk: ((String) -> String)? = null

    private var statCalls = 0
    private var statChars = 0L

    // 同一调用连续重复的计数，换新会话/新任务时重置
    private var lastToolKey = ""
    private var lastToolCount = 0
    private var lastToolWarned = 0
    private var warnedAt = 0

    /** 3 次提示 / 5 次详细 / 8 次警告 / 15 次拦下；返回 (是否执行, 提示文本) */
    /** 工具输出回填给模型前先脱敏（照沙箱 outgate 那 8 类） */
    private fun redact(s: String): String {
        var t = s
        val pats = listOf(
            Regex("sk-[A-Za-z0-9]{16,}"),
            Regex("AKIA[0-9A-Z]{16}"),
            Regex("ghp_[A-Za-z0-9]{20,}"),
            Regex("eyJ[A-Za-z0-9_-]{20,}"),
            Regex("(?i)Bearer\\s+[A-Za-z0-9._-]{20,}"),
            Regex("(?i)api[_-]?key\\s*[:=]\\s*\\S{12,}"),
            Regex("(?i)password\\s*[:=]\\s*\\S+"),
            Regex("(?i)token\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]{16,}")
        )
        for (r in pats) t = r.replace(t, "<已脱敏>")
        return t
    }

    private fun repeatGuard(key: String): Pair<Boolean, String> {
        if (key == lastToolKey) {
            lastToolCount++
        } else {
            lastToolKey = key
            lastToolCount = 1
            warnedAt = 0
        }
        val n = lastToolCount
        if (n >= 15) {
            return false to "[已拦下] 同一调用已连续 $n 次，结果不会变。换个做法，或直接说明卡在哪。"
        }
        var warn = ""
        if (n >= 8 && warnedAt < 8) {
            warnedAt = 8
            warn = "[警告] 同一调用已第 $n 次，再重复只会浪费时间。"
        } else if (n >= 5 && warnedAt < 5) {
            warnedAt = 5
            warn = "[提醒] 同一调用第 $n 次：${key.take(120)}。先看上一次的报错，别原样再来。"
        } else if (n >= 3 && warnedAt < 3) {
            warnedAt = 3
            warn = "[提示] 这一步重复了 $n 次，确认参数是不是写错了。"
        }
        return true to warn
    }

    /** 新任务开始时清掉重复计数 */
    private fun resetRepeat() {
        lastToolKey = ""
        lastToolCount = 0
        warnedAt = 0
    }

    private fun confirm(what: String): Boolean {
        if (!settings.confirmExec) return true
        val f = onConfirm ?: return true
        return runCatching { f(what) }.getOrDefault(false)
    }

    /** MCP 工具清单缓存，改配置后清掉重拉 */
    private var mcpTools: org.json.JSONArray? = null

    fun refreshMcp() {
        mcpTools = null
        mcp.stopAll()
    }

    /** 停：界面按停止时调，掐请求、杀在跑的 proot 进程、掐掉 MCP 服务，循环下一轮退出 */
    fun stop() {
        stopFlag = true
        client.cancel()
        runCatching { Linux.killCurrent() }
        runCatching { mcp.stopAll() }
    }

    private val skills by lazy { SkillsStore(ctx) }
    private val memory by lazy { MemoryStore(ctx) }

    /** 系统提示 = 基础人格 + 记忆 + 命中技能的正文（每轮重算，记忆和技能随时生效） */
    private fun buildSystem(userText: String): String {
        val sb = StringBuilder(settings.systemPrompt.trim())
        sb.append("\n\n## 产物输出\n")
        sb.append("图片、图表、视频、文档等产物一律写进 /out 目录（例如 /out/chart.png）。")
        sb.append("\n文件读写工具（read_file/write_file）只在 /host 下面可用（/host 就是 App 私有目录：/host/out 产物、/host/ajhome 家目录、/host/web 网页版）。"
                  + "读写 /host 以外的系统路径请用 sh 命令。\n")
        sb.append("写完后只回一句说明路径，不要把 base64 或 ANSI 彩色字符贴进对话——App 会自动在聊天里显示 /out 里的新文件。\n")
        sb.append("\n\n## 先查再问（不确定时的固定走法）\n")
        sb.append("不知道某个东西在哪、能不能用、怎么走 —— 不要凭印象答，也不要先问用户，先自己查：\n")
        sb.append("1. 有现成技能：`ls /var/minis/skills` 或 `ls ~/skills` 看目录，读到就用（App 内技能在 files/skills 已经注入到你的上下文，沙箱里的要自己 ls）。\n")
        sb.append("2. 找文件/找线索：`rg -n 关键词 /path`（比 grep 快），找不到再放宽目录。\n")
        sb.append("3. 探服务：`curl -s -o /dev/null -w '%{http_code}' 地址`，或 `ss -lntp` 看端口在不在。\n")
        sb.append("4. 试命令：先 `--help` 或 `-h`，再小范围试一次；失败换参数不换说法。\n")
        sb.append("5. 查完再答，答的时候说清楚依据（哪条命令、什么输出）。真查不到就说查不到。\n")
        sb.append("6. 摸出来的结论写进 `/out/notes-日期.md`，下次遇到同样的问题先读它。\n")
        val mem = memory.recent()
        if (mem.isNotBlank()) {
            sb.append("\n\n## 记忆（之前的对话，供参考）\n").append(mem)
        }
        val hit = skills.match(userText)
        if (hit.isNotEmpty()) {
            sb.append("\n\n## 技能（命中，照里面的步骤做）\n")
            for ((n, body) in hit) {
                sb.append("\n### ").append(n).append('\n').append(body).append('\n')
            }
        }
        return sb.toString()
    }

    /** 会话历史，只保留在内存，不落盘（第一版） */
    private val history = mutableListOf<JSONObject>()

    fun reset() = history.clear()

    /** 当前会话 id，文件名就是它：sessions/<id>.json */
    var sessionId: String = "last"
        private set

    private val sessionDir: java.io.File
        get() = java.io.File(ctx.filesDir, "sessions").apply { mkdirs() }

    /** 会话文件：只存对话，不存 system（system 每轮重建） */
    private val sessionFile: java.io.File
        get() = java.io.File(sessionDir, "$sessionId.json")

    /** 开新会话：先把当前这段存下来，再切到新 id */
    fun startNew(id: String) {
        save()
        sessionId = id
        history.clear()
    }

    /** 切到某个历史会话，返回载入的消息条数 */
    fun switchTo(id: String): Int {
        save()
        sessionId = id
        return load()
    }

    /** 定背后台任务用：不落盘，绝不碰用户的会话文件 */
    var persist: Boolean = true

    fun save() {
        if (!persist) return
        runCatching {
            val arr = org.json.JSONArray()
            history.filter { it.optString("role") != "system" }.forEach { arr.put(it) }
            if (arr.length() == 0) {
                sessionFile.delete()   // 空会话不留文件，免得会话列表里一堆空壳
                return@runCatching
            }
            sessionFile.writeText(arr.toString())
        }
    }

    /** 启动时把上次会话读回来 */
    fun load(): Int {
        val f = sessionFile
        if (!f.exists()) return 0
        return runCatching {
            val arr = org.json.JSONArray(f.readText())
            history.clear()
            for (i in 0 until arr.length()) history += arr.getJSONObject(i)
            history.size
        }.getOrDefault(0)
    }

    fun send(userText: String, parts: List<JSONObject> = emptyList()) {
        stopFlag = false
        client.forgetModel()
        client.resetCancel()
        statCalls = 0
        statChars = 0L
        resetRepeat()
        // 交给路由大脑做任务偏好；带图片时标记成"看图"，让它优先挑能看图的模型
        RouteBrain.task = if (parts.isNotEmpty()) "看图 " + userText else userText
        val t0 = System.currentTimeMillis()
        try {
            turn(userText, parts)
        } finally {
            save()
            onEvent(Event.Stat(statCalls, statChars, System.currentTimeMillis() - t0))
        }
    }

    // ---------- 读文件 / 写文件 / 查技能 ----------
    private fun readFile(path: String, offset: Int, limit: Int): String {
        if (path.isBlank()) return "[没有给 path]"
        val f = File(path.trim())
        if (!f.exists()) return "[文件不存在] $path"
        if (f.isDirectory) return "[这是目录] $path\n" + runCatching {
            f.listFiles()?.take(200)?.joinToString("\n") { (if (it.isDirectory) "d " else "- ") + it.name }.orEmpty()
        }.getOrDefault("")
        return runCatching {
            val all = f.readLines()
            val from = (offset.coerceAtLeast(1) - 1).coerceAtMost(all.size)
            val to = (from + limit.coerceAtLeast(1)).coerceAtMost(all.size)
            val sb = StringBuilder()
            for (i in from until to) sb.append(i + 1).append("\t").append(all[i]).append("\n")
            if (to < all.size) sb.append("…（共 ${all.size} 行，读到第 $to 行，后面接着读用 offset=${to + 1}）")
            "$path（第 ${from + 1}-$to 行 / 共 ${all.size} 行）\n" + sb
        }.getOrElse { "[读不了] $path：${it.message}" }
    }

    private fun writeFile(path: String, content: String, append: Boolean): String {
        if (path.isBlank()) return "[没有给 path]"
        return runCatching {
            val f = File(path.trim())
            f.parentFile?.mkdirs()
            if (append && f.exists()) f.appendText(content) else f.writeText(content)
            val n = f.length()
            "[已写入] ${f.absolutePath}（$n 字节${if (append) "，追加" else ""}）"
        }.getOrElse { "[写不了] $path：${it.message}" }
    }

    private fun skillDoc(name: String): String {
        val st = SkillsStore(ctx)
        val list = st.names()
        if (name.isBlank()) {
            return if (list.isEmpty()) "[这台机器上还没有技能] 把技能放到 ${st.dir().absolutePath}/<名字>/SKILL.md"
            else "[可用技能 ${list.size} 个] " + list.joinToString("、") + "\n读正文：skill(name=\"技能名\")"
        }
        val hit = list.firstOrNull { it.equals(name.trim(), true) } ?: list.firstOrNull { it.contains(name.trim(), true) }
        if (hit == null) return "[没有技能] $name；现有：${list.joinToString("、")}"
        val body = st.read(hit)
        return "[技能 $hit]\n" + body.take(4000)
    }

    /** 上下文压缩：太长就砍掉中段，保留开头 system 和最后十几条，避免几十轮后 400 */
    /** 把中段对话压成 8 节结构摘要；失败就返回空，让调用方退回机械省略 */
    private fun checkpoint(mid: List<JSONObject>): String {
        if (mid.isEmpty()) return ""
        var budget = 16000
        val sb = StringBuilder()
        for (m in mid) {
            var t = m.optString("content", "")
            if (t.isBlank() && m.has("tool_calls")) t = "[调用工具] " + m.optJSONArray("tool_calls").toString().take(300)
            if (t.isBlank()) continue
            if (t.length > 800) t = t.take(800) + "…"
            sb.append(m.optString("role", "?")).append(": ").append(t).append("\n")
            budget -= t.length
            if (budget <= 0) break
        }
        val body = sb.toString().trim()
        if (body.length < 200) return ""
        return runCatching {
            val ask = JSONObject().apply {
                put("role", "user")
                put("content", "把下面这段较早的对话整理成一份结构化摘要，供另一个模型接着干活、不丢关键信息。" +
                    "只要摘要本身，不要寒暄。用这 8 节：\n" +
                    "1 用户目标\n2 已确认的技术点/事实\n3 涉及的文件与路径\n4 遇到的错误与解决\n" +
                    "5 待办\n6 当前进展\n7 下一步\n8 关键上下文\n\n--- 对话 ---\n" + body)
            }
            val r = LlmClient(settings).chat(listOf(ask))
            (r.text ?: "").trim()
        }.getOrDefault("")
    }

    private fun compress() {
        val others = history.filter { it.optString("role") != "system" }
        val chars = others.sumOf { it.toString().length }
        if (others.size <= 30 && chars <= 80000) return
        var cut = others.size - 16
        if (cut <= 0) return
        // 从用户消息下刀，别把 assistant(tool_calls) 和 tool 回执切开
        while (cut < others.size - 1 && others[cut].optString("role") != "user") cut++
        val sys = history.firstOrNull { it.optString("role") == "system" }
        val tail = others.subList(cut, others.size).toList()
        history.clear()
        sys?.let { history += it }
        val mid = others.subList(0, cut).toList()
        val sum = if (settings.compressSummary) checkpoint(mid) else ""
        history += JSONObject().apply {
            put("role", "user")
            put("content", if (sum.isBlank()) "[前 $cut 条历史已省略]"
                          else "[较早的 $cut 条历史，已整理成下面这份摘要]\n$sum")
        }
        history += JSONObject().apply {
            put("role", "assistant")
            put("content", "明白，接着说。")
        }
        history.addAll(tail)
    }

    private fun turn(userText: String, parts: List<JSONObject>) {
        history += JSONObject().apply {
            put("role", "user")
            if (parts.isEmpty()) {
                put("content", userText)
            } else {
                val arr = org.json.JSONArray()
                arr.put(JSONObject().apply {
                    put("type", "text")
                    put("text", userText.ifBlank { "(见附件)" })
                })
                parts.forEach { arr.put(it) }
                put("content", arr)
            }
        }

        // 每轮重建 system（记忆/技能会变），旧的丢掉
        history.removeAll { it.optString("role") == "system" }
        history.add(0, JSONObject().apply {
            put("role", "system")
            put("content", buildSystem(userText))
        })

        var turn = 0
        while (turn < settings.maxTurns) {
            if (stopFlag) {
                onEvent(Event.Error("已停止"))
                return
            }
            turn++
            compress()
            val extra = mcpTools ?: runCatching { mcp.toolDefs() }
                .getOrDefault(org.json.JSONArray())
                .also { mcpTools = it }
            val reply = client.chatStream(history, extra) { d -> onEvent(Event.Delta(d)) }
            statCalls++
            statChars += (reply.text?.length ?: 0).toLong() +
                reply.toolCalls.sumOf { it.arguments.length.toLong() }

            if (reply.error != null) {
                onEvent(Event.Error(reply.error!!))
                return
            }

            // 模型要执行命令
            if (reply.hasTool) {
                reply.text?.takeIf { it.isNotBlank() }?.let { onEvent(Event.Thinking(it)) }

                // 把 assistant 的 tool_calls 原样写回历史
                val assistantMsg = JSONObject().apply {
                    put("role", "assistant")
                    put("content", reply.text ?: JSONObject.NULL)
                    put("tool_calls", org.json.JSONArray().apply {
                        reply.toolCalls.forEach { tc ->
                            put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }
                history += assistantMsg

                // 逐条执行
                for (tc in reply.toolCalls) {
                    if (stopFlag) {
                        onEvent(Event.Error("已停止"))
                        return
                    }
                    val cmd = runCatching {
                        JSONObject(tc.arguments).optString("command", "")
                    }.getOrDefault("")

                    // 同一调用连续重复：3 次提示 / 5 次详细 / 8 次警告 / 15 次拦下
                    val (rgOk, rgWarn) = repeatGuard(tc.name + "|" + tc.arguments)
                    if (!rgOk) {
                        onEvent(Event.Output(rgWarn))
                        history += JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", rgWarn)
                        }
                        continue
                    }

                    // 动手前确认（设置里可关）
                    val label = when {
                        tc.name == "draw" ->
                            "出图：" + runCatching { JSONObject(tc.arguments).optString("prompt") }.getOrDefault("")
                        tc.name == "remind" ->
                            "设提醒：" + runCatching {
                                JSONObject(tc.arguments).let { it.optString("delay") + " 后 " + it.optString("text") }
                            }.getOrDefault("")
                        tc.name == "write_file" ->
                            "写文件：" + runCatching { JSONObject(tc.arguments).optString("path") }.getOrDefault("")
                        tc.name == "ask_user" -> ""
                        tc.name.startsWith("mcp__") -> "调用 MCP 工具：${tc.name}"
                        else -> "执行命令：" + cmd
                    }
                    if (tc.name != "ask_user" && !confirm(label)) {
                        onEvent(Event.Thinking("已拒绝 —— $label".take(160)))
                        history += JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", "[已取消] 宿主拒绝执行：$label")
                        }
                        continue
                    }

                    val rawResult = when {
                        tc.name == "read_file" -> {
                            val a = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                            readFile(a.optString("path", ""), a.optInt("offset", 1), a.optInt("limit", 200))
                        }
                        tc.name == "write_file" -> {
                            val a = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                            writeFile(a.optString("path", ""), a.optString("content", ""), a.optBoolean("append", false))
                        }
                        tc.name == "skill" -> {
                            val a = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                            skillDoc(a.optString("name", ""))
                        }
                        tc.name == "ask_user" -> {
                            val a = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                            val q = a.optString("question", "").ifBlank { "（问题为空）" }
                            onEvent(Event.Thinking("等你回答：$q".take(160)))
                            val ans = onAsk?.invoke(q) ?: ""
                            if (ans.isBlank()) "[宿主未回答]" else "宿主回答：$ans"
                        }
                        tc.name == "remind" -> {
                            val args = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                            val d = Remind.parseDelay(args.optString("delay", "10"))
                            val txt = args.optString("text", "").ifBlank { "时间到了" }
                            if (d <= 0) "[参数错误] delay 写法要像 10m / 30s / 2h" else {
                                Remind.set(ctx, d, txt)
                                "已设提醒：${Remind.whenText(d)} · $txt"
                            }
                        }
                        tc.name == "draw" -> {
                            val args = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                            val prompt = args.optString("prompt", "")
                            val size = args.optString("size", "1024x1024")
                            onEvent(Event.Command("draw $prompt"))
                            val r = if (prompt.isBlank()) {
                                "[参数错误] 没有 prompt"
                            } else {
                                ImageGen.generate(ctx, prompt, ImageGen.keyOf(envStore.asEnv(), settings.baseUrl, settings.apiKey), size)
                                    .also { onEvent(Event.Output(it)) }
                            }
                            r
                        }
                        tc.name.startsWith("mcp__") -> {
                            onEvent(Event.Command("${tc.name} ${tc.arguments.take(300)}"))
                            val r = mcp.call(tc.name, tc.arguments)
                            onEvent(Event.Output(r))
                            r
                        }
                        cmd.isBlank() -> "[参数错误] 没有 command"
                        else -> {
                            onEvent(Event.Command(cmd))
                            val blocked = runBeforeHook(cmd)
                            val out = if (blocked != null) {
                                blocked
                            } else {
                                val (code, o) = Linux.run(ctx, cmd, 120, envStore.asEnv())
                                onEvent(Event.Output(o))
                                runAfterHook(cmd, code, o)
                                if (code == 0) o else "$o\n[exit=$code]"
                            }
                            out
                        }
                    }

                    val result = if (rgWarn.isBlank()) rawResult else "$rawResult\n$rgWarn"
                    history += JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", tc.id)
                        put("content", redact(result).take(20000))
                    }
                }
                continue
            }

            // 最终回答
            val ans = reply.text ?: "(空回复)"
            onEvent(Event.Answer(ans))
            runCatching { memory.append(userText, ans) }
            history += JSONObject().apply {
                put("role", "assistant")
                put("content", reply.text ?: "")
            }
            return
        }

        onEvent(Event.Error("超过 ${settings.maxTurns} 轮还没结束，停手了。可能模型在死循环。"))
    }

    // ---------------------------------------------------------------- 钩子

    private fun hooksDir(): File = File(ctx.filesDir, "hooks")

    /** 返回非 null 表示被拦截，内容是给模型的回执 */
    private fun runBeforeHook(cmd: String): String? {
        val f = File(hooksDir(), "before.sh")
        if (!f.exists()) return null
        // 用绝对路径调，免得 PATH 不对时连 sh 都找不到；钩子自身跑不起来就跳过，不拦命令
        val (code, out) = Linux.run(ctx, "/bin/sh /host/hooks/before.sh " + shellQuote(cmd), 30, envStore.asEnv())
        if (code == 127 || out.contains("not found")) return null
        return if (code == 0) null else "[钩子拦截 exit=$code] $out"
    }

    private fun runAfterHook(cmd: String, exit: Int, output: String) {
        val f = File(hooksDir(), "after.sh")
        if (!f.exists()) return
        Linux.run(
            ctx,
            "/bin/sh /host/hooks/after.sh ${shellQuote(cmd)} $exit ${shellQuote(output.take(2000))}",
            30,
            envStore.asEnv()
        )
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** 初始化目录结构：hooks / skills / memory 都建好，你往里丢文件就生效 */
    fun ensureDirs() {
        for (d in listOf("hooks", "skills", "memory")) File(ctx.filesDir, d).mkdirs()

        val before = File(hooksDir(), "before.sh")
        if (!before.exists()) {
            before.writeText(
                """#!/bin/sh
# 每条命令执行前调用，$1 = 要执行的命令
# 返回 0 = 放行；返回非 0 = 拦截，输出会回给模型
# 例：禁止 rm -rf
case "${'$'}1" in
  *"rm -rf /"*) echo "危险命令，已拦截"; exit 1 ;;
esac
exit 0
"""
            )
        }

        val after = File(hooksDir(), "after.sh")
        if (!after.exists()) {
            after.writeText(
                """#!/bin/sh
# 每条命令执行后调用
# $1 = 命令  $2 = 退出码  $3 = 输出片段
exit 0
"""
            )
        }

        val global = File(File(ctx.filesDir, "memory"), "GLOBAL.md")
        if (!global.exists()) {
            global.writeText("""# 全局记忆

这里写的东西会被读进上下文。你自己改，随时生效。
""")
        }
    }
}
