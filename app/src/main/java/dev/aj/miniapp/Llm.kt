package dev.aj.miniapp

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType as toOkMediaType
import org.json.JSONObject

/**
 * 设置持久化。第一版用明文 SharedPreferences —— key 只在本机 App 私有目录，
 * 不写日志、不进崩溃上报。要加密以后换 EncryptedSharedPreferences 即可。
 */
class Settings(ctx: Context) {

    private val sp = ctx.getSharedPreferences("aj", Context.MODE_PRIVATE)

    /** 一套模型配置：名字 + 接口地址 + 模型名 + 加密后的 key */
    data class Profile(val name: String, val baseUrl: String, val model: String, val keyCipher: String) {
        val key: String get() = Vault.decrypt(keyCipher)
    }

    /** 这套配置是不是试过不支持流式（智谱 1212 那种），试过就整段返回，别再撞一次 */
    fun noStream(baseUrl: String, model: String): Boolean =
        sp.getBoolean("noStream:$baseUrl|$model", false)

    fun markNoStream(baseUrl: String, model: String) {
        sp.edit().putBoolean("noStream:$baseUrl|$model", true).apply()
    }

    fun profiles(): MutableList<Profile> {
        val raw = sp.getString("profiles", "") ?: ""
        val out = mutableListOf<Profile>()
        if (raw.isBlank()) return out
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Profile(
                    o.optString("name", "配置$i"),
                    o.optString("baseUrl", DEFAULT_BASE),
                    o.optString("model", DEFAULT_MODEL),
                    o.optString("key", "")
                )
            }
        }
        return out
    }

    fun saveProfiles(list: List<Profile>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("model", p.model)
                put("key", p.keyCipher)
            })
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    var activeIndex: Int
        get() = sp.getInt("active", 0)
        set(v) = sp.edit().putInt("active", v).apply()

    // ------------------------------------------------ 服务商（地址+key）与其下的模型
    /** 服务商：一个接口地址 + 一个 key，下面挂多个模型（模型 = 以前的一个"配置"） */
    data class Provider(
        val name: String,
        val baseUrl: String,
        val keyCipher: String,
        val models: MutableList<String>
    ) {
        val key: String get() = Vault.decrypt(keyCipher)
    }

    fun providers(): MutableList<Provider> {
        val raw = sp.getString("providers", "") ?: ""
        if (raw.isNotBlank()) {
            val out = mutableListOf<Provider>()
            runCatching {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ms = mutableListOf<String>()
                    o.optJSONArray("models")?.let { a ->
                        for (k in 0 until a.length()) {
                            val m = a.optString(k)
                            if (m.isNotBlank()) ms += m
                        }
                    }
                    out += Provider(
                        o.optString("name", "服务商$i"),
                        o.optString("baseUrl", DEFAULT_BASE),
                        o.optString("key", ""),
                        ms
                    )
                }
            }
            return out
        }
        return migrateProfiles()
    }

    /** 老版本的 profiles：同地址同 key 的合成一个服务商，模型并成列表。只跑一次 */
    private fun migrateProfiles(): MutableList<Provider> {
        val out = mutableListOf<Provider>()
        val ps = profiles()
        if (ps.isEmpty()) return out
        val actModel = ps.getOrNull(activeIndex)?.model ?: ""
        for (p in ps) {
            val ex = out.firstOrNull { it.baseUrl == p.baseUrl && it.keyCipher == p.keyCipher }
            if (ex != null) {
                if (!ex.models.contains(p.model)) ex.models += p.model
            } else {
                out += Provider(p.name, p.baseUrl, p.keyCipher, mutableListOf(p.model))
            }
        }
        saveProviders(out)
        val pi = out.indexOfFirst { it.models.contains(actModel) }.coerceAtLeast(0)
        sp.edit().putInt("active", pi).putString("activeModel", actModel).apply()
        return out
    }

    fun saveProviders(list: List<Provider>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            val ms = org.json.JSONArray()
            p.models.forEach { ms.put(it) }
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("key", p.keyCipher)
                put("models", ms)
            })
        }
        sp.edit().putString("providers", arr.toString()).apply()
    }

    fun currentProvider(): Provider? {
        val ps = providers()
        val i = activeIndex
        return if (i in ps.indices) ps[i] else ps.firstOrNull()
    }

    /** 当前在用哪个模型名 */
    var activeModelName: String
        get() = sp.getString("activeModel", "") ?: ""
        set(v) = sp.edit().putString("activeModel", v).apply()

    fun pickModel(pi: Int, m: String) {
        sp.edit().putInt("active", pi).putString("activeModel", m).apply()
    }

    /** 新增服务商：地址和 key 只填这一次，以后加模型不用再填 */
    fun addProvider(name: String, url: String, key: String) {
        val ps = providers()
        val u = url.trim().trimEnd('/')
        ps += Provider(
            name.ifBlank { "服务商${ps.size + 1}" },
            if (u.isBlank()) DEFAULT_BASE else u,
            Vault.encrypt(key.trim()),
            mutableListOf()
        )
        saveProviders(ps)
        sp.edit().putInt("active", ps.size - 1).putString("activeModel", "").apply()
    }

    fun delProvider(i: Int) {
        val ps = providers()
        if (i !in ps.indices) return
        ps.removeAt(i)
        saveProviders(ps)
        if (activeIndex >= ps.size) {
            sp.edit().putInt("active", (ps.size - 1).coerceAtLeast(0)).apply()
        }
    }

    /** 往当前服务商下加一个模型，并立刻切过去 */
    fun addModel(m: String) {
        val ps = providers()
        val i = activeIndex
        if (i !in ps.indices || m.isBlank()) return
        val mm = m.trim()
        if (!ps[i].models.contains(mm)) ps[i].models += mm
        saveProviders(ps)
        activeModelName = mm
    }

    fun delModel(m: String) {
        val ps = providers()
        val i = activeIndex
        if (i !in ps.indices) return
        ps[i].models.remove(m)
        saveProviders(ps)
        if (activeModelName == m) activeModelName = ps[i].models.firstOrNull() ?: ""
    }

    /** 启动时调用一次：把历史版本留下的明文 key 转成密文 */
    fun migratePlaintext() {
        val raw = sp.getString("apiKey", "") ?: ""
        if (raw.isNotEmpty() && raw.isNotBlank() && !Vault.isEncrypted(raw)) {
            val plain = raw
            sp.edit().putString("apiKey", Vault.encrypt(plain)).apply()
        }
    }

    var baseUrl: String
        get() = currentProvider()?.baseUrl?.takeIf { it.isNotBlank() }
            ?: (sp.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE)
        set(v) {
            val ps = providers()
            val i = activeIndex
            if (i in ps.indices) {
                ps[i] = ps[i].copy(baseUrl = v.trim().trimEnd('/'))
                saveProviders(ps)
            }
        }

    /** API Key 用 Keystore 加密后落盘（密文前缀 enc1:），磁盘上没有明文 */
    var apiKey: String
        get() = currentProvider()?.key?.takeIf { it.isNotBlank() }
            ?: Vault.decrypt(sp.getString("apiKey", "") ?: "")
        set(v) {
            val ps = providers()
            val i = activeIndex
            if (i in ps.indices) {
                ps[i] = ps[i].copy(keyCipher = Vault.encrypt(v.trim()))
                saveProviders(ps)
            }
        }

    var model: String
        get() {
            val p = currentProvider() ?: return sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
            val n = activeModelName
            if (n.isNotBlank() && p.models.contains(n)) return n
            return p.models.firstOrNull() ?: (sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        }
        set(v) {
            val ps = providers()
            val i = activeIndex
            if (i !in ps.indices || v.isBlank()) return
            if (!ps[i].models.contains(v.trim())) ps[i].models += v.trim()
            saveProviders(ps)
            activeModelName = v.trim()
        }

    /** 当前生效配置的名字，用于界面显示 */
    val activeName: String
        get() = currentProvider()?.name ?: "默认"

    var systemPrompt: String
        get() = sp.getString("systemPrompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM
        set(v) = sp.edit().putString("systemPrompt", v).apply()

    /** 执行命令 / 出图前弹确认卡（默认开） */
    var confirmExec: Boolean
        get() = sp.getBoolean("confirmExec", true)
        set(v) = sp.edit().putBoolean("confirmExec", v).apply()

    /** 深色模式 */
    var dark: Boolean
        get() = sp.getBoolean("dark", false)
        set(v) = sp.edit().putBoolean("dark", v).apply()

    /** 聊天字号 */
    var chatFont: Float
        get() = sp.getFloat("chatFont", 15f)
        set(v) = sp.edit().putFloat("chatFont", v.coerceIn(11f, 26f)).apply()

    /** 回答自动朗读 */
    var speakReplies: Boolean
        get() = sp.getBoolean("speak", false)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    /** 完成时发系统通知 */
    var notifyDone: Boolean
        get() = sp.getBoolean("notifyDone", true)
        set(v) = sp.edit().putBoolean("notifyDone", v).apply()

    // ------------------------------------------------ 用量（累计，跨会话）
    fun usageAdd(calls: Int, chars: Long, ms: Long) {
        sp.edit()
            .putInt("u.calls", sp.getInt("u.calls", 0) + calls)
            .putLong("u.chars", sp.getLong("u.chars", 0L) + chars)
            .putLong("u.ms", sp.getLong("u.ms", 0L) + ms)
            .apply()
    }

    val usageCalls: Int get() = sp.getInt("u.calls", 0)
    val usageChars: Long get() = sp.getLong("u.chars", 0L)
    val usageMs: Long get() = sp.getLong("u.ms", 0L)

    fun usageReset() {
        sp.edit().remove("u.calls").remove("u.chars").remove("u.ms")
            .remove("u.byModel").apply()
    }

    /** 按「服务商 · 模型」分开记，看是谁在吃额度 */
    fun usageModelAdd(label: String, calls: Int, chars: Long, ms: Long) {
        if (label.isBlank() || calls <= 0) return
        val all = runCatching { org.json.JSONObject(sp.getString("u.byModel", "{}")) }
            .getOrElse { org.json.JSONObject() }
        val one = all.optJSONObject(label) ?: org.json.JSONObject()
        one.put("c", one.optInt("c") + calls)
        one.put("ch", one.optLong("ch") + chars)
        one.put("ms", one.optLong("ms") + ms)
        all.put(label, one)
        sp.edit().putString("u.byModel", all.toString()).apply()
    }

    /** (标签, 次数, 字符数)，按次数从多到少 */
    fun usageModels(): List<Triple<String, Int, Long>> {
        val all = runCatching { org.json.JSONObject(sp.getString("u.byModel", "{}")) }
            .getOrElse { org.json.JSONObject() }
        val out = mutableListOf<Triple<String, Int, Long>>()
        for (k in all.keys()) {
            val o = all.optJSONObject(k) ?: continue
            out += Triple(k, o.optInt("c"), o.optLong("ch"))
        }
        out.sortByDescending { it.second }
        return out
    }

    /** 当前「服务商 · 模型」标签，用量和顶栏都用它 */
    fun modelLabel(): String {
        val p = currentProvider()?.name ?: "未配置"
        val m = activeModelName.ifBlank { "未选模型" }
        return "$p · $m"
    }

    // ------------------------------------------------ 配置导出 / 导入（换机、重装用）
    /** 导出成一段 JSON。含明文 key（换机后也能用），文件请自己保管 */
    fun exportConfig(): String {
        val o = org.json.JSONObject()
        o.put("app", "aj-mini")
        o.put("ver", 1)
        o.put("systemPrompt", systemPrompt)
        o.put("maxTurns", maxTurns)
        o.put("confirmExec", confirmExec)
        o.put("dark", dark)
        o.put("chatFont", chatFont.toDouble())
        o.put("speakReplies", speakReplies)
        o.put("notifyDone", notifyDone)
        val arr = org.json.JSONArray()
        for (p in providers()) {
            val ms = org.json.JSONArray()
            p.models.forEach { ms.put(it) }
            arr.put(org.json.JSONObject().apply {
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("key", runCatching { p.key }.getOrDefault(""))
                put("models", ms)
            })
        }
        o.put("providers", arr)
        o.put("active", activeIndex)
        o.put("activeModel", activeModelName)
        return o.toString(2)
    }

    /** 导入：配置档整体替换，其它开关覆盖。返回一句人话 */
    fun importConfig(text: String): String {
        val o = org.json.JSONObject(text)
        // 新格式：providers（服务商 + 模型列表）；老文件是 profiles，这里一起认
        val arr = o.optJSONArray("providers")
        if (arr != null) {
            val list = mutableListOf<Provider>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val ms = mutableListOf<String>()
                p.optJSONArray("models")?.let { a ->
                    for (k in 0 until a.length()) {
                        val m = a.optString(k)
                        if (m.isNotBlank()) ms += m
                    }
                }
                list += Provider(
                    p.optString("name", "服务商${i + 1}"),
                    p.optString("baseUrl", DEFAULT_BASE),
                    Vault.encrypt(p.optString("key", "")),
                    ms
                )
            }
            if (list.isNotEmpty()) {
                saveProviders(list)
                sp.edit()
                    .putInt("active", o.optInt("active", 0).coerceIn(0, list.size - 1))
                    .putString("activeModel", o.optString("activeModel", ""))
                    .apply()
            }
        } else {
            val parr = o.optJSONArray("profiles") ?: org.json.JSONArray()
            val old = mutableListOf<Profile>()
            for (i in 0 until parr.length()) {
                val p = parr.getJSONObject(i)
                old += Profile(
                    p.optString("name", "配置${i + 1}"),
                    p.optString("baseUrl", DEFAULT_BASE),
                    p.optString("model", DEFAULT_MODEL),
                    Vault.encrypt(p.optString("key", ""))
                )
            }
            if (old.isNotEmpty()) {
                saveProfiles(old)
                sp.edit().remove("providers").apply()
                activeIndex = o.optInt("active", 0).coerceIn(0, old.size - 1)
            }
        }
        if (o.has("systemPrompt")) systemPrompt = o.optString("systemPrompt", DEFAULT_SYSTEM)
        if (o.has("maxTurns")) maxTurns = o.optInt("maxTurns", maxTurns)
        confirmExec = o.optBoolean("confirmExec", confirmExec)
        dark = o.optBoolean("dark", dark)
        speakReplies = o.optBoolean("speakReplies", speakReplies)
        notifyDone = o.optBoolean("notifyDone", notifyDone)
        if (o.has("chatFont")) chatFont = o.optDouble("chatFont", 15.0).toFloat()
        return "导入 ${providers().size} 个服务商"
    }

    /** 工具循环最多跑几轮，防止模型死循环 */
    var maxTurns: Int
        get() = sp.getInt("maxTurns", 12)
        set(v) = sp.edit().putInt("maxTurns", v.coerceIn(1, 30)).apply()

    val configured: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        // 深度求索默认值
        const val DEFAULT_BASE = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        const val DEFAULT_SYSTEM = """你是 AJ，跑在一台 Android 手机上的私人助手。

【能做什么】
你有一条真 Linux（Alpine）可以用，通过 sh 工具执行命令：
- 以 root 身份运行，能装包（apk add）、读写文件、跑脚本
- 工作目录 /root，宿主目录挂在 /host（App 私有目录）
- 系统是 Alpine，用 apk 而不是 apt

【怎么干活】
需要看文件、跑命令、装东西时，直接调用 sh 工具，一次一条命令。
拿到结果再决定下一步，别猜。

【说话方式】
- 简洁，像同事回消息，别写报告
- 不要复述命令输出，直接给结论
- 不确定的事先跑命令查，不要编
"""
    }
}

