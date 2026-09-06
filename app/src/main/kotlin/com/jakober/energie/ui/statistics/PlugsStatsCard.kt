package com.jakober.energie.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.plugs.PlugTotals
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.BigValue
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.ShareBar
import com.jakober.energie.ui.theme.EnergyColors

/** Summe je Stecker ueber mehrere Tage. */
fun plugTotals(days: List<DayStatistics>): Map<String, PlugTotals> {
    val out = HashMap<String, PlugTotals>()
    days.forEach { d -> d.plugs.forEach { (id, t) -> out[id] = out[id]?.plus(t) ?: t } }
    return out
}

/**
 * Wer verbraucht wie viel: jeder Stecker mit kWh, Anteil am Hausverbrauch
 * und Kosten zum Strompreis, dazu der nicht gemessene Rest.
 */
@Composable
fun PlugsStatsCard(days: List<DayStatistics>, settings: Settings, houseConsumptionWh: Double?) {
    val totals = plugTotals(days)
    if (totals.isEmpty()) return
    val names = settings.plugs.associate { it.id to it.name }
    val rows = totals.entries.sortedByDescending { it.value.energyWh }
    val measured = rows.sumOf { it.value.energyWh }
    val house = houseConsumptionWh?.takeIf { it > 0 }
    val rest = house?.let { (it - measured).coerceAtLeast(0.0) }

    EnergieCard(title = "Verbraucher", accent = EnergyColors.house) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(measured), "Gemessen", EnergyColors.house, Modifier.weight(1f))
            if (house != null) BigValue(Format.percent(measured / house), "vom Hausverbrauch", EnergyColors.house, Modifier.weight(1f))
            BigValue(Format.euro(measured / 1000 * settings.pricePerKwh), "zum Strompreis", EnergyColors.grid, Modifier.weight(1f))
        }
        rows.forEach { (id, t) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(names[id] ?: id, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(
                                t.maxPowerW?.let { "Spitze ${Format.power(it)}" },
                                if (days.size > 1) "Ø ${Format.energy(t.energyWh / days.count { it.plugs.containsKey(id) }.coerceAtLeast(1))}/Tag" else null,
                                if (!t.fromCounter) "geschätzt" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Format.energy(t.energyWh), style = MaterialTheme.typography.titleMedium, color = EnergyColors.house)
                        Text(Format.euro(t.energyWh / 1000 * settings.pricePerKwh), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (house != null) ShareBar("Anteil am Haus", t.energyWh / house, EnergyColors.house)
            }
        }
        if (rest != null && rest > 50) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Nicht gemessen (Rest)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.energy(rest), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ShareBar("Anteil am Haus", rest / house!!, EnergyColors.neutral)
        }
        Text(
            "Der Zähler jedes Steckers zählt auch, wenn die App nicht misst; Messlücken verfälschen die Werte darum nicht.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
