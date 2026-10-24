package dev.aj.miniapp

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地定时提醒：走 AlarmManager，到点系统直接发通知。
 * 不依赖 App 进程活着，也不依赖网络。
 */
object Remind {

    const val CH = "remind"

    fun set(ctx: Context, delayMs: Long, text: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = (System.currentTimeMillis() / 1000L).toInt()
        val i = Intent(ctx, RemindReceiver::class.java).putExtra("text", text)
        val pi = PendingIntent.getBroadcast(
            ctx, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delayMs,
            pi
        )
    }

    fun whenText(delayMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + delayMs))

    /** 把 "10m" "90s" "1h" "10" 解析成毫秒；解析不出返回 -1 */
    fun parseDelay(s: String): Long {
        val m = Regex("^(\\d{1,6})\\s*(s|sec|秒|m|min|分|分钟|h|hour|小时|时)?$").find(s.trim())
            ?: return -1
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].lowercase()) {
            "s", "sec", "秒" -> n * 1000L
            "h", "hour", "小时", "时" -> n * 3600000L
            else -> n * 60000L
        }
    }

    fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000L).toInt(), n)
    }
}

class RemindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Remind.notify(context, "提醒", intent.getStringExtra("text") ?: "时间到了")
    }
}
