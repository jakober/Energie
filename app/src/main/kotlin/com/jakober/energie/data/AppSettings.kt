package com.jakober.energie.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jakober.energie.core.senec.SenecConnectClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "einstellungen")

/** Alles, was der Nutzer in der App eintraegt. */
data class Settings(
    val fritzHost: String = "fritz.box",
    val fritzUser: String = "",
    val fritzPassword: String = "",
    val senecKey: String = "",
    val senecBaseUrl: String = SenecConnectClient.DEFAULT_BASE_URL,
    /** Abfrageabstand im Vordergrund in Sekunden. */
    val pollSeconds: Int = 60,
    /** Strompreis in Euro je kWh, fuer die Kostenschaetzung. */
    val pricePerKwh: Double = 0.32,
    /** Einspeiseverguetung in Euro je kWh. */
    val feedInPerKwh: Double = 0.08,
    /** So viele Tage Verlauf bleiben gespeichert. */
    val keepDays: Int = 400,
) {
    val fritzConfigured: Boolean get() = fritzHost.isNotBlank() && fritzPassword.isNotBlank()
    val senecConfigured: Boolean get() = senecKey.isNotBlank()
    val anythingConfigured: Boolean get() = fritzConfigured || senecConfigured
}

/**
 * Einstellungen in DataStore. Die Zugangsdaten liegen im privaten
 * App-Speicher; Android verschluesselt diesen Bereich pro Geraet. Ein
 * Backup uebertraegt sie nicht (siehe AndroidManifest, allowBackup).
 */
class AppSettings(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            fritzHost = p[FRITZ_HOST] ?: "fritz.box",
            fritzUser = p[FRITZ_USER] ?: "",
            fritzPassword = p[FRITZ_PASSWORD] ?: "",
            senecKey = p[SENEC_KEY] ?: "",
            senecBaseUrl = p[SENEC_BASE_URL] ?: SenecConnectClient.DEFAULT_BASE_URL,
            pollSeconds = p[POLL_SECONDS] ?: 60,
            pricePerKwh = p[PRICE_PER_KWH] ?: 0.32,
            feedInPerKwh = p[FEED_IN_PER_KWH] ?: 0.08,
            keepDays = p[KEEP_DAYS] ?: 400,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[FRITZ_HOST] = s.fritzHost.trim()
            p[FRITZ_USER] = s.fritzUser.trim()
            p[FRITZ_PASSWORD] = s.fritzPassword
            p[SENEC_KEY] = s.senecKey.trim()
            p[SENEC_BASE_URL] = s.senecBaseUrl.trim().ifBlank { SenecConnectClient.DEFAULT_BASE_URL }
            p[POLL_SECONDS] = s.pollSeconds.coerceIn(20, 3600)
            p[PRICE_PER_KWH] = s.pricePerKwh
            p[FEED_IN_PER_KWH] = s.feedInPerKwh
            p[KEEP_DAYS] = s.keepDays.coerceIn(7, 3650)
        }
    }

    private companion object {
        val FRITZ_HOST = stringPreferencesKey("fritz_host")
        val FRITZ_USER = stringPreferencesKey("fritz_user")
        val FRITZ_PASSWORD = stringPreferencesKey("fritz_password")
        val SENEC_KEY = stringPreferencesKey("senec_key")
        val SENEC_BASE_URL = stringPreferencesKey("senec_base_url")
        val POLL_SECONDS = intPreferencesKey("poll_seconds")
        val PRICE_PER_KWH = doublePreferencesKey("price_per_kwh")
        val FEED_IN_PER_KWH = doublePreferencesKey("feed_in_per_kwh")
        val KEEP_DAYS = intPreferencesKey("keep_days")
    }
}
