package com.jakober.energie.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jakober.energie.core.alerts.AlertSettings
import com.jakober.energie.core.alerts.AlertState
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
    /** Aus dem Verbrauchssprung beim Ladestart gelernte Ladeleistung in W, 0 = noch nichts gelernt. */
    val carLearnedPowerW: Double = 0.0,
    /** Anschaffungskosten der Anlage in Euro fuer die Amortisation, 0 = nicht angegeben. */
    val systemCostEur: Double = 0.0,
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
    /** Sicherung: Zielordner (SAF-Baum-URI), Passwort fuer die Zugangsdaten, letztes Ergebnis. */
    val backupTreeUri: String = "",
    val backupPassword: String = "",
    val backupLastAt: Long = 0,
    val backupLastResult: String = "",
    /** Benachrichtigungen und der Merkzustand der Hinweis-Engine. */
    val alerts: AlertSettings = AlertSettings(),
    val alertState: AlertState = AlertState(),
) {
    val backupConfigured: Boolean get() = backupTreeUri.isNotBlank() && backupPassword.length >= 8
    val fordConnected: Boolean get() = fordTokensJson.isNotBlank() && fordVin.isNotBlank()
    val fritzConfigured: Boolean get() = fritzHost.isNotBlank() && fritzPassword.isNotBlank()
    val senecConfigured: Boolean get() = senecKey.isNotBlank()
    val smartcarConfigured: Boolean get() = smartcarClientId.isNotBlank() && smartcarClientSecret.isNotBlank()
    val carConnected: Boolean get() = smartcarConfigured && smartcarVehicleId.isNotBlank()
    val anythingConfigured: Boolean get() = fritzConfigured || senecConfigured
    /** Ladeleistung, mit der gerechnet wird: gelernt, sonst der Annahmewert. */
    val carAssumedPowerW: Double get() = if (carLearnedPowerW > 0) carLearnedPowerW else carFallbackPowerW.toDouble()
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
            carLearnedPowerW = p[CAR_LEARNED_POWER] ?: 0.0,
            systemCostEur = p[SYSTEM_COST] ?: 0.0,
            fordTokensJson = p[FORD_TOKENS] ?: "",
            fordVin = p[FORD_VIN] ?: "",
            fordLocationId = p[FORD_LOCATION] ?: "",
            homeLat = p[HOME_LAT] ?: 0.0,
            homeLon = p[HOME_LON] ?: 0.0,
            chargeRules = p[CHARGE_RULES]?.let { runCatching { rulesJson.decodeFromString(ChargeRules.serializer(), it) }.getOrNull() } ?: ChargeRules(),
            chargeLastCommandAt = p[CHARGE_LAST_CMD] ?: 0,
            chargeOverride = p[CHARGE_OVERRIDE] ?: false,
            chargeLog = p[CHARGE_LOG] ?: "",
            backupTreeUri = p[BACKUP_TREE] ?: "",
            backupPassword = p[BACKUP_PASSWORD] ?: "",
            backupLastAt = p[BACKUP_LAST_AT] ?: 0,
            backupLastResult = p[BACKUP_LAST_RESULT] ?: "",
            alerts = p[ALERTS]?.let { runCatching { rulesJson.decodeFromString(AlertSettings.serializer(), it) }.getOrNull() } ?: AlertSettings(),
            alertState = p[ALERT_STATE]?.let { runCatching { rulesJson.decodeFromString(AlertState.serializer(), it) }.getOrNull() } ?: AlertState(),
        )
    }

    suspend fun saveAlerts(a: AlertSettings) { context.dataStore.edit { it[ALERTS] = rulesJson.encodeToString(AlertSettings.serializer(), a) } }

    suspend fun saveAlertState(s: AlertState) { context.dataStore.edit { it[ALERT_STATE] = rulesJson.encodeToString(AlertState.serializer(), s) } }

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
            p[SYSTEM_COST] = s.systemCostEur.coerceAtLeast(0.0)
        }
    }

    suspend fun saveCarLearnedPower(watts: Double) { context.dataStore.edit { it[CAR_LEARNED_POWER] = watts.coerceAtLeast(0.0) } }

    suspend fun saveRules(rules: ChargeRules) { context.dataStore.edit { it[CHARGE_RULES] = rulesJson.encodeToString(ChargeRules.serializer(), rules) } }

    /** Einstellungen ohne Geheimnisse als flache Textwerte, fuer die Sicherung. */
    fun plainForBackup(s: Settings): Map<String, String> = linkedMapOf(
        "fritzHost" to s.fritzHost, "fritzUser" to s.fritzUser, "senecBaseUrl" to s.senecBaseUrl,
        "pollSeconds" to s.pollSeconds.toString(), "pricePerKwh" to s.pricePerKwh.toString(), "feedInPerKwh" to s.feedInPerKwh.toString(),
        "keepDays" to s.keepDays.toString(), "smartcarAppId" to s.smartcarAppId, "smartcarClientId" to s.smartcarClientId,
        "smartcarVehicleId" to s.smartcarVehicleId, "smartcarUserId" to s.smartcarUserId, "carFallbackPowerW" to s.carFallbackPowerW.toString(),
        "carLearnedPowerW" to s.carLearnedPowerW.toString(), "systemCostEur" to s.systemCostEur.toString(),
        "fordVin" to s.fordVin, "fordLocationId" to s.fordLocationId, "homeLat" to s.homeLat.toString(), "homeLon" to s.homeLon.toString(),
        "chargeRules" to rulesJson.encodeToString(ChargeRules.serializer(), s.chargeRules),
        "chargeLastCommandAt" to s.chargeLastCommandAt.toString(), "chargeLog" to s.chargeLog,
        "alerts" to rulesJson.encodeToString(AlertSettings.serializer(), s.alerts),
    )

    /** Die Geheimnisse, die nur verschluesselt in die Sicherung duerfen. */
    fun secretsForBackup(s: Settings): Map<String, String> = linkedMapOf(
        "senecKey" to s.senecKey, "fritzPassword" to s.fritzPassword,
        "smartcarClientSecret" to s.smartcarClientSecret, "fordTokensJson" to s.fordTokensJson,
    )

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

    suspend fun saveBackupTarget(treeUri: String, password: String) {
        context.dataStore.edit { p -> p[BACKUP_TREE] = treeUri; p[BACKUP_PASSWORD] = password }
    }

    suspend fun noteBackup(atEpochSeconds: Long, result: String) {
        context.dataStore.edit { p -> p[BACKUP_LAST_AT] = atEpochSeconds; p[BACKUP_LAST_RESULT] = result }
    }

    /**
     * Uebernimmt Werte aus einer Sicherung. `plain` sind die unverschluesselt
     * gesicherten Einstellungen, `secrets` die entschluesselten Zugangsdaten
     * (null, wenn der Nutzer sie nicht wiederherstellen will).
     */
    suspend fun restore(plain: Map<String, String>, secrets: Map<String, String>?) {
        context.dataStore.edit { p ->
            fun str(key: Preferences.Key<String>, name: String, from: Map<String, String>?) { from?.get(name)?.let { p[key] = it } }
            fun int(key: Preferences.Key<Int>, name: String) { plain[name]?.toIntOrNull()?.let { p[key] = it } }
            fun dbl(key: Preferences.Key<Double>, name: String) { plain[name]?.toDoubleOrNull()?.let { p[key] = it } }
            fun lng(key: Preferences.Key<Long>, name: String) { plain[name]?.toLongOrNull()?.let { p[key] = it } }
            str(FRITZ_HOST, "fritzHost", plain); str(FRITZ_USER, "fritzUser", plain); str(SENEC_BASE_URL, "senecBaseUrl", plain)
            int(POLL_SECONDS, "pollSeconds"); dbl(PRICE_PER_KWH, "pricePerKwh"); dbl(FEED_IN_PER_KWH, "feedInPerKwh"); int(KEEP_DAYS, "keepDays")
            str(SMARTCAR_APP_ID, "smartcarAppId", plain); str(SMARTCAR_CLIENT_ID, "smartcarClientId", plain)
            str(SMARTCAR_VEHICLE_ID, "smartcarVehicleId", plain); str(SMARTCAR_USER_ID, "smartcarUserId", plain)
            int(CAR_FALLBACK_POWER, "carFallbackPowerW"); dbl(CAR_LEARNED_POWER, "carLearnedPowerW"); dbl(SYSTEM_COST, "systemCostEur")
            str(FORD_VIN, "fordVin", plain); str(FORD_LOCATION, "fordLocationId", plain)
            dbl(HOME_LAT, "homeLat"); dbl(HOME_LON, "homeLon"); str(CHARGE_RULES, "chargeRules", plain)
            lng(CHARGE_LAST_CMD, "chargeLastCommandAt"); str(CHARGE_LOG, "chargeLog", plain); str(ALERTS, "alerts", plain)
            str(SENEC_KEY, "senecKey", secrets); str(FRITZ_PASSWORD, "fritzPassword", secrets)
            str(SMARTCAR_CLIENT_SECRET, "smartcarClientSecret", secrets); str(FORD_TOKENS, "fordTokensJson", secrets)
        }
    }

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
        val CAR_LEARNED_POWER = doublePreferencesKey("car_learned_power")
        val SYSTEM_COST = doublePreferencesKey("system_cost_eur")
        val FORD_TOKENS = stringPreferencesKey("ford_tokens")
        val FORD_VIN = stringPreferencesKey("ford_vin")
        val FORD_LOCATION = stringPreferencesKey("ford_location")
        val HOME_LAT = doublePreferencesKey("home_lat")
        val HOME_LON = doublePreferencesKey("home_lon")
        val CHARGE_RULES = stringPreferencesKey("charge_rules")
        val CHARGE_LAST_CMD = longPreferencesKey("charge_last_cmd")
        val CHARGE_OVERRIDE = booleanPreferencesKey("charge_override")
        val CHARGE_LOG = stringPreferencesKey("charge_log")
        val BACKUP_TREE = stringPreferencesKey("backup_tree")
        val BACKUP_PASSWORD = stringPreferencesKey("backup_password")
        val BACKUP_LAST_AT = longPreferencesKey("backup_last_at")
        val BACKUP_LAST_RESULT = stringPreferencesKey("backup_last_result")
        val ALERTS = stringPreferencesKey("alerts")
        val ALERT_STATE = stringPreferencesKey("alert_state")
        private val rulesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
