package com.jakober.energie.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.theme.EnergyColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// Geometrie, die Canvas und Composables gemeinsam nutzen, damit die Pfade
// genau an den Symbolen enden.
private val NodeRadius = 34.dp
private val EdgeInset = 26.dp
private val HubRadius = 44.dp

/**
 * Das Herzstueck der Uebersicht: PV oben, Haus rechts, Netz unten, Speicher
 * links, alle mit der Mitte verbunden. Fliesst Energie, wandern Leuchtpunkte
 * entlang geschwungener Pfade in Flussrichtung, umso kraeftiger, je mehr
 * Leistung. In der Mitte steht die Autarkie des Augenblicks, um das
 * Speicher-Symbol laeuft der Ladezustand als Ring.
 */
/** Die antippbaren Knoten des Diagramms. */
enum class FlowNodeKind { PV, HOUSE, GRID, BATTERY, CAR }

/** Kleine Anzeige links oben, etwa die PV-Prognose fuer morgen. */
data class ForecastBadge(val label: String, val value: String, val detail: String)

@Composable
fun FlowDiagram(
    sample: EnergySample?,
    showCar: Boolean = false,
    onNodeClick: ((FlowNodeKind) -> Unit)? = null,
    forecast: ForecastBadge? = null,
    modifier: Modifier = Modifier,
) {
    fun click(kind: FlowNodeKind): (() -> Unit)? = onNodeClick?.let { cb -> { cb(kind) } }
    val production = sample?.productionW ?: 0.0
    val carPower = if (showCar) sample?.carChargePowerW ?: 0.0 else 0.0
    // Mit Auto-Knoten zeigt das Haus nur den Rest ohne Ladeleistung.
    val consumption = ((sample?.consumptionW ?: 0.0) - carPower).coerceAtLeast(0.0)
    val grid = sample?.gridPowerW ?: 0.0
    val battery = sample?.batteryPowerW ?: 0.0
    val soc = sample?.batterySocPercent
    val autarky = sample?.selfSufficiency

    val transition = rememberInfiniteTransition(label = "flow")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(if (showCar) 400.dp else 372.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0B1220), Color(0xFF17203B), Color(0xFF2A1F4E)),
                    start = Offset.Zero, end = Offset.Infinite,
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val nodeR = NodeRadius.toPx()
            val inset = EdgeInset.toPx() + nodeR
            val hubR = HubRadius.toPx()
            val top = Offset(c.x, inset)
            val bottom = Offset(c.x, size.height - inset)
            val left = Offset(inset, c.y)
            val sideGap = 62.dp.toPx()
            val right = if (showCar) Offset(size.width - inset, c.y - sideGap) else Offset(size.width - inset, c.y)
            val carPos = Offset(size.width - inset, c.y + sideGap)
            val measure = PathMeasure()

            // Zarte Leuchtflecken im Hintergrund geben der Flaeche Tiefe.
            drawCircle(
                Brush.radialGradient(listOf(EnergyColors.sun.copy(alpha = 0.10f), Color.Transparent), center = top, radius = size.width * 0.45f),
                radius = size.width * 0.45f, center = top,
            )
            drawCircle(
                Brush.radialGradient(listOf(EnergyColors.grid.copy(alpha = 0.10f), Color.Transparent), center = bottom, radius = size.width * 0.45f),
                radius = size.width * 0.45f, center = bottom,
            )

            fun link(node: Offset, watts: Double, color: Color, towardsHub: Boolean, startInset: Float = nodeR) {
                val dir = c - node
                val len = dir.getDistance()
                val unit = dir / len
                val start = node + unit * startInset
                val end = c - unit * hubR
                val mid = (start + end) / 2f
                val normal = Offset(-unit.y, unit.x)
                val ctrl = mid + normal * (len * 0.14f)
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    quadraticBezierTo(ctrl.x, ctrl.y, end.x, end.y)
                }
                val active = abs(watts) >= 15.0
                val strength = min(1f, (abs(watts) / 4000.0).toFloat())
                val width = (2.5f + 4f * strength).dp.toPx()

                if (!active) {
                    drawPath(path, Color.White.copy(alpha = 0.07f), style = Stroke(width, cap = StrokeCap.Round))
                    return
                }
                // Schein, Grundlinie, dann die wandernden Punkte.
                drawPath(path, color.copy(alpha = 0.10f), style = Stroke(width * 3.4f, cap = StrokeCap.Round))
                drawPath(path, color.copy(alpha = 0.32f), style = Stroke(width, cap = StrokeCap.Round))
                measure.setPath(path, false)
                val total = measure.length
                val dots = 4
                for (i in 0 until dots) {
                    var t = (phase + i.toFloat() / dots) % 1f
                    if (!towardsHub) t = 1f - t
                    val p = measure.getPosition(t * total)
                    // An den Enden ausblenden, damit die Punkte weich erscheinen und verschwinden.
                    val fade = sin(t * PI).toFloat().coerceIn(0.15f, 1f)
                    drawCircle(color.copy(alpha = 0.30f * fade), radius = (6f + 4f * strength).dp.toPx(), center = p)
                    drawCircle(color.copy(alpha = fade), radius = (3f + 1.5f * strength).dp.toPx(), center = p)
                }
            }

            // PV liefert immer zur Mitte, das Haus nimmt immer aus der Mitte.
            link(top, production, EnergyColors.sun, towardsHub = true)
            link(right, consumption, EnergyColors.house, towardsHub = false)
            if (showCar) link(carPos, carPower, EnergyColors.car, towardsHub = false)
            // Netz: Bezug zur Mitte, Einspeisung von der Mitte weg.
            if (grid >= 0) link(bottom, grid, EnergyColors.grid, towardsHub = true)
            else link(bottom, grid, EnergyColors.export, towardsHub = false)
            // Speicher: laden von der Mitte weg, entladen zur Mitte hin. Der Pfad
            // beginnt an der rechten Kante des Speicher-Rechtecks.
            val batteryEdge = BatteryWidth.toPx() / 2 + 2.dp.toPx()
            if (battery >= 0) link(left, battery, EnergyColors.battery, towardsHub = false, startInset = batteryEdge)
            else link(left, battery, EnergyColors.battery, towardsHub = true, startInset = batteryEdge)

            // Die Mitte: Glasscheibe mit Autarkie-Ring.
            drawCircle(Color.White.copy(alpha = 0.06f), hubR, c)
            drawCircle(Color.White.copy(alpha = 0.12f), hubR, c, style = Stroke(1.5.dp.toPx()))
            val ringStroke = 5.dp.toPx()
            val ringR = hubR - ringStroke
            val ringTopLeft = Offset(c.x - ringR, c.y - ringR)
            val ringSize = Size(ringR * 2, ringR * 2)
            drawArc(Color.White.copy(alpha = 0.10f), -90f, 360f, false, ringTopLeft, ringSize, style = Stroke(ringStroke, cap = StrokeCap.Round))
            if (autarky != null) {
                drawArc(EnergyColors.battery, -90f, (360f * autarky).toFloat(), false, ringTopLeft, ringSize, style = Stroke(ringStroke, cap = StrokeCap.Round))
            }
        }

        // PV-Prognose links oben, wo der Speicher Platz laesst.
        if (forecast != null) {
            Column(
                Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 16.dp).width(SideNodeWidth + 10.dp)
                    .let { m -> onNodeClick?.let { cb -> m.clip(RoundedCornerShape(12.dp)).clickable { cb(FlowNodeKind.PV) } } ?: m }
                    .padding(4.dp),
            ) {
                Text(forecast.label, style = MaterialTheme.typography.labelSmall, color = EnergyColors.sun.copy(alpha = 0.9f))
                Text(forecast.value, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(forecast.detail, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            }
        }

        // Beschriftung der Mitte
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (autarky != null) Format.percent(autarky) else "–",
                style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold,
            )
            Text("Autarkie", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
        }

        FlowNode(
            Icons.Rounded.WbSunny, EnergyColors.sun, Format.power(production), "PV",
            Modifier.align(Alignment.TopCenter).padding(top = EdgeInset), textBelow = true, onClick = click(FlowNodeKind.PV),
        )
        if (showCar) {
            // Text ueber dem Haus, unter dem Auto - so bleibt zwischen beiden Platz.
            FlowNode(
                Icons.Rounded.Home, EnergyColors.house, Format.power(consumption), "Haus",
                Modifier.align(Alignment.CenterEnd).offset(y = (-86).dp), textBelow = false, onClick = click(FlowNodeKind.HOUSE),
            )
            // Auto: laedt es, steht die Leistung gross; sonst der Ladestand. Kurze
            // Beschriftung, damit nichts in die Nachbarn laeuft.
            val charging = carPower > 15 || sample?.carCharging == true
            val socLabel = sample?.carSocPercent?.let { Format.percentValue(it) }
            FlowNode(
                Icons.Rounded.ElectricCar, EnergyColors.car,
                value = if (charging) Format.power(carPower) else socLabel ?: "–",
                label = when {
                    charging -> "Auto lädt" + (socLabel?.let { " · $it" } ?: "")
                    sample?.carPluggedIn == true -> "Auto steckt, pausiert"
                    sample?.carPluggedIn == false -> "Auto nicht angeschlossen"
                    else -> "Auto"
                },
                Modifier.align(Alignment.CenterEnd).offset(y = 86.dp), textBelow = true,
                ring = sample?.carSocPercent?.let { (it / 100.0).toFloat() },
                onClick = click(FlowNodeKind.CAR),
            )
        } else {
            FlowNode(
                Icons.Rounded.Home, EnergyColors.house, Format.power(consumption), "Haus",
                Modifier.align(Alignment.CenterEnd).offset(y = 26.dp), textBelow = true, onClick = click(FlowNodeKind.HOUSE),
            )
        }
        FlowNode(
            Icons.Rounded.Bolt, if (grid < -15) EnergyColors.export else EnergyColors.grid,
            Format.power(abs(grid)), if (grid < -15) "Einspeisung" else if (grid > 15) "Netzbezug" else "Netz",
            Modifier.align(Alignment.BottomCenter).padding(bottom = EdgeInset), textBelow = false, onClick = click(FlowNodeKind.GRID),
        )
        BatteryNode(
            soc = soc, charging = battery > 15,
            value = Format.power(abs(battery)),
            label = when {
                battery > 15 -> "Speicher lädt"
                battery < -15 -> "Speicher gibt ab"
                else -> "Speicher"
            },
            modifier = Modifier.align(Alignment.CenterStart).offset(y = 26.dp),
            onClick = click(FlowNodeKind.BATTERY),
        )
    }
}

