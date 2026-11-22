package com.thirutricks.nexussmartride.sync

/**
 * Encapsulates fleet, scooter telemetry, and AI copilot insights returned from the cloud backend.
 * Conforms to the Stitch Nexus Pulse Cyber-Automotive Specification.
 */
data class ServerInsights(
    val deviceId: String = "",

    // Stitch Home Tab Hero Telemetry
    val todayDistanceKm: Double = 18.6,
    val todayAvgSpeedKmh: Double = 24.0,
    val todayRideTimeMins: Int = 38,
    val todaySocConsumed: Int = 10,
    val vsYesterdayPct: Int = 22,
    val dailyGoalKm: Double = 25.0,
    val goalProgressPct: Int = 74,

    // Monthly & Lifecycle Aggregates
    val monthDistanceKm: Double = 96.4,
    val monthDaysActive: Int = 7,
    val monthAvgDailyKm: Double = 13.8,
    val realWorldEfficiency: String? = "1.94 km/%",
    val petrolSavedLiters: Double = 32.5,
    val moneySavedInr: Int = 1240,
    val totalLifetimeSavedInr: Int = 18600,
    val co2SavedKg: Double = 74.0,
    val odometerKm: Double = 4812.0,
    val nextServiceKm: Int = 5000,
    val kmUntilService: Double = 188.0,
    val aiServiceUrgency: String = "UPCOMING",
    val aiSmartTip: String = "Maintain smooth throttle in ECO/City mode for maximum range.",

    // Stitch Energy Dynamics Split
    val consumedKwh: Double = 42.6,
    val recapturedRegenKwh: Double = 6.8,
    val driveExpenditurePct: Int = 84,
    val regenRecapturedPct: Int = 16,

    // Stitch Neural Copilot & Eco Score
    val ecoScore: Int = 96,
    val ecoRating: String = "Master Throttle Flow",
    val fleetRank: String = "Top 5% Rider",
    val smoothAccelPct: Int = 94,
    val bonusRangeKm: Int = 18,
    val throttleLagSec: Double = 0.04,
    val speedZoneEcoPct: Int = 68,
    val speedZoneCruisePct: Int = 24,
    val speedZonePowerPct: Int = 8,
    val peakCommuteWindows: String = "8:15 AM & 6:20 PM",

    // Stitch LFP 16-Cell Battery Matrix
    val batteryVoltage: Double = 51.2,
    val batteryCurrentA: Double = 4.2,
    val batteryTemp: Double = 29.4,
    val cellDeltaV: Double = 0.008,
    val batterySoh: Int = 98,
    val cycleCount: Int = 44,
    val fastChargeEnabled: Boolean = true,

    // Hardware Control States
    val isLocked: Boolean = false,
    val bootOpen: Boolean = false,
    val sirenActive: Boolean = false,
    val clusterMode: String = "TBT_BLE",
    val defaultRidingMode: String = "City",
    val regenStrength: String = "MEDIUM",
    val hillHoldEnabled: Boolean = true,
    val reverseCrawlEnabled: Boolean = true,
    val antiTheftEnabled: Boolean = true,

    // AI Dynamic Recommendations
    val recommendations: List<AiRecommendationItem> = listOf(
        AiRecommendationItem("rec_1", "Optimal Highway Speed", "+12% RANGE", "Maintain 42 km/h on Outer Ring Road to reduce aerodynamic drag surge."),
        AiRecommendationItem("rec_2", "Smart Charge Health", "CELL GUARD", "Cap charging at 80% before 8:00 AM to preserve battery chemistry life."),
        AiRecommendationItem("rec_3", "Tire Pressure Optimization", "+4 KM / CHARGE", "Rear pressure is 28 PSI (recommended 32 PSI). Adjusting will yield +4 km per charge."),
        AiRecommendationItem("rec_4", "Regen Level Recommendation", "CITY REGEN", "Your braking pattern suits High Regen in city stop-and-go traffic.")
    ),

    val lastSyncTimeMs: Long = 0L,
    val syncStatus: String = "IDLE", // "IDLE", "SYNCING", "SUCCESS", "QUEUED", "ERROR"
    val lastErrorMessage: String? = null,
    val pendingQueueCount: Int = 0
) {
    val isRecentlySynced: Boolean get() = (System.currentTimeMillis() - lastSyncTimeMs) < 60_000L
}

data class AiRecommendationItem(
    val id: String,
    val title: String,
    val badge: String,
    val description: String
)
