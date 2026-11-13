package io.echolab.pleosecholog

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.echolab.pleosecholog.db.GeoSpatialVoicePinRepository
import io.echolab.pleosecholog.engine.BlackboxWitnessEngine
import io.echolab.pleosecholog.ptt.DualModePttManager
import io.echolab.pleosecholog.sdk.PleosVehicleBridge

// Main activity binding Pleos Connect SDKs, local SQLite storage, and HMI WebView.
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var vehicleBridge: PleosVehicleBridge
    private lateinit var witnessEngine: BlackboxWitnessEngine
    private lateinit var pttManager: DualModePttManager
    private lateinit var geoSpatialRepo: GeoSpatialVoicePinRepository

    @Volatile private var pageLoaded = false
    private var lastObservedGear: String = "D"
    private var lastSosState: Boolean = false
    private var lastSosSignature: Triple<Boolean, Boolean, Boolean> = Triple(false, false, false)
    private var lastStep1Signature: Triple<Boolean, Boolean, Int> = Triple(false, false, 0)
    private var lastStep1Active: Boolean = false
    private var lastAirCleanerState: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vehicleBridge = PleosVehicleBridge(this)
        witnessEngine = BlackboxWitnessEngine()
        pttManager = DualModePttManager(this)
        geoSpatialRepo = GeoSpatialVoicePinRepository(this)

        // Seed initial personal Road Memory into local SQLite database
        geoSpatialRepo.cacheVoicePinLocally(
            pinId = "PIN-ROADMEM-001",
            latitude = 33.4890,
            longitude = 126.4980,
            category = "POTHOLE_SLIP",
            sensorTrigger = "Safety.registerTelltales(ESC) + Brake + SteeringWheel",
            voiceTranscript = "2차선 깊은 포트홀과 물고임 미끄러짐 있어요! 뒤따르는 분들 1차선으로 피하세요!"
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageLoaded = true
                    // Reset edge trackers so initial Virtual Console / Vehicle SDK state triggers immediately
                    lastSosState = false
                    lastSosSignature = Triple(false, false, false)
                    lastStep1Signature = Triple(false, false, 0)
                    handleTelemetrySnapshot(vehicleBridge.getLatestSnapshot(), isInitialPageLoad = true)
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(PleosJsBridge(), "PleosNative")
        }

        setContentView(webView)

        vehicleBridge.registerTelemetryListener { snapshot ->
            handleTelemetrySnapshot(snapshot, isInitialPageLoad = false)
        }

        vehicleBridge.initialize { _ ->
            // Register default proximity geofences via FusedLocation.addProximityAlert
            pttManager.registerHazardProximityAlert(
                pinKey = "PIN_YANGJAE_IC_ACCIDENT",
                latitude = 37.4632,
                longitude = 127.0365,
                radiusMeters = 1200f
            ) { _ -> }

            pttManager.registerHazardProximityAlert(
                pinKey = "PIN_MAINLINE_SMOKE_DUST",
                latitude = 37.4480,
                longitude = 127.0420,
                radiusMeters = 800f
            ) { _ -> }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("file:///android_asset/pleos_voicepin_hmi.html")
        }
    }

    private fun handleTelemetrySnapshot(
        snapshot: PleosVehicleBridge.TelemetrySnapshot,
        isInitialPageLoad: Boolean
    ) {
        if (!pageLoaded || !::webView.isInitialized) return

        val isParked = (snapshot.gearState == "P")
        val hasEvasiveSteer = kotlin.math.abs(snapshot.steeringAngleDeg) >= BlackboxWitnessEngine.STEERING_ANGLE_THRESHOLD_DEG ||
                kotlin.math.abs(snapshot.steeringRateDegPerSec) >= BlackboxWitnessEngine.STEERING_RATE_THRESHOLD_DEG_S
        val step1Sig = Triple(
            snapshot.safetyEscTelltaleWarn,
            snapshot.brakePedalActive && hasEvasiveSteer,
            snapshot.wiperStage
        )

        // 1. Gear P <-> D mode transition (checked first so Gear P never triggers driving cards)
        if (snapshot.gearState != lastObservedGear || isInitialPageLoad) {
            lastObservedGear = snapshot.gearState
            val stateStr = if (isParked) "parked" else "driving"
            runOnUiThread {
                webView.evaluateJavascript("if(window.setDriveState) window.setDriveState('$stateStr', false);", null)
            }
        }
        if (isParked) {
            lastStep1Signature = step1Sig
            return
        }

        val isSos = witnessEngine.shouldDispatchZeroTouchSosPin(
            fusedLocationSpeedKmh = 0,
            hazardLightActive = snapshot.hazardLightActive,
            tirePressureWarn = snapshot.tirePressureWarn,
            safetyTelltaleWarn = snapshot.safetyEscTelltaleWarn,
            driverDoorOpen = snapshot.driverDoorOpen
        )
        val sosSig = Triple(snapshot.hazardLightActive, snapshot.driverDoorOpen, snapshot.tirePressureWarn)
        val trigger = witnessEngine.evaluateSnapshot(snapshot)

        // 2. Emergency SOS pin condition
        if (isSos && (!lastSosState || sosSig != lastSosSignature || isInitialPageLoad)) {
            lastSosState = true
            lastSosSignature = sosSig
            val activeParts = mutableListOf<String>()
            if (snapshot.hazardLightActive) activeParts.add("Hazard Lights(On)") else activeParts.add("Hazard Lights(Ready)")
            if (snapshot.driverDoorOpen) activeParts.add("Door Position(Open)")
            if (snapshot.tirePressureWarn) activeParts.add("Telltales(TPMS)")
            if (snapshot.safetyEscTelltaleWarn) activeParts.add("Telltales(ESC/ABS)")
            val badgeText = activeParts.joinToString(" + ")

            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window.runScenario){ window.runScenario(3); if(window.updateLiveSensorBadge) window.updateLiveSensorBadge('$badgeText'); }",
                    null
                )
            }
            return
        }
        if (!isSos) {
            if (lastSosState) {
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.scheduleReturnToHome) window.scheduleReturnToHome(1500, '🟢 SOS 해제됨 · 전방 타일 3건 정상 감시 중');",
                        null
                    )
                }
            }
            lastSosState = false
            lastSosSignature = sosSig
        }

        // 3. Cabin shield popup when air cleaner or smoke sensor is triggered
        if (snapshot.airCleanerActive && !lastAirCleanerState) {
            lastAirCleanerState = true
            runOnUiThread {
                webView.evaluateJavascript("if(window.runScenario) window.runScenario(2);", null)
            }
            return
        }
        lastAirCleanerState = snapshot.airCleanerActive

        // 4. Kinematic anomaly detection (requires brake + steering, ESC/ABS, or heavy wiper in Drive)
        if (!isSos && trigger != null && (step1Sig != lastStep1Signature)) {
            lastStep1Signature = step1Sig
            lastStep1Active = true
            witnessEngine.recordBookmark(
                snapshot = snapshot,
                roadLinkId = "LINK-HIGHWAY-042",
                latitude = 37.4632,
                longitude = 127.0365,
                triggerType = trigger,
                sttVoiceTag = "차량 물리 거동 감지 5초 보이스 핀"
            )
            val step1Parts = mutableListOf<String>()
            if (snapshot.brakePedalActive) step1Parts.add("Brake Pedal(On)")
            if (hasEvasiveSteer) step1Parts.add("Steering Angle(${snapshot.steeringAngleDeg.toInt()}°)")
            if (snapshot.safetyEscTelltaleWarn) step1Parts.add("Telltales(ESC/ABS)")
            if (snapshot.wiperStage >= BlackboxWitnessEngine.WIPER_HEAVY_RAIN_STAGE) step1Parts.add("Wipers(Heavy)")
            val step1Badge = step1Parts.joinToString(" + ")

            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window.runScenario){ window.runScenario(1); if(window.updateLiveSensorBadge && '$step1Badge') window.updateLiveSensorBadge('$step1Badge'); }",
                    null
                )
            }
            return
        }
        if (trigger == null) {
            if (lastStep1Active) {
                lastStep1Active = false
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.scheduleReturnToHome) window.scheduleReturnToHome(3500, '✅ 방금 내 5초 보이스핀 등록됨 · 전방 타일 3건 감시 중');",
                        null
                    )
                }
            }
            lastStep1Signature = step1Sig
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val mode = if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
        webView.evaluateJavascript("if(window.setWindowMode) window.setWindowMode('$mode');", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        vehicleBridge.release()
        pttManager.shutdown()
        geoSpatialRepo.closeConnections()
        super.onDestroy()
    }

    inner class PleosJsBridge {
        @JavascriptInterface
        fun triggerFiveSecondStt(): String {
            geoSpatialRepo.cacheVoicePinLocally(
                pinId = "PIN-${System.currentTimeMillis() % 100000}",
                latitude = 33.4890,
                longitude = 126.4980,
                category = "POTHOLE_SLIP",
                sensorTrigger = "Safety.registerTelltales(ESC) + Brake + SteeringWheel",
                voiceTranscript = "2차선 깊은 포트홀과 물고임 미끄러짐 있어요! 뒤따르는 분들 1차선으로 피하세요!",
                isPersonalPin = true
            )
            return pttManager.requestFiveSecondVoicePinRecording()
        }

        @JavascriptInterface
        fun setGear(gear: String): String {
            lastObservedGear = gear
            vehicleBridge.setGearState(gear)
            if (gear == "P") {
                val logs = geoSpatialRepo.getPersonalRoadMemoryChronological()
                return "GEAR_P_LOGS:${logs.size}"
            }
            return "GEAR_D"
        }

        @JavascriptInterface
        fun triggerIcDetour(): String {
            return pttManager.requestIcDetourRoute()
        }

        @JavascriptInterface
        fun triggerCabinShield(colorHex: String): Boolean {
            return vehicleBridge.activateCabinShield(colorHex)
        }

        @JavascriptInterface
        fun restoreCabinShield(): Boolean {
            return vehicleBridge.restoreCabinShield()
        }

        @JavascriptInterface
        fun sendPttBriefing(callsign: String, text: String, naviActive: Boolean): String {
            val packet = DualModePttManager.VoicePinPacket(
                senderCallsign = callsign,
                roadLinkId = "LINK-HIGHWAY-042",
                sttText = text
            )
            return pttManager.speakPreemptiveBriefingOrPtt(packet, naviActive)
        }
    }
}
