package dev.aj.miniapp

import android.content.Context
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网页子 AJ：把 broker.py + server.py + index.html 放进 App 私有目录，
 * 在 rootfs 里跑起来，局域网内的电脑/平板用浏览器就能用。
 *
 * key 只存在 /host/web/.env（600），由 App 从自己的配置解密后写；网页和模型都看不到。
 */
object WebAj {

    private val ASSETS = listOf("ajweb/broker.py", "ajweb/server.py", "ajweb/index.html")

    fun dir(ctx: Context) = File(ctx.filesDir, "web").apply { mkdirs() }

    /** 子 AJ 每轮把真实结果追加进 health-report.jsonl，这里收回来更新父的唯一台账 */
    fun absorbReport(ctx: Context) {
        runCatching {
            val st = Settings(ctx)
            val f = File(dir(ctx), "health-report.jsonl")
            if (!f.exists() || f.length() == 0L) return@runCatching
            val lines = f.readLines()
            for (ln in lines) {
                val o = org.json.JSONObject(ln)
                val name = o.optString("name", "")
                if (name.isBlank()) continue
                if (o.optBoolean("ok", false)) {
                    RouteBrain.markOk(st, name, "")
                } else {
                    RouteBrain.markFail(st, name, "", o.optString("err", ""))
                }
            }
            f.delete()
        }
    }

    /** 把资源里的脚本铺到 files/web（每次覆盖，保证跟 App 版本一致） */
    fun ensure(ctx: Context): String {
        val d = dir(ctx)
        for (a in ASSETS) {
            val name = a.substringAfterLast('/')
            runCatching {
                ctx.assets.open(a).use { input ->
                    File(d, name).outputStream().use { out -> input.copyTo(out) }
                }
            }.onFailure { return "铺文件失败：${it.message}" }
        }
        return "ok"
    }

    /** 用 App 自己的服务商配置生成 .env（600）。网页端和模型都拿不到 key。 */
    fun writeEnv(ctx: Context): String {
        val st = Settings(ctx)
        val base = st.baseUrl.ifBlank { "" }
        val model = st.model.ifBlank { "" }
        val key = st.apiKey.ifBlank { "" }
        if (key.isBlank()) return "当前服务商没填 key，网页版跑不起来"
        var token = st.webToken
        if (token.isBlank()) {
            token = randomToken()
            st.webToken = token
        }
        val port = st.webPort
        val f = File(dir(ctx), ".env")
        f.writeText(
            "AJ_BASE=$base\n", Charsets.UTF_8
        )
        f.appendText("AJ_MODEL=$model\n")
        // 降级链直接用 App 里这个服务商已配好的模型列表，用户加一个模型就自动带上，不用手改任何文件
        val chain = runCatching {
            st.providers().firstOrNull { it.baseUrl == base }?.models
                ?.filter { it.isNotBlank() }
                ?.joinToString(",") ?: ""
        }.getOrDefault("")
        f.appendText("AJ_MODELS=" + (chain.ifBlank { model }) + "\n")
        f.appendText("AJ_KEY=$key\n")
        f.appendText("AJ_WEB_PORT=$port\n")
        f.appendText("AJ_WEB_TOKEN=$token\n")
        f.appendText("AJ_BROKER=http://127.0.0.1:8788\n")
        f.appendText("AJ_OUT=/host/out\n")
        f.appendText("AJ_HOME=/host/ajhome\n")
        f.appendText("AJ_DRAW_MODEL=cogview-3-flash\n")
        // 模型清单 = 父 AJ 路由大脑排好的顺序（唯一口径）。子 AJ 不排序、不另记台账。
        runCatching {
            val arr = org.json.JSONArray()
            for (e in RouteBrain.chain(st, "", 12)) {
                arr.put(
                    org.json.JSONObject()
                        .put("name", e.name)
                        .put("tier", e.tier)
                        .put("role", e.role)
                )
            }
            File(dir(ctx), "models.json").writeText(arr.toString(1), Charsets.UTF_8)
        }
        runCatching { Runtime.getRuntime().exec(arrayOf("chmod", "600", f.absolutePath)).waitFor() }
        return "ok"
    }

    private fun randomToken(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..12).map { chars[(Math.random() * chars.length).toInt()] }.joinToString("")
    }

    /** 后台跑起来：目标进程常驻，线程一直挂着直到被杀 */
    fun start(ctx: Context): String {
        absorbReport(ctx)
        val e = ensure(ctx)
        if (e != "ok") return "启动失败：$e"
        val w = writeEnv(ctx)
        if (w != "ok") return "启动失败：$w"
        Settings(ctx).webOn = true
        val cmd = "cd /host/web && (python3 broker.py >/host/web/broker.log 2>&1 &) ; " +
            "exec python3 server.py"
        Thread {
            runCatching { Linux.run(ctx, cmd, 86400) }
        }.apply { isDaemon = true; name = "ajweb" }.start()
        KeepAlive.start(ctx)
        return "已启动（等十几秒再刷新网页）"
    }

    /** 状态 + 地址 + 口令 */
    fun info(ctx: Context): String {
        absorbReport(ctx)
        val st = Settings(ctx)
        val up = runCatching { running(ctx) }.getOrDefault(false)
        val sb = StringBuilder()
        sb.append("状态：").append(if (up) "运行中" else "未运行").append('\n')
        sb.append("端口：").append(st.webPort).append('\n')
        sb.append("口令：").append(st.webToken).append('\n')
        val pub = st.webPublic.trim()
        if (pub.isNotBlank()) {
            sb.append("\n外部地址（你自己填的公网 / 隧道地址）：\n").append(withToken(pub, st.webToken))
            sb.append("\n\n同一 WiFi 备用：\n").append(addr(ctx))
        } else if (up) {
            sb.append("\n同一 WiFi 的电脑 / 平板 / 手机浏览器打开：\n").append(addr(ctx))
            sb.append("\n\n要让外网访问，在上面「外部地址」里填你的公网域名或隧道地址。")
        } else {
            sb.append("\n点「启动」后这里给出地址。")
        }
        return sb.toString()
    }

    fun stop(ctx: Context) {
        Settings(ctx).webOn = false
        runCatching {
            // 按 pid 文件精确杀，绝不用 pkill -f（会匹配到自己，rootfs 里还会带走 proot 的 vpid 1）
            Linux.run(
                ctx,
                "cd /host/web 2>/dev/null || exit 0; " +
                    "for f in server.pid broker.pid; do " +
                    "if [ -f \"\$f\" ]; then kill \"\$(cat \"\$f\")\" 2>/dev/null; rm -f \"\$f\"; fi; " +
                    "done; true",
                20
            )
        }
        KeepAlive.stop(ctx)
    }

    /** 局域网地址 */
    fun addr(ctx: Context): String {
        val ip = lanIp()
        val st = Settings(ctx)
        val t = st.webToken
        return "http://$ip:${st.webPort}/?k=$t"
    }

    /** 把用户填的外部地址拼上口令（已带 ?k= 就不重复拼） */
    fun withToken(url: String, token: String): String {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return ""
        val full = if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
        return if (full.contains("k=")) full else "$full/?k=$token"
    }

    fun lanIp(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    fun running(ctx: Context): Boolean {
        val (_, out) = Linux.run(ctx, "ps aux | grep -c '[s]erver.py' || true", 15)
        return (out.trim().toIntOrNull() ?: 0) > 0
    }

}
