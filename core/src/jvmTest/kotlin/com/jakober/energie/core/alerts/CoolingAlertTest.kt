package com.jakober.energie.core.alerts

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class CoolingAlertTest {
    private val t0 = Instant.parse("2026-09-05T10:00:00Z")
    private val settings = AlertSettings()

    private fun input(now: Instant, powerW: Double?, rated: Double? = 100.0, warnings: List<CoolingWarning> = emptyList()) = AlertInput(
        now = now, batterySocPercent = null, gridPowerW = null, carPluggedIn = null, carCharging = null,
        carLockState = null, carDistanceHomeM = null, chargeOverride = false,
        senecConfigured = false, fritzConfigured = false, lastSenecOkAt = null, lastFritzOkAt = null, automationLine = null,
        coolingPlugs = listOf(CoolingLive("f1", "Gefriertruhe", powerW, rated)), coolingWarnings = warnings,
    )

    @Test
    fun `dauerlauf nach drei stunden einmal melden`() {
        var s = AlertState()
        var r = AlertEngine.evaluate(input(t0, 90.0), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 2.hours, 90.0), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 3.hours + 1.minutes, 90.0), s, settings); s = r.state
        assertEquals(listOf(AlertKind.COOLING_STUCK_ON), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].text.contains("3 h 1 min"))
        // Weiter laufend: keine zweite Meldung.
        r = AlertEngine.evaluate(input(t0 + 4.hours, 90.0), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        // Pause setzt zurueck, naechster Dauerlauf meldet wieder.
        r = AlertEngine.evaluate(input(t0 + 5.hours, 1.0), s, settings); s = r.state
        assertTrue(s.plugOnSince.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 9.hours, 90.0), s, settings); s = r.state
        r = AlertEngine.evaluate(input(t0 + 12.hours + 5.minutes, 90.0), s, settings)
        assertEquals(listOf(AlertKind.COOLING_STUCK_ON), r.alerts.map { it.kind })
    }

    @Test
    fun `stillstand nach sechs stunden und wieder da`() {
        var s = AlertState()
        var r = AlertEngine.evaluate(input(t0, 90.0), s, settings); s = r.state
        r = AlertEngine.evaluate(input(t0 + 10.minutes, 1.0), s, settings); s = r.state
        r = AlertEngine.evaluate(input(t0 + 5.hours, 1.0), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 6.hours + 1.minutes, 1.0), s, settings); s = r.state
        assertEquals(listOf(AlertKind.COOLING_SILENT), r.alerts.map { it.kind })
        r = AlertEngine.evaluate(input(t0 + 7.hours, 1.0), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 8.hours, 90.0), s, settings); s = r.state
        assertEquals(listOf(AlertKind.COOLING_BACK), r.alerts.map { it.kind })
    }

    @Test
    fun `erstes sehen im stillstand zaehlt ab jetzt`() {
        // Kein Alarm, nur weil das Geraet beim ersten Durchlauf gerade aus ist.
        var s = AlertState()
        var r = AlertEngine.evaluate(input(t0, 1.0), s, settings); s = r.state
        r = AlertEngine.evaluate(input(t0 + 5.hours, 1.0), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 6.hours, 1.0), s, settings)
        assertEquals(listOf(AlertKind.COOLING_SILENT), r.alerts.map { it.kind })
    }

    @Test
    fun `abtauheizung ist kein ueberlast-alarm, eine stunde schon`() {
        var s = AlertState()
        var r = AlertEngine.evaluate(input(t0, 250.0), s, settings); s = r.state
        r = AlertEngine.evaluate(input(t0 + 25.minutes, 250.0), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 30.minutes, 90.0), s, settings); s = r.state
        assertTrue(s.plugHighSince.isEmpty())
        r = AlertEngine.evaluate(input(t0 + 1.hours, 250.0), s, settings); s = r.state
        r = AlertEngine.evaluate(input(t0 + 1.hours + 50.minutes, 250.0), s, settings)
        assertEquals(listOf(AlertKind.COOLING_OVERLOAD), r.alerts.map { it.kind })
    }

    @Test
    fun `trend hoechstens alle sieben tage, nicht erreichbar friert ein`() {
        val w = CoolingWarning("f1", "Gefriertruhe", "braucht 40 % mehr")
        var s = AlertState()
        var r = AlertEngine.evaluate(input(t0, 90.0, warnings = listOf(w)), s, settings); s = r.state
        assertEquals(listOf(AlertKind.COOLING_TREND), r.alerts.map { it.kind })
        r = AlertEngine.evaluate(input(t0 + 3.hours, 1.0, warnings = listOf(w)), s, settings); s = r.state
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(t0 + (8 * 24).hours, 90.0, warnings = listOf(w)), s, settings); s = r.state
        assertEquals(listOf(AlertKind.COOLING_TREND), r.alerts.map { it.kind })
        // Stecker nicht erreichbar: kein Stillstandsalarm daraus.
        r = AlertEngine.evaluate(input(t0 + (9 * 24).hours, null), s, settings)
        assertTrue(r.alerts.isEmpty())
    }

    @Test
    fun `abgeschaltet meldet nichts`() {
        val r = AlertEngine.evaluate(input(t0 + 10.hours, 90.0), AlertState(plugOnSince = mapOf("f1" to t0.epochSeconds)), settings.copy(cooling = false))
        assertTrue(r.alerts.isEmpty())
    }
}