/** Breite eines Seitenknotens: Symbol mittig ueber der Kreismitte des Canvas, Text darf umbrechen. */
private val SideNodeWidth = (EdgeInset + NodeRadius) * 2

private val BatteryWidth = 46.dp
private val BatteryHeight = 88.dp

/**
 * Der Speicher als stehende Zelle: Pol oben, der Koerper fuellt sich von unten
 * mit dem Ladezustand, die Prozentzahl steht darin. Nutzt den Platz links
 * oben, den der Kreis frei liess.
 */
@Composable
private fun BatteryNode(
    soc: Double?,
    charging: Boolean,
    value: String,
    label: String,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val color = EnergyColors.battery
    val fraction = ((soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    Column(modifier.width(SideNodeWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(BatteryWidth + 16.dp, BatteryHeight + 8.dp)
                .let { m -> if (onClick != null) m.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick) else m },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(BatteryWidth, BatteryHeight)) {
                val w = size.width
                val h = size.height
                val capW = w * 0.42f
                val capH = 6.dp.toPx()
                val bodyTop = capH + 2.dp.toPx()
                val r = CornerRadius(9.dp.toPx())
                val bodySize = Size(w, h - bodyTop)
                // Schein
                drawRoundRect(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), center = Offset(w / 2, (bodyTop + h) / 2), radius = h * 0.75f),
                    topLeft = Offset(-10.dp.toPx(), bodyTop - 10.dp.toPx()), size = Size(w + 20.dp.toPx(), bodySize.height + 20.dp.toPx()),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                )
                // Koerper
                drawRoundRect(color.copy(alpha = 0.16f), Offset(0f, bodyTop), bodySize, r)
                // Fuellung von unten, im Koerper beschnitten
                val clipPath = Path().apply { addRoundRect(RoundRect(0f, bodyTop, w, h, r)) }
                clipPath(clipPath) {
                    val fillH = bodySize.height * fraction
                    if (fillH > 0f) {
                        drawRect(
                            Brush.verticalGradient(listOf(color, color.copy(alpha = 0.72f)), startY = h - fillH, endY = h),
                            topLeft = Offset(0f, h - fillH), size = Size(w, fillH),
                        )
                        // Helle Kante am Fuellstand
                        drawRect(Color.White.copy(alpha = 0.35f), topLeft = Offset(0f, h - fillH), size = Size(w, 1.5.dp.toPx()))
                    }
                }
                // Rahmen und Pol
                drawRoundRect(color.copy(alpha = 0.9f), Offset(0f, bodyTop), bodySize, r, style = Stroke(1.5.dp.toPx()))
                drawRoundRect(color.copy(alpha = 0.9f), Offset((w - capW) / 2, 0f), Size(capW, capH), CornerRadius(2.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    soc?.let { Format.percentValue(it) } ?: "–",
                    style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold,
                )
                if (charging) Icon(Icons.Rounded.Bolt, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(14.dp))
            }
        }
        NodeText(value, label)
    }
}

