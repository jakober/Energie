package com.jakober.energie.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.history.GridMonth
import com.jakober.energie.core.history.GridMonths
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.BigValue
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.LegendItem
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.charts.BarSeries
import com.jakober.energie.ui.charts.GroupedBarChart
import com.jakober.energie.ui.theme.EnergyColors

/**
 * Was wirklich zugekauft wurde: Bezug und Einspeisung je Monat aus den
 * Zaehlerstaenden des Hauptzaehlers, dazu die Kosten zum eingestellten Tarif.
 * Messluecken der App aendern die Summe nicht, weil der Zaehler weiterzaehlt.
 */
@Composable
fun GridBillCard(months: List<GridMonth>, settings: Settings) {
    if (months.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val price = settings.pricePerKwh
    val feed = settings.feedInPerKwh
    val shown = months.takeLast(if (expanded) 24 else 12)
    val current = months.last()
    val closed = months.dropLast(1).lastOrNull { it.complete }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    EnergieCard(title = "Netzbezug je Monat", accent = EnergyColors.grid) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(current.importWh), "${Format.monthName(current.first)} bisher", EnergyColors.grid, Modifier.weight(1f))
            BigValue(Format.euro(current.costEur(price)), "zum Strompreis", EnergyColors.grid, Modifier.weight(1f))
            val projected = current.projectedImportWh(GridMonths.daysInMonth(current.first))
            BigValue(
                if (current.complete && current.daysElapsed >= GridMonths.daysInMonth(current.first)) "fertig"
                else Format.euro(projected?.div(1000)?.times(price)),
                "voraussichtlich", EnergyColors.house, Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(EnergyColors.grid, "Bezug")
            LegendItem(EnergyColors.export, "Einspeisung")
        }
        GroupedBarChart(
            categories = shown.map { Format.monthShort(it.first) },
            series = listOf(
                BarSeries("Bezug", EnergyColors.grid, shown.map { it.importWh }),
                BarSeries("Einspeisung", EnergyColors.export, shown.map { it.exportWh }),
            ),
            modifier = Modifier.fillMaxWidth().height(150.dp),
            labelEvery = if (shown.size > 14) 2 else 1,
            valueFormatter = { Format.energy(it) },
        )

        shown.reversed().forEach { m ->
            val note = buildString {
                append("eingespeist ${Format.energy(m.exportWh)} (${Format.euro(m.feedInEur(feed))})")
                if (!m.complete) append(" · nur ${m.days} von ${m.daysElapsed} Tagen gemessen")
                if (!m.fromMeter) append(" · teils geschätzt")
            }
            ValueRow(
                "${Format.monthName(m.first)} ${m.year}",
                Format.energy(m.importWh),
                note,
                color = EnergyColors.grid,
            )
            ValueRow("  davon zu zahlen", Format.euro(m.balanceEur(price, feed)), "Bezug ${Format.euro(m.costEur(price))} − Vergütung ${Format.euro(m.feedInEur(feed))}")
        }

        if (months.size > 12) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                Text(if (expanded) "Weniger anzeigen" else "Alle Monate anzeigen")
            }
        }

        closed?.let {
            ValueRow("Letzter voller Monat", Format.energy(it.importWh), "${Format.monthName(it.first)} · ${Format.euro(it.costEur(price))}")
        }
        val full = months.filter { it.complete && it.days >= 28 }
        if (full.size >= 2) {
            val avg = full.sumOf { it.importWh } / full.size
            ValueRow("Durchschnitt je Monat", Format.energy(avg), "aus ${full.size} vollen Monaten · ${Format.euro(avg / 1000 * price)}")
            ValueRow("Hochgerechnet aufs Jahr", Format.energy(avg * 12), Format.euro(avg * 12 / 1000 * price), color = EnergyColors.grid)
        }
        Text(
            "Aus den Zählerständen des Hauptzählers. Das ist der Strom, den der Versorger abrechnet; Messlücken der App verfälschen ihn nicht.",
            style = MaterialTheme.typography.labelSmall, color = muted,
        )
    }
}
