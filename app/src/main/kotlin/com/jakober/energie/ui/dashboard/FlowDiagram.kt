package com.jakober.energie.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.IconBubble
import com.jakober.energie.ui.theme.EnergyColors
import kotlin.math.abs
import kotlin.math.min

/**
 * Das Herzstueck der Uebersicht: PV oben, Haus rechts, Netz unten, Speicher
 * links, alle mit der Mitte verbunden. Fliesst Energie, wandern Punkte in
 * Flussrichtung, umso dicker, je mehr Leistung.
 */
@Composable
fun FlowDiagram(sample: EnergySample?, modifier: Modifier = Modifier) {
    val production = sample?.productionW ?: 0.0
    val consumption = sample?.consumptionW ?: 0.0
    val grid = sample?.senecGridPowerW ?: sample?.meterGridPowerW ?: 0.0
    val battery = sample?.batteryPowerW ?: 0.0

    val transition = rememberInfiniteTransition(label = "flow")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val idle = MaterialTheme.colorScheme.outlineVariant
    val hub = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(modifier.fillMaxWidth().height(300.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val nodeGap = 52.dp.toPx()
            val top = Offset(c.x, nodeGap)
            val bottom = Offset(c.x, size.height - nodeGap)
            val left = Offset(nodeGap + 8.dp.toPx(), c.y)
            val right = Offset(size.width - nodeGap - 8.dp.toPx(), c.y)
            val dash = 14.dp.toPx()

            fun flow(from: Offset, to: Offset, watts: Double, color: Color) {
                val active = abs(watts) >= 15.0
                val width = if (active) (2.dp + min(6f, (abs(watts) / 1000.0).toFloat() * 2f).dp).toPx() else 2.dp.toPx()
                if (!active) {
                    drawLine(idle, from, to, strokeWidth = width, cap = StrokeCap.Round)
                    return
                }
                drawLine(color.copy(alpha = 0.25f), from, to, strokeWidth = width, cap = StrokeCap.Round)
                val effect = PathEffect.dashPathEffect(floatArrayOf(dash * 0.45f, dash * 0.55f), -phase * dash)
                drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round, pathEffect = effect)
            }

            // PV liefert immer zur Mitte.
            flow(top, c, production, EnergyColors.sun)
            // Haus nimmt immer aus der Mitte.
            flow(c, right, consumption, EnergyColors.house)
            // Netz: Bezug vom Netz zur Mitte, Einspeisung von der Mitte zum Netz.
            if (grid >= 0) flow(bottom, c, grid, EnergyColors.grid) else flow(c, bottom, grid, EnergyColors.export)
            // Speicher: laden von der Mitte zum Speicher, entladen umgekehrt.
            if (battery >= 0) flow(c, left, battery, EnergyColors.battery) else flow(left, c, battery, EnergyColors.battery)

            drawCircle(hub, radius = 10.dp.toPx(), center = c)
        }

        Node(Icons.Rounded.WbSunny, EnergyColors.sun, Format.power(production), "PV", Modifier.align(Alignment.TopCenter))
        Node(Icons.Rounded.Home, EnergyColors.house, Format.power(consumption), "Haus", Modifier.align(Alignment.CenterEnd))
        Node(
            Icons.Rounded.Bolt, if (grid < 0) EnergyColors.export else EnergyColors.grid,
            Format.power(abs(grid)), if (grid < -15) "Einspeisung" else "Netz", Modifier.align(Alignment.BottomCenter),
        )
        Node(
            Icons.Rounded.BatteryChargingFull, EnergyColors.battery, Format.power(abs(battery)),
            when {
                battery > 15 -> "Speicher lädt"
                battery < -15 -> "Speicher gibt ab"
                else -> "Speicher"
            },
            Modifier.align(Alignment.CenterStart),
        )
    }
}

@Composable
private fun Node(icon: ImageVector, color: Color, value: String, label: String, modifier: Modifier) {
    Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBubble(icon, color)
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
