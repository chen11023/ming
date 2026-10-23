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

    /** 待处理的符号链接：目标可能在 tar 顺序里还没出现，解压完统一做 symlink/hardlink/copy 兜底 */
    private data class PendingLink(
        val linkPath: String,   // rootfs 内相对路径，如 "bin/sh"
        val target: String,     // tar 里的原始 target（相对或绝对）
        val isDir: Boolean
    )

    /** rootfs 解压后的位置 */
    fun rootDir(ctx: Context): File = File(ctx.filesDir, "rootfs")

    /** Android 安装后释放原生库的目录，里面有 libproot.so */
    fun libDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun prootPath(ctx: Context): File = File(libDir(ctx), "libproot.so")

    fun isReady(ctx: Context): Boolean =
        File(rootDir(ctx), ".ready2").exists() && prootPath(ctx).exists()

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
        // 旧版用 .ready 标记，且没有 .ready2 → 说明是老版本解压（符号链接缺失），强制重解
        val legacyReady = File(dest, ".ready")
        if (ready.exists()) return Result.success(Unit)
        if (legacyReady.exists()) {
            dest.deleteRecursively()
        }

        return try {
            dest.mkdirs()
            val t0 = System.currentTimeMillis()
            var count = 0
            val pendingLinks = mutableListOf<PendingLink>()

            val src = openRootfs(ctx) ?: throw java.io.IOException("找不到 rootfs 资源")
            src.use { raw ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                    maybeGzip(raw)
                ).use { tar ->
                    // ---- 第一遍：只解真实文件 + 目录 + 收集符号链接 ----
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val name = entry.name.removePrefix("./")
                        if (name.isNotEmpty() && !name.contains("..")) {
                            val f = File(dest, name)
                            when {
                                entry.isDirectory -> f.mkdirs()
                                entry.isSymbolicLink -> {
                                    f.parentFile?.mkdirs()
                                    pendingLinks += PendingLink(name, entry.linkName, isDir = false)
                                    count++
                                }
                                entry.isLink -> {
                                    // hardlink：目标可能还没出现，也先记成普通文件的复制兜底
                                    val tgt = File(dest, entry.linkName.removePrefix("./"))
                                    f.parentFile?.mkdirs()
                                    try {
                                        if (tgt.exists()) tgt.copyTo(f, overwrite = true)
                                    } catch (_: Exception) {
                                    }
                                    count++
                                }
                                else -> {
                                    f.parentFile?.mkdirs()
                                    f.outputStream().use { tar.copyTo(it) }
                                    applyMode(f, entry.mode)
                                    count++
                                    if (count % 50 == 0) onProgress?.invoke(count, name)
                                }
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }

            // ---- 第二遍：统一处理所有符号链接（symlink → hardlink → copy 三级兜底） ----
            var symlinkDowngraded = 0
            for (link in pendingLinks) {
                val linkFile = File(dest, link.linkPath)
                val resolved = resolveLinkTarget(dest, link.target)

                // 首选：真 symlink
                val symlinkOk = try {
                    java.nio.file.Files.deleteIfExists(linkFile.toPath())
                    java.nio.file.Files.createSymbolicLink(
                        linkFile.toPath(), java.nio.file.Paths.get(link.target)
                    )
                    true
                } catch (_: Exception) {
                    // 失败后清掉半成品，避免留下损坏的链接
                    try { linkFile.delete() } catch (_: Exception) {}
                    false
                }

                if (!symlinkOk) {
                    symlinkDowngraded++
                    when {
                        resolved == null -> {
                            // 链条断了（如 /proc 下虚拟路径）→ 跳过，不建
                            android.util.Log.w(TAG, "unresolved link: ${link.linkPath} -> ${link.target}")
                        }
                        resolved.isDirectory -> {
                            // 目录型链接 → 降级为真实目录
                            linkFile.mkdirs()
                        }
                        else -> {
                            // 文件型链接 → hardlink，失败再 copy，保留可执行位
                            linkFile.parentFile?.mkdirs()
                            var done = false
                            try {
                                java.nio.file.Files.createLink(
                                    linkFile.toPath(), resolved.toPath()
                                )
                                done = true
                            } catch (_: Exception) {
                                done = false
                            }
                            if (!done) {
                                try {
                                    resolved.copyTo(linkFile, overwrite = true)
                                    applyMode(linkFile, 0b111_101_101) // 0755
                                } catch (_: Exception) {
                                }
                            }
                        }
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
            File(dest, ".ready2").writeText(
                "extracted=$count costMs=$cost\n" +
                "symlink_total=${pendingLinks.size}\n" +
                "symlink_downgraded=$symlinkDowngraded\n"
            )
            onProgress?.invoke(count, "完成")
            Result.success(Unit)
        } catch (e: Exception) {
            try {
                File(dest.parentFile, ".extract_err")
                    .writeText(e.toString() + "\n" + e.stackTrace.take(6).joinToString("\n"))
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

    /**
     * 循环解析符号链接目标，直到得到真实文件/目录。
     * 绝对路径 target 拼到 rootfs 根下（不是 Android 真实根）；相对路径按链接所在目录解析。
     * @return 解析到的真实 File，或 null（目标不存在 / 链条断裂 / 超过层数）
     */
    private fun resolveLinkTarget(root: File, target: String, maxDepth: Int = 10): File? {
        var current = if (target.startsWith("/")) File(root, target.removePrefix("/"))
                      else File(target)
        var depth = 0
        while (depth++ < maxDepth) {
            if (!current.exists()) return null
            val attrs = try {
                java.nio.file.Files.readAttributes(
                    current.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java
                )
            } catch (_: Exception) {
                return null
            }
            if (!attrs.isSymbolicLink) return current
            // 它本身也是个 symlink → 追下一层
            val next = try {
                java.nio.file.Files.readSymbolicLink(current.toPath()).toString()
            } catch (_: Exception) {
                return null
            }
            current = if (next.startsWith("/")) File(root, next.removePrefix("/"))
                      else File(current.parentFile, next).canonicalFile
        }
        return null
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
            pb.environment()["PROOT_NO_SECCOMP"] = "1"

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
            pb.environment()["PROOT_NO_SECCOMP"] = "1"

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
        val ready = File(root, ".ready2")
        appendLine("rootfs   : ${if (ready.exists()) "✅ 已解压" else "❌ 未解压"}")
        val sh = File(root, "bin/sh")
        val bb = File(root, "bin/busybox")
        val shTarget = try {
            java.nio.file.Files.readSymbolicLink(sh.toPath()).toString()
        } catch (_: Exception) { "" }
        appendLine("bin/sh   : ${if (sh.exists()) "✅" else "❌"}" +
                (if (shTarget.isNotEmpty()) " -> $shTarget" else if (sh.isFile) " [降级为实体]" else "") +
                "  busybox=${bb.length()}B")
        val err = File(root.parentFile, ".extract_err")
        if (err.exists()) appendLine("解压错误 : ${err.readText().take(300)}")

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
