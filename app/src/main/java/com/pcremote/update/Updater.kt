package com.pcremote.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.pcremote.BuildConfig
import com.pcremote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class Release(val version: String, val apkUrl: String)

/**
 * Self-updates from the latest GitHub release. Android checks that the new APK is signed with the
 * same key. From Android 12 the install is silent once PC Remote installed itself the first time;
 * until then (or when "Install unknown apps" is off) the user gets one tap via a notification.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/SadikTD/pc-remote/releases/latest"
    private const val JOB = 47_01
    private const val CHANNEL = "updates"
    private val http by lazy { OkHttpClient() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false

    /** The release's version and APK, from GitHub's `releases/latest` JSON. */
    fun parse(json: String): Release? = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        val tag = o["tag_name"]!!.jsonPrimitive.content.trimStart('v')
        val apk = o["assets"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content.endsWith(".apk") }["browser_download_url"]!!.jsonPrimitive.content
        Release(tag, apk)
    }.getOrNull()

    fun isNewer(latest: String, current: String): Boolean {
        fun v(s: String) = s.split('.', '-').mapNotNull { it.toIntOrNull() }
        val (a, b) = v(latest) to v(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Every 6 h while the phone has a network, plus once now. Debug builds never self-update. */
    fun schedule(ctx: Context) {
        if (BuildConfig.DEBUG) return
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB) == null) {
            js.schedule(JobInfo.Builder(JOB, ComponentName(ctx, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 3600 * 1000L)
                .setPersisted(true)
                .build())
        }
        checkNow(ctx)
    }

    fun checkNow(ctx: Context, done: () -> Unit = {}) {
        if (BuildConfig.DEBUG || busy) return done()
        busy = true
        scope.launch {
            try { runCatching { check(ctx.applicationContext) } } finally { busy = false; done() }
        }
    }

    private fun check(ctx: Context) {
        val body = http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute()
            .use { if (it.isSuccessful) it.body.string() else null } ?: return
        val r = parse(body) ?: return
        if (!isNewer(r.version, BuildConfig.VERSION_NAME)) return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            // one-time: let PC Remote install its own updates
            notify(ctx, ctx.getString(R.string.pcr_update_allow, r.version),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
            return
        }
        val apk = File(ctx.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) return
            apk.outputStream().use { resp.body.byteStream().copyTo(it) }
        }
        install(ctx, apk)
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(status.intentSender)
        }
    }

    internal fun notify(ctx: Context, text: String, action: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.pcr_update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(ctx, 1, action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        nm.notify(4702, b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(ctx.getString(R.string.pcr_update_title))
            .setContentText(text).setContentIntent(tap).setAutoCancel(true).build())
    }
}

/** Installer results: asks for the one confirmation Android may still want. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                Updater.notify(ctx, ctx.getString(R.string.pcr_update_tap), confirm)
            }
            else -> {}
        }
    }
}

class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.checkNow(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
