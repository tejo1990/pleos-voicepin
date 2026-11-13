package io.echolab.pleosecholog.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// Bridge between Pleos Connect Vehicle SDK (v2.3.2) and Virtual Console (127.0.0.1:8088).
class PleosVehicleBridge(private val context: Context) {

    companion object {
        private const val TAG = "PleosVehicleBridge"
        private const val DEBOUNCE_INTERVAL_MS = 100L // Max 10Hz telemetry evaluation (<1% CPU)
        private const val VIRTUAL_CONSOLE_BASE_URL = "http://127.0.0.1:8088"
    }

    data class VehicleCapabilities(
        val steeringWheelSupported: Boolean = true,
        val brakeSupported: Boolean = true,
        val turnSignalSupported: Boolean = true,
        val wiperSupported: Boolean = true,
        val tireSafetySupported: Boolean = true,
        val doorSupported: Boolean = true,
        val drivingModeReadOnlySupported: Boolean = true,
        val windowControlSupported: Boolean = true,
        val hvacControlSupported: Boolean = true,
        val ambientLightSupported: Boolean = true
    )

    data class TelemetrySnapshot(
        val timestampMs: Long,
        val steeringAngleDeg: Float,
        val steeringRateDegPerSec: Float,
        val brakePedalActive: Boolean,
        val hazardLightActive: Boolean,
        val wiperStage: Int,
        val tirePressureWarn: Boolean,
        val safetyEscTelltaleWarn: Boolean = false,
        val driverDoorOpen: Boolean = false,
        val windowClosedMask: BooleanArray,
        val hvacRecirculationActive: Boolean,
        val sccCruiseActiveReadOnly: Boolean = true,
        val gearState: String, // "P", "D", "R", "N"
        val airCleanerActive: Boolean = false
    )

    private var pleosVehicle: Vehicle? = null
    @Volatile private var initialized = false
    private var lastDispatchElapsedMs = 0L
    private var capabilities = VehicleCapabilities()
    private var listener: ((TelemetrySnapshot) -> Unit)? = null
    private var virtualConsolePoller: ScheduledExecutorService? = null

    private var currentSteeringAngle = 0f
    private var lastSteeringTimestampMs = 0L
    private var currentSteeringRate = 0f
    private var currentBrakeActive = false
    private var currentHazardActive = false
    private var currentWiperStage = 0
    private var lowTireThresholdKpa = 200f
    private var currentTirePressureWarn = false
    private var currentEscWarn = false
    private var currentDriverDoorOpen = false
    private var currentSccActive = true
    private var currentAirCleanerActive = false
    private var previousWindowPosition = 35
    private var previousHvacRecirc = false
    private var currentWindowClosed = booleanArrayOf(false, false, false, false)
    private var currentHvacRecirc = false
    private var currentAmbientHex = "#00E5FF"
    private var currentGear = "D"

    // Separate SDK vs Virtual Console state trackers so neither overwrites active warnings
    private var sdkHazardActive = false
    private var vcHazardActive = false
    private var sdkDoorOpen = false
    private var vcDoorOpen = false
    private var sdkTpmsWarn = false
    private var vcTpmsWarn = false
    private var sdkEscWarn = false
    private var vcEscWarn = false
    private var sdkBrakeActive = false
    private var vcBrakeActive = false
    private var sdkWiperStage = 0
    private var vcWiperStage = 0
    private var lastVcSteeringDeg: Float? = null
    private var lastVcGearVal: String = ""

