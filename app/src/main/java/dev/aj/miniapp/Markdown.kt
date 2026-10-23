package dev.aj.miniapp

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * 极简 Markdown 渲染：标题 / 粗体 / 行内代码 / 代码块 / 列表 / 链接。
 * 不引第三方库，够聊天用。
 */
object Markdown {

    fun render(src: String, baseColor: Int = 0xFF111827.toInt()): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val lines = src.split("\n")
        var inCode = false
        var codeStart = -1

        for ((i, raw) in lines.withIndex()) {
            val line = raw
            if (line.trimStart().startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    codeStart = out.length
                } else {
                    inCode = false
                    styleCode(out, codeStart, out.length)
                    codeStart = -1
                }
                if (i < lines.size - 1) out.append("\n")
                continue
            }
            if (inCode) {
                out.append(line)
                if (i < lines.size - 1) out.append("\n")
                continue
            }

            val start = out.length
            val heading = Regex("^(#{1,4})\\s+(.*)$").find(line)
            if (heading != null) {
                out.append(heading.groupValues[2])
                val end = out.length
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val size = when (heading.groupValues[1].length) {
                    1 -> 1.5f
                    2 -> 1.3f
                    else -> 1.15f
                }
                out.setSpan(android.text.style.RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (Regex("^\\s*[-*+]\\s+").containsMatchIn(line)) {
                out.append("  • ").append(line.replaceFirst(Regex("^\\s*[-*+]\\s+"), ""))
                inline(out, start + 2, out.length)
            } else {
                out.append(line)
                inline(out, start, out.length)
            }
            if (i < lines.size - 1) out.append("\n")
        }
        if (inCode && codeStart >= 0) styleCode(out, codeStart, out.length)
        return out
    }

    private fun styleCode(sb: SpannableStringBuilder, start: Int, end: Int) {
        if (end <= start) return
        sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(BackgroundColorSpan(0xFFF3F4F6.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 行内 **粗体**、`代码`、[文字](链接) */
    private fun inline(sb: SpannableStringBuilder, from: Int, to: Int) {
        if (to <= from) return
        val text = sb.substring(from, to)

        Regex("\\*\\*([^*]+)\\*\\*").findAll(text).forEach { m ->
            val s = from + m.range.first
            val e = from + m.range.last + 1
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("`([^`]+)`").findAll(text).forEach { m ->
            val s = from + m.range.first
            val e = from + m.range.last + 1
            sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(0xFFF3F4F6.toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").findAll(text).forEach { m ->
            val s = from + m.range.first
            val e = from + m.range.last + 1
            runCatching {
                sb.setSpan(URLSpan(m.groupValues[2]), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xFF2563EB.toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
