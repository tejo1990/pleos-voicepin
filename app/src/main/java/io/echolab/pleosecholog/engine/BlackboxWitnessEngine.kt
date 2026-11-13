package io.echolab.pleosecholog.engine

import io.echolab.pleosecholog.sdk.PleosVehicleBridge.TelemetrySnapshot
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

// Evaluates vehicle telemetry signals and manages VoicePin bookmark lifecycle.
class BlackboxWitnessEngine {

    companion object {
        const val STEERING_ANGLE_THRESHOLD_DEG = 15.0f
        const val STEERING_RATE_THRESHOLD_DEG_S = 28.0f
        const val WIPER_HEAVY_RAIN_STAGE = 3
    }

    enum class TriggerType {
        AUTO_EVASIVE_STEERING,
        AUTO_ROAD_SLIP_ESC_TELLTALE,
        AUTO_POTHOLE_BRAKE_IMPACT,
        AUTO_HEAVY_WEATHER_WIPER,
        MANUAL_PTT_BOOKMARK
    }

    data class WitnessBookmark(
        val id: String,
        val timestampIso: String,
        val roadLinkId: String,
        val latitude: Double,
        val longitude: Double,
        val triggerType: TriggerType,
        val sttVoiceTag: String,
        val peakSteeringRateDegPerSec: Float,
        val brakeActive: Boolean,
        val wiperStage: Int,
        val sharedToVoicePinCloud: Boolean
    )

    private val bookmarks = CopyOnWriteArrayList<WitnessBookmark>()

    // Evaluates telemetry snapshot for slip, evasive steering + braking, or heavy wiper usage while driving.
    fun evaluateSnapshot(snapshot: TelemetrySnapshot): TriggerType? {
        if (snapshot.gearState == "P") {
            return null
        }
        if (snapshot.safetyEscTelltaleWarn) {
            return TriggerType.AUTO_ROAD_SLIP_ESC_TELLTALE
        }
        val isEvasiveSteering = abs(snapshot.steeringAngleDeg) >= STEERING_ANGLE_THRESHOLD_DEG ||
                abs(snapshot.steeringRateDegPerSec) >= STEERING_RATE_THRESHOLD_DEG_S
        if (snapshot.brakePedalActive && isEvasiveSteering) {
            return TriggerType.AUTO_EVASIVE_STEERING
        }
        if (snapshot.wiperStage >= WIPER_HEAVY_RAIN_STAGE) {
            return TriggerType.AUTO_HEAVY_WEATHER_WIPER
        }
        return null
    }

    // Records a timestamped road memory bookmark with telemetry and voice tag.
    fun recordBookmark(
        snapshot: TelemetrySnapshot,
        roadLinkId: String,
        latitude: Double,
        longitude: Double,
        triggerType: TriggerType,
        sttVoiceTag: String
    ): WitnessBookmark {
        val bookmark = WitnessBookmark(
            id = "PIN-${System.currentTimeMillis() % 100000}",
            timestampIso = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(snapshot.timestampMs)),
            roadLinkId = roadLinkId,
            latitude = latitude,
            longitude = longitude,
            triggerType = triggerType,
            sttVoiceTag = sttVoiceTag,
            peakSteeringRateDegPerSec = snapshot.steeringRateDegPerSec,
            brakeActive = snapshot.brakePedalActive,
            wiperStage = snapshot.wiperStage,
            sharedToVoicePinCloud = true
        )
        bookmarks.add(0, bookmark)
        return bookmark
    }

    fun getRoadMemoryLog(): List<WitnessBookmark> = bookmarks.toList()

    // Finds hazard pins ahead of an upcoming IC when the navigation link still shows green.
    fun scanAheadOfNextIcForGreenMapTrap(
        upcomingIcDistanceKm: Double,
        navLinkColorIsGreen: Boolean,
        remoteBookmarksAheadOfIc: List<WitnessBookmark>
    ): WitnessBookmark? {
        if (!navLinkColorIsGreen || upcomingIcDistanceKm <= 0.0) return null
        return remoteBookmarksAheadOfIc.firstOrNull { bm ->
            bm.brakeActive || bm.triggerType == TriggerType.AUTO_EVASIVE_STEERING || bm.triggerType == TriggerType.MANUAL_PTT_BOOKMARK
        }
    }

    // Checks if a stopped vehicle with hazard lights and warning signals should trigger an SOS pin.
    fun shouldDispatchZeroTouchSosPin(
        fusedLocationSpeedKmh: Int,
        hazardLightActive: Boolean,
        tirePressureWarn: Boolean,
        safetyTelltaleWarn: Boolean,
        driverDoorOpen: Boolean
    ): Boolean {
        if (fusedLocationSpeedKmh != 0) return false
        if (hazardLightActive && (tirePressureWarn || safetyTelltaleWarn || driverDoorOpen)) return true
        if (driverDoorOpen && tirePressureWarn) return true
        return false
    }

    // Determines if a public VoicePin should be cleared based on trailing vehicle pass-throughs or TTL.
    fun shouldAutoClearPublicVoicePin(
        isSosPin: Boolean,
        isAirborneCabinShieldPin: Boolean = false,
        sosVehicleSpeedKmh: Int = 0,
        consecutiveSmoothTrailingPassCount: Int = 0,
        consecutiveFreshAirPassCount: Int = 0,
        elapsedMinutes: Int = 0,
        defaultTtlMinutes: Int = 45
    ): Boolean {
        if (isSosPin) {
            return sosVehicleSpeedKmh >= 20 || elapsedMinutes >= 30
        }
        if (isAirborneCabinShieldPin) {
            return consecutiveFreshAirPassCount >= 3 || elapsedMinutes >= defaultTtlMinutes
        }
        return consecutiveSmoothTrailingPassCount >= 3 || elapsedMinutes >= defaultTtlMinutes
    }
}
