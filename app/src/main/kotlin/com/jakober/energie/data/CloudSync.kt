package com.jakober.energie.data

import com.jakober.energie.core.alerts.Alert
import com.jakober.energie.core.alerts.AlertKind
import com.jakober.energie.core.cloud.CloudAlert
import com.jakober.energie.core.cloud.CloudCommand
import com.jakober.energie.core.cloud.CloudException
import com.jakober.energie.core.cloud.CloudSession
import com.jakober.energie.core.cloud.SupabaseClient
import com.jakober.energie.core.history.HistoryStore
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.core.senec.SenecSystem
import com.jakober.energie.core.smartcar.CarState
import io.ktor.client.HttpClient
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.days

/** Was die Zentrale ueber den Moment in die Cloud schreibt und die Anzeige daraus liest. */
data class HubStatus(
    val car: CarState?,
    val senec: SenecSystem?,
    val automationStatus: String?,
    val senecError: String?,
    val fritzError: String?,
    val carError: String?,
    val plugErrors: Map<String, String>,
    val pvPeakEstimateKw: Double?,
    val lastUpdate: Instant?,
    val hubSeenAt: Instant?,
)

/**
 * Bindeglied zu Supabase. Haelt die Anmeldung frisch, laedt als Zentrale
 * Messpunkte, Status und Hinweise hoch und arbeitet Auftraege ab; holt als
 * Anzeige Messpunkte, Status und Hinweise und stellt Auftraege ein.
 */
