package com.jakober.energie.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import com.jakober.energie.data.FordCommand
import com.jakober.energie.core.places.NamedPlace
import com.jakober.energie.core.places.Places
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.data.LiveState
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.BigValue
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.EnergieViewModel
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.ShareBar
import com.jakober.energie.ui.ValueRow
import com.jakober.energie.ui.charts.RingGauge
import com.jakober.energie.ui.theme.EnergyColors
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlin.math.abs

@Composable
fun DashboardScreen(vm: EnergieViewModel, onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today by vm.todayStats.collectAsStateWithLifecycle()

    val fordResult by vm.fordResult.collectAsStateWithLifecycle()
    val yesterday by vm.yesterdayStats.collectAsStateWithLifecycle()
    // Welcher Knoten des Diagramms gerade seine Detailkarte zeigt; nochmal Tippen schliesst.
    var selectedNode by rememberSaveable { mutableStateOf<FlowNodeKind?>(null) }

    // "vor 12 s" soll mitlaufen, ohne dass sich sonst etwas aendert.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = Clock.System.now() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Energie", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "Aktualisiert ${Format.ago(live.lastUpdate, now)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (live.refreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = vm::refreshNow) { Icon(Icons.Rounded.Refresh, "Aktualisieren") }
            }
        }

        if (!settings.anythingConfigured) {
            item { SetupHint(onOpenSettings) }
        }

        live.senecError?.let { item { ErrorCard("SENEC", it) } }
        live.fritzError?.let { item { ErrorCard("FRITZ!Box", it) } }

        val carActive = settings.carConnected || settings.fordConnected || live.car != null
        item {
            FlowDiagram(
                live.sample, showCar = carActive,
                onNodeClick = { kind -> selectedNode = if (selectedNode == kind) null else kind },
                forecast = pvForecastBadge(settings, live.pvPeakEstimateKw, vm.todayDate(), today?.totals?.productionWh),
            )
        }

        when (selectedNode) {
            FlowNodeKind.CAR -> if (carActive) item {
                CarCard(
                    live, settings,
                    onOverride = vm::setChargeOverride,
                    onCommand = vm::fordCommand,
                    commandResult = fordResult,
                    onClose = { selectedNode = null },
                    onSavePlaces = vm::savePlaces,
                )
            }
            FlowNodeKind.BATTERY -> item { BatteryDetailCard(live, today, onClose = { selectedNode = null }) }
            FlowNodeKind.PV -> item { PvDetailCard(live, today, yesterday, settings, vm.todayDate(), onClose = { selectedNode = null }) }
            FlowNodeKind.HOUSE -> item { HouseDetailCard(live, today, yesterday, settings, onClose = { selectedNode = null }) }
            FlowNodeKind.GRID -> item { GridDetailCard(live, today, yesterday, settings, onClose = { selectedNode = null }) }
            null -> {}
        }

        item { BatteryAndGridRow(live) }

        item { TodayCard(today, settings) }

        if (live.meter != null || live.sample?.meterImportWh != null) {
            item { MeterCard(live) }
        }

        live.senec?.evse?.firstOrNull()?.let { evse ->
            item {
                EnergieCard(title = "Wallbox", accent = EnergyColors.car) {
                    ValueRow(
                        if (evse.evCharging == true) "Auto lädt" else if (evse.evConnected == true) "Auto angesteckt" else "Kein Auto",
                        Format.power(evse.chargingPower), icon = Icons.Rounded.ElectricCar, iconTint = EnergyColors.car,
                    )
                }
            }
        }

        live.senec?.bessNameplate?.let { np ->
            item {
                EnergieCard(title = "Anlage") {
                    ValueRow("Modell", np.model ?: "–")
                    ValueRow("Kapazität", Format.energy(np.designCapacityWh))
                    ValueRow("Max. Laden / Entladen", "${Format.power(np.activeChargePowerW)} / ${Format.power(np.activeDischargePowerW)}")
                }
            }
        }
    }
}

