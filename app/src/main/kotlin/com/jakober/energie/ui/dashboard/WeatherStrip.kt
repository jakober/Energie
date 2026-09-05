package com.jakober.energie.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.forecast.PvForecastDay
import com.jakober.energie.core.forecast.WeatherClass
import com.jakober.energie.data.LiveState
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.theme.EnergyColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val weekday = DateTimeFormatter.ofPattern("EE", Locale.GERMANY)

/**
 * Wochenstreifen unter dem Flussdiagramm: je Tag Wettersymbol, Hoechsttemperatur
 * und der erwartete Ertrag vom Dach. Tippen oeffnet die PV-Detailkarte.
 */
@Composable
fun WeatherStrip(live: LiveState, settings: Settings, today: LocalDate, onClick: () -> Unit) {
    val forecast = settings.pvForecast ?: return
    val days = forecast.days.filter { it.date >= today }.sortedBy { it.date }.take(7)
    if (days.isEmpty()) return
    val peak = pvPeakKw(settings, live.pvPeakEstimateKw)
    val energies = days.map { d -> peak?.let { d.energyFor(settings, it) } }
    val maxKwh = energies.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0

    EnergieCard(modifier = Modifier.clickable(onClick = onClick), title = "Wetter und Ertrag, ${days.size} Tage", accent = EnergyColors.sun) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEachIndexed { i, d ->
                DayCell(d, today, energies[i], maxKwh, Modifier.weight(1f))
            }
        }
        if (peak == null) {
            Text(
                "Ertrag fehlt: Anlagenleistung unter Einstellungen → PV-Prognose in kWp eintragen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        } else {
            val total = energies.filterNotNull().sum()
            Text(
                "Summe ≈ ${Format.energy(total * 1000)} · Quelle Open-Meteo, Stand ${Format.time(kotlinx.datetime.Instant.fromEpochSeconds(forecast.fetchedAtEpochSeconds))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayCell(d: PvForecastDay, today: LocalDate, kwh: Double?, maxKwh: Double, modifier: Modifier) {
    val offset = d.date.toEpochDays() - today.toEpochDays()
    val name = when (offset) { 0 -> "Heute"; 1 -> "Morgen"; else -> weekday.format(d.date.toJavaLocalDate()).trimEnd('.') }
    val cls = d.weatherCode?.let { PvForecastDay.weatherClass(it) }
    val (icon, tint) = weatherIcon(cls)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            name, style = MaterialTheme.typography.labelSmall,
            color = if (offset == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, textAlign = TextAlign.Center,
        )
        Icon(icon, contentDescription = d.weatherLabel, tint = tint, modifier = Modifier.size(26.dp))
        Text(
            d.tempMaxC?.let { "${it.roundToInt()}°" } ?: "–",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            kwh?.let { String.format(Locale.GERMANY, "%.0f", it) } ?: "–",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
        )
        Text("kWh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Balken relativ zum besten Tag der Woche, damit man den Verlauf auf einen Blick sieht.
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val share = ((kwh ?: 0.0) / maxKwh).coerceIn(0.0, 1.0).toFloat()
            if (share > 0f) Box(Modifier.fillMaxWidth(share).height(5.dp).background(EnergyColors.sun))
        }
    }
}

private fun weatherIcon(cls: WeatherClass?): Pair<ImageVector, Color> = when (cls) {
    WeatherClass.SUN -> Icons.Rounded.WbSunny to EnergyColors.sun
    WeatherClass.PARTLY -> Icons.Rounded.WbCloudy to EnergyColors.sun.copy(alpha = 0.85f)
    WeatherClass.CLOUDS -> Icons.Rounded.Cloud to EnergyColors.neutral
    WeatherClass.FOG -> Icons.Rounded.Cloud to EnergyColors.neutral.copy(alpha = 0.6f)
    WeatherClass.DRIZZLE -> Icons.Rounded.Grain to EnergyColors.grid
    WeatherClass.RAIN -> Icons.Rounded.WaterDrop to EnergyColors.grid
    WeatherClass.SNOW -> Icons.Rounded.AcUnit to EnergyColors.export
    WeatherClass.THUNDER -> Icons.Rounded.Thunderstorm to EnergyColors.car
    null -> Icons.Rounded.Cloud to EnergyColors.neutral
}