/**
 * Ein Knoten des Diagramms: leuchtender Kreis mit Symbol, darunter (oder
 * darueber) Wert und Beschriftung. `ring` zeichnet einen Fortschrittsring um
 * das Symbol, etwa fuer den Ladezustand.
 */
@Composable
private fun FlowNode(
    icon: ImageVector,
    color: Color,
    value: String,
    label: String,
    modifier: Modifier,
    textBelow: Boolean,
    ring: Float? = null,
    ringLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    // Seitliche Knoten bekommen eine feste Breite, deren Mitte genau auf der
    // Kreismitte des Canvas liegt - lange Beschriftungen brechen um, statt das
    // Symbol zur Seite zu schieben.
    Column(modifier.width(SideNodeWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!textBelow) NodeText(value, label)
        Box(
            Modifier.size(NodeRadius * 2).let { m -> if (onClick != null) m.clip(CircleShape).clickable(onClick = onClick) else m },
            contentAlignment = Alignment.Center,
        ) {
            // Schein
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent)), CircleShape,
                ),
            )
            if (ring != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2 + 1.dp.toPx()
                    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                    drawArc(Color.White.copy(alpha = 0.14f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(color, -90f, 360f * ring.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Box(
                Modifier
                    .size(NodeRadius * 2 - 18.dp)
                    .background(color.copy(alpha = 0.22f), CircleShape)
                    .border(1.5.dp, color.copy(alpha = 0.75f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            }
        }
        if (textBelow) NodeText(value, label, ringLabel)
    }
}

@Composable
private fun NodeText(value: String, label: String, extra: String? = null) {
    Text(
        value, style = MaterialTheme.typography.titleLarge, color = Color.White,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
    )
    Text(
        if (extra != null) "$label · $extra" else label,
        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center,
        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
    )
}
