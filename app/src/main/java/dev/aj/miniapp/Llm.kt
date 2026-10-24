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

    private fun active(): Profile? {
        val ps = profiles()
        val i = activeIndex
        return if (i in ps.indices) ps[i] else null
    }

    /** 改某项时，有配置档就写进当前档，没有就写老字段 */
    private fun writeActive(field: String, value: String) {
        val ps = profiles()
        val i = activeIndex
        if (i in ps.indices) {
            val p = ps[i]
            ps[i] = when (field) {
                "baseUrl" -> p.copy(baseUrl = value)
                "model" -> p.copy(model = value)
                else -> p.copy(keyCipher = value)
            }
            saveProfiles(ps)
        } else {
            sp.edit().putString(field, value).apply()
        }
    }

    var baseUrl: String
        get() = active()?.baseUrl?.takeIf { it.isNotBlank() }
            ?: (sp.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE)
        set(v) = writeActive("baseUrl", v.trim().trimEnd('/'))

    /** API Key 用 Keystore 加密后落盘（密文前缀 enc1:），磁盘上没有明文 */
    var apiKey: String
        get() = active()?.key?.takeIf { it.isNotBlank() }
            ?: Vault.decrypt(sp.getString("apiKey", "") ?: "")
        set(v) = writeActive("apiKey", Vault.encrypt(v.trim()))

    /** 启动时调用一次：把历史版本留下的明文 key 转成密文 */
    fun migratePlaintext() {
        val raw = sp.getString("apiKey", "") ?: ""
        if (raw.isNotEmpty() && raw.isNotBlank() && !Vault.isEncrypted(raw)) {
            val plain = raw
            sp.edit().putString("apiKey", Vault.encrypt(plain)).apply()
        }
    }

    var model: String
        get() = active()?.model?.takeIf { it.isNotBlank() }
            ?: (sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        set(v) = writeActive("model", v.trim())

    /** 当前生效配置的名字，用于界面显示 */
    val activeName: String
        get() = active()?.name ?: "默认"

    var systemPrompt: String
        get() = sp.getString("systemPrompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM
        set(v) = sp.edit().putString("systemPrompt", v).apply()

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
    fun chatStream(
        history: List<JSONObject>,
        extraTools: org.json.JSONArray? = null,
        onDelta: (String) -> Unit
    ): Reply {
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
    ).let { arr ->
        // 外部工具（MCP）追加进来
        if (extra != null) for (i in 0 until extra.length()) arr.put(extra.getJSONObject(i))
        arr
    }
}

private fun String.toRequestBody(ct: okhttp3.MediaType): okhttp3.RequestBody =
    okhttp3.RequestBody.create(ct, this)

