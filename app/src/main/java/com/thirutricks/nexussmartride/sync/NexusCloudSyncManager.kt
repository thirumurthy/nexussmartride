package com.thirutricks.nexussmartride.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.thirutricks.nexussmartride.ScooterData
import com.thirutricks.nexussmartride.data.PendingSyncEntity
import com.thirutricks.nexussmartride.data.ScooterDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Manages reliable bidirectional telemetry synchronization with the Nexus Cloud API:
 *  - High-reliability HTTP client with API Key authentication.
 *  - Automatic sync on cluster disconnect (ride completion) and connect (session start).
 *  - Offline queueing via Room DB with automatic background flush upon network reconnection.
 *  - Caches fleet savings (₹, Petrol, CO2) & AI maintenance alerts.
 */
class NexusCloudSyncManager private constructor(private val context: Context) {

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = ScooterDatabase.getDatabase(context)
    private val dao = db.scooterDao()
    private val prefs = context.getSharedPreferences("nexus_cloud_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "NexusCloudSync"
        const val DEFAULT_SERVER_URL = "https://nexus-api-zeta-ochre.vercel.app"
        const val DEFAULT_API_KEY = "K7vN9xQ2mR5pL8wT4cH1zB6fD3jA0sE9"

        const val TRIGGER_DISCONNECT = "DISCONNECT"
        const val TRIGGER_CONNECT    = "CONNECT"
        const val TRIGGER_PERIODIC   = "PERIODIC"
        const val TRIGGER_MANUAL     = "MANUAL"

        @Volatile
        private var INSTANCE: NexusCloudSyncManager? = null

        fun getInstance(context: Context): NexusCloudSyncManager {
            return INSTANCE ?: synchronized(this) {
                val inst = NexusCloudSyncManager(context.applicationContext)
                INSTANCE = inst
                inst
            }
        }
    }

