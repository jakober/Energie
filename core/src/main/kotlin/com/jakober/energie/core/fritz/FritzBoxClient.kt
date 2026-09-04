package com.jakober.energie.core.fritz

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class FritzBoxException(message: String) : Exception(message)

/**
 * Zugriff auf die Smart-Home-Schnittstelle (AHA-HTTP) einer FRITZ!Box im
 * Heimnetz. Meldet sich mit Benutzer und Passwort an, haelt die Sitzung und
 * meldet sich bei einer abgelaufenen Sitzung einmal neu an.
 *
 * Voraussetzung in der Box: Der Benutzer braucht das Recht "Smart Home".
 */
class FritzBoxClient(
    private val http: HttpClient,
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val clock: Clock = Clock.System,
) {
    private val base = baseUrl.trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
    private val lock = Mutex()
    @Volatile private var sid: String? = null

    /** Meldet sich an und liefert die Sitzungskennung. */
    suspend fun login(): String = lock.withLock { loginLocked() }

    private suspend fun loginLocked(): String {
        val first = FritzXml.parseSessionInfo(getText("$base/login_sid.lua", mapOf("version" to "2")))
        if (first.sid != NO_SID) return first.sid.also { sid = it }
        if (first.blockTimeSeconds > 0) {
            throw FritzBoxException("FRITZ!Box sperrt die Anmeldung noch ${first.blockTimeSeconds} s (zu viele Fehlversuche).")
        }
        val response = FritzChallenge.response(first.challenge, password)
        val second = FritzXml.parseSessionInfo(
            getText("$base/login_sid.lua", mapOf("version" to "2", "username" to username, "response" to response)),
        )
        if (second.sid == NO_SID) throw FritzBoxException("Anmeldung an der FRITZ!Box fehlgeschlagen: Benutzer oder Passwort falsch.")
        sid = second.sid
        return second.sid
    }

    suspend fun logout() {
        val s = sid ?: return
        runCatching { getText("$base/login_sid.lua", mapOf("version" to "2", "logout" to "1", "sid" to s)) }
        sid = null
    }

    suspend fun deviceList(): List<FritzDevice> = FritzXml.parseDeviceList(aha("getdevicelistinfos"))

    suspend fun deviceStats(ain: String): DeviceStats =
        FritzXml.parseDeviceStats(aha("getbasicdevicestats", ain.replace(" ", "")))

    /**
     * Sucht den Lesekopf am Stromzaehler (FRITZ!Smart Energy 250) und fasst
     * Bezug und Einspeisung zusammen. `null`, wenn kein Unterzaehler da ist.
     */
    suspend fun smartMeter(): SmartMeterReading? = smartMeter(deviceList())

    fun smartMeter(devices: List<FritzDevice>): SmartMeterReading? = summarizeSmartMeter(devices, clock.now())

    private suspend fun aha(cmd: String, ain: String? = null): String {
        val s = sid ?: login()
        val params = buildMap {
            put("switchcmd", cmd)
            put("sid", s)
            if (ain != null) put("ain", ain)
        }
        val url = "$base/webservices/homeautoswitch.lua"
        val response = http.get(url) { params.forEach { (k, v) -> parameter(k, v) } }
        if (response.status == HttpStatusCode.Forbidden) {
            // Sitzung abgelaufen: einmal neu anmelden und wiederholen.
            val fresh = lock.withLock { loginLocked() }
            val retry = http.get(url) { params.forEach { (k, v) -> parameter(k, if (k == "sid") fresh else v) } }
            if (!retry.status.isSuccess()) throw FritzBoxException("FRITZ!Box antwortet mit ${retry.status} auf $cmd")
            return retry.bodyAsText()
        }
        if (!response.status.isSuccess()) throw FritzBoxException("FRITZ!Box antwortet mit ${response.status} auf $cmd")
        return response.bodyAsText()
    }

    private suspend fun getText(url: String, params: Map<String, String>): String {
        val response = http.get(url) { params.forEach { (k, v) -> parameter(k, v) } }
        if (!response.status.isSuccess()) throw FritzBoxException("FRITZ!Box nicht erreichbar unter $base (${response.status})")
        return response.bodyAsText()
    }

    companion object {
        const val NO_SID = "0000000000000000"
    }
}

/**
 * Fasst die beiden Unterzaehler des Lesekopfs zu einem Messwert zusammen.
 * `null`, wenn kein Bezugszaehler in der Liste ist.
 */
fun summarizeSmartMeter(devices: List<FritzDevice>, at: kotlinx.datetime.Instant): SmartMeterReading? {
    val import = devices.firstOrNull { it.meterRole == MeterRole.GRID_IMPORT } ?: return null
    val export = devices.firstOrNull { it.meterRole == MeterRole.GRID_EXPORT }
    val importPower = import.powerMeter!!.powerWatt
    val exportPower = export?.powerMeter?.powerWatt ?: 0.0
    // Der Bezugszaehler zeigt bei Einspeisung 0 W (oder je nach Firmware einen negativen
    // Wert), der Einspeisezaehler dann die eingespeiste Leistung. Beides zu einem
    // vorzeichenbehafteten Wert zusammenfassen: positiv Bezug, negativ Einspeisung.
    val grid = when {
        importPower > 0 -> importPower
        exportPower > 0 -> -exportPower
        else -> importPower
    }
    return SmartMeterReading(
        at = at,
        gridPowerWatt = grid,
        importEnergyWh = import.powerMeter.energyWh,
        exportEnergyWh = export?.powerMeter?.energyWh,
        importAin = import.ainCompact,
        exportAin = export?.ainCompact,
    )
}
