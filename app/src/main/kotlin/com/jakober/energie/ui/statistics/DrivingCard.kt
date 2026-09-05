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
import java.util.Locale

private fun km(v: Double): String = String.format(Locale.GERMANY, if (v < 100) "%.1f km" else "%,.0f km", v)
private fun kwh100(v: Double?): String = if (v == null) "–" else String.format(Locale.GERMANY, "%.1f kWh/100 km", v)
private fun eur100(v: Double?): String = if (v == null) "–" else String.format(Locale.GERMANY, "%.2f €/100 km", v)

/**
 * Fahrten des Autos: Strecke aus dem Kilometerstand, Energie aus dem Akkuinhalt,
 * Herkunft aus dem Tank-Mix (Sonne, Netz, unterwegs, unbekannt).
 */
@Composable
fun DrivingCard(period: List<DriveDay>, all: List<DriveDay>, settings: Settings, range: Range) {
    val sum = DriveDay.sum(period)
    val life = DriveDay.sum(all)
    val price = settings.pricePerKwh
    val pub = settings.carPublicPricePerKwh
    var expanded by rememberSaveable { mutableStateOf(false) }

    EnergieCard(title = "Fahrten", accent = EnergyColors.car) {
        if (sum == null || (sum.drivenKm < 0.5 && sum.usedWh < 100)) {
            Text("Im gewählten Zeitraum keine Fahrt erkannt.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigValue(km(sum.drivenKm), "Gefahren", EnergyColors.car, Modifier.weight(1f))
                BigValue(Format.energy(sum.usedWh), "Verbraucht", EnergyColors.car, Modifier.weight(1f))
                BigValue(Format.euro(sum.costEur(price, pub)), "Bezahlt", EnergyColors.grid, Modifier.weight(1f))
            }
            MixBar(sum)
            ValueRow("Verbrauch", kwh100(sum.kwhPer100Km))
            ValueRow("Kosten je 100 km", eur100(sum.costPer100Km(price, pub)), detail = "Netzstrom ${Format.euro(price)} · unterwegs ${Format.euro(pub)} je kWh")
            ValueRow("Sonnenstrom", Format.energy(sum.used.solarWh), detail = "entgangene Einspeisung ${Format.euro(sum.solarValueEur(settings.feedInPerKwh))}", color = EnergyColors.sun)
            if (sum.used.gridWh > 50) ValueRow("Netzstrom von zu Hause", Format.energy(sum.used.gridWh), detail = Format.euro(sum.used.gridWh / 1000 * price), color = EnergyColors.grid)
            if (sum.used.publicWh > 50) ValueRow("Unterwegs geladen", Format.energy(sum.used.publicWh), detail = Format.euro(sum.used.publicWh / 1000 * pub), color = EnergyColors.house)
            if (sum.used.unknownWh > 50) ValueRow("Herkunft unbekannt", Format.energy(sum.used.unknownWh), detail = "war schon im Akku, bevor die App mitzählte", color = EnergyColors.neutral)
            if (sum.startKm != null && sum.endKm != null) {
                ValueRow("Kilometerstand", String.format(Locale.GERMANY, "%,.0f → %,.0f km", sum.startKm, sum.endKm))
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
            Text(
                "Seit Beginn: ${km(life.drivenKm)} · ${Format.energy(life.usedWh)} · ${Format.euro(life.costEur(price, pub))} bezahlt · ${kwh100(life.kwhPer100Km)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Kilometer und Akkuinhalt meldet Ford alle paar Minuten. Der Akku zählt als Tank: Laden zu Hause füllt ihn mit dem Sonnen-/Netzmix des Moments, Fahren entnimmt anteilig.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MixBar(d: DriveDay) {
    val total = d.usedWh
    if (total <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            val parts = listOf(d.used.solarWh to EnergyColors.sun, d.used.gridWh to EnergyColors.grid, d.used.publicWh to EnergyColors.house, d.used.unknownWh to EnergyColors.neutral)
            parts.forEach { (wh, color) ->
                val f = (wh / total).toFloat()
                if (f > 0.005f) Box(Modifier.weight(f).height(10.dp).background(color))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(EnergyColors.sun, "Sonne ${Format.percent(d.solarShare)}")
            LegendItem(EnergyColors.grid, "Netz ${Format.percent(d.used.gridWh / total)}")
            if (d.used.publicWh > 50) LegendItem(EnergyColors.house, "Unterwegs ${Format.percent(d.used.publicWh / total)}")
            if (d.used.unknownWh > 50) LegendItem(EnergyColors.neutral, "Unbekannt ${Format.percent(d.unknownShare)}")
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
