package com.thirutricks.nexussmartride.sync

/**
 * Encapsulates fleet and scooter telemetry insights returned from the cloud backend.
 * Provides savings (₹, Petrol, CO2), service milestones, and server-generated AI health metrics.
 */
data class ServerInsights(
    val deviceId: String = "",
    val todayDistanceKm: Double = 0.0,
    val todaySocConsumed: Int = 0,
    val monthDistanceKm: Double = 0.0,
    val monthDaysActive: Int = 0,
    val monthAvgDailyKm: Double = 0.0,
    val realWorldEfficiency: String? = null,
    val petrolSavedLiters: Double = 0.0,
    val moneySavedInr: Int = 0,
    val co2SavedKg: Double = 0.0,
    val odometerKm: Double = 0.0,
    val nextServiceKm: Int = 1000,
    val kmUntilService: Double = 1000.0,
    val aiServiceUrgency: String = "GOOD",
    val aiSmartTip: String = "Maintain smooth throttle in ECO/City mode for maximum range.",
    val lastSyncTimeMs: Long = 0L,
    val syncStatus: String = "IDLE", // "IDLE", "SYNCING", "SUCCESS", "QUEUED", "ERROR"
    val lastErrorMessage: String? = null,
    val pendingQueueCount: Int = 0
) {
    val isRecentlySynced: Boolean get() = (System.currentTimeMillis() - lastSyncTimeMs) < 60_000L
}
