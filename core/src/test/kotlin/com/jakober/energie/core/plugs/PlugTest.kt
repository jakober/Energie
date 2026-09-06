package com.jakober.energie.core.plugs

import com.jakober.energie.core.model.EnergySample
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class PlugTest {
    private val t0 = Instant.parse("2026-09-07T10:00:00Z")

    @Test
    fun `shelly und tasmota antworten werden gelesen`() = runBlocking {
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath == "/rpc/Switch.GetStatus" -> respond("""{"id":0,"source":"init","output":true,"apower":84.6,"voltage":231.2,"current":0.41,"aenergy":{"total":12345.678,"by_minute":[1,2,3],"minute_ts":1}}""")
                req.url.encodedPath == "/rpc/Shelly.GetDeviceInfo" -> respond("""{"name":"Kühlschrank","id":"shellyplugmg3-abc123","model":"S3PL-10112EU","gen":3}""")
                req.url.encodedPath == "/cm" -> respond("""{"StatusSNS":{"Time":"2026-09-07T12:00:00","ENERGY":{"Total":3.456,"Power":12,"Voltage":230}}}""")
                else -> respond("nope")
            }
        }
        val client = PlugClient(HttpClient(engine))
        val shelly = client.read(PlugDevice("x", "Kühlschrank", "192.168.178.50"))
        assertEquals(84.6, shelly.powerW)
        assertEquals(12345.678, shelly.energyWh)
        assertEquals(true, shelly.on)
        val info = client.shellyInfo("192.168.178.50")
        assertEquals("shellyplugmg3-abc123", info.id)
        assertEquals("Kühlschrank", info.name)
        val tasmota = client.read(PlugDevice("y", "Truhe", "192.168.178.51", PlugKind.TASMOTA))
        assertEquals(12.0, tasmota.powerW)
        assertEquals(3456.0, tasmota.energyWh!!, 1e-9)
    }

    private fun s(min: Int, vararg plugs: Pair<String, PlugReading>) = EnergySample(at = t0 + min.minutes, plugs = plugs.toMap())

    @Test
    fun `zaehlerdifferenz mit Reset und Rueckfall auf Integration`() {
        val samples = listOf(
            s(0, "a" to PlugReading(50.0, 1000.0), "b" to PlugReading(100.0)),
            s(60, "a" to PlugReading(60.0, 1050.0), "b" to PlugReading(100.0)),
            s(120, "a" to PlugReading(70.0, 20.0), "b" to PlugReading(200.0)), // a: Zaehler auf 20 zurueckgesetzt
            s(180, "a" to PlugReading(40.0, 60.0)),
        )
        val prev = s(-30, "a" to PlugReading(50.0, 990.0))
        val r = PlugEnergy.of(samples, prev)
        // a: 10 (aus Vortag) + 50 + 20 (nach Reset ab null) + 40 = 120 Wh
        assertEquals(120.0, r["a"]!!.energyWh, 1e-9)
        assertTrue(r["a"]!!.fromCounter)
        assertEquals(70.0, r["a"]!!.maxPowerW)
        assertEquals(4, r["a"]!!.samples)
        // b ohne Zaehler: 100 W * 1 h + 150 W * 1 h = 250 Wh
        assertEquals(250.0, r["b"]!!.energyWh, 1e-9)
        assertTrue(!r["b"]!!.fromCounter)
    }
}
