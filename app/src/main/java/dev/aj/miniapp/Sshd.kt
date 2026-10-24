package dev.aj.miniapp

import android.content.Context
import java.net.NetworkInterface

/**
 * 入站 SSH：在 App 自己的 rootfs 里跑一个 sshd，别人拿地址+密码就能连进来。
 * 认证只有密码（root），密码由 App 生成、加密存在本机。
 * 端口默认 2222；公网访问还得自己做隧道（运营商 CGNAT 下手机没有公网 IP）。
 */
object Sshd {

    private const val SSH_PORT_DEFAULT = 2222

    private fun passKey(ctx: Context) = "sshd.json"

    private fun readPass(ctx: Context): String {
        val f = java.io.File(ctx.filesDir, passKey(ctx))
        val blob = runCatching { f.readText() }.getOrNull() ?: return ""
        return runCatching { Vault.decrypt(blob) }.getOrDefault("")
    }

    private fun writePass(ctx: Context, plain: String) {
        runCatching {
            java.io.File(ctx.filesDir, passKey(ctx)).writeText(Vault.encrypt(plain))
        }
    }

    fun password(ctx: Context): String {
        val cur = readPass(ctx)
        if (cur.isNotBlank()) return cur
        val p = genPass()
        writePass(ctx, p)
        return p
    }

    fun resetPassword(ctx: Context): String {
        val p = genPass()
        writePass(ctx, p)
        return p
    }

    private fun genPass(): String {
        val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        return (1..14).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    fun port(ctx: Context): Int = Settings(ctx).sshPort.takeIf { it in 22..65000 } ?: SSH_PORT_DEFAULT

    /** 本机在局域网里的 IPv4，用来看该让对方连哪个地址 */
    fun localIp(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.isNotBlank() && !it.startsWith("127.") } ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    private fun alive(p: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", p), 1200) }
        true
    }.getOrDefault(false)

    fun status(ctx: Context): String {
        // 每次 Linux.run 都是新的 proot 会话，ps 看不到别的会话里的 sshd，
        // 所以直接对着端口连一下，连上就是活着（2026-10-24 实测 ps 永远返回 0）
        val up = alive(port(ctx))
        return "sshd 进程：${if (up) "运行中（端口 ${port(ctx)} 可连）" else "未运行"}"
    }

    /** 装 openssh（只装一次）+ 生成 host key + 设 root 密码，返回日志 */
    fun ensure(ctx: Context): String {
        val p = password(ctx)
        val script = """
            set -e
            if ! command -v sshd >/dev/null 2>&1; then
              apk add --no-cache openssh >/dev/null 2>&1 || apk add --no-cache openssh
            fi
            mkdir -p /root/.ssh /var/run/sshd /run/sshd
            chmod 700 /root/.ssh
            [ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A >/dev/null 2>&1
            echo "root:${p}" | chpasswd
            printf '%s\n' 'PermitRootLogin yes' 'PasswordAuthentication yes' 'KbdInteractiveAuthentication yes' 'PermitEmptyPasswords no' 'UsePAM no' > /etc/ssh/sshd_config.d/aj.conf 2>/dev/null || true
            grep -q '^Include /etc/ssh/sshd_config.d' /etc/ssh/sshd_config 2>/dev/null || printf '%s\n' 'Include /etc/ssh/sshd_config.d/*.conf' | cat - /etc/ssh/sshd_config > /tmp/_sshcfg && mv /tmp/_sshcfg /etc/ssh/sshd_config
            echo ok
        """.trimIndent()
        val (code, out) = Linux.run(ctx, script, timeoutSec = 300)
        return if (code == 0) "安装/初始化完成" else "[exit $code]\n${out.trim().take(600)}"
    }

    fun start(ctx: Context): String {
        val port = port(ctx)
        if (alive(port)) return "已经在跑（端口 $port 可连）"
        val script = """
            mkdir -p /run/sshd /var/run/sshd
            if ps -ef | grep -q '[s]shd'; then echo "already running"; exit 0; fi
            /usr/sbin/sshd -p $port
            sleep 1
            ps -ef | grep -c '[s]shd'
        """.trimIndent()
        val (code, out) = Linux.run(ctx, script, timeoutSec = 60)
        return if (code == 0) "已启动，端口 $port" else "[exit $code]\n${out.trim().take(600)}"
    }

    fun stop(ctx: Context): String {
        val (code, out) = Linux.run(
            ctx,
            "pkill -f '/usr/sbin/sshd -p ' ; sleep 1; ps -ef | grep -c '[s]shd' || true",
            timeoutSec = 60
        )
        return if (code == 0) "已停止" else "[exit $code]\n${out.trim().take(600)}"
    }

    /** 给用户看的连接信息 */
    fun info(ctx: Context): String {
        val port = port(ctx)
        val ip = localIp()
        return buildString {
            append("局域网连法：\n")
            append("  ssh root@").append(ip).append(" -p ").append(port).append('\n')
            append("  密码：").append(password(ctx)).append('\n')
            append('\n')
            append(status(ctx)).append('\n')
            append("开关：").append(if (Settings(ctx).sshOn) "已开启" else "已关闭").append('\n')
            append("\n公网要连，得在 rootfs 里跑隧道（cloudflared / frp），手机没有公网 IP。")
        }
    }
}
