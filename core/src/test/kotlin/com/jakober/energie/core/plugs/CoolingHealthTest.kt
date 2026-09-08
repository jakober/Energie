package com.jakober.energie.core.plugs

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CoolingHealthTest {
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")
    private val fridge = PlugDevice("f1", "Kühlschrank", "10.0.0.5", type = PlugType.COOLING, ratedPowerW = 100.0, labelKwhPerYear = 200.0)

    /** Ein Tag Minutenwerte: Kompressor `onMin` an, `offMin` aus, immer im Wechsel. */
    private fun day(onMin: Int, offMin: Int, powerW: Double = 90.0): List<EnergySample> {
        var wh = 0.0
        return (0 until 1440).map { m ->
            val on = (m % (onMin + offMin)) < onMin
            val p = if (on) powerW else 1.0
            wh += p / 60
            EnergySample(at = t0 + m.minutes, plugs = mapOf("f1" to PlugReading(p, wh, true)))
        }
    }

    @Test
    fun `laufzeit und zyklen aus der leistungsfolge`() {
        val t = PlugEnergy.of(day(15, 30))["f1"]!!
        // 45-min-Zyklus: 32 Starts am Tag, der erste um 0:00 zaehlt nicht (Vorgeschichte unbekannt).
        assertEquals(31, t.cycles)
        assertEquals(1.0 / 3, t.dutyShare!!, 0.01)
        assertEquals(15.0, t.longestRunMinutes, 0.01)
        assertTrue(t.measuredMinutes > 1400)
    }

    private fun totals(onMin: Int, offMin: Int, powerW: Double = 90.0) = PlugEnergy.of(day(onMin, offMin, powerW))["f1"]!!

    @Test
    fun `lernphase ohne genug tage`() {
        val days = (1..4).map { LocalDate(2026, 9, it) to totals(15, 30) }
        val r = CoolingHealth.of(fridge, days)
        assertEquals(CoolingVerdict.LEARNING, r.verdict)
        assertNull(r.baselineKwhPerDay)
        assertTrue(r.summary.startsWith("lernt noch"))
        // Herstellerwert wird trotzdem schon verglichen.
        assertNotNull(r.labelPercent)
    }

    @Test
    fun `normalbetrieb bleibt normal`() {
        val days = (1..12).map { LocalDate(2026, 9, it) to totals(15, 30) }
        val r = CoolingHealth.of(fridge, days)
        assertEquals(CoolingVerdict.OK, r.verdict)
        assertEquals(0, r.deviationPercent)
        assertNull(r.warning)
    }

    @Test
    fun `vereist - laeuft laenger und braucht mehr`() {
        val normal = (1..10).map { LocalDate(2026, 9, it) to totals(15, 30) }
        val iced = (11..13).map { LocalDate(2026, 9, it) to totals(25, 20) } // 55 % statt 33 %
        val r = CoolingHealth.of(fridge, normal + iced)
        assertEquals(CoolingVerdict.HIGH, r.verdict)
        assertTrue(r.deviationPercent!! >= 30)
        assertTrue(r.warning!!.contains("vereist"))
    }

    @Test
    fun `ein einzelner heisser tag loest nichts aus`() {
        val normal = (1..12).map { LocalDate(2026, 9, it) to totals(15, 30) }
        val hot = listOf(LocalDate(2026, 9, 13) to totals(30, 15))
        val r = CoolingHealth.of(fridge, normal + hot)
        // Median der letzten drei Tage: zwei normale, ein heisser -> normal.
        assertEquals(CoolingVerdict.OK, r.verdict)
    }

    @Test
    fun `ueber dem herstellerwert auch ohne eigenen normalwert`() {
        // 200 kWh/Jahr = 0,55 kWh/Tag; 30 min an je Stunde mit 150 W = 1,8 kWh/Tag.
        val days = (1..3).map { LocalDate(2026, 9, it) to totals(30, 30, 150.0) }
        val r = CoolingHealth.of(fridge, days)
        assertEquals(CoolingVerdict.HIGH, r.verdict)
        assertTrue(r.labelPercent!! > 150)
        assertTrue(r.warning!!.contains("Herstellerwert"))
    }

    @Test
    fun `halbe tage zaehlen nicht`() {
        val days = (1..12).map { LocalDate(2026, 9, it) to totals(15, 30).copy(measuredMinutes = 300.0) }
        assertEquals(CoolingVerdict.LEARNING, CoolingHealth.of(fridge, days).verdict)
    }
}
