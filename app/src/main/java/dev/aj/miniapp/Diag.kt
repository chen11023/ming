package dev.aj.miniapp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断：崩溃留痕 + 日志导出 + 版本检查。
 * 崩溃只落本地，要不要发出去由人决定。
 */
object Diag {

    private var installed = false

    fun logsDir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun verName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 崩了就落一个文件，再交回原来的处理器（系统照常弹「已停止」） */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(logsDir(ctx), "crash-" + stamp() + ".txt").writeText(
                    buildString {
                        append("时间: ").append(stamp()).append('\n')
                        append("版本: ").append(verName(ctx)).append('\n')
                        append("设备: ").append(android.os.Build.MANUFACTURER).append(' ')
                            .append(android.os.Build.MODEL)
                            .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
                            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
                        append("线程: ").append(t.name).append('\n')
                        append("----\n")
                        append(android.util.Log.getStackTraceString(e))
                    }
                )
                prune(ctx)
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun prune(ctx: Context) {
        logsDir(ctx).listFiles()?.sortedByDescending { it.name }?.drop(10)
            ?.forEach { runCatching { it.delete() } }
    }

    fun crashCount(ctx: Context): Int =
        logsDir(ctx).listFiles()?.count { it.name.startsWith("crash-") } ?: 0

    /** 最近一次崩溃的尾部几行，设置页里预览 */
    fun lastCrashTail(ctx: Context, lines: Int = 12): String {
        val f = logsDir(ctx).listFiles()
            ?.filter { it.name.startsWith("crash-") }
            ?.maxByOrNull { it.name } ?: return ""
        return runCatching { f.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }

    fun exportText(ctx: Context): String = buildString {
        append("AJ Mini 诊断包\n")
        append("版本: ").append(verName(ctx)).append('\n')
        append("设备: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL)
            .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n')
        append("时间: ").append(stamp()).append("\n\n")
        val fs = logsDir(ctx).listFiles()?.sortedBy { it.name } ?: emptyList()
        if (fs.isEmpty()) append("（没有崩溃记录）\n")
        for (f in fs) {
            append("==== ").append(f.name).append(" ====\n")
            append(runCatching { f.readText() }.getOrDefault("(读不到)"))
            append("\n\n")
        }
    }

    class Build(val number: Int, val at: String, val url: String)

    /** 仓库最新一次成功构建；网络不通返回 null（不抛异常） */
    fun latestRun(): Build? {
        var c: HttpURLConnection? = null
        return try {
            val u = URL("https://api.github.com/repos/chen11023/ming/actions/runs?status=success&per_page=1")
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "aj-mini")
            }
            c = conn
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().readText()
            val runs = JSONObject(txt).optJSONArray("workflow_runs") ?: return null
            if (runs.length() == 0) return null
            val r = runs.getJSONObject(0)
            Build(
                r.optInt("run_number", 0),
                r.optString("created_at", "").replace("T", " ").replace("Z", ""),
                r.optString("html_url", "")
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { c?.disconnect() }
        }
    }
}
