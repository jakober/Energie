package com.jakober.energie.core.alerts

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AlertEngineTest {
    private val t0 = Instant.parse("2026-09-05T10:00:00Z")
    private val settings = AlertSettings()

    private fun input(
        now: Instant = t0,
        soc: Double? = 50.0, grid: Double? = 0.0,
        plugged: Boolean? = false, charging: Boolean? = false,
        lock: String? = "LOCKED", distance: Double? = 20.0,
        override: Boolean = false,
        senecOk: Instant? = now, fritzOk: Instant? = now,
        line: String? = null,
    ) = AlertInput(
        now = now, batterySocPercent = soc, gridPowerW = grid, carPluggedIn = plugged, carCharging = charging,
        carLockState = lock, carDistanceHomeM = distance, chargeOverride = override,
        senecConfigured = true, fritzConfigured = true, lastSenecOkAt = senecOk, lastFritzOkAt = fritzOk, automationLine = line,
    )

    @Test
    fun `nichts zu melden im Normalfall`() {
        val r = AlertEngine.evaluate(input(), AlertState(), settings)
        assertTrue(r.alerts.isEmpty())
        assertNull(r.state.unlockedSince)
    }

    @Test
    fun `auto unverschlossen zu Hause meldet nach Wartezeit genau einmal`() {
        var state = AlertState()
        var r = AlertEngine.evaluate(input(lock = "UNLOCKED"), state, settings)
        assertTrue(r.alerts.isEmpty(), "Erst merken, noch nicht melden")
        assertEquals(t0.epochSeconds, r.state.unlockedSince)
        state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 5.minutes, lock = "UNLOCKED"), state, settings)
        assertTrue(r.alerts.isEmpty(), "5 min sind unter der Schwelle")
        state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 10.minutes, lock = "UNLOCKED"), state, settings)
        assertEquals(listOf(AlertKind.CAR_UNLOCKED_HOME), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].text.contains("10 min"))
        state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 20.minutes, lock = "UNLOCKED"), state, settings)
        assertTrue(r.alerts.isEmpty(), "nicht wiederholen")
        state = r.state

        // Abgeschlossen: Zustand zurueck, naechstes Mal wieder melden.
        r = AlertEngine.evaluate(input(now = t0 + 30.minutes, lock = "LOCKED"), state, settings)
        assertNull(r.state.unlockedSince)
        assertEquals(false, r.state.unlockedReported)
    }

    @Test
    fun `unbekannter Schliesszustand aendert nichts`() {
        val state = AlertState(unlockedSince = t0.epochSeconds, unlockedReported = true)
        val r = AlertEngine.evaluate(input(now = t0 + 60.minutes, lock = null), state, settings)
        assertEquals(state, r.state)
        assertTrue(r.alerts.isEmpty())
    }

    @Test
    fun `unverschlossen unterwegs ist kein Thema`() {
        val r = AlertEngine.evaluate(input(now = t0 + 60.minutes, lock = "UNLOCKED", distance = 5000.0), AlertState(unlockedSince = t0.epochSeconds), settings)
        assertTrue(r.alerts.isEmpty())
        assertNull(r.state.unlockedSince)
    }

    @Test
    fun `ueberschuss ungenutzt mit Ladeknopf, hoechstens einmal je Stunde`() {
        var r = AlertEngine.evaluate(input(soc = 97.0, grid = -2100.0, plugged = true, charging = false), AlertState(), settings)
        assertEquals(listOf(AlertKind.SURPLUS_UNUSED), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].offerCharge)
        assertTrue(r.alerts[0].text.contains("2100 W"))
        val state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 30.minutes, soc = 97.0, grid = -2100.0, plugged = true, charging = false), state, settings)
        assertTrue(r.alerts.isEmpty())

        r = AlertEngine.evaluate(input(now = t0 + 61.minutes, soc = 97.0, grid = -2100.0, plugged = true, charging = false), state, settings)
        assertEquals(1, r.alerts.size)
    }

    @Test
    fun `kein Ueberschuss-Hinweis wenn Auto laedt, Handschalter an oder Speicher nicht voll`() {
        assertTrue(AlertEngine.evaluate(input(soc = 97.0, grid = -2100.0, plugged = true, charging = true), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(soc = 97.0, grid = -2100.0, plugged = true, charging = false, override = true), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(soc = 80.0, grid = -2100.0, plugged = true, charging = false), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(soc = 97.0, grid = -900.0, plugged = true, charging = false), AlertState(), settings).alerts.isEmpty())
    }

    @Test
    fun `automatik-zeile wird durchgereicht`() {
        val r = AlertEngine.evaluate(input(line = "10:00 Pausiert: Speicher 40 %"), AlertState(), settings)
        assertEquals(listOf(AlertKind.AUTOMATION_ACTED), r.alerts.map { it.kind })
        assertEquals("10:00 Pausiert: Speicher 40 %", r.alerts[0].text)
        assertTrue(AlertEngine.evaluate(input(line = "x"), AlertState(), settings.copy(automation = false)).alerts.isEmpty())
    }

    @Test
    fun `quelle ausgefallen einmal melden und Rueckkehr melden`() {
        var r = AlertEngine.evaluate(input(now = t0 + 61.minutes, senecOk = t0), AlertState(), settings)
        assertEquals(listOf(AlertKind.SOURCE_DOWN), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].title.contains("SENEC"))
        assertTrue(r.state.senecDownReported)

        r = AlertEngine.evaluate(input(now = t0 + 90.minutes, senecOk = t0), r.state, settings)
        assertTrue(r.alerts.isEmpty())

        r = AlertEngine.evaluate(input(now = t0 + 95.minutes, senecOk = t0 + 95.minutes), r.state, settings)
        assertEquals(listOf(AlertKind.SOURCE_BACK), r.alerts.map { it.kind })
        assertEquals(false, r.state.senecDownReported)
    }

    @Test
    fun `quelle die noch nie geantwortet hat wird nicht gemeldet`() {
        val r = AlertEngine.evaluate(input(now = t0 + 600.minutes, senecOk = null, fritzOk = null), AlertState(), settings)
        assertTrue(r.alerts.isEmpty())
    }
}
