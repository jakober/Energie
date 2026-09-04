package com.jakober.energie.core.fordpass

import com.jakober.energie.core.smartcar.JsonPick
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class FordPassException(val status: Int, message: String) : Exception(message)

/**
 * Inoffizieller Zugang zu FordPass, so wie ihn die FordPass-App selbst nutzt
 * (nachgebaut nach marq24/ha-fordpass, Stand September 2026). Ford
 * dokumentiert nichts davon und kann es jederzeit aendern oder Konten
 * sperren - deshalb ein Zweitkonto verwenden.
 *
 * Ablauf: Login im Browser ueber Fords B2C-Seite mit PKCE, Rueckkehr ueber
 * `fordapp://userauthorized?code=...`, Tausch in ein Ford-Token, daraus ein
 * Autonomic-Token fuer Telemetrie und Befehle. Tokens haelt die App.
 */
class FordPassClient(
    private val http: HttpClient,
    private var tokens: FordTokens?,
    private val onTokens: suspend (FordTokens) -> Unit = {},
    private val region: Region = Region.GERMANY,
    private val clock: Clock = Clock.System,
) {
    data class Region(val loginUrl: String, val appId: String, val locale: String, val countryCode: String) {
        companion object {
            val GERMANY = Region("https://login.ford.de", "667D773E-1BDC-4139-8AD0-2B16474E8DC7", "de-DE", "DEU")
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()

    val hasTokens: Boolean get() = tokens != null

    // ----------------------------------------------------------------- Login

    /** Zufallswert fuer PKCE; die App merkt ihn sich bis zur Rueckkehr aus dem Login. */
    fun newCodeVerifier(): String {
        val bytes = ByteArray(96).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun loginUrl(codeVerifier: String): String {
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.UTF_8)))
        return "${region.loginUrl}/$OAUTH_ID/$SIGN_UP${region.locale}/oauth2/v2.0/authorize" +
            "?redirect_uri=$REDIRECT_URI&response_type=code&max_age=3600" +
            "&code_challenge=$challenge&code_challenge_method=S256" +
            "&scope=%20$CLIENT_ID%20openid&client_id=$CLIENT_ID" +
            "&ui_locales=${region.locale}&language_code=${region.locale}" +
            "&ford_application_id=${region.appId}&country_code=${region.countryCode}"
    }

    /** Nimmt die komplette Rueckkehr-Adresse oder nur den Code entgegen. */
    suspend fun exchangeCode(codeOrUrl: String, codeVerifier: String): FordTokens {
        val code = Regex("[?&]code=([^&#]+)").find(codeOrUrl)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: codeOrUrl.trim().takeIf { it.isNotEmpty() && !it.contains("://") }
            ?: throw FordPassException(400, "In der Rueckkehr-Adresse steht kein code=")

        val first = http.submitForm(
            url = "${region.loginUrl}/$OAUTH_ID/$SIGN_UP${region.locale}/oauth2/v2.0/token",
            formParameters = parameters {
                append("client_id", CLIENT_ID)
                append("scope", "$CLIENT_ID openid")
                append("redirect_uri", REDIRECT_URI)
                append("grant_type", "authorization_code")
                append("resource", "")
                append("code", code)
                append("code_verifier", codeVerifier)
            },
        ) { loginHeaders() }
        val firstBody = first.bodyAsText()
        val idpToken = JsonPick.string(parse(firstBody), "access_token")
            ?: throw FordPassException(first.status.value, "Ford-Login fehlgeschlagen (${first.status.value}): ${firstBody.take(300)}")
        return catToken(buildJsonObject { put("idpToken", idpToken) }.toString(), "cat-with-b2c-access-token")
    }

    private suspend fun catToken(body: String, path: String): FordTokens {
        val response = http.post("$FOUNDATIONAL_API/token/v2/$path") {
            apiHeaders()
            header("Application-Id", region.appId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        val el = parse(text)
        val access = JsonPick.string(el, "access_token")
            ?: throw FordPassException(response.status.value, "Ford-Token fehlgeschlagen (${response.status.value}): ${text.take(300)}")
        val refresh = JsonPick.string(el, "refresh_token") ?: tokens?.refreshToken
            ?: throw FordPassException(500, "Ford-Antwort ohne refresh_token")
        val expires = JsonPick.number(el, "expires_in")?.toLong() ?: 1800L
        val t = FordTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = clock.now().epochSeconds + expires - 60,
            autoAccessToken = tokens?.autoAccessToken,
            autoExpiresAt = tokens?.autoExpiresAt ?: 0,
        )
        tokens = t
        onTokens(t)
        return t
    }

    private suspend fun fordToken(): String = lock.withLock {
        val t = tokens ?: throw FordPassException(401, "Nicht bei Ford angemeldet.")
        if (clock.now().epochSeconds < t.expiresAt) return@withLock t.accessToken
        catToken(buildJsonObject { put("refresh_token", t.refreshToken) }.toString(), "cat-with-refresh-token").accessToken
    }

    private suspend fun autoToken(): String {
        fordToken() // stellt sicher, dass ein gueltiges Refresh-Token da ist
        return lock.withLock {
            val t = tokens ?: throw FordPassException(401, "Nicht bei Ford angemeldet.")
            t.autoAccessToken?.takeIf { clock.now().epochSeconds < t.autoExpiresAt }?.let { return@withLock it }
            val response = http.submitForm(
                url = "$AUTONOMIC_ACCOUNT/auth/oidc/token",
                formParameters = parameters {
                    append("subject_token", t.refreshToken)
                    append("subject_issuer", "fordpass")
                    append("client_id", "fordpass-prod")
                    append("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                    append("subject_token_type", "urn:ietf:params:oauth:token-type:jwt")
                },
            ) { apiHeaders() }
            val text = response.bodyAsText()
            val el = parse(text)
            val access = JsonPick.string(el, "access_token")
                ?: throw FordPassException(response.status.value, "Autonomic-Token fehlgeschlagen (${response.status.value}): ${text.take(300)}")
            val expires = JsonPick.number(el, "expires_in")?.toLong() ?: 3600L
            val updated = t.copy(autoAccessToken = access, autoExpiresAt = clock.now().epochSeconds + expires - 60)
            tokens = updated
            onTokens(updated)
            access
        }
    }

    // -------------------------------------------------------------- Fahrzeuge

    suspend fun vehicles(): List<FordVehicle> {
        val token = fordToken()
        val response = http.get("$VEHICLE_API/fpcpl-user-garage-service/v1/user/garage") {
            apiHeaders()
            header("auth-token", token)
            header("Application-Id", region.appId)
            header("countryCode", region.countryCode)
            header("locale", region.locale)
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..207) throw FordPassException(response.status.value, "Fahrzeugliste fehlgeschlagen (${response.status.value}): ${text.take(300)}")
        val el = parse(text)
        val items: List<JsonObject> = when (el) {
            is JsonArray -> el.filterIsInstance<JsonObject>()
            is JsonObject -> JsonPick.objectsWith(el, "vin")
            else -> emptyList()
        }
        return items.mapNotNull { o ->
            val vin = (o["vin"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val profile = o["profile"] as? JsonObject
            FordVehicle(
                vin = vin,
                model = profile?.let { JsonPick.string(it, "model", "modelName") } ?: JsonPick.string(o, "model", "modelName"),
                year = profile?.let { JsonPick.string(it, "year", "modelYear") } ?: JsonPick.string(o, "year", "modelYear"),
                nickname = JsonPick.string(o, "nickName", "nickname", "vehicleNickName"),
            )
        }.distinctBy { it.vin }
    }

    // ------------------------------------------------------------- Telemetrie

    suspend fun state(vin: String): FordCarState {
        val token = autoToken()
        val response = http.get("$AUTONOMIC/telemetry/sources/fordpass/vehicles/$vin") {
            apiHeaders()
            header("Authorization", "Bearer $token")
            parameter("lrdt", "01-01-1970 00:00:00")
        }
        val text = response.bodyAsText()
        if (response.status.value != 200) {
            throw FordPassException(response.status.value, "Ford-Telemetrie fehlgeschlagen (${response.status.value}): ${text.take(300)}")
        }
        val metrics = (parse(text) as? JsonObject)?.get("metrics") as? JsonObject
        fun metricNumber(key: String) = (metrics?.get(key) as? JsonObject)?.get("value")?.let { (it as? JsonPrimitive)?.doubleOrNull }
        fun metricString(key: String) = (metrics?.get(key) as? JsonObject)?.get("value")?.let { (it as? JsonPrimitive)?.contentOrNull }
        // Position: metrics.position.value.location.{lat,lon}
        val location = ((metrics?.get("position") as? JsonObject)?.get("value") as? JsonObject)?.get("location") as? JsonObject
        val lat = (location?.get("lat") as? JsonPrimitive)?.doubleOrNull
        val lon = (location?.get("lon") as? JsonPrimitive)?.doubleOrNull
        return FordCarState(
            at = clock.now(),
            vin = vin,
            socPercent = metricNumber("xevBatteryStateOfCharge"),
            rangeKm = metricNumber("xevBatteryRange"),
            chargeStatus = metricString("xevBatteryChargeDisplayStatus"),
            plugStatus = metricString("xevPlugChargerStatus"),
            chargerVoltage = metricNumber("xevBatteryChargerVoltageOutput"),
            chargerCurrent = metricNumber("xevBatteryChargerCurrentOutput"),
            latitude = lat,
            longitude = lon,
            raw = text,
        )
    }

    // ---------------------------------------------------------------- Befehle

    suspend fun pauseCharge(vin: String) = command(vin, "pauseGlobalChargeCommand")
    suspend fun startCharge(vin: String) = command(vin, "startGlobalChargeCommand")
    suspend fun cancelCharge(vin: String) = command(vin, "cancelGlobalChargeCommand")

    private suspend fun command(vin: String, type: String): FordCommandResult {
        val token = autoToken()
        val body = buildJsonObject {
            put("tags", buildJsonObject {})
            put("type", type)
            put("version", "1.0.1")
            put("wakeUp", true)
        }.toString()
        val response = http.post("$AUTONOMIC_BETA/command/vehicles/$vin/commands") {
            apiHeaders()
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return FordCommandResult(response.status.value, response.bodyAsText())
    }

    // --------------------------------------------------------------- Ladeziel

    /** Ladeorte mit Ladeprofil; das Ladeziel gilt je Ort. */
    suspend fun chargeLocations(vin: String): List<FordChargeLocation> {
        val token = fordToken()
        val response = http.get("$VEHICLE_API/electrification/experiences/v2/vehicles/preferred-charge-times") {
            apiHeaders()
            header("auth-token", token)
            header("Application-Id", region.appId)
            header("vin", vin)
        }
        val text = response.bodyAsText()
        if (response.status.value != 200) throw FordPassException(response.status.value, "Ladeorte fehlgeschlagen (${response.status.value}): ${text.take(300)}")
        val arr = parse(text) as? JsonArray ?: return emptyList()
        return arr.filterIsInstance<JsonObject>()
            .filter { (it["vin"] as? JsonPrimitive)?.contentOrNull?.equals(vin, ignoreCase = true) != false }
            .mapNotNull { entry ->
                val location = entry["location"] as? JsonObject ?: return@mapNotNull null
                val profile = entry["chargeProfile"] as? JsonObject
                FordChargeLocation(
                    id = (location["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    name = (location["name"] as? JsonPrimitive)?.contentOrNull,
                    type = (location["type"] as? JsonPrimitive)?.contentOrNull,
                    targetSoc = (profile?.get("targetSoc") as? JsonPrimitive)?.intOrNull,
                    chargeMode = (profile?.get("chargeMode") as? JsonPrimitive)?.contentOrNull,
                    raw = entry,
                )
            }
    }

    /**
     * Setzt das Ladeziel eines Ladeorts. Ford nimmt 50 bis 100 an, unter 80
     * nur volle Zehner (50, 60, 70).
     */
    suspend fun setTargetSoc(vin: String, location: FordChargeLocation, percent: Int): FordCommandResult {
        val target = percent.coerceIn(50, 100).let { if (it < 80) it / 10 * 10 else it }
        val profile = location.raw["chargeProfile"] as? JsonObject ?: throw FordPassException(400, "Ladeort ohne Ladeprofil")
        val loc = location.raw["location"] as? JsonObject ?: throw FordPassException(400, "Ladeort ohne Ortsdaten")
        val body = buildJsonObject {
            put("chargeProfile", buildJsonObject {
                profile["chargeMode"]?.let { put("chargeMode", it) }
                profile["schedules"]?.let { put("schedules", it) }
                put("targetSoc", target)
            })
            put("location", buildJsonObject {
                for (k in listOf("address", "id", "latitude", "longitude", "name", "type")) loc[k]?.let { put(k, it) }
            })
            put("vin", vin)
        }.toString()
        val token = fordToken()
        val response = http.post("$VEHICLE_API/electrification/experiences/v2/vehicles/preferred-charge-times/locations/${location.id.encodeURLParameter()}") {
            apiHeaders()
            header("auth-token", token)
            header("Application-Id", region.appId)
            header("vin", vin)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return FordCommandResult(response.status.value, response.bodyAsText())
    }

    // ------------------------------------------------------------------ intern

    private fun parse(text: String) = runCatching { json.parseToJsonElement(text) }.getOrElse { JsonObject(emptyMap()) }

    // Kein eigenes "Accept-Encoding": Setzt man es selbst, reicht OkHttp die
    // Antwort gepackt durch, statt sie zu entpacken - dann kommt Zeichensalat.
    private fun io.ktor.client.request.HttpRequestBuilder.apiHeaders() {
        header("User-Agent", USER_AGENT)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.loginHeaders() {
        header("User-Agent", USER_AGENT)
    }

    companion object {
        const val OAUTH_ID = "4566605f-43a7-400a-946e-89cc9fdb0bd7"
        const val CLIENT_ID = "09852200-05fd-41f6-8c21-d36d3497dc64"
        const val SIGN_UP = "B2C_1A_SignInSignUp_"
        const val REDIRECT_URI = "fordapp://userauthorized"
        const val USER_AGENT = "okhttp/4.12.0"
        const val FOUNDATIONAL_API = "https://api.foundational.ford.com/api"
        const val VEHICLE_API = "https://api.vehicle.ford.com/api"
        const val AUTONOMIC = "https://api.autonomic.ai/v1"
        const val AUTONOMIC_BETA = "https://api.autonomic.ai/v1beta"
        const val AUTONOMIC_ACCOUNT = "https://accounts.autonomic.ai/v1"
    }
}
