package com.jakober.energie.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.jakober.energie.core.history.DriveDay
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.BigValue
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.LegendItem
import com.jakober.energie.ui.Range
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.theme.EnergyColors

private fun km(v: Double): String = if (v < 100) "${Format.number(v, 1)} km" else "${Format.number(v, 0, grouping = true)} km"
private fun kwh100(v: Double?): String = if (v == null) "–" else "${Format.number((v).toDouble(), 1)} kWh/100 km"
private fun eur100(v: Double?): String = if (v == null) "–" else "${Format.number((v).toDouble(), 2)} €/100 km"

/**
 * Fahrten des Autos: Strecke aus dem Kilometerstand, Energie aus dem Akkuinhalt,
 * Herkunft aus dem Tank-Mix (Sonne, Netz, unterwegs, unbekannt).
 */
@Composable
fun DrivingCard(period: List<DriveDay>, all: List<DriveDay>, settings: Settings, range: Range, periodLabel: String) {
    val sum = DriveDay.sum(period)
    val life = DriveDay.sum(all)
    val price = settings.pricePerKwh
    val pub = settings.carPublicPricePerKwh
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Die Gesamtzeile aendert sich mit dem Zeitraum nicht; eingeklappt steht sie nicht
    // mehr unter den Zahlen des Zeitraums und wird nicht mehr mit ihnen verwechselt.
    var showLifetime by rememberSaveable { mutableStateOf(false) }

    EnergieCard(title = "Fahrten · $periodLabel", accent = EnergyColors.car) {
        if (sum == null || (sum.drivenKm < 0.5 && sum.usedWh < 100)) {
            Text("Im gewählten Zeitraum keine Fahrt erkannt.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigValue(km(sum.drivenKm), "Gefahren", EnergyColors.car, Modifier.weight(1f))
                BigValue(Format.energy(sum.drivingWh), "Fürs Fahren", EnergyColors.car, Modifier.weight(1f))
                BigValue(Format.euro(sum.costEur(price, pub)), "Bezahlt", EnergyColors.grid, Modifier.weight(1f))
            }
            MixBar(sum)
            ValueRow("Verbrauch", kwh100(sum.kwhPer100Km))
            ValueRow("Kosten je 100 km", eur100(sum.costPer100Km(price, pub)), detail = "Netzstrom ${Format.euro(price)} · unterwegs ${Format.euro(pub)} je kWh")
            val d = sum.driving
            ValueRow("Sonnenstrom", Format.energy(d.solarWh), detail = "entgangene Einspeisung ${Format.euro(sum.solarValueEur(settings.feedInPerKwh))}", color = EnergyColors.sun)
            if (d.gridWh > 50) ValueRow("Netzstrom von zu Hause", Format.energy(d.gridWh), detail = Format.euro(d.gridWh / 1000 * price), color = EnergyColors.grid)
            if (d.publicWh > 50) ValueRow("Unterwegs geladen", Format.energy(d.publicWh), detail = Format.euro(d.publicWh / 1000 * pub), color = EnergyColors.house)
            if (d.unknownWh > 50) ValueRow("Herkunft unbekannt", Format.energy(d.unknownWh), detail = "war schon im Akku, bevor die App mitzählte", color = EnergyColors.neutral)
            if (sum.regenWh > 100) {
                ValueRow(
                    "Zurückgewonnen", Format.energy(sum.regenWh),
                    detail = "Rekuperation beim Bremsen und bergab, oben schon abgezogen",
                    color = EnergyColors.battery,
                )
            }
            if (sum.standingWh > 100) {
                Text("Daneben, ohne gefahrene Kilometer", style = MaterialTheme.typography.titleSmall)
                ValueRow(
                    "Im Stand verbraucht", Format.energy(sum.standingWh),
                    detail = "Vorklimatisieren, Bordnetz, Selbstentladung" +
                        (sum.standingCostEur(price, pub).takeIf { it >= 0.01 }?.let { " · ${Format.euro(it)}" } ?: ""),
                    color = EnergyColors.neutral,
                )
                ValueRow("Aus dem Akku gesamt", Format.energy(sum.usedWh), detail = "Fahren und Stand zusammen")
            }
            val startKm = sum.startKm
            val endKm = sum.endKm
            if (startKm != null && endKm != null) {
                ValueRow("Kilometerstand", "${Format.number(startKm, 0, grouping = true)} → ${Format.number(endKm, 0, grouping = true)} km")
            }
        }

        if (range != Range.DAY && period.count { it.drivenKm >= 0.5 } > 1) {
            val rows = period.filter { it.drivenKm >= 0.5 || it.usedWh >= 100 }.sortedByDescending { it.date }
            val shown = if (expanded) rows else rows.take(7)
            Text("Tage", style = MaterialTheme.typography.titleSmall)
            shown.forEach { d -> DriveDayRow(d, price, pub) }
            if (rows.size > 7) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Weniger anzeigen" else "Alle ${rows.size} Tage anzeigen") }
            }
        }

        if (life != null && life.drivenKm >= 1) {
            TextButton(onClick = { showLifetime = !showLifetime }) {
                Text(if (showLifetime) "Gesamtstrecke ausblenden" else "Seit Beginn der Aufzeichnung anzeigen")
            }
            if (showLifetime) {
                Text(
                    "Seit Beginn, unabhängig vom Zeitraum: ${km(life.drivenKm)} · ${Format.energy(life.usedWh)} · " +
                        "${Format.euro(life.costEur(price, pub))} bezahlt · ${kwh100(life.kwhPer100Km)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Kilometer und Akkuinhalt meldet Ford alle paar Minuten. Der Akku zählt als Tank: Laden zu Hause füllt ihn mit dem Sonnen-/Netzmix des Moments, Fahren entnimmt anteilig.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MixBar(d: DriveDay) {
    val mix = d.driving
    val total = mix.totalWh
    if (total <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            val parts = listOf(mix.solarWh to EnergyColors.sun, mix.gridWh to EnergyColors.grid, mix.publicWh to EnergyColors.house, mix.unknownWh to EnergyColors.neutral)
            parts.forEach { (wh, color) ->
                val f = (wh / total).toFloat()
                if (f > 0.005f) Box(Modifier.weight(f).height(10.dp).background(color))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(EnergyColors.sun, "Sonne ${Format.percent(d.solarShare)}")
            LegendItem(EnergyColors.grid, "Netz ${Format.percent(mix.gridWh / total)}")
            if (mix.publicWh > 50) LegendItem(EnergyColors.house, "Unterwegs ${Format.percent(mix.publicWh / total)}")
            if (mix.unknownWh > 50) LegendItem(EnergyColors.neutral, "Unbekannt ${Format.percent(d.unknownShare)}")
        }
    }
}

@Composable
private fun DriveDayRow(d: DriveDay, price: Double, pub: Double) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Format.dateShort(d.date), style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(kwh100(d.kwhPer100Km).takeIf { d.kwhPer100Km != null }, d.solarShare?.let { "Sonne ${Format.percent(it)}" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(km(d.drivenKm), style = MaterialTheme.typography.titleMedium, color = EnergyColors.car)
            Text("${Format.energy(d.usedWh)} · ${Format.euro(d.costEur(price, pub))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
