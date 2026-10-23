package dev.aj.miniapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 真终端：Termux 官方的 TerminalView + TerminalSession，
 * 底层是它自带 libtermux.so 提供的 PTY + fork/exec。
 * 我们只把 shellPath 换成 proot，其余照用。
 */
class TermuxTerm(private val ctx: Context) {

    var view: TerminalView? = null
        private set
    var session: TerminalSession? = null
        private set

    fun createView(): TerminalView {
        val v = TerminalView(ctx).apply {
            setTerminalViewClient(viewClient)
            setTextSize(28)
        }
        view = v
        return v
    }

    /** 起一个 proot 里的 sh 会话 */
    fun start(): Result<TerminalSession> {
        return try {
            val rootfs = Linux.rootDir(ctx)
            val proot = Linux.prootPath(ctx)
            if (!proot.exists()) return Result.failure(IllegalStateException("proot 缺失"))
            if (!rootfs.exists()) return Result.failure(IllegalStateException("rootfs 未解压"))

            val args = arrayOf(
                "--link2symlink",
                "-0",
                "-r", rootfs.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${ctx.filesDir.absolutePath}:/host",
                "-w", "/root",
                "/bin/sh", "-l"
            )
            val env = arrayOf(
                "HOME=/root",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "PROOT_TMP_DIR=${ctx.cacheDir.absolutePath}",
            )

            val s = TerminalSession(
                proot.absolutePath,
                ctx.filesDir.absolutePath,
                args,
                env,
                2000,
                sessionClient
            )
            session = s
            view?.attachSession(s)
            Result.success(s)
        } catch (e: Throwable) {
            Log.e("TermuxTerm", "start failed", e)
            Result.failure(e)
        }
    }

    fun send(input: String) {
        val s = session ?: return
        val data = input.toByteArray(Charsets.UTF_8)
        s.write(data, 0, data.size)
    }

    fun sendNewline() = send("\r")

    fun isAlive(): Boolean = session?.isRunning == true

    // -------------------------------------------------- 会话回调 → 刷新画面

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            view?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            view?.onScreenUpdated()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?): String {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            return cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: "term", message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "term", message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "term", message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "term", message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "term", message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: "term", message ?: "", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: "term", "error", e)
        }
    }

// ---------------------------------------------------------------- 视图回调
private val viewClient = object : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent?) {
        // 点一下画面就拉起软键盘
        view?.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = view?.hasFocus() == true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: "termv", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "termv", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "termv", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "termv", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "termv", message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: "termv", message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: "termv", "error", e)
    }

    }   // end viewClient
}
