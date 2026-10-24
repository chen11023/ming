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

    private lateinit var bubbles: ChatBubbles
    private lateinit var chatBg: android.widget.ImageView
    private lateinit var chatFrame: android.widget.FrameLayout
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
    private val LOG_CODE = 3003

    private lateinit var rootView: android.view.View
    private lateinit var chatPageView: android.view.View

    private lateinit var diag: TextView
    private lateinit var diagSend: android.widget.Button
    private lateinit var termHost: android.widget.FrameLayout
    private lateinit var contentBox: android.widget.FrameLayout
    private var settingsView: android.view.View? = null
    private lateinit var termStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        Diag.install(this)                   // 崩了落文件，设置页能导出
        settings.migratePlaintext()          // 老版本明文 key 转密文
        MemoryStore(this).ensure()
        envStore = EnvStore(this)
        mcp = McpManager(this)
        runCatching { Sched.arm(this) }
        // 上次开着网页子 AJ：重开 App 时把守护进程接上，掉线它自己会拉
        runCatching {
            if (settings.webOn) {
                KeepAlive.start(this)
                thread { runCatching { if (!WebAj.running(this)) WebAj.start(this) } }
            }
            // 路由大脑每天自己探一次（探过了就不动，24 小时内不重复），子 AJ 才知道哪个模型已经坏了
            thread {
                runCatching {
                    val r = RouteBrain.probeAll(settings)
                    // 真探过了才重启子 AJ，让它丢掉旧缓存、换上新排好的链
                    if (!r.startsWith("今天已经探过") && settings.webOn) {
                        runCatching { WebAj.stop(this@MainActivity) }
                        runCatching { WebAj.start(this@MainActivity) }
                    }
                }
            }
        }
        agent = AgentLoop(this, settings, envStore) { ev -> onAgentEvent(ev) }
        agent.onConfirm = { what -> askConfirm(what) }
        agent.onAsk = { q -> askUserInline(q) }
        runCatching {
            tts = android.speech.tts.TextToSpeech(this) { st ->
                if (st != android.speech.tts.TextToSpeech.SUCCESS) tts = null
            }
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) { speaking = true }
                override fun onDone(id: String?) { speaking = false }
                @Deprecated("旧接口，必须实现")
                override fun onError(id: String?) { speaking = false }
            })
        }
        loadUi()
        restoreSession()
        askAllFilesOnce()
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
        Palette.dark = settings.dark
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg())
        }

        rootView = root

        val tabs = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.card())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val content = android.widget.FrameLayout(this)
        contentBox = content

        val chatPage = buildChatPage()
        chatPageView = chatPage
        val termPage = buildTermPage()
        val setPage = buildSettingsPage()

        val btns = listOf("聊天", "终端", "设置").map { name ->
            android.widget.TextView(this).apply {
                text = name
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                tag = "keepColor"
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
            if (i == 2) {
                settingsView = buildSettingsPage()
                content.addView(settingsView!!)
            } else {
                content.addView(pages[i])
            }
            btns.forEachIndexed { idx, b ->
                b.setTextColor(if (idx == i) Palette.accent() else Palette.sub())
                b.setTypeface(
                    null,
                    if (idx == i) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                b.background = if (idx == i) roundBg(this@MainActivity, Palette.accentSoft(), 12f) else null
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

    /** 统一给按钮/输入框上色，别让它们都是系统灰 */
    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun skin(view: android.view.View) {
        when (view) {
            is android.widget.Button -> {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(Palette.cardAlt())
                view.setTextColor(Palette.text())
                view.textSize = 14f
                view.isAllCaps = false
                view.minHeight = dp(42)
                view.stateListAnimator = null
            }
            is android.widget.EditText -> {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(Palette.line())
                view.setTextColor(Palette.text())
                view.setHintTextColor(Palette.sub())
            }
            is android.widget.TextView -> {
                if (view.tag != "jkLabel" && view.tag != "keepColor") view.setTextColor(Palette.text())
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) skin(view.getChildAt(i))
        }
    }

    /** 设置页：把「灰标签 + 一堆控件」重新排成一张一张的卡片（QQ 那种分组） */
    private fun restyleSections(root: LinearLayout) {
        val kids = (0 until root.childCount).map { root.getChildAt(it) }
        root.removeAllViews()
        var cur: LinearLayout? = null
        fun newCard() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(this@MainActivity, Palette.card(), 16f, Palette.line())
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }
        for (k in kids) {
            if (k is android.widget.TextView && k.tag == "jkLabel") {
                val card = newCard()
                card.addView(android.widget.TextView(this).apply {
                    text = k.text
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Palette.accent())
                    setPadding(0, dp(10), 0, dp(4))
                })
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.setMargins(0, dp(10), 0, 0)
                root.addView(card, lp)
                cur = card
            } else if (cur != null) {
                cur!!.addView(k)
            } else {
                root.addView(k)
            }
        }
    }

    /** 深色 / 字号，改完立刻生效 */
    private fun applyTheme() {
        val dark = settings.dark
        val bg = if (dark) 0xFF0F1115.toInt() else 0xFFF7F7F8.toInt()
        val fg = if (dark) 0xFFE5E7EB.toInt() else 0xFF111827.toInt()
        runCatching {
            rootView.setBackgroundColor(bg)
            chatPageView.setBackgroundColor(bg)
            Palette.dark = settings.dark
            rootView.setBackgroundColor(Palette.bg())
            bubbles.setFontSize(settings.chatFont)
            loadChatBg()
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

    /** 模型主动停下来问人：弹输入框，等回答（最长 5 分钟） */
    private fun askUserInline(q: String): String {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return ""
        val latch = java.util.concurrent.CountDownLatch(1)
        var ans = ""
        runOnUiThread {
            val input = android.widget.EditText(this).apply {
                hint = "在这里回答"
                setSingleLine(false)
                minLines = 2
            }
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(40, 20, 40, 0)
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = q.take(600); textSize = 14f
                })
                addView(input)
            }
            val d = android.app.AlertDialog.Builder(this)
                .setTitle("模型在问你")
                .setView(box)
                .setPositiveButton("回答") { _, _ -> ans = input.text.toString(); latch.countDown() }
                .setNegativeButton("跳过") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .create()
            d.show()
        }
        runCatching { latch.await(300, java.util.concurrent.TimeUnit.SECONDS) }
        return ans
    }

    /** 朗读、通知：完成时用 */
    private var speaking = false

    private fun speak(text: String) {
        val t = tts ?: return
        runCatching {
            t.setLanguage(java.util.Locale.CHINA)
            t.speak(text.take(600), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "aj")
            speaking = true
        }
    }

    /** 念到一半想停就停 */
    private fun stopSpeak() {
        runCatching { tts?.stop() }
        speaking = false
    }

    private fun toggleSpeak() {
        if (speaking) { stopSpeak(); android.widget.Toast.makeText(this, "停了", android.widget.Toast.LENGTH_SHORT).show(); return }
        val last = lastAnswer
        if (last.isBlank()) { android.widget.Toast.makeText(this, "还没有可念的内容", android.widget.Toast.LENGTH_SHORT).show(); return }
        speak(last)
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

    /** 技能目录在 /sdcard/Minis/skills：要"所有文件访问"，没有就问一次 */
    private fun askAllFilesOnce() {
        if (Linux.allFiles(this)) return
        if (getSharedPreferences("aj", MODE_PRIVATE).getBoolean("askedAllFiles", false)) return
        getSharedPreferences("aj", MODE_PRIVATE).edit().putBoolean("askedAllFiles", true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("给「所有文件访问」权限")
            .setMessage("技能目录放在 /sdcard/Minis/skills，别人的技能丢进去就能用；没有这个权限只有 App 自己能看见。\n\n不给也能用，技能会放在 App 私有目录。")
            .setPositiveButton("去开启") { _, _ ->
                runCatching {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("以后", null)
            .show()
    }

    /** 长按气泡：复制 / 删除这条 / 引用 */
    private fun bubbleMenu(tag: String, body: String) {
        val items = arrayOf("复制这条", "引用到输入框", "删除这条")
        android.app.AlertDialog.Builder(this)
            .setTitle(if (tag.isBlank()) "这条消息" else "[$tag]")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("aj", body))
                        android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    1 -> { chatInput.setText(quote(body)); chatInput.setSelection(chatInput.text.length) }
                    2 -> deleteBlock(tag, body)
                }
            }.show()
    }

    private fun quote(body: String): String {
        val q = body.trim().lines().take(8).joinToString("\n") { "> $it" }
        return q + "\n\n"
    }

    /** 从记录里挖掉一条；记录和界面一起更新 */
    private fun deleteBlock(tag: String, body: String) {
        val b = parseBlocks().lastOrNull { it.body.trim() == body.trim() && (tag.isBlank() || it.tag == tag) }
            ?: parseBlocks().lastOrNull { it.body.trim().startsWith(body.trim().take(40)) }
        if (b == null) {
            android.widget.Toast.makeText(this, "这条已经找不到了", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        deleteBlock(b)
        saveTranscript()
        renderAll()
    }

    /** 背景图：选一张存到私有目录，随时能换回来 */
    private fun pickChatBg() {
        val it = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        it.type = "image/*"
        runCatching { startActivityForResult(it, 3031) }
    }

    /** 长按聊天框：换背景 / 浓淡 / 去掉 */
    private fun bgMenu() {
        val items = arrayOf("选一张图片当背景", "背景淡一点", "背景浓一点", "去掉背景")
        android.app.AlertDialog.Builder(this)
            .setTitle("聊天背景")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> pickChatBg()
                    1 -> { settings.chatBgAlpha = settings.chatBgAlpha - 0.08f; loadChatBg() }
                    2 -> { settings.chatBgAlpha = settings.chatBgAlpha + 0.08f; loadChatBg() }
                    3 -> {
                        settings.chatBg = ""
                        runCatching { java.io.File(filesDir, "chatbg.img").delete() }
                        loadChatBg()
                    }
                }
            }
            .show()
    }

    private fun loadChatBg() {
        if (!::chatBg.isInitialized) return
        val p = settings.chatBg
        if (p.isBlank() || !java.io.File(p).exists()) { chatBg.visibility = android.view.View.GONE; return }
        runCatching {
            chatBg.setImageBitmap(android.graphics.BitmapFactory.decodeFile(p))
            chatBg.setImageAlpha((settings.chatBgAlpha * 255).toInt())
            chatBg.visibility = android.view.View.VISIBLE
        }.onFailure { chatBg.visibility = android.view.View.GONE }
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
        val speakBtn = android.widget.TextView(this).apply {
            text = "🔊"
            tag = "keepColor"
            textSize = 16f
            setPadding(18, 10, 18, 10)
            setTextColor(if (settings.speakReplies) Palette.accent() else Palette.sub())
            setOnClickListener {
                settings.speakReplies = true
                setTextColor(Palette.accent())
                toggleSpeak()
            }
            setOnLongClickListener {
                stopSpeak()
                settings.speakReplies = false
                setTextColor(Palette.sub())
                android.widget.Toast.makeText(this@MainActivity, "自动朗读已关闭（长按关的）", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
        row.addView(speakBtn)
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
        chatBg = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageAlpha((settings.chatBgAlpha * 255).toInt())
            visibility = android.view.View.GONE
        }
        bubbles = ChatBubbles(this) { tag, body -> bubbleMenu(tag, body) }
        outBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chatCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubbles.box)
            addView(outBox)
        }
        scroll.addView(chatCol)
        scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        chatScroll = scroll
        val stack = android.widget.FrameLayout(this).apply {
            addView(chatBg, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        chatFrame = android.widget.FrameLayout(this).apply {
            background = roundBg(this@MainActivity, Palette.card(), 20f, Palette.line())
            addView(stack)
            setOnLongClickListener {
                bgMenu()
                true
            }
        }
        v.addView(modelBar(), android.widget.LinearLayout.LayoutParams(-1, -2))
        val lpFrame = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
        lpFrame.setMargins(dp(10), dp(2), dp(10), dp(6))
        v.addView(chatFrame, lpFrame)

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
        val msgBtn = android.widget.Button(this).apply {
            text = "⋯"
            setOnClickListener { showMessages() }
        }
        val toolsRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        for (b in listOf(historyBtn, msgBtn, attachBtn)) {
            b.textSize = 13f
            toolsRow.addView(b)
        }
        // 麦克风放这里（发送键上方那块空位），不再和朗读挤在模型栏
        toolsRow.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
        toolsRow.addView(android.widget.TextView(this).apply {
            text = "🎤"
            textSize = 17f
            setPadding(16, 12, 22, 12)
            setOnClickListener { startVoice() }
        })
        val inputRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        chatInput.minWidth = 0
        inputRow.addView(chatInput, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(chatStop)
        inputRow.addView(chatSend)
        v.addView(toolsRow)
        v.addView(inputRow)
        skin(v)
        return v
    }

    private fun sendChat() {
        val t = chatInput.text.toString().trim()
        turnStart = System.currentTimeMillis() - 2000
        if (t.isEmpty() && pending.isEmpty()) return
        // 本地命令：/every 30m 提示词 或 /every 2h !shell 命令 —— 循环定时任务
        if (t.startsWith("/every ")) {
            val rest = t.substringAfter("/every ").trim()
            val sp = rest.indexOf(' ')
            val head = if (sp > 0) rest.substring(0, sp) else rest
            val body = if (sp > 0) rest.substring(sp + 1).trim() else ""
            val d = Remind.parseDelay(head)
            chatInput.setText("")
            if (d <= 0 || body.isBlank()) {
                appendChat("提示", "用法：/every 30m 提示词 或 /every 2h !命令（m 分 / h 小时 / d 天）", 0xFFDC2626.toInt(), false)
                return
            }
            val kind = if (body.startsWith("!")) "shell" else "prompt"
            val text = if (kind == "shell") body.substring(1).trim() else body
            val task = Sched.add(this, body, d)
            appendChat("定时", "已建 #${task.id}：每 ${Remind.whenText(d).removePrefix("约 ")} 执行一次（${if (kind == "shell") "沙箱命令" else "问模型"}）：$text", 0xFF16A34A.toInt(), false)
            return
        }
        if (t == "/tasks") {
            chatInput.setText("")
            val list = Sched.load(this)
            if (list.isEmpty()) {
                appendChat("定时", "还没有循环任务。用法：/every 30m 提示词", 0xFF6B7280.toInt(), false)
            } else {
                val now = System.currentTimeMillis()
                appendChat("定时", list.joinToString("\n") { x ->
                    val kind = if (x.kind == "shell") "命令" else "问模型"
                    val left = ((x.next - now) / 60000).coerceAtLeast(0)
                    "#${x.id} 每${x.everyMs / 60000}分·$kind · ${left}分后 · ${x.text.take(40)}"
                }, 0xFF6B7280.toInt(), false)
            }
            return
        }
        if (t.startsWith("/del ")) {
            val id = t.substringAfter("/del ").trim().toIntOrNull()
            chatInput.setText("")
            if (id == null) { appendChat("提示", "用法：/del 3", 0xFFDC2626.toInt(), false); return }
            val ok = Sched.remove(this, id)
            appendChat("定时", if (ok) "已删除 #$id" else "没有 #$id", if (ok) 0xFF16A34A.toInt() else 0xFFDC2626.toInt(), false)
            return
        }
        // 本地命令：/r 10m 喝水 —— 直接设系统提醒，不发给模型
        if (t.startsWith("/r ") || t.startsWith("/remind ")) {
            val rest = t.substringAfter(' ').trim()
            val sp = rest.indexOf(' ')
            val head = if (sp > 0) rest.substring(0, sp) else rest
            val body = if (sp > 0) rest.substring(sp + 1).trim() else ""
            val d = Remind.parseDelay(head)
            chatInput.setText("")
            if (d <= 0 || body.isBlank()) {
                appendChat("提示", "用法：/r 10m 喝水（s 秒 / m 分 / h 小时）", 0xFFDC2626.toInt(), false)
                return
            }
            Remind.set(this, d, body)
            appendChat("提醒", "${Remind.whenText(d)} 提醒你：$body", 0xFF16A34A.toInt(), false)
            return
        }
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
            KeepAlive.start(this)          // 长任务挂前台服务，别被系统回收
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
                    KeepAlive.stop(this)
                    settings.usageAdd(ev.calls, ev.chars, ev.ms)
                    val usedM = agent.lastModel()
                    val label = if (usedM.isNotBlank() && usedM != settings.model) usedM else settings.modelLabel()
                    settings.usageModelAdd(label, ev.calls, ev.chars, ev.ms)
                    if (usedM.isNotBlank() && usedM != settings.model) appendChat(
                        "提示", "主模型（${settings.model}）不可用，这一轮自动切到 $usedM", 0xFFB45309.toInt(), false
                    )
                    if (ev.calls > 0) appendChat(
                        "统计",
                        "${ev.calls} 次模型调用 · ${String.format("%.1f", ev.chars / 10000.0)} 万字符 · " +
                            "${String.format("%.1f", ev.ms / 1000.0)}s" +
                            if (settings.usagePromptTok + settings.usageCompletionTok > 0)
                                " · 累计 tokens 入 ${w(settings.usagePromptTok)} / 出 ${w(settings.usageCompletionTok)}"
                            else "",
                        0xFF9CA3AF.toInt(), false
                    )
                }
                is AgentLoop.Event.Error -> {
                    KeepAlive.stop(this)
                    appendChat("错误", ev.msg, 0xFFDC2626.toInt())
                }
            }
        }
    }

    private var pendingExport: String? = null
    private var pendingLog: String? = null

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

    /** 网页子 AJ：rootfs 里跑本地服务，同一 WiFi 的电脑/平板用浏览器打开 */
    private fun showWebAj() {
        val msg = android.widget.TextView(this).apply {
            text = "读取中…"
            setPadding(48, 8, 48, 0)
            textSize = 13f
        }
        // 外部地址：留给用户自己填（公网域名 / 公网 IP:端口 / 隧道地址）。留空就只显示局域网 IP。
        val pub = android.widget.EditText(this).apply {
            setSingleLine(true)
            hint = "外部地址，如 https://abc.example.com 或 1.2.3.4:8787（可不填）"
            setText(settings.webPublic)
            textSize = 13f
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(
                android.widget.TextView(this@MainActivity).apply {
                    text = "外部访问地址（自己填，留空只用局域网）"
                    textSize = 12f
                    setPadding(24, 8, 24, 0)
                }
            )
            addView(pub)
            addView(msg)
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("网页子 AJ")
            .setView(box)
            .setPositiveButton("启动", null)
            .setNeutralButton("换口令", null)
            .setNegativeButton("停止", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                settings.webPublic = pub.text.toString()
                msg.text = "启动中…（首次解压文件，约十几秒）"
                thread {
                    val log = WebAj.start(this)
                    settings.webOn = true
                    val txt = log + "\n\n" + WebAj.info(this)
                    runOnUiThread { msg.text = txt }
                }
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                settings.webPublic = pub.text.toString()
                settings.webToken = ""
                runOnUiThread { msg.text = "口令已换，重新「启动」后生效。\n\n" + WebAj.info(this) }
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                settings.webPublic = pub.text.toString()
                thread {
                    WebAj.stop(this)
                    settings.webOn = false
                    runOnUiThread { msg.text = "已停。\n\n" + WebAj.info(this) }
                }
            }
        }
        dlg.show()
        runOnUiThread { msg.text = WebAj.info(this) }
    }

    /** 入站 SSH：rootfs 里跑 sshd，别人拿地址+密码连进来 */
    private fun showSsh() {
        val msg = android.widget.TextView(this).apply {
            text = "读取中…"
            setPadding(48, 24, 48, 0)
            textSize = 13f
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("SSH 入口")
            .setView(msg)
            .setPositiveButton("开启", null)
            .setNeutralButton("重置密码", null)
            .setNegativeButton("关闭", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                msg.text = "安装 / 初始化中…\n首次要装 openssh，大约一分钟。"
                thread {
                    val a = Sshd.ensure(this)
                    val b = Sshd.start(this)
                    settings.sshOn = true
                    val txt = "$a\n$b\n\n" + Sshd.info(this)
                    runOnUiThread { msg.text = txt }
                }
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                thread {
                    Sshd.resetPassword(this)
                    val txt = Sshd.ensure(this) + "\n\n" + Sshd.info(this)
                    runOnUiThread { msg.text = txt }
                }
            }
        }
        dlg.show()
        thread {
            val txt = Sshd.info(this)
            runOnUiThread { msg.text = txt }
        }
    }

    /** 查最新发布，有新版就下载，下完直接弹安装器 */
    private fun checkUpdate(btn: android.widget.Button) {
        btn.text = "查…"
        thread {
            val rel = Updater.latest()
            runOnUiThread {
                btn.text = "检查更新"
                if (rel == null) {
                    android.widget.Toast.makeText(this, "没有查到发布版本（还没发过 release，或网络不通）", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val cur = Updater.current(this)
                if (rel.number <= cur) {
                    android.widget.Toast.makeText(this, "已是最新 r$cur", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("发现新版本 r${rel.number}")
                    .setMessage("当前 r$cur，最新 r${rel.number}（${rel.size / 1048576} MB）\n下载完成后直接弹安装器。")
                    .setPositiveButton("下载") { _, _ -> downloadUpdate(btn, rel) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun downloadUpdate(btn: android.widget.Button, rel: Updater.Rel) {
        btn.text = "0%"
        thread {
            val f = java.io.File(filesDir, "update/aj-mini.apk")
            var lastP = -1
            val ok = runCatching {
                Updater.download(rel.url, f) { p ->
                    if (p / 5 != lastP / 5) {
                        lastP = p
                        runOnUiThread { btn.text = "$p%" }
                    }
                }
            }.isSuccess
            runOnUiThread {
                btn.text = "检查更新"
                if (!ok) {
                    android.widget.Toast.makeText(this, "下载失败", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                runCatching { Updater.install(this, f) }.onFailure {
                    android.widget.Toast.makeText(
                        this, "装不了：${it.message}（要在系统设置里允许本应用安装未知来源）", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startLogExport() {
        pendingLog = Diag.exportText(this)
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(
                android.content.Intent.EXTRA_TITLE,
                "aj-diag-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date()) + ".txt"
            )
        }
        runCatching { startActivityForResult(i, LOG_CODE) }
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

        if (requestCode == 3031) {
            val uri = data.data ?: return
            Thread {
                runCatching {
                    val f = java.io.File(filesDir, "chatbg.img")
                    contentResolver.openInputStream(uri)!!.use { i ->
                        f.outputStream().use { o -> i.copyTo(o) }
                    }
                    settings.chatBg = f.absolutePath
                }.onSuccess {
                    runOnUiThread {
                        loadChatBg()
                        android.widget.Toast.makeText(this, "背景换好了", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { e ->
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "图片读不了：${e.message}",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return
        }
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
        if (requestCode == LOG_CODE) {
            val uri = data.data ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write((pendingLog ?: "").toByteArray()) }
            }.onSuccess {
                appendChat("提示", "诊断日志已导出。", 0xFF16A34A.toInt(), false)
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

    private fun modelUsageLine(): String {
        val list = settings.usageModels()
        if (list.isEmpty()) return "（还没有按模型的数据）"
        return list.take(6).joinToString("\n") { (label, calls, chars) ->
            val (pt, ct) = settings.usageModelTok(label)
            val tok = if (pt + ct > 0) " / 入 ${w(pt)} 出 ${w(ct)} tokens" else ""
            "· $label：$calls 次 / ${String.format("%.1f", chars / 10000.0)} 万字符$tok"
        } + if (list.size > 6) "\n· 还有 ${list.size - 6} 个" else ""
    }

    private fun usageLine(): String {
        val ms = settings.usageMs
        val pt = settings.usagePromptTok
        val ct = settings.usageCompletionTok
        val tok = if (pt + ct > 0) " · tokens 入 ${w(pt)} / 出 ${w(ct)}" else ""
        return "${settings.usageCalls} 次调用 · ${String.format("%.1f", settings.usageChars / 10000.0)} 万字符$tok · " +
            "累计 ${ms / 60000} 分 ${(ms % 60000) / 1000} 秒"
    }

    /** token 数太长了，w/亿 简写 */
    private fun w(n: Long): String = when {
        n >= 100_000_000L -> String.format("%.2f 亿", n / 100_000_000.0)
        n >= 10_000L -> String.format("%.1f 万", n / 10000.0)
        else -> "$n"
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
        bubbles.clear()
        outBox.removeAllViews()
        pending.clear()
        attachBtn.text = "📎"
        appendChat("提示", "新会话开始（$id）。点「☰」可切回以前的会话。", 0xFF6B7280.toInt())
    }

    /** 会话列表：可搜索（标题或内容），点一行切换 */
    private fun showSessions() {
        val dir = java.io.File(filesDir, "sessions")
        val ids = (dir.list() ?: emptyArray())
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sortedByDescending { java.io.File(dir, "$it.json").lastModified() }
        if (ids.isEmpty()) {
            appendChat("提示", "还没有历史会话。", 0xFF6B7280.toInt())
            return
        }
        data class SR(val id: String, val time: String, val title: String, val n: Int)
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        val rows = ids.map { id ->
            val f = java.io.File(dir, "$id.json")
            val n = runCatching { org.json.JSONArray(f.readText()).length() }.getOrDefault(0)
            SR(id, sdf.format(java.util.Date(f.lastModified())), sessionTitle(f), n)
        }

        val input = EditText(this).apply {
            hint = "搜标题或聊天内容，空=全部"
            setSingleLine(true)
        }
        val list = android.widget.ListView(this)
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list.adapter = adapter
        val shown = mutableListOf<String>()

        fun refresh(kw: String) {
            adapter.clear()
            shown.clear()
            for (r in rows) {
                var hit = kw.isBlank() || r.title.contains(kw, true) || r.id.contains(kw, true)
                if (!hit && kw.isNotBlank()) {
                    hit = runCatching {
                        java.io.File(dir, "${r.id}.txt").readText().contains(kw, true)
                    }.getOrDefault(false)
                }
                if (!hit) continue
                val mark = if (r.id == curSession) "▶ " else ""
                adapter.add("$mark${r.time}  ${r.title}  (${r.n}条)")
                shown.add(r.id)
            }
            adapter.notifyDataSetChanged()
        }
        refresh("")
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                runCatching { refresh(s?.toString() ?: "") }
            }
        })

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(36, 12, 36, 0)
            addView(input)
            addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("历史会话")
            .setView(box)
            .setNeutralButton("删除当前") { _, _ -> deleteCurrentSession() }
            .setNegativeButton("取消", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            val id = shown.getOrNull(i) ?: return@setOnItemClickListener
            dlg.dismiss()
            switchSession(id)
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val id = shown.getOrNull(i) ?: return@setOnItemLongClickListener true
            android.app.AlertDialog.Builder(this)
                .setMessage("删掉会话 $id ？连同记录一起没。")
                .setPositiveButton("删") { _, _ ->
                    runCatching {
                        sessionFile("$id.txt").delete()
                        sessionFile("$id.json").delete()
                    }
                    dlg.dismiss()
                    if (id == curSession) newSession() else showSessions()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        dlg.show()
    }

    private data class Block(val tag: String, val body: String, val start: Int, val end: Int)

    /** 把 transcript 切成一条条消息（格式：[tag] body，块间空行） */
    private fun parseBlocks(): MutableList<Block> {
        val t = transcript.toString()
        val out = mutableListOf<Block>()
        val re = Regex("(?:^|\\n\\n)\\[([^\\]]{1,16})\\] ")
        var lastTag = ""
        var bodyStart = -1
        var startPos = -1
        for (m in re.findAll(t)) {
            if (bodyStart >= 0) out += Block(lastTag, t.substring(bodyStart, m.range.first), startPos, m.range.first)
            lastTag = m.groupValues[1]
            bodyStart = m.range.last + 1
            startPos = m.range.first
            if (t.startsWith("\n\n", startPos)) startPos += 2
        }
        if (bodyStart in 0..t.length) out += Block(lastTag, t.substring(bodyStart), startPos, t.length)
        return out
    }

    private fun deleteBlock(b: Block) {
        val t = transcript.toString()
        var e = b.end
        if (t.startsWith("\n\n", e)) e += 2
        if (b.start in 0..e) runCatching { transcript.delete(b.start, e) }
    }

    /** 消息列表：点一条看操作（复制 / 填入输入框 / 删掉） */
    private fun showMessages() {
        val blocks = parseBlocks()
        if (blocks.isEmpty()) {
            appendChat("提示", "当前会话还没有消息。", 0xFF6B7280.toInt())
            return
        }
        val from = (blocks.size - 80).coerceAtLeast(0)
        val view = blocks.subList(from, blocks.size).toList()
        val items = view.map { b ->
            val who = if (b.tag == "你" || b.tag == "用户") "你" else b.tag
            "${who}｜" + b.body.replace("\n", " ").take(40)
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("消息（点一条操作）")
            .setItems(items) { _, i -> messageActions(view[i]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun messageActions(b: Block) {
        val isUser = b.tag == "你" || b.tag == "用户"
        val opts = arrayListOf("复制")
        if (isUser) {
            opts += "填入输入框（重发前可改）"
            opts += "编辑（填进输入框并删除原消息）"
        }
        opts += "删除这条（只删显示，模型历史不动）"
        android.app.AlertDialog.Builder(this)
            .setTitle(b.body.replace("\n", " ").take(30))
            .setItems(opts.toTypedArray()) { _, i ->
                val action = opts[i]
                when {
                    action == "复制" -> copyText(b.body)
                    action.startsWith("填入") -> {
                        chatInput.setText(b.body)
                        chatInput.setSelection(chatInput.text.length)
                    }
                    action.startsWith("编辑") -> {
                        chatInput.setText(b.body)
                        chatInput.setSelection(chatInput.text.length)
                        deleteBlock(b)
                        saveTranscript()
                        renderAll()
                    }
                    else -> {
                        deleteBlock(b)
                        saveTranscript()
                        renderAll()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyText(t: String) {
        runCatching {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AJ", t))
            android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
        }
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
        bubbles.clear()
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
        chatScroll.postDelayed({
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
        bubbles.render(shown)
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
        skin(v)
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
            tag = "jkLabel"
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
        v.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(android.widget.Button(this@MainActivity).apply {
                text = "换聊天背景图"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { pickChatBg() }
            })
            addView(android.widget.Button(this@MainActivity).apply {
                text = "去掉背景"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener {
                    settings.chatBg = ""
                    runCatching { java.io.File(filesDir, "chatbg.img").delete() }
                    loadChatBg()
                }
            })
        })
        v.addView(chk("路由大脑：主模型挂了自动换（含别的服务商，按健康度排）", { settings.autoFallback }, { settings.autoFallback = it }))
        v.addView(chk("上下文太长时压成结构摘要（多一次模型调用，省上下文）", { settings.compressSummary }, { settings.compressSummary = it }))
        v.addView(android.widget.Button(this@MainActivity).apply {
            text = "路由大脑：看模型健康度"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("模型健康度（来自真实调用结果）")
                    .setMessage(RouteBrain.probeInfo(settings) + RouteBrain.summary(settings))
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("清空记录") { _, _ ->
                        settings.setRouteHealthRaw("{}")
                        android.widget.Toast.makeText(
                            this@MainActivity, "已清空", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            }
        })
        v.addView(android.widget.Button(this@MainActivity).apply {
            text = "路由大脑：测一测（每天自动一次）"
            setOnClickListener {
                android.widget.Toast.makeText(this@MainActivity, "正在逐个探测模型…", android.widget.Toast.LENGTH_SHORT).show()
                Thread {
                    val r = RouteBrain.probeAll(settings, force = true)
                    runOnUiThread {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("探测结果")
                            .setMessage(r)
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }.start()
            }
        })
        v.addView(android.widget.Button(this@MainActivity).apply {
            text = "SSH 入口（别人连进这台手机）"
            setOnClickListener { showSsh() }
        })
        v.addView(android.widget.Button(this@MainActivity).apply {
            text = "网页子 AJ（局域网浏览器访问）"
            setOnClickListener { showWebAj() }
        })
        v.addView(android.widget.Button(this@MainActivity).apply {
            text = if (Linux.allFiles(this@MainActivity)) "存储权限：已开启（/sdcard/Minis/skills）"
                   else "存储权限：未开启（技能目录在私有目录）"
            setOnClickListener {
                if (Linux.allFiles(this@MainActivity)) {
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("已经开了")
                        .setMessage("技能目录：/sdcard/Minis/skills\n往里丢文件夹，里面放 SKILL.md 就生效。").setPositiveButton("好", null).show()
                } else {
                    runCatching {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")))
                    }
                }
            }
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
        val modelUsageTv = TextView(this).apply {
            text = modelUsageLine()
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 10, 0, 6)
        }
        v.addView(modelUsageTv)
        v.addView(android.widget.Button(this).apply {
            text = "清零"
            setOnClickListener {
                settings.usageReset()
                usageTv.text = usageLine()
                modelUsageTv.text = modelUsageLine()
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
                    text = "测"
                    textSize = 12f
                    setOnClickListener {
                        text = "…"
                        thread {
                            val r = McpManager(this@MainActivity).probe(s.name)
                            runOnUiThread {
                                renderMcp()
                                android.widget.Toast.makeText(
                                    this@MainActivity, r.take(300), android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
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

        // ---------------- 关于 / 诊断
        v.addView(label("关于 / 诊断"))
        val verLine = TextView(this).apply {
            text = "版本 ${Diag.verName(this@MainActivity)} · 崩溃记录 ${Diag.crashCount(this@MainActivity)} 条"
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        v.addView(verLine)
        val crashTv = TextView(this).apply {
            text = Diag.lastCrashTail(this@MainActivity).ifBlank { "（没有崩溃记录）" }
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, 4, 0, 10)
        }
        v.addView(crashTv)
        v.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(android.widget.Button(this@MainActivity).apply {
                text = "导出日志"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { startLogExport() }
            })
            addView(android.widget.Button(this@MainActivity).apply {
                text = "检查更新"
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { checkUpdate(this) }
            })
        })

        // 按小节标题拆成多页：菜单 → 点进去只看一节
        val groups = LinkedHashMap<String, MutableList<android.view.View>>()
        var curTitle = "其他"
        var curList = mutableListOf<android.view.View>()
        groups[curTitle] = curList
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is TextView && c.tag == "jkLabel") {
                curTitle = c.text.toString()
                curList = mutableListOf()
                groups[curTitle] = curList
            } else {
                curList.add(c)
            }
        }
        val menu = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 4, 0, 24)
        }
        for ((title, views) in groups) {
            if (title == "其他" && views.isEmpty()) continue
            menu.addView(TextView(this).apply {
                text = "▸  $title"
                textSize = 15f
                setPadding(dp(18), dp(18), dp(18), dp(18))
                setTextColor(Palette.text())
                background = roundBg(this@MainActivity, Palette.card(), 16f, Palette.line())
                setOnClickListener {
                    views.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                    openSubPage(title, views)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, dp(8), 0, 0)
                }
            })
        }
        return android.widget.ScrollView(this).apply { addView(menu) }
    }

    /** 设置里的小页：顶部返回条 + 内容 */
    private fun openSubPage(title: String, views: List<android.view.View>) {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(14, 8, 14, 8)
        }
        bar.addView(android.widget.Button(this).apply {
            text = "← 设置"
            textSize = 13f
            setOnClickListener { showSettings() }
        })
        bar.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Palette.text())
            setPadding(16, 0, 0, 0)
        })
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 6, 24, 28)
        }
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = roundBg(this@MainActivity, Palette.card(), 16f, Palette.line())
            setPadding(dp(14), dp(6), dp(14), dp(14))
        }
        for (v in views) {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            card.addView(v)
        }
        body.addView(card, android.widget.LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, 0) })
        skin(card)
        val page = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(bar)
            addView(android.widget.ScrollView(this@MainActivity).apply { addView(body) },
                android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
        contentBox.removeAllViews()
        contentBox.addView(page)
    }

    private fun showSettings() {
        val c = contentBox
        c.removeAllViews()
        settingsView = buildSettingsPage()
        c.addView(settingsView!!)
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
