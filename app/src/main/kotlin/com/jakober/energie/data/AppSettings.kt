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
import com.jakober.energie.core.places.NamedPlace
import com.jakober.energie.core.forecast.PvForecast
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.jakober.energie.core.rules.ChargeRules
import com.jakober.energie.core.senec.SenecConnectClient
import com.jakober.energie.core.smartcar.CarState
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "einstellungen")

/**
 * Einstellungen in DataStore im privaten App-Speicher. Androids Auto Backup
 * (allowBackup im Manifest) sichert diesen Bereich Ende-zu-Ende verschluesselt
 * ins Google-Konto und stellt ihn bei Neuinstallation wieder her - bewusst so
 * belassen, damit die Zugangsdaten nicht neu eingegeben werden muessen.
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
            carPublicPricePerKwh = p[CAR_PUBLIC_PRICE] ?: 0.59,
            carBatteryNominalKwh = p[CAR_BATTERY_NOMINAL] ?: 0.0,
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
            hubSilentReported = p[HUB_SILENT_REPORTED] ?: false,
            places = p[PLACES]?.let { runCatching { rulesJson.decodeFromString(placesSerializer, it) }.getOrNull() } ?: emptyList(),
            plugs = p[PLUGS]?.let { runCatching { rulesJson.decodeFromString(plugsSerializer, it) }.getOrNull() } ?: emptyList(),
            // Vorgaben aus dem Build: URL und Schluessel immer, E-Mail und Passwort nur, wenn beim Bauen hinterlegt.
            cloudUrl = p[CLOUD_URL]?.takeIf { it.isNotBlank() } ?: com.jakober.energie.BuildConfig.CLOUD_URL,
            cloudAnonKey = p[CLOUD_ANON_KEY]?.takeIf { it.isNotBlank() } ?: com.jakober.energie.BuildConfig.CLOUD_ANON_KEY,
            cloudEmail = p[CLOUD_EMAIL]?.takeIf { it.isNotBlank() } ?: com.jakober.energie.BuildConfig.CLOUD_EMAIL,
            cloudPassword = p[CLOUD_PASSWORD]?.takeIf { it.isNotBlank() } ?: com.jakober.energie.BuildConfig.CLOUD_PASSWORD,
            cloudRole = p[CLOUD_ROLE]?.let { runCatching { CloudRole.valueOf(it) }.getOrNull() } ?: CloudRole.STANDALONE,
            cloudSessionJson = p[CLOUD_SESSION] ?: "",
            cloudUploadedAt = p[CLOUD_UPLOADED_AT] ?: 0L,
            cloudSyncedAt = p[CLOUD_SYNCED_AT] ?: 0L,
            cloudSettingsAppliedAt = p[CLOUD_SETTINGS_APPLIED_AT] ?: 0L,
            cloudDaysUploadedThrough = p[CLOUD_DAYS_THROUGH] ?: "",
            cloudTodayUploadedAt = p[CLOUD_TODAY_UPLOADED_AT] ?: 0L,
            pushToken = p[PUSH_TOKEN] ?: "",
            pushRegisteredToken = p[PUSH_REGISTERED] ?: "",
            pvPeakKw = p[PV_PEAK_KW] ?: 0.0,
            pvTiltDeg = p[PV_TILT] ?: 30,
            pvAzimuthDeg = p[PV_AZIMUTH] ?: 0,
            pvPeakKw2 = p[PV_PEAK_KW2] ?: 0.0,
            pvTiltDeg2 = p[PV_TILT2] ?: 30,
            pvAzimuthDeg2 = p[PV_AZIMUTH2] ?: 0,
            pvCalibration = p[PV_CALIBRATION] ?: 1.0,
            pvForecast = p[PV_FORECAST]?.takeIf { it.isNotBlank() }?.let { runCatching { rulesJson.decodeFromString(PvForecast.serializer(), it) }.getOrNull() },
            pvForecastHistory = p[PV_FORECAST_HISTORY]?.let { runCatching { rulesJson.decodeFromString(historySerializer, it) }.getOrNull() } ?: emptyMap(),
        )
    }

    suspend fun savePvForecast(f: PvForecast?) { context.dataStore.edit { if (f == null) it.remove(PV_FORECAST) else it[PV_FORECAST] = rulesJson.encodeToString(PvForecast.serializer(), f) } }
    suspend fun savePvForecastHistory(h: Map<String, Double>) { context.dataStore.edit { it[PV_FORECAST_HISTORY] = rulesJson.encodeToString(historySerializer, h) } }
    suspend fun savePvCalibration(c: Double) { context.dataStore.edit { it[PV_CALIBRATION] = c } }

    suspend fun savePlaces(places: List<NamedPlace>) { context.dataStore.edit { it[PLACES] = rulesJson.encodeToString(placesSerializer, places) } }

    suspend fun savePlugs(plugs: List<com.jakober.energie.core.plugs.PlugDevice>) { context.dataStore.edit { it[PLUGS] = rulesJson.encodeToString(plugsSerializer, plugs) } }

    suspend fun saveCloudSession(json: String) { context.dataStore.edit { it[CLOUD_SESSION] = json } }
    suspend fun saveCloudUploadedAt(epochSeconds: Long) { context.dataStore.edit { it[CLOUD_UPLOADED_AT] = epochSeconds } }
    suspend fun saveCloudSyncedAt(epochSeconds: Long) { context.dataStore.edit { it[CLOUD_SYNCED_AT] = epochSeconds } }
    suspend fun saveCloudSettingsAppliedAt(epochSeconds: Long) { context.dataStore.edit { it[CLOUD_SETTINGS_APPLIED_AT] = epochSeconds } }
    suspend fun saveCloudDaysUploadedThrough(isoDate: String) { context.dataStore.edit { it[CLOUD_DAYS_THROUGH] = isoDate } }
    suspend fun saveCloudTodayUploadedAt(epochSeconds: Long) { context.dataStore.edit { it[CLOUD_TODAY_UPLOADED_AT] = epochSeconds } }
    suspend fun saveCloudRole(role: CloudRole) { context.dataStore.edit { it[CLOUD_ROLE] = role.name } }
    suspend fun savePushToken(token: String) { context.dataStore.edit { it[PUSH_TOKEN] = token } }
    suspend fun savePushRegistered(token: String) { context.dataStore.edit { it[PUSH_REGISTERED] = token } }

    suspend fun saveAlerts(a: AlertSettings) { context.dataStore.edit { it[ALERTS] = rulesJson.encodeToString(AlertSettings.serializer(), a) } }

    suspend fun saveHubSilentReported(v: Boolean) { context.dataStore.edit { it[HUB_SILENT_REPORTED] = v } }

    suspend fun saveAlertState(s: AlertState) { context.dataStore.edit { it[ALERT_STATE] = rulesJson.encodeToString(AlertState.serializer(), s) } }

    /**
     * Letzter bekannter Autozustand. Er liegt sonst nur im Arbeitsspeicher und waere nach
     * jedem Neustart der Zentrale weg; die Anzeigegeraete haetten dann bis zur naechsten
     * FordPass-Abfrage nichts vom Auto zu zeigen.
     */
    suspend fun lastCarState(): CarState? = context.dataStore.data.first()[CAR_STATE]
        ?.let { runCatching { rulesJson.decodeFromString(CarState.serializer(), it) }.getOrNull() }

    suspend fun saveCarState(c: CarState) {
        context.dataStore.edit { it[CAR_STATE] = rulesJson.encodeToString(CarState.serializer(), c) }
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
            p[SYSTEM_COST] = s.systemCostEur.coerceAtLeast(0.0)
            p[CAR_PUBLIC_PRICE] = s.carPublicPricePerKwh.coerceAtLeast(0.0)
            p[CAR_BATTERY_NOMINAL] = s.carBatteryNominalKwh.coerceAtLeast(0.0)
            // Aendert sich die Cloud-Anmeldung, ist die alte Sitzung hinfaellig.
            val cloudChanged = p[CLOUD_URL] != s.cloudUrl.trim() || p[CLOUD_ANON_KEY] != s.cloudAnonKey.trim() || p[CLOUD_EMAIL] != s.cloudEmail.trim() || p[CLOUD_PASSWORD] != s.cloudPassword
            p[CLOUD_URL] = s.cloudUrl.trim().trimEnd('/')
            p[CLOUD_ANON_KEY] = s.cloudAnonKey.trim()
            p[CLOUD_EMAIL] = s.cloudEmail.trim()
            p[CLOUD_PASSWORD] = s.cloudPassword
            if (cloudChanged) p.remove(CLOUD_SESSION)
            // Aendern sich Lage oder Groesse der Anlage, ist die alte Prognose hinfaellig.
            val pvChanged = p[PV_PEAK_KW] != s.pvPeakKw || p[PV_TILT] != s.pvTiltDeg || p[PV_AZIMUTH] != s.pvAzimuthDeg ||
                p[PV_PEAK_KW2] != s.pvPeakKw2 || p[PV_TILT2] != s.pvTiltDeg2 || p[PV_AZIMUTH2] != s.pvAzimuthDeg2
            p[PV_PEAK_KW] = s.pvPeakKw.coerceIn(0.0, 1000.0)
            p[PV_TILT] = s.pvTiltDeg.coerceIn(0, 90)
            p[PV_AZIMUTH] = s.pvAzimuthDeg.coerceIn(-180, 180)
            p[PV_PEAK_KW2] = s.pvPeakKw2.coerceIn(0.0, 1000.0)
            p[PV_TILT2] = s.pvTiltDeg2.coerceIn(0, 90)
            p[PV_AZIMUTH2] = s.pvAzimuthDeg2.coerceIn(-180, 180)
            if (pvChanged) p.remove(PV_FORECAST)
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
        "carPublicPricePerKwh" to s.carPublicPricePerKwh.toString(), "carBatteryNominalKwh" to s.carBatteryNominalKwh.toString(),
        "fordVin" to s.fordVin, "fordLocationId" to s.fordLocationId, "homeLat" to s.homeLat.toString(), "homeLon" to s.homeLon.toString(),
        "chargeRules" to rulesJson.encodeToString(ChargeRules.serializer(), s.chargeRules),
        "chargeLastCommandAt" to s.chargeLastCommandAt.toString(), "chargeLog" to s.chargeLog,
        "alerts" to rulesJson.encodeToString(AlertSettings.serializer(), s.alerts),
        "places" to rulesJson.encodeToString(placesSerializer, s.places),
        "plugs" to rulesJson.encodeToString(plugsSerializer, s.plugs),
        "cloudUrl" to s.cloudUrl, "cloudAnonKey" to s.cloudAnonKey, "cloudEmail" to s.cloudEmail,
        "pvPeakKw" to s.pvPeakKw.toString(), "pvTiltDeg" to s.pvTiltDeg.toString(), "pvAzimuthDeg" to s.pvAzimuthDeg.toString(), "pvCalibration" to s.pvCalibration.toString(),
        "pvPeakKw2" to s.pvPeakKw2.toString(), "pvTiltDeg2" to s.pvTiltDeg2.toString(), "pvAzimuthDeg2" to s.pvAzimuthDeg2.toString(),
    )

    /** Die Geheimnisse, die nur verschluesselt in die Sicherung duerfen. */
    fun secretsForBackup(s: Settings): Map<String, String> = linkedMapOf(
        "senecKey" to s.senecKey, "fritzPassword" to s.fritzPassword,
        "smartcarClientSecret" to s.smartcarClientSecret, "fordTokensJson" to s.fordTokensJson,
        "cloudPassword" to s.cloudPassword,
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
            dbl(CAR_PUBLIC_PRICE, "carPublicPricePerKwh"); dbl(CAR_BATTERY_NOMINAL, "carBatteryNominalKwh")
            str(FORD_VIN, "fordVin", plain); str(FORD_LOCATION, "fordLocationId", plain)
            dbl(HOME_LAT, "homeLat"); dbl(HOME_LON, "homeLon"); str(CHARGE_RULES, "chargeRules", plain)
            lng(CHARGE_LAST_CMD, "chargeLastCommandAt"); str(CHARGE_LOG, "chargeLog", plain); str(ALERTS, "alerts", plain); str(PLACES, "places", plain)
            str(PLUGS, "plugs", plain)
            str(CLOUD_URL, "cloudUrl", plain); str(CLOUD_ANON_KEY, "cloudAnonKey", plain); str(CLOUD_EMAIL, "cloudEmail", plain)
            str(CLOUD_PASSWORD, "cloudPassword", secrets)
            dbl(PV_PEAK_KW, "pvPeakKw"); int(PV_TILT, "pvTiltDeg"); int(PV_AZIMUTH, "pvAzimuthDeg"); dbl(PV_CALIBRATION, "pvCalibration")
            dbl(PV_PEAK_KW2, "pvPeakKw2"); int(PV_TILT2, "pvTiltDeg2"); int(PV_AZIMUTH2, "pvAzimuthDeg2")
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
        val CAR_PUBLIC_PRICE = doublePreferencesKey("car_public_price")
        val CAR_BATTERY_NOMINAL = doublePreferencesKey("car_battery_nominal_kwh")
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
        val CAR_STATE = stringPreferencesKey("car_state")
        val HUB_SILENT_REPORTED = booleanPreferencesKey("hub_silent_reported")
        val PLACES = stringPreferencesKey("places")
        val PLUGS = stringPreferencesKey("plugs")
        val CLOUD_URL = stringPreferencesKey("cloud_url")
        val CLOUD_ANON_KEY = stringPreferencesKey("cloud_anon_key")
        val CLOUD_EMAIL = stringPreferencesKey("cloud_email")
        val CLOUD_PASSWORD = stringPreferencesKey("cloud_password")
        val CLOUD_ROLE = stringPreferencesKey("cloud_role")
        val CLOUD_SESSION = stringPreferencesKey("cloud_session")
        val CLOUD_UPLOADED_AT = longPreferencesKey("cloud_uploaded_at")
        val CLOUD_SYNCED_AT = longPreferencesKey("cloud_synced_at")
        val CLOUD_SETTINGS_APPLIED_AT = longPreferencesKey("cloud_settings_applied_at")
        val CLOUD_DAYS_THROUGH = stringPreferencesKey("cloud_days_through")
        val CLOUD_TODAY_UPLOADED_AT = longPreferencesKey("cloud_today_uploaded_at")
        val PUSH_TOKEN = stringPreferencesKey("push_token")
        val PUSH_REGISTERED = stringPreferencesKey("push_registered")
        val PV_PEAK_KW = doublePreferencesKey("pv_peak_kw")
        val PV_TILT = intPreferencesKey("pv_tilt")
        val PV_AZIMUTH = intPreferencesKey("pv_azimuth")
        val PV_CALIBRATION = doublePreferencesKey("pv_calibration")
        val PV_PEAK_KW2 = doublePreferencesKey("pv_peak_kw2")
        val PV_TILT2 = intPreferencesKey("pv_tilt2")
        val PV_AZIMUTH2 = intPreferencesKey("pv_azimuth2")
        val PV_FORECAST = stringPreferencesKey("pv_forecast")
        val PV_FORECAST_HISTORY = stringPreferencesKey("pv_forecast_history")
        private val historySerializer = MapSerializer(String.serializer(), Double.serializer())
        private val placesSerializer = ListSerializer(NamedPlace.serializer())
        private val plugsSerializer = ListSerializer(com.jakober.energie.core.plugs.PlugDevice.serializer())
        private val rulesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
