package com.jakober.energie.core.forecast

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PvForecastTest {
    private val body = """
        {"latitude":48.4,"longitude":10.1,
         "hourly":{"time":["2026-09-05T10:00","2026-09-05T11:00","2026-09-05T12:00","2026-09-06T11:00","2026-09-06T12:00"],
                   "global_tilted_irradiance":[400.0,600.0,800.0,200.0,null]},
         "daily":{"time":["2026-09-05","2026-09-06"],"weather_code":[1,61],"sunshine_duration":[36000.0,7200.0]}}
    """.trimIndent()

    @Test
    fun `stundenwerte werden je Tag summiert und mit Wetter verknuepft`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/v1/forecast", req.url.encodedPath)
            assertEquals("global_tilted_irradiance,shortwave_radiation", req.url.parameters["hourly"])
            assertEquals("30", req.url.parameters["tilt"])
            assertEquals("-10", req.url.parameters["azimuth"])
            respond(body)
        }
        val days = OpenMeteoClient(HttpClient(engine)).forecast(48.4, 10.1, tiltDeg = 30, azimuthDeg = -10)
        assertEquals(2, days.size)
        val today = days[0]
        assertEquals(LocalDate(2026, 9, 5), today.date)
        assertEquals(1800.0, today.irradianceWhPerM2)
        assertEquals(10.0, today.sunshineHours)
        assertEquals("meist sonnig", today.weatherLabel)
        assertEquals(200.0, days[1].irradianceWhPerM2)
        assertEquals("Regen", days[1].weatherLabel)
    }

    @Test
    fun `zwei Dachseiten werden je Tag zusammengefuehrt`() = runTest {
        val engine = MockEngine { req ->
            val az = req.url.parameters["azimuth"]
            if (az == "-93") respond(body) else respond(body.replace("[400.0,600.0,800.0,200.0,null]", "[200.0,300.0,400.0,100.0,null]"))
        }
        val days = OpenMeteoClient(HttpClient(engine)).forecastTwoSides(48.4, 10.1, 25, -93, 25, 87)
        val today = days[0]
        assertEquals(1800.0, today.irradianceWhPerM2)
        assertEquals(900.0, today.irradiance2WhPerM2)
        // Ost 4,86 kWp, West 4,05 kWp: (1,8*4,86 + 0,9*4,05) * 0,8 = 9,9144
        assertEquals(9.9144, today.energyKwhTwoSides(4.86, 4.05, calibration = 1.0), 1e-6)
        // Ohne zweite Seite muss dieselbe Zahl herauskommen wie bei der einfachen Rechnung.
        assertEquals(today.energyKwh(4.86), today.energyKwhTwoSides(4.86, 0.0, calibration = 1.0), 1e-9)
    }

    @Test
    fun `ohne geneigte Werte zaehlt die horizontale Einstrahlung`() {
        val text = """{"hourly":{"time":["2026-09-05T10:00","2026-09-05T11:00"],"global_tilted_irradiance":[null,null],"shortwave_radiation":[300.0,500.0]},"daily":{"time":["2026-09-05"],"weather_code":[0],"sunshine_duration":[3600.0]}}"""
        val days = OpenMeteoClient(HttpClient(MockEngine { respond("") })).parse(text)
        assertEquals(800.0, days.single().irradianceWhPerM2)
    }

    @Test
    fun `ertrag aus Einstrahlung, kWp und Kalibrierung`() {
        val d = PvForecastDay(LocalDate(2026, 9, 5), irradianceWhPerM2 = 5000.0)
        // 5 kWh/m² * 9,9 kWp * 0,8 = 39,6 kWh
        assertEquals(39.6, d.energyKwh(peakKw = 9.9), 1e-9)
        assertEquals(43.56, d.energyKwh(peakKw = 9.9, calibration = 1.1), 1e-9)
    }

    @Test
    fun `kalibrierung naehert sich dem echten Verhaeltnis und bleibt begrenzt`() {
        var c = 1.0
        repeat(10) { c = PvCalibration.update(c, actualKwh = 24.0, forecastKwhUncalibrated = 20.0) }
        assertTrue(c > 1.15 && c < 1.2, "$c")
        assertEquals(c, PvCalibration.update(c, actualKwh = 0.2, forecastKwhUncalibrated = 20.0), "zu wenig Ertrag: unveraendert")
        assertEquals(c, PvCalibration.update(c, actualKwh = 20.0, forecastKwhUncalibrated = 0.1), "zu kleine Prognose: unveraendert")
        var extreme = 1.0
        repeat(30) { extreme = PvCalibration.update(extreme, 100.0, 1.0) }
        assertTrue(extreme > 2.99, "$extreme")
    }

    @Test
    fun `gespeicherte Prognose findet den Tag`() {
        val f = PvForecast(0, listOf(PvForecastDay(LocalDate(2026, 9, 6), 100.0)))
        assertEquals(100.0, f.day(LocalDate(2026, 9, 6))?.irradianceWhPerM2)
        assertEquals(null, f.day(LocalDate(2026, 9, 7)))
    }
}
