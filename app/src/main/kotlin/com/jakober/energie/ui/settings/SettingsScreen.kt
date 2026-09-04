package com.jakober.energie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jakober.energie.data.CarCommand
import com.jakober.energie.data.FordCommand
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.EnergieViewModel
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.theme.EnergyColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: EnergieViewModel, contentPadding: PaddingValues) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val storedDays by vm.storedDays.collectAsStateWithLifecycle()

    // Ein Entwurf, den der Nutzer bearbeitet; gespeichert wird erst per Knopf.
    var draft by rememberSaveable(saved, stateSaver = SettingsSaver) { mutableStateOf(saved) }
    var showFritzPw by rememberSaveable { mutableStateOf(false) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var showCarRaw by rememberSaveable { mutableStateOf(false) }
    val carResult by vm.carResult.collectAsStateWithLifecycle()
    val carRaw by vm.carRaw.collectAsStateWithLifecycle()
    val fordResult by vm.fordResult.collectAsStateWithLifecycle()
    val fordRaw by vm.fordRaw.collectAsStateWithLifecycle()
    var showFordRaw by rememberSaveable { mutableStateOf(false) }
    var fordPaste by rememberSaveable { mutableStateOf("") }
    val fordLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(FordLoginActivity.RESULT_URL)?.let { vm.fordExchange(it) }
        }
    }
    val context = LocalContext.current
    // Nur die hier editierbaren Felder vergleichen; Automatik, Ford-Tokens und Protokoll
    // aendern sich im Hintergrund und gehoeren nicht zum Entwurf.
    val dirty = draft.copy(chargeRules = saved.chargeRules, chargeLastCommandAt = saved.chargeLastCommandAt, chargeOverride = saved.chargeOverride, chargeLog = saved.chargeLog,
        fordTokensJson = saved.fordTokensJson, fordVin = saved.fordVin, fordLocationId = saved.fordLocationId, smartcarVehicleId = saved.smartcarVehicleId, smartcarUserId = saved.smartcarUserId) != saved

    LaunchedEffect(Unit) { vm.clearTestResult() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Einstellungen", style = MaterialTheme.typography.displaySmall) }

        item {
            EnergieCard(title = "SENEC.Connect", accent = EnergyColors.sun) {
                Text(
                    "Abonnementschlüssel aus developer.senec.com (Benutzerprofil → Abonnements). Primär- oder Sekundärschlüssel, beide gehen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.senecKey, onValueChange = { draft = draft.copy(senecKey = it) },
                    label = { Text("Schlüssel") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
                )
                OutlinedTextField(
                    value = draft.senecBaseUrl, onValueChange = { draft = draft.copy(senecBaseUrl = it) },
                    label = { Text("Basis-Adresse (nur ändern, wenn SENEC es sagt)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedButton(onClick = { vm.testSenec(draft) }, enabled = draft.senecKey.isNotBlank()) { Text("SENEC prüfen") }
            }
        }

        item {
            EnergieCard(title = "FRITZ!Box", accent = EnergyColors.grid) {
                Text(
                    "Im Heimnetz „fritz.box“, von unterwegs die MyFRITZ!-Adresse mit https://, etwa https://abc123.myfritz.net. Der Benutzer braucht in der FRITZ!Box die Rechte „Smart Home“ und „Zugang aus dem Internet“.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.fritzHost, onValueChange = { draft = draft.copy(fritzHost = it) },
                    label = { Text("Adresse") }, placeholder = { Text("fritz.box oder https://…myfritz.net") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = draft.fritzUser, onValueChange = { draft = draft.copy(fritzUser = it) },
                    label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.fritzPassword, onValueChange = { draft = draft.copy(fritzPassword = it) },
                    label = { Text("Passwort") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showFritzPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showFritzPw = !showFritzPw }) { Icon(if (showFritzPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
                )
                OutlinedButton(onClick = { vm.testFritz(draft) }, enabled = draft.fritzConfigured) { Text("FRITZ!Box prüfen") }
            }
        }

        testResult?.let { item { EnergieCard(title = "Prüfergebnis") { Text(it) } } }

        item {
            EnergieCard(title = "Smartcar (Auto)", accent = EnergyColors.car) {
                Text(
                    "Aus dem Smartcar-Dashboard: Application ID (öffnet Connect), Client ID und Client Secret (holen das API-Token). Nach dem Speichern „Auto verbinden“, dort mit FordPass anmelden, dann „Verbindung prüfen“.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.smartcarAppId, onValueChange = { draft = draft.copy(smartcarAppId = it) },
                    label = { Text("Application ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = draft.smartcarClientId, onValueChange = { draft = draft.copy(smartcarClientId = it) },
                    label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = draft.smartcarClientSecret, onValueChange = { draft = draft.copy(smartcarClientSecret = it) },
                    label = { Text("Client Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showSecret = !showSecret }) { Icon(if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
                )
                OutlinedTextField(
                    value = draft.carFallbackPowerW.toString(),
                    onValueChange = { t -> t.filter { it.isDigit() }.toIntOrNull()?.let { draft = draft.copy(carFallbackPowerW = it) } },
                    label = { Text("Angenommene Ladeleistung in W, falls das Auto keine meldet") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (dirty) {
                    Text("Erst speichern, dann verbinden und testen.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.connectUrl(saved)))) },
                        enabled = !dirty && saved.smartcarAppId.isNotBlank(), modifier = Modifier.weight(1f),
                    ) { Text("Auto verbinden") }
                    OutlinedButton(onClick = vm::carCheck, enabled = !dirty && saved.smartcarConfigured, modifier = Modifier.weight(1f)) { Text("Verbindung prüfen") }
                }
                if (saved.carConnected) {
                    Text("Verbunden: ${saved.smartcarVehicleId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = vm::carStatus, enabled = !dirty, modifier = Modifier.fillMaxWidth()) { Text("Status lesen") }
                    Text("Testbefehle (zählen zum Smartcar-Kontingent):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.carCommand(CarCommand.LIMIT_50) }, enabled = !dirty, modifier = Modifier.weight(1f)) { Text("Ziel 50 %") }
                        OutlinedButton(onClick = { vm.carCommand(CarCommand.LIMIT_100) }, enabled = !dirty, modifier = Modifier.weight(1f)) { Text("Ziel 100 %") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.carCommand(CarCommand.STOP) }, enabled = !dirty, modifier = Modifier.weight(1f)) { Text("Stoppen") }
                        OutlinedButton(onClick = { vm.carCommand(CarCommand.START) }, enabled = !dirty, modifier = Modifier.weight(1f)) { Text("Starten") }
                    }
                }
                carResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (carRaw != null) {
                    TextButton(onClick = { showCarRaw = !showCarRaw }) { Text(if (showCarRaw) "Rohantwort ausblenden" else "Rohantwort anzeigen") }
                    if (showCarRaw) {
                        Text(carRaw ?: "", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }

        item {
            EnergieCard(title = "FordPass (Steuerung, inoffiziell)", accent = EnergyColors.car) {
                Text(
                    "Nutzt Fords eigene App-Schnittstelle, die Ford nicht offiziell freigibt. Bitte mit einem Zweitkonto anmelden, das im Auto als weiterer Fahrer freigegeben ist. Liefert Laden pausieren, fortsetzen und Ladeziel je Ladeort.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!saved.fordConnected) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, FordLoginActivity::class.java).putExtra(FordLoginActivity.EXTRA_URL, vm.fordLoginUrl())
                            fordLogin.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Bei Ford anmelden") }
                    Text("Falls der Rücksprung nicht klappt: Adresse aus der Browserzeile (fordapp://userauthorized?code=…) hier einfügen.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = fordPaste, onValueChange = { fordPaste = it },
                        label = { Text("Rückkehr-Adresse oder Code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    TextButton(onClick = { vm.fordExchange(fordPaste); fordPaste = "" }, enabled = fordPaste.isNotBlank()) { Text("Code einlösen") }
                } else {
                    Text("Angemeldet, Fahrzeug ${saved.fordVin}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::fordStatus, modifier = Modifier.weight(1f)) { Text("Status lesen") }
                        OutlinedButton(onClick = vm::fordLocations, modifier = Modifier.weight(1f)) { Text("Ladeorte") }
                    }
                    Text("Testbefehle:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.fordCommand(FordCommand.PAUSE) }, modifier = Modifier.weight(1f)) { Text("Pausieren") }
                        OutlinedButton(onClick = { vm.fordCommand(FordCommand.RESUME) }, modifier = Modifier.weight(1f)) { Text("Fortsetzen") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.fordCommand(FordCommand.TARGET_50) }, modifier = Modifier.weight(1f)) { Text("Ziel 50 %") }
                        OutlinedButton(onClick = { vm.fordCommand(FordCommand.TARGET_100) }, modifier = Modifier.weight(1f)) { Text("Ziel 100 %") }
                    }
                    TextButton(onClick = vm::fordLogout) { Text("Ford-Anmeldung entfernen") }
                }
                fordResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (fordRaw != null) {
                    TextButton(onClick = { showFordRaw = !showFordRaw }) { Text(if (showFordRaw) "Rohantwort ausblenden" else "Rohantwort anzeigen") }
                    if (showFordRaw) Text(fordRaw ?: "", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }

        item {
            ChargeRulesCard(
                saved = saved.chargeRules,
                fordConnected = saved.fordConnected,
                status = live.automationStatus,
                log = saved.chargeLog,
                onSave = vm::saveRules,
            )
        }

        item {
            EnergieCard(title = "Abfrage") {
                Text("Alle ${draft.pollSeconds} s, solange die App offen ist", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = draft.pollSeconds.toFloat(), onValueChange = { draft = draft.copy(pollSeconds = it.roundToInt()) },
                    valueRange = 20f..600f, steps = 28,
                )
                Text(
                    "Im Hintergrund holt die App alle 15 Minuten einen Messpunkt, egal was hier steht. " +
                        "Das SENEC-Kontingent „SENEC.Data 45000“ reicht für etwa eine Abfrage je Minute im Monat.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            EnergieCard(title = "Preise") {
                NumberField("Strompreis in € je kWh", draft.pricePerKwh) { draft = draft.copy(pricePerKwh = it) }
                NumberField("Einspeisevergütung in € je kWh", draft.feedInPerKwh) { draft = draft.copy(feedInPerKwh = it) }
            }
        }

        item {
            EnergieCard(title = "Verlauf") {
                ValueRow("Gespeicherte Tage", storedDays.toString())
                Text("Behalten: ${draft.keepDays} Tage", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = draft.keepDays.toFloat(), onValueChange = { draft = draft.copy(keepDays = it.roundToInt()) },
                    valueRange = 30f..1095f, steps = 34,
                )
                Text(
                    "Eine Datei je Tag im App-Speicher, rund 100 kB bei einem Wert je Minute.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.save(draft) }, enabled = dirty, modifier = Modifier.weight(1f)) { Text("Speichern") }
                TextButton(onClick = { draft = saved }, enabled = dirty) { Text("Verwerfen") }
            }
        }

        item {
            EnergieCard(title = "Rohdaten") {
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Ausblenden" else "Letzte SENEC-Antwort anzeigen") }
                if (showRaw) {
                    Text(
                        live.senecRaw ?: "Noch keine Antwort.",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (live.fritzDevices.isNotEmpty()) {
                        Text("FRITZ!Box-Geräte", style = MaterialTheme.typography.titleSmall)
                        live.fritzDevices.forEach { d ->
                            Text(
                                "${d.ainCompact}  ${d.productName}  „${d.name}“" +
                                    (d.powerMeter?.let { " ${Format.power(it.powerWatt)} / ${Format.meterReading(it.energyWh)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString().replace('.', ',')) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Damit der Entwurf eine Drehung des Geraets ueberlebt. */
private val SettingsSaver = androidx.compose.runtime.saveable.Saver<Settings, List<String>>(
    save = {
        listOf(
            it.fritzHost, it.fritzUser, it.fritzPassword, it.senecKey, it.senecBaseUrl, it.pollSeconds.toString(),
            it.pricePerKwh.toString(), it.feedInPerKwh.toString(), it.keepDays.toString(),
            it.smartcarAppId, it.smartcarClientId, it.smartcarClientSecret, it.smartcarVehicleId, it.smartcarUserId, it.carFallbackPowerW.toString(),
            it.fordTokensJson, it.fordVin, it.fordLocationId,
        )
    },
    restore = {
        Settings(
            fritzHost = it[0], fritzUser = it[1], fritzPassword = it[2], senecKey = it[3], senecBaseUrl = it[4],
            pollSeconds = it[5].toInt(), pricePerKwh = it[6].toDouble(), feedInPerKwh = it[7].toDouble(), keepDays = it[8].toInt(),
            smartcarAppId = it[9], smartcarClientId = it[10], smartcarClientSecret = it[11], smartcarVehicleId = it[12], smartcarUserId = it[13],
            carFallbackPowerW = it[14].toInt(),
            fordTokensJson = it.getOrElse(15) { "" }, fordVin = it.getOrElse(16) { "" }, fordLocationId = it.getOrElse(17) { "" },
        )
    },
)
