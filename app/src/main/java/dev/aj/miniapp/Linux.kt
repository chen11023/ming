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

    /** rootfs 解压后的位置 */
    fun rootDir(ctx: Context): File = File(ctx.filesDir, "rootfs")

    /** Android 安装后释放原生库的目录，里面有 libproot.so */
    fun libDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun prootPath(ctx: Context): File = File(libDir(ctx), "libproot.so")

    fun isReady(ctx: Context): Boolean =
        File(rootDir(ctx), ".ready").exists() && prootPath(ctx).exists()

    // ---------------------------------------------------------------- 解压

    /**
     * 解压 assets/rootfs.tar.gz 到 filesDir/rootfs。
     * 已完成过就跳过（用 .ready 标记）。
     * @param onProgress 回调已解压文件数，用于显示进度
     */
    fun extractRootfs(
        ctx: Context,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<Unit> {
        val dest = rootDir(ctx)
        val ready = File(dest, ".ready2")
        if (ready.exists()) return Result.success(Unit)

        return try {
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
            File(dest, ".ready2").writeText("extracted=$count costMs=$cost\n")
            onProgress?.invoke(count, "完成")
            Result.success(Unit)
        } catch (e: Exception) {
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
    fun prootCommand(ctx: Context, script: String): List<String> {
        val root = rootDir(ctx)
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
        env: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder(prootCommand(ctx, script))
                .redirectErrorStream(true)
            pb.environment().putAll(env)
            pb.environment()["HOME"] = "/root"
            pb.environment()["TERM"] = "xterm"
            pb.environment()["LANG"] = "C.UTF-8"
            // proot 需要可写的临时目录，否则启动就退
            pb.environment()["PROOT_TMP_DIR"] = ctx.cacheDir.absolutePath

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
    fun runStreaming(
        ctx: Context,
        script: String,
        onLine: (String) -> Unit,
        onExit: (Int) -> Unit
    ): Process? {
        return try {
            val pb = ProcessBuilder(prootCommand(ctx, script)).redirectErrorStream(true)
            pb.environment()["HOME"] = "/root"
            pb.environment()["TERM"] = "xterm"
            pb.environment()["LANG"] = "C.UTF-8"
            pb.environment()["PROOT_TMP_DIR"] = ctx.cacheDir.absolutePath

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
        val ready = File(root, ".ready")
        appendLine("rootfs   : ${if (ready.exists()) "✅ 已解压" else "❌ 未解压"}")

        if (ready.exists()) {
            appendLine("标记文件 : ${ready.readText().trim()}")
        }

        if (proot.exists() && ready.exists()) {
            val (code, out) = run(ctx, "cat /etc/alpine-release 2>/dev/null; uname -m", 30)
            appendLine("自检     : exit=$code")
            appendLine(out.take(500))
        }
    }
}
