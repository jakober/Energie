package com.jakober.energie.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.history.UpgradeResult
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.BigValue
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.charts.BarSeries
import com.jakober.energie.ui.charts.GroupedBarChart
import com.jakober.energie.ui.theme.EnergyColors
import kotlin.math.roundToInt

/**
 * "Lohnt sich ein weiteres Speichermodul?" Spielt die gespeicherten Tage mit einem
 * gedachten Zusatzmodul nach und rechnet Ersparnis und Amortisation aus.
 */
@Composable
fun StorageUpgradeCard(
    result: UpgradeResult?,
    settings: Settings,
    currentCapacityWh: Double?,
    moduleKwh: Double,
    costEur: Double,
    onModuleKwh: (Double) -> Unit,
    onCost: (Double) -> Unit,
) {
    var kwhText by rememberSaveable { mutableStateOf(Format.plain(moduleKwh)) }
    var costText by rememberSaveable { mutableStateOf(Format.plain(costEur)) }
    val price = settings.pricePerKwh
    val feed = settings.feedInPerKwh

    EnergieCard(title = "Speicher erweitern?", accent = EnergyColors.battery) {
        Text(
            "Nachgerechnet mit deinen Minutenwerten: Das Zusatzmodul lädt nur, wenn der Speicher voll ist und trotzdem eingespeist wird, und liefert nur, wenn er leer ist und bezogen wird. Alles andere macht der Speicher heute schon." +
                (currentCapacityWh?.let { " Heute: ${Format.energy(it)}." } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = kwhText, onValueChange = { kwhText = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v > 0 }?.let(onModuleKwh) },
                label = { Text("Modul in kWh") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = costText, onValueChange = { costText = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v > 0 }?.let(onCost) },
                label = { Text("Kosten in €") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        if (result == null) {
            Text("Rechne …", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@EnergieCard
        }
        val days = result.days
        if (days.isEmpty()) {
            Text("Noch keine vollen Tage mit Speicherdaten.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@EnergieCard
        }
        val saved = result.savedEur(price, feed)
        val perYear = result.savedPerYearEur(price, feed)
        val payback = result.paybackYears(costEur, price, feed)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(result.dischargedWh), "zusätzlich genutzt", EnergyColors.battery, Modifier.weight(1f))
            BigValue(Format.euro(saved), "gespart in ${days.size} Tagen", EnergyColors.sun, Modifier.weight(1f))
            BigValue(
                payback?.let { if (it > 99) "> 99 J." else String.format(java.util.Locale.GERMANY, "%.0f Jahre", it) } ?: "nie",
                "Amortisation", if (payback != null && payback <= 12) EnergyColors.battery else EnergyColors.grid, Modifier.weight(1f),
            )
        }
        ValueRow("Zeitraum", "${Format.dateNum(days.first().date)} – ${Format.dateNum(days.last().date)}", "${days.size} Tage, ohne heute")
        ValueRow("Speicher war voll", "${result.daysFull} Tage", "und leer: ${result.daysEmpty} Tage · beides am selben Tag: ${result.daysBothFullAndEmpty}")
        ValueRow("Eingespeist bei vollem Speicher", Format.energy(result.exportWhileFullWh), "davon ins Modul: ${Format.energy(result.chargedWh)}", color = EnergyColors.export)
        ValueRow("Bezogen bei leerem Speicher", Format.energy(result.importWhileEmptyWh), "davon aus dem Modul: ${Format.energy(result.dischargedWh)}", color = EnergyColors.grid)
        ValueRow("Ersparnis je Tag", Format.euro(saved / days.size), "${Format.energy(result.dischargedWh / days.size)} × (${Format.euro(price)} − ${Format.euro(feed)})")
        ValueRow("Aufs Jahr hochgerechnet", Format.euro(perYear), "${result.cycles.roundToInt()} Zyklen im Zeitraum")

        val show = days.takeLast(60)
        Column {
            Text("Aus dem Modul je Tag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GroupedBarChart(
                categories = show.map { Format.dateNum(it.date) },
                series = listOf(BarSeries("Modul", EnergyColors.battery, show.map { it.dischargedWh })),
                modifier = Modifier.fillMaxWidth().height(120.dp),
                labelEvery = maxOf(1, show.size / 6),
                valueFormatter = { Format.energy(it) },
            )
        }
        Text(
            "Die Hochrechnung nimmt an, dass das ganze Jahr wie der Zeitraum aussieht. Sommertage bringen viel, von November bis Februar wird der Speicher selten voll. " +
                "Erst mit Daten aus Frühjahr und Winter wird die Jahreszahl belastbar. Speichermodule halten etwa 10 bis 15 Jahre; darüber lohnt sich die Erweiterung nicht.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
