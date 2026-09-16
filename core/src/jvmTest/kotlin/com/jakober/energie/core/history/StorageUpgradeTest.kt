package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class StorageUpgradeTest {
    private val t0 = Instant.parse("2026-08-01T00:00:00Z")

    /** Ein Sommertag: mittags Speicher voll und 3 kW Einspeisung, abends Speicher leer und 1 kW Bezug. */
    private fun summerDay(offsetDays: Int = 0): Pair<LocalDate, List<EnergySample>> {
        val base = t0 + (offsetDays * 1440).minutes
        val samples = (0 until 1440).map { m ->
            val h = m / 60.0
            val (soc, grid, batt) = when {
                h in 11.0..15.0 -> Triple(100.0, -3000.0, 0.0)          // voll, 3 kW ins Netz
                h in 8.0..11.0 -> Triple(50.0 + (h - 8) * 16, -500.0, 2000.0) // laedt
                h in 19.0..23.0 -> Triple(5.0, 1000.0, 0.0)             // leer, 1 kW Bezug
                h >= 23.0 || h < 8.0 -> Triple(5.0, 400.0, 0.0)         // nachts leer
                else -> Triple(90.0, -200.0, 0.0)
            }
            EnergySample(at = base + m.minutes, batterySocPercent = soc, batteryPowerW = batt, meterGridPowerW = grid)
        }
        return LocalDate(2026, 8, 1 + offsetDays) to samples
    }

    @Test
    fun `sommertag - modul laedt mittags und liefert abends`() {
        val r = StorageUpgrade.simulate(listOf(summerDay()), extraWh = 2500.0)
        val d = r.days.single()
        assertTrue(d.wasFull && d.wasEmpty)
        // 4 h x 3 kW = 12 kWh bei 100 %, dazu 4 h x 200 W nachmittags: 90 % und Einspeisung ohne Ladung zaehlt als voll.
        assertEquals(12800.0, d.exportWhileFullWh, 200.0)
        // Nutzbar 2,25 kWh, davon nach Wirkungsgrad ~2,14 kWh entnommen
        assertEquals(2250 * 0.95, d.dischargedWh, 50.0)
        assertEquals(2250 / 0.95, d.chargedWh, 50.0)
        // 0,30 €/kWh Bezug statt 0,08 € Einspeisung
        assertEquals(2.14 * 0.22, r.savedEur(0.30, 0.08), 0.03)
    }

    @Test
    fun `wintertag - speicher nie voll, modul bringt nichts`() {
        val samples = (0 until 1440).map { m ->
            EnergySample(at = t0 + m.minutes, batterySocPercent = 40.0, batteryPowerW = 0.0, meterGridPowerW = 800.0)
        }
        val r = StorageUpgrade.simulate(listOf(LocalDate(2026, 12, 1) to samples), extraWh = 2500.0)
        assertEquals(0.0, r.dischargedWh)
        assertEquals(0, r.daysFull)
        assertTrue(r.paybackYears(1500.0, 0.30, 0.08) == null)
    }

    @Test
    fun `mehrere tage - ladung bleibt ueber nacht erhalten und hochrechnung`() {
        val r = StorageUpgrade.simulate(listOf(summerDay(0), summerDay(1)), extraWh = 2500.0)
        assertEquals(2, r.days.size)
        assertEquals(2 * 2250 * 0.95, r.dischargedWh, 100.0)
        val perYear = r.savedPerYearEur(0.30, 0.08)!!
        assertEquals(365 * 2.14 * 0.22, perYear, 5.0)
        assertEquals(1500 / perYear, r.paybackYears(1500.0, 0.30, 0.08)!!, 0.01)
    }
}
