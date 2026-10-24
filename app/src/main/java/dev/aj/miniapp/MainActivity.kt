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
    private lateinit var chatStop: android.widget.Button
    private lateinit var modelLabel: TextView
    private lateinit var envStore: EnvStore
    private lateinit var mcp: McpManager
    private val transcript = StringBuilder()
    private val streamBuf = StringBuilder()

    private lateinit var diag: TextView
    private lateinit var diagSend: android.widget.Button
    private lateinit var termHost: android.widget.FrameLayout
    private lateinit var termStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        settings.migratePlaintext()          // 老版本明文 key 转密文
        MemoryStore(this).ensure()
        envStore = EnvStore(this)
        mcp = McpManager(this)
        agent = AgentLoop(this, settings, envStore) { ev -> onAgentEvent(ev) }
        loadUi()
        restoreSession()
        if (transcript.isEmpty()) {
            appendChat(
                "提示",
                if (!settings.configured) "还没填 API Key，去「设置」页填一下。默认接的是 DeepSeek。"
                else "在下面的输入框说话。需要跑命令时我会自己执行。",
                if (!settings.configured) 0xFFB45309.toInt() else 0xFF6B7280.toInt()
            )
        }
        thread { runCatching { Linux.cleanup(this@MainActivity) } }
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

    /** 聊天页顶部那条：显示当前模型，点一下换 */
    private fun modelBar(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 6, 10, 0)
        }
        modelLabel = TextView(this).apply {
            text = "模型：${settings.activeName} · ${settings.model}"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(4, 10, 4, 10)
            setOnClickListener { pickModel() }
        }
        row.addView(modelLabel, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun pickModel() {
        val ps = settings.profiles()
        val names = ps.map { "${it.name}（${it.model}）" }.toMutableList()
        names.add("新增配置 → 去「设置」页")
        android.app.AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < ps.size) {
                    settings.activeIndex = i
                    modelLabel.text = "模型：${ps[i].name} · ${ps[i].model}"
                    appendChat("系统", "已切到配置「${ps[i].name}」", 0xFF6B7280.toInt())
                } else {
                    android.widget.Toast.makeText(this, "去底部「设置」→ 模型配置", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

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
        v.addView(modelBar(), android.widget.LinearLayout.LayoutParams(-1, -2))
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
        chatStop = android.widget.Button(this).apply {
            text = "停"
            setVisibility(android.view.View.GONE)
            setTextColor(0xFFDC2626.toInt())
            setOnClickListener {
                agent.stop()
                appendChat("系统", "已发送停止指令", 0xFF6B7280.toInt())
            }
        }
        val historyBtn = android.widget.Button(this).apply {
            text = "☰"
            setOnClickListener { showSessions() }
        }
        row.addView(historyBtn)
        row.addView(attachBtn)
        row.addView(chatInput, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chatStop)
        row.addView(chatSend)
        v.addView(row)
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
        chatStop.visibility = android.view.View.VISIBLE
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
            runOnUiThread {
                chatSend.isEnabled = true
                chatStop.visibility = android.view.View.GONE
            }
        }
    }

    private fun onAgentEvent(ev: AgentLoop.Event) {
        runOnUiThread {
            when (ev) {
                is AgentLoop.Event.Delta -> {
                    streamBuf.append(ev.text)
                    if (streamBuf.length > 40000) streamBuf.delete(0, streamBuf.length - 20000)
                    scheduleStreamRender()
                }
                is AgentLoop.Event.Thinking ->
                    appendChat("思考", ev.text, 0xFF6B7280.toInt())
                is AgentLoop.Event.Command ->
                    appendChat("执行", ev.cmd, 0xFF7C3AED.toInt())
                is AgentLoop.Event.Output -> {
                    appendChat("输出", ev.text.ifBlank { "(无输出)" }, 0xFF059669.toInt())
                    outputs.render(outBox, turnStart)
                }
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
        data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i)?.uri?.let { uris += it } }
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

    /** 当前会话 id（和 AgentLoop 里的一一对应） */
    private var curSession = "last"

    private fun saveTranscript() {
        val t = transcript.toString()
        if (t.length > 300000) return
        runCatching { sessionFile("$curSession.txt").writeText(t) }
    }

    private fun restoreSession() {
        curSession = agent.sessionId
        // 老版本把聊天记录存在 chat.txt，第一次升级时并到当前会话名下
        if (!sessionFile("last.txt").exists() && sessionFile("chat.txt").exists()) {
            runCatching { sessionFile("chat.txt").renameTo(sessionFile("last.txt")) }
        }
        val f = sessionFile("$curSession.txt")
        if (f.exists()) runCatching {
            transcript.append(f.readText())
            renderAll()
        }
        val n = runCatching { agent.load() }.getOrDefault(0)
        if (n > 0) {
            appendChat("提示", "已恢复上次会话（$n 条消息）。长按「发送」可开新会话。", 0xFF6B7280.toInt(), false)
        }
    }

    private fun newSession() {
        val id = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        // startNew 会先把当前会话存盘，历史不会丢
        runCatching { agent.startNew(id) }
        curSession = id
        transcript.setLength(0)
        streamBuf.setLength(0)
        chatView.text = ""
        outBox.removeAllViews()
        pending.clear()
        attachBtn.text = "📎"
        appendChat("提示", "新会话开始（$id）。点「☰」可切回以前的会话。", 0xFF6B7280.toInt())
    }

    /** 会话列表：点一下切换，长按「删除当前」 */
    private fun showSessions() {
        val dir = java.io.File(filesDir, "sessions")
        val ids = (dir.list() ?: emptyArray())
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .filter { it != curSession || java.io.File(dir, "$it.json").length() > 2 }
            .sortedByDescending { java.io.File(dir, "$it.json").lastModified() }
        if (ids.isEmpty()) {
            appendChat("提示", "还没有历史会话。", 0xFF6B7280.toInt())
            return
        }
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        val items = ids.map { id ->
            val f = java.io.File(dir, "$id.json")
            val n = runCatching { org.json.JSONArray(f.readText()).length() }.getOrDefault(0)
            val mark = if (id == curSession) "▶ " else ""
            "$mark${sdf.format(java.util.Date(f.lastModified()))}  ${sessionTitle(f)}  (${n}条)"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("历史会话（点一行切换）")
            .setItems(items) { _, i -> switchSession(ids[i]) }
            .setNeutralButton("删除当前") { _, _ -> deleteCurrentSession() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从会话文件里抠出标题：第一条用户消息的前 26 个字 */
    private fun sessionTitle(f: java.io.File): String = runCatching {
        val arr = org.json.JSONArray(f.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("role") != "user") continue
            val c = o.opt("content")
            if (c is org.json.JSONArray) {
                for (k in 0 until c.length()) {
                    val part = c.optJSONObject(k) ?: continue
                    if (part.optString("type") == "text") {
                        return@runCatching part.optString("text").replace("\n", " ").take(26)
                    }
                }
                return@runCatching "(附件)"
            }
            return@runCatching c.toString().replace("\n", " ").take(26)
        }
        "(空)"
    }.getOrDefault("(空)")

    private fun switchSession(id: String) {
        if (id == curSession) return
        val n = runCatching { agent.switchTo(id) }.getOrDefault(0)
        curSession = id
        transcript.setLength(0)
        streamBuf.setLength(0)
        chatView.text = ""
        outBox.removeAllViews()
        runCatching {
            val f = sessionFile("$id.txt")
            if (f.exists()) {
                transcript.append(f.readText())
                renderAll()
            }
        }
        appendChat("提示", "已切到会话 $id（$n 条消息）", 0xFF6B7280.toInt(), false)
    }

    private fun deleteCurrentSession() {
        android.app.AlertDialog.Builder(this)
            .setMessage("删除当前会话 $curSession？删了就找不回来了。")
            .setPositiveButton("删除") { _, _ ->
                runCatching {
                    sessionFile("$curSession.txt").delete()
                    sessionFile("$curSession.json").delete()
                }
                newSession()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appendChat(tag: String, body: String, color: Int, save: Boolean = true) {
        streamBuf.setLength(0)
        if (transcript.isNotEmpty()) transcript.append("\n\n")
        transcript.append("[$tag] ").append(body)
        renderAll()
        if (save) saveTranscript()
        chatScroll.post { chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /** 流式期间别每个 token 都整段重绘，攒到 120ms 一次 */
    private var streamPending = false

    private fun scheduleStreamRender() {
        if (streamPending) return
        streamPending = true
        chatView.postDelayed({
            streamPending = false
            renderAll()
        }, 120)
    }

    /** 整段重绘：Markdown 渲染 + 流式中的临时尾巴 */
    private fun renderAll() {
        val sb = StringBuilder(transcript)
        if (streamBuf.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("[AJ] ").append(streamBuf)
        }
        val text = sb.toString()
        val shown = if (text.length > 400000) text.takeLast(400000) else text
        chatView.text = Markdown.render(shown)
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

        // ---------------- 多套模型配置（聊天页顶部切换）
        v.addView(label("模型配置列表（可存多套 key/地址，聊天页顶部点一下切换）"))
        val profBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        fun renderProfiles() {
            profBox.removeAllViews()
            val list = settings.profiles()
            if (list.isEmpty()) {
                profBox.addView(TextView(this).apply {
                    text = "（还没有，下面加一个）"
                    textSize = 12f
                    setTextColor(0xFF9CA3AF.toInt())
                })
            }
            for ((i, p) in list.withIndex()) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                val active = i == settings.activeIndex
                row.addView(TextView(this).apply {
                    text = "${if (active) "● " else "○ "}${p.name}  ${p.model}"
                    textSize = 13f
                    setPadding(0, 12, 0, 12)
                    setOnClickListener {
                        settings.activeIndex = i
                        renderProfiles()
                        modelLabel.text = "模型：${p.name} · ${p.model}"
                    }
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(android.widget.Button(this).apply {
                    text = "删"
                    textSize = 12f
                    setOnClickListener {
                        val now = settings.profiles()
                        if (i in now.indices) {
                            now.removeAt(i)
                            settings.saveProfiles(now)
                            if (settings.activeIndex >= now.size) settings.activeIndex = 0
                            renderProfiles()
                        }
                    }
                })
                profBox.addView(row)
            }
        }
        renderProfiles()
        v.addView(profBox)
        val pn = EditText(this).apply { hint = "配置名，如 智谱" }
        val pb = EditText(this).apply { hint = "接口地址 https://.../v1" }
        val pm = EditText(this).apply { hint = "模型名 gpt-4o-mini" }
        val pk = EditText(this).apply { hint = "API Key" }
        v.addView(pn)
        v.addView(pb)
        v.addView(pm)
        v.addView(pk)
        v.addView(android.widget.Button(this).apply {
            text = "新增并启用"
            setOnClickListener {
                val name = pn.text.toString().trim().ifBlank { "配置${settings.profiles().size + 1}" }
                val url = pb.text.toString().trim().ifBlank { settings.baseUrl }
                val md = pm.text.toString().trim().ifBlank { settings.model }
                val k = pk.text.toString().trim()
                val now = settings.profiles()
                now.add(Settings.Profile(name, url.trimEnd('/'), md, Vault.encrypt(k)))
                settings.saveProfiles(now)
                settings.activeIndex = now.size - 1
                renderProfiles()
                modelLabel.text = "模型：$name · $md"
                pn.setText(""); pb.setText(""); pm.setText(""); pk.setText("")
                android.widget.Toast.makeText(
                    this@MainActivity, "已新增并启用「$name」", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        })

        // ---------------- MCP 服务
        v.addView(label("MCP 服务（stdio 命令行，例：python3 /host/mcp/demo.py）"))
        val mcpBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        fun renderMcp() {
            mcpBox.removeAllViews()
            val list = mcp.servers()
            if (list.isEmpty()) {
                mcpBox.addView(TextView(this).apply {
                    text = "（还没有 MCP 服务）"
                    textSize = 12f
                    setTextColor(0xFF9CA3AF.toInt())
                })
            }
            for (s in list) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                row.addView(TextView(this).apply {
                    text = "${s.name}  ${
                        if (s.enabled) "" else "[已停] "
                    }${s.command}"
                    textSize = 12f
                    setPadding(0, 12, 0, 12)
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(android.widget.Button(this).apply {
                    text = if (s.enabled) "停" else "启"
                    textSize = 12f
                    setOnClickListener {
                        mcp.setEnabled(s.name, !s.enabled)
                        agent.refreshMcp()
                        renderMcp()
                    }
                })
                row.addView(android.widget.Button(this).apply {
                    text = "删"
                    textSize = 12f
                    setOnClickListener {
                        mcp.remove(s.name)
                        agent.refreshMcp()
                        renderMcp()
                    }
                })
                mcpBox.addView(row)
            }
        }
        renderMcp()
        v.addView(mcpBox)
        val mn = EditText(this).apply { hint = "名字，如 files" }
        val mc = EditText(this).apply { hint = "启动命令，如 python3 /host/mcp/demo.py" }
        v.addView(mn)
        v.addView(mc)
        val mcpBtn = android.widget.Button(this).apply {
            text = "添加 MCP 服务并测连通"
            setOnClickListener {
                val name = mn.text.toString().trim()
                val cmd = mc.text.toString().trim()
                if (name.isBlank() || cmd.isBlank()) return@setOnClickListener
                mcp.add(McpManager.Server(name, cmd, true))
                agent.refreshMcp()
                renderMcp()
                text = "连接中…"
                thread {
                    val r = McpManager(this@MainActivity).probe(name)
                    runOnUiThread {
                        text = "添加 MCP 服务并测连通"
                        android.widget.Toast.makeText(
                            this@MainActivity, r, android.widget.Toast.LENGTH_LONG
                        ).show()
                        mn.setText(""); mc.setText("")
                    }
                }
            }
        }
        v.addView(mcpBtn)

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
