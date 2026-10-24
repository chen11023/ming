package dev.aj.miniapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 一套颜色，深/浅两套，改这里全 App 跟着变 */
object Palette {
    var dark = true

    fun bg() = if (dark) 0xFF0E0F12.toInt() else 0xFFF5F6F8.toInt()
    fun card() = if (dark) 0xFF17191F.toInt() else 0xFFFFFFFF.toInt()
    fun cardAlt() = if (dark) 0xFF23272F.toInt() else 0xFFEDF0F5.toInt()
    fun accent() = 0xFF5B8CFF.toInt()
    fun accentSoft() = if (dark) 0xFF2A3550.toInt() else 0xFFDCE6FF.toInt()
    fun text() = if (dark) 0xFFE6E8EE.toInt() else 0xFF14161B.toInt()
    fun sub() = if (dark) 0xFF8A90A0.toInt() else 0xFF6B7280.toInt()
    fun line() = if (dark) 0xFF2A2E38.toInt() else 0xFFE2E5EC.toInt()
    fun danger() = 0xFFFF6B6B.toInt()
    fun ok() = 0xFF34D399.toInt()
    fun warn() = 0xFFF5A524.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(ctx: Context, color: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
        if (stroke != null) setStroke(ctx.dp(strokeDp), stroke)
    }

/** 设置页的一块白/深卡片 */
fun cardBox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    background = roundBg(ctx, Palette.card(), 16f, Palette.line())
    setPadding(ctx.dp(16f), ctx.dp(6f), ctx.dp(16f), ctx.dp(6f))
}

/** 设置页的卡片小标题 */
fun groupTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 12f
    setTextColor(Palette.sub())
    setPadding(ctx.dp(6f), ctx.dp(14f), 0, ctx.dp(6f))
}

/** 卡片里的一行：标题 + 副标题 + 右箭头 */
fun settingRow(ctx: Context, title: String, sub: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ctx.dp(13f), 0, ctx.dp(13f))
    }
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(TextView(ctx).apply {
        text = title
        textSize = 15f
        setTextColor(Palette.text())
    })
    if (!sub.isNullOrBlank()) col.addView(TextView(ctx).apply {
        this.text = sub
        textSize = 12f
        setTextColor(Palette.sub())
        setPadding(0, ctx.dp(3f), 0, 0)
    })
    row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    if (onClick != null) {
        row.addView(TextView(ctx).apply {
            text = "›"
            textSize = 20f
            setTextColor(Palette.sub())
            setPadding(ctx.dp(8f), 0, 0, 0)
        })
        row.setOnClickListener { onClick() }
    }
    return row
}

/** 卡片之间的分隔线 */
fun divider(ctx: Context): View = View(ctx).apply {
    setBackgroundColor(Palette.line())
    layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(0.7f))
}

/**
 * 聊天气泡：把 transcript 按 [标签] 切成块，每块一个气泡。
 * 只在内容变化时改文字，流式刷新也不卡。
 */
