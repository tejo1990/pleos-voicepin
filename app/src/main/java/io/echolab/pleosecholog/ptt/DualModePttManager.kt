package io.echolab.pleosecholog.ptt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech as AndroidTts
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener as SttResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Manages Pleos Connect STT, TTS, FusedLocation geofencing, and NaviHelper route requests.
class DualModePttManager(private val context: Context) : AndroidTts.OnInitListener {

    companion object {
        private const val TAG = "DualModePttManager"
        const val GEOFENCE_RADIUS_METERS = 150.0
        const val GEOFENCE_COOLDOWN_MS = 30 * 60 * 1000L
    }

    data class SavedVoicePin(
        val pinId: String,
        val latitude: Double,
        val longitude: Double,
        val voiceTranscript: String,
        val requiresCabinShield: Boolean = false
    )

    data class VoicePinPacket(
        val senderCallsign: String,
        val roadLinkId: String,
        val sttText: String,
        val payloadBytes: Int = 84
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var androidTts: AndroidTts? = AndroidTts(context, this)
    private var androidTtsReady = false

    private var pleosTts: PleosTts? = null
    private var pleosTtsReady = false
    private var pleosStt: PleosStt? = null
    private var pleosSttReady = false
    private var fusedLocation: FusedLocation? = null
    private var naviHelper: NaviHelper? = null

    private var currentNavigationStatus: NavigationStatus = NavigationStatus.ROUTE_DRIVE
    private var lastLocationData: LocationData? = null
    private var latestSttTranscript: String = ""

    private val pendingQueue = ConcurrentLinkedQueue<VoicePinPacket>()
    private val lastGeofenceBriefedAtMs = ConcurrentHashMap<String, Long>()
    private val registeredAlertIds = ConcurrentHashMap<String, String>()

    private val fusedLocationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            lastLocationData = location
        }
        override fun onError(e: Exception) {
            Log.w(TAG, "FusedLocation callback error: ${e.message}")
        }
    }

    private val naviHelperListener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(info: RouteStateInfo) {
            Log.i(TAG, "NaviHelper.onRouteStateInfo -> isRouting=${info.isRouting}, mode=${info.naviMode}")
        }
        override fun onNavigationStatus(status: NavigationStatus) {
            currentNavigationStatus = status
            Log.i(TAG, "NaviHelper.onNavigationStatus -> $status")
        }
        override fun onTBTInfo(tbtList: List<TBTInfo>) {
            Log.i(TAG, "NaviHelper.onTBTInfo -> ${tbtList.size} upcoming turns")
        }
    }

    init {
        initializeOfficialPleosSdks()
    }

    private fun initializeOfficialPleosSdks() {
        val navAudioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // 1. Official Pleos TextToSpeech SDK (v3.3.1, Mode.HYBRID)
        runCatching {
            val tts = PleosTts(context, TtsMode.HYBRID)
            pleosTts = tts
            tts.setAudioAttributes(navAudioAttrs)
            tts.setLocale(Locale.KOREAN)
            tts.addEventListener(object : TtsEventListener {
                override fun onStart() {}
                override fun onReady() { pleosTtsReady = true }
                override fun onDone() {}
                override fun onError(error: TtsErrorType) { Log.w(TAG, "Pleos TTS error: $error") }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            })
            tts.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    pleosTtsReady = true
                    Log.i(TAG, "Pleos TextToSpeech(Mode.HYBRID) initialized")
                }
                override fun onFailure(errorType: TtsErrorType) {
                    Log.w(TAG, "Pleos TextToSpeech init fallback: $errorType")
                }
            })
        }.onFailure { e ->
            Log.i(TAG, "Pleos TextToSpeech fallback to Android TTS: ${e.message}")
        }

        // 2. Official Pleos SpeechToText SDK (v3.3.1, Mode.HYBRID)
        runCatching {
            val stt = PleosStt(context, SttMode.HYBRID)
            pleosStt = stt
            stt.setLocale(Locale.KOREAN)
            stt.addListener(object : SttResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    latestSttTranscript = text
                }
                override fun onUpdatedEpdData(startMs: Long, endMs: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(error: SttErrorType) { Log.w(TAG, "Pleos STT error: $error") }
                override fun onReady() { pleosSttReady = true }
                override fun onUpdatedRms(rms: Double) {}
            })
            stt.initialize(object : SttInitListener {
                override fun onSuccess() {
                    pleosSttReady = true
                    Log.i(TAG, "Pleos SpeechToText(Mode.HYBRID) initialized")
                }
                override fun onFailure(errorType: SttErrorType) {
                    Log.w(TAG, "Pleos SpeechToText init fallback: $errorType")
                }
            })
        }.onFailure { e ->
            Log.i(TAG, "Pleos SpeechToText service fallback active: ${e.message}")
        }

        // 3. Official Pleos FusedLocation SDK (v1.1.3)
        runCatching {
            val loc = FusedLocation(context)
            fusedLocation = loc
            loc.initialize()
            if (loc.isSupported()) {
                loc.getLastKnownLocation(fusedLocationListener)
                loc.registerFusedLocationCallback(fusedLocationListener)
            }
        }.onFailure { e ->
            Log.i(TAG, "Pleos FusedLocation service fallback active: ${e.message}")
        }

        // 4. Official Pleos NaviHelper SDK (v3.0.5)
        runCatching {
            val navi = NaviHelper(context)
            naviHelper = navi
            navi.initialize({
                runCatching {
                    navi.addListener(naviHelperListener)
                    navi.getRouteStateInfo()
                    Log.i(TAG, "Pleos NaviHelper(v3.0.5) initialized and getRouteStateInfo() invoked")
                }.onFailure { e ->
                    Log.w(TAG, "Pleos NaviHelper async callback warning: ${e.message}")
                }
            }, { err ->
                Log.i(TAG, "Pleos NaviHelper fallback active: ${err.message}")
            })
        }.onFailure { e ->
            Log.i(TAG, "Pleos NaviHelper service fallback active: ${e.message}")
        }
    }

    // Starts 5-second voice memo recognition via Pleos SpeechToText.
    fun requestFiveSecondVoicePinRecording(): String {
        runCatching {
            pleosStt?.request()
            Log.i(TAG, "Pleos SpeechToText.request() invoked for 5-second VoicePin")
        }
        return "SpeechToText.request() invoked"
    }

    // Registers a proximity alert geofence via FusedLocation.
    fun registerHazardProximityAlert(
        pinKey: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = 1200f,
        onEnterZone: (String) -> Unit
    ) {
        runCatching {
            val req = ProximityAlertRequest(
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)
            )
            fusedLocation?.addProximityAlert(req, object : ProximityAlertListener {
                override fun onRegistered(alertId: String) {
                    registeredAlertIds[pinKey] = alertId
                    Log.i(TAG, "FusedLocation.addProximityAlert registered: $alertId ($radiusMeters m)")
                }
                override fun onTransition(event: ProximityAlertEvent) {
                    if (event.transition == ProximityTransition.ENTER) {
                        onEnterZone(event.alertId)
                    }
                }
                override fun onError(e: Exception) {
                    Log.w(TAG, "ProximityAlertListener error: ${e.message}")
                }
            })
        }
    }

    // Requests an IC detour route via NaviHelper.
    fun requestIcDetourRoute(
        icName: String = "양재IC 우회 경유지",
        latitude: Double = 37.4632,
        longitude: Double = 127.0365
    ): String {
        runCatching {
            naviHelper?.getRouteStateInfo()
            val routeInfo = RouteInfo(
                latitude = latitude,
                longitude = longitude,
                displayLatitude = latitude,
                displayLongitude = longitude,
                poiName = icName,
                poiId = "IC_DETOUR_01",
                address = "서울특별시 서초구 양재동",
                poiSubId = "",
                routeOption = RouteOption.RECOMMENDED,
                poiProvider = "PLEOS_VOICEPIN",
                isMobileRequest = false
            )
            naviHelper?.requestRoute(routeInfo)
            Log.i(TAG, "NaviHelper.requestRoute($icName) invoked")
        }
        return "NaviHelper.requestRoute($icName) invoked"
    }

    fun checkGeofenceAutoBriefing150m(
        currentLat: Double,
        currentLng: Double,
        savedPins: List<SavedVoicePin>,
        isNaviGuidanceActive: Boolean
    ): SavedVoicePin? {
        val now = System.currentTimeMillis()
        for (pin in savedPins) {
            val distM = calculateHaversineMeters(currentLat, currentLng, pin.latitude, pin.longitude)
            if (distM <= GEOFENCE_RADIUS_METERS) {
                val lastBriefed = lastGeofenceBriefedAtMs[pin.pinId] ?: 0L
                if (now - lastBriefed >= GEOFENCE_COOLDOWN_MS) {
                    lastGeofenceBriefedAtMs[pin.pinId] = now
                    val packet = VoicePinPacket(
                        senderCallsign = "Pleos-VoicePin-150m",
                        roadLinkId = pin.pinId,
                        sttText = "[보이스핀 150m 근접 브리핑] ${pin.voiceTranscript}"
                    )
                    speakPreemptiveBriefingOrPtt(packet, isNaviGuidanceActive)
                    return pin
                }
            }
        }
        return null
    }

    fun calculateHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusM = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }

    override fun onInit(status: Int) {
        if (status == AndroidTts.SUCCESS) {
            androidTts?.language = Locale.KOREAN
            androidTtsReady = true
        }
    }

    fun speakPreemptiveBriefingOrPtt(packet: VoicePinPacket, isNaviGuidanceActive: Boolean): String {
        if (isNaviGuidanceActive) {
            pendingQueue.offer(packet)
            return "QUEUED_FOR_NAVI_TBT"
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .setOnAudioFocusChangeListener { }
            .build()

        val res = audioManager.requestAudioFocus(focusReq)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (pleosTtsReady) {
                runCatching { pleosTts?.speak(packet.sttText, true) }
            } else if (androidTtsReady) {
                androidTts?.speak(packet.sttText, AndroidTts.QUEUE_ADD, null, "VOICEPIN_${System.currentTimeMillis()}")
            }
        }
        return "PLAYED_WITH_AUDIO_DUCKING (${packet.payloadBytes}B)"
    }

    fun shutdown() {
        runCatching {
            registeredAlertIds.values.forEach { id -> fusedLocation?.removeProximityAlert(id) }
            fusedLocation?.unregisterFusedLocationCallback(fusedLocationListener)
            fusedLocation?.release()
            naviHelper?.removeListener(naviHelperListener)
            naviHelper?.release()
            pleosStt?.stop()
            pleosStt?.release()
            pleosTts?.stop()
            pleosTts?.release()
        }
        androidTts?.stop()
        androidTts?.shutdown()
    }
}
