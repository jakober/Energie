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
import java.util.concurrent.TimeUnit

/**
 * Holt im Hintergrund alle 15 Minuten einen Messpunkt - das kleinste
 * Intervall, das Android fuer wiederkehrende Arbeit erlaubt. So entsteht
 * auch dann ein Tagesverlauf, wenn die App nicht offen ist. Im Vordergrund
 * fragt die App haeufiger ab (Einstellung "Abfrageabstand").
 */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EnergieApp
        val state = app.container.repository.refresh(background = true)
        if (runAttemptCount == 0) app.container.repository.prune()
        // Beide Quellen gescheitert: Android soll es spaeter noch einmal versuchen.
        val nothing = state.sample == null || (state.senecError != null && state.fritzError != null)
        return if (nothing && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "energie-messpunkt"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
