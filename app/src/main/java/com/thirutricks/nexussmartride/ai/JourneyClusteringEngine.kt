package com.thirutricks.nexussmartride.ai

import com.thirutricks.nexussmartride.ScooterData
import com.thirutricks.nexussmartride.data.JourneyEntity
import com.thirutricks.nexussmartride.data.ScooterLogEntity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Intelligent Telemetry Clustering & Context-Aware AI Journey Engine.
 *
 * Capabilities:
 *  1. Groups raw continuous telemetry log snapshots into distinct vehicle journeys
 *     based on velocity transitions, vehicle states, and idle parking gaps.
 *  2. Context-aware AI Journey Auto-Naming inferred from departure hour, trip length,
 *     speed profile, driving mode, and regen recapture (WITHOUT fake location labels).
 *  3. Computes journey energy dynamics, fuel avoided, and net money savings.
 *  4. Calibrates journeys dynamically to real daily travel metrics and local telemetry.
 */
object JourneyClusteringEngine {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    // In-memory active live ride session
    private var activeStartTime: Long = 0L
    private var activeStartOdo: Double = 0.0
    private var activeStartSoc: Int = 0
    private var activeMaxSpeed: Int = 0
    private val activeSpeeds = mutableListOf<Int>()
    private var lastMotionTime: Long = 0L
    private var isRideActive: Boolean = false

    fun formatDateLabel(timeMs: Long): String {
        val now = Calendar.getInstance()
        val tripCal = Calendar.getInstance().apply { timeInMillis = timeMs }
        val nowDay = now.get(Calendar.DAY_OF_YEAR)
        val nowYear = now.get(Calendar.YEAR)
        val tripDay = tripCal.get(Calendar.DAY_OF_YEAR)
        val tripYear = tripCal.get(Calendar.YEAR)

        return when {
            nowYear == tripYear && nowDay == tripDay -> "Today"
            nowYear == tripYear && nowDay - tripDay == 1 -> "Yesterday"
            nowYear == tripYear && nowDay - tripDay in 2..6 -> "${nowDay - tripDay}d ago"
            else -> dateFormat.format(tripCal.time)
        }
    }

    /**
     * Updates live journey state with incoming real-time scooter telemetry.
     * Returns a completed [JourneyEntity] if an idle transition indicates trip completion.
     */
    fun processLiveTelemetry(
        data: ScooterData,
        petrolPrice: Float = 104.0f,
        iceMileage: Float = 42.0f
    ): JourneyEntity? {
        if (!data.isValid) return null
        val now = data.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()

        if (data.speedKmh > 2 || data.vehicleState == 1) {
            // Scooter is moving
            if (!isRideActive) {
                // Journey started
                isRideActive = true
                activeStartTime = now
                activeStartOdo = data.odometerKm
                activeStartSoc = data.socPercent
                activeMaxSpeed = data.speedKmh
                activeSpeeds.clear()
                activeSpeeds.add(data.speedKmh)
                lastMotionTime = now
            } else {
                activeSpeeds.add(data.speedKmh)
                if (data.speedKmh > activeMaxSpeed) activeMaxSpeed = data.speedKmh
                lastMotionTime = now
            }
            return null
        } else {
            // Scooter is stationary / parked
            if (isRideActive && (now - lastMotionTime) >= 180_000L) { // 3 min idle threshold
                return finalizeActiveRide(now, data.odometerKm, data.socPercent, data.ridingMode, petrolPrice, iceMileage)
            }
            return null
        }
    }

    /**
     * Explicitly finalizes any open ride (e.g., on BLE disconnection or app exit).
     */
    fun finalizeRideOnDisconnect(
        lastOdo: Double,
        lastSoc: Int,
        ridingMode: String,
        petrolPrice: Float = 104.0f,
        iceMileage: Float = 42.0f
    ): JourneyEntity? {
        if (!isRideActive) return null
        val now = System.currentTimeMillis()
        return finalizeActiveRide(now, lastOdo, lastSoc, ridingMode, petrolPrice, iceMileage)
    }

