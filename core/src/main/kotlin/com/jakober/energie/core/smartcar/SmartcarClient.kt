package com.jakober.energie.core.smartcar

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.seconds

class SmartcarException(val status: Int, message: String) : Exception(message)

/**
 * Smartcar API v3. Ein Anwendungs-Token aus Client-ID und Client-Secret
 * (Client-Credentials) gilt fuer alle verbundenen Fahrzeuge; der Nutzer wird
 * je Anfrage ueber den Header `sc-user-id` benannt. Fahrzeuge verbindet der
 * Nutzer einmalig ueber Smartcar Connect (siehe [connectUrl]).
 *
 * Die Antwortformate von v3 sind hier nicht dokumentiert verfuegbar; das
 * Parsen sucht deshalb tolerant nach Kennungen (siehe [JsonPick]) und haelt
 * die Rohantworten fest.
 */
class SmartcarClient(
    private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()
    private var token: String? = null
    private var tokenValidUntil: Instant = Instant.DISTANT_PAST

    suspend fun accessToken(): String = lock.withLock {
        val now = clock.now()
        token?.takeIf { now < tokenValidUntil }?.let { return@withLock it }

        // Erst Zugangsdaten im Formular, dann als HTTP-Basic-Kopfzeile: OAuth-Server
        // akzeptieren mal das eine, mal das andere. Beide Antworten landen in der
        // Fehlermeldung, damit die Ursache (falsches Secret, falsche Form) sichtbar wird.
        val attempts = ArrayList<String>()
        val first = http.submitForm(
            url = tokenUrl,
            formParameters = parameters {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", clientSecret)
            },
        )
        var body = first.bodyAsText()
        var status = first.status.value
        if (status !in 200..299) {
            attempts += "Formular: HTTP $status ${body.take(200)}"
            val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
            val second = http.submitForm(
                url = tokenUrl,
                formParameters = parameters { append("grant_type", "client_credentials") },
            ) { header("Authorization", basic) }
            body = second.bodyAsText()
            status = second.status.value
            if (status !in 200..299) {
                attempts += "Basic-Auth: HTTP $status ${body.take(200)}"
                throw SmartcarException(status, "Smartcar-Anmeldung fehlgeschlagen. " + attempts.joinToString(" | "))
            }
        }
        val el = json.parseToJsonElement(body)
        val t = JsonPick.string(el, "access_token") ?: throw SmartcarException(500, "Smartcar-Antwort ohne access_token: ${body.take(200)}")
        val expires = JsonPick.number(el, "expires_in")?.toLong() ?: 3600L
        token = t
        // Eine Minute Puffer, damit kein Aufruf mit einem gerade ablaufenden Token startet.
        tokenValidUntil = now + (expires - 60).coerceAtLeast(30).seconds
        t
    }

    /** Alle Fahrzeugverbindungen der Anwendung. */
    suspend fun connections(): List<SmartcarConnection> {
        val body = getText("$baseUrl/connections?page[size]=50", userId = null)
        val el = json.parseToJsonElement(body)
        return JsonPick.objectsWith(el, "vehicleId").mapNotNull { obj ->
            val vehicleId = JsonPick.string(obj, "vehicleId") ?: return@mapNotNull null
            SmartcarConnection(vehicleId = vehicleId, userId = JsonPick.string(obj, "userId"), raw = body)
        }.distinctBy { it.vehicleId }
    }

    /** Rohantwort eines einzelnen Signals, z. B. `tractionbattery-stateofcharge`. */
    suspend fun signal(vehicleId: String, userId: String?, code: String): String =
        getText("$baseUrl/vehicles/${vehicleId.encodeURLParameter()}/signals/$code", userId)

    /** Alle Signale auf einer Seite, zur Erkundung des Formats. */
    suspend fun allSignals(vehicleId: String, userId: String?): String =
        getText("$baseUrl/vehicles/${vehicleId.encodeURLParameter()}/signals?page[size]=200", userId)

    /** Holt die fuer die Ladesteuerung noetigen Signale und setzt sie zusammen. */
    suspend fun state(vehicleId: String, userId: String?): CarState {
        val raw = LinkedHashMap<String, String>()
        suspend fun fetch(code: String): JsonElement? = runCatching {
            val body = signal(vehicleId, userId, code)
            raw[code] = body
            json.parseToJsonElement(body)
        }.getOrElse { e -> raw[code] = "Fehler: ${e.message}"; null }

        val soc = fetch(SIG_SOC)
        val range = fetch(SIG_RANGE)
        val charging = fetch(SIG_IS_CHARGING)
        val plugged = fetch(SIG_PLUGGED)
        val limit = fetch(SIG_LIMIT)
        val status = fetch(SIG_STATUS)
        val wattage = fetch(SIG_WATTAGE)
        val voltage = fetch(SIG_VOLTAGE)
        val amperage = fetch(SIG_AMPERAGE)

        val powerW: Double? = wattage?.let { JsonPick.number(it, "wattage", "power", "value") }?.let { w -> if (w < 100) w * 1000 else w }
            ?: run {
                val v = voltage?.let { JsonPick.number(it, "voltage", "value") }
                val a = amperage?.let { JsonPick.number(it, "amperage", "current", "value") }
                if (v != null && a != null && v > 0 && a > 0) v * a else null
            }

        return CarState(
            at = clock.now(),
            vehicleId = vehicleId,
            socPercent = soc?.let { JsonPick.number(it, "stateOfCharge", "percentRemaining", "percent", "value") }?.let(::asPercent),
            rangeKm = range?.let { JsonPick.number(it, "range", "distance", "value") },
            isCharging = charging?.let { JsonPick.boolean(it, "isCharging", "value") },
            isPluggedIn = plugged?.let { JsonPick.boolean(it, "isChargingCableConnected", "isPluggedIn", "value") },
            chargeLimitPercent = limit?.let { JsonPick.number(it, "activeLimit", "limit", "percent", "value") }?.let(::asPercent),
            chargingStatus = status?.let { JsonPick.string(it, "detailedChargingStatus", "status", "state", "value") },
            chargePowerW = powerW,
            raw = raw,
        )
    }

    suspend fun startCharge(vehicleId: String, userId: String?): CommandResult =
        command(vehicleId, userId, "charge/start", null)

    suspend fun stopCharge(vehicleId: String, userId: String?): CommandResult =
        command(vehicleId, userId, "charge/stop", null)

    /** Ladeziel in Prozent, Ford erlaubt 50 bis 100. */
    suspend fun setChargeLimit(vehicleId: String, userId: String?, percent: Int): CommandResult =
        command(
            vehicleId, userId, "charge/set-limit",
            """{"data":{"attributes":{"percent":${percent.coerceIn(50, 100)}}}}""",
        )

    private suspend fun command(vehicleId: String, userId: String?, path: String, body: String?): CommandResult {
        val t = accessToken()
        val response: HttpResponse = http.post("$baseUrl/vehicles/${vehicleId.encodeURLParameter()}/commands/$path") {
            header("Authorization", "Bearer $t")
            if (userId != null) header(USER_HEADER, userId)
            header("Accept", "application/json")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return CommandResult(response.status.value, response.bodyAsText())
    }

    private suspend fun getText(url: String, userId: String?): String {
        val t = accessToken()
        val response = http.get(url) {
            header("Authorization", "Bearer $t")
            header("Accept", "application/json")
            header("sc-unit-system", "metric")
            if (userId != null) header(USER_HEADER, userId)
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw SmartcarException(response.status.value, "Smartcar antwortet mit ${response.status.value}: ${body.take(300)}")
        }
        return body
    }

    // Manche Signale kommen als 0..1, andere als 0..100 - beides zu Prozent.
    private fun asPercent(v: Double): Double = if (v <= 1.0) v * 100 else v

    companion object {
        const val DEFAULT_BASE_URL = "https://vehicle.api.smartcar.com/v3"
        const val DEFAULT_TOKEN_URL = "https://iam.smartcar.com/oauth2/token"
        const val USER_HEADER = "sc-user-id"

        const val SIG_SOC = "tractionbattery-stateofcharge"
        const val SIG_RANGE = "tractionbattery-range"
        const val SIG_IS_CHARGING = "charge-ischarging"
        const val SIG_PLUGGED = "charge-ischargingcableconnected"
        const val SIG_LIMIT = "charge-activelimit"
        const val SIG_STATUS = "charge-detailedchargingstatus"
        const val SIG_WATTAGE = "charge-wattage"
        const val SIG_VOLTAGE = "charge-voltage"
        const val SIG_AMPERAGE = "charge-amperage"

        val DEFAULT_SCOPES = listOf("read_vehicle_info", "read_battery", "read_charge", "control_charge")

        /** Schema, unter dem Connect nach dem Verbinden zurueck in die App springt. */
        fun redirectScheme(applicationId: String) = "sc$applicationId"

        /**
         * Adresse von Smartcar Connect. `response_type=none` verbindet das
         * Fahrzeug direkt mit der Anwendung, ohne Code-Austausch - die App
         * arbeitet danach mit dem Anwendungs-Token.
         */
        fun connectUrl(applicationId: String, scopes: List<String> = DEFAULT_SCOPES, withRedirect: Boolean = true): String {
            val params = buildList {
                add("response_type" to "none")
                add("client_id" to applicationId)
                add("scope" to scopes.joinToString(" "))
                add("mode" to "live")
                add("approval_prompt" to "force")
                if (withRedirect) add("redirect_uri" to "${redirectScheme(applicationId)}://exchange")
            }
            return "https://connect.smartcar.com/oauth/authorize?" +
                params.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        }
    }
}
