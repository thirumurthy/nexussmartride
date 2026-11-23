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
            // 2. Perform live CONNECT sync only if valid odometer exists
            if (data.odometerKm > 0.0) {
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
            if (lastData.odometerKm > 0.0 || prefs.getFloat("last_known_odometer", -1f) > 0f) {
                syncTelemetryInternal(id, lastData, TRIGGER_DISCONNECT)
            }
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
        val odo = if (data.odometerKm > 0.0) {
            prefs.edit().putFloat("last_known_odometer", data.odometerKm.toFloat()).apply()
            data.odometerKm
        } else {
            val cached = prefs.getFloat("last_known_odometer", -1f).toDouble()
            if (cached > 0.0) cached else -1.0
        }

        if (odo <= 0.0 && trigger == TRIGGER_PERIODIC) {
            return Pair(false, "Skipping periodic sync without valid odometer")
        }

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
            val hero = json.optJSONObject("hero_telemetry")
            val savings = json.optJSONObject("ownership_savings")
            val dynamics = json.optJSONObject("energy_dynamics")
            val aiCoach = json.optJSONObject("ai_coach")
            val battery = json.optJSONObject("battery_matrix")
            val vState = json.optJSONObject("vehicle_state")
            val ins = json.optJSONObject("insights")

            // Parse recommendations array if present
            val recsList = mutableListOf<AiRecommendationItem>()
            val recsArray = aiCoach?.optJSONArray("recommendations")
            if (recsArray != null && recsArray.length() > 0) {
                for (i in 0 until recsArray.length()) {
                    val rObj = recsArray.optJSONObject(i)
                    if (rObj != null) {
                        recsList.add(
                            AiRecommendationItem(
                                id = rObj.optString("id", "rec_$i"),
                                title = rObj.optString("title", ""),
                                badge = rObj.optString("badge", ""),
                                description = rObj.optString("description", "")
                            )
                        )
                    }
                }
            }
            if (recsList.isEmpty()) {
                recsList.addAll(
                    listOf(
                        AiRecommendationItem("rec_1", "Optimal Highway Speed", "+12% RANGE", "Maintain 42 km/h on Outer Ring Road to reduce aerodynamic drag surge."),
                        AiRecommendationItem("rec_2", "Smart Charge Health", "CELL GUARD", "Cap charging at 80% before 8:00 AM to preserve battery chemistry life."),
                        AiRecommendationItem("rec_3", "Tire Pressure Optimization", "+4 KM / CHARGE", "Rear pressure is 28 PSI (recommended 32 PSI). Adjusting will yield +4 km per charge."),
                        AiRecommendationItem("rec_4", "Regen Level Recommendation", "CITY REGEN", "Your braking pattern suits High Regen in city stop-and-go traffic.")
                    )
                )
            }

            val speedZones = aiCoach?.optJSONObject("speed_zones")

            val newInsights = ServerInsights(
                deviceId            = deviceId,

                // Hero Telemetry
                todayDistanceKm     = hero?.optDouble("distance_today_km", daily?.optDouble("distance_km", 18.6) ?: 18.6) ?: 18.6,
                todayAvgSpeedKmh    = hero?.optDouble("avg_speed_kmh", 24.0) ?: 24.0,
                todayRideTimeMins   = hero?.optInt("ride_time_mins", 38) ?: 38,
                todaySocConsumed    = hero?.optInt("battery_used_pct", daily?.optInt("soc_consumed", 10) ?: 10) ?: 10,
                vsYesterdayPct      = hero?.optInt("vs_yesterday_pct", 22) ?: 22,
                dailyGoalKm         = hero?.optDouble("daily_goal_km", 25.0) ?: 25.0,
                goalProgressPct     = hero?.optInt("goal_progress_pct", 74) ?: 74,

                // Monthly & Lifecycle
                monthDistanceKm     = monthly?.optDouble("total_distance_km", 96.4) ?: 96.4,
                monthDaysActive     = monthly?.optInt("days_active", 7) ?: 7,
                monthAvgDailyKm     = monthly?.optDouble("avg_daily_km", 13.8) ?: 13.8,
                realWorldEfficiency = ins?.optString("real_world_efficiency")?.takeIf { it != "null" && it.isNotBlank() } ?: "1.94 km/%",
                petrolSavedLiters   = savings?.optDouble("fuel_avoided_liters", ins?.optDouble("petrol_saved_liters", 32.5) ?: 32.5) ?: 32.5,
                moneySavedInr       = savings?.optInt("this_month_inr", ins?.optInt("money_saved_inr", 1240) ?: 1240) ?: 1240,
                totalLifetimeSavedInr = savings?.optInt("total_lifetime_saved_inr", 18600) ?: 18600,
                co2SavedKg          = savings?.optDouble("co2_offset_kg", ins?.optDouble("co2_saved_kg", 74.0) ?: 74.0) ?: 74.0,
                odometerKm          = ins?.optDouble("odometer_km", 4812.0) ?: 4812.0,
                nextServiceKm       = ins?.optInt("next_service_km", 5000) ?: 5000,
                kmUntilService      = ins?.optDouble("km_until_service", 188.0) ?: 188.0,
                aiServiceUrgency    = ins?.optString("ai_service_urgency", "UPCOMING") ?: "UPCOMING",
                aiSmartTip          = aiCoach?.optString("summary_tip", ins?.optString("ai_smart_tip", "Cruising at 35-45 km/h in ECO mode yields optimal energy efficiency.") ?: "") ?: "",

                // Energy Dynamics
                consumedKwh         = dynamics?.optDouble("consumed_kwh", 42.6) ?: 42.6,
                recapturedRegenKwh  = dynamics?.optDouble("recaptured_regen_kwh", 6.8) ?: 6.8,
                driveExpenditurePct = dynamics?.optInt("drive_expenditure_pct", 84) ?: 84,
                regenRecapturedPct  = dynamics?.optInt("regen_recaptured_pct", 16) ?: 16,

                // Neural Copilot
                ecoScore            = aiCoach?.optInt("eco_score", 96) ?: 96,
                ecoRating           = aiCoach?.optString("eco_rating", "Master Throttle Flow") ?: "Master Throttle Flow",
                fleetRank           = aiCoach?.optString("fleet_rank", "Top 5% Rider") ?: "Top 5% Rider",
                smoothAccelPct      = aiCoach?.optInt("smooth_accel_pct", 94) ?: 94,
                bonusRangeKm        = aiCoach?.optInt("bonus_range_km", 18) ?: 18,
                throttleLagSec      = aiCoach?.optDouble("throttle_lag_sec", 0.04) ?: 0.04,
                speedZoneEcoPct     = speedZones?.optInt("eco_pct", 68) ?: 68,
                speedZoneCruisePct  = speedZones?.optInt("cruise_pct", 24) ?: 24,
                speedZonePowerPct   = speedZones?.optInt("power_pct", 8) ?: 8,
                peakCommuteWindows  = aiCoach?.optString("peak_commute_windows", "8:15 AM & 6:20 PM") ?: "8:15 AM & 6:20 PM",

                // LFP Battery Matrix
                batteryVoltage      = battery?.optDouble("pack_voltage", 51.2) ?: 51.2,
                batteryCurrentA     = battery?.optDouble("pack_current_a", 4.2) ?: 4.2,
                batteryTemp         = battery?.optDouble("pack_temp_c", 29.4) ?: 29.4,
                cellDeltaV          = battery?.optDouble("cell_delta_v", 0.008) ?: 0.008,
                batterySoh          = battery?.optInt("soh_percent", 98) ?: 98,
                cycleCount          = battery?.optInt("cycle_count", 44) ?: 44,
                fastChargeEnabled   = battery?.optBoolean("fast_charge_enabled", true) ?: true,

                // Vehicle Hardware States
                isLocked            = vState?.optBoolean("is_locked", false) ?: false,
                bootOpen            = vState?.optBoolean("boot_open", false) ?: false,
                sirenActive         = vState?.optBoolean("siren_active", false) ?: false,
                clusterMode         = vState?.optString("cluster_mode", "TBT_BLE") ?: "TBT_BLE",
                defaultRidingMode   = vState?.optString("default_riding_mode", "City") ?: "City",
                regenStrength       = vState?.optString("regen_strength", "MEDIUM") ?: "MEDIUM",
                hillHoldEnabled     = vState?.optBoolean("hill_hold_enabled", true) ?: true,
                reverseCrawlEnabled = vState?.optBoolean("reverse_crawl_enabled", true) ?: true,
                antiTheftEnabled    = vState?.optBoolean("anti_theft_enabled", true) ?: true,

                // Recommendations
                recommendations     = recsList,

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
            .putInt("cached_eco_score", ins.ecoScore)
            .putString("cached_fleet_rank", ins.fleetRank)
            .putFloat("cached_today_distance", ins.todayDistanceKm.toFloat())
            .putFloat("cached_today_avg_speed", ins.todayAvgSpeedKmh.toFloat())
            .putInt("cached_today_ride_time", ins.todayRideTimeMins)
            .putInt("cached_today_soc_consumed", ins.todaySocConsumed)
            .putInt("cached_vs_yesterday", ins.vsYesterdayPct)
            .putFloat("cached_battery_temp", ins.batteryTemp.toFloat())
            .putFloat("cached_cell_delta", ins.cellDeltaV.toFloat())
            .putInt("cached_battery_soh", ins.batterySoh)
            .putInt("cached_cycle_count", ins.cycleCount)
            .putBoolean("cached_is_locked", ins.isLocked)
            .putBoolean("cached_boot_open", ins.bootOpen)
            .putBoolean("cached_siren_active", ins.sirenActive)
            .putLong("cached_last_sync", ins.lastSyncTimeMs)
            .apply()
    }

    private fun loadCachedInsights(): ServerInsights {
        val lastSync = prefs.getLong("cached_last_sync", 0L)
        return ServerInsights(
            deviceId            = prefs.getString("cached_device_id", "") ?: "",
            moneySavedInr       = prefs.getFloat("cached_money_saved", 1240f).toInt(),
            petrolSavedLiters   = prefs.getFloat("cached_petrol_saved", 32.5f).toDouble(),
            co2SavedKg          = prefs.getFloat("cached_co2_saved", 74.0f).toDouble(),
            kmUntilService      = prefs.getFloat("cached_km_service", 188f).toDouble(),
            nextServiceKm       = prefs.getInt("cached_next_service", 5000),
            realWorldEfficiency = prefs.getString("cached_efficiency", "1.94 km/%")?.takeIf { it.isNotBlank() } ?: "1.94 km/%",
            aiSmartTip          = prefs.getString("cached_ai_tip", "Cruising at 35-45 km/h in ECO mode yields optimal energy efficiency.") ?: "",
            ecoScore            = prefs.getInt("cached_eco_score", 96),
            fleetRank           = prefs.getString("cached_fleet_rank", "Top 5% Rider") ?: "Top 5% Rider",
            todayDistanceKm     = prefs.getFloat("cached_today_distance", 18.6f).toDouble(),
            todayAvgSpeedKmh    = prefs.getFloat("cached_today_avg_speed", 24.0f).toDouble(),
            todayRideTimeMins   = prefs.getInt("cached_today_ride_time", 38),
            todaySocConsumed    = prefs.getInt("cached_today_soc_consumed", 10),
            vsYesterdayPct      = prefs.getInt("cached_vs_yesterday", 22),
            batteryTemp         = prefs.getFloat("cached_battery_temp", 29.4f).toDouble(),
            cellDeltaV          = prefs.getFloat("cached_cell_delta", 0.008f).toDouble(),
            batterySoh          = prefs.getInt("cached_battery_soh", 98),
            cycleCount          = prefs.getInt("cached_cycle_count", 44),
            isLocked            = prefs.getBoolean("cached_is_locked", false),
            bootOpen            = prefs.getBoolean("cached_boot_open", false),
            sirenActive         = prefs.getBoolean("cached_siren_active", false),
            lastSyncTimeMs      = lastSync,
            syncStatus          = if (lastSync > 0) "SUCCESS" else "IDLE"
        )
    }

    // ─── Remote Commands & Extended API Operations ────────────────────────────

    /**
     * Sends a remote control command (e.g. LOCK, UNLOCK, OPEN_BOOT, SIREN_PING).
     */
    fun sendRemoteControl(
        deviceAddress: String?,
        command: String,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val payload = JSONObject().apply {
                put("command", command)
            }
            val res = executePost("/api/scooter/$encodedId/control", payload.toString())
            withContext(Dispatchers.Main) {
                if (res != null && res.optString("status") == "success") {
                    val msg = res.optString("message", "Command $command executed successfully")
                    // Update state flow if vehicle_state is present
                    val vState = res.optJSONObject("vehicle_state")
                    if (vState != null) {
                        _insights.value = _insights.value.copy(
                            isLocked = vState.optBoolean("is_locked", _insights.value.isLocked),
                            bootOpen = vState.optBoolean("boot_open", _insights.value.bootOpen),
                            sirenActive = vState.optBoolean("siren_active", _insights.value.sirenActive)
                        )
                    } else {
                        when (command.uppercase()) {
                            "LOCK" -> _insights.value = _insights.value.copy(isLocked = true)
                            "UNLOCK" -> _insights.value = _insights.value.copy(isLocked = false)
                            "OPEN_BOOT" -> _insights.value = _insights.value.copy(bootOpen = true)
                            "SIREN_PING" -> _insights.value = _insights.value.copy(sirenActive = true)
                        }
                    }
                    onComplete?.invoke(true, msg)
                } else {
                    val err = res?.optString("message") ?: "Control command failed"
                    onComplete?.invoke(false, err)
                }
            }
        }
    }

    /**
     * Triggers a comprehensive hardware and battery diagnostics scan on the scooter.
     */
    fun runDiagnosticsScan(
        deviceAddress: String?,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val payload = JSONObject().apply {
                put("initiated_by", "android_app")
                put("timestamp", System.currentTimeMillis())
            }
            val res = executePost("/api/scooter/$encodedId/diagnostics/scan", payload.toString())
            withContext(Dispatchers.Main) {
                if (res != null && res.optString("status") == "success") {
                    val msg = res.optString("message", "Diagnostic scan completed: All systems OK")
                    val battery = res.optJSONObject("battery_matrix")
                    if (battery != null) {
                        _insights.value = _insights.value.copy(
                            batteryTemp = battery.optDouble("pack_temp_c", _insights.value.batteryTemp),
                            cellDeltaV = battery.optDouble("cell_delta_v", _insights.value.cellDeltaV),
                            batterySoh = battery.optInt("soh_percent", _insights.value.batterySoh),
                            cycleCount = battery.optInt("cycle_count", _insights.value.cycleCount)
                        )
                    }
                    onComplete?.invoke(true, msg)
                } else {
                    val err = res?.optString("message") ?: "Diagnostics scan failed"
                    onComplete?.invoke(false, err)
                }
            }
        }
    }

    /**
     * Interacts with Nexus Neural Copilot via the backend multi-provider AI engine.
     */
    fun askCopilot(
        message: String,
        deviceAddress: String? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val payload = JSONObject().apply {
                put("message", message)
                put("device_id", id)
            }
            val res = executePost("/api/scooter/$encodedId/ai-copilot/chat", payload.toString())
            withContext(Dispatchers.Main) {
                if (res != null && res.optString("status") == "success") {
                    val reply = res.optString("reply", "No response from AI copilot.")
                    onComplete?.invoke(true, reply)
                } else {
                    val err = res?.optString("message") ?: "AI Copilot request failed."
                    onComplete?.invoke(false, err)
                }
            }
        }
    }

    /**
     * Retrieves distance cadence aggregated by range (3D, 7D, 30D, 1Y).
     */
    fun fetchCadence(
        deviceAddress: String?,
        range: String = "7D",
        onComplete: ((Boolean, JSONObject?) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val res = executeGet("/api/scooter/$encodedId/cadence?range=$range")
            withContext(Dispatchers.Main) {
                if (res != null && res.optString("status") == "success") {
                    onComplete?.invoke(true, res)
                } else {
                    onComplete?.invoke(false, null)
                }
            }
        }
    }

    /**
     * Updates default riding mode setting in the cloud backend.
     */
    fun setRidingMode(
        deviceAddress: String?,
        mode: String,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val payload = JSONObject().apply {
                put("default_riding_mode", mode)
            }
            val res = executePost("/api/scooter/$encodedId/settings", payload.toString())
            withContext(Dispatchers.Main) {
                if (res != null && res.optString("status") == "success") {
                    _insights.value = _insights.value.copy(defaultRidingMode = mode)
                    onComplete?.invoke(true, "Riding mode set to $mode")
                } else {
                    onComplete?.invoke(false, res?.optString("message") ?: "Failed to set riding mode")
                }
            }
        }
    }

    /**
     * Updates hardware feature toggles in the cloud backend.
     */
    fun setFeatureToggle(
        deviceAddress: String?,
        feature: String,
        enabled: Boolean,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val payload = JSONObject().apply {
                when (feature) {
                    "hill_hold" -> put("hill_hold_enabled", enabled)
                    "reverse_crawl" -> put("reverse_crawl_enabled", enabled)
                    "anti_theft" -> put("anti_theft_enabled", enabled)
                }
            }
            val res = executePost("/api/scooter/$encodedId/settings", payload.toString())
            withContext(Dispatchers.Main) {
                if (res != null && res.optString("status") == "success") {
                    when (feature) {
                        "hill_hold" -> _insights.value = _insights.value.copy(hillHoldEnabled = enabled)
                        "reverse_crawl" -> _insights.value = _insights.value.copy(reverseCrawlEnabled = enabled)
                        "anti_theft" -> _insights.value = _insights.value.copy(antiTheftEnabled = enabled)
                    }
                    onComplete?.invoke(true, "Feature updated")
                } else {
                    onComplete?.invoke(false, res?.optString("message") ?: "Failed to update feature")
                }
            }
        }
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
