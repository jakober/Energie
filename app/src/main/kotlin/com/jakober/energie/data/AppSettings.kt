package com.jakober.energie.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jakober.energie.core.rules.ChargeRules
import com.jakober.energie.core.senec.SenecConnectClient
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.serialization.json.Json
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
    // Smartcar: Application ID oeffnet Connect, Client-ID und Secret holen das API-Token.
    val smartcarAppId: String = "",
    val smartcarClientId: String = "",
    val smartcarClientSecret: String = "",
    /** Nach dem Verbinden gemerkt, damit nicht jede Abfrage die Verbindungen listet. */
    val smartcarVehicleId: String = "",
    val smartcarUserId: String = "",
    /** Ladeleistung in W, wenn Smartcar keine liefert (Ladeziegel: 2200). */
    val carFallbackPowerW: Int = 2200,
    /** FordPass (inoffiziell): Tokens als JSON, Fahrzeug und bevorzugter Ladeort. */
    val fordTokensJson: String = "",
    val fordVin: String = "",
    val fordLocationId: String = "",
    /** Koordinaten des Ladeorts Zuhause (aus FordPass), 0 = unbekannt. */
    val homeLat: Double = 0.0,
    val homeLon: Double = 0.0,
    /** Ladeautomatik. */
    val chargeRules: ChargeRules = ChargeRules(),
    /** Zeitpunkt des letzten Befehls der Automatik, Unix-Sekunden, 0 = nie. */
    val chargeLastCommandAt: Long = 0,
    /** Handschalter "jetzt voll laden" bis zum Abstecken. */
    val chargeOverride: Boolean = false,
    /** Letzte Entscheidungen der Automatik, neueste zuerst, eine je Zeile. */
    val chargeLog: String = "",
) {
    val fordConnected: Boolean get() = fordTokensJson.isNotBlank() && fordVin.isNotBlank()
    val fritzConfigured: Boolean get() = fritzHost.isNotBlank() && fritzPassword.isNotBlank()
    val senecConfigured: Boolean get() = senecKey.isNotBlank()
    val smartcarConfigured: Boolean get() = smartcarClientId.isNotBlank() && smartcarClientSecret.isNotBlank()
    val carConnected: Boolean get() = smartcarConfigured && smartcarVehicleId.isNotBlank()
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
            smartcarAppId = p[SMARTCAR_APP_ID] ?: "",
            smartcarClientId = p[SMARTCAR_CLIENT_ID] ?: "",
            smartcarClientSecret = p[SMARTCAR_CLIENT_SECRET] ?: "",
            smartcarVehicleId = p[SMARTCAR_VEHICLE_ID] ?: "",
            smartcarUserId = p[SMARTCAR_USER_ID] ?: "",
            carFallbackPowerW = p[CAR_FALLBACK_POWER] ?: 2200,
            fordTokensJson = p[FORD_TOKENS] ?: "",
            fordVin = p[FORD_VIN] ?: "",
            fordLocationId = p[FORD_LOCATION] ?: "",
            homeLat = p[HOME_LAT] ?: 0.0,
            homeLon = p[HOME_LON] ?: 0.0,
            chargeRules = p[CHARGE_RULES]?.let { runCatching { rulesJson.decodeFromString(ChargeRules.serializer(), it) }.getOrNull() } ?: ChargeRules(),
            chargeLastCommandAt = p[CHARGE_LAST_CMD] ?: 0,
            chargeOverride = p[CHARGE_OVERRIDE] ?: false,
            chargeLog = p[CHARGE_LOG] ?: "",
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
            p[SMARTCAR_APP_ID] = s.smartcarAppId.trim()
            p[SMARTCAR_CLIENT_ID] = s.smartcarClientId.trim()
            p[SMARTCAR_CLIENT_SECRET] = s.smartcarClientSecret.trim()
            // Fahrzeug-Zuordnung, Ford-Tokens, Automatik und Protokoll pflegt die App selbst - nicht ueberschreiben.
            p[CAR_FALLBACK_POWER] = s.carFallbackPowerW.coerceIn(0, 22_000)
        }
    }

    suspend fun saveRules(rules: ChargeRules) { context.dataStore.edit { it[CHARGE_RULES] = rulesJson.encodeToString(ChargeRules.serializer(), rules) } }

    suspend fun saveChargeOverride(on: Boolean) { context.dataStore.edit { it[CHARGE_OVERRIDE] = on } }

    suspend fun noteChargeCommand(atEpochSeconds: Long) { context.dataStore.edit { it[CHARGE_LAST_CMD] = atEpochSeconds } }

    /** Haengt eine Zeile vorn an das Protokoll, hoechstens 30 Zeilen. */
    suspend fun appendChargeLog(line: String) {
        context.dataStore.edit { p ->
            val old = p[CHARGE_LOG] ?: ""
            p[CHARGE_LOG] = (listOf(line) + old.lines().filter { it.isNotBlank() }).take(30).joinToString("\n")
        }
    }

    suspend fun saveFordTokens(json: String) { context.dataStore.edit { it[FORD_TOKENS] = json } }

    suspend fun saveFordVehicle(vin: String) { context.dataStore.edit { it[FORD_VIN] = vin } }

    suspend fun saveFordLocation(id: String) { context.dataStore.edit { it[FORD_LOCATION] = id } }

    suspend fun saveHome(lat: Double, lon: Double) { context.dataStore.edit { it[HOME_LAT] = lat; it[HOME_LON] = lon } }

    suspend fun clearFord() { context.dataStore.edit { it.remove(FORD_TOKENS); it.remove(FORD_VIN); it.remove(FORD_LOCATION) } }

    /** Merkt sich das verbundene Fahrzeug. */
    suspend fun saveCar(vehicleId: String, userId: String?) {
        context.dataStore.edit { p ->
            p[SMARTCAR_VEHICLE_ID] = vehicleId
            p[SMARTCAR_USER_ID] = userId ?: ""
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
        val SMARTCAR_APP_ID = stringPreferencesKey("smartcar_app_id")
        val SMARTCAR_CLIENT_ID = stringPreferencesKey("smartcar_client_id")
        val SMARTCAR_CLIENT_SECRET = stringPreferencesKey("smartcar_client_secret")
        val SMARTCAR_VEHICLE_ID = stringPreferencesKey("smartcar_vehicle_id")
        val SMARTCAR_USER_ID = stringPreferencesKey("smartcar_user_id")
        val CAR_FALLBACK_POWER = intPreferencesKey("car_fallback_power")
        val FORD_TOKENS = stringPreferencesKey("ford_tokens")
        val FORD_VIN = stringPreferencesKey("ford_vin")
        val FORD_LOCATION = stringPreferencesKey("ford_location")
        val HOME_LAT = doublePreferencesKey("home_lat")
        val HOME_LON = doublePreferencesKey("home_lon")
        val CHARGE_RULES = stringPreferencesKey("charge_rules")
        val CHARGE_LAST_CMD = longPreferencesKey("charge_last_cmd")
        val CHARGE_OVERRIDE = booleanPreferencesKey("charge_override")
        val CHARGE_LOG = stringPreferencesKey("charge_log")
        private val rulesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
