package dev.aj.miniapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用手机自带的 WebView（Chrome 内核）当浏览器：能跑 JS、能取正文、能截长图。
 * 不下载任何东西；必须在主线程建 WebView，所以这里 post 到主线程再等结果。
 */
object Browse {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** mode: "text" 取正文 / "shot" 截图存 /out */
    fun open(ctx: Context, url: String, mode: String = "text", timeoutMs: Long = 30000): String {
        val raw = url.trim()
        if (raw.isEmpty()) return "[参数错误] 没有 url"
        val u = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val shot = mode.equals("shot", true)
        val res = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)

        val finish: (String) -> Unit = { s ->
            if (done.compareAndSet(false, true)) {
                res[0] = s
                latch.countDown()
            }
        }

        Handler(Looper.getMainLooper()).post {
            var wv: WebView? = null
            try {
                wv = WebView(ctx.applicationContext)
                val v = wv
                v.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = UA
                }
                v.setInitialScale(100)
                v.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        // 给 JS 一点渲染时间再取，单页应用靠这个
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                if (shot) shotAndSave(ctx, view, u, finish)
                                else readText(view, u, finish)
                            } catch (t: Throwable) {
                                finish("[失败] " + (t.message ?: t.javaClass.simpleName))
                            }
                        }, 1500)
                    }

                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame)
                            finish("[打不开] ${err.errorCode} ${err.description} · $u")
                    }
                }
                v.loadUrl(u)
                Handler(Looper.getMainLooper()).postDelayed({
                    finish("[超时] ${timeoutMs / 1000} 秒没加载完 · $u")
                }, timeoutMs)
            } catch (t: Throwable) {
                finish("[失败] " + (t.message ?: t.javaClass.simpleName))
            }
        }

        latch.await(timeoutMs + 8000, TimeUnit.MILLISECONDS)
        return res[0] ?: "[超时] 浏览器没有返回结果"
    }

    /** 取标题 + 正文，压一压空白 */
    private fun readText(view: WebView, url: String, finish: (String) -> Unit) {
        view.evaluateJavascript(
            "(function(){var t=document.title||'';var b=document.body?document.body.innerText:'';" +
                "b=b.replace(/\\n{3,}/g,'\\n\\n').trim();return t+'\\u0001'+b;})()"
        ) { r ->
            val s = dequote(r)
            val cut = s.indexOf('\u0001')
            val title = if (cut > 0) s.substring(0, cut) else ""
            val body = if (cut >= 0) s.substring(cut + 1) else s
            if (body.isBlank() && title.isBlank()) {
                finish("[空页面] 没取到正文（可能要登录，或被反爬拦了）· $url")
            } else {
                finish("[$title]($url)\n\n" + body.take(12000))
            }
        }
    }

    /** 整页截图存 /out，返回路径；页面没渲染出来（全白）会明说 */
    private fun shotAndSave(ctx: Context, view: WebView, url: String, finish: (String) -> Unit) {
        try {
            val w = 1080
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val h = view.measuredHeight.coerceIn(600, 6000)
            view.layout(0, 0, view.measuredWidth.coerceAtLeast(w), h)
            val bmp = Bitmap.createBitmap(view.measuredWidth.coerceAtLeast(w), h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val dir = File(ctx.filesDir, "out").apply { mkdirs() }
            val f = File(dir, "browse-" + System.currentTimeMillis() + ".png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
            bmp.recycle()
            if (f.length() < 4000) finish("[截图可能是空白] 页面没渲染出来（后台截图需要窗口）· $url · 存到 ${f.absolutePath}")
            else finish("已截图：${f.absolutePath}（$url）")
        } catch (t: Throwable) {
            finish("[截图失败] " + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** evaluateJavascript 回的是 JSON 字符串字面量，去掉外层引号和转义 */
    private fun dequote(r: String?): String {
        if (r.isNullOrBlank() || r == "null") return ""
        return runCatching {
            val o = JSONTokener(r).nextValue()
            if (o is String) o else r
        }.getOrDefault(r.trim('"'))
    }
}
