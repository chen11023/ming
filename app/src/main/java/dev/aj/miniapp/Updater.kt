package dev.aj.miniapp

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自更新：走 GitHub Releases（公开资源，不需要 token）。
 * CI 每次构建发一个 r<run_number> 的 release，asset 就是 APK。
 */
object Updater {

    data class Rel(val number: Int, val tag: String, val url: String, val size: Long, val page: String)

    /** 当前安装的构建号，取自 versionName 0.2.<n> */
    fun current(ctx: Context): Int =
        Diag.verName(ctx).substringAfterLast('.').toIntOrNull() ?: 0

    fun latest(): Rel? {
        var c: HttpURLConnection? = null
        return try {
            val u = URL("https://api.github.com/repos/chen11023/ming/releases/latest")
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "aj-mini")
            }
            c = conn
            if (conn.responseCode != 200) return null
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = o.optJSONArray("assets") ?: return null
            if (assets.length() == 0) return null
            var best: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (!a.optString("name").endsWith(".apk", true)) continue
                if (best == null || a.optLong("size") > best!!.optLong("size")) best = a
            }
            val a = best ?: return null
            val tag = o.optString("tag_name", "")
            Rel(
                number = tag.removePrefix("r").toIntOrNull() ?: 0,
                tag = tag,
                url = a.optString("browser_download_url", ""),
                size = a.optLong("size", 0L),
                page = o.optString("html_url", "")
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    /** 下载到 filesDir/update/，pct 回调 0–100 */
    fun download(url: String, dst: File, pct: (Int) -> Unit): Boolean {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        var c: HttpURLConnection? = null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "aj-mini")
            }
            c = conn
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != lastPct) {
                                lastPct = p
                                pct(p)
                            }
                        }
                    }
                }
            }
            dst.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    /** 唤起系统安装器；用户没给「安装未知应用」权限时会失败，交给调用方提示 */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