    private val allWindowsArea = WindowArea(
        WindowValue.ROW_1_LEFT,
        WindowValue.ROW_1_RIGHT,
        WindowValue.ROW_2_LEFT,
        WindowValue.ROW_2_RIGHT
    )
    private val frontSeatsArea = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)
    private val allDoorsArea = DoorArea(
        DoorValue.ROW_1_LEFT,
        DoorValue.ROW_1_RIGHT,
        DoorValue.ROW_2_LEFT,
        DoorValue.ROW_2_RIGHT
    )
    private val driverDoorArea = DoorArea(DoorValue.ROW_1_LEFT)
    private val frontLeftWheelArea = WheelArea(WheelValue.LEFT_FRONT)
    private val frontWindshieldArea = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(isPressed: Boolean?) {
            sdkBrakeActive = isPressed == true
            currentBrakeActive = sdkBrakeActive || vcBrakeActive
            dispatchCurrentState(forceImmediate = true)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "BrakePedalStatusListener error: ${e.message}")
        }
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            val now = SystemClock.elapsedRealtime()
            val newAngle = angle ?: 0f
            if (lastSteeringTimestampMs > 0L && now > lastSteeringTimestampMs) {
                val dtSec = (now - lastSteeringTimestampMs) / 1000f
                val delta = kotlin.math.abs(newAngle - currentSteeringAngle)
                if (delta > 0.1f) {
                    currentSteeringRate = delta / dtSec
                } else {
                    currentSteeringRate = 0f
                }
            }
            currentSteeringAngle = newAngle
            lastSteeringTimestampMs = now
            dispatchCurrentState(forceImmediate = false)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "SteeringWheelAngleListener error: ${e.message}")
        }
    }

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            val list = telltales?.telltales ?: emptyList()
            sdkEscWarn = list.any {
                (it.type == TelltaleType.ESC || it.type == TelltaleType.ABS) &&
                        (it.state == TelltaleState.ON || it.state == TelltaleState.BLINK)
            }
            sdkTpmsWarn = list.any {
                it.type == TelltaleType.TPMS &&
                        (it.state == TelltaleState.ON || it.state == TelltaleState.BLINK)
            }
            val doorTelltale = list.any {
                it.type == TelltaleType.DOOR_OPEN &&
                        (it.state == TelltaleState.ON || it.state == TelltaleState.BLINK)
            }
            if (doorTelltale) sdkDoorOpen = true
            currentEscWarn = sdkEscWarn || vcEscWarn
            currentTirePressureWarn = sdkTpmsWarn || vcTpmsWarn
            currentDriverDoorOpen = sdkDoorOpen || vcDoorOpen
            dispatchCurrentState(forceImmediate = true)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "TelltaleInfoListener error: ${e.message}")
        }
    }

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            sdkWiperStage = if (state == WiperState.ON) 3 else 0
            currentWiperStage = maxOf(sdkWiperStage, vcWiperStage)
            dispatchCurrentState(forceImmediate = true)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "WiperStateListener error: ${e.message}")
        }
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            sdkHazardActive = (value != null && value != TurnSignalValue.OFF)
            currentHazardActive = sdkHazardActive || vcHazardActive
            dispatchCurrentState(forceImmediate = true)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "TurnSignalStateListener error: ${e.message}")
        }
    }

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(position: Int?) {
            sdkDoorOpen = (position ?: 0) > 0
            currentDriverDoorOpen = sdkDoorOpen || vcDoorOpen
            dispatchCurrentState(forceImmediate = true)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "DoorPositionListener error: ${e.message}")
        }
    }

    private val tireListener = object : TirePressureListener {
        override fun onTirePressureUpdated(pressureKpa: Float?) {
            if (pressureKpa != null) {
                sdkTpmsWarn = pressureKpa < lowTireThresholdKpa
                currentTirePressureWarn = sdkTpmsWarn || vcTpmsWarn
                dispatchCurrentState(forceImmediate = true)
            }
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "TirePressureListener error: ${e.message}")
        }
    }

    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) {
            currentSccActive = (state == CruiseControl.ACTIVATED || state == CruiseControl.ENABLED)
            dispatchCurrentState(forceImmediate = true)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "CruiseControlStateListener error: ${e.message}")
        }
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            currentGear = when (gear) {
                VehicleGear.GEAR_PARK -> "P"
                VehicleGear.GEAR_REVERSE -> "R"
                VehicleGear.GEAR_NEUTRAL -> "N"
                else -> "D"
            }
            dispatchCurrentState(forceImmediate = true)
        }
        override fun onFailed(e: Exception) {
            Log.w(TAG, "GearSelectionListener error: ${e.message}")
        }
    }

    fun initialize(onReady: (VehicleCapabilities) -> Unit) {
        Log.i(TAG, "Vehicle(context).initialize() -> Binding to official Pleos Connect Vehicle SDK v2.3.2")
        initialized = true
        runCatching {
            val vehicle = Vehicle(context)
            pleosVehicle = vehicle
            vehicle.initialize()

            // Capability verification & initial state fetch
            vehicle.getBrake().checkBrakePedalStatusCapabilities({ _ -> }, { _ -> })
            vehicle.getBrake().getCurrentBrakePedalStatus({ active ->
                sdkBrakeActive = active == true
                currentBrakeActive = sdkBrakeActive || vcBrakeActive
                dispatchCurrentState(forceImmediate = true)
            }, { _ -> })
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)

            vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities({ _, _ -> }, { _ -> })
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ angle ->
                currentSteeringAngle = angle ?: 0f
            }, { _ -> })
            vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)

            vehicle.getSafety().checkTelltalesCapabilities({ _ -> }, { _ -> })
            vehicle.getSafety().getTelltalesInfo({ info -> telltaleListener.onTelltaleInfoUpdated(info) }, { _ -> })
            vehicle.getSafety().registerTelltales(telltaleListener)

            vehicle.getWiper().registerWiperState(frontWindshieldArea, wiperListener)

            vehicle.getTurnSignal().getCurrentTurnSignalState({ v ->
                sdkHazardActive = (v != null && v != TurnSignalValue.OFF)
                currentHazardActive = sdkHazardActive || vcHazardActive
                dispatchCurrentState(forceImmediate = true)
            }, { _ -> })
            vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener)

            vehicle.getDoor().getCurrentDoorPosition(driverDoorArea, { pos ->
                sdkDoorOpen = (pos ?: 0) > 0
                currentDriverDoorOpen = sdkDoorOpen || vcDoorOpen
                dispatchCurrentState(forceImmediate = true)
            }, { _ -> })
            vehicle.getDoor().registerDoorPosition(allDoorsArea, doorListener)

            vehicle.getTire().getLowTirePressure(frontLeftWheelArea, { threshold -> lowTireThresholdKpa = threshold ?: 190f }, { _ -> })
            vehicle.getTire().registerTirePressure(frontLeftWheelArea, tireListener)

            vehicle.getDrivingMode().getCruiseControlState({ state ->
                currentSccActive = (state == CruiseControl.ACTIVATED || state == CruiseControl.ENABLED)
            }, { _ -> })
            vehicle.getDrivingMode().registerCruiseControlState(cruiseListener)

            vehicle.getGear().getCurrentGearSelectionState({ g -> gearListener.onSelected(g) }, { _ -> })
            vehicle.getGear().registerGearSelection(gearListener)

            vehicle.getWindow().checkWindowPositionCapabilities({ _ -> }, { _ -> })
            vehicle.getHVAC().checkHvacRecirculationCapabilities({ _ -> }, { _ -> })
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ _, _ -> }, { _ -> })
        }.onFailure { e ->
            Log.i(TAG, "Pleos Vehicle Service fallback active (Virtual Console / Simulator mode): ${e.message}")
        }

        startVirtualConsoleSyncLoop()
        onReady(capabilities)
    }

    // Polls Virtual Console state every 450ms and keeps the DDS emulation session alive.
    private fun startVirtualConsoleSyncLoop() {
        virtualConsolePoller?.shutdownNow()
        val executor = Executors.newSingleThreadScheduledExecutor()
        virtualConsolePoller = executor
        executor.scheduleWithFixedDelay({
            if (!initialized) return@scheduleWithFixedDelay
            runCatching {
                // 1. Keep Virtual Console DDS publishers active
                val hbConn = (URL("$VIRTUAL_CONSOLE_BASE_URL/browser/session?client=pleos-voicepin-bridge").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 400
                    readTimeout = 400
                }
                hbConn.responseCode
                hbConn.disconnect()

                // 2. Read live Virtual Console state
                val stateConn = (URL("$VIRTUAL_CONSOLE_BASE_URL/vehicle/state").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 400
                    readTimeout = 400
                }
                if (stateConn.responseCode == 200) {
                    val jsonStr = stateConn.inputStream.bufferedReader().use { it.readText() }
                    stateConn.disconnect()
                    parseVirtualConsoleState(jsonStr)
                } else {
                    stateConn.disconnect()
                }
            }
        }, 100L, 450L, TimeUnit.MILLISECONDS)
    }

    private fun parseVirtualConsoleState(jsonStr: String) {
        val root = JSONObject(jsonStr)

        // 1. Lights: Hazard Lights (`lights.hazard_on`) & Turn Signal (`lights.turn_signal_mode`) & Stop Light
        val lights = root.optJSONObject("lights")
        val hazardOn = lights?.optBoolean("hazard_on", false) ?: false
        val turnMode = lights?.optString("turn_signal_mode", "Off") ?: "Off"
        val stopLightOn = lights?.optBoolean("stop_light_on", false) ?: false
        vcHazardActive = hazardOn || (turnMode != "Off" && turnMode.isNotEmpty())

        // 2. Body: Doors (`front_left`, `front_right`, `rear_left`, `rear_right`), Wipers, Windows
        val body = root.optJSONObject("body")
        val doors = body?.optJSONObject("doors")
        val anyPassengerDoorOpen = if (doors != null) {
            doors.optBoolean("front_left", false) ||
                    doors.optBoolean("front_right", false) ||
                    doors.optBoolean("rear_left", false) ||
                    doors.optBoolean("rear_right", false)
        } else false

        val wipersFrontMode = body?.optJSONObject("wipers")?.optJSONObject("front")?.optString("mode", "Off") ?: "Off"
        vcWiperStage = if (wipersFrontMode != "Off" && wipersFrontMode.isNotEmpty()) 3 else 0

        // 3. Telltales: `TPMS`, `ABS`, `ESC`, `DoorOpen`
        val telltalesActual = root.optJSONObject("telltales")?.optJSONObject("actual")
        fun isTelltaleActive(key: String): Boolean {
            val state = telltalesActual?.optJSONObject(key)?.optString("state", "Off") ?: "Off"
            return state == "On" || state == "Blink"
        }
        vcTpmsWarn = isTelltaleActive("TPMS")
        vcEscWarn = isTelltaleActive("ESC") || isTelltaleActive("ABS")
        val vcDoorTelltale = isTelltaleActive("DoorOpen")
        vcDoorOpen = anyPassengerDoorOpen || vcDoorTelltale

        // 4. Controls: Brake (`brake_pct`) & Steering (`steering_deg`)
        val controls = root.optJSONObject("controls")
        val brakePct = controls?.optDouble("brake_pct", 0.0) ?: 0.0
        vcBrakeActive = (brakePct > 0.0) || stopLightOn

        val steerDeg = controls?.optDouble("steering_deg", 0.0)?.toFloat() ?: 0f
        val prevSteer = lastVcSteeringDeg
        if (prevSteer != null) {
            val delta = kotlin.math.abs(steerDeg - prevSteer)
            currentSteeringRate = if (delta >= 5f) delta / 0.45f else 0f
        }
        lastVcSteeringDeg = steerDeg
        currentSteeringAngle = steerDeg

        // 5. Gear: `gear.actual.value` ("Park", "Reverse", "Neutral", "Drive")
        val gearVal = root.optJSONObject("gear")?.optJSONObject("actual")?.optString("value", "") ?: ""
        if (gearVal.isNotEmpty() && gearVal != lastVcGearVal) {
            lastVcGearVal = gearVal
            currentGear = when (gearVal) {
                "Park" -> "P"
                "Reverse" -> "R"
                "Neutral" -> "N"
                else -> "D"
            }
        }

        // 6. HVAC & Air Cleaner
        val hvac = root.optJSONObject("hvac")
        if (hvac != null) {
            currentHvacRecirc = hvac.optBoolean("recirc_on", currentHvacRecirc)
        }
        val airCleaner = root.optJSONObject("hvac_air_cleaner")
        if (airCleaner != null) {
            currentAirCleanerActive = airCleaner.optBoolean("activated", false)
        }

        currentHazardActive = sdkHazardActive || vcHazardActive
        currentDriverDoorOpen = sdkDoorOpen || vcDoorOpen
        currentTirePressureWarn = sdkTpmsWarn || vcTpmsWarn
        currentEscWarn = sdkEscWarn || vcEscWarn
        currentBrakeActive = sdkBrakeActive || vcBrakeActive
        currentWiperStage = maxOf(sdkWiperStage, vcWiperStage)

        dispatchCurrentState(forceImmediate = true)
    }

    fun registerTelemetryListener(callback: (TelemetrySnapshot) -> Unit) {
        this.listener = callback
    }

    fun getLatestSnapshot(): TelemetrySnapshot {
        return TelemetrySnapshot(
            timestampMs = System.currentTimeMillis(),
            steeringAngleDeg = currentSteeringAngle,
            steeringRateDegPerSec = currentSteeringRate,
            brakePedalActive = currentBrakeActive,
            hazardLightActive = currentHazardActive,
            wiperStage = currentWiperStage,
            tirePressureWarn = currentTirePressureWarn,
            safetyEscTelltaleWarn = currentEscWarn,
            driverDoorOpen = currentDriverDoorOpen,
            windowClosedMask = currentWindowClosed.copyOf(),
            hvacRecirculationActive = currentHvacRecirc,
            sccCruiseActiveReadOnly = currentSccActive,
            gearState = currentGear,
            airCleanerActive = currentAirCleanerActive
        )
    }

    private fun dispatchCurrentState(forceImmediate: Boolean = false) {
        onRawSensorFrame(
            steeringAngleDeg = currentSteeringAngle,
            steeringRateDegPerSec = currentSteeringRate,
            brakePedalActive = currentBrakeActive,
            hazardLightActive = currentHazardActive,
            wiperStage = currentWiperStage,
            tirePressureWarn = currentTirePressureWarn,
            safetyEscTelltaleWarn = currentEscWarn,
            driverDoorOpen = currentDriverDoorOpen,
            forceImmediate = forceImmediate
        )
    }

    fun onRawSensorFrame(
        steeringAngleDeg: Float,
        steeringRateDegPerSec: Float,
        brakePedalActive: Boolean,
        hazardLightActive: Boolean,
        wiperStage: Int,
        tirePressureWarn: Boolean = false,
        safetyEscTelltaleWarn: Boolean = false,
        driverDoorOpen: Boolean = false,
        forceImmediate: Boolean = false
    ) {
        if (!initialized) return
        val now = SystemClock.elapsedRealtime()
        if (!forceImmediate && now - lastDispatchElapsedMs < DEBOUNCE_INTERVAL_MS) return
        lastDispatchElapsedMs = now

        val snapshot = getLatestSnapshot()
        listener?.invoke(snapshot)
    }

    // Closes all windows, enables HVAC recirculation, and sets ambient warning color.
    fun activateCabinShield(ambientColorHex: String = "#B388FF"): Boolean {
        if (!initialized) return false
        runCatching {
            pleosVehicle?.getWindow()?.getCurrentWindowPosition(WindowArea(WindowValue.ROW_1_LEFT), { pos ->
                previousWindowPosition = pos ?: 35
            }, { _ -> })
            pleosVehicle?.getWindow()?.setWindowPosition(allWindowsArea, 0, { _, _ ->
                Log.i(TAG, "Pleos Vehicle SDK: Window.setWindowPosition(0) succeeded")
            }, { _ -> })

            pleosVehicle?.getHVAC()?.getHvacRecirculationState(frontSeatsArea, { recirc ->
                previousHvacRecirc = recirc == true
            }, { _ -> })
            pleosVehicle?.getHVAC()?.setHvacRecirculation(frontSeatsArea, true, { _, _ ->
                Log.i(TAG, "Pleos Vehicle SDK: HVAC.setHvacRecirculation(true) succeeded")
            }, { _ -> })

            pleosVehicle?.getLight()?.setAmbientLightBrightnessColor(frontSeatsArea, 1, { _, _ ->
                Log.i(TAG, "Pleos Vehicle SDK: Light.setAmbientLightBrightnessColor succeeded")
            }, { _ -> })
        }
        sendVirtualConsolePutAsync("/vehicle/body?window_fl=0&window_fr=0&window_rl=0&window_rr=0&ambient_color=%23aa00ff&ambient_mode=On")
        sendVirtualConsolePutAsync("/vehicle/hvac?power=true&recirc=true")
        currentWindowClosed = booleanArrayOf(true, true, true, true)
        currentHvacRecirc = true
        currentAmbientHex = ambientColorHex
        return true
    }

    // Restores previous window position and HVAC fresh air mode.
    fun restoreCabinShield(): Boolean {
        if (!initialized) return false
        runCatching {
            pleosVehicle?.getWindow()?.setWindowPosition(allWindowsArea, previousWindowPosition, { _, _ ->
                Log.i(TAG, "Pleos Vehicle SDK: Window.setWindowPosition($previousWindowPosition) restored")
            }, { _ -> })
            pleosVehicle?.getHVAC()?.setHvacRecirculation(frontSeatsArea, previousHvacRecirc, { _, _ ->
                Log.i(TAG, "Pleos Vehicle SDK: HVAC.setHvacRecirculation($previousHvacRecirc) restored")
            }, { _ -> })
        }
        sendVirtualConsolePutAsync("/vehicle/body?window_fl=$previousWindowPosition&window_fr=$previousWindowPosition&window_rl=$previousWindowPosition&window_rr=$previousWindowPosition&ambient_color=%2300e5ff&ambient_mode=On")
        sendVirtualConsolePutAsync("/vehicle/hvac?recirc=false")
        currentWindowClosed = booleanArrayOf(false, false, false, false)
        currentHvacRecirc = previousHvacRecirc
        return true
    }

    fun setGearState(gear: String) {
        currentGear = gear
        val vcGear = if (gear == "P") "Park" else "Drive"
        sendVirtualConsolePutAsync("/vehicle/gear?gear=$vcGear")
        Log.i(TAG, "Gear state changed -> $gear (CarUxRestrictions Driving=${gear != "P"})")
    }

    private fun sendVirtualConsolePutAsync(pathAndQuery: String) {
        Thread {
            runCatching {
                val conn = (URL("$VIRTUAL_CONSOLE_BASE_URL$pathAndQuery").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    connectTimeout = 400
                    readTimeout = 400
                }
                conn.responseCode
                conn.disconnect()
            }
        }.start()
    }

    fun release() {
        initialized = false
        virtualConsolePoller?.shutdownNow()
        virtualConsolePoller = null
        runCatching {
            pleosVehicle?.getBrake()?.unregisterBrakePedalStatus(brakeListener)
            pleosVehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(steeringListener)
            pleosVehicle?.getSafety()?.unregisterTelltales(telltaleListener)
            pleosVehicle?.getWiper()?.unregisterWiperState(frontWindshieldArea, wiperListener)
            pleosVehicle?.getTurnSignal()?.unregisterTurnSignalState(turnSignalListener)
            pleosVehicle?.getDoor()?.unregisterDoorPosition(allDoorsArea, doorListener)
            pleosVehicle?.getTire()?.unregisterTirePressure(frontLeftWheelArea, tireListener)
            pleosVehicle?.getDrivingMode()?.unregisterCruiseControlState(cruiseListener)
            pleosVehicle?.getGear()?.unregisterGearSelection(gearListener)
            pleosVehicle?.release()
        }
        listener = null
    }
}
