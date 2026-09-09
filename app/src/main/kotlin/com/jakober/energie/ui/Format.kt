package com.jakober.energie.ui

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Zahlen und Zeiten so, wie man sie in Deutschland liest. */
object Format {
    private val de = Locale.GERMANY
    private val zone: TimeZone get() = TimeZone.currentSystemDefault()
    private val dayLong = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", de)
    private val dayShort = DateTimeFormatter.ofPattern("EE, d. MMM", de)
    private val dayNum = DateTimeFormatter.ofPattern("d.M.", de)
    private val dayFull = DateTimeFormatter.ofPattern("dd.MM.yyyy", de)
    private val monthLong = DateTimeFormatter.ofPattern("MMMM yyyy", de)
    private val monthShortF = DateTimeFormatter.ofPattern("MMM", de)
    private val monthName = DateTimeFormatter.ofPattern("MMMM", de)

    /** 850 W, 1,25 kW, -3,4 kW */
    fun power(w: Double?, signed: Boolean = false): String {
        if (w == null) return "–"
        val sign = if (signed && w > 0) "+" else ""
        return if (abs(w) < 1000) "$sign${w.roundToInt()} W" else "$sign${String.format(de, "%.2f", w / 1000)} kW"
    }

    /** 0,8 kWh, 12,3 kWh, 1.234 kWh */
    fun energy(wh: Double?): String {
        if (wh == null) return "–"
        val kwh = wh / 1000
        return when {
            abs(kwh) < 10 -> String.format(de, "%.2f kWh", kwh)
            abs(kwh) < 100 -> String.format(de, "%.1f kWh", kwh)
            else -> String.format(de, "%,.0f kWh", kwh)
        }
    }

    fun energy(wh: Long?): String = energy(wh?.toDouble())

    /** Zaehlerstand mit drei Nachkommastellen: 12.345,678 kWh */
    fun meterReading(wh: Long?): String = if (wh == null) "–" else String.format(de, "%,.3f kWh", wh / 1000.0)

    fun percent(fraction: Double?): String = if (fraction == null) "–" else "${(fraction * 100).roundToInt()} %"

    fun percentValue(percent: Double?): String = if (percent == null) "–" else "${percent.roundToInt()} %"

    fun euro(amount: Double?): String = if (amount == null) "–" else String.format(de, "%.2f €", amount)

    fun time(at: Instant?): String {
        if (at == null) return "–"
        val t = at.toLocalDateTime(zone).time
        return String.format(de, "%02d:%02d", t.hour, t.minute)
    }

    /** "Fr, 4. Sep 23:12" */
    fun dateTime(at: Instant?): String {
        if (at == null) return "–"
        val l = at.toLocalDateTime(zone)
        return "${dayShort.format(l.date.toJavaLocalDate())} ${time(at)}"
    }

    fun dateLong(d: LocalDate): String = dayLong.format(d.toJavaLocalDate())
    fun dateShort(d: LocalDate): String = dayShort.format(d.toJavaLocalDate())
    fun dateNum(d: LocalDate): String = dayNum.format(d.toJavaLocalDate())
    fun month(d: LocalDate): String = monthLong.format(d.toJavaLocalDate())
    /** "Sep" */
    fun monthShort(d: LocalDate): String = monthShortF.format(d.toJavaLocalDate()).removeSuffix(".")
    /** "September" */
    fun monthName(d: LocalDate): String = monthName.format(d.toJavaLocalDate())

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
        val clock = String.format(de, "%02d:%02d:%02d", l.hour, l.minute, l.second)
        val day = when (l.date) {
            today -> "Heute,"
            today.minus(1, DateTimeUnit.DAY) -> "Gestern,"
            else -> dayFull.format(l.date.toJavaLocalDate())
        }
        return "$day $clock Uhr"
    }

    /** Zahl fuer ein Eingabefeld: ganzzahlig ohne Nachkommastellen, sonst mit Komma. */
    fun plain(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else String.format(de, "%.1f", v)

    fun hourLabel(h: Int): String = String.format(de, "%02d", h)

    /** "45 min", "2 h 10 min" */
    fun duration(minutes: Long): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"

    /** "2,3 Jahre" */
    fun years(y: Double?): String = if (y == null) "–" else String.format(de, "%.1f Jahre", y)
}
