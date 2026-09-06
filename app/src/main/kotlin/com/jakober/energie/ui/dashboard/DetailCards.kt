package com.jakober.energie.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.WbSunny
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.model.CarExtras
import com.jakober.energie.data.LiveState
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.BigValue
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.LegendItem
import com.jakober.energie.ui.ShareBar
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.charts.BarSeries
import com.jakober.energie.ui.charts.GroupedBarChart
import com.jakober.energie.ui.charts.LineChart
import com.jakober.energie.ui.charts.LineSeries
import com.jakober.energie.ui.charts.RingGauge
import com.jakober.energie.ui.theme.EnergyColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Detailkarten zu den Knoten des Flussdiagramms. Sie erscheinen nach Tippen
 * auf das Symbol, tragen den Rahmen in der Knotenfarbe und zeigen alles, was
 * zu Speicher, PV, Haus und Netz aus Momentaufnahme und Tagesverlauf bekannt ist.
 */

private fun compare(today: Double, yesterday: Double?): String? {
    if (yesterday == null || yesterday <= 0) return null
    val diff = (today - yesterday) / yesterday * 100
    val sign = if (diff >= 0) "+" else ""
    return "gestern ${Format.energy(yesterday)} ($sign${diff.roundToInt()} %)"
}

/**
 * "leer gegen 23:15 · 2 h 40 min" bzw. "voll gegen 12:40 · 1 h 05 min", aus dem
 * Moment gerechnet. Null, wenn der Speicher ruht oder die Kapazitaet fehlt.
 */
fun batteryEtaLabel(soc: Double?, powerW: Double?, capacityWh: Double?, now: kotlinx.datetime.Instant = kotlinx.datetime.Clock.System.now()): String? {
    val e = com.jakober.energie.core.history.BatteryRuntime.estimate(soc, powerW, capacityWh) ?: return null
    val what = if (e.charging) "voll" else "leer"
    if (e.beyondHorizon) return if (e.charging) "voll in über 2 Tagen" else "reicht über 2 Tage"
    val at = now + e.duration
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    val dayOffset = at.toLocalDateTime(zone).date.toEpochDays() - now.toLocalDateTime(zone).date.toEpochDays()
    val day = when (dayOffset) { 0 -> ""; 1 -> "morgen "; else -> "${Format.dateNum(at.toLocalDateTime(zone).date)} " }
    return "$what ${day}gegen ${Format.time(at)} · ${Format.duration(e.duration.inWholeMinutes)}"
}

