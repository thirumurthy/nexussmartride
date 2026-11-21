package com.thirutricks.nexussmartride.ai

import com.thirutricks.nexussmartride.ScooterData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edge AI Telemetry Analyzer & Smart Ride Coach.
 *
 * Capabilities:
 *  1. Dynamic Predictive Range (AI DTE) calibrated to real-time riding habits & terrain.
 *  2. Real-time Eco-Riding Score (0-100) analyzing throttle stability and aerodynamic efficiency.
 *  3. Battery State of Health (SOH) estimation & smart charge advisory.
 *  4. Context-aware coaching tips for maximum energy savings.
 */
data class AiRideState(
    val aiPredictedRangeKm: Int = 0,
    val rangeConfidence: String = "Calibrating", // "Calibrating", "Estimated", "High Confidence"
    val ecoScore: Int = 85,
    val ecoRating: String = "Efficient Rider",    // "Eco Master", "Efficient Rider", "Moderate", "Sporty"
    val estimatedKmPerSoc: Double = 1.2,
    val batteryHealthSoh: Int = 98,
    val smartChargeTargetSoc: Int = 80,
    val smartChargeTip: String = "Charge to 80% for daily commutes to prolong cell longevity.",
    val coachTip: String = "Cruising at 35-45 km/h in ECO mode yields optimal energy efficiency.",
    val isCharging: Boolean = false,
    val timeTo80Min: Int = -1,
    val timeTo100Min: Int = -1
)

object SmartAiEngine {

    private val _aiState = MutableStateFlow(AiRideState())
    val aiState: StateFlow<AiRideState> = _aiState.asStateFlow()

    private const val WINDOW_SIZE = 30
    private val speedHistory = ArrayDeque<Int>(WINDOW_SIZE)
    private var initialSoc = -1
    private var initialOdo = -1.0
    private var lastRecordedOdo = -1.0
    private var lastRecordedSoc = -1

    // Baseline Ampere Nexus ST parameters (60V ~3.5 kWh LFP pack ~100-110km nominal)
    private const val NOMINAL_KM_PER_SOC = 1.15

    fun updateTelemetry(data: ScooterData) {
        if (!data.isValid) return

        // Record speed history for smoothness analysis
        if (data.speedKmh >= 0) {
            if (speedHistory.size >= WINDOW_SIZE) speedHistory.removeFirst()
            speedHistory.add(data.speedKmh)
        }

        // Initialize baseline if new trip
        if (initialSoc < 0 && data.socPercent >= 0) {
            initialSoc = data.socPercent
            lastRecordedSoc = data.socPercent
        }
        if (initialOdo < 0 && data.odometerKm >= 0) {
            initialOdo = data.odometerKm
            lastRecordedOdo = data.odometerKm
        }

        // Calculate dynamic consumption rate (km per % SoC)
        val kmPerSoc: Double
        var confidence = "Calibrating"

        val odoDelta = if (data.odometerKm >= 0 && initialOdo >= 0) max(0.0, data.odometerKm - initialOdo) else 0.0
        val socDelta = if (data.socPercent >= 0 && initialSoc >= 0) max(0, initialSoc - data.socPercent) else 0

        if (odoDelta >= 2.0 && socDelta >= 2) {
            val raw = odoDelta / socDelta.toDouble()
            // Bound within reasonable real-world physical limits (0.5 to 2.5 km/%)
            kmPerSoc = raw.coerceIn(0.6, 2.0)
            confidence = if (socDelta >= 8) "High Confidence" else "Estimated"
        } else {
            // Mode based heuristic while calibrating
            kmPerSoc = when (data.ridingMode.uppercase()) {
                "ECO" -> 1.35
                "CITY" -> 1.15
                "POWER" -> 0.90
                else -> 1.15
            }
        }

        // Dynamic AI Range
        val currentSoc = if (data.socPercent >= 0) data.socPercent else 50
        val rawAiRange = (currentSoc * kmPerSoc).roundToInt()
        // Blend with scooter's internal DTE if available
        val finalAiRange = if (data.remainingKm > 0) {
            ((rawAiRange * 0.6) + (data.remainingKm * 0.4)).roundToInt()
        } else {
            rawAiRange
        }

        // Calculate Eco Score (0-100)
        val (calculatedScore, rating) = evaluateEcoScore(data.speedKmh, data.ridingMode)

        // Generate Smart Coach Tip
        val tip = generateCoachTip(data, currentSoc, finalAiRange, calculatedScore)

        // Battery Health SOH Estimate
        val totalOdo = if (data.odometerKm >= 0) data.odometerKm else 100.0
        // LFP cells degrade ~1% per ~4,000 km in normal conditions
        val degradationPercent = (totalOdo / 4000.0).coerceIn(0.0, 20.0)
        val soh = (100 - degradationPercent).roundToInt().coerceIn(80, 100)

        // Charging detection
        val isCharging = data.vehicleState == 1 || (data.currentA < -0.5)
        var t80 = -1
        var t100 = -1
        if (data.timeToChargeMin > 0) {
            t100 = data.timeToChargeMin.roundToInt()
            if (currentSoc < 80) {
                val remTo80 = (80 - currentSoc).toDouble() / max(1, 100 - currentSoc)
                t80 = (data.timeToChargeMin * remTo80).roundToInt()
            } else {
                t80 = 0
            }
        }

        _aiState.value = AiRideState(
            aiPredictedRangeKm   = finalAiRange,
            rangeConfidence      = confidence,
            ecoScore             = calculatedScore,
            ecoRating            = rating,
            estimatedKmPerSoc    = Math.round(kmPerSoc * 100.0) / 100.0,
            batteryHealthSoh     = soh,
            smartChargeTargetSoc = if (finalAiRange > 50) 80 else 100,
            smartChargeTip       = if (currentSoc >= 80 && !isCharging) "Battery at ${currentSoc}%. Ready for full daily commute." else "80% charge preserves cell lifespan x2.",
            coachTip             = tip,
            isCharging           = isCharging,
            timeTo80Min          = t80,
            timeTo100Min         = t100
        )
    }

