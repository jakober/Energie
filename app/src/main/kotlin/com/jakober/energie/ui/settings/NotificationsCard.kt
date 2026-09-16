package com.jakober.energie.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.jakober.energie.core.alerts.AlertSettings
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.theme.EnergyColors
import kotlin.math.roundToInt

/** Welche Benachrichtigungen die App schickt, mit ihren Schwellen. */
@Composable
fun NotificationsCard(saved: AlertSettings, onSave: (AlertSettings) -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable(saved, stateSaver = AlertSaver) { mutableStateOf(saved) }
    val dirty = draft != saved
    val enabledOnDevice = NotificationManagerCompat.from(context).areNotificationsEnabled()

    EnergieCard(title = "Benachrichtigungen", accent = EnergyColors.car) {
        if (!enabledOnDevice) {
            Text("Android blockiert Benachrichtigungen dieser App. Bitte in den Systemeinstellungen erlauben.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Systemeinstellungen öffnen") }
        }

        ToggleRow("Auto nicht abgeschlossen", "Wenn es zu Hause steht und nach ${draft.unlockedMinutes} min noch offen ist.", draft.carUnlocked) { draft = draft.copy(carUnlocked = it) }
        if (draft.carUnlocked) {
            SliderRow("Wartezeit", "${draft.unlockedMinutes} min", draft.unlockedMinutes.toFloat(), 5f..60f, 10) { draft = draft.copy(unlockedMinutes = (it / 5).roundToInt() * 5) }
        }
        ToggleRow("Sonnenstrom ungenutzt", "Speicher ab ${draft.batteryFullPercent} % voll, mindestens ${draft.surplusW} W Einspeisung, Auto steckt und lädt nicht. Mit Knopf „Jetzt laden“.", draft.surplusUnused) { draft = draft.copy(surplusUnused = it) }
        if (draft.surplusUnused) {
            SliderRow("Einspeisung ab", "${draft.surplusW} W", draft.surplusW.toFloat(), 500f..5000f, 17) { draft = draft.copy(surplusW = (it / 250).roundToInt() * 250) }
            SliderRow("Speicher ab", "${draft.batteryFullPercent} %", draft.batteryFullPercent.toFloat(), 70f..100f, 5) { draft = draft.copy(batteryFullPercent = it.roundToInt().let { v -> v - v % 5 }) }
        }
        ToggleRow(
            "Auto steckt nicht, obwohl es leer ist",
            "Wenn das Auto zu Hause steht, nicht angesteckt ist und unter ${draft.carLowPercent} % hat. Einmal je Parkvorgang.",
            draft.carLowUnplugged,
        ) { draft = draft.copy(carLowUnplugged = it) }
        if (draft.carLowUnplugged) {
            SliderRow("Ladung unter", "${draft.carLowPercent} %", draft.carLowPercent.toFloat(), 20f..90f, 13) { draft = draft.copy(carLowPercent = (it / 5).roundToInt() * 5) }
        }
        ToggleRow(
            "Strom da, Auto steckt nicht",
            "Wenn das Auto zu Hause steht, nicht angesteckt ist und gleichzeitig mindestens ${draft.surplusW} W ins Netz gehen oder der Speicher über ${draft.carSurplusBatteryPercent} % steht. Höchstens stündlich.",
            draft.carSurplusUnplugged,
        ) { draft = draft.copy(carSurplusUnplugged = it) }
        if (draft.carSurplusUnplugged) {
            SliderRow("Speicher über", "${draft.carSurplusBatteryPercent} %", draft.carSurplusBatteryPercent.toFloat(), 40f..100f, 11) { draft = draft.copy(carSurplusBatteryPercent = (it / 5).roundToInt() * 5) }
        }
        ToggleRow("Ladeautomatik", "Wenn die Automatik das Laden pausiert oder fortsetzt.", draft.automation) { draft = draft.copy(automation = it) }
        ToggleRow("Ladestart und Ladeende", "Wenn das Auto laut Ford zu laden beginnt oder aufhört, mit Akkustand.", draft.chargeStartStop) { draft = draft.copy(chargeStartStop = it) }
        ToggleRow("Quelle ausgefallen", "SENEC oder FRITZ!Box antworten seit ${draft.sourceDownMinutes} min nicht, und wenn sie wieder da sind.", draft.sourceDown) { draft = draft.copy(sourceDown = it) }
        ToggleRow("Sicherung fehlgeschlagen", "Wenn die nächtliche Sicherung nicht geschrieben werden konnte.", draft.backupFailed) { draft = draft.copy(backupFailed = it) }
        ToggleRow(
            "Zentrale meldet sich nicht",
            "Nur auf dem Anzeige-Gerät: Hinweis, wenn die Zentrale seit ${draft.hubSilentMinutes} min keine Messwerte mehr geschrieben hat, und wenn sie wieder da ist.",
            draft.hubSilent,
        ) { draft = draft.copy(hubSilent = it) }
        if (draft.hubSilent) {
            SliderRow("Stille ab", "${draft.hubSilentMinutes} min", draft.hubSilentMinutes.toFloat(), 15f..120f, 6) { draft = draft.copy(hubSilentMinutes = (it / 15).roundToInt() * 15) }
        }
        ToggleRow(
            "Kühlgeräte",
            "Steckdosen vom Typ „Kühlgerät“: Kompressor läuft seit ${draft.coolingStuckHours} h durch, seit ${draft.coolingSilentHours} h kein Lauf, dauerhaft über der Nennleistung, oder seit Tagen deutlich mehr Verbrauch als üblich.",
            draft.cooling,
        ) { draft = draft.copy(cooling = it) }
        if (draft.cooling) {
            SliderRow("Dauerlauf ab", "${draft.coolingStuckHours} h", draft.coolingStuckHours.toFloat(), 1f..8f, 6) { draft = draft.copy(coolingStuckHours = it.roundToInt()) }
            SliderRow("Stillstand ab", "${draft.coolingSilentHours} h", draft.coolingSilentHours.toFloat(), 2f..24f, 10) { draft = draft.copy(coolingSilentHours = (it / 2).roundToInt() * 2) }
        }

        Button(onClick = { onSave(draft) }, enabled = dirty, modifier = Modifier.fillMaxWidth()) { Text("Benachrichtigungen speichern") }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
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

private val alertJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Haelt den Entwurf ueber eine Drehung. Bewusst vollstaendig als JSON: eine Aufzaehlung
 * einzelner Felder verliert die uebrigen, und Speichern wuerde sie auf Standard zuruecksetzen.
 */
private val AlertSaver = androidx.compose.runtime.saveable.Saver<AlertSettings, String>(
    save = { alertJson.encodeToString(AlertSettings.serializer(), it) },
    restore = { runCatching { alertJson.decodeFromString(AlertSettings.serializer(), it) }.getOrNull() },
)
