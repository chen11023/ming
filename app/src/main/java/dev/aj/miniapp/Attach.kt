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
 */
class Attach(private val ctx: Context) {

    fun inboxDir(): File = File(ctx.filesDir, "inbox").apply { mkdirs() }

    fun copy(uri: Uri): File {
        val name = displayName(uri)
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(48).ifBlank { "file" }
        val dst = File(inboxDir(), System.currentTimeMillis().toString() + "_" + safe)
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

    fun name(f: File): String = f.name.substringAfter('_', f.name)

    fun parts(file: File, log: (String) -> Unit): List<JSONObject> {
        val ext = file.extension.lowercase()
        return when {
            ext in IMAGES -> listOf(imgPart(file))
            ext in VIDEOS -> {
                val fs = frames(file, log)
                if (fs.isEmpty()) listOf(textPart("[视频 ${name(file)}，抽帧失败，无法查看内容]"))
                else fs.map { imgPart(it) }
            }
            else -> {
                val text = extractText(file, log)
                if (text.isBlank()) listOf(textPart("[附件 ${name(file)}，${fmt(file.length())}，无法抽取文本]"))
                else listOf(textPart("[附件 ${name(file)} 的内容]\n" + text))
            }
        }
    }

    private fun textPart(s: String) = JSONObject().apply {
        put("type", "text")
        put("text", s)
    }

    private fun imgPart(file: File): JSONObject {
        val bound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bound)
        var s = 1
        while (bound.outWidth / s > 1400 || bound.outHeight / s > 1400) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp: Bitmap? = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
        if (bmp == null) return textPart("[图片 ${name(file)} 读取失败]")
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        }
    }

    private fun frames(v: File, log: (String) -> Unit): List<File> {
        val tag = v.nameWithoutExtension.take(16)
        val dir = File(inboxDir(), "frames_" + tag + "_" + System.currentTimeMillis()).apply { mkdirs() }
        val inp = "/host/inbox/" + v.name
        val out = "/host/inbox/" + dir.name + "/f%02d.jpg"

        val probe = Linux.run(ctx, "ffprobe -v error -show_entries format=duration -of csv=p=0 '$inp'", 60)
        val dur = probe.second.lineSequence().map { it.trim() }.lastOrNull { it.toDoubleOrNull() != null }
            ?.toDoubleOrNull() ?: 0.0
        val step = if (dur > 1.0) dur / 5.0 else 3.0

        val cmd = buildString {
            append("mkdir -p '/host/inbox/").append(dir.name).append("'\n")
            append("ffmpeg -y -loglevel error -i '").append(inp).append("' ")
            append("-vf 'fps=1/").append(String.format("%.3f", step)).append(",scale=800:-1' ")
            append("-frames:v 5 '").append(out).append("'")
        }
        val r = Linux.run(ctx, cmd, 240)
        log("抽帧 exit=${r.first} " + r.second.take(160).replace("\n", " "))
        return dir.listFiles()?.filter { it.length() > 0 }?.sortedBy { it.name }?.take(5) ?: emptyList()
    }

    private fun extractText(f: File, log: (String) -> Unit): String {
        val script = File(ctx.filesDir, "extract.py")
        if (!script.exists()) script.writeText(PY)
        val rel = "/host/inbox/" + f.name
        val r = Linux.run(ctx, "python3 /host/extract.py '$rel'", 240)
        log("抽文本 exit=${r.first} 字符=${r.second.length}")
        return r.second.take(20000)
    }

    private fun fmt(n: Long): String = when {
        n >= 1L shl 20 -> String.format("%.1f MB", n / 1048576.0)
        n >= 1L shl 10 -> String.format("%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    companion object {
        val IMAGES = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
        val VIDEOS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")

        private val PY = """
import sys
p = sys.argv[1]
ext = p.lower().rsplit('.', 1)[-1]
try:
    if ext == 'pdf':
        from pdfminer.high_level import extract_text
        sys.stdout.write(extract_text(p) or '')
    elif ext == 'docx':
        import docx
        d = docx.Document(p)
        for para in d.paragraphs:
            sys.stdout.write(para.text + '\n')
    elif ext in ('xlsx', 'xlsm'):
        import openpyxl
        wb = openpyxl.load_workbook(p, read_only=True, data_only=True)
        for ws in wb.worksheets:
            sys.stdout.write('### ' + ws.title + '\n')
            for row in ws.iter_rows(values_only=True):
                sys.stdout.write('\t'.join('' if c is None else str(c) for c in row) + '\n')
    else:
        data = open(p, 'rb').read()
        sys.stdout.write(data.decode('utf-8', 'replace'))
except Exception:
    sys.stdout.write('')
""".trimIndent()
    }
}