@Composable
private fun SetupHint(onOpenSettings: () -> Unit) {
    EnergieCard(title = "Einrichtung") {
        Text("Noch keine Quelle eingerichtet. Trage den SENEC-Schlüssel und die FRITZ!Box-Zugangsdaten ein, dann geht es los.")
        Button(onClick = onOpenSettings) { Text("Zu den Einstellungen") }
    }
}

@Composable
private fun ErrorCard(source: String, message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column {
                Text(source, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun BatteryAndGridRow(live: LiveState) {
    val s = live.sample
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EnergieCard(Modifier.weight(1f), title = "Speicher", accent = EnergyColors.battery) {
            val soc = s?.batterySocPercent
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RingGauge(((soc ?: 0.0) / 100).toFloat(), EnergyColors.battery, Modifier.size(84.dp), strokeWidth = 10.dp) {
                    Text(Format.percentValue(soc), style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    val p = s?.batteryPowerW
                    Text(
                        when {
                            p == null -> "–"
                            p > 15 -> "lädt"
                            p < -15 -> "gibt ab"
                            else -> "ruht"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(Format.power(p?.let { abs(it) }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // SENEC liefert den Zustand als Zahlencode; nur Klartext anzeigen.
                    s?.batteryState?.takeIf { st -> st.any { ch -> ch.isLetter() } }?.let {
                        Text(it.lowercase().replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        EnergieCard(Modifier.weight(1f), title = "Netz", accent = EnergyColors.grid) {
            val meter = s?.meterGridPowerW
            val senec = s?.senecGridPowerW
            val grid = meter ?: senec
            BigValue(
                Format.power(grid?.let { abs(it) }),
                when {
                    grid == null -> "keine Daten"
                    grid < -15 -> "Einspeisung"
                    grid > 15 -> "Bezug"
                    else -> "ausgeglichen"
                },
                color = if ((grid ?: 0.0) < -15) EnergyColors.export else EnergyColors.grid,
            )
            if (meter != null && senec != null) {
                Text(
                    "Zähler ${Format.power(meter, signed = true)} · SENEC ${Format.power(senec, signed = true)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (meter != null) {
                Text("vom Stromzähler", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TodayCard(stats: DayStatistics?, settings: Settings) {
    EnergieCard(title = "Heute") {
        if (stats == null || stats.sampleCount == 0) {
            Text("Noch keine Messpunkte für heute.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@EnergieCard
        }
        val t = stats.totals
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(t.productionWh), "Erzeugt", EnergyColors.sun, Modifier.weight(1f))
            BigValue(Format.energy(t.consumptionWh), "Verbraucht", EnergyColors.house, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue(Format.energy(t.gridImportWh), "Bezogen", EnergyColors.grid, Modifier.weight(1f))
            BigValue(Format.energy(t.gridExportWh), "Eingespeist", EnergyColors.export, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        ShareBar("Autarkie", t.selfSufficiency, EnergyColors.battery)
        ShareBar("Eigenverbrauch", t.selfConsumptionShare, EnergyColors.sun)
        Spacer(Modifier.height(4.dp))
        stats.peakConsumption?.let {
            ValueRow("Höchster Verbrauch", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.Home, iconTint = EnergyColors.house)
        }
        stats.peakProduction?.let {
            ValueRow("Höchste Erzeugung", Format.power(it.value), "um ${Format.time(it.at)}", icon = Icons.Rounded.WbSunny, iconTint = EnergyColors.sun)
        }
        if (t.carChargeWh > 50) {
            ValueRow("Ins Auto geladen", Format.energy(t.carChargeWh), icon = Icons.Rounded.ElectricCar, iconTint = EnergyColors.car)
        }
        stats.baseLoadW?.let {
            ValueRow("Grundlast", Format.power(it), "kleinstes 15-min-Mittel", icon = Icons.Rounded.Bolt, iconTint = EnergyColors.neutral)
        }
        stats.socMin?.let { mn ->
            stats.socMax?.let { mx ->
                ValueRow("Speicher", "${Format.percentValue(mn.value)} – ${Format.percentValue(mx.value)}", "Tief um ${Format.time(mn.at)}, Hoch um ${Format.time(mx.at)}", icon = Icons.Rounded.BatteryChargingFull, iconTint = EnergyColors.battery)
            }
        }
        val cost = t.gridImportWh / 1000 * settings.pricePerKwh
        val income = t.gridExportWh / 1000 * settings.feedInPerKwh
        val saved = t.selfConsumptionWh / 1000 * settings.pricePerKwh
        ValueRow("Stromkosten heute", Format.euro(cost), "Einspeisung ${Format.euro(income)} · gespart ${Format.euro(saved)}")
    }
}

@Composable
private fun CarCard(
    live: LiveState,
    settings: Settings,
    onOverride: (Boolean) -> Unit = {},
    onCommand: (FordCommand) -> Unit = {},
    commandResult: String? = null,
    onClose: () -> Unit = {},
    onSavePlaces: (List<NamedPlace>) -> Unit = {},
) {
    val car = live.car
    var confirmUnlock by rememberSaveable { mutableStateOf(false) }
    EnergieCard(title = "Auto im Detail", accent = EnergyColors.car, border = EnergyColors.car, onClose = onClose) {
        if (car == null) {
            Text(
                live.carError ?: "Noch keine Daten vom Auto. Unter Einstellungen → FordPass „Status lesen“.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@EnergieCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RingGauge(((car.socPercent ?: 0.0) / 100).toFloat(), EnergyColors.car, Modifier.size(84.dp), strokeWidth = 10.dp) {
                Text(Format.percentValue(car.socPercent), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        car.isCharging == true -> "lädt"
                        car.isPluggedIn == true -> "steckt, lädt nicht"
                        car.isPluggedIn == false -> "nicht angeschlossen"
                        else -> "Status unbekannt"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                car.rangeKm?.let { Text("Reichweite ${it.toInt()} km", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val p = live.sample?.carChargePowerW ?: car.chargePowerW
                if (car.isCharging == true) {
                    Text(
                        "Ladeleistung ${Format.power(p ?: settings.carFallbackPowerW.toDouble())}" + if (car.chargePowerW == null) " (angenommen)" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                car.chargeLimitPercent?.let { Text("Ladeziel ${Format.percentValue(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Stand ${Format.time(car.at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        car.lockState?.let { lock ->
            val locked = lock == "LOCKED"
            val lockAt = car.extra?.lockUpdatedAt
            ValueRow(
                "Verriegelung",
                when (lock) { "LOCKED" -> "abgeschlossen"; "PARTLY_LOCKED" -> "teilweise offen"; else -> "NICHT abgeschlossen" },
                if (lockAt != null) "von Ford gemeldet ${Format.dateTime(lockAt)}" else "Stand ${Format.time(car.at)}",
                color = if (locked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                icon = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                iconTint = if (locked) EnergyColors.battery else MaterialTheme.colorScheme.error,
            )
            if (settings.fordConnected) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCommand(FordCommand.STATUS_REFRESH) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Auto wecken")
                    }
                    if (!locked) {
                        Button(onClick = { onCommand(FordCommand.LOCK) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Abschließen")
                        }
                    } else {
                        OutlinedButton(onClick = { confirmUnlock = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.LockOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Aufschließen")
                        }
                    }
                }
            }
        }
        commandResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (confirmUnlock) {
            AlertDialog(
                onDismissRequest = { confirmUnlock = false },
                title = { Text("Auto aufschließen?") },
                text = { Text("Das Auto wird über FordPass entriegelt, auch wenn niemand daneben steht.") },
                confirmButton = { TextButton(onClick = { confirmUnlock = false; onCommand(FordCommand.UNLOCK) }) { Text("Aufschließen") } },
                dismissButton = { TextButton(onClick = { confirmUnlock = false }) { Text("Abbrechen") } },
            )
        }
        val carLat = car.latitude
        val carLon = car.longitude
        if (carLat != null && carLon != null) {
            CarLocation(carLat, carLon, car.distanceHomeM, settings.places, onSavePlaces)
        }
        car.extra?.let { CarExtrasSection(it) }
        live.carError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (settings.fordConnected) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Jetzt voll laden", style = MaterialTheme.typography.titleSmall)
                    Text("Automatik aussetzen, bis das Auto abgesteckt wird", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.chargeOverride, onCheckedChange = onOverride)
            }
            Text(
                if (settings.chargeRules.enabled) "Automatik: ${live.automationStatus ?: "wartet auf erste Messung"}" else "Automatik aus",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Standort des Autos: zu Hause, ein gemerkter Ort (Plus-Knopf zum Anlegen,
 * Stift zum Umbenennen oder Loeschen) oder Adresse, plus Sprung in die Karten-App.
 */
@Composable
private fun CarLocation(lat: Double, lon: Double, distanceHomeM: Double?, places: List<NamedPlace>, onSavePlaces: (List<NamedPlace>) -> Unit) {
    val context = LocalContext.current
    val matched = remember(places, lat, lon) { Places.match(places, lat, lon) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // Adresse per Geocoder nachschlagen; schlaegt das fehl, bleiben die Koordinaten.
    val address by produceState<String?>(initialValue = null, lat, lon) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, java.util.Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.thoroughfare?.let { t -> t + (a.subThoroughfare?.let { " $it" } ?: "") }, a.locality).joinToString(", ")
                }
            }.getOrNull()
        }
    }
    val atHome = distanceHomeM != null && distanceHomeM < 300
    val where = when {
        atHome -> "zu Hause"
        matched != null -> "bei ${matched.name}"
        distanceHomeM != null && distanceHomeM < 10_000 -> "unterwegs, ${(distanceHomeM / 100).toInt() / 10.0} km von zu Hause"
        distanceHomeM != null -> "unterwegs, ${(distanceHomeM / 1000).toInt()} km von zu Hause"
        else -> "Standort"
    }
    val coords = String.format(java.util.Locale.GERMANY, "%.5f, %.5f", lat, lon)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(where, style = MaterialTheme.typography.titleSmall)
            Text(address ?: coords, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!atHome) {
            IconButton(onClick = { editing = true }) {
                Icon(if (matched != null) Icons.Rounded.Edit else Icons.Rounded.AddLocationAlt, if (matched != null) "Ort bearbeiten" else "Ort merken")
            }
        }
        TextButton(onClick = {
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Auto)")
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }) { Text("Karte") }
    }
    if (editing) {
        var name by rememberSaveable { mutableStateOf(matched?.name ?: address?.substringBefore(",") ?: "") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (matched != null) "Ort bearbeiten" else "Ort merken") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Steht das Auto künftig im Umkreis von 200 m um diese Stelle, zeigt die App den Namen statt der Adresse.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name, z. B. Arbeit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(address ?: coords, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        // Beim Umbenennen bleibt die gemerkte Position, sonst die aktuelle.
                        val base = matched?.let { Places.remove(places, it) } ?: places
                        onSavePlaces(Places.upsert(base, NamedPlace(name.trim(), matched?.latitude ?: lat, matched?.longitude ?: lon)))
                        editing = false
                    },
                ) { Text("Speichern") }
            },
            dismissButton = {
                Row {
                    if (matched != null) {
                        TextButton(onClick = { onSavePlaces(Places.remove(places, matched)); editing = false }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { editing = false }) { Text("Abbrechen") }
                }
            },
        )
    }
}

@Composable
private fun MeterCard(live: LiveState) {
    val s = live.sample
    EnergieCard(title = "Stromzähler", accent = EnergyColors.grid) {
        ValueRow("Bezug (1.8.0)", Format.meterReading(s?.meterImportWh), icon = Icons.Rounded.Speed, iconTint = EnergyColors.grid)
        ValueRow("Einspeisung (2.8.0)", Format.meterReading(s?.meterExportWh), icon = Icons.Rounded.Speed, iconTint = EnergyColors.export)
        live.meter?.let {
            Text(
                "Lesekopf ${it.importAin}" + (it.exportAin?.let { e -> " / $e" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
