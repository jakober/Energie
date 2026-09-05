package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ChargeSessionsTest {
    private val t0 = Instant.parse("2026-09-05T20:00:00Z")

    private fun s(min: Int, car: Double?, cons: Double = 2500.0, grid: Double = 0.0, soc: Double? = null) = EnergySample(
        at = t0 + min.minutes, consumptionW = cons, meterGridPowerW = grid, carChargePowerW = car, carSocPercent = soc, meterImportWh = 1000,
    )

    @Test
    fun `ein Vorgang mit Rampe auf null`() {
        val samples = listOf(
            s(0, null, cons = 300.0), s(15, 2200.0, soc = 40.0), s(30, 2200.0), s(45, 2200.0, soc = 45.0), s(60, null, cons = 300.0),
        )
        val list = ChargeSessions.of(samples)
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals(t0 + 15.minutes, c.start)
        assertEquals(t0 + 60.minutes, c.end)
        // 15..45: 2 Intervalle voll (2 * 2200 * 0.25 h) + Rampe 45..60 (1100 * 0.25 h) = 1100 + 275
        assertEquals(1375.0, c.energyWh, 1.0)
        assertEquals(40.0, c.socStart)
        assertEquals(45.0, c.socEnd)
        assertEquals(false, c.ongoing)
        assertEquals(45, c.durationMinutes)
    }

    @Test
    fun `kurze Pause gehoert zum selben Vorgang, lange Luecke trennt`() {
        // Ein verpasster 15-Minuten-Punkt (30 min zwischen zwei Ladepunkten) trennt nicht.
        val samples = listOf(
            s(0, 2200.0), s(15, 2200.0), s(30, null, cons = 300.0), s(45, 2200.0), s(60, 2200.0), s(75, null, cons = 300.0),
            // 2 Stunden nichts, dann neu
            s(200, 2200.0), s(215, 2200.0), s(230, null, cons = 300.0),
        )
        val list = ChargeSessions.of(samples)
        assertEquals(2, list.size)
        assertEquals(t0, list[0].start)
        assertEquals(t0 + 75.minutes, list[0].end)
        assertEquals(t0 + 200.minutes, list[1].start)
    }

    @Test
    fun `laufender Vorgang am Ende ist markiert`() {
        val list = ChargeSessions.of(listOf(s(0, null, cons = 300.0), s(15, 2200.0), s(30, 2200.0)))
        assertEquals(1, list.size)
        assertTrue(list[0].ongoing)
        assertEquals(550.0, list[0].energyWh, 1.0)
    }

    @Test
    fun `netzanteil folgt dem Hausmix`() {
        // Verbrauch 2500 W, davon 1250 W aus dem Netz: halbe Autoladung aus dem Netz.
        val list = ChargeSessions.of(
            listOf(s(0, 2200.0, grid = 1250.0), s(15, 2200.0, grid = 1250.0), s(30, 2200.0, grid = 1250.0), s(45, 2200.0, grid = 1250.0), s(60, 2200.0, grid = 1250.0), s(75, null, cons = 300.0, grid = 0.0)),
        )
        val c = list.single()
        assertEquals(2200.0 + 275.0, c.energyWh, 1.0)
        // Volle Intervalle: 2200 Wh, Mix 0,5 -> 1100 Wh Netz; Rampe: Mix (625/1400) * 275
        assertTrue(c.fromGridWh > 1100.0 && c.fromGridWh < 1250.0, "fromGrid=${c.fromGridWh}")
        assertNotNull(c.solarShare)
    }

    @Test
    fun `winzige Vorgaenge werden verworfen`() {
        val list = ChargeSessions.of(listOf(s(0, null), s(1, 200.0), s(2, null)))
        assertTrue(list.isEmpty())
    }

    @Test
    fun `leere Liste`() {
        assertTrue(ChargeSessions.of(emptyList()).isEmpty())
        assertNull(ChargeSessions.of(listOf(s(0, null))).firstOrNull())
    }
}
