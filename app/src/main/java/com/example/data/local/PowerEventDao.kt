package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.PowerEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface PowerEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: PowerEvent): Long

    @Query("SELECT * FROM power_events ORDER BY timestamp DESC LIMIT 100")
    fun getAllEventsFlow(): Flow<List<PowerEvent>>

    @Query("SELECT * FROM power_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEvent(): PowerEvent?

    @Query("SELECT * FROM power_events WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    suspend fun getEventsSince(sinceTimestamp: Long): List<PowerEvent>

    @Query("DELETE FROM power_events")
    suspend fun clearAll()
}
