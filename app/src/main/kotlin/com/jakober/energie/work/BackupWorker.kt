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
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Sichert einmal am Tag in den gewaehlten Ordner, bevorzugt nachts und nur im
 * WLAN. Android verschiebt die Ausfuehrung nach Bedarf; die Sicherung ist
 * idempotent (eine Datei je Tag), ein Nachholen am Vormittag schadet nicht.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EnergieApp
        val settings = app.container.settings.current()
        if (!settings.backupConfigured) return Result.success()
        return runCatching { app.container.backup.backupNow() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { e ->
                    app.container.settings.noteBackup(System.currentTimeMillis() / 1000, "Fehler: ${e.message ?: e}")
                    if (runAttemptCount < 2) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        private const val NAME = "energie-sicherung"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(untilNextThreeAm())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        /** Naechste 03:00 Uhr Ortszeit, damit die erste Sicherung nachts laeuft. */
        private fun untilNextThreeAm(): Duration {
            val now = LocalDateTime.now()
            var target = now.toLocalDate().atTime(LocalTime.of(3, 0))
            if (!target.isAfter(now)) target = target.plusDays(1)
            return Duration.between(now, target)
        }
    }
}
