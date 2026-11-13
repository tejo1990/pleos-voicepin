package io.echolab.pleosecholog.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Local SQLite repository (WAL mode) with 7-char GeoHash indexing and OkHttp3 REST/WSS sync.
class GeoSpatialVoicePinRepository(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "GeoSpatialVoicePinRepo"
        private const val DB_NAME = "pleos_voicepin_spatial.db"
        private const val DB_VERSION = 2
        private const val TABLE_ROAD_MEMORY = "road_memory_pins"
        private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        private const val RETENTION_30_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
        private const val MAX_PERSONAL_LOG_COUNT = 500

        val RECURRING_CATEGORIES = setOf(
            "POTHOLE",
            "BLACK_ICE",
            "SMOKE_DUST_ODOR",
            "RECURRING_HAZARD"
        )
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private var activeTileWebSocket: WebSocket? = null

    init {
        // Enable SQLite WAL (Write-Ahead Logging) mode to prevent DB corruption on sudden vehicle ignition-off
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ROAD_MEMORY (
                pin_id TEXT PRIMARY KEY,
                geohash7 TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                category TEXT NOT NULL,
                sensor_trigger TEXT NOT NULL,
                voice_transcript TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                is_personal_road_memory INTEGER NOT NULL DEFAULT 1,
                reentry_alert_active INTEGER NOT NULL DEFAULT 0,
                my_smooth_pass_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_geohash7 ON $TABLE_ROAD_MEMORY(geohash7)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_time ON $TABLE_ROAD_MEMORY(is_personal_road_memory, created_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ROAD_MEMORY")
        onCreate(db)
    }

    // Encodes WGS84 coordinates into a 7-character GeoHash (~150m x 150m cell).
    fun encodeGeoHash7(latitude: Double, longitude: Double, precision: Int = 7): String {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var isEvenBit = true
        var bit = 0
        var ch = 0
        val geohash = StringBuilder()

        while (geohash.length < precision) {
            if (isEvenBit) {
                val mid = (lonMin + lonMax) / 2.0
                if (longitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonMin = mid
                } else {
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2.0
                if (latitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }
            isEvenBit = !isEvenBit
            if (bit < 4) {
                bit++
            } else {
                geohash.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return geohash.toString()
    }

    // Stores a VoicePin in local SQLite and sets re-entry alert flag for recurring hazard categories.
    fun cacheVoicePinLocally(
        pinId: String,
        latitude: Double,
        longitude: Double,
        category: String,
        sensorTrigger: String,
        voiceTranscript: String,
        isPersonalPin: Boolean = true
    ): String {
        val geohash7 = encodeGeoHash7(latitude, longitude)
        val isRecurring = isPersonalPin && RECURRING_CATEGORIES.any { category.uppercase().contains(it) }
        runCatching {
            val values = ContentValues().apply {
                put("pin_id", pinId)
                put("geohash7", geohash7)
                put("latitude", latitude)
                put("longitude", longitude)
                put("category", category)
                put("sensor_trigger", sensorTrigger)
                put("voice_transcript", voiceTranscript)
                put("created_at_ms", System.currentTimeMillis())
                put("is_personal_road_memory", if (isPersonalPin) 1 else 0)
                put("reentry_alert_active", if (isRecurring) 1 else 0)
                put("my_smooth_pass_count", 0)
            }
            writableDatabase.insertWithOnConflict(
                TABLE_ROAD_MEMORY,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            pruneExpiredHistory30Days()
            Log.i(
                TAG,
                "SQLite saved pin=$pinId geohash7=$geohash7 personal=$isPersonalPin reentryAlert=$isRecurring"
            )
        }.onFailure { e ->
            Log.w(TAG, "SQLite cache insert warning: ${e.message}")
        }
        return geohash7
    }

    // Removes records older than 30 days.
    fun pruneExpiredHistory30Days() {
        runCatching {
            val cutoffMs = System.currentTimeMillis() - RETENTION_30_DAYS_MS
            writableDatabase.delete(
                TABLE_ROAD_MEMORY,
                "created_at_ms < ?",
                arrayOf(cutoffMs.toString())
            )
        }
    }

    // Returns personal Road Memory logs in reverse chronological order for Gear P view.
    fun getPersonalRoadMemoryChronological(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT pin_id, category, voice_transcript, reentry_alert_active, created_at_ms FROM $TABLE_ROAD_MEMORY WHERE is_personal_road_memory = 1 ORDER BY created_at_ms DESC LIMIT $MAX_PERSONAL_LOG_COUNT",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add("${cursor.getString(0)}|${cursor.getString(1)}|${cursor.getString(2)}|alert=${cursor.getInt(3)}")
                }
            }
        }
        return result
    }

    // Increments smooth pass count and disables re-entry alert after 3 consecutive normal passes.
    fun recordMyCarSmoothPass(pinId: String): Boolean {
        var deactivated = false
        runCatching {
            val db = writableDatabase
            db.rawQuery(
                "SELECT my_smooth_pass_count FROM $TABLE_ROAD_MEMORY WHERE pin_id = ? AND is_personal_road_memory = 1 AND reentry_alert_active = 1",
                arrayOf(pinId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val nextCount = cursor.getInt(0) + 1
                    val nextAlertState = if (nextCount >= 3) 0 else 1
                    val values = ContentValues().apply {
                        put("my_smooth_pass_count", nextCount)
                        put("reentry_alert_active", nextAlertState)
                    }
                    db.update(TABLE_ROAD_MEMORY, values, "pin_id = ?", arrayOf(pinId))
                    deactivated = (nextAlertState == 0)
                    Log.i(
                        TAG,
                        "My smooth pass recorded for pin=$pinId ($nextCount/3) -> reentry_alert_active=$nextAlertState (Gear P history preserved)"
                    )
                }
            }
        }
        return deactivated
    }

    // Deletes non-personal cached pins when public TTL expires.
    fun purgePublicCachedPin(pinId: String) {
        runCatching {
            writableDatabase.delete(
                TABLE_ROAD_MEMORY,
                "pin_id = ? AND is_personal_road_memory = 0",
                arrayOf(pinId)
            )
        }
    }

    // Sends VoicePin payload to the backend REST endpoint and caches locally.
    fun publishVoicePinToPostGisAsync(
        endpointUrl: String,
        pinId: String,
        latitude: Double,
        longitude: Double,
        category: String,
        sensorTrigger: String,
        voiceTranscript: String
    ): String {
        val geohash7 = cacheVoicePinLocally(
            pinId = pinId,
            latitude = latitude,
            longitude = longitude,
            category = category,
            sensorTrigger = sensorTrigger,
            voiceTranscript = voiceTranscript,
            isPersonalPin = true
        )
        val payload = JSONObject().apply {
            put("pinId", pinId)
            put("geohash7", geohash7)
            put("lat", latitude)
            put("lng", longitude)
            put("cat", category)
            put("trigger", sensorTrigger)
            put("stt", voiceTranscript)
        }.toString()

        runCatching {
            val req = Request.Builder()
                .url(endpointUrl)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req)
        }
        return geohash7
    }

    // Subscribes to forward GeoHash tiles over WebSocket.
    fun subscribeForwardGeoHashTilesWss(wssUrl: String, forwardGeoHashes: List<String>) {
        runCatching {
            val req = Request.Builder().url(wssUrl).build()
            activeTileWebSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val subMsg = JSONObject().apply {
                        put("action", "SUBSCRIBE_GEOHASH_TILES")
                        put("tiles", forwardGeoHashes.joinToString(","))
                    }.toString()
                    webSocket.send(subMsg)
                }
            })
        }
    }

    fun closeConnections() {
        runCatching {
            activeTileWebSocket?.close(1000, "App shutdown")
            close()
        }
    }
}
