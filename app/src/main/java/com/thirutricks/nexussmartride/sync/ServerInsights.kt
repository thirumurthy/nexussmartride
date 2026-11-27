package com.thirutricks.nexussmartride.sync

import com.thirutricks.nexussmartride.data.MaintenanceReport

/**
 * Encapsulates fleet, scooter telemetry, and AI copilot insights returned from the cloud backend.
 * Conforms to the Stitch Nexus Pulse Cyber-Automotive Specification.
 */
data class ServerInsights(
    val deviceId: String = "",

    // Stitch Home Tab Hero Telemetry
    val todayDistanceKm: Double = 0.0,
    val todayAvgSpeedKmh: Double = 0.0,
    val todayRideTimeMins: Int = 0,
    val todaySocConsumed: Int = 0,
    val todayEnergyKwh: Double = 0.0,
    val yesterdayDistanceKm: Double = 0.0,
    val yesterdaySocConsumed: Int = 0,
    val yesterdayEnergyKwh: Double = 0.0,
    val vsYesterdayPct: Int = 0,
    val dailyGoalKm: Double = 25.0,
    val goalProgressPct: Int = 0,

    // Monthly & Lifecycle Aggregates
    val monthDistanceKm: Double = 0.0,
    val monthDaysActive: Int = 0,
    val monthAvgDailyKm: Double = 0.0,
    val realWorldEfficiency: String? = null,
    val petrolSavedLiters: Double = 0.0,
    val moneySavedInr: Int = 0,
    val totalLifetimeSavedInr: Int = 0,
    val co2SavedKg: Double = 0.0,
    val odometerKm: Double = 0.0,
    val nextServiceKm: Int = 0,
    val kmUntilService: Double = 0.0,
    val aiServiceUrgency: String = "STANDBY",
    val aiSmartTip: String = "",

    // Stitch Energy Dynamics Split
    val consumedKwh: Double = 0.0,
    val recapturedRegenKwh: Double = 0.0,
    val driveExpenditurePct: Int = 0,
    val regenRecapturedPct: Int = 0,
    val ecoRangeProjectionKm: Int = 0,
    val sportRangeProjectionKm: Int = 0,

    // Stitch Neural Copilot & Eco Score
    val ecoScore: Int = 0,
    val ecoRating: String = "",
    val fleetRank: String = "",
    val smoothAccelPct: Int = 0,
    val bonusRangeKm: Int = 0,
    val throttleLagSec: Double = 0.0,
    val speedZoneEcoPct: Int = 0,
    val speedZoneCruisePct: Int = 0,
    val speedZonePowerPct: Int = 0,
    val peakCommuteWindows: String = "",
    val aiTipSummary: String = "",

    // Stitch Environmental & Wallet Impact Matrix (30D)
    val impactSavingsInr30D: Int = 0,
    val impactSavingsYoyPct: Int = 0,
    val impactFuelAvoidedLiters: Double = 0.0,
    val impactCo2OffsetKg: Double = 0.0,
    val serviceHealthLifePct: Int = 0,
    val serviceRemainingKm: Int = 0,

    // Stitch LFP 16-Cell Battery Matrix
    val batteryVoltage: Double = 0.0,
    val batteryCurrentA: Double = 0.0,
    val batteryTemp: Double = 0.0,
    val cellDeltaV: Double = 0.0,
    val batterySoh: Int = 0,
    val cycleCount: Int = 0,
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

    // Cloud / AI Clustered Recent Journeys
    val recentJourneys: List<ServerJourneyItem> = emptyList(),

    // Official Ampere Maintenance Timeline & Care Report
    val maintenanceReport: MaintenanceReport? = null,

    val lastSyncTimeMs: Long = 0L,
    val syncStatus: String = "IDLE", // "IDLE", "SYNCING", "SUCCESS", "QUEUED", "ERROR"
    val lastErrorMessage: String? = null,
    val pendingQueueCount: Int = 0
) {
    val isRecentlySynced: Boolean get() = (System.currentTimeMillis() - lastSyncTimeMs) < 60_000L
}

data class ServerJourneyItem(
    val id: String = "",
    val title: String = "",
    val routeSummary: String = "",
    val distanceKm: Double = 0.0,
    val durationMins: Int = 0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Int = 45,
    val startSoc: Int = 80,
    val endSoc: Int = 70,
    val ridingMode: String = "Eco",
    val efficiencyKmPerSoc: Double = 1.90,
    val energyConsumedKwh: Double = 0.5,
    val regenKwh: Double = 0.08,
    val moneySavedInr: Int = 22,
    val co2SavedKg: Double = 0.5,
    val aiTag: String = "AI Auto",
    val timestampFormatted: String = "",
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L
)

data class AiRecommendationItem(
    val id: String,
    val title: String,
    val badge: String,
    val description: String
)
