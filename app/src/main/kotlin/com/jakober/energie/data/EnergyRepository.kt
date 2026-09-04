package com.jakober.energie.data

import com.jakober.energie.core.fritz.FritzBoxClient
import com.jakober.energie.core.fritz.FritzDevice
import com.jakober.energie.core.fritz.SmartMeterReading
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.history.EnergyTotals
import com.jakober.energie.core.history.HistoryStore
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.core.senec.SenecConnectClient
import com.jakober.energie.core.senec.SenecSystem
import com.jakober.energie.core.smartcar.CarState
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
                    if (s.carConnected && due) runCatching { fetchCar(s) } else null
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
        val consumptionNow = senec?.system?.meter?.consumption
        val carPowerW = carChargePower(carForSample, consumptionNow, s.carFallbackPowerW.toDouble())

        val sample = EnergySample(
            at = now,
            batterySocPercent = senec?.system?.battery?.stateOfCharge,
            batteryPowerW = senec?.system?.battery?.power,
            batteryState = senec?.system?.battery?.state,
            productionW = senec?.system?.meter?.production,
            consumptionW = senec?.system?.meter?.consumption,
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
        _state.value = newState
        newState
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
        val p = car.chargePowerW?.takeIf { it > 100 } ?: fallbackW
        if (consumptionW != null && consumptionW < p * 0.8) return null
        return p
    }

    fun smartcar(s: Settings): SmartcarClient {
        val key = "${s.smartcarClientId}|${s.smartcarClientSecret}"
        return smartcar?.takeIf { smartcarKey == key } ?: SmartcarClient(http, s.smartcarClientId, s.smartcarClientSecret, clock = clock).also {
            smartcar = it
            smartcarKey = key
        }
    }

    private suspend fun fetchCar(s: Settings): CarState {
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

    suspend fun prune() {
        val s = settings.current()
        withContext(Dispatchers.IO) { history.prune(today(), s.keepDays) }
    }

    companion object {
        val MIN_STORE_INTERVAL = 55.seconds
        val CAR_INTERVAL = 5.minutes
    }
}

enum class CarCommand(val label: String) {
    LIMIT_50("Ladeziel 50 %"),
    LIMIT_100("Ladeziel 100 %"),
    STOP("Laden stoppen"),
    START("Laden starten"),
}