    private fun evaluateEcoScore(currentSpeed: Int, mode: String): Pair<Int, String> {
        if (speedHistory.isEmpty()) return Pair(85, "Efficient Rider")

        var score = 90

        // 1. Aerodynamic Speed Factor
        if (currentSpeed > 55) {
            score -= 20 // High wind drag
        } else if (currentSpeed in 30..45) {
            score += 8  // Sweet spot cruising
        }

        // 2. Speed Variance (Throttle Smoothness)
        if (speedHistory.size >= 5) {
            val avg = speedHistory.average()
            val variance = speedHistory.map { abs(it - avg) }.average()
            if (variance < 3.0) {
                score += 5 // Rock steady cruising
            } else if (variance > 10.0) {
                score -= 15 // Jerky / stop-and-go throttle
            }
        }

        // 3. Riding Mode Factor
        when (mode.uppercase()) {
            "ECO"   -> score += 5
            "POWER" -> score -= 10
        }

        val clamped = score.coerceIn(40, 100)
        val rating = when {
            clamped >= 90 -> "Eco Master"
            clamped >= 78 -> "Efficient Rider"
            clamped >= 65 -> "Moderate Commuter"
            else          -> "Sporty / Heavy Throttle"
        }
        return Pair(clamped, rating)
    }

    private fun generateCoachTip(data: ScooterData, soc: Int, aiRange: Int, ecoScore: Int): String {
        return when {
            soc <= 15 -> "Battery critical (${soc}%). Limp to safety in ECO mode; range ~${aiRange} km."
            soc <= 25 -> "Battery low (${soc}%). Safe return range: ${aiRange} km. Avoid Power mode."
            data.speedKmh > 58 -> "Cruising at ${data.speedKmh} km/h increases drag by ~35%. Easing to 45 km/h adds +12 km range."
            ecoScore >= 92 -> "Superb throttle control! You are achieving maximum energy efficiency."
            data.ridingMode.equals("Power", ignoreCase = true) && data.speedKmh < 30 -> "City stop-and-go traffic: Switch to 'City' or 'ECO' for smoother start & +20% battery savings."
            data.temperatureC > 45 -> "Battery temperature elevated (${data.temperatureC}°C). Park in shade and allow cooldown."
            else -> "Smooth cruising between 35-45 km/h in ECO mode yields optimal energy efficiency."
        }
    }

    fun resetTrip() {
        speedHistory.clear()
        initialSoc = -1
        initialOdo = -1.0
        lastRecordedOdo = -1.0
        lastRecordedSoc = -1
    }
}
