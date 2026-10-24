package dev.aj.miniapp

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Linux 环境管理：解压 rootfs、拼装 proot 命令、执行。
 *
 * 设计原则：
 *  - rootfs 保存在 App 私有目录，不进外部存储
 *  - proot 及其依赖库以 lib*.so 形式放在 jniLibs，由 Android 安装时释放到 nativeLibraryDir
 *  - 所有执行都走这里，聊天和终端用的是同一套环境
 */
object Linux {

    private const val TAG = "Linux"

    /** 只有改这个值才会强制重新解压（改 rootfs 内容时同步改它） */
    private const val ROOTFS_TAG = "alpine3.22.6-p4"

    /** 解压完成标记（内容含 ROOTFS_TAG） */
    private const val MARKER = ".ready2"

    /** 解压完整性校验用的关键文件（都不是符号链接，能直接判存在与非空） */
    private val KEY_PATHS = listOf(
        "bin/busybox",
        "bin/bash",
        "usr/bin/curl",
        "usr/bin/git",
        "usr/bin/python3.12",
        "etc/alpine-release",
    )

    fun rootDir(ctx: Context): File = File(ctx.filesDir, "rootfs")

    /**
     * 写 rootfs/etc/resolv.conf。
     * Alpine 的 curl/git 走 libc 解析，只看 /etc/resolv.conf；Android 主系统的 DNS 由 netd 管，
     * 文件里什么都没有 —— 不写这个，proot 里所有域名解析都会 "Could not resolve host"。
     * DNS 取手机当前活动网络的（VPN/热点都跟着走），取不到再用公共 DNS 兜底。
     */
    /** inbox / out 只留最近 keep 个，防止堆到几个 G */
    fun cleanup(ctx: Context, keep: Int = 30) {
        for (name in listOf("inbox", "out")) {
            val dir = File(ctx.filesDir, name)
            if (!dir.isDirectory) continue
            runCatching {
                val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@runCatching
                files.drop(keep).forEach { f -> runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() } }
            }
        }
    }

    /** 产物输出目录（挂进 proot 的 /out） */
    fun outDir(ctx: Context): File = File(ctx.filesDir, "out").apply { mkdirs() }

    fun writeResolvConf(ctx: Context, root: File): String {
        val dns = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val lp = cm?.let { it.getLinkProperties(it.activeNetwork) }
            lp?.dnsServers?.mapNotNull { it.hostAddress }?.filter { it.isNotBlank() }.orEmpty()
        } catch (e: Throwable) {
            emptyList()
        }
        val use = LinkedHashSet<String>()
        dns.forEach { use.add(it) }
        listOf("223.5.5.5", "1.1.1.1", "8.8.8.8").forEach { use.add(it) }

        val f = File(root, "etc/resolv.conf")
        try {
            f.parentFile?.mkdirs()
            f.writeText(use.joinToString("\n") { "nameserver $it" } + "\n")
        } catch (e: Throwable) {
            return "写入失败: ${e.javaClass.simpleName}: ${e.message}"
        }
        return "DNS=" + use.joinToString(",")
    }

    /** Android 安装后释放原生库的目录，里面有 libproot.so */
    fun libDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun prootPath(ctx: Context): File = File(libDir(ctx), "libproot.so")

    fun isReady(ctx: Context): Boolean {
        val root = rootDir(ctx)
        val mk = File(root, MARKER)
        if (!mk.exists()) return false
        if (!mk.readText().contains("tag=$ROOTFS_TAG")) return false
        if (KEY_PATHS.any { !File(root, it).isFile || File(root, it).length() == 0L }) return false
        return prootPath(ctx).exists()
    }

    // ---------------------------------------------------------------- 解压

    /**
     * 解压 assets/rootfs.tar.gz 到 filesDir/rootfs。
     * 已解压且标记与当前构建一致才跳过，否则重新解压。
     * @param onProgress 回调已解压文件数，用于显示进度
     */
    fun extractRootfs(
        ctx: Context,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<Unit> {
        if (isReady(ctx)) return Result.success(Unit)
        val dest = File(ctx.filesDir, "rootfs_new")
        val finalDir = rootDir(ctx)
        val oldDir = File(ctx.filesDir, "rootfs_old")

        return try {
            dest.deleteRecursively()
            dest.mkdirs()
            val t0 = System.currentTimeMillis()
            var count = 0

            val src = openRootfs(ctx) ?: throw java.io.IOException("找不到 rootfs 资源")
            src.use { raw ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                    maybeGzip(raw)
                ).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val name = entry.name.removePrefix("./")
                        if (name.isNotEmpty() && !name.contains("..")) {
                            val f = File(dest, name)
                            if (entry.isDirectory) {
                                f.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                f.parentFile?.mkdirs()
                                try {
                                    java.nio.file.Files.deleteIfExists(f.toPath())
                                    java.nio.file.Files.createSymbolicLink(
                                        f.toPath(), java.nio.file.Paths.get(entry.linkName)
                                    )
                                } catch (_: Exception) {
                                }
                                count++
                            } else if (entry.isLink) {
                                val tgt = File(dest, entry.linkName.removePrefix("./"))
                                f.parentFile?.mkdirs()
                                try {
                                    if (tgt.exists()) tgt.copyTo(f, overwrite = true)
                                } catch (_: Exception) {
                                }
                                count++
                            } else {
                                f.parentFile?.mkdirs()
                                f.outputStream().use { tar.copyTo(it) }
                                applyMode(f, entry.mode)
                                count++
                                if (count % 50 == 0) onProgress?.invoke(count, name)
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }

            // 建几个 proot 环境必需的挂载点
            for (d in listOf("proc", "sys", "dev", "dev/pts", "dev/shm", "tmp", "host", "root")) {
                File(dest, d).mkdirs()
            }
            File(dest, "tmp").setWritable(true, false)
            File(dest, "root").mkdirs()

            val cost = System.currentTimeMillis() - t0
            val missing = KEY_PATHS.filter { p ->
                val k = File(dest, p)
                !k.isFile || k.length() == 0L
            }
            if (missing.isNotEmpty()) {
                throw java.io.IOException("解压不完整，缺 ${missing.size} 项: ${missing.joinToString(",")}")
            }
            // apk 需要这两个目录，否则每次 add 都因写缓存失败返回 5
            for (d in listOf("var/lib/apk", "var/cache/apk", "var/cache/fontconfig", "var/tmp")) File(dest, d).mkdirs()
            File(dest, MARKER).writeText("tag=$ROOTFS_TAG\nfiles=$count\ncostMs=$cost\n")
            if (oldDir.exists()) oldDir.deleteRecursively()
            if (finalDir.exists() && !finalDir.renameTo(oldDir)) {
                throw java.io.IOException("旧 rootfs 让位失败")
            }
            if (!dest.renameTo(finalDir)) throw java.io.IOException("替换 rootfs 失败")
            oldDir.deleteRecursively()
            File(ctx.filesDir, ".extract_err").delete()
            onProgress?.invoke(count, "完成")
            Result.success(Unit)
        } catch (e: Exception) {
            try {
                File(ctx.filesDir, ".extract_err").writeText(
                    "${BuildConfig.BUILD_TAG} ${e.javaClass.simpleName}: ${e.message}\n"
                )
            } catch (_: Exception) {
            }
            try {
                dest.deleteRecursively()
            } catch (_: Exception) {
            }
            Result.failure(e)
        }
    }


    /** 打开 rootfs 资源：AGP 会把 .tar.gz 拆包成 .tar，两种都试 */
    private fun openRootfs(ctx: Context): java.io.InputStream? {
        for (n in listOf("rootfs.tar.gz", "rootfs.tar")) {
            try {
                return ctx.assets.open(n)
            } catch (_: java.io.IOException) {
            }
        }
        return null
    }

    /** 按文件头判断是否 gzip，不是就原样返回 tar */
    private fun maybeGzip(raw: java.io.InputStream): java.io.InputStream {
        val pb = java.io.PushbackInputStream(raw, 2)
        val sig = ByteArray(2)
        val n = pb.read(sig)
        if (n == 2 && sig[0] == 0x1f.toByte() && sig[1] == 0x8b.toByte()) {
            return org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(pb)
        }
        if (n > 0) pb.unread(sig, 0, n)
        return pb
    }

    private fun applyMode(f: File, mode: Int) {
        // 0o755 / 0o644 这两类最常见
        val ownerExec = (mode and 0b001_000_000) != 0
        val anyExec = (mode and 0b000_001_000) != 0
        if (ownerExec || anyExec) {
            f.setExecutable(true, false)
        }
        f.setReadable(true, false)
    }

    // ---------------------------------------------------------------- 命令拼装

    /**
     * 拼一条在 rootfs 里执行的 proot 命令。
     * @param script 交给 /bin/sh -c 的脚本
     */
    /**
     * 给 rootfs 里的 python 打 SIGSYS 补丁。
     * Android 的 app seccomp 会拦 rseq/clone3/mbind/io_uring 这类调用并直接 SIGSYS 杀进程，
     * numpy、matplotlib 一 import 就 "Bad system call"。装了空处理器后这些调用退化成 ENOSYS。
     */
    fun patchPython(ctx: Context) {
        val root = rootDir(ctx)
        if (!root.exists()) return
        val dirs = listOf("usr/lib/python3.12/site-packages", "usr/lib/python3.11/site-packages")
        for (d in dirs) {
            val dir = File(root, d)
            if (!dir.isDirectory) continue
            val f = File(dir, "sitecustomize.py")
            try {
                if (f.exists() && f.readText() == SITECUSTOMIZE) continue
                f.writeText(SITECUSTOMIZE)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * rootfs 里没有 tzdata，写 "Asia/Shanghai" 这种名字 musl 找不到会退回 UTC。
     * 用 POSIX 写法 "XXX-8:00" 才不依赖时区文件，任何发行版都认。
     */
    private fun posixTz(): String {
        val off = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val sign = if (off >= 0) "-" else "+"
        val a = kotlin.math.abs(off)
        return "XXX" + sign + (a / 60) + ":" + String.format("%02d", a % 60)
    }

    fun prootCommand(ctx: Context, script: String): List<String> {
        val root = rootDir(ctx)
        // 聊天里跑的命令和终端走同一套环境，DNS 也要在
        if (root.exists()) {
            writeResolvConf(ctx, root)
            patchPython(ctx)
        }
        outDir(ctx)
        val lib = libDir(ctx)
        val files = ctx.filesDir

        return listOf(
            prootPath(ctx).absolutePath,
            "--link2symlink",
            "-0",
            "-r", root.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            // 把 App 私有目录挂成 /host，方便脚本读写宿主数据
            "-b", "${files.absolutePath}:/host",
            // /out 单独挂出来：产物写这里，App 会在聊天里回显
            "-b", "${File(files, "out").absolutePath}:/out",
            // 让 rootfs 里的程序能找到 proot 自带的依赖库
            "-w", "/root",
            "/bin/sh", "-c", script
        )
    }

    /**
     * 执行一条脚本，返回 (退出码, 合并后的输出)。
     * 这是聊天工具调用和终端共用的一条路。
     */
    fun run(
        ctx: Context,
        script: String,
        timeoutSec: Long = 120,
        env: Map<String, String> = emptyMap(),
        noSeccomp: Boolean = false
    ): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder(prootCommand(ctx, script))
                .redirectErrorStream(true)
            pb.environment().putAll(env)
            // noSeccomp=true：关掉 proot 的 seccomp 加速，走完整 ptrace 拦截。
            // 用于验证 numpy 之类的 SIGSYS("Bad system call") 是不是被 seccomp 拦下的。
            if (noSeccomp) pb.environment()["PROOT_NO_SECCOMP"] = "1"
            val defEnv = mapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME" to "/root",
                "TERM" to "xterm",
                "LANG" to "C.UTF-8",
                "TZ" to posixTz(),
                "PROOT_TMP_DIR" to ctx.cacheDir.absolutePath
            )
            // 默认值只在用户没给时补上；PATH 必须有，否则 proot 里连 sh 都找不到
            // 只看用户是否显式给了同名变量；App 进程自带的那套 Android PATH 不能沿用
            defEnv.forEach { (k, v) -> if (!env.containsKey(k)) pb.environment()[k] = v }

            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                -2 to (out + "\n[超时 ${timeoutSec}s，已终止]")
            } else {
                p.exitValue() to out
            }
        } catch (e: Exception) {
            -1 to "[执行失败] ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 流式执行：边跑边把输出喂给回调（给终端页用）。
     */
    /** 起一个常驻进程（MCP 服务之类），stdout/stderr 分开，由调用方自己读 */
    fun startInteractive(ctx: Context, script: String): Process {
        val pb = ProcessBuilder(prootCommand(ctx, script))
        pb.redirectErrorStream(false)
        // 跟 run() 用同一套环境，否则 proot 里拿不到 PATH，python3 直接找不到
        val defEnv = mapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/root",
            "TERM" to "xterm",
            "LANG" to "C.UTF-8",
            "TZ" to posixTz(),
            "PROOT_TMP_DIR" to ctx.cacheDir.absolutePath
        )
        defEnv.forEach { (k, v) -> pb.environment()[k] = v }
        return pb.start()
    }

    fun runStreaming(
        ctx: Context,
        script: String,
        onLine: (String) -> Unit,
        onExit: (Int) -> Unit,
        env: Map<String, String> = emptyMap()
    ): Process? {
        return try {
            val pb = ProcessBuilder(prootCommand(ctx, script)).redirectErrorStream(true)
            pb.environment().putAll(env)
            val defEnvS = mapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME" to "/root",
                "TERM" to "xterm",
                "LANG" to "C.UTF-8",
                "TZ" to posixTz(),
                "PROOT_TMP_DIR" to ctx.cacheDir.absolutePath
            )
            defEnvS.forEach { (k, v) -> if (!env.containsKey(k)) pb.environment()[k] = v }

            val p = pb.start()
            p.outputStream.close()

            Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { onLine(it) }
                    }
                } catch (_: Exception) {
                }
                onExit(try { p.waitFor() } catch (_: Exception) { -1 })
            }.start()

            p
        } catch (e: Exception) {
            onLine("[执行失败] ${e.message}")
            onExit(-1)
            null
        }
    }

    // ---------------------------------------------------------------- 环境自检

    /** 返回几行动诊断信息，给设置页显示 */
    fun diagnose(ctx: Context): String = buildString {
        appendLine("dataDir  : ${ctx.filesDir}")
        appendLine("nativeLib: ${libDir(ctx)}")

        val proot = prootPath(ctx)
        appendLine("proot    : ${if (proot.exists()) "${proot.length()} B" else "❌ 缺失"}")

        val root = rootDir(ctx)
        val mk = File(root, MARKER)
        appendLine("网络     : " + writeResolvConf(ctx, root))
        val missing = KEY_PATHS.filter { p ->
            val k = File(root, p)
            !k.isFile || k.length() == 0L
        }
        val ok = isReady(ctx)
        appendLine("版本     : ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TAG})")
        appendLine("rootfs   : ${if (ok) "✅ 已解压" else "❌ 未解压"}")

        if (mk.exists()) {
            appendLine("标记文件 : ${mk.readText().trim().replace("\n", " / ")}")
        }
        if (missing.isNotEmpty()) {
            appendLine("缺失     : ${missing.joinToString(",")}")
        }
        val err = File(ctx.filesDir, ".extract_err")
        if (err.exists()) {
            appendLine("解压错误 : ${err.readText().trim()}")
        }

        if (proot.exists() && ok) {
            val (code, out) = run(ctx, "cat /etc/alpine-release 2>/dev/null; uname -m", 30)
            appendLine("自检     : exit=$code")
            appendLine(out.take(300))

            // 关键库冒烟：numpy/matplotlib 是画图的地基
            patchPython(ctx)
            val sc0 = File(root, "usr/lib/python3.12/site-packages/sitecustomize.py")
            appendLine("py补丁   : ${if (sc0.exists()) "✅ ${sc0.length()} B" else "❌ 未写入"}")
            val (nc, no) = run(ctx, "python3 -c \"import numpy;print('numpy',numpy.__version__)\" 2>&1 | tail -2", 120)
            appendLine("numpy    : exit=$nc  ${no.trim().take(160)}")
            val (mc, mo) = run(ctx, "python3 -c \"import matplotlib;print('mpl',matplotlib.__version__)\" 2>&1 | tail -2", 180)
            appendLine("mpl      : exit=$mc  ${mo.trim().take(160)}")

            // 探 syscall：哪个被 Android seccomp 拦掉（SIGSYS=31）
            val probe = File(ctx.filesDir, "syscall_probe.py")
            probe.writeText(SYSCALL_PROBE)
            val (sc, so) = run(ctx, "python3 /host/syscall_probe.py 2>&1 | tail -3", 120)
            appendLine("syscall  : exit=$sc  ${so.trim().replace("\n", " ").take(200)}")
            val (fc, fo) = run(ctx, "mkdir -p /var/lib/apk /var/cache/apk /var/tmp && ls -d /var/lib/apk /var/cache/apk && fc-cache -f >/dev/null 2>&1; echo fc=$?", 120)
            appendLine("apk目录  : exit=$fc  ${fo.trim().replace("\n", " ").take(160)}")
        }
    }
}

