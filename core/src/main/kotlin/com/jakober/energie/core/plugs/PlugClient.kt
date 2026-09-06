package com.jakober.energie.core.plugs

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Liest Messstecker im Heimnetz ohne Cloud: Shelly Gen2/Gen3 ueber die
 * RPC-Schnittstelle, Tasmota ueber das cmnd-Interface. Beide liefern
 * Momentanleistung und einen Energiezaehler.
 */
class PlugClient(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(device: PlugDevice): PlugReading = withTimeout(TIMEOUT_MS) {
        when (device.kind) {
            PlugKind.SHELLY -> readShelly(device.host)
            PlugKind.TASMOTA -> readTasmota(device.host)
        }
    }

    suspend fun readShelly(host: String): PlugReading {
        val text = http.get("http://${host.trim()}/rpc/Switch.GetStatus?id=0").bodyAsText()
        return parseShellyStatus(text) ?: throw IllegalStateException("Keine Shelly-Antwort von $host: ${text.take(120)}")
    }

    suspend fun readTasmota(host: String): PlugReading {
        val text = http.get("http://${host.trim()}/cm?cmnd=Status%208").bodyAsText()
        return parseTasmotaStatus(text) ?: throw IllegalStateException("Keine Tasmota-Antwort von $host: ${text.take(120)}")
    }

    /** Geraetekennung und der in der Shelly-App vergebene Name. */
    suspend fun shellyInfo(host: String): ShellyInfo = withTimeout(TIMEOUT_MS) {
        val text = http.get("http://${host.trim()}/rpc/Shelly.GetDeviceInfo").bodyAsText()
        val o = json.parseToJsonElement(text).jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("Kein Shelly unter $host: ${text.take(120)}")
        ShellyInfo(id = id, name = o["name"]?.jsonPrimitive?.contentOrNull, model = o["model"]?.jsonPrimitive?.contentOrNull)
    }

    fun parseShellyStatus(text: String): PlugReading? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val power = (o["apower"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val total = ((o["aenergy"] as? JsonObject)?.get("total") as? JsonPrimitive)?.doubleOrNull
        return PlugReading(powerW = power, energyWh = total, on = (o["output"] as? JsonPrimitive)?.booleanOrNull)
    }

    fun parseTasmotaStatus(text: String): PlugReading? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val energy = (o["StatusSNS"] as? JsonObject)?.get("ENERGY") as? JsonObject ?: return null
        val power = (energy["Power"] as? JsonPrimitive)?.doubleOrNull ?: return null
        // Tasmota zaehlt in kWh.
        val total = (energy["Total"] as? JsonPrimitive)?.doubleOrNull?.let { it * 1000.0 }
        return PlugReading(powerW = power, energyWh = total)
    }

    companion object {
        /** Stecker antworten im Heimnetz in Millisekunden; von unterwegs soll die Abfrage schnell aufgeben. */
        const val TIMEOUT_MS = 4_000L
    }
}
