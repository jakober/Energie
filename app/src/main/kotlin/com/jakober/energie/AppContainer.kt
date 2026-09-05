package com.jakober.energie

import android.content.Context
import com.jakober.energie.core.history.HistoryStore
import com.jakober.energie.data.AppSettings
import com.jakober.energie.data.BackupManager
import com.jakober.energie.data.EnergyRepository
import com.jakober.energie.notify.Notifier
import com.jakober.energie.core.places.Places
import com.jakober.energie.ui.Format
import com.jakober.energie.widget.EnergieWidget
import com.jakober.energie.widget.WidgetState
import androidx.glance.appwidget.updateAll
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

/** Haelt die wenigen langlebigen Objekte der App zusammen - ohne DI-Framework. */
class AppContainer(context: Context) {
    val settings = AppSettings(context)

    val http: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    }

    val history = HistoryStore(File(context.filesDir, "verlauf"))

    val notifier = Notifier(context)

    val repository = EnergyRepository(settings, http, history).also { repo ->
        repo.onAlerts = { alerts -> notifier.showAll(alerts) }
        repo.onWidgetUpdate = { sample, car ->
            val lat = car?.latitude
            val lon = car?.longitude
            val place = if (lat != null && lon != null) Places.match(settings.current().places, lat, lon)?.name else null
            WidgetState.save(context, WidgetState.of(sample, car, place) { Format.power(it) })
            EnergieWidget().updateAll(context)
        }
    }

    val backup = BackupManager(context, settings, history, repository)
}
