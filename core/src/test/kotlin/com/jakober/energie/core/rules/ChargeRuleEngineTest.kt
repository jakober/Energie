package com.jakober.energie.core.rules

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ChargeRuleEngineTest {
    private val rules = ChargeRules(enabled = true)
    private val t0 = Instant.parse("2026-09-05T10:00:00Z")

    private fun input(
        time: LocalTime = LocalTime(12, 0), soc: Double? = 80.0, grid: Double? = 0.0, carSoc: Double? = 60.0,
        plugged: Boolean? = true, charging: Boolean? = true, carPower: Double? = 2200.0,
        last: Instant? = null, override: Boolean = false,
    ) = ChargeInput(t0, time, soc, grid, carSoc, plugged, charging, carPower, last, override)

    @Test
    fun ausOderNichtAngeschlossen() {
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules.copy(enabled = false), input()).action)
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules, input(plugged = false)).action)
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules, input(charging = null)).action)
    }

    @Test
    fun speicherVollLaedtSpeicherLeerPausiert() {
        // Speicher 80 %, Auto pausiert -> fortsetzen
        assertEquals(ChargeAction.RESUME, ChargeRuleEngine.decide(rules, input(soc = 80.0, charging = false)).action)
        // Speicher 40 %, Auto laedt 2,2 kW und 1,5 kW kommen aus dem Netz -> nur 0,7 kW eigen -> pausieren
        val d = ChargeRuleEngine.decide(rules, input(soc = 40.0, grid = 1500.0, charging = true))
        assertEquals(ChargeAction.PAUSE, d.action)
        assertTrue(d.reason.contains("40 %"), d.reason)
        // Hysterese: 60 % ist zwischen aus (50) und ein (70) -> nichts aendern
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules, input(soc = 60.0, grid = 800.0, charging = true)).action)
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules, input(soc = 60.0, charging = false)).action)
        // Speicher 40 %, Auto laedt, aber 1,7 kW eigen (2,2 kW minus 0,5 kW Bezug): zwischen den Schwellen -> nichts
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules, input(soc = 40.0, grid = 500.0, charging = true)).action)
    }

    @Test
    fun ueberschussZaehltMitLadeleistung() {
        // Speicher 40 %, aber 2,2 kW gehen ins Auto und 300 W noch ins Netz -> 2,5 kW verfuegbar -> weiter laden
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules, input(soc = 40.0, grid = -300.0, charging = true, carPower = 2200.0)).action)
        // Auto pausiert, 2,5 kW Einspeisung -> fortsetzen wegen Ueberschuss
        val d = ChargeRuleEngine.decide(rules, input(soc = 40.0, grid = -2500.0, charging = false))
        assertEquals(ChargeAction.RESUME, d.action)
        assertTrue(d.reason.contains("Ueberschuss"), d.reason)
    }

    @Test
    fun nachtsperreUndReserve() {
        // 23:00, Auto laedt, Speicher voll -> trotzdem pausieren
        assertEquals(ChargeAction.PAUSE, ChargeRuleEngine.decide(rules, input(time = LocalTime(23, 0), soc = 90.0)).action)
        // 23:00, Auto unter Reserve -> laden trotz Nacht
        assertEquals(ChargeAction.RESUME, ChargeRuleEngine.decide(rules, input(time = LocalTime(23, 0), carSoc = 30.0, charging = false)).action)
        // 06:00 ist Ende der Sperre
        assertEquals(false, rules.isNight(LocalTime(6, 0)))
        assertEquals(true, rules.isNight(LocalTime(5, 59)))
        assertEquals(true, rules.isNight(LocalTime(22, 0)))
    }

    @Test
    fun handschalterUndWartezeit() {
        assertEquals(ChargeAction.RESUME, ChargeRuleEngine.decide(rules, input(soc = 10.0, charging = false, override = true)).action)
        assertEquals(ChargeAction.NONE, ChargeRuleEngine.decide(rules, input(soc = 10.0, charging = true, override = true)).action)
        // Letzter Befehl vor 5 Minuten -> warten
        val d = ChargeRuleEngine.decide(rules, input(soc = 80.0, charging = false, last = t0 - 5.minutes))
        assertEquals(ChargeAction.NONE, d.action)
        assertTrue(d.reason.contains("Wartezeit"), d.reason)
        assertEquals(ChargeAction.RESUME, ChargeRuleEngine.decide(rules, input(soc = 80.0, charging = false, last = t0 - 16.minutes)).action)
    }
}
