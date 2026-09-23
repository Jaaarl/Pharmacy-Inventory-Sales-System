package com.medtryx.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * A Phase 0 persistence probe only. It will be replaced by the production
 * database schema before F01 user and credential records are introduced.
 */
@Entity(tableName = "compatibility_probe")
data class CompatibilityProbe(
    val id: Int = 1,
    val launchCount: Int,
)

@Dao
interface CompatibilityProbeDao {
    @Query("SELECT * FROM compatibility_probe WHERE id = 1")
    suspend fun read(): CompatibilityProbe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(probe: CompatibilityProbe)
}

@Database(entities = [CompatibilityProbe::class], version = 1, exportSchema = true)
abstract class DeviceCompatibilityDatabase : RoomDatabase() {
    abstract fun compatibilityProbeDao(): CompatibilityProbeDao
}
