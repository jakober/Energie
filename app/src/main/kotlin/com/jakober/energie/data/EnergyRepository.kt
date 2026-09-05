package com.jakober.energie.data

import com.jakober.energie.core.fritz.FritzBoxClient
import com.jakober.energie.core.fritz.FritzDevice
import com.jakober.energie.core.fritz.SmartMeterReading
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.history.EnergyTotals
import com.jakober.energie.core.history.HistoryStore
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.core.rules.ChargeAction
import com.jakober.energie.core.rules.ChargeInput
import com.jakober.energie.core.rules.ChargeRuleEngine
import com.jakober.energie.core.senec.SenecConnectClient
import com.jakober.energie.core.senec.SenecSystem
import com.jakober.energie.core.fordpass.FordCarState
import com.jakober.energie.core.fordpass.FordChargeLocation
import com.jakober.energie.core.fordpass.FordCommandResult
import com.jakober.energie.core.fordpass.FordPassClient
import com.jakober.energie.core.fordpass.FordTokens
import com.jakober.energie.core.smartcar.CarState
import com.jakober.energie.core.smartcar.distanceMeters
import kotlinx.serialization.json.Json
import com.jakober.energie.core.smartcar.CommandResult
import com.jakober.energie.core.smartcar.ConnectionsResult
import com.jakober.energie.core.smartcar.SmartcarClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.jakober.energie.core.alerts.Alert
import com.jakober.energie.core.history.ChargePowerLearner
import com.jakober.energie.core.alerts.AlertEngine
import com.jakober.energie.core.alerts.AlertInput
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Der aktuelle Zustand, wie ihn die Oberflaeche zeigt. */
data class LiveState(
    val sample: EnergySample? = null,
    val senec: SenecSystem? = null,
    val meter: SmartMeterReading? = null,
    val fritzDevices: List<FritzDevice> = emptyList(),
    val senecError: String? = null,
    val fritzError: String? = null,
    val car: CarState? = null,
    val carError: String? = null,
    val lastUpdate: Instant? = null,
    val refreshing: Boolean = false,
    /** Letzte Rohantwort von SENEC, fuer die Ansicht in den Einstellungen. */
    val senecRaw: String? = null,
    /** Letzte Entscheidung der Ladeautomatik in Worten. */
    val automationStatus: String? = null,
)

/**
 * Holt die Werte beider Quellen, fuehrt sie zu einem Messpunkt zusammen und
 * legt ihn im Verlauf ab. Wird sowohl vom Bildschirm (regelmaessig, solange
 * die App offen ist) als auch vom Hintergrund-Worker aufgerufen.
 */
