package dev.aj.miniapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/** 长任务期间挂前台服务 + 唤醒锁，避免切后台就被系统掐掉 */
object KeepAlive {
    @Volatile
    private var running = false

    fun start(ctx: Context) {
        if (running) return
        running = true
        runCatching { ctx.startService(Intent(ctx, KeepAliveService::class.java)) }
    }

    fun stop(ctx: Context) {
        // 网页子 AJ 还开着就不收服务：它同时是掉线自动拉起的守护
        if (runCatching { Settings(ctx).webOn }.getOrDefault(false)) return
        if (!running) return
        running = false
        runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
    }
}

class KeepAliveService : Service() {

    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "任务进行中", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val n = android.app.Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AJ 正在执行任务")
            .setContentText("长任务期间保持后台运行，结束后自动收起")
            .setOngoing(true)
            .build()
        startForeground(1201, n)
        startWatch()
        lock = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aj:task")
                .apply { acquire(60L * 60 * 1000) }
        }.getOrNull()
    }

    private var wd: Thread? = null

    /** 每 45 秒看一眼网页子 AJ：开了却没应答就重新拉起 */
    private fun startWatch() {
        if (wd != null) return
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    val ctx = applicationContext
                    if (Settings(ctx).webOn && !WebAj.running(ctx)) WebAj.start(ctx)
                }
                try {
                    Thread.sleep(45_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }
        t.isDaemon = true
        t.name = "ajweb-watch"
        wd = t
        t.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        runCatching { lock?.release() }
        lock = null
        runCatching { stopForeground(true) }
        super.onDestroy()
    }

    companion object {
        const val CH = "task"
    }
}