    private val _insights = MutableStateFlow(loadCachedInsights())
    val insights: StateFlow<ServerInsights> = _insights.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val appVersion: String by lazy {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.3"
        } catch (e: Exception) {
            "1.0.3"
        }
    }

    init {
        // Observe pending count
        coroutineScope.launch {
            dao.getPendingSyncCount().collect { count ->
                _pendingCount.value = count
                _insights.value = _insights.value.copy(pendingQueueCount = count)
            }
        }
        setupNetworkCallback()
    }

    private fun setupNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        try {
            cm.registerNetworkCallback(builder.build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network connection restored. Flushing pending offline syncs...")
                    coroutineScope.launch {
                        flushPendingSyncs()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getServerUrl(): String = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    fun getApiKey(): String = prefs.getString("api_key", DEFAULT_API_KEY) ?: DEFAULT_API_KEY

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", url.trim().removeSuffix("/")).apply()
    }

    fun setApiKey(key: String) {
        prefs.edit().putString("api_key", key.trim()).apply()
    }

    /**
     * Resolves the permanent scooter device ID:
     * Uses the saved Bluetooth MAC address (which the server securely salts & hashes with SHA-256).
     */
    private fun resolveDeviceId(explicit: String?): String {
        if (!explicit.isNullOrBlank()) return explicit
        val mainPrefs = context.getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        val savedAddr = mainPrefs.getString("device_address", null)
        if (!savedAddr.isNullOrBlank()) return savedAddr
        val savedName = mainPrefs.getString("device_name", null)
        if (!savedName.isNullOrBlank()) return savedName
        return "NEXUS_DEFAULT_SCOOTER"
    }

    /**
     * Called when the phone establishes a connection to the scooter cluster.
     */
    fun onClusterConnected(deviceAddress: String?, data: ScooterData) {
        val id = resolveDeviceId(deviceAddress)
        Log.i(TAG, "onClusterConnected: Initiating session sync for $id")
        coroutineScope.launch {
            // 1. Fetch historical cloud insights for immediate dashboard restoration
            fetchInsights(id)
            // 2. Perform live CONNECT sync if valid telemetry or last known telemetry exists
            if (data.isValid || data.odometerKm >= 0) {
                syncTelemetryInternal(id, data, TRIGGER_CONNECT)
            }
            // 3. Flush any pending offline syncs
            flushPendingSyncs()
        }
    }

    /**
     * Called when the Bluetooth connection to the scooter cluster disconnects.
     * This marks ride completion and is the most important sync trigger.
     */
    fun onClusterDisconnected(deviceAddress: String?, lastData: ScooterData) {
        val id = resolveDeviceId(deviceAddress)
        Log.i(TAG, "onClusterDisconnected: Capturing final ride telemetry for $id (Trigger=DISCONNECT)")
        coroutineScope.launch {
            syncTelemetryInternal(id, lastData, TRIGGER_DISCONNECT)
        }
    }

    /**
     * Periodic background sync while riding.
     */
    fun onPeriodicSync(deviceAddress: String?, currentData: ScooterData) {
        if (!currentData.isValid) return
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            syncTelemetryInternal(id, currentData, TRIGGER_PERIODIC)
        }
    }

    /**
     * Manual sync triggered by user tapping the Sync button in UI.
     */
    fun triggerManualSync(deviceAddress: String?, currentData: ScooterData, onComplete: ((Boolean, String) -> Unit)? = null) {
        val id = resolveDeviceId(deviceAddress)
        _isSyncing.value = true
        coroutineScope.launch {
            val result = syncTelemetryInternal(id, currentData, TRIGGER_MANUAL)
            withContext(Dispatchers.Main) {
                _isSyncing.value = false
                if (result.first) {
                    onComplete?.invoke(true, "Cloud synced successfully")
                } else {
                    onComplete?.invoke(false, result.second)
                }
            }
        }
    }

    private suspend fun syncTelemetryInternal(
        deviceId: String,
        data: ScooterData,
        trigger: String
    ): Pair<Boolean, String> {
        val odo = if (data.odometerKm >= 0) data.odometerKm else 0.0
        val trip = if (data.tripKm >= 0) data.tripKm else 0.0
        val soc = if (data.socPercent in 0..100) data.socPercent else 50
        val range = if (data.remainingKm >= 0) data.remainingKm else 0
        val mode = if (data.ridingMode.isNotBlank()) data.ridingMode else "ECO"
        val timestamp = System.currentTimeMillis()

        if (!isOnline()) {
            Log.w(TAG, "Offline: Queueing sync payload for later transmission (trigger=$trigger, odo=$odo)")
            dao.insertPendingSync(
                PendingSyncEntity(
                    deviceId = deviceId,
                    timestamp = timestamp,
                    trigger = trigger,
                    odometerKm = odo,
                    tripKm = trip,
                    socPercent = soc,
                    remainingRangeKm = range,
                    ridingMode = mode,
                    appVersion = appVersion
                )
            )
            _insights.value = _insights.value.copy(syncStatus = "QUEUED")
            return Pair(false, "Offline: Saved to queue")
        }

        return try {
            _isSyncing.value = true
            _insights.value = _insights.value.copy(syncStatus = "SYNCING")

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("sync_timestamp", timestamp)
                put("trigger", trigger)
                put("odometer_km", odo)
                put("trip_km", trip)
                put("soc_percent", soc)
                put("remaining_range_km", range)
                put("riding_mode", mode)
                put("app_version", appVersion)
            }

            val response = executePost("/api/scooter/sync", payload.toString())
            if (response != null) {
                parseAndApplyInsights(response)
                // Also flush queued syncs
                flushPendingSyncs()
                Pair(true, "Sync OK")
            } else {
                // Queue on failure
                dao.insertPendingSync(
                    PendingSyncEntity(
                        deviceId = deviceId,
                        timestamp = timestamp,
                        trigger = trigger,
                        odometerKm = odo,
                        tripKm = trip,
                        socPercent = soc,
                        remainingRangeKm = range,
                        ridingMode = mode,
                        appVersion = appVersion
                    )
                )
                _insights.value = _insights.value.copy(syncStatus = "QUEUED")
                Pair(false, "Server error, queued")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncTelemetryInternal error: ${e.message}")
            dao.insertPendingSync(
                PendingSyncEntity(
                    deviceId = deviceId,
                    timestamp = timestamp,
                    trigger = trigger,
                    odometerKm = odo,
                    tripKm = trip,
                    socPercent = soc,
                    remainingRangeKm = range,
                    ridingMode = mode,
                    appVersion = appVersion
                )
            )
            _insights.value = _insights.value.copy(syncStatus = "QUEUED", lastErrorMessage = e.message)
            Pair(false, e.message ?: "Sync failed")
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Drains all offline pending syncs from Room database in chronological order.
     */
    private suspend fun flushPendingSyncs() {
        if (!isOnline()) return
        val pendingList = dao.getPendingSyncs()
        if (pendingList.isEmpty()) return

        Log.i(TAG, "Flushing ${pendingList.size} queued offline sync records to cloud...")

        for (item in pendingList) {
            try {
                val payload = JSONObject().apply {
                    put("device_id", item.deviceId)
                    put("sync_timestamp", item.timestamp)
                    put("trigger", item.trigger)
                    put("odometer_km", item.odometerKm)
                    put("trip_km", item.tripKm)
                    put("soc_percent", item.socPercent)
                    put("remaining_range_km", item.remainingRangeKm)
                    put("riding_mode", item.ridingMode)
                    put("app_version", item.appVersion)
                }

                val response = executePost("/api/scooter/sync", payload.toString())
                if (response != null) {
                    dao.deletePendingSync(item.id)
                    parseAndApplyInsights(response)
                    Log.d(TAG, "Pending sync #${item.id} flushed successfully.")
                } else {
                    // Stop on failure to preserve ordering
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to flush pending sync #${item.id}: ${e.message}")
                break
            }
        }
    }

    /**
     * Fetches current cloud insights for the given scooter ID (e.g. on fresh install or app launch).
     */
    suspend fun fetchInsights(deviceId: String): Boolean {
        if (!isOnline()) return false
        val encodedId = withContext(Dispatchers.IO) {
            try { URLEncoder.encode(deviceId, "UTF-8") } catch (e: Exception) { deviceId }
        }
        val response = executeGet("/api/scooter/$encodedId/insights")
        return if (response != null) {
            parseAndApplyInsights(response)
            true
        } else false
    }

    private fun parseAndApplyInsights(json: JSONObject) {
        try {
            val deviceId = json.optString("device_id", "")
            val daily = json.optJSONObject("daily_usage")
            val monthly = json.optJSONObject("monthly_usage")
            val ins = json.optJSONObject("insights")

            val newInsights = ServerInsights(
                deviceId            = deviceId,
                todayDistanceKm     = daily?.optDouble("distance_km", 0.0) ?: 0.0,
                todaySocConsumed    = daily?.optInt("soc_consumed", 0) ?: 0,
                monthDistanceKm     = monthly?.optDouble("total_distance_km", 0.0) ?: 0.0,
                monthDaysActive     = monthly?.optInt("days_active", 0) ?: 0,
                monthAvgDailyKm     = monthly?.optDouble("avg_daily_km", 0.0) ?: 0.0,
                realWorldEfficiency = ins?.optString("real_world_efficiency")?.takeIf { it != "null" && it.isNotBlank() },
                petrolSavedLiters   = ins?.optDouble("petrol_saved_liters", 0.0) ?: 0.0,
                moneySavedInr       = ins?.optInt("money_saved_inr", 0) ?: 0,
                co2SavedKg          = ins?.optDouble("co2_saved_kg", 0.0) ?: 0.0,
                odometerKm          = ins?.optDouble("odometer_km", 0.0) ?: 0.0,
                nextServiceKm       = ins?.optInt("next_service_km", 1000) ?: 1000,
                kmUntilService      = ins?.optDouble("km_until_service", 1000.0) ?: 1000.0,
                aiServiceUrgency    = ins?.optString("ai_service_urgency", "GOOD") ?: "GOOD",
                aiSmartTip          = ins?.optString("ai_smart_tip", "Cruising at 35-45 km/h in ECO mode yields optimal energy efficiency.") ?: "",
                lastSyncTimeMs      = System.currentTimeMillis(),
                syncStatus          = "SUCCESS",
                pendingQueueCount   = _pendingCount.value
            )

            _insights.value = newInsights
            cacheInsights(newInsights)
        } catch (e: Exception) {
            Log.e(TAG, "parseAndApplyInsights error: ${e.message}")
        }
    }

    private fun cacheInsights(ins: ServerInsights) {
        prefs.edit()
            .putString("cached_device_id", ins.deviceId)
            .putFloat("cached_money_saved", ins.moneySavedInr.toFloat())
            .putFloat("cached_petrol_saved", ins.petrolSavedLiters.toFloat())
            .putFloat("cached_co2_saved", ins.co2SavedKg.toFloat())
            .putFloat("cached_km_service", ins.kmUntilService.toFloat())
            .putInt("cached_next_service", ins.nextServiceKm)
            .putString("cached_efficiency", ins.realWorldEfficiency ?: "")
            .putString("cached_ai_tip", ins.aiSmartTip)
            .putLong("cached_last_sync", ins.lastSyncTimeMs)
            .apply()
    }

    private fun loadCachedInsights(): ServerInsights {
        val lastSync = prefs.getLong("cached_last_sync", 0L)
        return ServerInsights(
            deviceId            = prefs.getString("cached_device_id", "") ?: "",
            moneySavedInr       = prefs.getFloat("cached_money_saved", 0f).toInt(),
            petrolSavedLiters   = prefs.getFloat("cached_petrol_saved", 0f).toDouble(),
            co2SavedKg          = prefs.getFloat("cached_co2_saved", 0f).toDouble(),
            kmUntilService      = prefs.getFloat("cached_km_service", 1000f).toDouble(),
            nextServiceKm       = prefs.getInt("cached_next_service", 1000),
            realWorldEfficiency = prefs.getString("cached_efficiency", null)?.takeIf { it.isNotBlank() },
            aiSmartTip          = prefs.getString("cached_ai_tip", "Cruising at 35-45 km/h in ECO mode yields optimal energy efficiency.") ?: "",
            lastSyncTimeMs      = lastSync,
            syncStatus          = if (lastSync > 0) "SUCCESS" else "IDLE"
        )
    }

    // ─── Native HTTP Implementation ──────────────────────────────────────────

    private fun executePost(endpoint: String, jsonBody: String): JSONObject? {
        val fullUrl = getServerUrl().removeSuffix("/") + endpoint
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(fullUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.doInput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            val apiKey = getApiKey()
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("x-api-key", apiKey)
            }

            conn.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(jsonBody)
                    writer.flush()
                }
            }

            val code = conn.responseCode
            val isSuccess = code in 200..299
            val stream = if (isSuccess) conn.inputStream else conn.errorStream
            val respString = stream?.bufferedReader()?.use(BufferedReader::readText) ?: "{}"

            if (isSuccess) {
                JSONObject(respString)
            } else {
                Log.w(TAG, "POST $endpoint failed ($code): $respString")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "executePost $endpoint error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun executeGet(endpoint: String): JSONObject? {
        val fullUrl = getServerUrl().removeSuffix("/") + endpoint
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(fullUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doInput = true
            conn.setRequestProperty("Accept", "application/json")
            val apiKey = getApiKey()
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("x-api-key", apiKey)
            }

            val code = conn.responseCode
            val isSuccess = code in 200..299
            val stream = if (isSuccess) conn.inputStream else conn.errorStream
            val respString = stream?.bufferedReader()?.use(BufferedReader::readText) ?: "{}"

            if (isSuccess) {
                JSONObject(respString)
            } else {
                Log.w(TAG, "GET $endpoint failed ($code): $respString")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeGet $endpoint error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
