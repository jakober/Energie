package com.jakober.energie.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.history.EnergyTotals
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.theme.EnergyColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** Eine Zeile der Liste: Messpunkt oder Luecke davor. */
private sealed class Row {
    data class Point(val s: EnergySample) : Row()
    data class Gap(val minutes: Long) : Row()
}

private fun rows(samples: List<EnergySample>, date: LocalDate): List<Row> {
    val out = ArrayList<Row>()
    val dayStart = date.atStartOfDayIn(TimeZone.currentSystemDefault())
    var prev: EnergySample? = null
    for (s in samples) {
        val gapSec = (s.at - (prev?.at ?: dayStart)).inWholeSeconds
        if (gapSec > EnergyTotals.MAX_GAP_SECONDS) out += Row.Gap(gapSec / 60)
        out += Row.Point(s)
        prev = s
    }
    return out
}

/**
 * Alle Messpunkte eines Tages, so wie sie im Verlauf liegen: eine Zeile je
 * Punkt mit Uhrzeit und den Rohwerten, Luecken ueber 90 Minuten dazwischen.
 * Zugeklappt, weil ein Tag im Vordergrund gut 1000 Punkte hat.
 */
fun LazyListScope.sampleItems(samples: List<EnergySample>, date: LocalDate, expanded: Boolean, onToggle: () -> Unit) {
    item {
        EnergieCard(title = "Messpunkte", accent = EnergyColors.neutral) {
            Text(
                "${samples.size} Messpunkte im Verlauf. ● im Vordergrund gemessen, ○ vom Hintergrund-Worker. " +
                    "Zähler = Bezug / Einspeisung laut Lesekopf, Netz = Leistung laut Lesekopf (Bezug +, Einspeisung −).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onToggle) { Text(if (expanded) "Liste ausblenden" else "Alle ${samples.size} anzeigen") }
        }
    }
    if (!expanded) return
    val list = rows(samples, date)
    items(list.size, key = { i -> (list[i] as? Row.Point)?.s?.at?.epochSeconds ?: -(i.toLong()) }) { i ->
        when (val r = list[i]) {
            is Row.Gap -> Text(
                "— Lücke ${Format.duration(r.minutes)} —",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            is Row.Point -> SampleRow(r.s)
        }
    }
}

@Composable
private fun SampleRow(s: EnergySample) {
    val mark = when (s.background) { true -> "○"; false -> "●"; null -> "·" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$mark ${Format.time(s.at)}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
            Text(
                listOfNotNull(
                    s.productionW?.let { "PV ${Format.power(it)}" },
                    s.consumptionW?.let { "Haus ${Format.power(it)}" },
                    s.meterGridPowerW?.let { "Netz ${Format.power(it, signed = true)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
        }
        Text(
            listOfNotNull(
                if (s.meterImportWh != null || s.meterExportWh != null) "Zähler ${Format.meterReading(s.meterImportWh)} / ${Format.meterReading(s.meterExportWh)}" else null,
                s.batterySocPercent?.let { soc -> "Speicher ${Format.percentValue(soc)}" + (s.batteryPowerW?.let { " ${Format.power(it, signed = true)}" } ?: "") },
                s.senecGridPowerW?.let { "SENEC-Netz ${Format.power(it, signed = true)}" },
                s.carSocPercent?.let { "Auto ${Format.percentValue(it)}" + (s.carChargePowerW?.takeIf { p -> p > 0 }?.let { " lädt ${Format.power(it)}" } ?: "") },
                s.carOdometerKm?.let { "${String.format(java.util.Locale.GERMANY, "%,.0f", it)} km" },
            ).joinToString(" · ").ifBlank { "keine weiteren Werte" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
