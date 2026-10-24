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

    /** 语音：TTS 朗读 + 麦克风输入 */
    private var tts: android.speech.tts.TextToSpeech? = null
    private var sr: android.speech.SpeechRecognizer? = null
    private var lastAnswer = ""
    /** 本次对话里点过「全允许」就不再问了 */
    private var allowAllThisSession = false
    private val EXPORT_CODE = 3001
    private val IMPORT_CODE = 3002

    private lateinit var rootView: android.view.View
    private lateinit var chatPageView: android.view.View

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
        agent.onConfirm = { what -> askConfirm(what) }
        runCatching {
            tts = android.speech.tts.TextToSpeech(this) { st ->
                if (st != android.speech.tts.TextToSpeech.SUCCESS) tts = null
            }
        }
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

        rootView = root

        val tabs = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val content = android.widget.FrameLayout(this)

        val chatPage = buildChatPage()
        chatPageView = chatPage
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
        applyTheme()
    }

    /** 深色 / 字号，改完立刻生效 */
    private fun applyTheme() {
        val dark = settings.dark
        val bg = if (dark) 0xFF0F1115.toInt() else 0xFFF7F7F8.toInt()
        val fg = if (dark) 0xFFE5E7EB.toInt() else 0xFF111827.toInt()
        runCatching {
            rootView.setBackgroundColor(bg)
            chatPageView.setBackgroundColor(bg)
            chatView.setBackgroundColor(bg)
            chatView.setTextColor(fg)
            chatView.textSize = settings.chatFont
            chatInput.setTextColor(fg)
        }
        runCatching { renderAll() }
    }

    /** 工具执行前的确认卡：卡住后台线程等宿主点 */
    private fun askConfirm(what: String): Boolean {
        if (!settings.confirmExec || allowAllThisSession) return true
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return true
        val latch = java.util.concurrent.CountDownLatch(1)
        var yes = false
        runOnUiThread {
            val d = android.app.AlertDialog.Builder(this)
                .setTitle("要动手了")
                .setMessage(what.take(900))
                .setPositiveButton("允许") { _, _ -> yes = true; latch.countDown() }
                .setNeutralButton("本次全允许") { _, _ ->
                    allowAllThisSession = true; yes = true; latch.countDown()
                }
                .setNegativeButton("拒绝") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .create()
            d.show()
        }
        runCatching { latch.await(120, java.util.concurrent.TimeUnit.SECONDS) }
        return yes
    }

    /** 朗读、通知：完成时用 */
    private fun speak(text: String) {
        val t = tts ?: return
        runCatching {
            t.setLanguage(java.util.Locale.CHINA)
            t.speak(text.take(600), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "aj")
        }
    }

    private fun notifyDone(text: String) {
        if (!settings.notifyDone) return
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "aj-done"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(ch, "任务完成", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val n = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(this, ch)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }
            nm.notify(1, n
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("AJ 说完了")
                .setContentText(text.take(60))
                .setAutoCancel(true)
                .build())
        }
    }

    /** 麦克风：系统识别服务转文字，填进输入框 */
    private fun startVoice() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2002)
            return
        }
        runCatching {
            if (sr == null) sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            }
            sr?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: android.os.Bundle?) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
                override fun onError(e: Int) {
                    runOnUiThread { appendChat("提示", "没听清（code=$e），再点一次", 0xFFB45309.toInt(), false) }
                }
                override fun onResults(b: android.os.Bundle?) {
                    val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    runOnUiThread {
                        if (t.isNotBlank()) {
                            chatInput.setText(t)
                            chatInput.setSelection(t.length)
                        }
                    }
                }
            })
            sr?.startListening(i)
            appendChat("提示", "说话…", 0xFF6B7280.toInt(), false)
        }.onFailure {
            appendChat("错误", "麦克风起不来：${it.message}", 0xFFDC2626.toInt(), false)
        }
    }

    override fun onRequestPermissionsResult(code: Int, ps: Array<out String>, rs: IntArray) {
        super.onRequestPermissionsResult(code, ps, rs)
        if (code == 2002) {
            if (rs.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
            else appendChat("提示", "没给麦克风权限，语音输入用不了。", 0xFFB45309.toInt(), false)
        }
    }

    override fun onDestroy() {
        runCatching { tts?.shutdown() }
        runCatching { sr?.destroy() }
        super.onDestroy()
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
        row.addView(android.widget.TextView(this).apply {
            text = "🔊"
            textSize = 16f
            setPadding(18, 10, 18, 10)
            setTextColor(if (settings.speakReplies) 0xFF2563EB.toInt() else 0xFF9CA3AF.toInt())
            setOnClickListener {
                settings.speakReplies = !settings.speakReplies
                setTextColor(if (settings.speakReplies) 0xFF2563EB.toInt() else 0xFF9CA3AF.toInt())
                if (settings.speakReplies && lastAnswer.isNotBlank()) speak(lastAnswer)
            }
        })
        row.addView(android.widget.TextView(this).apply {
            text = "🎤"
            textSize = 16f
            setPadding(6, 10, 12, 10)
            setOnClickListener { startVoice() }
        })
        return row
    }

    private fun pickModel() {
        val ps = settings.providers()
        if (ps.isEmpty()) {
            android.widget.Toast.makeText(this, "还没有服务商，去「设置」页加一个", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val items = mutableListOf<String>()
        val targets = mutableListOf<Pair<Int, String>>()
        ps.forEachIndexed { i, p ->
            if (p.models.isEmpty()) {
                items += "${p.name}（还没加模型）"
                targets += i to ""
            } else {
                p.models.forEach { m ->
                    items += "${p.name} · $m"
                    targets += i to m
                }
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("选模型")
            .setItems(items.toTypedArray()) { _, k ->
                val t = targets[k]
                if (t.second.isBlank()) {
                    android.widget.Toast.makeText(this, "去「设置」→ 服务商，给 ${ps[t.first].name} 加模型",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    settings.pickModel(t.first, t.second)
                    modelLabel.text = "模型：${ps[t.first].name} · ${t.second}"
                    appendChat("系统", "已切到「${ps[t.first].name} · ${t.second}」", 0xFF6B7280.toInt())
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
                    lastAnswer = ev.text
                    outputs.render(outBox, turnStart)
                    if (settings.speakReplies) speak(ev.text)
                    if (!hasWindowFocus()) notifyDone(ev.text)
                }
                is AgentLoop.Event.Stat -> {
                    settings.usageAdd(ev.calls, ev.chars, ev.ms)
                    if (ev.calls > 0) appendChat(
                        "统计",
                        "${ev.calls} 次模型调用 · ${String.format("%.1f", ev.chars / 10000.0)} 万字符 · " +
                            "${String.format("%.1f", ev.ms / 1000.0)}s",
                        0xFF9CA3AF.toInt(), false
                    )
                }
                is AgentLoop.Event.Error ->
                    appendChat("错误", ev.msg, 0xFFDC2626.toInt())
            }
        }
    }

    private var pendingExport: String? = null

    private fun startExport() {
        pendingExport = buildExport()
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(
                android.content.Intent.EXTRA_TITLE,
                "aj-config-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date()) + ".json"
            )
        }
        runCatching { startActivityForResult(i, EXPORT_CODE) }
            .onFailure { appendChat("错误", "打不开保存对话框：${it.message}", 0xFFDC2626.toInt(), false) }
    }

    private fun startImport() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(i, IMPORT_CODE) }
            .onFailure { appendChat("错误", "打不开文件选择器：${it.message}", 0xFFDC2626.toInt(), false) }
    }

    /** 设置 + MCP 列表打成一份 JSON */
    private fun buildExport(): String {
        val o = org.json.JSONObject(settings.exportConfig())
        runCatching {
            val f = java.io.File(filesDir, "mcp/servers.json")
            if (f.exists()) o.put("mcp", org.json.JSONArray(f.readText()))
        }
        return o.toString(2)
    }

    private fun applyImport(text: String) {
        val msg = settings.importConfig(text)
        runCatching {
            val m = org.json.JSONObject(text).optJSONArray("mcp")
            if (m != null) {
                val d = java.io.File(filesDir, "mcp").apply { mkdirs() }
                java.io.File(d, "servers.json").writeText(m.toString())
                agent.refreshMcp()
            }
        }
        allowAllThisSession = false
        applyTheme()
        appendChat("提示", "配置$msg，MCP 列表已同步。", 0xFF16A34A.toInt(), false)
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
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == EXPORT_CODE) {
            val uri = data.data ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write((pendingExport ?: "").toByteArray()) }
            }.onSuccess {
                appendChat("提示", "配置已导出到所选位置。", 0xFF16A34A.toInt(), false)
            }.onFailure {
                appendChat("错误", "导出失败：${it.message}", 0xFFDC2626.toInt(), false)
            }
            return
        }
        if (requestCode == IMPORT_CODE) {
            val uri = data.data ?: return
            val t = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            }.getOrElse {
                appendChat("错误", "读不到文件：${it.message}", 0xFFDC2626.toInt(), false)
                return
            }
            if (t.isBlank()) {
                appendChat("错误", "文件是空的。", 0xFFDC2626.toInt(), false)
                return
            }
            runCatching { applyImport(t) }.onFailure {
                appendChat("错误", "导入失败：${it.message}", 0xFFDC2626.toInt(), false)
            }
            return
        }

        if (requestCode != PICK) return
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

    private fun usageLine(): String {
        val ms = settings.usageMs
        return "${settings.usageCalls} 次调用 · ${String.format("%.1f", settings.usageChars / 10000.0)} 万字符 · " +
            "累计 ${ms / 60000} 分 ${(ms % 60000) / 1000} 秒"
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
        allowAllThisSession = false
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
        allowAllThisSession = false
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

        // ---------------- 服务商（地址 + key）与其下的模型
        v.addView(label("服务商与模型"))
        val provBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val modelBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }

        fun syncBar() {
            modelLabel.text = "模型：${settings.activeName} · ${settings.model}"
        }

        fun renderProviders() {
            provBox.removeAllViews()
            modelBox.removeAllViews()
            val ps = settings.providers()
            if (ps.isEmpty()) {
                provBox.addView(TextView(this).apply {
                    text = "还没有服务商。在下面加一个：名字 + 接口地址 + key，一次填好。"
                    textSize = 12f
                    setTextColor(0xFF9CA3AF.toInt())
                })
                return
            }
            val ai = settings.activeIndex
            for ((i, p) in ps.withIndex()) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                val dotP = if (i == ai) "●" else "○"
                row.addView(TextView(this).apply {
                    text = "$dotP ${p.name}\n${p.baseUrl}"
                    textSize = 13f
                    setPadding(0, 14, 0, 14)
                    setOnClickListener {
                        settings.activeIndex = i
                        settings.activeModelName = p.models.firstOrNull() ?: ""
                        renderProviders()
                        syncBar()
                    }
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(android.widget.Button(this).apply {
                    text = "删"
                    textSize = 12f
                    setOnClickListener {
                        settings.delProvider(i)
                        renderProviders()
                        syncBar()
                    }
                })
                provBox.addView(row)
            }
            val cur = ps.getOrNull(ai) ?: return
            modelBox.addView(TextView(this).apply {
                text = "「${cur.name}」下的模型（点一下就切过去）"
                textSize = 12f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, 16, 0, 4)
            })
            if (cur.models.isEmpty()) {
                modelBox.addView(TextView(this).apply {
                    text = "还没模型，下面加一个"
                    textSize = 12f
                    setTextColor(0xFF9CA3AF.toInt())
                })
            }
            for (m in cur.models.toList()) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                val dotM = if (m == settings.model) "●" else "○"
                row.addView(TextView(this).apply {
                    text = "$dotM $m"
                    textSize = 13f
                    setPadding(0, 12, 0, 12)
                    setOnClickListener {
                        settings.pickModel(ai, m)
                        renderProviders()
                        syncBar()
                    }
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(android.widget.Button(this).apply {
                    text = "删"
                    textSize = 12f
                    setOnClickListener {
                        settings.delModel(m)
                        renderProviders()
                        syncBar()
                    }
                })
                modelBox.addView(row)
            }
        }
        renderProviders()
        v.addView(provBox)
        v.addView(modelBox)

        // 给当前服务商加模型：不用再填地址和 key
        val addModelIn = EditText(this).apply { hint = "模型名，如 glm-4.6" }
        v.addView(addModelIn)
        v.addView(android.widget.Button(this).apply {
            text = "加到当前服务商"
            setOnClickListener {
                val m = addModelIn.text.toString().trim()
                if (m.isBlank()) return@setOnClickListener
                settings.addModel(m)
                addModelIn.setText("")
                renderProviders()
                syncBar()
            }
        })

        // 新增服务商
        v.addView(label("新增服务商（地址和 key 只填这一次）"))
        val pnIn = EditText(this).apply { hint = "名字，如 智谱" }
        val pbIn = EditText(this).apply { hint = "接口地址，如 https://open.bigmodel.cn/api/paas/v4" }
        val pkIn = EditText(this).apply { hint = "API Key"; setSingleLine(true) }
        v.addView(pnIn)
        v.addView(pbIn)
        v.addView(pkIn)
        v.addView(android.widget.Button(this).apply {
            text = "新增服务商"
            setOnClickListener {
                val name = pnIn.text.toString().trim()
                val url = pbIn.text.toString().trim()
                val k = pkIn.text.toString().trim()
                if (url.isBlank() || k.isBlank()) {
                    android.widget.Toast.makeText(this@MainActivity, "地址和 key 都要填",
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settings.addProvider(name, url, k)
                pnIn.setText("")
                pbIn.setText("")
                pkIn.setText("")
                renderProviders()
                syncBar()
            }
        })

        // ---------------- 界面与行为
        v.addView(label("界面与行为"))
        fun chk(name: String, get: () -> Boolean, set: (Boolean) -> Unit): android.widget.CheckBox =
            android.widget.CheckBox(this).apply {
                text = name
                textSize = 13f
                isChecked = get()
                setOnCheckedChangeListener { _, b -> set(b) }
            }
        v.addView(chk("深色模式", { settings.dark }, { settings.dark = it; applyTheme() }))
        v.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "字号  ${settings.chatFont.toInt()}"
                textSize = 13f
                setPadding(0, 16, 16, 0)
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.Button(this@MainActivity).apply {
                text = "A-"
                setOnClickListener { settings.chatFont = settings.chatFont - 1f; applyTheme() }
            })
            addView(android.widget.Button(this@MainActivity).apply {
                text = "A+"
                setOnClickListener { settings.chatFont = settings.chatFont + 1f; applyTheme() }
            })
        })
        v.addView(chk("执行命令 / 出图前弹确认卡", { settings.confirmExec }, { settings.confirmExec = it }))
        v.addView(chk("回答自动朗读", { settings.speakReplies }, { settings.speakReplies = it }))
        v.addView(chk("任务完成发系统通知", { settings.notifyDone }, { settings.notifyDone = it }))

        // ---------------- 用量
        v.addView(label("用量（累计，跨会话）"))
        val usageTv = TextView(this).apply {
            text = usageLine()
            textSize = 13f
        }
        v.addView(usageTv)
        v.addView(android.widget.Button(this).apply {
            text = "清零"
            setOnClickListener {
                settings.usageReset()
                usageTv.text = usageLine()
            }
        })

        // ---------------- 配置搬家
        v.addView(label("配置导出 / 导入"))
        v.addView(TextView(this).apply {
            text = "导出成一份 JSON：配置档（key 明文，自己保管）、系统提示、开关、MCP 列表。换机或重装后导入就恢复。"
            textSize = 12f
            setTextColor(0xFF9CA3AF.toInt())
        })
        v.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(android.widget.Button(this@MainActivity).apply {
                text = "导出配置"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { startExport() }
            })
            addView(android.widget.Button(this@MainActivity).apply {
                text = "导入配置"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { startImport() }
            })
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
