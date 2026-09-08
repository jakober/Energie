package com.jakober.energie.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.plugs.PlugDevice
import com.jakober.energie.core.plugs.PlugReading
import com.jakober.energie.core.plugs.PlugTotals
import com.jakober.energie.data.CloudRole
import com.jakober.energie.data.LiveState
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.theme.EnergyColors

/** Feste Farben je Stecker, nach Reihenfolge in den Einstellungen, damit sie stabil bleiben. */
private val PlugPalette = listOf(
    Color(0xFFF472B6), Color(0xFF2DD4BF), Color(0xFFFBBF24), Color(0xFF818CF8),
    Color(0xFFF87171), Color(0xFF4ADE80), Color(0xFF22D3EE), Color(0xFFC084FC),
    Color(0xFFFB7185), Color(0xFFA3E635), Color(0xFF38BDF8), Color(0xFFE879F9),
)

fun plugColor(index: Int): Color = PlugPalette[index % PlugPalette.size]

/** Ein Anteil im Stapelbalken. */
data class BarSegment(val color: Color, val value: Double)

/**
 * Ein breiter Balken, der `total` darstellt und die Segmente von links nach rechts stapelt.
 * Was die Segmente nicht abdecken, bleibt als Rest in der Spurfarbe sichtbar.
 */
@Composable
fun StackedBar(segments: List<BarSegment>, total: Double?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp))) {
        drawRect(track)
        val t = total ?: 0.0
        if (t <= 0) return@Canvas
        val scale = size.width / t
        var x = 0f
        segments.filter { it.value > 0 }.forEach { seg ->
            val w = (seg.value * scale).toFloat().coerceAtMost(size.width - x)
            if (w <= 0f) return@forEach
            drawRect(seg.color, Offset(x, 0f), Size(w, size.height))
            x += w
            // schmale Trennlinie, damit sich Nachbarn abheben
            drawRect(track, Offset(x - 1f, 0f), Size(2f, size.height))
        }
    }
}

private class PlugRow(val device: PlugDevice, val color: Color, val reading: PlugReading?, val day: PlugTotals?) {
    val nowW: Double? get() = reading?.powerW
    val dayWh: Double? get() = day?.energyWh
}

/**
 * Abschnitt "Was gerade Strom zieht": Stapelbalken fuer den Moment und fuer den Tag,
 * darunter jede Steckdose mit Farbe, Leistung, Anteil und Tagesenergie.
 */
@Composable
fun PlugBreakdown(live: LiveState, today: DayStatistics?, settings: Settings) {
    val s = live.sample
    val household = s?.householdW
    val carW = s?.carChargePowerW ?: 0.0
    val readings = s?.plugs.orEmpty()
    val rows = settings.plugs.mapIndexed { i, d -> PlugRow(d, plugColor(i), readings[d.id], today?.plugs?.get(d.id)) }
    val measuredW = rows.sumOf { it.nowW ?: 0.0 }
    val restW = household?.let { (it - measuredW - carW).coerceAtLeast(0.0) }

    val dayTotal = today?.totals?.consumptionWh
    val dayCar = today?.totals?.carChargeWh ?: 0.0
    val dayMeasured = rows.sumOf { it.dayWh ?: 0.0 }
    val dayRest = dayTotal?.let { (it - dayMeasured - dayCar).coerceAtLeast(0.0) }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val byPower = rows.sortedByDescending { it.nowW ?: -1.0 }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Was gerade Strom zieht", style = MaterialTheme.typography.titleSmall)

        // Jetzt
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Jetzt", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
            Text(Format.power(household), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = EnergyColors.house)
        }
        StackedBar(
            segments = byPower.map { BarSegment(it.color, it.nowW ?: 0.0) } + BarSegment(EnergyColors.car, carW),
            total = household,
        )

        // Heute
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Heute", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
            Text(Format.energy(dayTotal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = EnergyColors.house)
        }
        StackedBar(
            segments = byPower.map { BarSegment(it.color, it.dayWh ?: 0.0) } + BarSegment(EnergyColors.car, dayCar),
            total = dayTotal,
        )

        // Einzelne Verbraucher
        byPower.forEach { r ->
            val status = when {
                r.reading != null -> null
                live.plugErrors.containsKey(r.device.id) -> "nicht erreichbar"
                settings.cloudRole == CloudRole.VIEWER -> "wartet auf Zentrale"
                else -> "keine Messung"
            }
            BreakdownRow(
                color = r.color,
                name = r.device.name,
                value = status ?: (if (r.reading?.on == false && (r.nowW ?: 0.0) < 1) "aus" else Format.power(r.nowW)),
                valueColor = if (status != null) muted else MaterialTheme.colorScheme.onSurface,
                detail = listOfNotNull(
                    share(r.nowW, household)?.let { "$it jetzt" },
                    r.dayWh?.let { "heute ${Format.energy(it)}" + (share(it, dayTotal)?.let { p -> " ($p)" } ?: "") },
                ).joinToString(" · ").ifBlank { null },
            )
        }
        if (carW > 0 || dayCar > 50) {
            BreakdownRow(
                color = EnergyColors.car, name = "Auto lädt",
                value = if (carW > 0) Format.power(carW) else "aus",
                valueColor = if (carW > 0) MaterialTheme.colorScheme.onSurface else muted,
                detail = listOfNotNull(
                    share(carW.takeIf { it > 0 }, household)?.let { "$it jetzt" },
                    dayCar.takeIf { it > 50 }?.let { "heute ${Format.energy(it)}" + (share(it, dayTotal)?.let { p -> " ($p)" } ?: "") },
                ).joinToString(" · ").ifBlank { null },
            )
        }
        BreakdownRow(
            color = MaterialTheme.colorScheme.surfaceContainerHighest, name = "Übrige Verbraucher",
            value = Format.power(restW), valueColor = muted,
            detail = listOfNotNull(
                share(restW, household)?.let { "$it jetzt" },
                dayRest?.let { "heute ${Format.energy(it)}" + (share(it, dayTotal)?.let { p -> " ($p)" } ?: "") },
                "nicht einzeln gemessen",
            ).joinToString(" · "),
        )
    }
}

private fun share(part: Double?, total: Double?): String? {
    if (part == null || total == null || total <= 0) return null
    return Format.percent((part / total).coerceIn(0.0, 1.0))
}

@Composable
private fun BreakdownRow(color: Color, name: String, value: String, valueColor: Color, detail: String?) {
    // Name und Zusatz links untereinander, der Wert allein rechts: so bleibt der Name immer lesbar.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1)
    }
}