private const val SITECUSTOMIZE = """
import signal
try:
    signal.signal(signal.SIGSYS, lambda *a: None)
except Exception:
    pass
"""

/** 逐个 fork 试候选 syscall，报出哪个被 seccomp 杀掉（aarch64 号） */
private const val SYSCALL_PROBE = """
import os, ctypes
libc = ctypes.CDLL("libc.so.6", use_errno=True)
libc.syscall.restype = ctypes.c_long
CANDS = [("membarrier",283),("rseq",293),("io_uring_setup",425),("pidfd_open",434),("clone3",435),("futex_waitv",449),("sched_setaffinity",122),("mbind",235),("get_mempolicy",236),("set_mempolicy",237),("migrate_pages",238),("move_pages",239),("perf_event_open",241),("process_vm_readv",270),("process_vm_writev",271),("kcmp",272),("bpf",280),("userfaultfd",282),("setpriority",140)]
out = []
for name, nr in CANDS:
    pid = os.fork()
    if pid == 0:
        try:
            libc.syscall(nr, 0)
        except Exception:
            pass
        os._exit(0)
    st = os.waitpid(pid, 0)[1]
    if os.WIFSIGNALED(st):
        sig = os.WTERMSIG(st)
        out.append(name + ("=SIGSYS" if sig == 31 else "=SIG" + str(sig)))
    else:
        out.append(name + "=ok")
bad = [o for o in out if not o.endswith("=ok")]
print("blocked:", " ".join(bad) if bad else "none")
"""
