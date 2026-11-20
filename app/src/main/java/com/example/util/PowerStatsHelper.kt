package com.example.util

import com.example.data.model.PowerEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

data class MonthKey(
    val year: Int,
    val month: Int // Calendar.JANUARY .. Calendar.DECEMBER (0..11)
) : Comparable<MonthKey> {
    override fun compareTo(other: MonthKey): Int {
        return if (year != other.year) year.compareTo(other.year) else month.compareTo(other.month)
    }

    fun formatTitle(): String {
        val monthNames = arrayOf(
            "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
            "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
        )
        val name = monthNames.getOrElse(month) { "" }
        return "$name $year"
    }
}

data class MonthStats(
    val onlineHours: Float,
    val outageHours: Float,
    val outageCount: Int,
    val totalEvents: Int
)

data class DailyOutageStat(
    val dateLabel: String,
    val outageMinutes: Long
)

object PowerStatsHelper {

    /**
     * Collects all available months from events plus the current month, sorted newest first.
     */
    fun getAvailableMonths(
        events: List<PowerEvent>,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<MonthKey> {
        val result = linkedSetOf<MonthKey>()
        val currentCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        result.add(MonthKey(currentCal.get(Calendar.YEAR), currentCal.get(Calendar.MONTH)))

        val cal = Calendar.getInstance(timeZone)
        for (event in events) {
            cal.timeInMillis = event.timestamp
            result.add(MonthKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)))
        }

