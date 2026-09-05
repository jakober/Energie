package com.jakober.energie.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.jakober.energie.MainActivity
import com.jakober.energie.ui.Format
import kotlinx.datetime.Clock

/**
 * Homescreen-Widget: Speicher, PV, Netz, Haus und Auto auf einen Blick, im
 * dunklen Look der App. Zwei Groessen: 2x2 (zwei Kacheln je Zeile) und 4x2
 * (eine Zeile mit allen Werten plus Auto).
 */
class EnergieWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetState.load(context)
        provideContent { Content(state) }
    }

    @Composable
    private fun Content(s: WidgetState?) {
        val wide = LocalSize.current.width >= WIDE.width
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ColorProvider(Bg))
                .cornerRadius(20.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Top,
        ) {
            if (s == null) {
                Text("Energie", style = TextStyle(color = ColorProvider(Fg), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(4.dp))
                Text("Noch keine Messung. App einmal öffnen.", style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp))
                return@Column
            }
            val soc = s.batterySocPercent
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Speicher", style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp))
                Spacer(GlanceModifier.defaultWeight())
                Text(Format.percentValue(soc), style = TextStyle(color = ColorProvider(Battery), fontSize = 18.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = ((soc ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = ColorProvider(Battery),
                backgroundColor = ColorProvider(Track),
            )
            Spacer(GlanceModifier.height(8.dp))
            if (wide) {
                Row(GlanceModifier.fillMaxWidth()) {
                    Tile("PV", Format.power(s.productionW), Sun, GlanceModifier.defaultWeight())
                    Tile("Haus", Format.power(s.householdW), House, GlanceModifier.defaultWeight())
                    Tile(gridLabel(s.gridPowerW), Format.power(s.gridPowerW?.let { kotlin.math.abs(it) }), Grid, GlanceModifier.defaultWeight())
                    Tile("Auto", s.carSocPercent?.let { Format.percentValue(it) } ?: "–", Car, GlanceModifier.defaultWeight(), s.carLabel)
                }
            } else {
                Row(GlanceModifier.fillMaxWidth()) {
                    Tile("PV", Format.power(s.productionW), Sun, GlanceModifier.defaultWeight())
                    Tile(gridLabel(s.gridPowerW), Format.power(s.gridPowerW?.let { kotlin.math.abs(it) }), Grid, GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    Tile("Haus", Format.power(s.householdW), House, GlanceModifier.defaultWeight())
                    Tile("Auto", s.carSocPercent?.let { Format.percentValue(it) } ?: "–", Car, GlanceModifier.defaultWeight(), s.carLabel)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(Format.ago(s.at, Clock.System.now()), style = TextStyle(color = ColorProvider(Muted), fontSize = 10.sp))
        }
    }

    @Composable
    private fun Tile(label: String, value: String, color: Color, modifier: GlanceModifier, detail: String? = null) {
        Column(modifier) {
            Text(label, style = TextStyle(color = ColorProvider(Muted), fontSize = 10.sp), maxLines = 1)
            Text(value, style = TextStyle(color = ColorProvider(color), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (detail != null) Text(detail, style = TextStyle(color = ColorProvider(Muted), fontSize = 9.sp), maxLines = 1)
        }
    }

    private fun gridLabel(grid: Double?): String = when {
        grid == null -> "Netz"
        grid < -20 -> "Einspeisung"
        else -> "Bezug"
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)

        // Farben wie in der App (EnergyColors, dunkles Schema), hier ohne Compose-Theme.
        private val Bg = Color(0xFF141C2E)
        private val Fg = Color(0xFFE5E9F0)
        private val Muted = Color(0xFFB4BCCB)
        private val Track = Color(0xFF212C44)
        private val Sun = Color(0xFFFACC15)
        private val Battery = Color(0xFF34D399)
        private val Grid = Color(0xFF38BDF8)
        private val House = Color(0xFFFB923C)
        private val Car = Color(0xFFA78BFA)
    }
}

class EnergieWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EnergieWidget()
}
