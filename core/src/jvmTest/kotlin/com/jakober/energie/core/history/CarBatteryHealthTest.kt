package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class CarBatteryHealthTest {
    private val t0 = Instant.parse("2026-08-03T00:00:00Z") // Montag

    private fun day(d: Int, capKwh: Double, socs: List<Double> = listOf(40.0, 55.0, 70.0, 85.0)) =
        LocalDate(2026, 8, 3 + d) to socs.mapIndexed { i, soc ->
            EnergySample(at = t0 + (d * 24 + i * 3).hours, carSocPercent = soc, carEnergyKwh = capKwh * soc / 100)
        }

    @Test
    fun `tageswert ist restenergie durch ladestand`() {
        val (date, samples) = day(0, 85.5)
        val p = CarBatteryHealth.dayPoint(date, samples)!!
        assertEquals(85.5, p.capacityKwh, 1e-6)
        assertEquals(4, p.samples)
    }

    @Test
    fun `niedriger ladestand und zu wenige punkte zaehlen nicht`() {
        assertNull(CarBatteryHealth.dayPoint(LocalDate(2026, 8, 3), day(0, 85.0, listOf(10.0, 15.0, 19.0, 50.0)).second))
        assertNull(CarBatteryHealth.dayPoint(LocalDate(2026, 8, 3), day(0, 85.0, listOf(50.0, 60.0)).second))
    }

    @Test
    fun `wochenwerte, aktueller wert und gesundheit gegen bezug`() {
        // Drei Wochen: 88, 87, 86 kWh, dazu ein Ausreisser.
        val points = (0 until 21).mapNotNull { d ->
            val cap = when { d < 7 -> 88.0; d < 14 -> 87.0; else -> 86.0 } + (if (d == 10) 5.0 else 0.0)
            CarBatteryHealth.dayPoint(day(d, cap).first, day(d, cap).second)
        }
        val h = CarBatteryHealth.of(points, nominalKwh = null)
        assertEquals(3, h.weekly.size)
        assertEquals(88.0, h.referenceKwh!!, 1e-6) // hoechste Woche
        assertEquals(86.5, h.currentKwh!!, 1e-6)   // letzte 14 Tage: 7x86, 6x87, 1x92 -> Median (86+87)/2
        assertEquals(98, h.healthPercent)
        val withNominal = CarBatteryHealth.of(points, nominalKwh = 91.0)
        assertEquals(95, withNominal.healthPercent) // 86,5 / 91
        assertEquals(true, withNominal.referenceFromSetting)
    }
}