        return result.sortedDescending()
    }

    /**
     * Filters events belonging to the given month.
     */
    fun filterEventsForMonth(
        events: List<PowerEvent>,
        monthKey: MonthKey,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<PowerEvent> {
        val cal = Calendar.getInstance(timeZone)
        return events.filter { event ->
            cal.timeInMillis = event.timestamp
            cal.get(Calendar.YEAR) == monthKey.year && cal.get(Calendar.MONTH) == monthKey.month
        }
    }

    /**
     * Calculates statistics for a selected month without resetting daily.
     */
    fun calculateMonthStats(
        events: List<PowerEvent>,
        monthKey: MonthKey,
        nowMs: Long = System.currentTimeMillis(),
        ongoingOutageStartMs: Long? = null,
        timeZone: TimeZone = TimeZone.getDefault()
    ): MonthStats {
        val monthStartCal = Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, monthKey.year)
            set(Calendar.MONTH, monthKey.month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStartMs = monthStartCal.timeInMillis

        val monthEndCal = (monthStartCal.clone() as Calendar).apply {
            add(Calendar.MONTH, 1)
        }
        val monthEndMs = monthEndCal.timeInMillis

        val effectiveEndMs = min(nowMs, monthEndMs)
        val elapsedMonthMinutes = max(0L, (effectiveEndMs - monthStartMs) / 60_000L)

        var outageMinutes = 0L
        var outageCount = 0

        val monthEvents = filterEventsForMonth(events, monthKey, timeZone)
        for (event in events) {
            if (event.eventType == "POWER_CONNECTED" && event.durationMinutes > 0L) {
                val intervalEnd = event.timestamp
                val intervalStart = intervalEnd - event.durationMinutes * 60_000L
                val overlapStart = max(intervalStart, monthStartMs)
                val overlapEnd = min(intervalEnd, monthEndMs)
                if (overlapEnd > overlapStart) {
                    outageMinutes += (overlapEnd - overlapStart) / 60_000L
                    outageCount++
                }
            } else if (event.eventType == "POWER_DISCONNECTED") {
                if (event.timestamp in monthStartMs until monthEndMs) {
                    // Count disconnections even if ongoing or not yet paired
                    if (events.none { it.eventType == "POWER_CONNECTED" && it.timestamp > event.timestamp && (it.timestamp - it.durationMinutes * 60_000L) <= event.timestamp + 60_000L }) {
                        outageCount++
                    }
                }
            }
        }

        if (ongoingOutageStartMs != null && nowMs > ongoingOutageStartMs) {
            val overlapStart = max(ongoingOutageStartMs, monthStartMs)
            val overlapEnd = min(nowMs, monthEndMs)
            if (overlapEnd > overlapStart) {
                outageMinutes += (overlapEnd - overlapStart) / 60_000L
            }
        }

        val outageHours = outageMinutes / 60f
        val onlineMinutes = max(0L, elapsedMonthMinutes - outageMinutes)
        val onlineHours = onlineMinutes / 60f

        return MonthStats(
            onlineHours = onlineHours,
            outageHours = outageHours,
            outageCount = outageCount,
            totalEvents = monthEvents.size
        )
    }

    /**
     * Computes daily outage stats for the last [daysCount] days (default 7 days including today).
     * Returns only days that had at least 1 minute of outage.
     */
    fun calculateWeeklyOutageStats(
        events: List<PowerEvent>,
        nowMs: Long = System.currentTimeMillis(),
        ongoingOutageStartMs: Long? = null,
        daysCount: Int = 7,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<DailyOutageStat> {
        val todayStartCal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val windowStartCal = (todayStartCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -(daysCount - 1))
        }

        val dayBuckets = LinkedHashMap<Long, Long>()
        val cursor = windowStartCal.clone() as Calendar
        for (i in 0 until daysCount) {
            dayBuckets[cursor.timeInMillis] = 0L
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Accumulate completed outage intervals (from POWER_CONNECTED events)
        val intervals = mutableListOf<Pair<Long, Long>>()
        for (event in events) {
            if (event.eventType == "POWER_CONNECTED" && event.durationMinutes > 0L) {
                val endMs = event.timestamp
                val startMs = endMs - event.durationMinutes * 60_000L
                intervals.add(startMs to endMs)
            }
        }
        // Include active ongoing outage if light is currently off
        if (ongoingOutageStartMs != null && nowMs > ongoingOutageStartMs) {
            intervals.add(ongoingOutageStartMs to nowMs)
        }

        val oneDayMs = 24L * 60L * 60L * 1000L
        for ((dayStartMs, _) in dayBuckets.toList()) {
            val dayEndCal = Calendar.getInstance(timeZone).apply {
                timeInMillis = dayStartMs
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val dayEndMs = dayEndCal.timeInMillis

            var dayMinutes = 0L
            for ((intStart, intEnd) in intervals) {
                val overlapStart = max(intStart, dayStartMs)
                val overlapEnd = min(intEnd, min(dayEndMs, nowMs + 60_000L))
                if (overlapEnd > overlapStart) {
                    dayMinutes += (overlapEnd - overlapStart) / 60_000L
                }
            }
            dayBuckets[dayStartMs] = min(dayMinutes, oneDayMs / 60_000L)
        }

        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply {
            this.timeZone = timeZone
        }

        val result = mutableListOf<DailyOutageStat>()
        for ((dayStartMs, minutes) in dayBuckets) {
            if (minutes > 0L) {
                result.add(
                    DailyOutageStat(
                        dateLabel = dateFormat.format(Date(dayStartMs)),
                        outageMinutes = minutes
                    )
                )
            }
        }
        return result
    }

    /**
     * Formats short weekly outage statistics for Telegram.
     * Example line: `20.11.2026 - 🔌не було 2 години`
     */
    fun formatWeeklyStatsForTelegram(
        events: List<PowerEvent>,
        nowMs: Long = System.currentTimeMillis(),
        ongoingOutageStartMs: Long? = null,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val dailyStats = calculateWeeklyOutageStats(
            events = events,
            nowMs = nowMs,
            ongoingOutageStartMs = ongoingOutageStartMs,
            daysCount = 7,
            timeZone = timeZone
        )

        if (dailyStats.isEmpty()) {
            return "📊 *Статистика відключень за тиждень:*\n\n✅ За останні 7 днів відключень не зафіксовано."
        }

        val sb = StringBuilder()
        sb.append("📊 *Статистика відключень за тиждень:*\n\n")
        for (stat in dailyStats) {
            val durText = formatOutageDurationUk(stat.outageMinutes)
            sb.append("${stat.dateLabel} - 🔌не було $durText\n")
        }
        return sb.toString().trimEnd()
    }

    fun formatOutageDurationUk(totalMinutes: Long): String {
        if (totalMinutes < 1L) return "менше 1 хв"
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L

        if (hours == 0L) {
            return "$mins хв"
        }
        if (mins == 0L) {
            val word = when {
                hours % 100 in 11L..14L -> "годин"
                hours % 10 == 1L -> "година"
                hours % 10 in 2L..4L -> "години"
                else -> "годин"
            }
            return "$hours $word"
        }
        return "$hours год $mins хв"
    }
}
