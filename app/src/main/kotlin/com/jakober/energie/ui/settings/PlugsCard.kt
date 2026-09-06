package com.jakober.energie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jakober.energie.core.plugs.PlugDevice
import com.jakober.energie.core.plugs.PlugKind
import com.jakober.energie.core.plugs.PlugReading
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.theme.EnergyColors

/**
 * Messstecker im Heimnetz: Liste mit Livewert, Umbenennen, Entfernen;
 * Suche per mDNS oder Eingabe der Adresse.
 */
@Composable
fun PlugsCard(
    plugs: List<PlugDevice>,
    readings: Map<String, PlugReading>,
    errors: Map<String, String>,
    discovered: List<PlugDevice>,
    discovering: Boolean,
    message: String?,
    onDiscover: () -> Unit,
    onAdd: (host: String, name: String, kind: PlugKind) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(PlugKind.SHELLY) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    EnergieCard(title = "Steckdosen", accent = EnergyColors.house) {
        Text(
            "Shelly- oder Tasmota-Messstecker im Heimnetz. Jeder Stecker steht für einen Verbraucher, etwa „Kühlschrank“; die Statistik zeigt dann, wer wie viel verbraucht. Abfrage nur im Heimnetz, von unterwegs bleibt der letzte Stand.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plugs.isEmpty()) {
            Text("Noch keine Stecker eingerichtet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        plugs.forEach { d ->
            val r = readings[d.id]
            val err = errors[d.id]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${d.host} · ${if (d.kind == PlugKind.SHELLY) "Shelly" else "Tasmota"}" +
                            (r?.let { " · jetzt ${Format.power(it.powerW)}" + (it.energyWh?.let { e -> " · Zähler ${Format.energy(e)}" } ?: "") } ?: "") +
                            (err?.let { " · nicht erreichbar" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (err != null && r == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { renaming = d.id }) { Icon(Icons.Rounded.Edit, "Umbenennen") }
                IconButton(onClick = { removing = d.id }) { Icon(Icons.Rounded.Delete, "Entfernen") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDiscover, enabled = !discovering) { Text("Shelly im Heimnetz suchen") }
            if (discovering) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        discovered.forEach { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text(d.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onAdd(d.host, d.name, PlugKind.SHELLY) }) { Text("Hinzufügen") }
            }
        }

        Text("Oder von Hand", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == PlugKind.SHELLY, onClick = { kind = PlugKind.SHELLY }, label = { Text("Shelly") })
            FilterChip(selected = kind == PlugKind.TASMOTA, onClick = { kind = PlugKind.TASMOTA }, label = { Text("Tasmota") })
        }
        OutlinedTextField(
            value = host, onValueChange = { host = it }, label = { Text("IP-Adresse im Heimnetz, z. B. 192.168.178.50") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Verbraucher, z. B. Kühlschrank (leer = Name vom Gerät)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onAdd(host, name, kind); host = ""; name = "" }, enabled = host.isNotBlank()) { Text("Stecker prüfen und hinzufügen") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "Tipp: In der FRITZ!Box unter Heimnetz → Netzwerk jedem Stecker „immer die gleiche IPv4-Adresse“ geben, dann bleibt die Adresse stabil.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    renaming?.let { id ->
        val current = plugs.firstOrNull { it.id == id } ?: return@let
        var text by rememberSaveable(id) { mutableStateOf(current.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Verbraucher benennen") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { onRename(id, text); renaming = null }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Abbrechen") } },
        )
    }
    removing?.let { id ->
        val current = plugs.firstOrNull { it.id == id } ?: return@let
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("${current.name} entfernen?") },
            text = { Text("Der Stecker wird nicht mehr abgefragt. Bereits gespeicherte Messwerte bleiben im Verlauf.") },
            confirmButton = { TextButton(onClick = { onRemove(id); removing = null }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Abbrechen") } },
        )
    }
}
