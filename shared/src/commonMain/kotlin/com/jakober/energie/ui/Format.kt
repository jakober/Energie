package com.jakober.energie.ui

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Zahlen und Zeiten so, wie man sie in Deutschland liest. Ohne java.text und
 * java.time, damit dieselbe Datei auf Android und iOS laeuft.
 */
object Format {
    private val zone: TimeZone get() = TimeZone.currentSystemDefault()

    private val monthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
    private val monthShorts = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")
    private val dayNames = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val dayShorts = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    private fun Month.longName(): String = monthNames[ordinal]
    private fun Month.shortName(): String = monthShorts[ordinal]
    private fun DayOfWeek.longName(): String = dayNames[ordinal]
    private fun DayOfWeek.shortName(): String = dayShorts[ordinal]

    /**
     * Zahl mit fester Nachkommazahl, Komma als Dezimaltrenner und optional Punkt als
     * Tausendertrenner: 1234.5 -> "1.234,5". Entspricht String.format(Locale.GERMANY).
     */
    fun number(v: Double, decimals: Int, grouping: Boolean = false): String {
        // NaN und Unendlich duerfen nie zum Absturz fuehren (roundToLong wirft bei NaN).
        if (v.isNaN() || v.isInfinite()) return "–"
        val factor = 10.0.pow(decimals)
        val scaled = (abs(v) * factor).roundToLong()
        val intPart = scaled / factor.toLong()
        val frac = scaled % factor.toLong()
        val intText = if (grouping) group(intPart) else intPart.toString()
        val sign = if (v < 0 && scaled != 0L) "-" else ""
        return if (decimals == 0) sign + intText else sign + intText + "," + frac.toString().padStart(decimals, '0')
    }

    private fun group(n: Long): String {
        val s = n.toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun two(n: Int): String = n.toString().padStart(2, '0')

    /** 850 W, 1,25 kW, -3,4 kW */
    fun power(w: Double?, signed: Boolean = false): String {
        if (w == null || w.isNaN() || w.isInfinite()) return "–"
        val sign = if (signed && w > 0) "+" else ""
        return if (abs(w) < 1000) "$sign${w.roundToInt()} W" else "$sign${number(w / 1000, 2)} kW"
    }

    /** 0,8 kWh, 12,3 kWh, 1.234 kWh */
    fun energy(wh: Double?): String {
        if (wh == null || wh.isNaN() || wh.isInfinite()) return "–"
        val kwh = wh / 1000
        return when {
            abs(kwh) < 10 -> "${number(kwh, 2)} kWh"
            abs(kwh) < 100 -> "${number(kwh, 1)} kWh"
            else -> "${number(kwh, 0, grouping = true)} kWh"
        }
    }

    fun energy(wh: Long?): String = energy(wh?.toDouble())

    /** Zaehlerstand mit drei Nachkommastellen: 12.345,678 kWh */
    fun meterReading(wh: Long?): String = if (wh == null) "–" else "${number(wh / 1000.0, 3, grouping = true)} kWh"

    fun percent(fraction: Double?): String = if (fraction == null || fraction.isNaN() || fraction.isInfinite()) "–" else "${(fraction * 100).roundToInt()} %"

    fun percentValue(percent: Double?): String = if (percent == null || percent.isNaN() || percent.isInfinite()) "–" else "${percent.roundToInt()} %"

    fun euro(amount: Double?): String = if (amount == null) "–" else "${number(amount, 2)} €"

    fun time(at: Instant?): String {
        if (at == null) return "–"
        val t = at.toLocalDateTime(zone).time
        return "${two(t.hour)}:${two(t.minute)}"
    }

    /** "Fr, 4. Sep 23:12" */
    fun dateTime(at: Instant?): String {
        if (at == null) return "–"
        val l = at.toLocalDateTime(zone)
        return "${dateShort(l.date)} ${time(at)}"
    }

    /** "Freitag, 4. September 2026" */
    fun dateLong(d: LocalDate): String = "${d.dayOfWeek.longName()}, ${d.dayOfMonth}. ${d.month.longName()} ${d.year}"
    /** "Fr, 4. Sep" */
    fun dateShort(d: LocalDate): String = "${d.dayOfWeek.shortName()}, ${d.dayOfMonth}. ${d.month.shortName()}"
    /** "4.9." */
    fun dateNum(d: LocalDate): String = "${d.dayOfMonth}.${d.monthNumber}."
    /** "04.09.2026" */
    fun dateFull(d: LocalDate): String = "${two(d.dayOfMonth)}.${two(d.monthNumber)}.${d.year}"
    /** "September 2026" */
    fun month(d: LocalDate): String = "${d.month.longName()} ${d.year}"
    /** "Sep" */
    fun monthShort(d: LocalDate): String = d.month.shortName()
    /** "September" */
    fun monthName(d: LocalDate): String = d.month.longName()

    /** "vor 12 s", "vor 3 min", "vor 2 h" */
    fun ago(at: Instant?, now: Instant): String {
        if (at == null) return "noch nie"
        val s = (now - at).inWholeSeconds
        return when {
            s < 60 -> "vor $s s"
            s < 3600 -> "vor ${s / 60} min"
            s < 86_400 -> "vor ${s / 3600} h"
            else -> "vor ${s / 86_400} Tagen"
        }
    }

    /**
     * Genauer Zeitstempel eines Messpunkts: "Heute, 13:12:41 Uhr", "Gestern, 23:58:00 Uhr",
     * sonst "22.08.2026 12:24:03 Uhr".
     */
    fun stamp(at: Instant?, now: Instant): String {
        if (at == null) return "noch kein Stand"
        val l = at.toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        val clock = "${two(l.hour)}:${two(l.minute)}:${two(l.second)}"
        val day = when (l.date) {
            today -> "Heute,"
            today.minus(1, DateTimeUnit.DAY) -> "Gestern,"
            else -> dateFull(l.date)
        }
        return "$day $clock Uhr"
    }

    /** Zahl fuer ein Eingabefeld: ganzzahlig ohne Nachkommastellen, sonst mit Komma. */
    fun plain(v: Double): String = if (v.isNaN() || v.isInfinite()) "0" else if (v == v.roundToLong().toDouble()) v.toLong().toString() else number(v, 1)

    fun hourLabel(h: Int): String = two(h)

    /** "45 min", "2 h 10 min" */
    fun duration(minutes: Long): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"

    /** "2,3 Jahre" */
    fun years(y: Double?): String = if (y == null) "–" else "${number(y, 1)} Jahre"
}
