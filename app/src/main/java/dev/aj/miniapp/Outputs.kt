package dev.aj.miniapp

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/**
 * 聊天里回显产物：/out（= filesDir/out）下这一轮新生成的文件。
 * 图片内联，视频给首帧+时长点开系统播放器，其它格式给可点卡片走系统预览。
 */
class Outputs(private val act: Activity) {

    private val imgExt = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")
    private val vidExt = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v")

    fun render(box: LinearLayout, since: Long) {
        box.removeAllViews()
        val dir = File(act.filesDir, "out")
        val files = dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.lastModified() >= since }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (f in files) box.addView(view(f))
    }

    private fun view(f: File): View {
        val ext = f.extension.lowercase()
        return when {
            ext in imgExt -> image(f)
            ext in vidExt -> video(f)
            else -> row(f)
        }
    }

    private fun image(f: File): View {
        val iv = ImageView(act)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1440) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, opts))
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(0, 8, 0, 8)
        iv.setOnClickListener { open(f) }
        return iv
    }

    private fun video(f: File): View {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var durationMs = 0L
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            if (frame != null) {
                val iv = ImageView(act)
                iv.setImageBitmap(frame)
                iv.adjustViewBounds = true
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setOnClickListener { open(f) }
                box.addView(iv)
            }
        }
        box.addView(TextView(act).apply {
            text = "▶ ${f.name}  ${durationMs / 1000}s  ${size(f.length())}（点击播放）"
            textSize = 13f
            setTextColor(0xFF2563EB.toInt())
            setPadding(0, 4, 0, 10)
            setOnClickListener { open(f) }
        })
        return box
    }

    private fun row(f: File): View = TextView(act).apply {
        text = "📄 ${f.name}  ${size(f.length())}（点击打开）"
        textSize = 13f
        setTextColor(0xFF2563EB.toInt())
        setPadding(0, 6, 0, 10)
        setOnClickListener { open(f) }
    }

    private fun size(n: Long): String = when {
        n >= 1L shl 20 -> String.format("%.1f MB", n / 1048576.0)
        n >= 1L shl 10 -> String.format("%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    private fun open(f: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(act, act.packageName + ".files", f)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Throwable) {
            Toast.makeText(act, "打不开: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
