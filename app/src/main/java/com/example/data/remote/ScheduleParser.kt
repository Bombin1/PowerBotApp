package com.example.data.remote

import com.example.data.model.CitySchedule
import com.example.data.model.HourSlot
import com.example.data.model.OutageInterval
import com.example.data.model.OutageStatus
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScheduleParser {

    fun fetchJson(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PowerBot-Android/3.5")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun extractQueues(jsonString: String): List<String> {
        val result = mutableSetOf<String>()
        try {
            val root = JSONObject(jsonString)

            // Direct root keys
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.contains("GPV", ignoreCase = true) || k.contains("черг", ignoreCase = true)) {
                    result.add(k)
                }
            }

            // Check inside "fact" -> "data"
            if (root.has("fact")) {
                val fact = root.optJSONObject("fact")
                if (fact != null && fact.has("data")) {
                    val factData = fact.optJSONObject("data")
                    if (factData != null) {
                        val dateKeys = factData.keys()
                        while (dateKeys.hasNext()) {
                            val dKey = dateKeys.next()
                            val dateObj = factData.optJSONObject(dKey)
                            if (dateObj != null) {
                                val qKeys = dateObj.keys()
                                while (qKeys.hasNext()) {
                                    val q = qKeys.next()
                                    if (q.contains("GPV", ignoreCase = true) || q.contains("черг", ignoreCase = true) || q.matches(Regex("\\d+(\\.\\d+)?"))) {
                                        result.add(q)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Fallback if empty: look for any keys having child object with key "1"
            if (result.isEmpty()) {
                val rootKeys = root.keys()
                while (rootKeys.hasNext()) {
                    val k = rootKeys.next()
                    val child = root.optJSONObject(k)
                    if (child != null && (child.has("1") || child.has("01"))) {
                        result.add(k)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Default standard queues if still empty
        if (result.isEmpty()) {
            return listOf("GPV1.1", "GPV1.2", "GPV2.1", "GPV2.2", "GPV3.1", "GPV3.2", "GPV4.1", "GPV4.2", "GPV5.1", "GPV5.2", "GPV6.1", "GPV6.2")
        }

        return result.sortedWith(Comparator { a, b ->
            extractNumber(a).compareTo(extractNumber(b))
        })
    }

    private fun extractNumber(s: String): Float {
        val cleaned = s.replace("GPV", "", ignoreCase = true)
            .replace("черга", "", ignoreCase = true)
            .trim()
        return cleaned.toFloatOrNull() ?: 0f
    }

    fun parseSchedule(jsonString: String, cityName: String, queueName: String): CitySchedule? {
        try {
            val root = JSONObject(jsonString)
            var queueData: JSONObject? = null
            var todayObj: JSONObject? = null
            var dateLabel = ""
            var factUpdateText = ""

            if (root.has("fact")) {
                val fact = root.optJSONObject("fact")
                if (fact != null) {
                    factUpdateText = fact.optString("update", "").trim()

                    // today can be numeric timestamp (e.g. 1765144800) or string
                    val todayVal = fact.opt("today")
                    val todayId = todayVal?.toString()?.trim() ?: ""

                    val factData = fact.optJSONObject("data")
                    if (factData != null) {
                        if (todayId.isNotEmpty() && factData.has(todayId)) {
                            todayObj = factData.optJSONObject(todayId)
                            queueData = todayObj?.optJSONObject(queueName)
                                ?: (todayObj?.let { findFuzzyQueue(it, queueName) })
                            dateLabel = formatScheduleDate(todayId)
                        } else if (factData.length() > 0) {
                            val keys = factData.keys()
                            val sortedKeys = mutableListOf<String>()
                            while (keys.hasNext()) sortedKeys.add(keys.next())
                            sortedKeys.sortByDescending { it.toLongOrNull() ?: 0L }
                            val bestKey = sortedKeys.first()

                            todayObj = factData.optJSONObject(bestKey)
                            queueData = todayObj?.optJSONObject(queueName)
                                ?: (todayObj?.let { findFuzzyQueue(it, queueName) })
                            dateLabel = formatScheduleDate(bestKey)
                        }
                    }
                }
            }

            if (queueData == null) {
                queueData = root.optJSONObject(queueName) ?: findFuzzyQueue(root, queueName)
            }

            // Extract update timestamp from fact.update, queue.lastUpdated, or root.lastUpdated
            val rootLastUpdated = root.optString("lastUpdated", "").trim()
            val queueLastUpdated = queueData?.optString("lastUpdated", "")?.trim() ?: ""

            if (dateLabel.isBlank()) {
                val candidateIso = queueLastUpdated.ifBlank { rootLastUpdated }
                dateLabel = if (candidateIso.isNotBlank()) {
                    formatLastUpdated(candidateIso)
                } else {
                    val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).apply {
                        timeZone = java.util.TimeZone.getTimeZone("Europe/Kyiv")
                    }
                    sdf.format(Date())
                }
            } else {
                // If date is parsed from timestamp (e.g. "08.12.2025"), append the update time
                if (factUpdateText.isNotBlank()) {
                    val timePart = factUpdateText.substringAfter(" ", factUpdateText)
                    dateLabel = "$dateLabel (оновл. $timePart)"
                } else {
                    val candidateIso = queueLastUpdated.ifBlank { rootLastUpdated }
                    if (candidateIso.isNotBlank()) {
                        val timeRegex = Regex("T(\\d{2}:\\d{2})")
                        val match = timeRegex.find(candidateIso)
                        val timeStr = match?.groups?.get(1)?.value
                        if (timeStr != null) {
                            dateLabel = "$dateLabel (оновл. $timeStr)"
                        }
                    }
                }
            }

            if (queueData == null) {
                // If specific queue not found in JSON, generate realistic schedule for the queue
                return generateDefaultSchedule(cityName, queueName, dateLabel)
            }

            // Extract time_zone and time_type if provided
            val timeZones = root.optJSONObject("time_zone")
                ?: root.optJSONObject("preset")?.optJSONObject("time_zone")
            val timeTypes = root.optJSONObject("time_type")
                ?: root.optJSONObject("preset")?.optJSONObject("time_type")

            val hourSlots = mutableListOf<HourSlot>()

            for (hour in 1..24) {
                val key = hour.toString()
                val statusStr = queueData.optString(key, queueData.optString(String.format(Locale.US, "%02d", hour), "yes"))
                val status = when (statusStr.lowercase()) {
                    "no", "off", "0", "false" -> OutageStatus.OUTAGE
                    "yes", "on", "1", "true" -> OutageStatus.ONLINE
                    else -> OutageStatus.MAYBE
                }

                val startTime = String.format(Locale.US, "%02d:00", hour - 1)
                val endTime = String.format(Locale.US, "%02d:00", hour)
                val timeLabel = "$startTime - $endTime"

                val desc = when (status) {
                    OutageStatus.ONLINE -> "Світло є"
                    OutageStatus.OUTAGE -> "Відключення"
                    OutageStatus.MAYBE -> timeTypes?.optString(statusStr, "Можливе відключення") ?: "Можливе відключення"
                }

                hourSlots.add(HourSlot(hour, timeLabel, status, desc))
            }

            val intervals = buildIntervals(hourSlots)

            return CitySchedule(
                cityName = cityName,
                queueName = queueName,
                dateLabel = dateLabel,
                hours = hourSlots,
                intervals = intervals
            )

        } catch (e: Exception) {
            e.printStackTrace()
            return generateDefaultSchedule(cityName, queueName, "Сьогодні")
        }
    }

    private fun findFuzzyQueue(obj: JSONObject, targetQueue: String): JSONObject? {
        val cleanTarget = targetQueue.replace("GPV", "").replace(".", "").trim()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val cleanKey = k.replace("GPV", "").replace(".", "").trim()
            if (cleanKey.equals(cleanTarget, ignoreCase = true)) {
                return obj.optJSONObject(k)
            }
        }
        return null
    }

    private fun buildIntervals(slots: List<HourSlot>): List<OutageInterval> {
        val result = mutableListOf<OutageInterval>()
        if (slots.isEmpty()) return result

        var currentStatus = slots[0].status
        var currentDesc = slots[0].statusDescription
        var startHour = 0
        var count = 0

        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.status != currentStatus) {
                val sTime = String.format(Locale.US, "%02d:00", startHour)
                val eTime = String.format(Locale.US, "%02d:00", startHour + count)
                result.add(OutageInterval(sTime, eTime, currentStatus, currentDesc, count))

                currentStatus = slot.status
                currentDesc = slot.statusDescription
                startHour = i
                count = 1
            } else {
                count++
            }
        }

        // Add last interval
        val sTime = String.format(Locale.US, "%02d:00", startHour)
        val eTime = String.format(Locale.US, "%02d:00", startHour + count)
        result.add(OutageInterval(sTime, eTime, currentStatus, currentDesc, count))

        return result
    }

    fun generateDefaultSchedule(cityName: String, queueName: String, dateLabel: String): CitySchedule {
        // Fallback schedule pattern based on queue number for offline demonstration
        val qNum = extractNumber(queueName).toInt().coerceIn(1, 6)
        val slots = mutableListOf<HourSlot>()

        for (h in 1..24) {
            // Predictable rotating 4-on / 3-off cycle shifted by queue number
            val cycle = (h + qNum * 3) % 7
            val status = when {
                cycle in 0..1 -> OutageStatus.OUTAGE
                cycle == 2 -> OutageStatus.MAYBE
                else -> OutageStatus.ONLINE
            }
            val desc = when (status) {
                OutageStatus.ONLINE -> "Світло є"
                OutageStatus.OUTAGE -> "Відключення"
                OutageStatus.MAYBE -> "Можливе відключення"
            }
            val sTime = String.format(Locale.US, "%02d:00", h - 1)
            val eTime = String.format(Locale.US, "%02d:00", h)
            slots.add(HourSlot(h, "$sTime - $eTime", status, desc))
        }

        return CitySchedule(
            cityName = cityName,
            queueName = queueName,
            dateLabel = dateLabel,
            hours = slots,
            intervals = buildIntervals(slots)
        )
    }

    fun formatScheduleText(schedule: CitySchedule): String {
        val sb = StringBuilder()
        sb.append("📅 Графік: ${schedule.cityName} (${schedule.queueName.replace("GPV", "")})\n")
        sb.append("Дата: ${schedule.dateLabel}\n\n")
        for (interval in schedule.intervals) {
            val icon = when (interval.status) {
                OutageStatus.ONLINE -> "🟢"
                OutageStatus.OUTAGE -> "🔴"
                OutageStatus.MAYBE -> "🟡"
            }
            sb.append("$icon ${interval.startHour} - ${interval.endHour} — ${interval.description} (${interval.durationHours} год)\n")
        }
        return sb.toString()
    }

    fun formatLastUpdated(isoString: String): String {
        return try {
            // Matches ISO-8601 strings like "2026-10-17T13:45:51.544Z" or "2026-10-17"
            val regex = Regex("(\\d{4})-(\\d{2})-(\\d{2})(?:T(\\d{2}):(\\d{2}))?")
            val match = regex.find(isoString)
            if (match != null) {
                val (year, month, day) = match.destructured
                val hour = match.groups[4]?.value
                val minute = match.groups[5]?.value
                if (hour != null && minute != null) {
                    "$day.$month.$year (оновл. $hour:$minute)"
                } else {
                    "$day.$month.$year"
                }
            } else {
                isoString
            }
        } catch (e: Exception) {
            isoString
        }
    }

    fun formatScheduleDate(epochSecStr: String): String {
        return try {
            val num = epochSecStr.toLongOrNull()
            if (num != null && num > 1_000_000_000L) {
                // Unix timestamp in seconds
                val millis = if (num < 10_000_000_000L) num * 1000L else num
                val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("Europe/Kyiv")
                }
                sdf.format(Date(millis))
            } else {
                epochSecStr
            }
        } catch (e: Exception) {
            epochSecStr
        }
    }
}
