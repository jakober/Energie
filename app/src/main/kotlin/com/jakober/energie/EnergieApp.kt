package com.jakober.energie

import android.app.Application
import androidx.work.Configuration
import com.jakober.energie.work.BackupWorker
import com.jakober.energie.work.HubWatchWorker
import com.jakober.energie.work.PollWorker

class EnergieApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Absturzbericht in eine Datei, damit er beim naechsten Start in den Einstellungen steht.
        // Ohne Rechner mit ADB ist das der einzige Weg, die Ursache eines Absturzes zu sehen.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()} · Thread ${thread.name}\n${e.stackTraceToString()}".take(12_000),
                )
            }
            previous?.uncaughtException(thread, e)
        }
        // Jeder Schritt einzeln abgesichert: faellt einer aus, startet die App trotzdem,
        // und der Fehler steht beim naechsten Start auf dem Bildschirm.
        val problems = StringBuilder()
        fun step(name: String, block: () -> Unit) {
            runCatching(block).onFailure { problems.append("$name: ${it.stackTraceToString()}\n\n") }
        }
        step("AppContainer") { container = AppContainer(this) }
        step("Push") { com.jakober.energie.notify.Push.init(this) }
        step("PollWorker") { PollWorker.schedule(this) }
        step("BackupWorker") { BackupWorker.schedule(this) }
        step("HubWatchWorker") { HubWatchWorker.schedule(this) }
        step("HubService") {
            // Zentrale: Vordergrund-Dienst, damit jede Minute gemessen wird.
            if (kotlinx.coroutines.runBlocking { container.settings.current().cloudRole } == com.jakober.energie.data.CloudRole.HUB) {
                com.jakober.energie.hub.HubService.start(this)
            }
        }
        if (problems.isNotEmpty()) {
            runCatching { java.io.File(filesDir, CRASH_FILE).writeText("Start-Fehler\n$problems".take(12_000)) }
        }
    }

    companion object {
        const val CRASH_FILE = "absturz.txt"
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