class EnergyRepository(
    private val settings: AppSettings,
    private val http: HttpClient,
    val history: HistoryStore,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(LiveState(sample = history.latest(), lastUpdate = history.latest()?.at))
    val state: StateFlow<LiveState> = _state

    private val lock = Mutex()
    private var fritz: FritzBoxClient? = null
    private var fritzKey: String? = null
    private var smartcar: SmartcarClient? = null
    private var smartcarKey: String? = null
    private var lastCarFetch: Instant? = null
    private var lastStored: Instant? = history.latest()?.at

    /** Fragt beide Quellen ab. Fehler einer Quelle blockieren die andere nicht. */
    suspend fun refresh(): LiveState = lock.withLock {
        val s = settings.current()
        _state.update { it.copy(refreshing = true) }
        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                val senecJob = async { if (s.senecConfigured) runCatching { fetchSenec(s) } else null }
                val fritzJob = async { if (s.fritzConfigured) runCatching { fetchFritz(s) } else null }
                // Das Auto seltener: Smartcar zaehlt Aufrufe, und der Ladezustand aendert sich langsam.
                val carJob = async {
                    val due = lastCarFetch?.let { clock.now() - it >= CAR_INTERVAL } ?: true
                    if ((s.carConnected || s.fordConnected) && due) runCatching { fetchCar(s) } else null
                }
                Triple(senecJob.await(), fritzJob.await(), carJob.await())
            }
        }
        val (senecResult, fritzResult, carResult) = result
        val now = clock.now()
        val car = carResult?.getOrNull()
        if (carResult != null) lastCarFetch = now
        val carForSample = car ?: _state.value.car?.takeIf { now - it.at < 30.minutes }
        val senec = senecResult?.getOrNull()
        val fritzData = fritzResult?.getOrNull()
        val meter = fritzData?.second
        // Hausverbrauch bevorzugt aus der Bilanz mit dem geeichten Zaehler:
        // Verbrauch = PV + Netz (Bezug positiv) - Speicherleistung (Laden positiv).
        // SENECs Verbrauchsfeld und Netzfeld stammen aus verschiedenen Momenten
        // und passen nicht immer zusammen.
        val production = senec?.system?.meter?.production
        val batteryPower = senec?.system?.battery?.power
        val consumption = if (meter != null && production != null && batteryPower != null) {
            (production + meter.gridPowerWatt - batteryPower).coerceAtLeast(0.0)
        } else senec?.system?.meter?.consumption

        val consumptionNow = consumption
        val carPowerW = carChargePower(carForSample, consumptionNow, s.carAssumedPowerW)

        val sample = EnergySample(
            at = now,
            batterySocPercent = senec?.system?.battery?.stateOfCharge,
            batteryPowerW = batteryPower,
            batteryState = senec?.system?.battery?.state,
            productionW = production,
            consumptionW = consumption,
            senecGridPowerW = senec?.system?.meter?.gridPower,
            evseChargingPowerW = senec?.system?.evse?.firstOrNull()?.chargingPower,
            evConnected = senec?.system?.evse?.firstOrNull()?.evConnected,
            meterGridPowerW = meter?.gridPowerWatt,
            meterImportWh = meter?.importEnergyWh,
            meterExportWh = meter?.exportEnergyWh,
            carSocPercent = carForSample?.socPercent,
            carCharging = carForSample?.isCharging,
            carPluggedIn = carForSample?.isPluggedIn,
            carChargePowerW = carPowerW,
        )

        // Ladestart erkannt: aus dem Sprung im Hausverbrauch die echte Ladeleistung lernen.
        val wasCharging = (_state.value.sample?.carChargePowerW ?: 0.0) > 0
        if (carPowerW != null && !wasCharging && sample.hasMeter) {
            runCatching {
                val before = history.range(now - 35.minutes, now)
                ChargePowerLearner.estimate(before, sample)?.let { learned ->
                    val blended = ChargePowerLearner.blend(s.carLearnedPowerW.takeIf { it > 0 }, learned)
                    settings.saveCarLearnedPower(blended)
                }
            }
        }

        val gotSomething = sample.hasSenec || sample.hasMeter
        if (gotSomething && shouldStore(now)) {
            history.append(sample)
            lastStored = now
        }

        val newState = LiveState(
            sample = if (gotSomething) sample else _state.value.sample,
            senec = senec?.system ?: if (senecResult == null) null else _state.value.senec,
            meter = meter ?: if (fritzResult == null) null else _state.value.meter,
            fritzDevices = fritzData?.first ?: _state.value.fritzDevices,
            senecError = senecResult?.exceptionOrNull()?.let { it.message ?: it.toString() },
            fritzError = fritzResult?.exceptionOrNull()?.let { it.message ?: it.toString() },
            car = car ?: _state.value.car,
            carError = carResult?.exceptionOrNull()?.let { it.message ?: it.toString() } ?: if (carResult == null) _state.value.carError else null,
            lastUpdate = if (gotSomething) now else _state.value.lastUpdate,
            refreshing = false,
            senecRaw = senec?.raw ?: _state.value.senecRaw,
        )
        if (senec != null) lastSenecOkAt = now
        if (fritzData != null) lastFritzOkAt = now
        _state.value = newState
        val automationLine = runCatching { runAutomation(s, newState) }.getOrNull()
        runCatching { runAlerts(newState, automationLine) }
        if (gotSomething) onWidgetUpdate?.let { cb -> runCatching { cb(sample, newState.car) } }
        _state.value
    }

    /** Wird nach jeder Messung gerufen, damit das Homescreen-Widget nachzieht. */
    var onWidgetUpdate: (suspend (EnergySample, CarState?) -> Unit)? = null

    private var lastSenecOkAt: Instant? = null
    private var lastFritzOkAt: Instant? = null

    /** Wer die Hinweise anzeigt (Benachrichtigungen); ohne Empfaenger passiert nichts. */
    var onAlerts: ((List<Alert>) -> Unit)? = null

    private suspend fun runAlerts(live: LiveState, automationLine: String?) {
        val sink = onAlerts ?: return
        val s = settings.current()
        val car = live.car
        val input = AlertInput(
            now = clock.now(),
            batterySocPercent = live.sample?.batterySocPercent,
            gridPowerW = live.sample?.gridPowerW,
            carPluggedIn = car?.isPluggedIn,
            carCharging = car?.isCharging,
            carLockState = car?.lockState,
            carDistanceHomeM = car?.distanceHomeM,
            chargeOverride = s.chargeOverride,
            senecConfigured = s.senecConfigured,
            fritzConfigured = s.fritzConfigured,
            lastSenecOkAt = lastSenecOkAt,
            lastFritzOkAt = lastFritzOkAt,
            automationLine = automationLine,
        )
        val result = AlertEngine.evaluate(input, s.alertState, s.alerts)
        if (result.state != s.alertState) settings.saveAlertState(result.state)
        if (result.alerts.isNotEmpty()) sink(result.alerts)
    }

    /**
     * Ladeautomatik: entscheidet aus Speicher, Netz und Autozustand, ob das Auto
     * pausieren oder weiterladen soll, und schickt den Befehl ueber FordPass.
     */
    private suspend fun runAutomation(s0: Settings, live: LiveState): String? {
        val s = settings.current() // Regeln koennten sich seit Beginn des Refresh geaendert haben
        if (!s.fordConnected || !s.chargeRules.enabled) return null
        val car = live.car ?: return null
        val sample = live.sample
        val now = clock.now()

        // Abgesteckt: Handschalter zuruecksetzen.
        if (car.isPluggedIn == false && s.chargeOverride) settings.saveChargeOverride(false)

        val input = ChargeInput(
            now = now,
            localTime = now.toLocalDateTime(TimeZone.currentSystemDefault()).time,
            houseBatteryPercent = sample?.batterySocPercent,
            gridPowerW = sample?.gridPowerW,
            carSocPercent = car.socPercent,
            carPluggedIn = car.isPluggedIn,
            carCharging = car.isCharging,
            carChargePowerW = sample?.carChargePowerW ?: car.chargePowerW ?: s.carAssumedPowerW,
            lastCommandAt = s.chargeLastCommandAt.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) },
            overrideFullCharge = s.chargeOverride && car.isPluggedIn != false,
        )
        val decision = ChargeRuleEngine.decide(s.chargeRules, input)
        val time = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
        val stamp = "%02d:%02d".format(time.hour, time.minute)

        return when (decision.action) {
            ChargeAction.NONE -> { _state.update { it.copy(automationStatus = decision.reason) }; null }
            ChargeAction.PAUSE, ChargeAction.RESUME -> {
                val result = withContext(Dispatchers.IO) {
                    if (decision.action == ChargeAction.PAUSE) fordpass(s).pauseCharge(s.fordVin) else fordpass(s).startCharge(s.fordVin)
                }
                val verb = if (decision.action == ChargeAction.PAUSE) "Pausiert" else "Fortgesetzt"
                val line = if (result.accepted) "$stamp $verb: ${decision.reason}" else "$stamp $verb FEHLGESCHLAGEN (HTTP ${result.status}): ${decision.reason}"
                settings.noteChargeCommand(now.epochSeconds)
                settings.appendChargeLog(line)
                _state.update { it.copy(automationStatus = line) }
                // Den neuen Zustand bald nachlesen, nicht erst in 5 Minuten.
                lastCarFetch = now - CAR_INTERVAL + 90.seconds
                line
            }
        }
    }

    /** Hoechstens ein gespeicherter Messpunkt je Minute, damit der Verlauf klein bleibt. */
    private fun shouldStore(now: Instant): Boolean {
        val last = lastStored ?: return true
        return now - last >= MIN_STORE_INTERVAL
    }

    private class SenecResult(val system: SenecSystem, val raw: String)

    private suspend fun fetchSenec(s: Settings): SenecResult {
        val client = SenecConnectClient(http, s.senecKey, s.senecBaseUrl)
        val raw = client.rawGeneral()
        val systems = client.parse(raw)
        val system = systems.firstOrNull() ?: throw IllegalStateException("SENEC liefert keine Anlage fuer dieses Konto.")
        return SenecResult(system, raw)
    }

    private suspend fun fetchFritz(s: Settings): Pair<List<FritzDevice>, SmartMeterReading?> {
        val key = "${s.fritzHost}|${s.fritzUser}|${s.fritzPassword}"
        val client = fritz?.takeIf { fritzKey == key } ?: FritzBoxClient(http, s.fritzHost, s.fritzUser, s.fritzPassword, clock).also {
            fritz = it
            fritzKey = key
        }
        val devices = client.deviceList()
        return devices to client.smartMeter(devices)
    }

    /**
     * Ladeleistung, die dem Haus zugerechnet wird: gemessen, sonst der
     * Annahmewert, solange das Auto laedt. Zieht der Haushalt laut SENEC
     * deutlich weniger als diese Leistung, laedt das Auto offenbar woanders -
     * dann zaehlt es nicht.
     */
    private fun carChargePower(car: CarState?, consumptionW: Double?, fallbackW: Double): Double? {
        if (car == null || car.isCharging != true) return null
        // Weit weg von zu Hause laedt das Auto woanders - nicht dem Haus zurechnen.
        car.distanceHomeM?.let { if (it > 300) return null }
        val p = car.chargePowerW?.takeIf { it > 100 } ?: fallbackW
        if (consumptionW != null && consumptionW < p * 0.8) return null
        return p
    }

    // --- FordPass (inoffiziell) ---

    private var ford: FordPassClient? = null
    private var fordKey: String? = null
    private val tokenJson = Json { ignoreUnknownKeys = true }

    fun fordpass(s: Settings): FordPassClient {
        val key = s.fordTokensJson
        ford?.takeIf { fordKey == key }?.let { return it }
        val tokens = s.fordTokensJson.takeIf { it.isNotBlank() }?.let { runCatching { tokenJson.decodeFromString(FordTokens.serializer(), it) }.getOrNull() }
        return FordPassClient(http, tokens, onTokens = { t ->
            val encoded = tokenJson.encodeToString(FordTokens.serializer(), t)
            fordKey = encoded
            settings.saveFordTokens(encoded)
        }, clock = clock).also {
            ford = it
            fordKey = key
        }
    }

    private fun FordCarState.toCarState(s: Settings): CarState {
        val lat = latitude
        val lon = longitude
        return CarState(
        at = at,
        vehicleId = vin,
        socPercent = socPercent,
        rangeKm = rangeKm,
        isCharging = isCharging,
        isPluggedIn = isPluggedIn,
        chargeLimitPercent = null,
        chargingStatus = listOfNotNull(chargeStatus, plugStatus).joinToString(" / ").ifBlank { null },
        chargePowerW = chargePowerW?.let { it / SmartcarClient.CHARGER_EFFICIENCY },
        latitude = latitude,
        longitude = longitude,
        lockState = lockState,
        extra = extra,
        distanceHomeM = if (lat != null && lon != null && (s.homeLat != 0.0 || s.homeLon != 0.0)) distanceMeters(lat, lon, s.homeLat, s.homeLon) else null,
        raw = mapOf("fordpass-telemetry" to raw),
        )
    }

    suspend fun fordRefreshNow(s: Settings): CarState = withContext(Dispatchers.IO) {
        val state = fetchCar(s)
        lastCarFetch = clock.now()
        _state.update { it.copy(car = state, carError = null) }
        state
    }

    suspend fun fordLocations(s: Settings): List<FordChargeLocation> = withContext(Dispatchers.IO) { fordpass(s).chargeLocations(s.fordVin) }

    suspend fun fordCommand(s: Settings, command: FordCommand): FordCommandResult = withContext(Dispatchers.IO) {
        val client = fordpass(s)
        when (command) {
            FordCommand.PAUSE -> client.pauseCharge(s.fordVin)
            FordCommand.RESUME -> client.startCharge(s.fordVin)
            FordCommand.CANCEL -> client.cancelCharge(s.fordVin)
            FordCommand.LOCK -> client.lock(s.fordVin)
            FordCommand.UNLOCK -> client.unlock(s.fordVin)
            FordCommand.STATUS_REFRESH -> client.statusRefresh(s.fordVin)
            FordCommand.TARGET_50, FordCommand.TARGET_100 -> {
                val locations = client.chargeLocations(s.fordVin)
                val loc = locations.firstOrNull { it.id == s.fordLocationId }
                    ?: locations.firstOrNull { it.type?.uppercase() == "HOME" }
                    ?: locations.firstOrNull()
                    ?: return@withContext FordCommandResult(404, "Ford kennt fuer dieses Auto keinen Ladeort. In der FordPass-App unter Laden einen Ladeort 'Zuhause' anlegen.")
                if (loc.id != s.fordLocationId) settings.saveFordLocation(loc.id)
                client.setTargetSoc(s.fordVin, loc, if (command == FordCommand.TARGET_50) 50 else 100)
            }
        }
    }

    fun smartcar(s: Settings): SmartcarClient {
        val key = "${s.smartcarClientId}|${s.smartcarClientSecret}"
        return smartcar?.takeIf { smartcarKey == key } ?: SmartcarClient(http, s.smartcarClientId, s.smartcarClientSecret, clock = clock).also {
            smartcar = it
            smartcarKey = key
        }
    }

    private suspend fun fetchCar(s: Settings): CarState {
        // FordPass liefert direkt vom Hersteller und zaehlt nicht aufs Smartcar-Kontingent;
        // wenn angemeldet, hat es Vorrang.
        if (s.fordConnected) {
            val state = fordpass(s).state(s.fordVin)
            var home = s
            // Zuhause einmal aus Fords Ladeorten lernen (Ort vom Typ HOME).
            if (s.homeLat == 0.0 && s.homeLon == 0.0) {
                runCatching { fordpass(s).chargeLocations(s.fordVin) }.getOrNull()
                    ?.let { list -> list.firstOrNull { it.type?.uppercase() == "HOME" } ?: list.firstOrNull() }
                    ?.let { loc ->
                        val location = loc.raw["location"] as? kotlinx.serialization.json.JsonObject
                        val lat = (location?.get("latitude") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                        val lon = (location?.get("longitude") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                        if (lat != null && lon != null) { settings.saveHome(lat, lon); home = s.copy(homeLat = lat, homeLon = lon) }
                    }
            }
            return state.toCarState(home)
        }
        val client = smartcar(s)
        val state = client.state(s.smartcarVehicleId, s.smartcarUserId.ifBlank { null })
        // Alle Signale gescheitert mit 404: Das Fahrzeug hat bei Smartcar eine neue ID
        // (etwa nach Trennen und Neuverbinden). Verbindungen neu lesen und uebernehmen.
        val allMissing = state.raw.isNotEmpty() && state.raw.values.all { it.contains("Fehler") && (it.contains(" 404") || it.contains("NOT_FOUND")) }
        if (allMissing) {
            val fresh = client.connections().connections.firstOrNull()
            if (fresh != null && fresh.vehicleId != s.smartcarVehicleId) {
                settings.saveCar(fresh.vehicleId, fresh.userId)
                return client.state(fresh.vehicleId, fresh.userId)
            }
        }
        return state
    }

    suspend fun carConnections(s: Settings): ConnectionsResult = withContext(Dispatchers.IO) { smartcar(s).connections() }

    /** Naechster Refresh holt das Auto sofort, unabhaengig vom Intervall (Aktualisieren-Knopf). */
    fun forceCarOnNextRefresh() { lastCarFetch = null }

    /** Liest den Autozustand sofort, unabhaengig vom Intervall, und uebernimmt ihn. */
    suspend fun carRefreshNow(s: Settings): CarState = withContext(Dispatchers.IO) {
        val state = fetchCar(s)
        lastCarFetch = clock.now()
        _state.update { it.copy(car = state, carError = null) }
        state
    }

    suspend fun carCommand(s: Settings, command: CarCommand): CommandResult = withContext(Dispatchers.IO) {
        val client = smartcar(s)
        val v = s.smartcarVehicleId
        val u = s.smartcarUserId.ifBlank { null }
        when (command) {
            CarCommand.LIMIT_50 -> client.setChargeLimit(v, u, 50)
            CarCommand.LIMIT_100 -> client.setChargeLimit(v, u, 100)
            CarCommand.STOP -> client.stopCharge(v, u)
            CarCommand.START -> client.startCharge(v, u)
        }
    }

    // Vergangene Tage aendern sich nicht mehr - einmal rechnen reicht.
    private val dayCache = java.util.concurrent.ConcurrentHashMap<LocalDate, DayStatistics>()

    fun dayStatistics(date: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): DayStatistics {
        if (date >= today(zone)) return DayStatistics.of(date, history.day(date), zone)
        return dayCache.getOrPut(date) { DayStatistics.of(date, history.day(date), zone) }
    }

    /** Summe ueber alle gespeicherten Tage. */
    fun lifetimeTotals(): EnergyTotals {
        val days = history.days().map { dayStatistics(it).totals }
        return EnergyTotals(
            productionWh = days.sumOf { it.productionWh },
            consumptionWh = days.sumOf { it.consumptionWh },
            gridImportWh = days.sumOf { it.gridImportWh },
            gridExportWh = days.sumOf { it.gridExportWh },
            batteryChargeWh = days.sumOf { it.batteryChargeWh },
            batteryDischargeWh = days.sumOf { it.batteryDischargeWh },
            carChargeWh = days.sumOf { it.carChargeWh },
            carFromGridWh = days.sumOf { it.carFromGridWh },
            meterImportWh = null, meterExportWh = null,
        )
    }

    fun today(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = clock.now().toLocalDateTime(zone).date

    /** Nach einer Wiederherstellung: Statistik neu rechnen und die Ansicht anstossen. */
    fun historyChanged() {
        dayCache.clear()
        val latest = history.latest()
        _state.update { it.copy(sample = latest ?: it.sample, lastUpdate = clock.now()) }
    }

    suspend fun prune() {
        val s = settings.current()
        withContext(Dispatchers.IO) { history.prune(today(), s.keepDays) }
    }

    companion object {
        val MIN_STORE_INTERVAL = 55.seconds
        val CAR_INTERVAL = 5.minutes
    }
}

enum class FordCommand(val label: String) {
    PAUSE("Laden pausieren"),
    RESUME("Laden fortsetzen"),
    CANCEL("Laden abbrechen"),
    TARGET_50("Ladeziel 50 %"),
    TARGET_100("Ladeziel 100 %"),
    LOCK("Abschließen"),
    UNLOCK("Aufschließen"),
    STATUS_REFRESH("Auto wecken"),
}

enum class CarCommand(val label: String) {
    LIMIT_50("Ladeziel 50 %"),
    LIMIT_100("Ladeziel 100 %"),
    STOP("Laden stoppen"),
    START("Laden starten"),
}