class CloudSync(
    private val settings: AppSettings,
    private val http: HttpClient,
    private val history: HistoryStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var client: SupabaseClient? = null
    private var clientKey: String = ""
    private var lastSettingsHash: Int = 0

    private fun client(s: Settings): SupabaseClient {
        val key = s.cloudUrl + "|" + s.cloudAnonKey
        if (client == null || clientKey != key) { client = SupabaseClient(http, s.cloudUrl, s.cloudAnonKey); clientKey = key }
        return client!!
    }

    /** Gueltige Sitzung: gespeicherte, erneuerte oder neue Anmeldung. */
    suspend fun session(s: Settings, force: Boolean = false): CloudSession {
        if (!s.cloudConfigured) throw CloudException("Cloud nicht eingerichtet: URL, Schlüssel, E-Mail und Passwort fehlen.")
        val c = client(s)
        val stored = s.cloudSessionJson.takeIf { it.isNotBlank() && !force }?.let { runCatching { json.decodeFromString(CloudSession.serializer(), it) }.getOrNull() }
        val now = Clock.System.now()
        val fresh = when {
            stored == null -> c.signIn(s.cloudEmail, s.cloudPassword)
            stored.expiresSoon(now) -> runCatching { c.refresh(stored) }.getOrElse { c.signIn(s.cloudEmail, s.cloudPassword) }
            else -> return stored
        }
        settings.saveCloudSession(json.encodeToString(CloudSession.serializer(), fresh))
        return fresh
    }

    /** Fuehrt einen Aufruf aus; bei 401 einmal neu anmelden und wiederholen. */
    private suspend fun <T> withSession(s: Settings, block: suspend (SupabaseClient, CloudSession) -> T): T {
        val c = client(s)
        return try {
            block(c, session(s))
        } catch (e: CloudException) {
            if (!e.unauthorized) throw e
            block(c, session(s, force = true))
        }
    }

    suspend fun test(s: Settings): String = withSession(s) { c, sess ->
        val latest = c.latestSampleAt(sess)
        "Angemeldet als ${s.cloudEmail}. " + (latest?.let { "Neuester Messpunkt in der Cloud: $it" } ?: "Noch keine Messpunkte in der Cloud.")
    }

    // ---------------- Zentrale ----------------

    /** Messpunkte seit dem letzten Upload, in Paketen; hoechstens `maxBatches` je Aufruf. */
    suspend fun uploadPending(s: Settings, maxBatches: Int = 4): Int = withSession(s) { c, sess ->
        val now = Clock.System.now()
        val from = if (s.cloudUploadedAt > 0) Instant.fromEpochSeconds(s.cloudUploadedAt) else now - BACKFILL
        val pending = history.range(from + kotlin.time.Duration.parse("1ms"), now + kotlin.time.Duration.parse("1m"))
        var sent = 0
        pending.chunked(BATCH).take(maxBatches).forEach { batch ->
            c.upsertSamples(sess, batch)
            sent += batch.size
            settings.saveCloudUploadedAt(batch.last().at.epochSeconds)
        }
        sent
    }

    suspend fun putStatus(s: Settings, live: LiveState) = withSession(s) { c, sess ->
        val obj = buildJsonObject {
            live.car?.let { put("car", json.encodeToJsonElement(CarState.serializer(), it)) }
            live.senec?.let { put("senec", json.encodeToJsonElement(SenecSystem.serializer(), it)) }
            live.automationStatus?.let { put("automationStatus", it) }
            live.senecError?.let { put("senecError", it) }
            live.fritzError?.let { put("fritzError", it) }
            live.carError?.let { put("carError", it) }
            put("plugErrors", buildJsonObject { live.plugErrors.forEach { (k, v) -> put(k, v) } })
            live.pvPeakEstimateKw?.let { put("pvPeakEstimateKw", it) }
            live.lastUpdate?.let { put("lastUpdate", it.toString()) }
        }
        c.putStatus(sess, obj)
    }

    /** Einstellungen ohne Geheimnisse hochladen, wenn sie sich geaendert haben. */
    suspend fun uploadSettingsIfChanged(s: Settings) {
        val plain = settings.plainForBackup(s).filterKeys { it !in CLOUD_KEYS }
        val h = plain.hashCode()
        if (h == lastSettingsHash) return
        withSession(s) { c, sess -> c.putSettings(sess, plain) }
        lastSettingsHash = h
    }

    suspend fun pushAlerts(s: Settings, alerts: List<Alert>) = withSession(s) { c, sess ->
        c.addAlerts(sess, alerts.map { CloudAlert(0, it.kind.name, it.title, it.text, it.offerCharge) })
    }

    /** Offene Auftraege holen, ausfuehren, abschliessen. */
    suspend fun processCommands(s: Settings, execute: suspend (CloudCommand) -> String): Int = withSession(s) { c, sess ->
        val open = c.openCommands(sess)
        open.forEach { cmd ->
            val result = runCatching { execute(cmd) }.getOrElse { "Fehler: ${it.message ?: it}" }
            c.finishCommand(sess, cmd.id, result)
        }
        open.size
    }

    // ---------------- Anzeige ----------------

    /** Neue Messpunkte holen und in den Verlauf mischen. */
    suspend fun pullSamples(s: Settings, maxPages: Int = 6): Int = withSession(s) { c, sess ->
        var after = if (s.cloudSyncedAt > 0) Instant.fromEpochSeconds(s.cloudSyncedAt) else (history.latest()?.at ?: (Clock.System.now() - BACKFILL))
        var total = 0
        repeat(maxPages) {
            val page = c.samplesAfter(sess, after, PAGE)
            if (page.isEmpty()) return@withSession total
            total += history.merge(page)
            after = page.last().at
            settings.saveCloudSyncedAt(after.epochSeconds)
            if (page.size < PAGE) return@withSession total
        }
        total
    }

    suspend fun pullStatus(s: Settings): HubStatus? = withSession(s) { c, sess ->
        val (live, seen) = c.getStatus(sess) ?: return@withSession null
        fun str(k: String) = (live[k] as? JsonPrimitive)?.contentOrNull
        HubStatus(
            car = live["car"]?.let { runCatching { json.decodeFromJsonElement(CarState.serializer(), it) }.getOrNull() },
            senec = live["senec"]?.let { runCatching { json.decodeFromJsonElement(SenecSystem.serializer(), it) }.getOrNull() },
            automationStatus = str("automationStatus"),
            senecError = str("senecError"),
            fritzError = str("fritzError"),
            carError = str("carError"),
            plugErrors = (live["plugErrors"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap(),
            pvPeakEstimateKw = (live["pvPeakEstimateKw"] as? JsonPrimitive)?.doubleOrNull,
            lastUpdate = str("lastUpdate")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            hubSeenAt = seen,
        )
    }

    /** Hinweise der Zentrale holen und als abgeholt markieren. */
    suspend fun pullAlerts(s: Settings): List<Alert> = withSession(s) { c, sess ->
        val open = c.openAlerts(sess)
        if (open.isEmpty()) return@withSession emptyList()
        c.markDelivered(sess, open.map { it.id })
        open.map { a -> Alert(runCatching { AlertKind.valueOf(a.kind) }.getOrDefault(AlertKind.AUTOMATION_ACTED), a.title, a.body, a.offerCharge) }
    }

    /** Einstellungen der Zentrale uebernehmen, wenn sie neuer sind als die zuletzt uebernommenen. */
    suspend fun pullSettings(s: Settings): Boolean = withSession(s) { c, sess ->
        val (plain, updated) = c.getSettings(sess) ?: return@withSession false
        val stamp = updated?.epochSeconds ?: return@withSession false
        if (stamp <= s.cloudSettingsAppliedAt) return@withSession false
        settings.restore(plain.filterKeys { it !in CLOUD_KEYS }, null)
        settings.saveCloudSettingsAppliedAt(stamp)
        true
    }

    /** Firebase-Token in der Cloud eintragen, wenn es neu ist. */
    suspend fun registerDeviceIfNeeded(s: Settings): Boolean {
        val token = s.pushToken
        if (token.isBlank() || token == s.pushRegisteredToken) return false
        withSession(s) { c, sess -> c.upsertDevice(sess, token, android.os.Build.MODEL ?: "Android") }
        settings.savePushRegistered(token)
        return true
    }

    /** Ein per Push zugestellter Hinweis soll beim Abholen nicht noch einmal kommen. */
    suspend fun markDelivered(s: Settings, id: Long) = withSession(s) { c, sess -> c.markDelivered(sess, listOf(id)) }

    suspend fun sendCommand(s: Settings, kind: String, payload: JsonObject = JsonObject(emptyMap())) = withSession(s) { c, sess -> c.addCommand(sess, kind, payload) }

    suspend fun recentCommands(s: Settings): List<Pair<CloudCommand, String?>> = withSession(s) { c, sess -> c.recentCommands(sess) }

    companion object {
        const val BATCH = 500
        const val PAGE = 2000
        /** Beim ersten Upload bzw. Abgleich: so weit zurueck. */
        val BACKFILL = 60.days
        /** Cloud-Zugang und Rolle bleiben je Geraet. */
        val CLOUD_KEYS = setOf("cloudUrl", "cloudAnonKey", "cloudEmail")

        /** Auftragsarten zwischen Anzeige und Zentrale. */
        const val CMD_FORD = "FORD"            // payload: {"command": "PAUSE"}
        const val CMD_OVERRIDE = "CHARGE_OVERRIDE" // payload: {"on": true}
        const val CMD_SETTINGS = "SET_SETTINGS"    // payload: {"plain": {...}}
        const val CMD_REFRESH = "REFRESH"

        fun payloadString(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull
        fun payloadBool(o: JsonObject, key: String): Boolean? = (o[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        fun payloadMap(o: JsonObject, key: String): Map<String, String> =
            (o[key] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
    }
}
