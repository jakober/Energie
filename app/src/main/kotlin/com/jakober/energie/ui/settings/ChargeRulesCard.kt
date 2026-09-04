package com.jakober.energie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.rules.ChargeRules
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.theme.EnergyColors
import kotlin.math.roundToInt

/** Einstellungen der Ladeautomatik mit Protokoll der letzten Entscheidungen. */
@Composable
fun ChargeRulesCard(
    saved: ChargeRules,
    fordConnected: Boolean,
    status: String?,
    log: String,
    onSave: (ChargeRules) -> Unit,
) {
    var draft by rememberSaveable(saved, stateSaver = RulesSaver) { mutableStateOf(saved) }
    val dirty = draft != saved

    EnergieCard(title = "Ladeautomatik", accent = EnergyColors.car) {
        if (!fordConnected) {
            Text("Erst unter FordPass anmelden, dann kann die Automatik das Auto pausieren und fortsetzen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatik", style = MaterialTheme.typography.titleMedium)
                Text("Prüft bei jeder Messung, alle 15 Minuten im Hintergrund.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) }, enabled = fordConnected)
        }

        SliderRow("Laden, wenn Speicher mindestens", "${draft.batteryOnPercent} %", draft.batteryOnPercent.toFloat(), 20f..100f, 15) {
            draft = draft.copy(batteryOnPercent = it.roundToInt().let { v -> v - v % 5 })
        }
        SliderRow("Pausieren, wenn Speicher unter", "${draft.batteryOffPercent} %", draft.batteryOffPercent.toFloat(), 0f..95f, 18) {
            draft = draft.copy(batteryOffPercent = it.roundToInt().let { v -> v - v % 5 })
        }
        if (draft.batteryOffPercent >= draft.batteryOnPercent) {
            Text("Die Pausier-Schwelle muss unter der Lade-Schwelle liegen, sonst flattert es. Wird beim Speichern angepasst.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        SliderRow("Oder laden bei PV-Überschuss ab", "${draft.surplusOnW} W", draft.surplusOnW.toFloat(), 500f..6000f, 21) {
            draft = draft.copy(surplusOnW = (it / 250).roundToInt() * 250)
        }
        SliderRow("Immer laden, wenn Auto unter", "${draft.carReservePercent} %", draft.carReservePercent.toFloat(), 10f..80f, 13) {
            draft = draft.copy(carReservePercent = it.roundToInt().let { v -> v - v % 5 })
        }
        SliderRow("Mindestabstand zwischen Befehlen", "${draft.minCommandGapMinutes} min", draft.minCommandGapMinutes.toFloat(), 5f..60f, 10) {
            draft = draft.copy(minCommandGapMinutes = (it / 5).roundToInt() * 5)
        }

        Text("Nachtsperre (kein Laden, außer unter der Reserve). Gleiche Zeiten = keine Sperre.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Von", draft.nightStartMinutes, Modifier.weight(1f)) { draft = draft.copy(nightStartMinutes = it) }
            TimeField("Bis", draft.nightEndMinutes, Modifier.weight(1f)) { draft = draft.copy(nightEndMinutes = it) }
        }

        Button(onClick = { onSave(draft) }, enabled = dirty, modifier = Modifier.fillMaxWidth()) { Text("Automatik speichern") }

        status?.let {
            Text("Zuletzt: $it", style = MaterialTheme.typography.bodyMedium)
        }
        if (log.isNotBlank()) {
            Text("Protokoll", style = MaterialTheme.typography.titleSmall)
            Text(log, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** Uhrzeit als HH:MM, gespeichert in Minuten seit Mitternacht. */
@Composable
private fun TimeField(label: String, minutes: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by rememberSaveable(minutes) { mutableStateOf("%02d:%02d".format(minutes / 60, minutes % 60)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim())?.let { m ->
                val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                if (h in 0..23 && mi in 0..59) onChange(h * 60 + mi)
            }
        },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private val RulesSaver = androidx.compose.runtime.saveable.Saver<ChargeRules, List<String>>(
    save = { listOf(it.enabled.toString(), it.batteryOnPercent.toString(), it.batteryOffPercent.toString(), it.surplusOnW.toString(), it.nightStartMinutes.toString(), it.nightEndMinutes.toString(), it.carReservePercent.toString(), it.minCommandGapMinutes.toString()) },
    restore = {
        ChargeRules(
            enabled = it[0].toBoolean(), batteryOnPercent = it[1].toInt(), batteryOffPercent = it[2].toInt(), surplusOnW = it[3].toInt(),
            nightStartMinutes = it[4].toInt(), nightEndMinutes = it[5].toInt(), carReservePercent = it[6].toInt(), minCommandGapMinutes = it[7].toInt(),
        )
    },
)
