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
        
        // Update daily distance if running
        // For now, we estimate distance = speed * time
        // (This should be replaced with native odometer if found)
        if (data.vehicleState == 2 && data.speedKmh > 0) {
            updateDailyDistance(data)
        }
    }

    private suspend fun updateDailyDistance(data: ScooterData) {
        val today = dateFormatter.format(Date())
        val existing = dao.getDailyTravel(today)
        
        // Interval is roughly 10 minutes (based on external sync logic)
        // or whatever the snapshot interval is. 
        // Better: store last sync time and calculate precisely.
        val lastLog = dao.getLogsSince(System.currentTimeMillis() - 15 * 60 * 1000).lastOrNull { it.timestamp < data.timestampMs }
        
        val distanceDelta = if (lastLog != null) {
            val hours = (data.timestampMs - lastLog.timestamp) / 3600000.0
            val avgSpeed = (data.speedKmh + lastLog.speedKmh) / 2.0
            avgSpeed * hours
        } else 0.0

        val newDist = (existing?.distanceKm ?: 0.0) + distanceDelta
        dao.insertDailyTravel(DailyTravelEntity(today, newDist))
    }

    suspend fun getTodayDistance(): Double {
        return dao.getDailyTravel(dateFormatter.format(Date()))?.distanceKm ?: 0.0
    }

    suspend fun getYesterdayDistance(): Double {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return dao.getDailyTravel(dateFormatter.format(cal.time))?.distanceKm ?: 0.0
    }

    fun getAllDailyTravel() = dao.getAllDailyTravel()
    
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
