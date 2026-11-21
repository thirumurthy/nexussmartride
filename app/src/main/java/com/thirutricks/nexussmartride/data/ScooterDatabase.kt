package com.thirutricks.nexussmartride.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity to store periodic snapshots of scooter telemetry.
 */
@Entity(tableName = "scooter_logs")
data class ScooterLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val speedKmh: Int,
    val socPercent: Int,
    val remainingKm: Int,
    val voltageV: Double,
    val currentA: Double,
    val vehicleState: Int,
    val temperatureC: Int
)

/**
 * Entity to store daily aggregated travel statistics.
 */
@Entity(tableName = "daily_travel")
data class DailyTravelEntity(
    @PrimaryKey val date: String, // Format: YYYY-MM-DD
    val distanceKm: Double,
    val lastOdometer: Double = 0.0
)

/**
 * Entity to store queued offline sync payloads until network connectivity is available.
 */
@Entity(tableName = "pending_syncs")
data class PendingSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val timestamp: Long,
    val trigger: String, // DISCONNECT, CONNECT, PERIODIC, MANUAL
    val odometerKm: Double,
    val tripKm: Double,
    val socPercent: Int,
    val remainingRangeKm: Int,
    val ridingMode: String,
    val appVersion: String,
    val retryCount: Int = 0
)

@Dao
interface ScooterDao {
    @Insert
    suspend fun insertLog(log: ScooterLogEntity)

    @Query("SELECT * FROM scooter_logs ORDER BY timestamp DESC LIMIT 1")
    fun getLatestLog(): Flow<ScooterLogEntity?>

    @Query("SELECT * FROM scooter_logs WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getLogsSince(since: Long): List<ScooterLogEntity>

    // Aggregation for daily metrics
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyTravel(travel: DailyTravelEntity)

    @Query("SELECT * FROM daily_travel WHERE date = :date")
    suspend fun getDailyTravel(date: String): DailyTravelEntity?

    @Query("SELECT * FROM daily_travel ORDER BY date DESC LIMIT 30")
    fun getAllDailyTravel(): Flow<List<DailyTravelEntity>>

    // Offline Cloud Sync Queue
    @Insert
    suspend fun insertPendingSync(sync: PendingSyncEntity): Long

    @Query("SELECT * FROM pending_syncs ORDER BY timestamp ASC")
    suspend fun getPendingSyncs(): List<PendingSyncEntity>

    @Query("DELETE FROM pending_syncs WHERE id = :id")
    suspend fun deletePendingSync(id: Long)

    @Query("SELECT COUNT(*) FROM pending_syncs")
    fun getPendingSyncCount(): Flow<Int>
}

@Database(entities = [ScooterLogEntity::class, DailyTravelEntity::class, PendingSyncEntity::class], version = 2)
abstract class ScooterDatabase : RoomDatabase() {
    abstract fun scooterDao(): ScooterDao

    companion object {
        @Volatile
        private var INSTANCE: ScooterDatabase? = null

        fun getDatabase(context: android.content.Context): ScooterDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScooterDatabase::class.java,
                    "scooter_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
