package com.jakober.energie.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** Eine Reihe in einem Balkendiagramm: Werte je Kategorie, eine Farbe. */
data class BarSeries(val label: String, val color: Color, val values: List<Double>)

/**
 * Balkendiagramm mit mehreren Reihen nebeneinander je Kategorie, z. B.
 * Erzeugung und Verbrauch je Stunde. Alle Reihen muessen gleich lang sein.
 */
@Composable
fun GroupedBarChart(
    categories: List<String>,
    series: List<BarSeries>,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    highlightIndex: Int? = null,
    valueFormatter: (Double) -> String = { "%.0f".format(it) },
) {
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val progress by animateFloatAsState(1f, animationSpec = tween(600), label = "bars")
    val n = categories.size
    val maxValue = series.flatMap { it.values }.maxOrNull()?.takeIf { it > 0 } ?: 1.0

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val labelHeight = 14.sp.toPx()
            val axisWidth = 0f
            val chartHeight = size.height - labelHeight - 6.dp.toPx()
            val slot = (size.width - axisWidth) / max(n, 1)
            val gap = slot * 0.18f
            val barWidth = (slot - gap) / max(series.size, 1)
            val style = TextStyle(color = labelColor, fontSize = 10.sp)

            // Hilfslinien bei 1/3 und 2/3 der Hoehe
            for (f in listOf(1f / 3f, 2f / 3f, 1f)) {
                val y = chartHeight * (1 - f)
                drawLine(gridColor, Offset(axisWidth, y), Offset(size.width, y), strokeWidth = 1f)
            }
            // Beschriftung des Maximums oben links
            drawText(textMeasurer, valueFormatter(maxValue), Offset(axisWidth + 2.dp.toPx(), 0f), style)

            categories.forEachIndexed { i, cat ->
                val x0 = axisWidth + i * slot + gap / 2
                if (highlightIndex == i) {
                    drawRoundRect(
                        labelColor.copy(alpha = 0.10f), Offset(x0 - gap / 2, 0f), Size(slot, chartHeight),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                series.forEachIndexed { si, s ->
                    val v = s.values.getOrElse(i) { 0.0 }
                    val h = (v / maxValue * chartHeight * progress).toFloat()
                    if (h > 0f) {
                        drawRoundRect(
                            s.color, Offset(x0 + si * barWidth, chartHeight - h), Size(barWidth * 0.9f, h),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                }
                if (i % labelEvery == 0) {
                    val measured = textMeasurer.measure(cat, style)
                    drawText(measured, topLeft = Offset(x0 + (slot - gap) / 2 - measured.size.width / 2, chartHeight + 4.dp.toPx()))
                }
            }
        }
    }
}

/** Eine Linie mit optionaler Flaechenfuellung; `null` unterbricht die Linie. */
data class LineSeries(val label: String, val color: Color, val values: List<Double?>, val fill: Boolean = false)

/**
 * Liniendiagramm ueber gleich verteilte x-Positionen (z. B. Messpunkte eines
 * Tages). y-Bereich von `min` bis `max`, standardmaessig aus den Daten.
 */
@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    min: Double? = null,
    max: Double? = null,
    xLabels: List<Pair<Float, String>> = emptyList(),
    zeroLine: Boolean = false,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val all = series.flatMap { it.values.filterNotNull() }
    val lo = min ?: all.minOrNull()?.coerceAtMost(0.0) ?: 0.0
    val hi = (max ?: all.maxOrNull() ?: 1.0).let { if (it <= lo) lo + 1.0 else it }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val labelHeight = if (xLabels.isEmpty()) 0f else 14.sp.toPx() + 4.dp.toPx()
            val h = size.height - labelHeight
            val w = size.width
            fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
            val style = TextStyle(color = labelColor, fontSize = 10.sp)

            for (f in listOf(0.25f, 0.5f, 0.75f)) {
                val yy = h * f
                drawLine(gridColor, Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
            }
            if (zeroLine && lo < 0 && hi > 0) {
                drawLine(labelColor.copy(alpha = 0.6f), Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 1.5f)
            }

            series.forEach { s ->
                val n = s.values.size
                if (n < 2) return@forEach
                val step = w / (n - 1)
                val path = Path()
                var open = false
                var fillPath: Path? = null
                var segStartX = 0f
                s.values.forEachIndexed { i, v ->
                    val x = i * step
                    if (v == null) {
                        if (open && s.fill && fillPath != null) {
                            fillPath!!.lineTo((i - 1) * step, y(0.0.coerceIn(lo, hi)))
                            fillPath!!.lineTo(segStartX, y(0.0.coerceIn(lo, hi)))
                            fillPath!!.close()
                            drawPath(fillPath!!, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.02f))))
                        }
                        open = false
                        fillPath = null
                        return@forEachIndexed
                    }
                    if (!open) {
                        path.moveTo(x, y(v))
                        if (s.fill) fillPath = Path().apply { moveTo(x, y(0.0.coerceIn(lo, hi))); lineTo(x, y(v)) }
                        segStartX = x
                        open = true
                    } else {
                        path.lineTo(x, y(v))
                        fillPath?.lineTo(x, y(v))
                    }
                }
                if (open && s.fill && fillPath != null) {
                    fillPath!!.lineTo((n - 1) * step, y(0.0.coerceIn(lo, hi)))
                    fillPath!!.close()
                    drawPath(fillPath!!, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.02f))))
                }
                drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            }

            xLabels.forEach { (fraction, text) ->
                val measured = textMeasurer.measure(text, style)
                val x = (w * fraction - measured.size.width / 2).coerceIn(0f, w - measured.size.width)
                drawText(measured, topLeft = Offset(x, h + 4.dp.toPx()))
            }
        }
    }
}

/**
 * Ringanzeige, etwa fuer den Ladezustand. `fraction` 0..1.
 */
@Composable
fun RingGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: androidx.compose.ui.unit.Dp = 14.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = tween(700), label = "ring")
    Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}