/**
 * 极简 DeepSeek / OpenAI 兼容客户端。
 * 不引入任何 provider 路由，就是一个 POST /chat/completions。
 */
class LlmClient(private val settings: Settings) {

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class ToolCall(val id: String, val name: String, val arguments: String)

    data class Reply(
        val text: String?,
        val toolCalls: List<ToolCall>,
        val error: String? = null
    ) {
        val hasTool: Boolean get() = toolCalls.isNotEmpty()
    }

    /** 消息历史用 JSONObject 列表表示，直接透传回接口 */
    fun chat(history: List<JSONObject>, extraTools: org.json.JSONArray? = null): Reply {
        if (!settings.configured) {
            return Reply(null, emptyList(), "还没填 API key，去设置页填一下")
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", org.json.JSONArray(history))
            put("temperature", 0.7)
            put("tools", buildTools(extraTools))
        }

        val req = okhttp3.Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toOkMediaType()))
            .build()

        return try {
            val c = http.newCall(req)
            call = c
            c.execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Reply(null, emptyList(), "HTTP ${resp.code}: ${raw.take(400)}")
                }
                parse(raw)
            }
        } catch (e: Exception) {
            Reply(null, emptyList(), if (cancelled) "已停止" else "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            call = null
        }
    }

    /**
     * 流式版本：边收边把增量文字吐给 onDelta，tool_calls 按 index 拼完再返回。
     * 服务端不认 stream 时自动退回普通响应。
     */
    private fun noStreamFor(): Boolean = settings.noStream(settings.baseUrl, settings.model)

    private fun markNoStream() = settings.markNoStream(settings.baseUrl, settings.model)

    fun chatStream(
        history: List<JSONObject>,
        extraTools: org.json.JSONArray? = null,
        onDelta: (String) -> Unit
    ): Reply {
        // 有的模型（智谱部分型号）不认 stream:true，记住一次，以后这套配置直接整段返回
        if (noStreamFor()) {
            val r = chat(history, extraTools)
            r.text?.let { onDelta(it) }
            return r
        }
        if (!settings.configured) {
            return Reply(null, emptyList(), "还没填 API key，去设置页填一下")
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", org.json.JSONArray(history))
            put("temperature", 0.7)
            put("tools", buildTools(extraTools))
            put("stream", true)
        }

        val req = okhttp3.Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toOkMediaType()))
            .build()

        return try {
            val c = http.newCall(req)
            call = c
            c.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    if (resp.code == 400 && (raw.contains("1212") || raw.contains("SSE"))) {
                        markNoStream()
                        val r = chat(history, extraTools)
                        r.text?.let { onDelta(it) }
                        return r
                    }
                    return Reply(null, emptyList(), "HTTP ${resp.code}: ${raw.take(400)}")
                }
                val ct = resp.header("Content-Type").orEmpty()
                val src = resp.body?.source() ?: return Reply(null, emptyList(), "空响应")
                if (!ct.contains("event-stream")) {
                    return parse(src.readUtf8())
                }

                val text = StringBuilder()
                val slots = linkedMapOf<Int, Array<StringBuilder>>()
                while (true) {
                    if (cancelled) break
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val ch = o.optJSONArray("choices") ?: continue
                    if (ch.length() == 0) continue
                    val delta = ch.getJSONObject(0).optJSONObject("delta") ?: continue

                    if (delta.has("content") && !delta.isNull("content")) {
                        val d = delta.optString("content", "")
                        if (d.isNotEmpty()) {
                            text.append(d)
                            onDelta(d)
                        }
                    }
                    val tcs = delta.optJSONArray("tool_calls")
                    if (tcs != null) {
                        for (i in 0 until tcs.length()) {
                            val t = tcs.getJSONObject(i)
                            val idx = t.optInt("index", i)
                            val slot = slots.getOrPut(idx) {
                                arrayOf(StringBuilder(), StringBuilder(), StringBuilder())
                            }
                            if (t.has("id")) slot[0].append(t.optString("id", ""))
                            val fn = t.optJSONObject("function") ?: continue
                            if (fn.has("name")) slot[1].append(fn.optString("name", ""))
                            if (fn.has("arguments")) slot[2].append(fn.optString("arguments", ""))
                        }
                    }
                }

                val calls = slots.toSortedMap().values.mapIndexed { i, s ->
                    ToolCall(
                        s[0].toString().ifBlank { "call_${System.currentTimeMillis()}_$i" },
                        s[1].toString(),
                        s[2].toString().ifBlank { "{}" }
                    )
                }.filter { it.name.isNotBlank() }

                if (cancelled) return Reply(text.toString().ifBlank { null }, calls, "已停止")
                Reply(text.toString().ifBlank { null }, calls)
            }
        } catch (e: Exception) {
            Reply(null, emptyList(), if (cancelled) "已停止" else "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            call = null
        }
    }

    private var call: okhttp3.Call? = null

    @Volatile
    private var cancelled = false

    /** 停：掐掉在飞的请求，循环里会看到 cancelled */
    fun cancel() {
        cancelled = true
        runCatching { call?.cancel() }
    }

    fun resetCancel() {
        cancelled = false
    }

    val isCancelled: Boolean get() = cancelled

    private fun parse(raw: String): Reply {
        return try {
            val choice = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            val msg = choice.getJSONObject("message")
            val text = if (msg.has("content") && !msg.isNull("content"))
                msg.getString("content") else null

            val calls = mutableListOf<ToolCall>()
            if (msg.has("tool_calls") && !msg.isNull("tool_calls")) {
                val arr = msg.getJSONArray("tool_calls")
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val fn = c.getJSONObject("function")
                    calls += ToolCall(
                        c.optString("id", "call_$i"),
                        fn.getString("name"),
                        fn.optString("arguments", "{}")
                    )
                }
            }
            Reply(text, calls)
        } catch (e: Exception) {
            Reply(null, emptyList(), "解析响应失败: ${e.message}\n原文: ${raw.take(300)}")
        }
    }

    /** 只有一个工具：跑 shell 命令 */
    private fun buildTools(extra: org.json.JSONArray? = null) = org.json.JSONArray().put(
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "sh")
                put("description", "在 Alpine Linux 里执行一条 shell 命令，返回 stdout+stderr。工作目录默认 /root，宿主目录在 /host。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "要执行的 shell 命令，单条")
                        })
                    })
                    put("required", org.json.JSONArray().put("command"))
                })
            })
        }
    ).put(
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "draw")
                put(
                    "description",
                    "根据文字描述画一张图，生成后自动显示在聊天里、保存到 /out。用户说画图、来一张、生成图片、配图时用它。" +
                        "描述要具体（主体、风格、光线、构图）；一次只画一张，不要连续画多张。"
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().apply {
                            put("type", "string")
                            put("description", "画面描述，中文即可，越具体越好")
                        })
                        put("size", JSONObject().apply {
                            put("type", "string")
                            put("description", "尺寸，默认 1024x1024，可选 768x1344（竖）、1344x768（横）")
                        })
                    })
                    put("required", org.json.JSONArray().put("prompt"))
                })
            })
        }
    ).put(
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "remind")
                put(
                    "description",
                    "设一个本地定时提醒：到点由系统发通知，不需要 App 开着，也不花钱。"
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("delay", JSONObject().apply {
                            put("type", "string")
                            put("description", "多久之后提醒，写法 10m / 30s / 2h / 10（默认分钟）")
                        })
                        put("text", JSONObject().apply {
                            put("type", "string")
                            put("description", "提醒内容，如「喝水」「开会」")
                        })
                    })
                    put("required", org.json.JSONArray().put("delay").put("text"))
                })
            })
        }
    ).let { arr ->
        // 外部工具（MCP）追加进来
        if (extra != null) for (i in 0 until extra.length()) arr.put(extra.getJSONObject(i))
        arr
    }
}

private fun String.toRequestBody(ct: okhttp3.MediaType): okhttp3.RequestBody =
    okhttp3.RequestBody.create(ct, this)

