package com.jakober.energie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.cloud.CloudCommand
import com.jakober.energie.data.CloudRole
import com.jakober.energie.data.LiveState
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.theme.EnergyColors
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Cloud (Supabase): Zugang, Rolle des Geraets und Stand des Abgleichs.
 * Zugangsdaten gehoeren zum Entwurf und werden mit "Speichern" uebernommen;
 * die Rolle wirkt sofort.
 */
@Composable
fun CloudCard(
    draft: Settings,
    saved: Settings,
    live: LiveState,
    message: String?,
    commands: List<Pair<CloudCommand, String?>>,
    onDraft: (Settings) -> Unit,
    onTest: (Settings) -> Unit,
    onRole: (CloudRole) -> Unit,
    onLoadCommands: () -> Unit,
) {
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showPw by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saved.cloudRole, saved.cloudConfigured) { if (saved.cloudConfigured && saved.cloudRole != CloudRole.STANDALONE) onLoadCommands() }

    EnergieCard(title = "Cloud (Supabase)", accent = EnergyColors.grid) {
        Text(
            "Zwei Handys, eine Wahrheit: Die Zentrale misst zu Hause jede Minute und schreibt alles in deine Supabase-Datenbank. " +
                "Die Anzeige misst nicht selbst, sondern holt Messpunkte, Autozustand und Hinweise von dort, auch unterwegs. Beide melden sich mit demselben Konto an.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.cloudUrl, onValueChange = { onDraft(draft.copy(cloudUrl = it)) },
            label = { Text("Projekt-URL (https://….supabase.co)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = draft.cloudAnonKey, onValueChange = { onDraft(draft.copy(cloudAnonKey = it)) },
            label = { Text("anon public key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
        )
        OutlinedTextField(
            value = draft.cloudEmail, onValueChange = { onDraft(draft.copy(cloudEmail = it)) },
            label = { Text("E-Mail des Supabase-Benutzers") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = draft.cloudPassword, onValueChange = { onDraft(draft.copy(cloudPassword = it)) },
            label = { Text("Passwort") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showPw = !showPw }) { Icon(if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
        )
        OutlinedButton(onClick = { onTest(draft) }, enabled = draft.cloudConfigured) { Text("Speichern und Anmeldung prüfen") }

        Text("Rolle dieses Geräts", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudRole.entries.forEach { r ->
                FilterChip(selected = saved.cloudRole == r, onClick = { onRole(r) }, label = { Text(r.label) }, enabled = r == CloudRole.STANDALONE || saved.cloudConfigured)
            }
        }
        Text(
            when (saved.cloudRole) {
                CloudRole.STANDALONE -> "Misst selbst, nutzt die Cloud nicht."
                CloudRole.HUB -> "Misst jede Minute über einen Dauer-Dienst (Benachrichtigung „Energie-Zentrale läuft“), lädt Messpunkte hoch, führt Aufträge der Anzeige aus. Gerät am Ladekabel lassen und von der Akku-Optimierung ausnehmen."
                CloudRole.VIEWER -> "Misst nicht selbst. Holt Messpunkte, Autozustand und Hinweise aus der Cloud; Befehle ans Auto und geänderte Einstellungen gehen als Auftrag an die Zentrale."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (saved.cloudConfigured && saved.cloudRole != CloudRole.STANDALONE) {
            val now = Clock.System.now()
            if (saved.cloudRole == CloudRole.HUB) {
                ValueRow("Hochgeladen bis", stamp(saved.cloudUploadedAt, now))
            } else {
                ValueRow("Abgeglichen bis", stamp(saved.cloudSyncedAt, now))
                ValueRow("Zentrale zuletzt gesehen", live.hubSeenAt?.let { Format.ago(it, now) } ?: "noch nie", color = if (live.hubSeenAt != null && now - live.hubSeenAt > com.jakober.energie.data.EnergyRepository.HUB_SILENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (saved.cloudRole == CloudRole.VIEWER) {
                ValueRow(
                    "Sofort-Push (Firebase)",
                    when {
                        !com.jakober.energie.notify.Push.available -> "kein Firebase-Schlüssel im Build"
                        saved.pushRegisteredToken.isNotBlank() -> "registriert"
                        saved.pushToken.isNotBlank() -> "Token da, noch nicht eingetragen"
                        else -> "kein Token (Google-Dienste?)"
                    },
                )
            }
            (live.cloudError ?: live.cloudInfo)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (live.cloudError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (commands.isNotEmpty()) {
                Text("Letzte Aufträge", style = MaterialTheme.typography.titleSmall)
                commands.forEach { (c, result) ->
                    ValueRow(c.kind + (com.jakober.energie.data.CloudSync.payloadString(c.payload, "command")?.let { " $it" } ?: ""), result ?: "offen", detail = c.createdAt.take(16).replace('T', ' '))
                }
                TextButton(onClick = onLoadCommands) { Text("Aufträge neu laden") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun stamp(epochSeconds: Long, now: Instant): String =
    if (epochSeconds <= 0) "noch nichts" else Format.dateTime(Instant.fromEpochSeconds(epochSeconds)) + " (${Format.ago(Instant.fromEpochSeconds(epochSeconds), now)})"
