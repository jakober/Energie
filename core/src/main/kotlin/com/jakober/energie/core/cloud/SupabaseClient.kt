package com.jakober.energie.core.cloud

import com.jakober.energie.core.model.EnergySample
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Anmeldung bei Supabase: Zugriffs- und Erneuerungs-Token samt Ablauf. */
@Serializable
data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    /** Unix-Sekunden, ab wann das Zugriffs-Token nicht mehr gilt. */
    val expiresAt: Long,
    val userId: String,
) {
    fun expiresSoon(now: Instant): Boolean = now.epochSeconds > expiresAt - 60
}

/** Ein Auftrag der Anzeige an die Zentrale. */
data class CloudCommand(val id: Long, val kind: String, val payload: JsonObject, val createdAt: String)

/** Ein Hinweis der Zentrale fuer die Anzeige. */
data class CloudAlert(val id: Long, val kind: String, val title: String, val body: String, val offerCharge: Boolean)

/**
 * Supabase ohne SDK: Auth ueber GoTrue, Tabellen ueber PostgREST. Nur der
 * oeffentliche anon-Schluessel plus die Anmeldung des Nutzers; die
 * Zugriffsregeln der Datenbank begrenzen alles auf die eigenen Zeilen.
 */
class SupabaseClient(
    private val http: HttpClient,
    private val url: String,
    private val anonKey: String,
) {
    private val base = url.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // ---------- Anmeldung ----------

    suspend fun signIn(email: String, password: String): CloudSession {
        val res = http.post("$base/auth/v1/token?grant_type=password") {
            header("apikey", anonKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("email", email.trim()); put("password", password) }.toString())
        }
        return parseSession(res)
    }

    suspend fun refresh(session: CloudSession): CloudSession {
        val res = http.post("$base/auth/v1/token?grant_type=refresh_token") {
            header("apikey", anonKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("refresh_token", session.refreshToken) }.toString())
        }
        return parseSession(res)
    }

    private suspend fun parseSession(res: HttpResponse): CloudSession {
        val text = res.bodyAsText()
        if (res.status.value !in 200..299) throw CloudException("Anmeldung fehlgeschlagen (${res.status.value}): ${errorText(text)}")
        val o = json.parseToJsonElement(text).jsonObject
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        val expiresAt = o["expires_at"]?.jsonPrimitive?.longOrNull ?: (kotlinx.datetime.Clock.System.now().epochSeconds + expiresIn)
        return CloudSession(
            accessToken = o["access_token"]?.jsonPrimitive?.contentOrNull ?: throw CloudException("Antwort ohne access_token"),
            refreshToken = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: "",
            expiresAt = expiresAt,
            userId = o["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    // ---------- Messpunkte ----------

    /** Schreibt Messpunkte; gleicher Zeitpunkt ueberschreibt (upsert). */
    suspend fun upsertSamples(session: CloudSession, samples: List<EnergySample>) {
        if (samples.isEmpty()) return
        val body = buildJsonArray {
            samples.forEach { s ->
                add(buildJsonObject {
                    put("at", s.at.toString())
                    put("data", json.encodeToJsonElement(EnergySample.serializer(), s))
                })
            }
        }
        val res = http.post("$base/rest/v1/samples") {
            auth(session)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        check(res, "Messpunkte schreiben")
    }

    /** Messpunkte nach `after`, aufsteigend, hoechstens `limit`. */
    suspend fun samplesAfter(session: CloudSession, after: Instant, limit: Int = 2000): List<EnergySample> {
        val res = http.get("$base/rest/v1/samples") {
            auth(session)
            parameter("select", "data")
            parameter("at", "gt.$after")
            parameter("order", "at.asc")
            parameter("limit", limit)
        }
        val text = check(res, "Messpunkte lesen")
        return json.parseToJsonElement(text).jsonArray.mapNotNull { row ->
            runCatching { json.decodeFromJsonElement(EnergySample.serializer(), row.jsonObject["data"]!!) }.getOrNull()
        }
    }

    /** Zeitpunkt des neuesten Messpunkts, null wenn noch keiner da ist. */
    suspend fun latestSampleAt(session: CloudSession): Instant? {
        val res = http.get("$base/rest/v1/samples") {
            auth(session)
            parameter("select", "at")
            parameter("order", "at.desc")
            parameter("limit", 1)
        }
        val text = check(res, "Neuesten Messpunkt lesen")
        val at = json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject?.get("at")?.jsonPrimitive?.contentOrNull ?: return null
        return runCatching { Instant.parse(at) }.getOrNull()
    }

    // ---------- Status, Einstellungen ----------

    suspend fun putStatus(session: CloudSession, live: JsonObject) {
        upsertRow(session, "status", buildJsonObject {
            put("user_id", session.userId)
            put("live", live)
            put("hub_seen_at", kotlinx.datetime.Clock.System.now().toString())
            put("updated_at", kotlinx.datetime.Clock.System.now().toString())
        })
    }

    /** Status der Zentrale: Inhalt und wann sie sich zuletzt gemeldet hat. */
    suspend fun getStatus(session: CloudSession): Pair<JsonObject, Instant?>? {
        val text = check(http.get("$base/rest/v1/status") { auth(session); parameter("select", "live,hub_seen_at"); parameter("limit", 1) }, "Status lesen")
        val row = json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject ?: return null
        val seen = row["hub_seen_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return (row["live"] as? JsonObject ?: JsonObject(emptyMap())) to seen
    }

    suspend fun putSettings(session: CloudSession, plain: Map<String, String>) {
        upsertRow(session, "settings", buildJsonObject {
            put("user_id", session.userId)
            put("plain", buildJsonObject { plain.forEach { (k, v) -> put(k, v) } })
            put("updated_at", kotlinx.datetime.Clock.System.now().toString())
        })
    }

    suspend fun getSettings(session: CloudSession): Pair<Map<String, String>, Instant?>? {
        val text = check(http.get("$base/rest/v1/settings") { auth(session); parameter("select", "plain,updated_at"); parameter("limit", 1) }, "Einstellungen lesen")
        val row = json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject ?: return null
        val plain = (row["plain"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
        val updated = row["updated_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return plain to updated
    }

    // ---------- Auftraege ----------

    suspend fun addCommand(session: CloudSession, kind: String, payload: JsonObject = JsonObject(emptyMap())) {
        val res = http.post("$base/rest/v1/commands") {
            auth(session)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("kind", kind); put("payload", payload) }.toString())
        }
        check(res, "Auftrag anlegen")
    }

    suspend fun openCommands(session: CloudSession): List<CloudCommand> {
        val text = check(http.get("$base/rest/v1/commands") {
            auth(session); parameter("select", "id,kind,payload,created_at"); parameter("done_at", "is.null"); parameter("order", "created_at.asc"); parameter("limit", 20)
        }, "Auftraege lesen")
        return json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            CloudCommand(
                id = o["id"]!!.jsonPrimitive.long, kind = o["kind"]!!.jsonPrimitive.content,
                payload = o["payload"] as? JsonObject ?: JsonObject(emptyMap()), createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    suspend fun finishCommand(session: CloudSession, id: Long, result: String) {
        val res = http.patch("$base/rest/v1/commands?id=eq.$id") {
            auth(session)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("done_at", kotlinx.datetime.Clock.System.now().toString()); put("result", result.take(500)) }.toString())
        }
        check(res, "Auftrag abschliessen")
    }

    /** Letzte erledigte Auftraege, neueste zuerst, fuer die Rueckmeldung in der Anzeige. */
    suspend fun recentCommands(session: CloudSession, limit: Int = 5): List<Pair<CloudCommand, String?>> {
        val text = check(http.get("$base/rest/v1/commands") {
            auth(session); parameter("select", "id,kind,payload,created_at,result,done_at"); parameter("order", "created_at.desc"); parameter("limit", limit)
        }, "Auftraege lesen")
        return json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            CloudCommand(o["id"]!!.jsonPrimitive.long, o["kind"]!!.jsonPrimitive.content, o["payload"] as? JsonObject ?: JsonObject(emptyMap()), o["created_at"]?.jsonPrimitive?.contentOrNull ?: "") to
                (o["result"] as? JsonPrimitive)?.contentOrNull
        }
    }

    // ---------- Hinweise ----------

    suspend fun addAlerts(session: CloudSession, alerts: List<CloudAlert>) {
        if (alerts.isEmpty()) return
        val body = buildJsonArray {
            alerts.forEach { a -> add(buildJsonObject { put("kind", a.kind); put("title", a.title); put("body", a.body); put("offer_charge", a.offerCharge) }) }
        }
        val res = http.post("$base/rest/v1/alerts") { auth(session); header("Prefer", "return=minimal"); contentType(ContentType.Application.Json); setBody(body.toString()) }
        check(res, "Hinweise schreiben")
    }

    suspend fun openAlerts(session: CloudSession): List<CloudAlert> {
        val text = check(http.get("$base/rest/v1/alerts") {
            auth(session); parameter("select", "id,kind,title,body,offer_charge"); parameter("delivered_at", "is.null"); parameter("order", "created_at.asc"); parameter("limit", 20)
        }, "Hinweise lesen")
        return json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            CloudAlert(o["id"]!!.jsonPrimitive.long, o["kind"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["body"]!!.jsonPrimitive.content, o["offer_charge"]?.jsonPrimitive?.content == "true")
        }
    }

    suspend fun markDelivered(session: CloudSession, ids: List<Long>) {
        if (ids.isEmpty()) return
        val res = http.patch("$base/rest/v1/alerts?id=in.(${ids.joinToString(",")})") {
            auth(session); header("Prefer", "return=minimal"); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("delivered_at", kotlinx.datetime.Clock.System.now().toString()) }.toString())
        }
        check(res, "Hinweise bestaetigen")
    }

    // ---------- Hilfen ----------

    private suspend fun upsertRow(session: CloudSession, table: String, row: JsonObject) {
        val res = http.post("$base/rest/v1/$table") {
            auth(session)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(row.toString())
        }
        check(res, "$table schreiben")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(session: CloudSession) {
        header("apikey", anonKey)
        header("Authorization", "Bearer ${session.accessToken}")
    }

    private suspend fun check(res: HttpResponse, what: String): String {
        val text = res.bodyAsText()
        if (res.status.value !in 200..299) throw CloudException("$what: HTTP ${res.status.value} ${errorText(text)}", res.status.value)
        return text
    }

    private fun errorText(text: String): String = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        (o["msg"] ?: o["message"] ?: o["error_description"] ?: o["error"])?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: text.take(200)
}

class CloudException(message: String, val status: Int = 0) : Exception(message) {
    val unauthorized: Boolean get() = status == 401
}
