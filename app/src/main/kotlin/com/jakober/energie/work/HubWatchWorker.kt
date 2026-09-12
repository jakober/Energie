package com.jakober.energie.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jakober.energie.EnergieApp
import com.jakober.energie.core.alerts.Alert
import com.jakober.energie.core.alerts.AlertKind
import com.jakober.energie.data.CloudRole
import kotlinx.datetime.Clock
import java.util.concurrent.TimeUnit

/**
 * Waechter der Anzeige: schaut regelmaessig nach, wann die Zentrale zuletzt
 * gesehen wurde, und meldet es, wenn sie schweigt. Das muss die Anzeige
 * uebernehmen, denn eine haengende oder abgestuerzte Zentrale kann sich nicht
 * selbst melden. Der Hinweis kommt lokal auf diesem Geraet, also auch dann,
 * wenn der Push-Weg ueber die Cloud nicht funktioniert.
 */
class HubWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EnergieApp
        val s = app.container.settings.current()
        if (s.cloudRole != CloudRole.VIEWER || !s.cloudConfigured || !s.alerts.hubSilent) return Result.success()
        val cloud = app.container.cloud ?: return Result.success()

        val seen = runCatching { cloud.pullStatus(s)?.hubSeenAt }.getOrElse { return Result.success() }
        val now = Clock.System.now()
        val silentFor = seen?.let { (now - it).inWholeMinutes }
        val limit = s.alerts.hubSilentMinutes.toLong()

        when {
            // Noch nie gesehen: nichts melden, die Einrichtung laeuft vielleicht gerade.
            seen == null -> Unit
            silentFor != null && silentFor >= limit && !s.hubSilentReported -> {
                app.container.notifier.show(
                    Alert(
                        AlertKind.HUB_SILENT, "Zentrale meldet sich nicht",
                        "Seit ${label(silentFor)} kein Lebenszeichen von der Zentrale. Läuft die App dort noch? " +
                            "Ohne sie werden keine Messwerte mehr aufgezeichnet.",
                    ),
                )
                app.container.settings.saveHubSilentReported(true)
            }
            silentFor != null && silentFor < limit && s.hubSilentReported -> {
                app.container.notifier.show(
                    Alert(AlertKind.HUB_BACK, "Zentrale ist wieder da", "Die Zentrale liefert wieder Messwerte."),
                )
                app.container.settings.saveHubSilentReported(false)
            }
        }
        return Result.success()
    }

    private fun label(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    companion object {
        private const val NAME = "energie-zentrale-waechter"

        /** Alle 15 Minuten, das kleinste Intervall, das Android fuer wiederkehrende Arbeit erlaubt. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HubWatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