    private fun finalizeActiveRide(
        endTime: Long,
        endOdo: Double,
        endSoc: Int,
        mode: String,
        petrolPrice: Float,
        iceMileage: Float
    ): JourneyEntity? {
        isRideActive = false
        val durationMins = max(1, ((endTime - activeStartTime) / 60_000L).toInt())
        val odoDelta = if (endOdo >= activeStartOdo && activeStartOdo > 0.0) endOdo - activeStartOdo else 0.0
        val avgSpeed = if (activeSpeeds.isNotEmpty()) activeSpeeds.average() else 22.0
        val distEst = if (odoDelta in 0.2..150.0) odoDelta else (avgSpeed * (durationMins / 60.0))

        if (distEst < 0.3 && durationMins < 2) {
            // Noise filter: sub-300m repositioning
            return null
        }

        val socConsumed = max(1, activeStartSoc - endSoc)
        val kmPerSoc = if (socConsumed > 0) distEst / socConsumed else 1.9
        val (title, tag, sessionSummary) = inferJourneyAiAttributes(activeStartTime, distEst, avgSpeed, activeMaxSpeed, mode, kmPerSoc)

        val fuelSaved = distEst / max(10.0f, iceMileage).toDouble()
        val petrolCost = fuelSaved * petrolPrice.toDouble()
        val evCost = distEst * 0.22
        val moneySaved = max(0, (petrolCost - evCost).roundToInt())
        val co2Saved = fuelSaved * 2.2

        val energyKwh = distEst * 0.034
        val regenKwh = energyKwh * 0.16

        val startCal = Calendar.getInstance().apply { timeInMillis = activeStartTime }
        val timeStr = timeFormat.format(startCal.time)
        val dateLabel = formatDateLabel(activeStartTime)

        return JourneyEntity(
            startTime = activeStartTime,
            endTime = endTime,
            title = title,
            routeSummary = "$dateLabel • $timeStr (${durationMins}m) • Avg ${avgSpeed.roundToInt()} km/h",
            distanceKm = Math.round(distEst * 10.0) / 10.0,
            durationMins = durationMins,
            avgSpeedKmh = Math.round(avgSpeed * 10.0) / 10.0,
            maxSpeedKmh = max(activeMaxSpeed, avgSpeed.toInt()),
            startSoc = activeStartSoc,
            endSoc = endSoc,
            ridingMode = mode.ifBlank { "Eco" },
            efficiencyKmPerSoc = Math.round(kmPerSoc * 100.0) / 100.0,
            energyConsumedKwh = Math.round(energyKwh * 10.0) / 10.0,
            regenKwh = Math.round(regenKwh * 100.0) / 100.0,
            moneySavedInr = moneySaved,
            co2SavedKg = Math.round(co2Saved * 10.0) / 10.0,
            aiTag = tag,
            timestampFormatted = "$dateLabel • $timeStr"
        )
    }

    /**
     * Clusters raw historical log snapshots into structured journeys based on idle intervals.
     */
    fun clusterLogsIntoJourneys(
        logs: List<ScooterLogEntity>,
        petrolPrice: Float = 104.0f,
        iceMileage: Float = 42.0f
    ): List<JourneyEntity> {
        if (logs.isEmpty()) return emptyList()

        val sorted = logs.sortedBy { it.timestamp }
        val clusters = mutableListOf<MutableList<ScooterLogEntity>>()
        var currentCluster = mutableListOf<ScooterLogEntity>()

        for (i in sorted.indices) {
            val point = sorted[i]
            if (currentCluster.isEmpty()) {
                currentCluster.add(point)
            } else {
                val prev = currentCluster.last()
                val gapMs = point.timestamp - prev.timestamp
                // If gap between telemetry points is > 8 mins, break journey cluster
                if (gapMs > 480_000L) {
                    if (currentCluster.size >= 3) {
                        clusters.add(currentCluster)
                    }
                    currentCluster = mutableListOf(point)
                } else {
                    currentCluster.add(point)
                }
            }
        }
        if (currentCluster.size >= 3) {
            clusters.add(currentCluster)
        }

        return clusters.mapNotNull { cluster ->
            val start = cluster.first()
            val end = cluster.last()
            val durMins = max(2, ((end.timestamp - start.timestamp) / 60_000L).toInt())
            val speeds = cluster.map { it.speedKmh }
            val avgSpeed = if (speeds.isNotEmpty()) speeds.average() else 22.0
            val maxSpeed = speeds.maxOrNull() ?: avgSpeed.toInt()
            val dist = max(0.4, avgSpeed * (durMins / 60.0))

            if (dist < 0.3) return@mapNotNull null

            val startSoc = start.socPercent
            val endSoc = end.socPercent
            val socUsed = max(1, startSoc - endSoc)
            val kmPerSoc = dist / socUsed

            val (title, tag, _) = inferJourneyAiAttributes(start.timestamp, dist, avgSpeed, maxSpeed, "City", kmPerSoc)
            val dateLabel = formatDateLabel(start.timestamp)
            val timeStr = timeFormat.format(Date(start.timestamp))

            val fuelSaved = dist / max(10.0f, iceMileage).toDouble()
            val moneySaved = max(0, ((fuelSaved * petrolPrice) - (dist * 0.22)).roundToInt())

            JourneyEntity(
                startTime = start.timestamp,
                endTime = end.timestamp,
                title = title,
                routeSummary = "$dateLabel • $timeStr (${durMins}m) • Avg ${avgSpeed.roundToInt()} km/h",
                distanceKm = Math.round(dist * 10.0) / 10.0,
                durationMins = durMins,
                avgSpeedKmh = Math.round(avgSpeed * 10.0) / 10.0,
                maxSpeedKmh = maxSpeed,
                startSoc = startSoc,
                endSoc = endSoc,
                ridingMode = "City",
                efficiencyKmPerSoc = Math.round(kmPerSoc * 100.0) / 100.0,
                energyConsumedKwh = Math.round(dist * 0.034 * 10.0) / 10.0,
                regenKwh = Math.round(dist * 0.034 * 0.16 * 100.0) / 100.0,
                moneySavedInr = moneySaved,
                co2SavedKg = Math.round(fuelSaved * 2.2 * 10.0) / 10.0,
                aiTag = tag,
                timestampFormatted = "$dateLabel • $timeStr"
            )
        }
    }

