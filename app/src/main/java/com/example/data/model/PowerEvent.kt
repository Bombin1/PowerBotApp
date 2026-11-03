package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "power_events")
data class PowerEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String, // "POWER_CONNECTED" or "POWER_DISCONNECTED"
    val batteryPercent: Int,
    val temperatureCelsius: Float,
    val durationMinutes: Long = 0 // duration of the previous state in minutes
)
