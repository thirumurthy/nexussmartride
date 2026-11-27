package com.thirutricks.nexussmartride.data

import com.thirutricks.nexussmartride.ScooterData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository that abstracts the source of scooter telemetry (BLE vs DB).
 */
class ScooterRepository(private val dao: ScooterDao) {

    private val _currentData = MutableStateFlow(ScooterData())
    val currentData: StateFlow<ScooterData> = _currentData.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun updateCurrentData(data: ScooterData) {
        _currentData.value = data
    }

    suspend fun saveLog(data: ScooterData) {
        if (!data.isValid) return
        
        dao.insertLog(
            ScooterLogEntity(
                timestamp = data.timestampMs,
                speedKmh = data.speedKmh,
                socPercent = data.socPercent,
                remainingKm = data.remainingKm,
                voltageV = data.voltageV,
                currentA = data.currentA,
                vehicleState = data.vehicleState,
                temperatureC = data.temperatureC
            )
        )
        
        // Update daily distance if valid odometer received
        if (data.odometerKm > 0.0) {
            updateOdometerReading(data.odometerKm)
        }
    }

    /**
     * Updates daily distance driven using the reliable odometer difference:
     *   Distance driven today = Today's current odometer - Yesterday's final odometer.
     * If the difference is > 300 km or < 0 km, ignore (charging takes ~3 hrs, 90 km per charge max).
     */
    suspend fun updateOdometerReading(odometerKm: Double): Double {
        if (odometerKm <= 0.0) return getTodayDistance()

        val today = dateFormatter.format(Date())
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dateFormatter.format(cal.time)

        val yesterdayTravel = dao.getDailyTravel(yesterday)
        val todayTravel = dao.getDailyTravel(today)

        // Baseline odometer is yesterday's end odometer if available
        val baselineOdo = when {
            yesterdayTravel != null && yesterdayTravel.lastOdometer > 0.0 -> yesterdayTravel.lastOdometer
            todayTravel != null && todayTravel.lastOdometer > 0.0 && todayTravel.distanceKm <= 0.0 -> todayTravel.lastOdometer
            else -> todayTravel?.lastOdometer?.takeIf { it > 0.0 } ?: odometerKm
        }

        val diff = odometerKm - baselineOdo
        val distance = if (diff in 0.0..300.0) {
            Math.round(diff * 10.0) / 10.0
        } else {
            todayTravel?.distanceKm ?: 0.0
        }

        dao.insertDailyTravel(
            DailyTravelEntity(
                date = today,
                distanceKm = distance,
                lastOdometer = odometerKm
            )
        )
        return distance
    }

    suspend fun getTodayDistance(): Double {
        val today = dao.getDailyTravel(dateFormatter.format(Date()))
        val dist = today?.distanceKm ?: 0.0
        return if (dist in 0.0..300.0) dist else 0.0
    }

    suspend fun getYesterdayDistance(): Double {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dao.getDailyTravel(dateFormatter.format(cal.time))
        val dist = yesterday?.distanceKm ?: 0.0
        return if (dist in 0.0..300.0) dist else 0.0
    }

    suspend fun getCurrentMonthDistance(): Double {
        val currentMonthPrefix = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val records = dao.getRecentDailyTravel()
        val monthKm = records
            .filter { it.date.startsWith(currentMonthPrefix) }
            .sumOf { it.distanceKm }
        return Math.round(monthKm * 10.0) / 10.0
    }

    suspend fun saveDailyTravel(date: String, distanceKm: Double, socConsumed: Int = 0) {
        if (date.isBlank()) return
        val existing = dao.getDailyTravel(date)
        if (existing == null) {
            dao.insertDailyTravel(
                DailyTravelEntity(
                    date = date,
                    distanceKm = distanceKm,
                    lastOdometer = 0.0,
                    socConsumed = socConsumed
                )
            )
        } else {
            val finalSoc = if (socConsumed > 0) socConsumed else existing.socConsumed
            val finalDist = maxOf(existing.distanceKm, distanceKm)
            dao.insertDailyTravel(
                existing.copy(distanceKm = finalDist, socConsumed = finalSoc)
            )
        }
    }

    fun getAllDailyTravel() = dao.getAllDailyTravel()
    suspend fun getRecentDailyTravel(): List<DailyTravelEntity> = dao.getRecentDailyTravel()

    suspend fun getLogsSince(since: Long) = dao.getLogsSince(since)
    suspend fun getAllLogs(): List<ScooterLogEntity> = dao.getAllLogs()

    // ─── Intelligent Journeys ──────────────────────────────────────────────────
    fun getRecentJourneys(limit: Int = 10): kotlinx.coroutines.flow.Flow<List<JourneyEntity>> = dao.getRecentJourneys(limit)
    suspend fun getRecentJourneysList(limit: Int = 10): List<JourneyEntity> = dao.getRecentJourneysList(limit)
    suspend fun saveJourney(journey: JourneyEntity): Long = dao.insertJourney(journey)
    suspend fun saveJourneys(journeys: List<JourneyEntity>) = dao.insertJourneys(journeys)
    suspend fun getJourneyCount(): Int = dao.getJourneyCount()
    suspend fun deleteJourney(id: Long) = dao.deleteJourney(id)
    suspend fun deleteAllJourneys() = dao.deleteAllJourneys()

    companion object {
        @Volatile
        private var INSTANCE: ScooterRepository? = null

        fun getInstance(dao: ScooterDao): ScooterRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = ScooterRepository(dao)
                INSTANCE = instance
                instance
            }
        }
    }
}
