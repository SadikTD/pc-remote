package com.pcremote.modes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.pcremote.MainActivity
import com.pcremote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps transfers going when the app is in the background, with a progress notification.
 * Stops itself (and closes the transfer connections) 30 s after the last transfer ends.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(getString(R.string.pcr_files_preparing), 0, true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (!running) {
            running = true
            scope.launch { loop() }
        }
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        var idleFor = 0
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            val all = TransferHub.all().flatMap { it.transfers.value }
            val active = all.filter { !it.finished && it.status != Transfer.Status.PAUSED }
            if (active.isEmpty()) {
                if (++idleFor >= 30) break
                if (idleFor == 1) {
                    val failed = all.count { it.status == Transfer.Status.FAILED }
                    nm.notify(ID, notification(getString(if (failed > 0) R.string.pcr_files_some_failed else R.string.pcr_files_all_done), 100, false))
                }
            } else {
                idleFor = 0
                val size = active.sumOf { it.size }.coerceAtLeast(1)
                val done = active.sumOf { it.done }
                val speed = active.sumOf { it.speed }
                val title = resources.getQuantityString(R.plurals.pcr_files_moving, active.size, active.size)
                nm.notify(ID, notification("$title · ${Format.bytes(speed.toLong())}/s", (done * 100 / size).toInt(), true))
            }
            delay(1000)
        }
        TransferHub.all().forEach { it.idleClose() }
        running = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun notification(text: String, percent: Int, ongoing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.pcr_files_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.pcr_mode_files))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(100, percent, percent == 0) }
            .build()
    }

    override fun onDestroy() { scope.cancel(); running = false }

    companion object {
        private const val ID = 4701
        private const val CHANNEL = "transfers"
        @Volatile private var running = false

        /** Called whenever a hub opens: makes sure the service runs while it works. */
        fun watch(ctx: Context, @Suppress("UNUSED_PARAMETER") hub: TransferHub) {
            if (running) return
            runCatching {
                val i = Intent(ctx, TransferService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
    }
}

object Format {
    fun bytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    fun eta(s: Long): String = when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${s % 3600 / 60}m"
    }
}
