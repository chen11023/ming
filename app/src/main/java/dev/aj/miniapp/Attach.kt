package dev.aj.miniapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 附件：拷进 inbox，转成模型能吃的 content part。
 * 图片 → image_url(data uri)；视频 → ffmpeg 抽 5 帧再当图片；其它 → 本地抽文本。
 * 类型判断不只看扩展名，也看文件头（截图被改名也能认出来）。
 */
class Attach(private val ctx: Context) {

    fun inboxDir(): File = File(ctx.filesDir, "inbox").apply { mkdirs() }

    fun copy(uri: Uri): File {
        val name = displayName(uri)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
        // 保留中文，只把路径分隔符和怪字符换成下划线；扩展名单独保住，绝不能被截掉
        val safeBase = base.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(40).ifBlank { "file" }
        val safeExt = ext.replace(Regex("[^a-z0-9]"), "").take(8)
        val fname = System.currentTimeMillis().toString() + "_" + safeBase +
            (if (safeExt.isEmpty()) "" else "." + safeExt)
        val dst = File(inboxDir(), fname)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("读不到这个文件")
        return dst
    }

    private fun displayName(uri: Uri): String {
        var n = uri.lastPathSegment ?: "file"
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) n = c.getString(i) ?: n
            }
        }
        return n
    }

    /** 去掉时间戳前缀，给用户看的名字 */
    fun name(f: File): String = f.name.substringAfter('_', f.name)

    fun parts(file: File, log: (String) -> Unit): List<JSONObject> {
        val kind = kindOf(file)
        log(name(file) + " → " + when (kind) {
            KIND_IMAGE -> "图片"
            KIND_VIDEO -> "视频"
            else -> "文本/文档"
        })
        return when (kind) {
            KIND_IMAGE -> listOf(imgPart(file))
            KIND_VIDEO -> {
                val fs = frames(file, log)
                if (fs.isEmpty()) listOf(textPart("[视频 " + name(file) + "：" + fmt(file.length()) + "，抽帧失败，看不到内容]"))
                else fs.map { imgPart(it) }
            }
            else -> {
                val text = extractText(file, log)
                if (text.isBlank())
                    listOf(textPart(
                        "[附件 " + name(file) + "：" + fmt(file.length()) +
                            "，是二进制或解析不出文本，没有可读内容]"
                    ))
                else listOf(textPart("[附件 " + name(file) + " 的内容]\n" + text))
            }
        }
    }

    // ---------------------------------------------------------------- 类型判断

    private fun kindOf(f: File): Int {
        val ext = f.extension.lowercase()
        if (ext in IMAGES) return KIND_IMAGE
        if (ext in VIDEOS) return KIND_VIDEO
        val h = head(f)
        fun u(i: Int) = h[i].toInt() and 0xFF
        if (h.size >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return KIND_IMAGE
        if (h.size >= 8 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) return KIND_IMAGE
        if (h.size >= 4 && u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46) return KIND_IMAGE
        if (h.size >= 2 && u(0) == 0x42 && u(1) == 0x4D) return KIND_IMAGE
        if (h.size >= 12 && u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) return KIND_VIDEO
        if (h.size >= 12 && u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46) return KIND_VIDEO
        return KIND_OTHER
    }

    private fun head(f: File, n: Int = 16): ByteArray {
        val b = ByteArray(n)
        runCatching { f.inputStream().use { it.read(b) } }
        return b
    }

    // ---------------------------------------------------------------- 各种 part

    private fun textPart(s: String) = JSONObject().apply {
        put("type", "text")
        put("text", s)
    }

    private fun imgPart(file: File): JSONObject {
        val bound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bound)
        if (bound.outWidth <= 0 || bound.outHeight <= 0) {
            return textPart("[图片 " + name(file) + " 解码失败]")
        }
        var s = 1
        while (bound.outWidth / s > 1400 || bound.outHeight / s > 1400) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp: Bitmap? = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
        if (bmp == null) return textPart("[图片 " + name(file) + " 读取失败]")
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64))
        }
    }

    private fun frames(v: File, log: (String) -> Unit): List<File> {
        val tag = v.nameWithoutExtension.take(16)
        val dir = File(inboxDir(), "frames_" + tag + "_" + System.currentTimeMillis()).apply { mkdirs() }
        val inp = "/host/inbox/" + v.name
        val out = "/host/inbox/" + dir.name + "/f%02d.jpg"

        val probe = Linux.run(ctx, "ffprobe -v error -show_entries format=duration -of csv=p=0 '" + inp + "'", 60)
        val dur = probe.second.lineSequence().map { it.trim() }
            .lastOrNull { it.toDoubleOrNull() != null }?.toDoubleOrNull() ?: 0.0
        val step = if (dur > 1.0) dur / 5.0 else 3.0

        val cmd = buildString {
            append("mkdir -p '/host/inbox/").append(dir.name).append("'\n")
            append("ffmpeg -y -loglevel error -i '").append(inp).append("' ")
            append("-vf 'fps=1/").append(String.format("%.3f", step)).append(",scale=800:-1' ")
            append("-frames:v 5 '").append(out).append("'")
        }
        val r = Linux.run(ctx, cmd, 240)
        val fs = dir.listFiles()?.filter { it.length() > 0 }?.sortedBy { it.name }?.take(5) ?: emptyList()
        log("视频 " + String.format("%.1f", dur) + "s，抽到 " + fs.size + " 帧（exit=" + r.first + "）")
        return fs
    }

    private fun extractText(f: File, log: (String) -> Unit): String {
        val script = File(ctx.filesDir, "extract.py")
        if (!script.exists() || script.length() < 100) script.writeText(PY)
        val rel = "/host/inbox/" + f.name
        val r = Linux.run(ctx, "python3 /host/extract.py '" + rel + "'", 300)
        val out = r.second
        val kind = when {
            out.startsWith("<<<BINARY>>>") -> "二进制"
            out.startsWith("<<<ERROR>>>") -> "解析失败"
            out.contains("<<<TRUNCATED>>>") -> "已截断"
            else -> "文本"
        }
        log("抽文本 exit=" + r.first + " 结果=" + kind + " 字符=" + out.length)
        return when {
            out.startsWith("<<<BINARY>>>") -> ""
            out.startsWith("<<<ERROR>>>") -> ""
            else -> out.take(24000)
        }
    }

    private fun fmt(n: Long): String = when {
        n >= 1L shl 20 -> String.format("%.1f MB", n / 1048576.0)
        n >= 1L shl 10 -> String.format("%.0f KB", n / 1024.0)
        else -> n.toString() + " B"
    }

    companion object {
        val IMAGES = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif")
        val VIDEOS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")
        const val KIND_IMAGE = 1
        const val KIND_VIDEO = 2
        const val KIND_OTHER = 3

        private val PY = """
import sys
p = sys.argv[1]
ext = p.lower().rsplit('.', 1)[-1]
LIMIT = 60000
try:
    if ext == 'pdf':
        from pdfminer.high_level import extract_text
        t = extract_text(p) or ''
    elif ext == 'docx':
        try:
            import docx
            d = docx.Document(p)
            t = '\n'.join(x.text for x in d.paragraphs)
        except Exception:
            # 没装 python-docx 时的兜底：直接读 word/document.xml
            import zipfile, re
            with zipfile.ZipFile(p) as z:
                xml = z.read('word/document.xml').decode('utf-8', 'replace')
            xml = xml.replace('</w:p>', '\n')
            t = re.sub(r'<[^>]+>', '', xml)
    elif ext in ('xlsx', 'xlsm'):
        try:
            import openpyxl
            wb = openpyxl.load_workbook(p, read_only=True, data_only=True)
            rows = []
            for ws in wb.worksheets:
                rows.append('### ' + ws.title)
                for row in ws.iter_rows(values_only=True):
                    rows.append('\t'.join('' if c is None else str(c) for c in row))
            t = '\n'.join(rows)
        except Exception:
            import zipfile, re
            rows = []
            with zipfile.ZipFile(p) as z:
                for name in z.namelist():
                    if not name.startswith('xl/worksheets/sheet'):
                        continue
                    xml = z.read(name).decode('utf-8', 'replace').replace('</row>', '\n').replace('</c>', '\t')
                    rows.append(re.sub(r'<[^>]+>', '', xml))
            t = '\n'.join(rows)
    else:
        data = open(p, 'rb').read(4000000)
        if b'\x00' in data[:8192]:
            print('<<<BINARY>>>')
            sys.exit(0)
        t = data.decode('utf-8', 'replace')
        sample = t[:8000]
        if sample:
            ok = sum(1 for ch in sample if ch.isprintable() or ch in '\n\r\t')
            if ok / len(sample) < 0.9:
                print('<<<BINARY>>>')
                sys.exit(0)
    if len(t) > LIMIT:
        sys.stdout.write(t[:LIMIT])
        sys.stdout.write('\n\n<<<TRUNCATED>>> 原文共 %d 字符，这里只给了前 %d 字符。' % (len(t), LIMIT))
    else:
        sys.stdout.write(t)
except Exception as e:
    print('<<<ERROR>>> ' + e.__class__.__name__ + ': ' + str(e))
""".trimIndent()
    }
}