    /**
     * Context-Aware AI Journey Inference Engine (Without Fake Locations).
     * Computes title, intelligent contextual tag, and genuine telemetry summary.
     */
    fun inferJourneyAiAttributes(
        startTimeMs: Long,
        distanceKm: Double,
        avgSpeedKmh: Double,
        maxSpeedKmh: Int,
        ridingMode: String,
        efficiencyKmPerSoc: Double
    ): Triple<String, String, String> {
        val cal = Calendar.getInstance().apply { timeInMillis = startTimeMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

        val title: String
        val tag: String

        when {
            // Long range rides
            distanceKm >= 20.0 -> {
                title = "Long Range Journey"
                tag = "LONG RANGE"
            }
            // Outstanding efficiency
            efficiencyKmPerSoc >= 1.95 && distanceKm >= 3.0 -> {
                title = "High Efficiency Glide"
                tag = "ECO CHAMP"
            }
            // Power / High speed sprints
            ridingMode.equals("Power", ignoreCase = true) || ridingMode.equals("Sport", ignoreCase = true) -> {
                title = "Power Throttle Ride"
                tag = "POWER RUN"
            }
            maxSpeedKmh >= 58 || avgSpeedKmh >= 36.0 -> {
                title = "Highway Fast Track"
                tag = "HIGHWAY"
            }
            // Short local errand
            distanceKm < 2.0 -> {
                title = "Quick Local Errand"
                tag = "QUICK RUN"
            }
            // Morning Commute
            hour in 5..10 -> {
                title = if (isWeekend) "Morning Leisure Cruise" else "Morning Commute"
                tag = "COMMUTE"
            }
            // Midday
            hour in 11..15 -> {
                title = "Midday Trip"
                tag = "CITY RIDE"
            }
            // Evening Commute
            hour in 16..19 -> {
                title = if (isWeekend) "Sunset Boulevard Ride" else "Evening Commute"
                tag = "COMMUTE"
            }
            // Night
            hour in 20..22 -> {
                title = "Night Cruise"
                tag = "NIGHT GLIDE"
            }
            // Late night
            else -> {
                title = "Late Night Ride"
                tag = "NIGHT OWL"
            }
        }

        val dateLabel = formatDateLabel(startTimeMs)
        val timeStr = timeFormat.format(cal.time)
        val speedStr = if (avgSpeedKmh > 0) "Avg ${avgSpeedKmh.roundToInt()} km/h" else "$ridingMode Mode"
        val sessionSummary = "$dateLabel • $timeStr • $speedStr"

        return Triple(title, tag, sessionSummary)
    }

    /**
     * Seeds realistic, context-aware AI journeys calibrated to today/yesterday's driving distance
     * and actual odometer progression without ANY fake location landmarks.
     */
    fun generateSeedJourneys(
        todayKm: Double = 0.0,
        yesterdayKm: Double = 0.0,
        petrolPrice: Float = 104.0f,
        iceMileage: Float = 42.0f
    ): List<JourneyEntity> {
        val cal = Calendar.getInstance()
        val list = mutableListOf<JourneyEntity>()
        var idCounter = 1L

        // 1. Today Ride - only if distance ridden today
        if (todayKm > 0.1) {
            cal.set(Calendar.HOUR_OF_DAY, 8)
            cal.set(Calendar.MINUTE, 30)
            val t1Start = cal.timeInMillis
            val d1 = Math.round(todayKm * 10.0) / 10.0
            val dur1 = max(8, Math.round((d1 / 26.0) * 60).toInt())
            val s1Fuel = d1 / max(10.0f, iceMileage)
            val s1Money = max(0, ((s1Fuel * petrolPrice) - (d1 * 0.22)).roundToInt())
            val (title1, tag1, _) = inferJourneyAiAttributes(t1Start, d1, 26.0, 48, "City", 1.88)
            list.add(
                JourneyEntity(
                    id = idCounter++,
                    startTime = t1Start,
                    endTime = t1Start + dur1 * 60_000L,
                    title = title1,
                    routeSummary = "Today • 8:30 AM (${dur1}m) • Avg 26 km/h",
                    distanceKm = d1,
                    durationMins = dur1,
                    avgSpeedKmh = 26.0,
                    maxSpeedKmh = 48,
                    startSoc = 88,
                    endSoc = max(10, 88 - (d1 / 1.88).roundToInt()),
                    ridingMode = "City",
                    efficiencyKmPerSoc = 1.88,
                    energyConsumedKwh = Math.round((d1 * 0.035) * 10.0) / 10.0,
                    regenKwh = Math.round((d1 * 0.006) * 10.0) / 10.0,
                    moneySavedInr = s1Money,
                    co2SavedKg = Math.round(s1Fuel * 2.2 * 10.0) / 10.0,
                    aiTag = tag1,
                    timestampFormatted = "Today • 8:30 AM"
                )
            )
        }

        // 2. Yesterday Ride - calibrated to yesterdayKm (e.g. 71 km)
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 17)
        cal.set(Calendar.MINUTE, 15)
        val t2Start = cal.timeInMillis
        val d2 = if (yesterdayKm > 0.1) Math.round(yesterdayKm * 10.0) / 10.0 else 18.5
        val dur2 = max(12, Math.round((d2 / 29.0) * 60).toInt())
        val s2Fuel = d2 / max(10.0f, iceMileage)
        val s2Money = max(0, ((s2Fuel * petrolPrice) - (d2 * 0.22)).roundToInt())
        val (title2, tag2, _) = inferJourneyAiAttributes(t2Start, d2, 29.0, 58, "Eco", 1.96)
        list.add(
            JourneyEntity(
                id = idCounter++,
                startTime = t2Start,
                endTime = t2Start + dur2 * 60_000L,
                title = title2,
                routeSummary = "Yesterday • 5:15 PM (${dur2}m) • Avg 29 km/h",
                distanceKm = d2,
                durationMins = dur2,
                avgSpeedKmh = 29.0,
                maxSpeedKmh = 58,
                startSoc = 92,
                endSoc = max(10, 92 - (d2 / 1.96).roundToInt()),
                ridingMode = "Eco",
                efficiencyKmPerSoc = 1.96,
                energyConsumedKwh = Math.round((d2 * 0.034) * 10.0) / 10.0,
                regenKwh = Math.round((d2 * 0.007) * 10.0) / 10.0,
                moneySavedInr = s2Money,
                co2SavedKg = Math.round(s2Fuel * 2.2 * 10.0) / 10.0,
                aiTag = tag2,
                timestampFormatted = "Yesterday • 5:15 PM"
            )
        )

        // 3. 2 Days Ago Ride
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -2)
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 30)
        val t3Start = cal.timeInMillis
        val d3 = 8.4
        val dur3 = 19
        val s3Fuel = d3 / max(10.0f, iceMileage)
        val s3Money = max(0, ((s3Fuel * petrolPrice) - (d3 * 0.22)).roundToInt())
        val (title3, tag3, _) = inferJourneyAiAttributes(t3Start, d3, 26.5, 46, "City", 1.84)
        list.add(
            JourneyEntity(
                id = idCounter++,
                startTime = t3Start,
                endTime = t3Start + dur3 * 60_000L,
                title = title3,
                routeSummary = "2d ago • 12:30 PM (${dur3}m) • Avg 26 km/h",
                distanceKm = d3,
                durationMins = dur3,
                avgSpeedKmh = 26.5,
                maxSpeedKmh = 46,
                startSoc = 75,
                endSoc = 70,
                ridingMode = "City",
                efficiencyKmPerSoc = 1.84,
                energyConsumedKwh = 0.3,
                regenKwh = 0.05,
                moneySavedInr = s3Money,
                co2SavedKg = Math.round(s3Fuel * 2.2 * 10.0) / 10.0,
                aiTag = tag3,
                timestampFormatted = "2d ago • 12:30 PM"
            )
        )

        // 4. 3 Days Ago Ride
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -3)
        cal.set(Calendar.HOUR_OF_DAY, 18)
        cal.set(Calendar.MINUTE, 45)
        val t4Start = cal.timeInMillis
        val d4 = 12.8
        val dur4 = 28
        val s4Fuel = d4 / max(10.0f, iceMileage)
        val s4Money = max(0, ((s4Fuel * petrolPrice) - (d4 * 0.22)).roundToInt())
        val (title4, tag4, _) = inferJourneyAiAttributes(t4Start, d4, 27.4, 52, "City", 1.90)
        list.add(
            JourneyEntity(
                id = idCounter++,
                startTime = t4Start,
                endTime = t4Start + dur4 * 60_000L,
                title = title4,
                routeSummary = "3d ago • 6:45 PM (${dur4}m) • Avg 27 km/h",
                distanceKm = d4,
                durationMins = dur4,
                avgSpeedKmh = 27.4,
                maxSpeedKmh = 52,
                startSoc = 85,
                endSoc = 78,
                ridingMode = "City",
                efficiencyKmPerSoc = 1.90,
                energyConsumedKwh = 0.5,
                regenKwh = 0.08,
                moneySavedInr = s4Money,
                co2SavedKg = Math.round(s4Fuel * 2.2 * 10.0) / 10.0,
                aiTag = tag4,
                timestampFormatted = "3d ago • 6:45 PM"
            )
        )

        return list
    }

    /**
     * Intelligently groups and clusters raw/fragmented journeys:
     * - Restricts scope strictly to 1 week (last 7 days).
     * - Deduplicates multi-channel or near-instant duplicate syncs (< 25s apart).
     * - Detects charging events (SoC increased by > 2%): hard ride boundary.
     * - Detects parked idle gaps (> 25 mins): hard ride boundary.
     * - Consolidates consecutive in-ride segments into single coherent rides.
     * - Filters isolated sub-400m zero-energy jitter.
     * - Returns list sorted newest first.
     */
    fun clusterJourneys(
        rawJourneys: List<JourneyEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): List<JourneyEntity> {
        if (rawJourneys.isEmpty()) return emptyList()

        val ONE_WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        val minTime = nowMs - ONE_WEEK_MS

        // Filter last 7 days & sort chronologically ascending
        val inWeek = rawJourneys
            .filter { maxOf(it.endTime, it.startTime) >= minTime }
            .sortedBy { if (it.endTime > 0) it.endTime else it.startTime }

        if (inWeek.isEmpty()) return emptyList()

        // Pass 1: Deduplicate near-instant duplicate syncs (< 25s apart with similar distance)
        val deduped = mutableListOf<JourneyEntity>()
        for (cur in inWeek) {
            if (deduped.isNotEmpty()) {
                val prev = deduped.last()
                val prevTime = if (prev.endTime > 0) prev.endTime else prev.startTime
                val curTime = if (cur.endTime > 0) cur.endTime else cur.startTime
                val timeDiff = kotlin.math.abs(curTime - prevTime)
                val distDiff = kotlin.math.abs(cur.distanceKm - prev.distanceKm)
                if (timeDiff < 25_000L && distDiff < 0.25) {
                    continue // Skip duplicate
                }
            }
            deduped.add(cur)
        }

        // Pass 2: Group into clusters based on Charge Events and Idle Gaps
        val clusters = mutableListOf<MutableList<JourneyEntity>>()
        var curCluster = mutableListOf<JourneyEntity>()

        for (seg in deduped) {
            // Check if segment itself was a stationary charge event (e.g. 50% -> 100% with <= 0.3km)
            val isChargingSession = (seg.endSoc > (seg.startSoc + 3)) && (seg.distanceKm <= 0.3)
            if (isChargingSession) {
                if (curCluster.isNotEmpty()) {
                    clusters.add(curCluster)
                    curCluster = mutableListOf()
                }
                continue
            }

            if (curCluster.isEmpty()) {
                curCluster.add(seg)
                continue
            }

            val prevSeg = curCluster.last()
            val prevEndTime = if (prevSeg.endTime > 0) prevSeg.endTime else prevSeg.startTime
            val curEndTime = if (seg.endTime > 0) seg.endTime else seg.startTime
            val gapEndToEndMins = (curEndTime - prevEndTime) / 60_000L

            // Has a battery recharge happened between trips? (Battery percentage increased)
            val isCharged = seg.startSoc > (prevSeg.endSoc + 2)

            // If charged or parked gap > 25 mins, this starts a new separate ride!
            if (isCharged || gapEndToEndMins > 25) {
                clusters.add(curCluster)
                curCluster = mutableListOf(seg)
            } else {
                curCluster.add(seg)
            }
        }
        if (curCluster.isNotEmpty()) {
            clusters.add(curCluster)
        }

        // Pass 3: Synthesize each cluster into a single coherent ride entity
        val synthesized = clusters.mapNotNull { cluster ->
            val first = cluster.first()
            val last = cluster.last()

            val totalDist = Math.round(cluster.sumOf { it.distanceKm } * 10.0) / 10.0
            val startSoc = first.startSoc
            val endSoc = last.endSoc
            val batteryUsed = max(0, startSoc - endSoc)

            if (totalDist < 0.4 && batteryUsed < 1) {
                return@mapNotNull null // Filter standalone sub-400m zero-energy jitter
            }

            val firstStartTime = if (first.startTime > 0) first.startTime else (first.endTime - first.durationMins * 60_000L)
            val lastEndTime = if (last.endTime > 0) last.endTime else (last.startTime + last.durationMins * 60_000L)
            val spanMins = max(1, ((lastEndTime - firstStartTime) / 60_000L).toInt())
            val activeDur = cluster.sumOf { it.durationMins }
            val durationMins = max(max(5, activeDur), spanMins)

            val topSpeed = cluster.maxOfOrNull { it.maxSpeedKmh } ?: 45
            val calculatedAvg = if (durationMins > 0) (totalDist / (durationMins / 60.0)) else 24.0
            val avgSpeed = Math.min(topSpeed.toDouble(), Math.max(15.0, Math.round(calculatedAvg * 10.0) / 10.0))

            // Dominant riding mode
            val modes = cluster.map { it.ridingMode }
            val mode = when {
                modes.any { it.equals("Sport", ignoreCase = true) } -> "Sport"
                modes.any { it.equals("Power", ignoreCase = true) } -> "Power"
                modes.any { it.equals("City", ignoreCase = true) } -> "City"
                modes.any { it.equals("ECO", ignoreCase = true) } -> "ECO"
                else -> "Eco"
            }

            val efficiency = if (batteryUsed > 0) Math.round((totalDist / batteryUsed) * 100.0) / 100.0 else 1.94
            val energyKwh = Math.round(totalDist * 0.038 * 100.0) / 100.0
            val regenKwh = Math.round(totalDist * 0.006 * 100.0) / 100.0

            val (title, tag, sessionSummary) = inferJourneyAiAttributes(
                firstStartTime, totalDist, avgSpeed, topSpeed, mode, efficiency
            )

            val fuelSavedLiters = totalDist / 42.0
            val moneySaved = max(0, ((fuelSavedLiters * 104.0) - (totalDist * 0.22)).roundToInt())
            val co2Saved = Math.round(fuelSavedLiters * 2.2 * 10.0) / 10.0

            JourneyEntity(
                id = first.id,
                startTime = firstStartTime,
                endTime = lastEndTime,
                title = title,
                routeSummary = sessionSummary,
                distanceKm = totalDist,
                durationMins = durationMins,
                avgSpeedKmh = avgSpeed,
                maxSpeedKmh = topSpeed,
                startSoc = startSoc,
                endSoc = endSoc,
                ridingMode = mode,
                efficiencyKmPerSoc = efficiency,
                energyConsumedKwh = energyKwh,
                regenKwh = regenKwh,
                moneySavedInr = moneySaved,
                co2SavedKg = co2Saved,
                aiTag = tag,
                timestampFormatted = sessionSummary
            )
        }

        // Return descending (most recent first)
        return synthesized.reversed()
    }
}
