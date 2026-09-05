package com.jakober.energie.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.theme.EnergyColors
import kotlinx.datetime.Instant

/**
 * Sicherung: Zielordner (auch Google Drive), Passwort fuer die Zugangsdaten,
 * "Jetzt sichern" und "Wiederherstellen".
 */
@Composable
fun BackupCard(
    saved: Settings,
    status: String?,
    busy: Boolean,
    onTarget: (treeUri: String, password: String) -> Unit,
    onBackupNow: () -> Unit,
    onRestore: (file: Uri, password: String?) -> Unit,
) {
    val context = LocalContext.current
    var password by rememberSaveable(saved.backupPassword) { mutableStateOf(saved.backupPassword) }
    var showPw by rememberSaveable { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    var restorePw by rememberSaveable { mutableStateOf("") }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            onTarget(uri.toString(), password)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri.toString(); restorePw = saved.backupPassword }
    }

    EnergieCard(title = "Sicherung", accent = EnergyColors.battery) {
        Text(
            "Täglich nachts im WLAN eine ZIP-Datei mit Verlauf und Einstellungen in einen Ordner deiner Wahl, etwa in Google Drive. Zugangsdaten sind darin mit dem Passwort verschlüsselt; die letzten 14 Sicherungen bleiben erhalten.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val folderName = saved.backupTreeUri.takeIf { it.isNotBlank() }?.let { folderLabel(it) }
        ValueRow("Zielordner", folderName ?: "nicht gewählt")
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Backup-Passwort (mind. 8 Zeichen)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { IconButton(onClick = { showPw = !showPw }) { Icon(if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
            isError = password.isNotEmpty() && password.length < 8,
            supportingText = {
                if (password.isNotEmpty() && password.length < 8) Text("Zu kurz.")
                else if (password != saved.backupPassword) Text("Noch nicht gespeichert.")
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.weight(1f)) {
                Text(if (folderName == null) "Ordner wählen" else "Ordner ändern")
            }
            OutlinedButton(
                onClick = { onTarget(saved.backupTreeUri, password) },
                enabled = password.length >= 8 && password != saved.backupPassword,
                modifier = Modifier.weight(1f),
            ) { Text("Passwort speichern") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBackupNow, enabled = saved.backupConfigured && !busy, modifier = Modifier.weight(1f)) { Text("Jetzt sichern") }
            OutlinedButton(onClick = { pickFile.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Wiederherstellen…") }
        }
        if (saved.backupLastAt > 0) {
            ValueRow("Letzte Sicherung", Format.dateTime(Instant.fromEpochSeconds(saved.backupLastAt)), saved.backupLastResult.takeIf { it.isNotBlank() })
        } else if (saved.backupLastResult.isNotBlank()) {
            Text(saved.backupLastResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    restoreUri?.let { uriString ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Sicherung wiederherstellen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verlauf und Einstellungen aus der Datei ersetzen die vorhandenen Tage gleichen Datums. Mit Passwort werden auch die Zugangsdaten übernommen, ohne bleiben die aktuellen.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = restorePw, onValueChange = { restorePw = it },
                        label = { Text("Passwort der Sicherung (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onRestore(Uri.parse(uriString), restorePw.ifBlank { null }); restoreUri = null }) { Text("Wiederherstellen") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Abbrechen") } },
        )
    }
}

/** Lesbarer Name des gewaehlten Ordners aus der SAF-Adresse, etwa "Drive · Energie". */
private fun folderLabel(treeUri: String): String {
    val uri = Uri.parse(treeUri)
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return treeUri
    val provider = when {
        uri.authority?.contains("google", ignoreCase = true) == true -> "Google Drive"
        uri.authority?.contains("externalstorage", ignoreCase = true) == true -> "Gerät"
        uri.authority?.contains("onedrive", ignoreCase = true) == true || uri.authority?.contains("skydrive", ignoreCase = true) == true -> "OneDrive"
        else -> uri.authority ?: ""
    }
    val path = docId.substringAfter(':', docId).substringAfterLast('/').ifBlank { docId }
    return if (provider.isBlank()) path else "$provider · $path"
}
