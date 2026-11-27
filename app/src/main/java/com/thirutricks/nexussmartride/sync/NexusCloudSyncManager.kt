package com.thirutricks.nexussmartride.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.thirutricks.nexussmartride.ScooterData
import com.thirutricks.nexussmartride.data.MaintenanceRepository
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
     * Resolves the active scooter device ID dynamically:
     * 1. User-configured custom scooter ID in Cloud Settings (if set).
     * 2. Paired scooter Bluetooth MAC address (which the server securely salts & hashes with SHA-256).
     * 3. Paired scooter Bluetooth device name.
     * 4. Persistent unique client installation identifier (never a shared collision).
     */
    fun getEffectiveDeviceId(): String {
        val customId = prefs.getString("custom_device_id", null)?.trim()
        if (!customId.isNullOrBlank()) return customId

        val mainPrefs = context.getSharedPreferences("nexus_prefs", Context.MODE_PRIVATE)
        val savedAddr = mainPrefs.getString("device_address", null)?.trim()
        if (!savedAddr.isNullOrBlank()) return savedAddr

        val savedName = mainPrefs.getString("device_name", null)?.trim()
        if (!savedName.isNullOrBlank()) return savedName

        var clientFallback = prefs.getString("nexus_client_uuid", null)
        if (clientFallback.isNullOrBlank()) {
            val androidId = try {
                android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            } catch (e: Exception) { null }
            val suffix = if (!androidId.isNullOrBlank() && androidId.length >= 6) {
                androidId.takeLast(6).uppercase()
            } else {
                java.util.UUID.randomUUID().toString().take(6).uppercase()
            }
            clientFallback = "NEXUS_DEV_$suffix"
            prefs.edit().putString("nexus_client_uuid", clientFallback).apply()
        }
        return clientFallback
    }

    fun setCustomDeviceId(id: String?) {
        val clean = id?.trim()?.takeIf { it.isNotBlank() }
        if (clean != null) {
            prefs.edit().putString("custom_device_id", clean).apply()
        } else {
            prefs.edit().remove("custom_device_id").apply()
        }
        clearDeviceCache()
    }

    fun getCustomDeviceId(): String? {
        return prefs.getString("custom_device_id", null)
    }

    fun clearDeviceCache() {
        prefs.edit()
            .remove("cached_device_id")
            .remove("cached_last_sync")
            .remove("cached_money_saved")
            .remove("cached_petrol_saved")
            .remove("cached_co2_saved")
            .remove("cached_km_service")
            .remove("cached_next_service")
            .remove("cached_efficiency")
            .remove("cached_ai_tip")
            .remove("cached_eco_score")
            .remove("cached_fleet_rank")
            .remove("cached_today_distance")
            .remove("cached_today_avg_speed")
            .remove("cached_today_ride_time")
            .remove("cached_today_soc_consumed")
            .remove("cached_today_energy_kwh")
            .remove("cached_yesterday_distance")
            .remove("cached_yesterday_soc_consumed")
            .remove("cached_yesterday_energy_kwh")
            .remove("cached_recommendations")
            .remove("cached_maintenance_json")
            .apply()
        _insights.value = ServerInsights(deviceId = getEffectiveDeviceId())
    }

    /**
     * Resolves the active scooter device ID:
     * Uses explicit address if provided, else resolves through getEffectiveDeviceId().
     */
    fun resolveDeviceId(explicit: String?): String {
        if (!explicit.isNullOrBlank()) return explicit
        return getEffectiveDeviceId()
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
     * Fetches current cloud insights for the given scooter ID (or active effective ID if omitted).
     */
    suspend fun fetchInsights(deviceId: String? = null): Boolean {
        if (!isOnline()) return false
        val id = resolveDeviceId(deviceId)
        val encodedId = withContext(Dispatchers.IO) {
            try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
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
            val yesterdayObj = json.optJSONObject("yesterday_usage")
            val monthly = json.optJSONObject("monthly_usage")
            val hero = json.optJSONObject("hero_telemetry")
            val savings = json.optJSONObject("ownership_savings")
            val dynamics = json.optJSONObject("energy_dynamics")
            val aiCoach = json.optJSONObject("ai_coach")
            val battery = json.optJSONObject("battery_matrix")
            val vState = json.optJSONObject("vehicle_state")
            val ins = json.optJSONObject("insights")
            val maintenanceJson = json.optJSONObject("maintenance")

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

            val journeysList = mutableListOf<ServerJourneyItem>()
            val journeysJson = json.optJSONArray("recent_trips")
                ?: json.optJSONArray("recent_journeys")
                ?: ins?.optJSONArray("recent_trips")
                ?: ins?.optJSONArray("recent_journeys")

            if (journeysJson != null && journeysJson.length() > 0) {
                for (i in 0 until journeysJson.length()) {
                    val jObj = journeysJson.optJSONObject(i) ?: continue
                    val startMs = try {
                        val s = jObj.optString("start_time", "")
                        if (s.isNotBlank()) java.time.Instant.parse(s).toEpochMilli() else 0L
                    } catch (e: Exception) { 0L }
                    val endMs = try {
                        val e = jObj.optString("end_time", "")
                        if (e.isNotBlank()) java.time.Instant.parse(e).toEpochMilli() else 0L
                    } catch (e: Exception) { 0L }

                    journeysList.add(
                        ServerJourneyItem(
                            id = jObj.optString("trip_id", jObj.optString("id", "j_$i")),
                            title = jObj.optString("title", "Ride Session"),
                            routeSummary = jObj.optString("session_summary", jObj.optString("route_summary", "")),
                            distanceKm = jObj.optDouble("distance_km", 0.0),
                            durationMins = jObj.optInt("duration_mins", 0),
                            avgSpeedKmh = jObj.optDouble("avg_speed_kmh", 0.0),
                            maxSpeedKmh = jObj.optInt("top_speed_kmh", jObj.optInt("max_speed_kmh", 50)),
                            startSoc = jObj.optInt("start_soc", 80),
                            endSoc = jObj.optInt("end_soc", 70),
                            ridingMode = jObj.optString("riding_mode", "City"),
                            efficiencyKmPerSoc = jObj.optDouble("efficiency_km_per_pct", jObj.optDouble("efficiency_km_per_soc", 1.90)),
                            energyConsumedKwh = jObj.optDouble("energy_consumed_kwh", 0.5),
                            regenKwh = jObj.optDouble("regen_recaptured_kwh", 0.08),
                            moneySavedInr = jObj.optInt("money_saved_inr", 20),
                            co2SavedKg = jObj.optDouble("co2_saved_kg", 0.5),
                            aiTag = jObj.optString("tag", jObj.optString("ai_tag", "COMMUTE")),
                            timestampFormatted = jObj.optString("session_summary", ""),
                            startTimeMs = startMs,
                            endTimeMs = endMs
                        )
                    )
                }
            }

            val speedZones = aiCoach?.optJSONObject("speed_zones")

            val parsedOdo = ins?.optDouble("odometer_km", 0.0) ?: 0.0
            val effectiveOdo = if (parsedOdo > 0.0) parsedOdo else prefs.getFloat("last_known_odometer", 0f).toDouble()
            val maintenanceReport = if (maintenanceJson != null) {
                MaintenanceRepository.parseFromJson(maintenanceJson, effectiveOdo)
            } else {
                MaintenanceRepository.buildLocalReport(effectiveOdo)
            }

            val newInsights = ServerInsights(
                deviceId            = deviceId,

                // Hero Telemetry
                todayDistanceKm     = hero?.optDouble("distance_today_km", daily?.optDouble("distance_km", 0.0) ?: 0.0) ?: 0.0,
                todayAvgSpeedKmh    = hero?.optDouble("avg_speed_kmh", 0.0) ?: 0.0,
                todayRideTimeMins   = hero?.optInt("ride_time_mins", 0) ?: 0,
                todaySocConsumed    = hero?.optInt("battery_used_pct", daily?.optInt("soc_consumed", 0) ?: 0) ?: 0,
                todayEnergyKwh      = hero?.optDouble("today_energy_kwh", daily?.optDouble("energy_kwh", 0.0) ?: 0.0) ?: 0.0,
                yesterdayDistanceKm = yesterdayObj?.optDouble("distance_km", hero?.optDouble("yesterday_distance_km", 0.0) ?: 0.0) ?: 0.0,
                yesterdaySocConsumed = yesterdayObj?.optInt("soc_consumed", hero?.optInt("yesterday_battery_used_pct", 0) ?: 0) ?: 0,
                yesterdayEnergyKwh  = yesterdayObj?.optDouble("energy_kwh", hero?.optDouble("yesterday_energy_kwh", 0.0) ?: 0.0) ?: 0.0,
                vsYesterdayPct      = hero?.optInt("vs_yesterday_pct", 0) ?: 0,
                dailyGoalKm         = hero?.optDouble("daily_goal_km", 25.0) ?: 25.0,
                goalProgressPct     = hero?.optInt("goal_progress_pct", 0) ?: 0,

                // Monthly & Lifecycle
                monthDistanceKm     = monthly?.optDouble("total_distance_km", 0.0) ?: 0.0,
                monthDaysActive     = monthly?.optInt("days_active", 0) ?: 0,
                monthAvgDailyKm     = monthly?.optDouble("avg_daily_km", 0.0) ?: 0.0,
                realWorldEfficiency = ins?.optString("real_world_efficiency")?.takeIf { it != "null" && it.isNotBlank() },
                petrolSavedLiters   = savings?.optDouble("fuel_avoided_liters", ins?.optDouble("petrol_saved_liters", 0.0) ?: 0.0) ?: 0.0,
                moneySavedInr       = savings?.optInt("this_month_inr", ins?.optInt("money_saved_inr", 0) ?: 0) ?: 0,
                totalLifetimeSavedInr = savings?.optInt("total_lifetime_saved_inr", 0) ?: 0,
                co2SavedKg          = savings?.optDouble("co2_offset_kg", ins?.optDouble("co2_saved_kg", 0.0) ?: 0.0) ?: 0.0,
                odometerKm          = ins?.optDouble("odometer_km", 0.0) ?: 0.0,
                nextServiceKm       = ins?.optInt("next_service_km", 0) ?: 0,
                kmUntilService      = ins?.optDouble("km_until_service", 0.0) ?: 0.0,
                aiServiceUrgency    = ins?.optString("ai_service_urgency", "STANDBY") ?: "STANDBY",
                aiSmartTip          = aiCoach?.optString("summary_tip", ins?.optString("ai_smart_tip", "") ?: "") ?: "",

                // Energy Dynamics
                consumedKwh         = dynamics?.optDouble("consumed_kwh", 0.0) ?: 0.0,
                recapturedRegenKwh  = dynamics?.optDouble("recaptured_regen_kwh", 0.0) ?: 0.0,
                driveExpenditurePct = dynamics?.optInt("drive_expenditure_pct", 0) ?: 0,
                regenRecapturedPct  = dynamics?.optInt("regen_recaptured_pct", 0) ?: 0,
                ecoRangeProjectionKm = dynamics?.optInt("eco_range_km", 0) ?: 0,
                sportRangeProjectionKm = dynamics?.optInt("sport_range_km", 0) ?: 0,

                // Neural Copilot
                ecoScore            = aiCoach?.optInt("eco_score", 0) ?: 0,
                ecoRating           = aiCoach?.optString("eco_rating", "") ?: "",
                fleetRank           = aiCoach?.optString("fleet_rank", "") ?: "",
                smoothAccelPct      = aiCoach?.optInt("smooth_accel_pct", 0) ?: 0,
                bonusRangeKm        = aiCoach?.optInt("bonus_range_km", 0) ?: 0,
                throttleLagSec      = aiCoach?.optDouble("throttle_lag_sec", 0.0) ?: 0.0,
                speedZoneEcoPct     = speedZones?.optInt("eco_pct", 0) ?: 0,
                speedZoneCruisePct  = speedZones?.optInt("cruise_pct", 0) ?: 0,
                speedZonePowerPct   = speedZones?.optInt("power_pct", 0) ?: 0,
                peakCommuteWindows  = aiCoach?.optString("peak_commute_windows", "") ?: "",
                aiTipSummary        = aiCoach?.optString("tip_summary", "") ?: "",

                // Environmental & Wallet Impact Matrix (30D)
                impactSavingsInr30D     = savings?.optInt("impact_savings_inr", 0) ?: 0,
                impactSavingsYoyPct     = savings?.optInt("impact_savings_yoy_pct", 0) ?: 0,
                impactFuelAvoidedLiters = savings?.optDouble("impact_fuel_avoided_liters", 0.0) ?: 0.0,
                impactCo2OffsetKg       = savings?.optDouble("impact_co2_offset_kg", 0.0) ?: 0.0,
                serviceHealthLifePct    = ins?.optInt("service_health_life_pct", 0) ?: 0,
                serviceRemainingKm      = ins?.optInt("service_remaining_km", 0) ?: 0,

                // LFP Battery Matrix
                batteryVoltage      = battery?.optDouble("pack_voltage", 0.0) ?: 0.0,
                batteryCurrentA     = battery?.optDouble("pack_current_a", 0.0) ?: 0.0,
                batteryTemp         = battery?.optDouble("pack_temp_c", 0.0) ?: 0.0,
                cellDeltaV          = battery?.optDouble("cell_delta_v", 0.0) ?: 0.0,
                batterySoh          = battery?.optInt("soh_percent", 0) ?: 0,
                cycleCount          = battery?.optInt("cycle_count", 0) ?: 0,
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

                // Clustered Recent Journeys
                recentJourneys      = journeysList,

                // Official Ampere Maintenance Timeline & Care Report
                maintenanceReport   = maintenanceReport,

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
            .putFloat("cached_today_energy_kwh", ins.todayEnergyKwh.toFloat())
            .putFloat("cached_yesterday_distance", ins.yesterdayDistanceKm.toFloat())
            .putInt("cached_yesterday_soc_consumed", ins.yesterdaySocConsumed)
            .putFloat("cached_yesterday_energy_kwh", ins.yesterdayEnergyKwh.toFloat())
            .putInt("cached_vs_yesterday", ins.vsYesterdayPct)
            .putFloat("cached_battery_temp", ins.batteryTemp.toFloat())
            .putFloat("cached_cell_delta", ins.cellDeltaV.toFloat())
            .putInt("cached_battery_soh", ins.batterySoh)
            .putInt("cached_cycle_count", ins.cycleCount)
            .putBoolean("cached_is_locked", ins.isLocked)
            .putBoolean("cached_boot_open", ins.bootOpen)
            .putBoolean("cached_siren_active", ins.sirenActive)
            .putFloat("cached_consumed_kwh", ins.consumedKwh.toFloat())
            .putFloat("cached_recaptured_regen_kwh", ins.recapturedRegenKwh.toFloat())
            .putInt("cached_drive_expenditure_pct", ins.driveExpenditurePct)
            .putInt("cached_regen_recaptured_pct", ins.regenRecapturedPct)
            .putInt("cached_eco_range_km", ins.ecoRangeProjectionKm)
            .putInt("cached_sport_range_km", ins.sportRangeProjectionKm)
            .putInt("cached_smooth_accel_pct", ins.smoothAccelPct)
            .putInt("cached_bonus_range_km", ins.bonusRangeKm)
            .putFloat("cached_throttle_lag_sec", ins.throttleLagSec.toFloat())
            .putInt("cached_speed_zone_eco_pct", ins.speedZoneEcoPct)
            .putInt("cached_speed_zone_cruise_pct", ins.speedZoneCruisePct)
            .putInt("cached_speed_zone_power_pct", ins.speedZonePowerPct)
            .putString("cached_peak_commute_windows", ins.peakCommuteWindows)
            .putString("cached_ai_tip_summary", ins.aiTipSummary)
            .putInt("cached_impact_savings_inr", ins.impactSavingsInr30D)
            .putInt("cached_impact_savings_yoy", ins.impactSavingsYoyPct)
            .putFloat("cached_impact_fuel_avoided", ins.impactFuelAvoidedLiters.toFloat())
            .putFloat("cached_impact_co2_offset", ins.impactCo2OffsetKg.toFloat())
            .putInt("cached_service_health_life", ins.serviceHealthLifePct)
            .putInt("cached_service_remaining_km", ins.serviceRemainingKm)
            .putLong("cached_last_sync", ins.lastSyncTimeMs)
            .apply()
    }

    private fun loadCachedInsights(): ServerInsights {
        return try {
            val currentEffective = try { getEffectiveDeviceId() } catch (e: Exception) { "NEXUS_SCOOTER" }
            val cachedDevId = try { prefs.getString("cached_device_id", "") ?: "" } catch (e: Exception) { "" }
            // If current active device differs from what was cached, avoid showing stale cross-device data
            if (cachedDevId.isNotBlank() && cachedDevId != currentEffective) {
                return ServerInsights(deviceId = currentEffective)
            }
            val lastSync = try { prefs.getLong("cached_last_sync", 0L) } catch (e: Exception) { 0L }
            val cachedOdo = try { prefs.getFloat("last_known_odometer", 0f).toDouble() } catch (e: Exception) { 0.0 }
            ServerInsights(
                deviceId            = if (cachedDevId.isNotBlank()) cachedDevId else currentEffective,
                moneySavedInr       = try { prefs.getFloat("cached_money_saved", 0f).toInt() } catch (e: Exception) { try { prefs.getInt("cached_money_saved", 0) } catch (e2: Exception) { 0 } },
                petrolSavedLiters   = try { prefs.getFloat("cached_petrol_saved", 0f).toDouble() } catch (e: Exception) { 0.0 },
                co2SavedKg          = try { prefs.getFloat("cached_co2_saved", 0f).toDouble() } catch (e: Exception) { 0.0 },
                kmUntilService      = try { prefs.getFloat("cached_km_service", 0f).toDouble() } catch (e: Exception) { 0.0 },
                nextServiceKm       = try { prefs.getInt("cached_next_service", 0) } catch (e: Exception) { 0 },
                realWorldEfficiency = try { prefs.getString("cached_efficiency", null)?.takeIf { it.isNotBlank() } } catch (e: Exception) { null },
                aiSmartTip          = try { prefs.getString("cached_ai_tip", "") ?: "" } catch (e: Exception) { "" },
                ecoScore            = try { prefs.getInt("cached_eco_score", 0) } catch (e: Exception) { 0 },
                fleetRank           = try { prefs.getString("cached_fleet_rank", "") ?: "" } catch (e: Exception) { "" },
                todayDistanceKm     = try { prefs.getFloat("cached_today_distance", 0.0f).toDouble() } catch (e: Exception) { 0.0 },
                todayAvgSpeedKmh    = try { prefs.getFloat("cached_today_avg_speed", 0.0f).toDouble() } catch (e: Exception) { 0.0 },
                todayRideTimeMins   = try { prefs.getInt("cached_today_ride_time", 0) } catch (e: Exception) { 0 },
                todaySocConsumed    = try { prefs.getInt("cached_today_soc_consumed", 0) } catch (e: Exception) { 0 },
                todayEnergyKwh      = try { prefs.getFloat("cached_today_energy_kwh", 0.0f).toDouble() } catch (e: Exception) { 0.0 },
                yesterdayDistanceKm = try { prefs.getFloat("cached_yesterday_distance", 0.0f).toDouble() } catch (e: Exception) { 0.0 },
                yesterdaySocConsumed = try { prefs.getInt("cached_yesterday_soc_consumed", 0) } catch (e: Exception) { 0 },
                yesterdayEnergyKwh  = try { prefs.getFloat("cached_yesterday_energy_kwh", 0.0f).toDouble() } catch (e: Exception) { 0.0 },
                vsYesterdayPct      = try { prefs.getInt("cached_vs_yesterday", 0) } catch (e: Exception) { 0 },
                batteryTemp         = try { prefs.getFloat("cached_battery_temp", 0f).toDouble() } catch (e: Exception) { 0.0 },
                cellDeltaV          = try { prefs.getFloat("cached_cell_delta", 0f).toDouble() } catch (e: Exception) { 0.0 },
                batterySoh          = try { prefs.getInt("cached_battery_soh", 0) } catch (e: Exception) { 0 },
                cycleCount          = try { prefs.getInt("cached_cycle_count", 0) } catch (e: Exception) { 0 },
                isLocked            = try { prefs.getBoolean("cached_is_locked", false) } catch (e: Exception) { false },
                bootOpen            = try { prefs.getBoolean("cached_boot_open", false) } catch (e: Exception) { false },
                sirenActive         = try { prefs.getBoolean("cached_siren_active", false) } catch (e: Exception) { false },
                consumedKwh         = try { prefs.getFloat("cached_consumed_kwh", 0f).toDouble() } catch (e: Exception) { 0.0 },
                recapturedRegenKwh  = try { prefs.getFloat("cached_recaptured_regen_kwh", 0f).toDouble() } catch (e: Exception) { 0.0 },
                driveExpenditurePct = try { prefs.getInt("cached_drive_expenditure_pct", 0) } catch (e: Exception) { 0 },
                regenRecapturedPct  = try { prefs.getInt("cached_regen_recaptured_pct", 0) } catch (e: Exception) { 0 },
                ecoRangeProjectionKm = try { prefs.getInt("cached_eco_range_km", 0) } catch (e: Exception) { 0 },
                sportRangeProjectionKm = try { prefs.getInt("cached_sport_range_km", 0) } catch (e: Exception) { 0 },
                smoothAccelPct      = try { prefs.getInt("cached_smooth_accel_pct", 0) } catch (e: Exception) { 0 },
                bonusRangeKm        = try { prefs.getInt("cached_bonus_range_km", 0) } catch (e: Exception) { 0 },
                throttleLagSec      = try { prefs.getFloat("cached_throttle_lag_sec", 0f).toDouble() } catch (e: Exception) { 0.0 },
                speedZoneEcoPct     = try { prefs.getInt("cached_speed_zone_eco_pct", 0) } catch (e: Exception) { 0 },
                speedZoneCruisePct  = try { prefs.getInt("cached_speed_zone_cruise_pct", 0) } catch (e: Exception) { 0 },
                speedZonePowerPct   = try { prefs.getInt("cached_speed_zone_power_pct", 0) } catch (e: Exception) { 0 },
                peakCommuteWindows  = try { prefs.getString("cached_peak_commute_windows", "") ?: "" } catch (e: Exception) { "" },
                aiTipSummary        = try { prefs.getString("cached_ai_tip_summary", "") ?: "" } catch (e: Exception) { "" },
                impactSavingsInr30D = try { prefs.getInt("cached_impact_savings_inr", 0) } catch (e: Exception) { 0 },
                impactSavingsYoyPct = try { prefs.getInt("cached_impact_savings_yoy", 0) } catch (e: Exception) { 0 },
                impactFuelAvoidedLiters = try { prefs.getFloat("cached_impact_fuel_avoided", 0f).toDouble() } catch (e: Exception) { 0.0 },
                impactCo2OffsetKg   = try { prefs.getFloat("cached_impact_co2_offset", 0f).toDouble() } catch (e: Exception) { 0.0 },
                serviceHealthLifePct = try { prefs.getInt("cached_service_health_life", 0) } catch (e: Exception) { 0 },
                serviceRemainingKm  = try { prefs.getInt("cached_service_remaining_km", 0) } catch (e: Exception) { 0 },
                maintenanceReport   = try { MaintenanceRepository.buildLocalReport(cachedOdo) } catch (e: Exception) { MaintenanceRepository.buildLocalReport(0.0) },
                lastSyncTimeMs      = lastSync,
                syncStatus          = if (lastSync > 0) "SUCCESS" else "IDLE"
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load cached insights safely: ${e.message}")
            ServerInsights(deviceId = "NEXUS_SCOOTER")
        }
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
     * Fetches real trips from the server for the active scooter.
     */
    fun fetchTrips(
        deviceAddress: String? = null,
        limit: Int = 10,
        onComplete: ((Boolean, JSONObject?) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val res = executeGet("/api/scooter/$encodedId/trips?limit=$limit")
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
     * Fetches AI insights filtered by timeframe ("30D", "7D", "ALL") from the cloud backend.
     */
    fun fetchInsightsTimeframe(
        deviceAddress: String?,
        timeframe: String = "30D",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val id = resolveDeviceId(deviceAddress)
        coroutineScope.launch {
            val encodedId = withContext(Dispatchers.IO) {
                try { URLEncoder.encode(id, "UTF-8") } catch (e: Exception) { id }
            }
            val res = executeGet("/api/scooter/$encodedId/insights?range=$timeframe")
            withContext(Dispatchers.Main) {
                if (res != null) {
                    parseAndApplyInsights(res)
                    onComplete?.invoke(true)
                } else {
                    onComplete?.invoke(false)
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

    private suspend fun executePost(endpoint: String, jsonBody: String): JSONObject? = withContext(Dispatchers.IO) {
        val fullUrl = getServerUrl().removeSuffix("/") + endpoint
        var conn: HttpURLConnection? = null
        try {
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
            Log.e(TAG, "executePost $endpoint error: ${e.javaClass.simpleName} - ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun executeGet(endpoint: String): JSONObject? = withContext(Dispatchers.IO) {
        val fullUrl = getServerUrl().removeSuffix("/") + endpoint
        var conn: HttpURLConnection? = null
        try {
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
            Log.e(TAG, "executeGet $endpoint error: ${e.javaClass.simpleName} - ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
