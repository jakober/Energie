package com.jakober.energie.core.senec

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class SenecConnectException(val status: Int, message: String) : Exception(message)

/**
 * Offizielle SENEC.Connect-API (developer.senec.com). Sie laeuft ueber Azure
 * API Management; der Abonnementschluessel aus dem Entwicklerportal geht als
 * Header `Ocp-Apim-Subscription-Key` mit. Primaer- und Sekundaerschluessel
 * sind gleichwertig - der zweite ist zum Wechseln ohne Unterbrechung gedacht.
 *
 * Stand September 2026 liefert die API nur Momentaufnahmen, keine Historie
 * und keine Steuerung. Den Verlauf baut die App daher selbst auf, indem sie
 * regelmaessig abfragt und die Werte lokal speichert.
 */
class SenecConnectClient(
    private val http: HttpClient,
    private val subscriptionKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Momentaufnahme aller Anlagen des Kontos. */
    suspend fun systems(): List<SenecSystem> = parse(rawGeneral())

    /** Rohantwort als Text, fuer die Ansicht "Rohdaten" in der App. */
    suspend fun rawGeneral(include: String? = null): String {
        val response = http.get("${baseUrl.trimEnd('/')}/systems/device-data/general") {
            header(KEY_HEADER, subscriptionKey)
            header("Accept", "application/json")
            if (include != null) parameter("include", include)
        }
        val body = response.bodyAsText()
        when (response.status) {
            HttpStatusCode.OK -> return body
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                // Mit SENECs eigener Begruendung: "ungueltiger Schluessel" und "Abonnement nicht
                // aktiv" kommen beide als 401 und sind ohne den Wortlaut nicht zu unterscheiden.
                throw SenecConnectException(
                    response.status.value,
                    "SENEC lehnt den Schluessel ab (${response.status.value})." +
                        (reason(body)?.let { " SENEC sagt: $it" } ?: " Stimmt der Abonnementschluessel?"),
                )
            HttpStatusCode.TooManyRequests ->
                throw SenecConnectException(429, "SENEC-Abfragekontingent erschoepft (429). Abfrageintervall vergroessern.")
            else -> throw SenecConnectException(response.status.value, "SENEC antwortet mit ${response.status.value}: ${body.take(200)}")
        }
    }

    /** Der Satz, mit dem SENEC eine Ablehnung begruendet, soweit die Antwort einen enthaelt. */
    private fun reason(body: String): String? {
        val message = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)?.get("message")?.toString()?.trim('"')
        }.getOrNull()
        return (message ?: body).trim().take(180).takeIf { it.isNotBlank() }
    }

    fun parse(body: String): List<SenecSystem> {
        val element: JsonElement = json.parseToJsonElement(body)
        // Eine Liste von Anlagen ist der dokumentierte Fall; ein einzelnes Objekt
        // fangen wir trotzdem ab, falls SENEC das Format aendert.
        return when (element) {
            is JsonArray -> element.map { json.decodeFromJsonElement(SenecSystem.serializer(), it) }
            is JsonObject -> listOf(json.decodeFromJsonElement(SenecSystem.serializer(), element))
            else -> emptyList()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://apim-eds-gwc-prod.azure-api.net/senec-connect/v1"
        const val KEY_HEADER = "Ocp-Apim-Subscription-Key"
    }
}