class ChatBubbles(private val ctx: Context, private val onLongPress: (String, String) -> Unit) {

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(4f), ctx.dp(8f), ctx.dp(4f), ctx.dp(18f))
    }

    private val shown = ArrayList<String>()   // 已渲染块的原文
    private val views = ArrayList<TextView>()

    private var fontSize = 15f

    /** 字号变了就整段重画 */
    fun setFontSize(v: Float) {
        if (v == fontSize) return
        fontSize = v
        clear()
    }

    fun clear() {
        shown.clear(); views.clear(); box.removeAllViews()
    }

    fun render(text: String) {
        val blocks = parse(text)
        // 会话被清空/切换
        if (blocks.isEmpty()) { clear(); return }
        if (blocks.size < shown.size) clear()
        for (i in blocks.indices) {
            val body = blocks[i].second
            if (i < shown.size) {
                if (shown[i] != body) {
                    shown[i] = body
                    views[i].text = bodyText(blocks[i].first, body)
                }
            } else {
                val tv = newBubble(blocks[i].first)
                tv.text = bodyText(blocks[i].first, body)
                box.addView(wrap(blocks[i].first, tv))
                shown.add(body)
                views.add(tv)
            }
        }
    }

    private fun bodyText(tag: String, body: String): CharSequence =
        when (tag) {
            "执行", "输出" -> body
            "错误" -> body
            "提示", "系统", "定时", "提醒", "附件", "思考" -> body
            else -> Markdown.render(body)
        }

    private fun newBubble(tag: String): TextView {
        val tv = TextView(ctx).apply {
            textSize = fontSize
            setTextIsSelectable(true)
            setLineSpacing(ctx.dp(3f).toFloat(), 1f)
        }
        when (tag) {
            "你", "我" -> {
                tv.setTextColor(Palette.text())
                tv.setBackground(roundBg(ctx, Palette.accentSoft(), 16f))
                tv.setPadding(ctx.dp(14f), ctx.dp(10f), ctx.dp(14f), ctx.dp(10f))
            }
            "执行", "输出" -> {
                tv.typeface = Typeface.MONOSPACE
                tv.textSize = 12.5f
                tv.setTextColor(if (Palette.dark) 0xFFB8F5D0.toInt() else 0xFF1F5F3F.toInt())
                tv.setBackground(roundBg(ctx, Palette.cardAlt(), 12f))
                tv.setPadding(ctx.dp(12f), ctx.dp(10f), ctx.dp(12f), ctx.dp(10f))
            }
            "错误" -> {
                tv.setTextColor(Palette.danger())
                tv.setBackground(roundBg(ctx, Palette.card(), 14f, Palette.danger()))
                tv.setPadding(ctx.dp(14f), ctx.dp(10f), ctx.dp(14f), ctx.dp(10f))
            }
            "提示", "系统", "定时", "提醒", "附件", "思考" -> {
                tv.textSize = 12.5f
                tv.setTextColor(Palette.sub())
                tv.setPadding(ctx.dp(6f), ctx.dp(2f), ctx.dp(6f), ctx.dp(2f))
            }
            else -> {
                tv.setTextColor(Palette.text())
                tv.setBackground(roundBg(ctx, Palette.card(), 16f))
                tv.setPadding(ctx.dp(14f), ctx.dp(11f), ctx.dp(14f), ctx.dp(11f))
            }
        }
        tv.setOnLongClickListener {
            onLongPress(tag, tv.text.toString())
            true
        }
        return tv
    }

    private fun wrap(tag: String, tv: TextView): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(3f), 0, ctx.dp(3f))
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        when (tag) {
            "你", "我" -> { row.gravity = Gravity.END; lp.width = 0 }
            "提示", "系统", "定时", "提醒", "附件", "思考" -> row.gravity = Gravity.CENTER_HORIZONTAL
            "执行", "输出" -> lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            else -> lp.width = 0
        }
        if (lp.width == 0) {
            lp.weight = 1f
            lp.rightMargin = ctx.dp(28f)
            if (tag == "你" || tag == "我") lp.leftMargin = ctx.dp(28f)
        }
        // 每条正经消息配一个彩色小标签，别整屏都是灰字
        val quiet = tag == "提示" || tag == "系统" || tag == "定时" || tag == "提醒" || tag == "附件" || tag == "思考"
        if (quiet) {
            row.addView(tv, lp)
            return row
        }
        val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val color = when (tag) {
            "你", "我" -> Palette.accent()
            "错误" -> Palette.danger()
            "执行", "输出" -> Palette.warn()
            else -> Palette.accent()
        }
        holder.addView(TextView(ctx).apply {
            text = tag
            textSize = 10.5f
            setTextColor(color)
            letterSpacing = 0.08f
            setPadding(4, 0, 4, ctx.dp(2))
            gravity = if (tag == "你" || tag == "我") Gravity.END else Gravity.START
        }, LinearLayout.LayoutParams(-1, -2))
        holder.addView(tv, LinearLayout.LayoutParams(
            if (tag == "执行" || tag == "输出") LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        row.addView(holder, lp)
        return row
    }

    /** transcript → [(标签, 正文)] */
    private fun parse(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val re = Regex("(?:^|\\n\\n)\\[([^\\]]{1,12})\\] ")
        val ms = re.findAll(text).toList()
        for ((k, m) in ms.withIndex()) {
            val start = m.range.last + 1
            val end = if (k + 1 < ms.size) ms[k + 1].range.first else text.length
            if (start > end) continue
            val body = text.substring(start, end).trimEnd('\n')
            out.add(m.groupValues[1] to body)
        }
        return out
    }
}
