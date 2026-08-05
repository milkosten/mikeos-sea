package com.mikeos.sea.trips

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One recorded trip. A row is created when the captain hits Record and finalised (endedAt +
 * aggregates) when they stop. Persisted so trips survive app restarts and can be reviewed later.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceKm: Double = 0.0,
    val maxSogKn: Double = 0.0,
    val avgSogKn: Double = 0.0,
    val pointCount: Int = 0,
)

/** One GPS sample within a trip — logged every ~5 s while recording. */
@Entity(
    tableName = "trip_points",
    indices = [Index("tripId")],
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val sogKn: Double? = null,
    val depthM: Double? = null,
    val windKn: Double? = null,
)

@Dao
interface TripDao {
    @Insert suspend fun insertTrip(t: TripEntity): Long
    @Insert suspend fun insertPoint(p: TripPointEntity)

    @Query("UPDATE trips SET endedAt = :endedAt, distanceKm = :distanceKm, maxSogKn = :maxSog, " +
        "avgSogKn = :avgSog, pointCount = :pointCount WHERE id = :id")
    suspend fun finishTrip(id: Long, endedAt: Long, distanceKm: Double, maxSog: Double, avgSog: Double, pointCount: Int)

    /** All trips newest-first, as a live Flow for the history list. */
    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun allTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun tripById(id: Long): TripEntity?

    @Query("SELECT * FROM trip_points WHERE tripId = :id ORDER BY ts ASC")
    suspend fun points(id: Long): List<TripPointEntity>

    @Query("SELECT COUNT(*) FROM trip_points WHERE tripId = :id")
    suspend fun pointCount(id: Long): Int

    /** Cascades to trip_points via the foreign key. */
    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)
}

@Database(entities = [TripEntity::class, TripPointEntity::class], version = 1, exportSchema = false)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile private var INSTANCE: TripDatabase? = null
        fun get(context: Context): TripDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, TripDatabase::class.java, "mikeos-trips.db"
            ).build().also { INSTANCE = it }
        }
    }
}
