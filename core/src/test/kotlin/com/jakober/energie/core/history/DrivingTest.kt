package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class DrivingTest {
    private val t0 = Instant.parse("2026-09-05T06:00:00Z")
    private val utc = TimeZone.UTC

    private fun s(min: Int, km: Double?, kwh: Double?, car: Double? = null, cons: Double = 500.0, grid: Double = 0.0) = EnergySample(
        at = t0 + min.minutes, consumptionW = cons, meterGridPowerW = grid, carChargePowerW = car, carOdometerKm = km, carEnergyKwh = kwh,
    )

    @Test
    fun `fahren entnimmt aus dem Tank, Anfangsinhalt ist unbekannt`() {
        val (days, state) = Driving.of(listOf(s(0, 1000.0, 40.0), s(30, 1020.0, 36.0), s(60, 1020.0, 36.0)), zone = utc)
        val d = days.single()
        assertEquals(LocalDate(2026, 9, 5), d.date)
        assertEquals(20.0, d.drivenKm, 1e-9)
        assertEquals(4000.0, d.usedWh, 1e-6)
        assertEquals(1.0, d.unknownShare!!, 1e-9)
        assertEquals(20.0, d.kwhPer100Km!!, 1e-9)
        assertEquals(36000.0, state.mix.totalWh, 1e-6)
        assertEquals(0.0, d.costEur(0.32, 0.59), 1e-9)
    }

    @Test
    fun `zu Hause laden fuellt Sonne und Netz nach dem Mix des Moments`() {
        // Leerer Akku, dann 10 kWh Laden bei 25 % Netzanteil, dann 4 kWh fahren.
        val samples = listOf(
            s(0, 1000.0, 0.0, cons = 2000.0, grid = 500.0),
            s(60, 1000.0, 10.0, car = 2000.0, cons = 2000.0, grid = 500.0),
            s(120, 1010.0, 6.0),
        )
        val (days, state) = Driving.of(samples, zone = utc)
        val d = days.single()
        assertEquals(10000.0, d.chargedHomeWh, 1e-6)
        assertEquals(4000.0, d.usedWh, 1e-6)
        assertEquals(0.75, d.solarShare!!, 1e-9)
        // 1 kWh Netz zu 0,32 € = 0,32 €; Sonnenwert 3 kWh * 0,08 = 0,24 €
        assertEquals(0.32, d.costEur(0.32, 0.59), 1e-9)
        assertEquals(0.24, d.solarValueEur(0.08), 1e-9)
        assertEquals(3.2, d.costPer100Km(0.32, 0.59)!!, 1e-9)
        assertEquals(6000.0, state.mix.totalWh, 1e-6)
        assertEquals(1500.0, state.mix.gridWh, 1e-6)
    }

    @Test
    fun `unterwegs laden ist Fremdstrom und der Zustand traegt ueber Tage`() {
        val day1 = listOf(s(0, 1000.0, 10.0), s(60, 1100.0, 0.0))
        val (d1, st) = Driving.of(day1, zone = utc)
        assertEquals(100.0, d1.single().drivenKm, 1e-9)
        val day2 = listOf(s(24 * 60, 1100.0, 30.0), s(24 * 60 + 60, 1150.0, 20.0))
        val (d2, _) = Driving.of(day2, st, zone = utc)
        val d = d2.single()
        assertEquals(LocalDate(2026, 9, 6), d.date)
        assertEquals(30000.0, d.chargedPublicWh, 1e-6)
        assertEquals(50.0, d.drivenKm, 1e-9)
        assertEquals(10000.0, d.used.publicWh, 1e-6)
        assertEquals(5.9, d.costEur(0.32, 0.59), 1e-9)
    }

    @Test
    fun `rauschen unter der Schwelle wird gesammelt, Kilometerspruenge verworfen`() {
        val samples = listOf(s(0, 1000.0, 40.0), s(5, 1000.0, 39.95), s(10, 1000.0, 39.9), s(15, 5000.0, 39.85), s(20, 5001.0, 39.85))
        val (days, state) = Driving.of(samples, zone = utc)
        val d = days.single()
        // Zwei Schritte je 50 Wh sammeln sich zu 100 Wh und werden dann gebucht; die letzten 50 Wh warten noch.
        assertEquals(100.0, d.usedWh, 1e-6)
        assertEquals(-50.0, state.pendingWh, 1e-6)
        // Der Sprung um 4000 km zaehlt nicht, der Kilometer danach schon.
        assertEquals(1.0, d.drivenKm, 1e-9)
        assertEquals(10.0, d.kwhPer100Km!!, 1e-9)
    }

    @Test
    fun `summe ueber Tage`() {
        val a = DriveDay(LocalDate(2026, 9, 5), 1000.0, 1050.0, 50.0, BatteryMix(solarWh = 6000.0, gridWh = 2000.0), 0.0, 0.0)
        val b = DriveDay(LocalDate(2026, 9, 6), 1050.0, 1070.0, 20.0, BatteryMix(solarWh = 1000.0, gridWh = 3000.0), 4000.0, 0.0)
        val s = DriveDay.sum(listOf(b, a))!!
        assertEquals(70.0, s.drivenKm, 1e-9)
        assertEquals(1000.0, s.startKm)
        assertEquals(1070.0, s.endKm)
        assertEquals(12000.0, s.usedWh, 1e-6)
        assertEquals(7000.0 / 12000.0, s.solarShare!!, 1e-9)
    }
}
