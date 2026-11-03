package com.example.data.model

data class BatteryInfo(
    val isPlugged: Boolean,
    val percent: Int,
    val temperatureCelsius: Float,
    val voltageMv: Int,
    val plugType: PlugType,
    val health: String,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PlugType {
    NONE,
    AC,
    USB,
    WIRELESS
}

enum class OutageStatus {
    ONLINE, // Світло є (yes)
    OUTAGE, // Відключення (no)
    MAYBE   // Можливе відключення (first, second, maybe)
}

data class HourSlot(
    val hour: Int, // 1..24
    val timeLabel: String, // "00:00 - 01:00"
    val status: OutageStatus,
    val statusDescription: String
)

data class OutageInterval(
    val startHour: String,
    val endHour: String,
    val status: OutageStatus,
    val description: String,
    val durationHours: Int
)

data class CitySchedule(
    val cityName: String,
    val queueName: String,
    val dateLabel: String,
    val hours: List<HourSlot>,
    val intervals: List<OutageInterval>,
    val lastUpdated: Long = System.currentTimeMillis()
)