@Composable
fun BatteryDetailCard(live: LiveState, today: DayStatistics?, onClose: () -> Unit) {
    val s = live.sample
    val soc = s?.batterySocPercent
    val p = s?.batteryPowerW
    val np = live.senec?.bessNameplate
    val capacity = np?.designCapacityWh
    EnergieCard(title = "Speicher im Detail", accent = EnergyColors.battery, border = EnergyColors.battery, onClose = onClose) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RingGauge(((soc ?: 0.0) / 100).toFloat(), EnergyColors.battery, Modifier.size(96.dp), strokeWidth = 12.dp) {
                Text(Format.percentValue(soc), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when { p == null -> "–"; p > 15 -> "lädt mit ${Format.power(p)}"; p < -15 -> "gibt ${Format.power(-p)} ab"; else -> "ruht" },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (capacity != null && soc != null) {
                    Text("${Format.energy(capacity * soc / 100)} von ${Format.energy(capacity)} gespeichert", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    batteryEtaLabel(soc, p, capacity)?.let { eta ->
                        Text(
                            "Beim jetzigen Fluss $eta",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                s?.batteryState?.takeIf { st -> st.any { it.isLetter() } }?.let {
                    Text("Zustand: ${it.lowercase().replaceFirstChar { c -> c.uppercase() }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (today != null && today.sampleCount > 0) {
            val t = today.totals
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigValue(Format.energy(t.batteryChargeWh), "Heute geladen", EnergyColors.battery, Modifier.weight(1f))
                BigValue(Format.energy(t.batteryDischargeWh), "Heute entladen", EnergyColors.battery, Modifier.weight(1f))
                if (capacity != null && capacity > 0) BigValue(String.format(Locale.GERMANY, "%.2f", t.batteryChargeWh / capacity), "Vollzyklen heute", modifier = Modifier.weight(1f))
            }
            if (today.socStart != null) {
                LineChart(
                    series = listOf(LineSeries("Ladezustand", EnergyColors.battery, today.hours.map { it.batterySocPercent }, fill = true)),
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    min = 0.0, max = 100.0,
                    xLabels = listOf(0f to "0", 0.25f to "6", 0.5f to "12", 0.75f to "18", 1f to "24"),
                )
                ValueRow("Ladezustand heute", "${Format.percentValue(today.socStart)} → ${Format.percentValue(today.socEnd)}", "Beginn → jetzt")
                today.socMin?.let { ValueRow("Tiefster Stand", Format.percentValue(it.value), "um ${Format.time(it.at)}") }
                today.socMax?.let { ValueRow("Höchster Stand", Format.percentValue(it.value), "um ${Format.time(it.at)}") }
            }
            today.peakBatteryCharge?.let { ValueRow("Stärkstes Laden", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.BatteryChargingFull, iconTint = EnergyColors.battery) }
            today.peakBatteryDischarge?.let { ValueRow("Stärkstes Entladen", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.BatteryChargingFull, iconTint = EnergyColors.battery) }
            val hoursCharging = today.hours.count { it.batteryChargeWh > 50 }
            val hoursDischarging = today.hours.count { it.batteryDischargeWh > 50 }
            ValueRow("Stunden mit Laden / Entladen", "$hoursCharging / $hoursDischarging")
        }
        if (np != null) {
            Spacer(Modifier.height(4.dp))
            Text("Anlage", style = MaterialTheme.typography.titleSmall)
            np.model?.let { ValueRow("Modell", it) }
            ValueRow("Nennkapazität", Format.energy(capacity))
            ValueRow("Max. Laden / Entladen", "${Format.power(np.activeChargePowerW)} / ${Format.power(np.activeDischargePowerW)}")
            val bat = live.senec?.battery
            bat?.voltage?.let { v -> ValueRow("Spannung / Strom", "${String.format(Locale.GERMANY, "%.1f V", v)} / ${bat.current?.let { String.format(Locale.GERMANY, "%.1f A", it) } ?: "–"}") }
        }
    }
}

/** Anlagenleistung fuer die Prognose: eingetragen, sonst aus dem Verlauf geschaetzt. */
fun pvPeakKw(settings: Settings, estimate: Double?): Double? = settings.pvPeakKw.takeIf { it > 0 } ?: estimate

/** Leistung der zweiten Dachseite; ohne eingetragene erste Seite gibt es keine zweite. */
fun pvPeakKw2(settings: Settings): Double = if (settings.pvPeakKw > 0) settings.pvPeakKw2 else 0.0

fun com.jakober.energie.core.forecast.PvForecastDay.energyFor(settings: Settings, peak: Double): Double =
    energyKwhTwoSides(peak, pvPeakKw2(settings), calibration = settings.pvCalibration)

/** Text fuer die kleine Prognose-Anzeige im Flussdiagramm, null wenn nichts zu zeigen ist. */
fun pvForecastBadge(settings: Settings, estimate: Double?, today: LocalDate, producedTodayWh: Double?): ForecastBadge? {
    val forecast = settings.pvForecast ?: return null
    val peak = pvPeakKw(settings, estimate)
        ?: return ForecastBadge("PV-Prognose", "kWp fehlt", "Anlagenleistung unter Einstellungen → PV-Prognose eintragen")
    val tomorrow = forecast.day(today.plus(1, DateTimeUnit.DAY)) ?: return null
    val todayDay = forecast.day(today)
    val detail = buildList {
        tomorrow.weatherLabel?.let { add(it) }
        if (todayDay != null) {
            val t = "heute ≈ ${Format.energy(todayDay.energyFor(settings, peak) * 1000)}"
            add(if (producedTodayWh != null && producedTodayWh > 100) "$t, bisher ${Format.energy(producedTodayWh)}" else t)
        }
    }.joinToString(" · ")
    return ForecastBadge("Morgen", "≈ ${Format.energy(tomorrow.energyFor(settings, peak) * 1000)}", detail.ifBlank { "PV-Prognose" })
}

@Composable
fun PvDetailCard(live: LiveState, today: DayStatistics?, yesterday: DayStatistics?, settings: Settings, todayDate: LocalDate, onClose: () -> Unit) {
    val s = live.sample
    EnergieCard(title = "Photovoltaik im Detail", accent = EnergyColors.sun, border = EnergyColors.sun, onClose = onClose) {
        settings.pvForecast?.let { forecast ->
            val peak = pvPeakKw(settings, live.pvPeakEstimateKw)
            Text("Prognose", style = MaterialTheme.typography.titleSmall)
            if (peak == null) {
                Text("Anlagenleistung fehlt: unter Einstellungen → PV-Prognose in kWp eintragen, dann rechnet die App den Ertrag.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                forecast.days.sortedBy { it.date }.forEach { d ->
                    val offset = d.date.toEpochDays() - todayDate.toEpochDays()
                    if (offset < 0) return@forEach
                    val name = when (offset) { 0 -> "Heute"; 1 -> "Morgen"; 2 -> "Übermorgen"; else -> Format.dateShort(d.date) }
                    val detail = listOfNotNull(d.weatherLabel, d.sunshineHours?.let { "${String.format(Locale.GERMANY, "%.1f", it)} h Sonne" }, "${(d.irradianceWhPerM2 / 1000).let { String.format(Locale.GERMANY, "%.1f", it) }} kWh/m²")
                        .joinToString(" · ")
                    val actual = if (offset == 0) today?.totals?.productionWh else null
                    ValueRow(name, "≈ ${Format.energy(d.energyFor(settings, peak) * 1000)}", detail + (actual?.takeIf { it > 100 }?.let { " · bisher ${Format.energy(it)}" } ?: ""), color = EnergyColors.sun)
                }
                Text(
                    "Mit ${String.format(Locale.GERMANY, "%.2f", peak + pvPeakKw2(settings))} kWp" + (if (settings.pvPeakKw <= 0) " (geschätzt)" else if (pvPeakKw2(settings) > 0) " auf zwei Dachseiten" else "") +
                        ", Faktor ${String.format(Locale.GERMANY, "%.2f", settings.pvCalibration)} aus den echten Erträgen. Quelle Open-Meteo.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.power(s?.productionW), "Jetzt", EnergyColors.sun, Modifier.weight(1f))
            BigValue(Format.energy(today?.totals?.productionWh), "Heute erzeugt", EnergyColors.sun, Modifier.weight(1f))
            BigValue(Format.energy(yesterday?.totals?.productionWh), "Gestern", modifier = Modifier.weight(1f))
        }
        if (today != null && today.sampleCount > 0) {
            val t = today.totals
            compare(t.productionWh, yesterday?.totals?.productionWh)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            GroupedBarChart(
                categories = today.hours.map { Format.hourLabel(it.hour) },
                series = listOf(BarSeries("Erzeugung", EnergyColors.sun, today.hours.map { it.productionWh })),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                labelEvery = 3,
                highlightIndex = today.hours.maxByOrNull { it.productionWh }?.takeIf { it.productionWh > 0 }?.hour,
                valueFormatter = { Format.energy(it) },
            )
            today.peakProduction?.let { ValueRow("Spitze heute", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.WbSunny, iconTint = EnergyColors.sun) }
            today.hours.maxByOrNull { it.productionWh }?.takeIf { it.productionWh > 0 }?.let {
                ValueRow("Beste Stunde", Format.energy(it.productionWh), "${Format.hourLabel(it.hour)}–${Format.hourLabel((it.hour + 1) % 24)} Uhr")
            }
            if (today.firstProduction != null && today.lastProduction != null) {
                ValueRow("PV aktiv", "${Format.time(today.firstProduction)} – ${Format.time(today.lastProduction)}")
            }
            Spacer(Modifier.height(4.dp))
            Text("Wohin der Strom ging", style = MaterialTheme.typography.titleSmall)
            ShareBar("Selbst verbraucht", t.selfConsumptionShare, EnergyColors.sun)
            ValueRow("Eigenverbrauch", Format.energy(t.selfConsumptionWh), "davon ins Auto ${Format.energy(t.carFromSolarWh)}")
            ValueRow("Eingespeist", Format.energy(t.gridExportWh), "Vergütung ${Format.euro(t.gridExportWh / 1000 * settings.feedInPerKwh)}", color = EnergyColors.export)
            ValueRow("Wert des Eigenverbrauchs", Format.euro(t.selfConsumptionWh / 1000 * settings.pricePerKwh), "zum Strompreis gerechnet")
        } else {
            Text("Noch keine Messpunkte für heute.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HouseDetailCard(live: LiveState, today: DayStatistics?, yesterday: DayStatistics?, settings: Settings, onClose: () -> Unit) {
    val s = live.sample
    val household = s?.householdW
    val carW = s?.carChargePowerW ?: 0.0
    EnergieCard(title = "Haus im Detail", accent = EnergyColors.house, border = EnergyColors.house, onClose = onClose) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.power(household), "Haushalt jetzt", EnergyColors.house, Modifier.weight(1f))
            if (carW > 0) BigValue(Format.power(carW), "davon Auto", EnergyColors.car, Modifier.weight(1f))
            BigValue(Format.energy(today?.totals?.consumptionWh), "Heute verbraucht", EnergyColors.house, Modifier.weight(1f))
        }
        if (today != null && today.sampleCount > 0) {
            val t = today.totals
            compare(t.consumptionWh, yesterday?.totals?.consumptionWh)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(EnergyColors.house, "Verbrauch")
                LegendItem(EnergyColors.car, "davon Auto")
            }
            GroupedBarChart(
                categories = today.hours.map { Format.hourLabel(it.hour) },
                series = listOf(
                    BarSeries("Verbrauch", EnergyColors.house, today.hours.map { it.consumptionWh }),
                    BarSeries("Auto", EnergyColors.car, today.hours.map { it.carChargeWh }),
                ),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                labelEvery = 3,
                highlightIndex = today.heaviestHour?.hour,
                valueFormatter = { Format.energy(it) },
            )
            today.peakConsumption?.let { ValueRow("Verbrauchsspitze", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.Home, iconTint = EnergyColors.house) }
            today.heaviestHour?.let { ValueRow("Stärkste Stunde", Format.energy(it.consumptionWh), "${Format.hourLabel(it.hour)}–${Format.hourLabel((it.hour + 1) % 24)} Uhr") }
            today.baseLoadW?.let { ValueRow("Grundlast", Format.power(it), "kleinstes 15-min-Mittel, ≈ ${Format.energy(it * 24)} am Tag", icon = Icons.Rounded.Bolt, iconTint = EnergyColors.neutral) }
            if (t.carChargeWh > 50) ValueRow("Ins Auto geladen", Format.energy(t.carChargeWh), "Haushalt ohne Auto: ${Format.energy(t.consumptionWh - t.carChargeWh)}", icon = Icons.Rounded.ElectricCar, iconTint = EnergyColors.car)
            Spacer(Modifier.height(4.dp))
            Text("Woher der Strom kam", style = MaterialTheme.typography.titleSmall)
            ShareBar("Autarkie heute", t.selfSufficiency, EnergyColors.battery)
            ValueRow("Aus PV und Speicher", Format.energy(t.consumptionWh - t.gridImportWh))
            ValueRow("Aus dem Netz", Format.energy(t.gridImportWh), "Kosten ${Format.euro(t.gridImportWh / 1000 * settings.pricePerKwh)}", color = EnergyColors.grid)
            ValueRow("Durchschnittsleistung heute", Format.power(t.consumptionWh / (today.hours.count { it.consumptionWh > 0 }.coerceAtLeast(1))), "je Stunde mit Daten")
        } else {
            Text("Noch keine Messpunkte für heute.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GridDetailCard(live: LiveState, today: DayStatistics?, yesterday: DayStatistics?, settings: Settings, onClose: () -> Unit) {
    val s = live.sample
    val grid = s?.gridPowerW
    EnergieCard(title = "Netz im Detail", accent = EnergyColors.grid, border = EnergyColors.grid, onClose = onClose) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(
                Format.power(grid?.let { abs(it) }),
                when { grid == null -> "Netz"; grid > 15 -> "Bezug jetzt"; grid < -15 -> "Einspeisung jetzt"; else -> "ausgeglichen" },
                if ((grid ?: 0.0) < -15) EnergyColors.export else EnergyColors.grid, Modifier.weight(1f),
            )
            BigValue(Format.energy(today?.totals?.gridImportWh), "Heute bezogen", EnergyColors.grid, Modifier.weight(1f))
            BigValue(Format.energy(today?.totals?.gridExportWh), "Heute eingespeist", EnergyColors.export, Modifier.weight(1f))
        }
        if (s?.meterGridPowerW != null && s.senecGridPowerW != null) {
            Text(
                "Zähler ${Format.power(s.meterGridPowerW, signed = true)} · SENEC ${Format.power(s.senecGridPowerW, signed = true)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (today != null && today.sampleCount > 0) {
            val t = today.totals
            compare(t.gridImportWh, yesterday?.totals?.gridImportWh)?.let { Text("Bezug: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(EnergyColors.grid, "Bezug")
                LegendItem(EnergyColors.export, "Einspeisung")
            }
            GroupedBarChart(
                categories = today.hours.map { Format.hourLabel(it.hour) },
                series = listOf(
                    BarSeries("Bezug", EnergyColors.grid, today.hours.map { it.gridImportWh }),
                    BarSeries("Einspeisung", EnergyColors.export, today.hours.map { it.gridExportWh }),
                ),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                labelEvery = 3,
                valueFormatter = { Format.energy(it) },
            )
            today.peakGridImport?.let { ValueRow("Höchster Bezug", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.Bolt, iconTint = EnergyColors.grid) }
            today.peakGridExport?.let { ValueRow("Höchste Einspeisung", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.Bolt, iconTint = EnergyColors.export) }
            val hoursImport = today.hours.count { it.gridImportWh > 50 }
            val hoursExport = today.hours.count { it.gridExportWh > 50 }
            ValueRow("Stunden mit Bezug / Einspeisung", "$hoursImport / $hoursExport")
            Spacer(Modifier.height(4.dp))
            Text("Geld", style = MaterialTheme.typography.titleSmall)
            val cost = t.gridImportWh / 1000 * settings.pricePerKwh
            val income = t.gridExportWh / 1000 * settings.feedInPerKwh
            ValueRow("Stromkosten heute", Format.euro(cost), "bei ${Format.euro(settings.pricePerKwh)}/kWh", color = EnergyColors.grid)
            ValueRow("Einspeisevergütung heute", Format.euro(income), "bei ${Format.euro(settings.feedInPerKwh)}/kWh", color = EnergyColors.export)
            ValueRow("Saldo heute", Format.euro(cost - income), if (cost - income >= 0) "zu zahlen" else "Gutschrift")
            if (today.meterImportStartWh != null) {
                Spacer(Modifier.height(4.dp))
                Text("Zählerstände", style = MaterialTheme.typography.titleSmall)
                ValueRow("Bezug (1.8.0)", Format.meterReading(s?.meterImportWh ?: today.meterImportEndWh), "heute +${Format.energy(t.meterImportWh)}")
                if (today.meterExportStartWh != null) ValueRow("Einspeisung (2.8.0)", Format.meterReading(s?.meterExportWh ?: today.meterExportEndWh), "heute +${Format.energy(t.meterExportWh)}")
            }
        } else {
            Text("Noch keine Messpunkte für heute.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Zusatzwerte des Autos aus der Ford-Telemetrie, plus Vollansicht aller Messwerte. */
@Composable
fun CarExtrasSection(x: CarExtras) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    Text("Fahrzeug", style = MaterialTheme.typography.titleSmall)
    x.energyRemainingKwh?.let { ValueRow("Energie im Akku", String.format(Locale.GERMANY, "%.1f kWh", it)) }
    x.timeToFullMinutes?.takeIf { it > 0 }?.let { ValueRow("Voll in", Format.duration(it.toLong())) }
    x.batteryTempC?.let { ValueRow("Akkutemperatur", String.format(Locale.GERMANY, "%.0f °C", it)) }
    x.outsideTempC?.let { ValueRow("Außentemperatur", String.format(Locale.GERMANY, "%.0f °C", it)) }
    x.odometerKm?.let { ValueRow("Kilometerstand", String.format(Locale.GERMANY, "%,.0f km", it)) }
    if (x.battery12V != null || x.battery12SocPercent != null) {
        ValueRow(
            "12-V-Batterie",
            listOfNotNull(x.battery12V?.let { String.format(Locale.GERMANY, "%.1f V", it) }, x.battery12SocPercent?.let { Format.percentValue(it) }).joinToString(" · "),
            color = if ((x.battery12V ?: 13.0) < 11.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    x.speedKmh?.takeIf { it > 1 }?.let { ValueRow("Geschwindigkeit", "${it.roundToInt()} km/h") }
    x.ignition?.let { ValueRow("Zündung", it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
    x.alarm?.let { ValueRow("Alarmanlage", it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
    x.oilLifePercent?.let { ValueRow("Öl-Restlebensdauer", Format.percentValue(it)) }

    if (x.tirePressuresKpa.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("Reifendruck", style = MaterialTheme.typography.titleSmall)
        val order = listOf("FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT")
        val wheels = (order.filter { it in x.tirePressuresKpa } + x.tirePressuresKpa.keys.filterNot { it in order })
        wheels.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { wheel ->
                    val kpa = x.tirePressuresKpa[wheel] ?: return@forEach
                    val status = x.tireStatus[wheel]?.uppercase()
                    val bad = status != null && status != "NORMAL" && status != "UNKNOWN"
                    BigValue(
                        String.format(Locale.GERMANY, "%.2f bar", kpa / 100),
                        wheelLabel(wheel) + (if (bad) " · ${status!!.lowercase()}" else ""),
                        if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (x.doors.isNotEmpty() || x.windows.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        val openDoors = x.openDoors
        val openWindows = x.openWindows
        ValueRow(
            "Türen",
            if (openDoors.isEmpty()) "alle geschlossen" else "offen: ${openDoors.joinToString { doorLabel(it) }}",
            color = if (openDoors.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        if (x.windows.isNotEmpty()) {
            ValueRow(
                "Fenster",
                if (openWindows.isEmpty()) "alle geschlossen" else "offen: ${openWindows.joinToString { doorLabel(it) }}",
                color = if (openWindows.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
    }
    if (x.allMetrics.isNotEmpty()) {
        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Alle Werte ausblenden" else "Alle ${x.allMetrics.size} Werte von Ford anzeigen") }
        if (showAll) {
            Text(
                x.allMetrics.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

private fun wheelLabel(w: String): String = when (w.uppercase()) {
    "FRONT_LEFT" -> "vorne links"; "FRONT_RIGHT" -> "vorne rechts"
    "REAR_LEFT" -> "hinten links"; "REAR_RIGHT" -> "hinten rechts"
    else -> w.lowercase().replace('_', ' ')
}

private fun doorLabel(d: String): String = when (d.uppercase()) {
    "FRONT_LEFT" -> "Fahrer"; "FRONT_RIGHT" -> "Beifahrer"
    "REAR_LEFT" -> "hinten links"; "REAR_RIGHT" -> "hinten rechts"
    "TAILGATE", "REAR_TAILGATE" -> "Kofferraum"; "HOOD", "FRONT_HOOD" -> "Motorhaube"
    "INNER_TAILGATE" -> "Heckklappe innen"
    else -> d.lowercase().replace('_', ' ')
}
