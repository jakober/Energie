package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.io.path.createTempDirectory

class HistoryStoreTest {
    private val zone = TimeZone.of("Europe/Berlin")

    @Test
    fun schreibenLesenUndAufraeumen() {
        val dir = createTempDirectory("energie").toFile()
        val store = HistoryStore(dir, zone)
        val t0 = Instant.parse("2026-09-04T10:00:00Z")
        store.append(EnergySample(at = t0, productionW = 1000.0, consumptionW = 400.0, senecGridPowerW = -600.0, batteryPowerW = 0.0))
        store.append(EnergySample(at = t0 + kotlin.time.Duration.parse("1m"), productionW = 1000.0, consumptionW = 400.0, senecGridPowerW = -600.0, batteryPowerW = 0.0))
        store.append(EnergySample(at = Instant.parse("2026-09-03T20:30:00Z"), meterGridPowerW = 200.0, meterImportWh = 10L))

        assertEquals(listOf(LocalDate(2026, 9, 4), LocalDate(2026, 9, 3)), store.days())
        assertEquals(2, store.day(LocalDate(2026, 9, 4)).size)
        assertEquals(3, store.range(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-05T00:00:00Z")).size)
        assertEquals(t0 + kotlin.time.Duration.parse("1m"), assertNotNull(store.latest()).at)

        store.prune(LocalDate(2026, 9, 8), keepDays = 4)
        assertEquals(listOf(LocalDate(2026, 9, 4)), store.days())
    }

    @Test
    fun energieAusLeistungIntegriert() {
        val t0 = Instant.parse("2026-09-04T10:00:00Z")
        val samples = (0..6).map { i ->
            EnergySample(
                at = t0 + kotlin.time.Duration.parse("${i * 10}m"),
                productionW = 3000.0, consumptionW = 1000.0, senecGridPowerW = -1500.0, batteryPowerW = 500.0,
                meterImportWh = 100L + i, meterExportWh = 1000L + 250L * i,
            )
        }
        val t = EnergyTotals.of(samples)
        // 60 Minuten bei 3000 W = 3000 Wh
        assertEquals(3000.0, t.productionWh, 1e-6)
        assertEquals(1000.0, t.consumptionWh, 1e-6)
        assertEquals(0.0, t.gridImportWh, 1e-6)
        assertEquals(1500.0, t.gridExportWh, 1e-6)
        assertEquals(500.0, t.batteryChargeWh, 1e-6)
        assertEquals(1500.0, t.selfConsumptionWh, 1e-6)
        assertEquals(1.0, t.selfSufficiency!!, 1e-6)
        assertEquals(6L, t.meterImportWh)
        assertEquals(1500L, t.meterExportWh)
    }

    @Test
    fun autoladungNachQuelleAufgeteilt() {
        val t0 = Instant.parse("2026-09-04T10:00:00Z")
        // Eine Stunde: Haus 3000 W, davon Auto 2200 W; Netzbezug 1500 W -> Netzanteil 50 %
        val samples = (0..6).map { i ->
            EnergySample(
                at = t0 + kotlin.time.Duration.parse("${i * 10}m"),
                consumptionW = 3000.0, productionW = 1500.0, senecGridPowerW = 1500.0, carChargePowerW = 2200.0,
            )
        }
        val t = EnergyTotals.of(samples)
        assertEquals(2200.0, t.carChargeWh, 1e-6)
        assertEquals(1100.0, t.carFromGridWh, 1e-6)
        assertEquals(1100.0, t.carFromSolarWh, 1e-6)
        assertEquals(0.5, t.carSolarShare!!, 1e-9)
        assertEquals(1.1 * 0.30, t.carCostPaid(0.30), 1e-9)
        assertEquals(2.2 * 0.30, t.carCostIfGrid(0.30), 1e-9)
        assertEquals(1.1 * 0.30, t.carSaved(0.30), 1e-9)
        assertEquals(1.1 * 0.08, t.carForgoneFeedIn(0.08), 1e-9)
    }

    @Test
    fun grosseLueckenWerdenUebersprungen() {
        val t0 = Instant.parse("2026-09-04T10:00:00Z")
        val samples = listOf(
            EnergySample(at = t0, productionW = 1000.0),
            EnergySample(at = t0 + kotlin.time.Duration.parse("5h"), productionW = 1000.0),
        )
        assertEquals(0.0, EnergyTotals.of(samples).productionWh)
    }
}
