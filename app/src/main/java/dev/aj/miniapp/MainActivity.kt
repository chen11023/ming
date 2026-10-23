package dev.aj.miniapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var agent: AgentLoop
    private var term: TermuxTerm? = null

    private lateinit var chatView: TextView
    private lateinit var chatScroll: android.widget.ScrollView
    private lateinit var outBox: LinearLayout
    private val outputs by lazy { Outputs(this) }
    private var turnStart = 0L
    private val attach by lazy { Attach(this) }
    private val pending = mutableListOf<java.io.File>()
    private lateinit var attachBtn: android.widget.Button
    private val PICK = 1001
    private lateinit var chatInput: EditText
    private lateinit var chatSend: android.widget.Button

    private lateinit var diag: TextView
    private lateinit var diagSend: android.widget.Button
    private lateinit var termHost: android.widget.FrameLayout
    private lateinit var termStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        settings.migratePlaintext()          // 老版本明文 key 转密文
        MemoryStore(this).ensure()
        agent = AgentLoop(this, settings) { ev -> onAgentEvent(ev) }
        loadUi()
        restoreSession()
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
        outBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chatCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chatView)
            addView(outBox)
        }
        scroll.addView(chatCol)
        chatScroll = scroll
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
            setOnLongClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage("开始新会话？当前对话会清空，记忆和技能保留。")
                    .setPositiveButton("清空") { _, _ -> newSession() }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }
        attachBtn = android.widget.Button(this).apply {
            text = "📎"
            setOnClickListener { pickFiles() }
        }
        row.addView(attachBtn)
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
        turnStart = System.currentTimeMillis() - 2000
        if (t.isEmpty() && pending.isEmpty()) return
        if (!settings.configured) {
            appendChat("错误", "先填 API Key。", 0xFFDC2626.toInt())
            return
        }
        chatInput.setText("")
        appendChat("你", t.ifBlank { "(附件)" }, 0xFF2563EB.toInt())
        val files = pending.toList()
        pending.clear()
        attachBtn.text = "📎"
        chatSend.isEnabled = false
        thread {
            val parts = try {
                files.flatMap { f ->
                    attach.parts(f) { line ->
                        runOnUiThread { appendChat("附件", line, 0xFF6B7280.toInt()) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { appendChat("错误", "附件处理失败: ${e.message}", 0xFFDC2626.toInt()) }
                emptyList()
            }
            try {
                agent.send(t.ifBlank { "看看这些附件" }, parts)
            } catch (e: Exception) {
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
                is AgentLoop.Event.Answer -> {
                    appendChat("AJ", ev.text, 0xFF111827.toInt())
                    outputs.render(outBox, turnStart)
                }
                is AgentLoop.Event.Error ->
                    appendChat("错误", ev.msg, 0xFFDC2626.toInt())
            }
        }
    }

    private fun pickFiles() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(i, PICK) }.onFailure {
            appendChat("错误", "打不开文件选择器: ${it.message}", 0xFFDC2626.toInt())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { c -> for (i in 0 until c.count) c.getItemAt(i)?.uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isEmpty()) return
        thread {
            for (u in uris) {
                runCatching { attach.copy(u) }.onSuccess { f ->
                    runOnUiThread {
                        pending += f
                        attachBtn.text = "📎" + pending.size
                        appendChat("附件", attach.name(f) + "  " + kb(f.length()), 0xFF6B7280.toInt())
                    }
                }.onFailure { e ->
                    runOnUiThread { appendChat("错误", "附件失败: ${e.message}", 0xFFDC2626.toInt()) }
                }
            }
        }
    }

    private fun kb(n: Long): String = when {
        n >= 1L shl 20 -> String.format("%.1f MB", n / 1048576.0)
        n >= 1L shl 10 -> String.format("%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    private fun sessionFile(name: String) = java.io.File(filesDir, "sessions/$name").apply { parentFile?.mkdirs() }

    private fun saveTranscript() {
        val t = chatView.text?.toString().orEmpty()
        if (t.length > 300000) return
        runCatching { sessionFile("chat.txt").writeText(t) }
    }

    private fun restoreSession() {
        val f = sessionFile("chat.txt")
        if (f.exists()) runCatching { chatView.text = f.readText() }
        val n = runCatching { agent.load() }.getOrDefault(0)
        if (n > 0) {
            appendChat("提示", "已恢复上次会话（$n 条消息）。长按「发送」可开新会话。", 0xFF6B7280.toInt())
        }
    }

    private fun newSession() {
        runCatching { agent.reset() }
        chatView.text = ""
        outBox.removeAllViews()
        pending.clear()
        attachBtn.text = "📎"
        runCatching {
            sessionFile("chat.txt").delete()
            sessionFile("last.json").delete()
        }
        appendChat("提示", "新会话开始。", 0xFF6B7280.toInt())
    }

    private fun appendChat(tag: String, body: String, color: Int) {
        val cur = chatView.text?.toString().orEmpty()
        chatView.text = if (cur.isEmpty()) "[$tag] $body" else "$cur\n\n[$tag] $body"
        chatView.setTextColor(Color.BLACK)
        saveTranscript()
        chatScroll.post { chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
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
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        fun mkBtn(label: String, act: () -> Unit): android.widget.Button =
            android.widget.Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { act() }
            }
        val lp = { android.widget.LinearLayout.LayoutParams(0, -2, 1f) }
        bar.addView(mkBtn("键盘") { showKb() }, lp())
        bar.addView(mkBtn("粘贴") {
            if (term?.pasteFromClipboard() != true) termStatus.text = "剪贴板是空的"
        }, lp())
        bar.addView(mkBtn("复制") {
            termStatus.text = if (term?.copySelection() == true) "已复制选中内容"
            else "先在终端里长按选中文字"
        }, lp())
        v.addView(termStatus)
        v.addView(bar)
        v.addView(termHost, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        return v
    }

    private fun showKb() {
        val tv = term?.view ?: return
        tv.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(tv, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun attachTerminal() {
        if (term != null) return
        try {
            val t = TermuxTerm(this)
            val view = t.createView()
            termHost.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
            val r = t.start()
            term = t
            val dx = java.io.File(filesDir, "rootfs").let { d ->
                "rootfs busybox=" +
                    java.io.File(d, "bin/busybox").length() + "B"
            }
            termStatus.text = if (r.isSuccess) "Alpine · proot · 点屏幕出键盘\n$dx"
            else "启动失败：${r.exceptionOrNull()?.message}"
            view.requestFocus()
            view.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 400)
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

        // ---------------- 环境变量 / 密钥（Keystore 加密）
        v.addView(label("环境变量 / 密钥（AES 加密存储，注入终端和聊天命令的环境，不落盘"))
        val envBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        fun renderEnv() {
            envBox.removeAllViews()
            val list = EnvStore(this@MainActivity).all()
            if (list.isEmpty()) {
                envBox.addView(TextView(this@MainActivity).apply {
                    text = "（空）"
                    textSize = 12f
                    setTextColor(0xFF9CA3AF.toInt())
                })
            }
            for ((k, value) in list) {
                val row = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                val masked = if (value.length <= 4) "****"
                else value.substring(0, 2) + "****" + value.substring(value.length - 2)
                row.addView(TextView(this@MainActivity).apply {
                    text = "$k = $masked"
                    textSize = 13f
                    setPadding(0, 12, 0, 12)
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(android.widget.Button(this@MainActivity).apply {
                    text = "删"
                    textSize = 12f
                    setOnClickListener {
                        EnvStore(this@MainActivity).remove(k)
                        renderEnv()
                    }
                })
                envBox.addView(row)
            }
        }
        v.addView(envBox)
        val envK = EditText(this).apply { hint = "变量名，如 MY_API_KEY" }
        val envV = EditText(this).apply { hint = "值" }
        v.addView(envK)
        v.addView(envV)
        v.addView(android.widget.Button(this).apply {
            text = "添加 / 覆盖"
            setOnClickListener {
                val k = envK.text.toString().trim()
                val value = envV.text.toString()
                if (!k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "变量名只能字母数字下划线，不能数字开头",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    EnvStore(this@MainActivity).put(k, value)
                    envK.setText("")
                    envV.setText("")
                    renderEnv()
                    android.widget.Toast.makeText(
                        this@MainActivity, "已加密保存", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        renderEnv()

        // ---------------- 技能
        v.addView(label("技能（放 filesDir/skills/<名字>/SKILL.md，命中就拼进系统提示）"))
        val skillBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        fun renderSkills() {
            skillBox.removeAllViews()
            val store = SkillsStore(this@MainActivity)
            val names = store.names()
            if (names.isEmpty()) {
                skillBox.addView(TextView(this@MainActivity).apply {
                    text = "（还没加技能）"
                    textSize = 12f
                    setTextColor(0xFF9CA3AF.toInt())
                })
            }
            for (n in names) {
                val row = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = n + "  (" + store.read(n).length + " 字)"
                    textSize = 13f
                    setPadding(0, 12, 0, 12)
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(android.widget.Button(this@MainActivity).apply {
                    text = "删"
                    textSize = 12f
                    setOnClickListener {
                        store.delete(n)
                        renderSkills()
                    }
                })
                skillBox.addView(row)
            }
        }
        v.addView(skillBox)
        val skillName = EditText(this).apply { hint = "技能名，如 画图" }
        val skillBody = EditText(this).apply {
            hint = "SKILL.md 正文（可以带 --- description: ... --- 头）"
            minLines = 3
        }
        v.addView(skillName)
        v.addView(skillBody)
        v.addView(android.widget.Button(this).apply {
            text = "保存技能"
            setOnClickListener {
                val n = skillName.text.toString().trim()
                val body = skillBody.text.toString()
                if (n.isBlank() || body.isBlank()) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "名字和正文都要填", android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    SkillsStore(this@MainActivity).save(n, body)
                    skillName.setText("")
                    skillBody.setText("")
                    renderSkills()
                    android.widget.Toast.makeText(
                        this@MainActivity, "已保存", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        renderSkills()

        // ---------------- 记忆
        v.addView(label("记忆（GLOBAL.md + 今天/昨天，每次对话注入）"))
        val memView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(0xFF374151.toInt())
            text = MemoryStore(this@MainActivity).recent(2000).ifBlank { "（还没有记忆）" }
        }
        v.addView(memView)
        val memBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        memBar.addView(android.widget.Button(this).apply {
            text = "刷新"
            setOnClickListener {
                memView.text = MemoryStore(this@MainActivity).recent(2000).ifBlank { "（还没有记忆）" }
            }
        })
        memBar.addView(android.widget.Button(this).apply {
            text = "清空今天"
            setOnClickListener {
                MemoryStore(this@MainActivity).clearToday()
                memView.text = MemoryStore(this@MainActivity).recent(2000).ifBlank { "（还没有记忆）" }
                android.widget.Toast.makeText(
                    this@MainActivity, "已清空今天的记忆", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        })
        v.addView(memBar)

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
