package com.jakober.energie.ui.statistics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Range
import com.jakober.energie.ui.TwoPane

/**
 * Nur das Auto: Laden, Ladevorgaenge und Fahrten im selben Zeitraum, den die
 * Statistikseite kennt. Die Auswahl ist dieselbe Einstellung, ein Wechsel hier
 * gilt also auch dort - das ist Absicht, zwei getrennte Zeitraeume wuerden beim
 * Hin- und Herspringen nur verwirren.
 */
@Composable
fun CarContent(data: StatisticsData, actions: StatisticsActions, contentPadding: PaddingValues) {
    val settings = data.settings
    val period = if (data.range == Range.DAY) data.day?.totals else data.rangeStats?.totals
    val label = periodLabelOf(data.date, data.range)
    val (from, to) = Range.bounds(data.date, data.range)
    val drivingInPeriod = data.driving.filter { it.date in from..to }

    val left: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item { Text("Auto", style = MaterialTheme.typography.displaySmall) }
        item { RangePicker(data.range, actions) }
        item { PeriodNav(label, data.isToday, actions) }
    }

    val right: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item { CarStatsCard(period, data.lifetime, settings, data.sessions.size, label) }
        if (data.sessions.isNotEmpty()) {
            item { ChargeSessionsCard(data.sessions, settings) }
        }
        if (data.driving.isNotEmpty()) {
            item { DrivingCard(drivingInPeriod, data.driving, settings, data.range, label) }
        }
        if (data.sessions.isEmpty() && drivingInPeriod.isEmpty() && (period?.carChargeWh ?: 0.0) <= 50) {
            item {
                EnergieCard(title = "Nichts zu zeigen") {
                    Text(
                        "Für $label liegen weder Ladevorgänge noch Fahrten vor.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    TwoPane(contentPadding, left, right)
}
