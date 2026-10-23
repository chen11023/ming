package dev.aj.miniapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var agent: AgentLoop
    private var term: TermuxTerm? = null

    private lateinit var chatView: TextView
    private lateinit var chatInput: EditText
    private lateinit var chatSend: android.widget.Button

    private lateinit var diag: TextView
    private lateinit var diagSend: android.widget.Button
    private lateinit var termHost: android.widget.FrameLayout
    private lateinit var termStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        agent = AgentLoop(this, settings) { ev -> onAgentEvent(ev) }
        loadUi()
        startBootstrap()
    }

    // ------------------------------------------------------------ 三页签

    private fun loadUi() {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F7F8.toInt())
        }

        val tabs = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val content = android.widget.FrameLayout(this)

        val chatPage = buildChatPage()
        val termPage = buildTermPage()
        val setPage = buildSettingsPage()

        val btns = listOf("聊天", "终端", "设置").map { name ->
            android.widget.Button(this).apply {
                text = name
                setOnClickListener { }
            }
        }

        root.addView(tabs)
        root.addView(
            content,
            android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
        )
        setContentView(root)

        val pages = listOf(chatPage, termPage, setPage)
        fun select(i: Int) {
            content.removeAllViews()
            content.addView(pages[i])
            btns.forEachIndexed { idx, b ->
                b.setTextColor(if (idx == i) 0xFF2563EB.toInt() else 0xFF6B7280.toInt())
            }
        }
        btns.forEachIndexed { i, b -> b.setOnClickListener { select(i) } }
        tabs.removeAllViews()
        btns.forEach { b ->
            tabs.addView(b, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        select(0)
    }

    // ------------------------------------------------------------ 聊天页

    private fun buildChatPage(): android.view.View {
        val v = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val scroll = android.widget.ScrollView(this)
        chatView = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, 24)
            setTextColor(0xFF111827.toInt())
        }
        scroll.addView(chatView)
        v.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        chatInput = EditText(this).apply {
            hint = "要它做什么…"
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        chatSend = android.widget.Button(this).apply {
            text = "发送"
            setOnClickListener { sendChat() }
        }
        row.addView(chatInput, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chatSend)
        v.addView(row)

        if (!settings.configured) {
            appendChat("提示", "还没填 API Key，去「设置」页填一下。默认接的是 DeepSeek。", 0xFFB45309.toInt())
        } else {
            appendChat("提示", "在下面的输入框说话。需要跑命令时我会自己执行。", 0xFF6B7280.toInt())
        }
        return v
    }

    private fun sendChat() {
        val t = chatInput.text.toString().trim()
        if (t.isEmpty()) return
        if (!settings.configured) {
            appendChat("错误", "先填 API Key。", 0xFFDC2626.toInt())
            return
        }
        chatInput.setText("")
        appendChat("你", t, 0xFF2563EB.toInt())
        chatSend.isEnabled = false
        thread {
            try { agent.send(t) } catch (e: Exception) {
                runOnUiThread {
                    appendChat("错误", "${e.javaClass.simpleName}: ${e.message}", 0xFFDC2626.toInt())
                }
            }
            runOnUiThread { chatSend.isEnabled = true }
        }
    }

    private fun onAgentEvent(ev: AgentLoop.Event) {
        runOnUiThread {
            when (ev) {
                is AgentLoop.Event.Thinking ->
                    appendChat("思考", ev.text, 0xFF6B7280.toInt())
                is AgentLoop.Event.Command ->
                    appendChat("执行", ev.cmd, 0xFF7C3AED.toInt())
                is AgentLoop.Event.Output ->
                    appendChat("输出", ev.text.ifBlank { "(无输出)" }, 0xFF059669.toInt())
                is AgentLoop.Event.Answer ->
                    appendChat("AJ", ev.text, 0xFF111827.toInt())
                is AgentLoop.Event.Error ->
                    appendChat("错误", ev.msg, 0xFFDC2626.toInt())
            }
        }
    }

    private fun appendChat(tag: String, body: String, color: Int) {
        val cur = chatView.text?.toString().orEmpty()
        chatView.text = if (cur.isEmpty()) "[$tag] $body" else "$cur\n\n[$tag] $body"
        chatView.setTextColor(Color.BLACK)
        val parent = chatView.parent as? android.view.View
        if (parent is android.widget.ScrollView) {
            parent.post { parent.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------ 终端页

    private fun buildTermPage(): android.view.View {
        val v = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0B0C.toInt())
        }
        termStatus = TextView(this).apply {
            text = "正在准备 Linux 环境…"
            textSize = 12f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(28, 16, 28, 16)
            setOnClickListener { attachTerminal() }
        }
        termHost = android.widget.FrameLayout(this)
        v.addView(termStatus)
        v.addView(termHost, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        return v
    }

    private fun attachTerminal() {
        if (term != null) return
        try {
            val t = TermuxTerm(this)
            val view = t.createView()
            termHost.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
            val r = t.start()
            term = t
            termStatus.text = if (r.isSuccess) "Alpine · proot · 点屏幕出键盘"
            else "启动失败：${r.exceptionOrNull()?.message}"
            view.requestFocus()
        } catch (e: Throwable) {
            termStatus.text = "终端创建失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ------------------------------------------------------------ 设置页

    private fun buildSettingsPage(): android.view.View {
        val v = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 20, 0, 6)
        }

        val baseIn = EditText(this).apply {
            setText(settings.baseUrl)
            hint = "https://api.deepseek.com"
        }
        val keyIn = EditText(this).apply {
            setText(settings.apiKey)
            hint = "sk-..."
            setSingleLine(true)
        }
        val modelIn = EditText(this).apply {
            setText(settings.model)
            hint = "deepseek-chat"
        }

        v.addView(label("接口地址"))
        v.addView(baseIn)
        v.addView(label("API Key"))
        v.addView(keyIn)
        v.addView(label("模型"))
        v.addView(modelIn)

        v.addView(android.widget.Button(this).apply {
            text = "保存"
            setOnClickListener {
                settings.baseUrl = baseIn.text.toString()
                settings.apiKey = keyIn.text.toString()
                settings.model = modelIn.text.toString()
                android.widget.Toast.makeText(this@MainActivity, "已保存", android.widget.Toast.LENGTH_SHORT).show()
            }
        })

        diag = TextView(this).apply {
            text = "点下面按钮检查 Linux 环境"
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 0)
            setTextColor(0xFF374151.toInt())
        }
        diagSend = android.widget.Button(this).apply {
            text = "自检"
            setOnClickListener {
                diag.text = "检查中…"
                thread {
                    val r = Linux.diagnose(this@MainActivity)
                    runOnUiThread { diag.text = r }
                }
            }
        }
        v.addView(diagSend)
        v.addView(diag)

        return android.widget.ScrollView(this).apply { addView(v) }
    }

    // ------------------------------------------------------------ 启动

    private fun startBootstrap() {
        thread {
            val r = Linux.extractRootfs(this) { n, name ->
                if (n % 40 == 0) runOnUiThread { termStatus.text = "解压 $n 个文件…" }
            }
            runOnUiThread {
                if (r.isFailure) {
                    termStatus.text = "解压失败：${r.exceptionOrNull()?.message}"
                    return@runOnUiThread
                }
                agent.ensureDirs()
                attachTerminal()
            }
        }
    }
}
