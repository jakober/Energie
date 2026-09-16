package com.jakober.energie.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.history.GridMonth
import com.jakober.energie.core.history.GridMonths
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.LegendItem
import com.jakober.energie.ui.charts.BarSeries
import com.jakober.energie.ui.charts.GroupedBarChart
import com.jakober.energie.ui.theme.EnergyColors

/** Wie viele Zeitraeume auf eine Seite passen. */
private const val PageSize = 6

/** Ein Balken im Streifen: Beschriftung, Bezug, Einspeisung, Zusatz. */
private class Bar(val label: String, val title: String, val importWh: Double, val exportWh: Double, val note: String?)

/**
 * Der echte Stromzukauf auf der Startseite: je Monat (oder Jahr) die Kilowattstunden
 * aus dem Netz und was sie gekostet haben, aus den Zaehlerstaenden des Hauptzaehlers.
 * Mit den Pfeilen blaettert man in die Vergangenheit, der Umschalter wechselt
 * zwischen Monaten und Jahren.
 */
@Composable
fun GridBillStrip(months: List<GridMonth>, settings: Settings) {
    if (months.isEmpty()) return
    var byYear by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val price = settings.pricePerKwh
    val feed = settings.feedInPerKwh

    val all: List<Bar> = if (byYear) {
        GridMonths.years(months).map { y ->
            Bar(
                y.year.toString(), y.year.toString(), y.importWh, y.exportWh,
                if (y.complete) null else "${y.months} von 12 Monaten",
            )
        }
    } else {
        months.map { m ->
            Bar(
                Format.monthShort(m.first), "${Format.monthName(m.first)} ${m.year}", m.importWh, m.exportWh,
                if (m.complete) null else "${m.days} von ${m.daysElapsed} Tagen",
            )
        }
    }
    // Seite 0 ist die jüngste; groessere Zahlen gehen in die Vergangenheit.
    val maxPage = ((all.size - 1) / PageSize).coerceAtLeast(0)
    val p = page.coerceIn(0, maxPage)
    val end = all.size - p * PageSize
    val shown = all.subList((end - PageSize).coerceAtLeast(0), end)
    if (shown.isEmpty()) return

    val sumImport = shown.sumOf { it.importWh }
    val newest = shown.last()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    EnergieCard(title = "Aus dem Netz gekauft", accent = EnergyColors.grid) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !byYear, onClick = { byYear = false; page = 0 }, label = { Text("Monate") })
            FilterChip(selected = byYear, onClick = { byYear = true; page = 0 }, label = { Text("Jahre") })
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (p < maxPage) page = p + 1 }, enabled = p < maxPage) {
                Icon(Icons.Rounded.ChevronLeft, "Früher", tint = if (p < maxPage) MaterialTheme.colorScheme.onSurface else muted)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (shown.size == 1) shown.first().title else "${shown.first().label} – ${shown.last().label}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("zusammen ${Format.energy(sumImport)} · ${Format.euro(sumImport / 1000 * price)}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            IconButton(onClick = { if (p > 0) page = p - 1 }, enabled = p > 0) {
                Icon(Icons.Rounded.ChevronRight, "Später", tint = if (p > 0) MaterialTheme.colorScheme.onSurface else muted)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(EnergyColors.grid, "Bezug")
            LegendItem(EnergyColors.export, "Einspeisung")
        }
        GroupedBarChart(
            categories = shown.map { it.label },
            series = listOf(
                BarSeries("Bezug", EnergyColors.grid, shown.map { it.importWh }),
                BarSeries("Einspeisung", EnergyColors.export, shown.map { it.exportWh }),
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp),
            highlightIndex = if (p == 0) shown.lastIndex else null,
            valueFormatter = { Format.energy(it) },
        )

        shown.reversed().forEach { b ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = MaterialTheme.typography.bodyMedium)
                    val detail = listOfNotNull(
                        "eingespeist ${Format.energy(b.exportWh)}".takeIf { b.exportWh > 50 },
                        b.note,
                    ).joinToString(" · ")
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall, color = muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Format.energy(b.importWh), style = MaterialTheme.typography.titleMedium, color = EnergyColors.grid)
                    Text(Format.euro(b.importWh / 1000 * price), style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }

        if (!byYear && p == 0) {
            val current = months.last()
            val projected = current.projectedImportWh(GridMonths.daysInMonth(current.first))
            if (projected != null && !current.complete || (projected != null && current.daysElapsed < GridMonths.daysInMonth(current.first))) {
                Text(
                    "${Format.monthName(current.first)} läuft noch: voraussichtlich ${Format.energy(projected)} · ${Format.euro(projected / 1000 * price)}",
                    style = MaterialTheme.typography.labelSmall, color = muted,
                )
            }
        }
        val saldo = shown.sumOf { it.importWh } / 1000 * price - shown.sumOf { it.exportWh } / 1000 * feed
        Text(
            "Zu zahlen nach Vergütung: ${Format.euro(saldo)}. Aus den Zählerständen des Hauptzählers.",
            style = MaterialTheme.typography.labelSmall, color = muted, fontWeight = FontWeight.Medium,
        )
    }
}
