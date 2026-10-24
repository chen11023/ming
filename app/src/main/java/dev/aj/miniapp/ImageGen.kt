package dev.aj.miniapp

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 出图：智谱 cogview-3-flash（免费额度），跟 Minis 那边同一个模型、同一个接口。
 * key 从「设置 → 环境变量」里的 ZHIPU_API_KEY 取（也可以 ZHIPU）。
 * 生成的图落到 /out（= filesDir/out，proot 里是 /out），聊天里自动回显。
 */
object ImageGen {
    private const val BASE = "https://open.bigmodel.cn/api/paas/v4"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * 找 key：先看环境变量，再退到「当前模型配置」里的密钥 ——
     * 只在模型页填了智谱 key 也能直接出图，不用再抄一份到环境变量。
     */
    fun keyOf(env: Map<String, String>, baseUrl: String = "", profileKey: String = ""): String {
        for (n in listOf("ZHIPU_API_KEY", "ZHIPU", "ZHIPU_KEY")) {
            val v = env[n]
            if (!v.isNullOrBlank()) return v.trim()
        }
        val b = baseUrl.lowercase()
        if ((b.contains("bigmodel") || b.contains("zhipu")) && profileKey.isNotBlank()) {
            return profileKey.trim()
        }
        return ""
    }

    /** 返回给模型看的一句话；成功时带上落盘路径 */
    fun generate(ctx: Context, prompt: String, key: String, size: String = "1024x1024"): String {
        if (prompt.isBlank()) return "[出图失败] 没给描述"
        if (key.isBlank()) {
            return "[出图失败] 没配 ZHIPU_API_KEY。去「设置 → 环境变量」加一条 ZHIPU_API_KEY" +
                "（智谱开放平台 open.bigmodel.cn 免费领，cogview-3-flash 不收钱），加完再说一次。"
        }
        val body = JSONObject().apply {
            put("model", "cogview-3-flash")
            put("prompt", prompt)
            put("size", size)
        }
        val req = Request.Builder()
            .url("$BASE/images/generations")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "[出图失败] HTTP ${resp.code}: ${txt.take(300)}"
                val item = JSONObject(txt).optJSONArray("data")?.optJSONObject(0)
                    ?: return "[出图失败] 返回里没有 data: ${txt.take(300)}"
                val ts = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
                val out = File(Linux.outDir(ctx), "draw_$ts.png")
                val url = item.optString("url", "")
                val b64 = item.optString("b64_json", "")
                when {
                    url.isNotBlank() -> {
                        val imgReq = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                        http.newCall(imgReq).execute().use { ir ->
                            if (!ir.isSuccessful) return "[出图失败] 下载图片 HTTP ${ir.code}"
                            out.writeBytes(ir.body?.bytes() ?: ByteArray(0))
                        }
                    }
                    b64.isNotBlank() -> out.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                    else -> return "[出图失败] 返回里既没有 url 也没有 b64_json"
                }
                if (!out.exists() || out.length() == 0L) return "[出图失败] 图片没落盘"
                "[图片已生成] ${out.absolutePath}（${out.length() / 1024} KB）—— 已经在聊天里显示了，别再重复画同一张。"
            }
        } catch (e: Exception) {
            "[出图失败] ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
