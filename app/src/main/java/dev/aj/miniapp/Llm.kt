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

    var baseUrl: String
        get() = sp.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = sp.edit().putString("baseUrl", v.trim().trimEnd('/')).apply()

    /** API Key 用 Keystore 加密后落盘（密文前缀 enc1:），磁盘上没有明文 */
    var apiKey: String
        get() = Vault.decrypt(sp.getString("apiKey", "") ?: "")
        set(v) = sp.edit().putString("apiKey", Vault.encrypt(v.trim())).apply()

    /** 启动时调用一次：把历史版本留下的明文 key 转成密文 */
    fun migratePlaintext() {
        val raw = sp.getString("apiKey", "") ?: ""
        if (raw.isNotEmpty() && raw.isNotBlank() && !Vault.isEncrypted(raw)) {
            val plain = raw
            sp.edit().putString("apiKey", Vault.encrypt(plain)).apply()
        }
    }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var systemPrompt: String
        get() = sp.getString("systemPrompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM
        set(v) = sp.edit().putString("systemPrompt", v).apply()

    /** 工具循环最多跑几轮，防止模型死循环 */
    var maxTurns: Int
        get() = sp.getInt("maxTurns", 8)
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
    fun chat(history: List<JSONObject>): Reply {
        if (!settings.configured) {
            return Reply(null, emptyList(), "还没填 API key，去设置页填一下")
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", org.json.JSONArray(history))
            put("temperature", 0.7)
            put("tools", buildTools())
        }

        val req = okhttp3.Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toOkMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Reply(null, emptyList(), "HTTP ${resp.code}: ${raw.take(400)}")
                }
                parse(raw)
            }
        } catch (e: Exception) {
            Reply(null, emptyList(), "${e.javaClass.simpleName}: ${e.message}")
        }
    }

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
    private fun buildTools() = org.json.JSONArray().put(
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
    )
}

private fun String.toRequestBody(ct: okhttp3.MediaType): okhttp3.RequestBody =
    okhttp3.RequestBody.create(ct, this)

