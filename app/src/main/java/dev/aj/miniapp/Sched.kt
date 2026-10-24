package dev.aj.miniapp

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条循环定时任务：kind = "prompt"（问模型一轮）或 "shell"（在 rootfs 里跑命令） */
data class SchedTask(
    val id: Int,
    val kind: String,
    val text: String,
    val everyMs: Long,
    var next: Long
)

/**
 * 循环定时任务。用的是系统闹钟（setExactAndAllowWhileIdle），
 * 不靠 crontab / 不靠进程常驻，App 被系统冻结也照样到点醒。
 */
object Sched {

    private fun file(ctx: Context): File =
        File(File(ctx.filesDir, "sched"), "tasks.json").apply { parentFile?.mkdirs() }

    fun load(ctx: Context): MutableList<SchedTask> {
        val out = mutableListOf<SchedTask>()
        val raw = runCatching { file(ctx).readText() }.getOrNull() ?: return out
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val every = o.optLong("every", 0L)
                if (every <= 0) continue
                out.add(
                    SchedTask(
                        o.optInt("id", 0),
                        o.optString("kind", "prompt"),
                        o.optString("text", ""),
                        every,
                        o.optLong("next", System.currentTimeMillis() + every)
                    )
                )
            }
        }
        return out
    }

    fun save(ctx: Context, list: List<SchedTask>) {
        val a = JSONArray()
        for (t in list) {
            a.put(JSONObject().apply {
                put("id", t.id)
                put("kind", t.kind)
                put("text", t.text)
                put("every", t.everyMs)
                put("next", t.next)
            })
        }
        runCatching { file(ctx).writeText(a.toString()) }
    }

    /** text 以 ! 开头 = 直接跑 shell，否则 = 问模型 */
    fun add(ctx: Context, raw: String, everyMs: Long): SchedTask {
        val list = load(ctx)
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val kind = if (raw.trimStart().startsWith("!")) "shell" else "prompt"
        val text = if (kind == "shell") raw.trimStart().removePrefix("!").trim() else raw.trim()
        val t = SchedTask(id, kind, text, everyMs, System.currentTimeMillis() + everyMs)
        list.add(t)
        save(ctx, list)
        arm(ctx)
        return t
    }

    fun remove(ctx: Context, id: Int): Boolean {
        val list = load(ctx)
        val hit = list.removeAll { it.id == id }
        save(ctx, list)
        arm(ctx)
        return hit
    }

    fun everyText(ms: Long): String = when {
        ms % 3600_000L == 0L -> "${ms / 3600_000L} 小时"
        ms % 60_000L == 0L -> "${ms / 60_000L} 分钟"
        else -> "${ms / 1000L} 秒"
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, SchedReceiver::class.java).setAction(ACTION_TICK)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (android.os.Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, 7001, i, flags)
    }

    /** 按最近一条到期任务排下一次唤醒 */
    fun arm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        runCatching { am.cancel(pi) }
        val next = load(ctx).minOfOrNull { it.next } ?: return
        val at = next.coerceAtLeast(System.currentTimeMillis() + 5_000L)
        val ok = runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }.isSuccess
        if (!ok) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun due(ctx: Context): List<SchedTask> {
        val now = System.currentTimeMillis()
        return load(ctx).filter { it.next <= now + 2_000L }
    }

    fun markRun(ctx: Context, t: SchedTask) {
        val list = load(ctx)
        list.firstOrNull { it.id == t.id }?.next = System.currentTimeMillis() + t.everyMs
        save(ctx, list)
    }

    const val ACTION_TICK = "dev.aj.miniapp.SCHED_TICK"

    /** 通知：任务跑完告诉用户 */
    fun notify(ctx: Context, title: String, body: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "aj-sched"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "定时任务", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val n: Notification = if (android.os.Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, ch)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(body.take(80))
                    .setStyle(Notification.BigTextStyle().bigText(body.take(600)))
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(body.take(80))
                    .build()
            }
            nm.notify(title.hashCode(), n)
        }
    }

    /** 跑一条任务，返回可展示的结果文本 */
    fun runTask(ctx: Context, t: SchedTask): String {
        return if (t.kind == "shell") {
            val (code, out) = Linux.run(ctx, t.text, timeoutSec = 300)
            "[exit $code]\n" + out.trim().take(800)
        } else {
            val settings = Settings(ctx)
            if (!settings.configured) return "没填 API key，跳过"
            var answer = ""
            val loop = AgentLoop(ctx, settings, EnvStore(ctx)) { ev ->
                if (ev is AgentLoop.Event.Answer) answer = ev.text
                if (ev is AgentLoop.Event.Error && answer.isBlank()) answer = "错误：" + ev.msg
            }
            loop.persist = false          // 不碰用户的会话文件
            loop.send(t.text)
            answer.ifBlank { "（无输出）" }.trim().take(800)
        }
    }
}

class SchedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val todo = Sched.due(ctx)
        if (todo.isEmpty()) {
            Sched.arm(ctx)
            return
        }
        KeepAlive.start(ctx)
        Thread {
            try {
                for (t in todo) {
                    Sched.markRun(ctx, t)
                    val r = runCatching { Sched.runTask(ctx, t) }
                        .getOrElse { "失败：" + it.javaClass.simpleName + " " + it.message }
                    Sched.notify(
                        ctx,
                        "定时任务 #${t.id} · ${if (t.kind == "shell") "命令" else "提问"}",
                        r
                    )
                }
            } finally {
                KeepAlive.stop(ctx)
                Sched.arm(ctx)
            }
        }.start()
    }
}

/** 开机自启：重排闹钟；SSH 开着就把入口拉起来 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
        val app = ctx.applicationContext
        runCatching { Sched.arm(app) }
        runCatching {
            if (Settings(app).sshOn) Thread { runCatching { Sshd.start(app) } }.start()
        }
    }
}
