package com.jakober.energie

import android.content.Context
import com.jakober.energie.core.history.HistoryStore
import com.jakober.energie.data.AppSettings
import com.jakober.energie.data.EnergyRepository
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

    val repository = EnergyRepository(settings, http, history)
}
