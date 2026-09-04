package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Legt Messpunkte als eine JSON-Zeile pro Eintrag in einer Datei pro Tag ab
 * (`2026-09-04.jsonl`). Das ist bei einem Wert pro Minute rund 100 kB pro Tag,
 * laesst sich ohne Datenbank lesen und mit einem Dateimanager sichern.
 */
class HistoryStore(
    private val dir: File,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun append(sample: EnergySample) {
        dir.mkdirs()
        fileFor(sample.at).appendText(json.encodeToString(sample) + "\n")
    }

    fun day(date: LocalDate): List<EnergySample> {
        val f = File(dir, "$date.jsonl")
        if (!f.exists()) return emptyList()
        return f.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { json.decodeFromString(EnergySample.serializer(), line) }.getOrNull() }
                .toList()
        }
    }

    /** Alle Messpunkte im Zeitraum [from, to), chronologisch. */
    fun range(from: Instant, to: Instant): List<EnergySample> {
        var d = from.toLocalDateTime(zone).date
        val last = to.toLocalDateTime(zone).date
        val out = ArrayList<EnergySample>()
        while (d <= last) {
            out += day(d).filter { it.at >= from && it.at < to }
            d = LocalDate.fromEpochDays(d.toEpochDays() + 1)
        }
        return out.sortedBy { it.at }
    }

    fun latest(): EnergySample? {
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedByDescending { it.name } ?: return null
        for (f in files) {
            val date = runCatching { LocalDate.parse(f.name.removeSuffix(".jsonl")) }.getOrNull() ?: continue
            day(date).lastOrNull()?.let { return it }
        }
        return null
    }

    /** Tage, fuer die Daten vorliegen, neueste zuerst. */
    fun days(): List<LocalDate> =
        dir.listFiles { f -> f.name.endsWith(".jsonl") }
            ?.mapNotNull { runCatching { LocalDate.parse(it.name.removeSuffix(".jsonl")) }.getOrNull() }
            ?.sortedDescending() ?: emptyList()

    /** Loescht Dateien, die aelter sind als `keepDays` Tage. */
    fun prune(today: LocalDate, keepDays: Int) {
        val cutoff = LocalDate.fromEpochDays(today.toEpochDays() - keepDays)
        days().filter { it < cutoff }.forEach { File(dir, "$it.jsonl").delete() }
    }

    private fun fileFor(at: Instant): File = File(dir, "${at.toLocalDateTime(zone).date}.jsonl")
}
