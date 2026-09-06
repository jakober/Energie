package com.jakober.energie.hub

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jakober.energie.EnergieApp
import com.jakober.energie.MainActivity
import com.jakober.energie.R
import com.jakober.energie.data.CloudRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Die Zentrale: ein Vordergrund-Dienst, der jede Minute misst und in die Cloud
 * schreibt, solange die Rolle "Zentrale" gesetzt ist. Die Dauerbenachrichtigung
 * zeigt den letzten Stand.
 */
class HubService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("Zentrale startet …")
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val app = applicationContext as EnergieApp
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "energie:zentrale").apply { setReferenceCounted(false); acquire() }
        try {
            while (scope.isActive) {
                val s = app.container.settings.current()
                if (s.cloudRole != CloudRole.HUB) { stopSelf(); return }
                val started = Clock.System.now()
                val state = runCatching { app.container.repository.refresh() }.getOrNull()
                val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
                val line = buildString {
                    append("%02d:%02d".format(t.hour, t.minute))
                    state?.sample?.let { smp ->
                        smp.productionW?.let { append(" · PV ${it.toInt()} W") }
                        smp.batterySocPercent?.let { append(" · Speicher ${it.toInt()} %") }
                    }
                    state?.cloudError?.let { append(" · $it") } ?: state?.cloudInfo?.let { append(" · ok") }
                }
                update(line)
                val elapsed = Clock.System.now() - started
                delay((INTERVAL_MS - elapsed.inWholeMilliseconds).coerceAtLeast(5_000))
            }
        } finally {
            runCatching { wakeLock?.release() }
        }
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Zentrale", NotificationManager.IMPORTANCE_LOW).apply { description = "Dauerbenachrichtigung der Zentrale" })
        val n = notification(text)
        // Der Typ "specialUse" existiert erst ab Android 14; davor reicht der einfache Aufruf.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Energie-Zentrale läuft")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "zentrale"
        const val NOTIFICATION_ID = 42
        const val INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, HubService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) { context.stopService(Intent(context, HubService::class.java)) }
    }
}
