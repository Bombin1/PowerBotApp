package com.example.util

import com.example.data.model.CitySchedule
import com.example.data.model.OutageInterval
import com.example.data.model.OutageStatus

data class CombinedIntervalResult(
    val intervals: List<OutageInterval>,
    val affectedQueuesByIntervalKey: Map<String, List<String>>,
    val onlineHours: Int,
    val outageHours: Int,
    val maybeHours: Int,
    val activeQueueLabels: List<String>
)

object ScheduleCombiner {

    /**
     * Combines 1 or more selected [CitySchedule]s into a unified hourly interval list.
     * Priority per hour when merging multiple schedules: OUTAGE > MAYBE > ONLINE,
     * so all outage/maybe intervals from selected schedules sum into one combined schedule.
     */
    fun combineSchedules(schedules: List<CitySchedule>): CombinedIntervalResult {
        if (schedules.isEmpty()) {
            return CombinedIntervalResult(
                intervals = emptyList(),
                affectedQueuesByIntervalKey = emptyMap(),
                onlineHours = 0,
                outageHours = 0,
                maybeHours = 0,
                activeQueueLabels = emptyList()
            )
        }

        val distinctCities = schedules.map { it.cityName }.distinct().size
        val cleanQueueLabels = schedules.map { schedule ->
            val qLabel = cleanQueueLabel(schedule.queueName)
            if (distinctCities > 1) "${schedule.cityName} $qLabel" else qLabel
        }

        if (schedules.size == 1) {
            val single = schedules.first()
            val online = single.hours.count { it.status == OutageStatus.ONLINE }
            val outage = single.hours.count { it.status == OutageStatus.OUTAGE }
            val maybe = single.hours.count { it.status == OutageStatus.MAYBE }
            val label = cleanQueueLabels.first()
            val map = single.intervals
                .filter { it.status != OutageStatus.ONLINE }
                .associate { "${it.startHour}-${it.endHour}" to listOf(label) }
            return CombinedIntervalResult(
                intervals = single.intervals,
                affectedQueuesByIntervalKey = map,
                onlineHours = online,
                outageHours = outage,
                maybeHours = maybe,
                activeQueueLabels = cleanQueueLabels
            )
        }

        val combinedStatuses = mutableListOf<OutageStatus>()
        val hourAffectedQueues = mutableListOf<List<String>>()

        for (h in 0 until 24) {
            var hasOutage = false
            var hasMaybe = false
            val outageQueues = mutableListOf<String>()
            val maybeQueues = mutableListOf<String>()

            for (idx in schedules.indices) {
                val slot = schedules[idx].hours.getOrNull(h) ?: continue
                val label = cleanQueueLabels[idx]
                when (slot.status) {
                    OutageStatus.OUTAGE -> {
                        hasOutage = true
                        outageQueues.add(label)
                    }
                    OutageStatus.MAYBE -> {
                        hasMaybe = true
                        maybeQueues.add(label)
                    }
                    OutageStatus.ONLINE -> Unit
                }
            }

            val mergedStatus = when {
                hasOutage -> OutageStatus.OUTAGE
                hasMaybe -> OutageStatus.MAYBE
                else -> OutageStatus.ONLINE
            }
            combinedStatuses.add(mergedStatus)
            hourAffectedQueues.add(
                when (mergedStatus) {
                    OutageStatus.OUTAGE -> (outageQueues + maybeQueues).distinct()
                    OutageStatus.MAYBE -> maybeQueues.distinct()
                    OutageStatus.ONLINE -> emptyList()
                }
            )
        }

        val intervals = mutableListOf<OutageInterval>()
        val affectedMap = mutableMapOf<String, List<String>>()

        var currentStatus = combinedStatuses[0]
        var blockStartHour = 0
        val currentQueues = linkedSetOf<String>().apply { addAll(hourAffectedQueues[0]) }

        for (h in 1 until 24) {
            val st = combinedStatuses[h]
            if (st != currentStatus) {
                val startStr = String.format("%02d:00", blockStartHour)
                val endStr = String.format("%02d:00", h)
                intervals.add(
                    OutageInterval(
                        startHour = startStr,
                        endHour = endStr,
                        status = currentStatus,
                        description = statusDescription(currentStatus),
                        durationHours = h - blockStartHour
                    )
                )
                if (currentQueues.isNotEmpty()) {
                    affectedMap["$startStr-$endStr"] = currentQueues.toList()
                }
                currentStatus = st
                blockStartHour = h
                currentQueues.clear()
                currentQueues.addAll(hourAffectedQueues[h])
            } else {
                currentQueues.addAll(hourAffectedQueues[h])
            }
        }

        val lastStartStr = String.format("%02d:00", blockStartHour)
        val lastEndStr = "24:00"
        intervals.add(
            OutageInterval(
                startHour = lastStartStr,
                endHour = lastEndStr,
                status = currentStatus,
                description = statusDescription(currentStatus),
                durationHours = 24 - blockStartHour
            )
        )
        if (currentQueues.isNotEmpty()) {
            affectedMap["$lastStartStr-$lastEndStr"] = currentQueues.toList()
        }

        return CombinedIntervalResult(
            intervals = intervals,
            affectedQueuesByIntervalKey = affectedMap,
            onlineHours = combinedStatuses.count { it == OutageStatus.ONLINE },
            outageHours = combinedStatuses.count { it == OutageStatus.OUTAGE },
            maybeHours = combinedStatuses.count { it == OutageStatus.MAYBE },
            activeQueueLabels = cleanQueueLabels
        )
    }

    fun cleanQueueLabel(queueName: String): String {
        return queueName.replace("GPV", "", ignoreCase = true)
            .replace("черга", "", ignoreCase = true)
            .trim()
    }

    private fun statusDescription(status: OutageStatus): String = when (status) {
        OutageStatus.ONLINE -> "Світло є"
        OutageStatus.OUTAGE -> "Світла немає"
        OutageStatus.MAYBE -> "Можливе відключення"
    }
}
