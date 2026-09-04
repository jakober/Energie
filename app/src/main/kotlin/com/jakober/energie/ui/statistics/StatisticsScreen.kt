package com.jakober.energie.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.history.EnergyTotals
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.BigValue
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.EnergieViewModel
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.LegendItem
import com.jakober.energie.ui.Range
import com.jakober.energie.ui.RangeStatistics
import com.jakober.energie.ui.ShareBar
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.charts.BarSeries
import com.jakober.energie.ui.charts.GroupedBarChart
import com.jakober.energie.ui.charts.LineChart
import com.jakober.energie.ui.charts.LineSeries
import com.jakober.energie.ui.theme.EnergyColors
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun StatisticsScreen(vm: EnergieViewModel, contentPadding: PaddingValues) {
    val range by vm.range.collectAsStateWithLifecycle()
    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val day by vm.dayStats.collectAsStateWithLifecycle()
    val rangeStats by vm.rangeStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Statistik", style = MaterialTheme.typography.displaySmall) }

        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Range.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = range == r,
                        onClick = { vm.setRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, Range.entries.size),
                    ) { Text(when (r) { Range.DAY -> "Tag"; Range.WEEK -> "Woche"; Range.MONTH -> "Monat" }) }
                }
            }
        }

        item {
            val title = when (range) {
                Range.DAY -> Format.dateLong(date)
                Range.WEEK -> EnergieViewModel.bounds(date, Range.WEEK).let { (a, b) -> "${Format.dateNum(a)} – ${Format.dateShort(b)}" }
                Range.MONTH -> Format.month(date)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.Rounded.ChevronLeft, "Zurück") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (!vm.isToday) TextButton(onClick = vm::goToday) { Text("Heute") }
                }
                IconButton(onClick = { vm.shift(1) }) { Icon(Icons.Rounded.ChevronRight, "Weiter") }
            }
        }

        when (range) {
            Range.DAY -> dayItems(day, settings)
            else -> rangeItems(rangeStats, settings, range)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dayItems(s: DayStatistics?, settings: Settings) {
    if (s == null) return
    if (s.sampleCount == 0) {
        item { EnergieCard { Text("Für diesen Tag liegen keine Messpunkte vor.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        return
    }
    item { TotalsCard(s.totals, settings, "${s.sampleCount} Messpunkte") }

    item {
        EnergieCard(title = "Stundenprofil") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(EnergyColors.sun, "Erzeugung")
                LegendItem(EnergyColors.house, "Verbrauch")
                LegendItem(EnergyColors.grid, "Bezug")
            }
            GroupedBarChart(
                categories = s.hours.map { Format.hourLabel(it.hour) },
                series = listOf(
                    BarSeries("Erzeugung", EnergyColors.sun, s.hours.map { it.productionWh }),
                    BarSeries("Verbrauch", EnergyColors.house, s.hours.map { it.consumptionWh }),
                    BarSeries("Bezug", EnergyColors.grid, s.hours.map { it.gridImportWh }),
                ),
                modifier = Modifier.fillMaxWidth().height(180.dp),
                labelEvery = 3,
                highlightIndex = s.heaviestHour?.hour,
                valueFormatter = { Format.energy(it) },
            )
            s.heaviestHour?.let {
                Text(
                    "Stärkste Stunde: ${Format.hourLabel(it.hour)}–${Format.hourLabel((it.hour + 1) % 24)} Uhr mit ${Format.energy(it.consumptionWh)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item {
        EnergieCard(title = "Spitzen") {
            s.peakConsumption?.let { ValueRow("Höchster Verbrauch", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.Home, iconTint = EnergyColors.house) }
            s.peakProduction?.let { ValueRow("Höchste Erzeugung", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.WbSunny, iconTint = EnergyColors.sun) }
            s.peakGridImport?.let { ValueRow("Höchster Netzbezug", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.Bolt, iconTint = EnergyColors.grid) }
            s.peakGridExport?.let { ValueRow("Höchste Einspeisung", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.Bolt, iconTint = EnergyColors.export) }
            s.peakBatteryCharge?.let { ValueRow("Stärkstes Laden", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.BatteryChargingFull, iconTint = EnergyColors.battery) }
            s.peakBatteryDischarge?.let { ValueRow("Stärkstes Entladen", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.BatteryChargingFull, iconTint = EnergyColors.battery) }
            s.baseLoadW?.let { ValueRow("Grundlast", Format.power(it), "kleinstes 15-min-Mittel") }
            if (s.firstProduction != null && s.lastProduction != null) {
                ValueRow("PV aktiv", "${Format.time(s.firstProduction)} – ${Format.time(s.lastProduction)}")
            }
        }
    }

    if (s.socStart != null) {
        item {
            EnergieCard(title = "Speicher", accent = EnergyColors.battery) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigValue(Format.percentValue(s.socStart), "Beginn", modifier = Modifier.weight(1f))
                    BigValue(Format.percentValue(s.socEnd), "Ende", modifier = Modifier.weight(1f))
                    BigValue(Format.percentValue(s.socMin?.value), "Tief ${Format.time(s.socMin?.at)}", modifier = Modifier.weight(1f))
                    BigValue(Format.percentValue(s.socMax?.value), "Hoch ${Format.time(s.socMax?.at)}", modifier = Modifier.weight(1f))
                }
                LineChart(
                    series = listOf(LineSeries("Ladezustand", EnergyColors.battery, s.hours.map { it.batterySocPercent }, fill = true)),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    min = 0.0, max = 100.0,
                    xLabels = listOf(0f to "0", 0.25f to "6", 0.5f to "12", 0.75f to "18", 1f to "24"),
                )
                ValueRow("Geladen / Entladen", "${Format.energy(s.totals.batteryChargeWh)} / ${Format.energy(s.totals.batteryDischargeWh)}")
            }
        }
    }

    if (s.meterImportStartWh != null) {
        item {
            EnergieCard(title = "Zählerstände", accent = EnergyColors.grid) {
                ValueRow("Bezug Beginn", Format.meterReading(s.meterImportStartWh))
                ValueRow("Bezug Ende", Format.meterReading(s.meterImportEndWh))
                ValueRow("Bezug laut Zähler", Format.energy(s.totals.meterImportWh), color = EnergyColors.grid)
                if (s.meterExportStartWh != null) {
                    ValueRow("Einspeisung Beginn", Format.meterReading(s.meterExportStartWh))
                    ValueRow("Einspeisung Ende", Format.meterReading(s.meterExportEndWh))
                    ValueRow("Einspeisung laut Zähler", Format.energy(s.totals.meterExportWh), color = EnergyColors.export)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rangeItems(r: RangeStatistics?, settings: Settings, range: Range) {
    if (r == null) return
    if (r.daysWithData.isEmpty()) {
        item { EnergieCard { Text("Für diesen Zeitraum liegen keine Messpunkte vor.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        return
    }
    item { TotalsCard(r.totals, settings, "${r.daysWithData.size} Tage mit Daten") }

    item {
        EnergieCard(title = if (range == Range.WEEK) "Tage der Woche" else "Tage des Monats") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(EnergyColors.sun, "Erzeugung")
                LegendItem(EnergyColors.house, "Verbrauch")
            }
            GroupedBarChart(
                categories = r.days.map { if (range == Range.WEEK) Format.dateShort(it.date).take(2) else it.date.dayOfMonth.toString() },
                series = listOf(
                    BarSeries("Erzeugung", EnergyColors.sun, r.days.map { it.totals.productionWh }),
                    BarSeries("Verbrauch", EnergyColors.house, r.days.map { it.totals.consumptionWh }),
                ),
                modifier = Modifier.fillMaxWidth().height(180.dp),
                labelEvery = if (range == Range.WEEK) 1 else 5,
                valueFormatter = { Format.energy(it) },
            )
        }
    }

    item {
        EnergieCard(title = "Auffälligkeiten") {
            r.bestProductionDay?.let { ValueRow("Bester PV-Tag", Format.energy(it.totals.productionWh), Format.dateShort(it.date), icon = Icons.Rounded.WbSunny, iconTint = EnergyColors.sun) }
            r.heaviestConsumptionDay?.let { ValueRow("Verbrauchsstärkster Tag", Format.energy(it.totals.consumptionWh), Format.dateShort(it.date), icon = Icons.Rounded.Home, iconTint = EnergyColors.house) }
            r.peakConsumption?.let { ValueRow("Höchste Verbrauchsspitze", Format.power(it.value), "${Format.dateShort(it.at.toLocalDateTime(TimeZone.currentSystemDefault()).date)} um ${Format.time(it.at)}", icon = Icons.Rounded.Bolt, iconTint = EnergyColors.house) }
            r.averageConsumptionWh?.let { ValueRow("Verbrauch je Tag", Format.energy(it), "Durchschnitt") }
        }
    }
}

@Composable
private fun TotalsCard(t: EnergyTotals, settings: Settings, subtitle: String) {
    EnergieCard(title = "Bilanz") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(t.productionWh), "Erzeugt", EnergyColors.sun, Modifier.weight(1f))
            BigValue(Format.energy(t.consumptionWh), "Verbraucht", EnergyColors.house, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(t.gridImportWh), "Bezogen", EnergyColors.grid, Modifier.weight(1f))
            BigValue(Format.energy(t.gridExportWh), "Eingespeist", EnergyColors.export, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(t.batteryChargeWh), "Speicher geladen", EnergyColors.battery, Modifier.weight(1f))
            BigValue(Format.energy(t.batteryDischargeWh), "Speicher entladen", EnergyColors.battery, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        ShareBar("Autarkie", t.selfSufficiency, EnergyColors.battery)
        ShareBar("Eigenverbrauch", t.selfConsumptionShare, EnergyColors.sun)
        Spacer(Modifier.height(4.dp))
        val cost = t.gridImportWh / 1000 * settings.pricePerKwh
        val income = t.gridExportWh / 1000 * settings.feedInPerKwh
        val saved = t.selfConsumptionWh / 1000 * settings.pricePerKwh
        ValueRow("Stromkosten", Format.euro(cost), "bei ${Format.euro(settings.pricePerKwh)}/kWh")
        ValueRow("Einspeisevergütung", Format.euro(income), "bei ${Format.euro(settings.feedInPerKwh)}/kWh")
        ValueRow("Gespart durch Eigenverbrauch", Format.euro(saved))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
