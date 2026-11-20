package com.example.data.remote

import com.example.data.local.AppPreferences
import com.example.data.model.CitySchedule
import com.example.data.model.HourSlot
import com.example.data.model.OutageInterval
import com.example.data.model.OutageStatus
import com.example.data.model.ScheduleChangeResult
import com.example.data.model.ScheduleParseResult
import com.example.data.model.ScheduleSnapshot
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object ScheduleParser {

    private val KYIV_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Europe/Kyiv")

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

    /**
     * Normalizes timestamps: converts milliseconds (> 10_000_000_000L) to seconds.
     */
    fun normalizeTimestamp(timestamp: Long): Long {
        return if (timestamp > 10_000_000_000L) timestamp / 1000L else timestamp
    }

    /**
     * 1. getCurrentScheduleDate:
     * Converts the Unix timestamp (seconds or milliseconds) into a local date in Europe/Kyiv timezone (dd.MM.yyyy).
     * Example: 1792184400 -> "17.10.2026"
     */
    fun getCurrentScheduleDate(todayTimestamp: Long): String {
        val seconds = normalizeTimestamp(todayTimestamp)
        val cal = Calendar.getInstance(KYIV_TIME_ZONE).apply {
            timeInMillis = seconds * 1000L
        }
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply {
            timeZone = KYIV_TIME_ZONE
        }
        return sdf.format(cal.time)
    }

    /**
     * 2. getQueueSchedule:
     * Searches for queue in fact.data[today] or available dates (Variant A & B fallback).
     * Matches the exact queue specified by the user (e.g. GPV3.1).
     * Will NEVER return GPV3.2 or GPV4.1 if GPV3.1 was requested (Test 6).
     */
    fun getQueueSchedule(factData: JSONObject, todayTimestamp: Long, queueName: String): JSONObject? {
        val normToday = if (todayTimestamp > 0L) normalizeTimestamp(todayTimestamp) else 0L

        // 1. Variant A: Try today's date if provided
        if (normToday > 0L) {
            val todayKey = normToday.toString()
            var dayObj = factData.optJSONObject(todayKey)
            if (dayObj == null) {
                val keys = factData.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val kNorm = k.toLongOrNull()?.let { normalizeTimestamp(it) }
                    if (kNorm == normToday) {
                        dayObj = factData.optJSONObject(k)
                        break
                    }
                }
            }
            if (dayObj != null) {
                val q = findQueueInObject(dayObj, queueName)
                if (q != null) return q
            }
        }

        // 2. Variant B: Check other available dates in descending order (e.g. Uzhhorod)
        val availableDates = mutableListOf<Pair<Long, JSONObject>>()
        val keys = factData.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val kLong = k.toLongOrNull()
            if (kLong != null) {
                val obj = factData.optJSONObject(k)
                if (obj != null) {
                    availableDates.add(Pair(normalizeTimestamp(kLong), obj))
                }
            }
        }
        availableDates.sortByDescending { it.first }

        for ((_, dObj) in availableDates) {
            val q = findQueueInObject(dObj, queueName)
            if (q != null) return q
        }

        return null
    }

    /**
     * Matches queue name inside a JSONObject (exact, case-insensitive, or normalized).
     * Ensures strict sub-queue isolation (e.g. GPV3.1 will NOT match GPV3.2).
     */
    fun findQueueInObject(obj: JSONObject, queueName: String): JSONObject? {
        if (obj.has(queueName)) {
            val q = obj.optJSONObject(queueName)
            if (q != null) return q
        }

        // Case-insensitive direct match
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.equals(queueName, ignoreCase = true)) {
                val q = obj.optJSONObject(k)
                if (q != null) return q
            }
        }

        // Normalized matching ensuring exact sub-queue match (e.g. "3.1" matches "GPV3.1" or "черга 3.1")
        val cleanTarget = normalizeQueue(queueName)
        val keys2 = obj.keys()
        while (keys2.hasNext()) {
            val k = keys2.next()
            if (normalizeQueue(k) == cleanTarget) {
                val q = obj.optJSONObject(k)
                if (q != null) return q
            }
        }
        return null
    }

    fun normalizeQueue(q: String): String {
        return q.replace("GPV", "", ignoreCase = true)
            .replace("черга", "", ignoreCase = true)
            .replace("черги", "", ignoreCase = true)
            .replace("черг", "", ignoreCase = true)
            .replace("group", "", ignoreCase = true)
            .replace("queue", "", ignoreCase = true)
            .replace(" ", "")
            .trim()
    }

    /**
     * 3. getHourlyStatuses:
     * Reads 24 status values for keys "1" through "24" or "01" through "24".
     * Normalizes to "yes", "no", "maybe".
     * Returns null if any hour is missing or data is incomplete (Section 13).
     */
    fun getHourlyStatuses(queueObj: JSONObject): List<String>? {
        val result = ArrayList<String>(24)
        for (i in 1..24) {
            val key = i.toString()
            val raw = if (queueObj.has(key)) {
                queueObj.optString(key, "")
            } else {
                val formattedKey = String.format(Locale.US, "%02d", i)
                queueObj.optString(formattedKey, "")
            }

            if (raw.isBlank()) {
                return null // Incomplete: missing hour value
            }

            val normalized = when (raw.trim().lowercase()) {
                "yes", "1", "true", "on" -> "yes"
                "no", "0", "false", "off" -> "no"
                "maybe", "first", "second", "yellow" -> "maybe"
                else -> "maybe"
            }
            result.add(normalized)
        }
        return if (result.size == 24) result else null
    }

    /**
     * 4. getTimeInterval:
     * Obtains the real start and end times for hour index 1..24 from preset.time_zone.
     * Fallback: (i - 1):00 - i:00 (for 24: 23:00 - 24:00).
     */
    fun getTimeInterval(hourIndex: Int, presetTimeZone: JSONObject?): Pair<String, String> {
        val key = hourIndex.toString()
        if (presetTimeZone != null && presetTimeZone.has(key)) {
            val arr = presetTimeZone.optJSONArray(key)
            if (arr != null && arr.length() >= 3) {
                val s = arr.optString(1)
                val e = arr.optString(2)
                if (s.isNotBlank() && e.isNotBlank()) {
                    return Pair(s, e)
                }
            }
        }
        val start = String.format(Locale.US, "%02d:00", hourIndex - 1)
        val end = if (hourIndex == 24) "24:00" else String.format(Locale.US, "%02d:00", hourIndex)
        return Pair(start, end)
    }

    /**
     * 5. mergeScheduleIntervals:
     * Combines consecutive hour slots with identical statuses (Section 8).
     * Does NOT merge different statuses together.
     */
    fun mergeScheduleIntervals(hourlySlots: List<HourSlot>): List<OutageInterval> {
        if (hourlySlots.isEmpty()) return emptyList()
        val result = mutableListOf<OutageInterval>()

        var currentStatus = hourlySlots[0].status
        var currentDesc = hourlySlots[0].statusDescription
        var startHour = hourlySlots[0].timeLabel.substringBefore(" - ").substringBefore("–").trim()
        var count = 0

        for (i in hourlySlots.indices) {
            val slot = hourlySlots[i]
            if (slot.status != currentStatus) {
                val prevEnd = hourlySlots[i - 1].timeLabel.substringAfter(" - ").substringAfter("–").trim()
                result.add(OutageInterval(startHour, prevEnd, currentStatus, currentDesc, count))

                currentStatus = slot.status
                currentDesc = slot.statusDescription
                startHour = slot.timeLabel.substringBefore(" - ").substringBefore("–").trim()
                count = 1
            } else {
                count++
            }
        }

        // Final interval
        val lastEnd = hourlySlots.last().timeLabel.substringAfter(" - ").substringAfter("–").trim()
        result.add(OutageInterval(startHour, lastEnd, currentStatus, currentDesc, count))
        return result
    }

    data class ResolvedScheduleQueue(
        val queueObj: JSONObject,
        val resolvedTimestamp: Long,
        val resolvedDateStr: String
    )

    private fun findScheduleQueue(
        root: JSONObject,
        fact: JSONObject?,
        factData: JSONObject?,
        todayTimestamp: Long,
        queueName: String
    ): ResolvedScheduleQueue? {
        // --- Variant A: Search for today in fact.data ---
        if (factData != null && todayTimestamp > 0L) {
            val todayKeyStr = todayTimestamp.toString()
            var todayObj = factData.optJSONObject(todayKeyStr)
            if (todayObj == null) {
                val keys = factData.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val kNorm = k.toLongOrNull()?.let { normalizeTimestamp(it) }
                    if (kNorm == todayTimestamp) {
                        todayObj = factData.optJSONObject(k)
                        break
                    }
                }
            }

            if (todayObj != null) {
                val q = findQueueInObject(todayObj, queueName)
                if (q != null) {
                    return ResolvedScheduleQueue(q, todayTimestamp, getCurrentScheduleDate(todayTimestamp))
                }
            }
        }

        // --- Variant B: Fallback date in fact.data (e.g. Uzhhorod) ---
        if (factData != null && factData.length() > 0) {
            val availableDates = mutableListOf<Pair<Long, JSONObject>>()
            val keys = factData.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val kLong = k.toLongOrNull()
                if (kLong != null) {
                    val obj = factData.optJSONObject(k)
                    if (obj != null) {
                        availableDates.add(Pair(normalizeTimestamp(kLong), obj))
                    }
                }
            }
            availableDates.sortByDescending { it.first }

            for ((ts, dateObj) in availableDates) {
                val q = findQueueInObject(dateObj, queueName)
                if (q != null) {
                    return ResolvedScheduleQueue(q, ts, getCurrentScheduleDate(ts))
                }
            }
        }

        // --- Variant C: Root level queue ---
        val rootQueue = findQueueInObject(root, queueName)
        if (rootQueue != null) {
            val dateInfo = resolveDateAndTimestamp(root, fact, todayTimestamp)
            if (dateInfo != null) {
                return ResolvedScheduleQueue(rootQueue, dateInfo.second, dateInfo.first)
            }
        }

        return null
    }

    private fun resolveDateAndTimestamp(root: JSONObject, fact: JSONObject?, todayTimestamp: Long): Pair<String, Long>? {
        if (todayTimestamp > 0L) {
            val norm = normalizeTimestamp(todayTimestamp)
            return Pair(getCurrentScheduleDate(norm), norm)
        }

        val updateStr = fact?.optString("update", "")?.trim() ?: ""
        if (updateStr.isNotBlank()) {
            val dateMatch = Regex("\\b(\\d{2}\\.\\d{2}\\.\\d{4})\\b").find(updateStr)
            if (dateMatch != null) {
                val dStr = dateMatch.groupValues[1]
                val ts = parseKyivDateStringToTimestamp(dStr)
                return Pair(dStr, ts)
            }
        }

        val lastUpdated = root.optString("lastUpdated", "").trim()
        if (lastUpdated.isNotBlank()) {
            val parsed = parseIsoToKyivDate(lastUpdated)
            if (parsed != null) {
                return parsed
            }
        }

        return null
    }

    private fun parseKyivDateStringToTimestamp(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply {
                timeZone = KYIV_TIME_ZONE
            }
            val date = sdf.parse(dateStr)
            (date?.time ?: 0L) / 1000L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseIsoToKyivDate(isoString: String): Pair<String, Long>? {
        return try {
            val cleanIso = isoString.substringBefore(".").substringBefore("Z")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = sdf.parse(cleanIso) ?: return null
            val kyivSdf = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply {
                timeZone = KYIV_TIME_ZONE
            }
            val dateStr = kyivSdf.format(date)
            Pair(dateStr, date.time / 1000L)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 6. parseScheduleJson:
     * Parses the JSON strictly following the specification and returns either Success or Failure
     * with the exact error messages specified in Section 13.
     */
    fun parseScheduleJson(jsonString: String, cityName: String, queueName: String): ScheduleParseResult {
        if (jsonString.isBlank()) {
            return ScheduleParseResult.Failure("Не вдалося завантажити графік.")
        }

        val root: JSONObject = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return ScheduleParseResult.Failure("Не вдалося завантажити графік.")
        }

        val fact = root.optJSONObject("fact")

        val rawToday = when (val todayOpt = fact?.opt("today")) {
            is Number -> todayOpt.toLong()
            is String -> todayOpt.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        val todayTimestamp = if (rawToday > 0L) normalizeTimestamp(rawToday) else 0L

        val factData = fact?.optJSONObject("data")

        // Search for the queue according to Variants A, B, C
        val resolved = findScheduleQueue(root, fact, factData, todayTimestamp, queueName)

        if (resolved == null) {
            // Check if factData has dates, meaning dates exist but the queue was not in any of them
            if (factData != null && factData.length() > 0) {
                return ScheduleParseResult.Failure("Обрана черга відсутня у графіку.")
            }

            // Check if date cannot be defined anywhere
            val rootHasQueues = root.keys().asSequence().any { isQueueKey(it, root.opt(it)) }
            if (!rootHasQueues && todayTimestamp <= 0L && factData == null) {
                return ScheduleParseResult.Failure("Дата графіка не визначена.")
            }

            // Missing or empty schedule for today (e.g. empty array [] or empty object {})
            return ScheduleParseResult.Failure("Графік на поточну дату відсутній.")
        }

        val queueObj = resolved.queueObj
        val hourlyStatuses = getHourlyStatuses(queueObj)
            ?: return ScheduleParseResult.Failure("Графік неповний або некоректний.")

        val preset = root.optJSONObject("preset")
        val presetTimeZone = preset?.optJSONObject("time_zone") ?: root.optJSONObject("time_zone")
        val presetTimeType = preset?.optJSONObject("time_type") ?: root.optJSONObject("time_type")

        val scheduleDate = resolved.resolvedDateStr
        val factUpdate = fact?.optString("update", "")?.trim() ?: ""
        val lastUpdatedIso = root.optString("lastUpdated", "").trim()

        val hourlySlots = ArrayList<HourSlot>(24)
        for (i in 1..24) {
            val statusStr = hourlyStatuses[i - 1]
            val (sTime, eTime) = getTimeInterval(i, presetTimeZone)

            val status = when (statusStr) {
                "no" -> OutageStatus.OUTAGE
                "yes" -> OutageStatus.ONLINE
                else -> OutageStatus.MAYBE
            }

            val desc = when (statusStr) {
                "no" -> "Світла немає"
                "yes" -> "Світло є"
                else -> presetTimeType?.optString(statusStr, "Можливе відключення") ?: "Можливе відключення"
            }

            hourlySlots.add(HourSlot(i, "$sTime - $eTime", status, desc))
        }

        val mergedIntervals = mergeScheduleIntervals(hourlySlots)

        val schedule = CitySchedule(
            cityName = cityName,
            queueName = queueName,
            dateLabel = scheduleDate,
            hours = hourlySlots,
            intervals = mergedIntervals,
            todayTimestamp = resolved.resolvedTimestamp,
            factUpdate = factUpdate,
            lastUpdatedIso = lastUpdatedIso
        )

        val snapshot = ScheduleSnapshot(
            regionId = root.optString("regionId", cityName),
            scheduleDate = scheduleDate,
            todayTimestamp = resolved.resolvedTimestamp,
            queueName = queueName,
            hourlyStatuses = hourlyStatuses,
            factUpdate = factUpdate,
            lastUpdated = lastUpdatedIso
        )

        return ScheduleParseResult.Success(schedule, snapshot)
    }

    /**
     * Backward-compatible helper for UI and Widget.
     */
    fun parseSchedule(jsonString: String, cityName: String, queueName: String): CitySchedule? {
        return when (val res = parseScheduleJson(jsonString, cityName, queueName)) {
            is ScheduleParseResult.Success -> res.schedule
            is ScheduleParseResult.Failure -> null
        }
    }

    /**
     * 7. detectScheduleChanges:
     * Compares the previous snapshot and the new snapshot according to Sections 10, 11, 12:
     * - Key comparison: regionId + scheduleDate + queueName
     * - Statuses: compares the 24 statuses
     * - If only lastUpdated or fact.update changed, but date & statuses are identical -> isChanged = false.
     * - If scheduleDate changed -> isChanged = true, isDateChanged = true.
     * - If 24 statuses changed -> isChanged = true, isDateChanged = false.
     * - First launch (oldSnapshot == null) -> isChanged = false (initial state, no alert).
     */
    fun detectScheduleChanges(oldSnapshot: ScheduleSnapshot?, newSnapshot: ScheduleSnapshot): ScheduleChangeResult {
        if (oldSnapshot == null) {
            return ScheduleChangeResult(
                isChanged = false,
                isDateChanged = false,
                reason = "Початковий стан"
            )
        }

        // If different region or queue was selected, this is not an update of the monitored schedule
        if (oldSnapshot.regionId != newSnapshot.regionId || oldSnapshot.queueName != newSnapshot.queueName) {
            return ScheduleChangeResult(
                isChanged = false,
                isDateChanged = false,
                reason = "Зміна налаштувань регіону або черги"
            )
        }

        // New day detected
        if (oldSnapshot.scheduleDate != newSnapshot.scheduleDate || oldSnapshot.todayTimestamp != newSnapshot.todayTimestamp) {
            return ScheduleChangeResult(
                isChanged = true,
                isDateChanged = true,
                reason = "Зміна дати графіка: ${oldSnapshot.scheduleDate} ➔ ${newSnapshot.scheduleDate}"
            )
        }

        // Hourly status change
        if (oldSnapshot.hourlyStatuses != newSnapshot.hourlyStatuses) {
            return ScheduleChangeResult(
                isChanged = true,
                isDateChanged = false,
                reason = "Зміна статусів годин відключення"
            )
        }

        // Only metadata (lastUpdated, fact.update) changed, but 24 statuses and date are identical
        return ScheduleChangeResult(
            isChanged = false,
            isDateChanged = false,
            reason = "Графік не змінився"
        )
    }

    /**
     * 8. saveScheduleSnapshot:
     * Persists the snapshot in AppPreferences to survive app restarts.
     */
    fun saveScheduleSnapshot(preferences: AppPreferences, snapshot: ScheduleSnapshot) {
        preferences.lastScheduleSnapshot = snapshot
    }

    /**
     * Extracts all available queues from fact.data[today], other dates in fact.data, or root.
     */
    fun extractQueues(jsonString: String): List<String> {
        val result = mutableSetOf<String>()
        try {
            val root = JSONObject(jsonString)

            if (root.has("fact")) {
                val fact = root.optJSONObject("fact")
                if (fact != null) {
                    val factData = fact.optJSONObject("data")
                    val rawToday = when (val t = fact.opt("today")) {
                        is Number -> t.toLong()
                        is String -> t.trim().toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    val todayTs = if (rawToday > 0L) normalizeTimestamp(rawToday) else 0L

                    if (factData != null) {
                        // Check today object first
                        var todayObj = if (todayTs > 0L) factData.optJSONObject(todayTs.toString()) else null
                        if (todayObj == null && todayTs > 0L) {
                            val dKeys = factData.keys()
                            while (dKeys.hasNext()) {
                                val dk = dKeys.next()
                                val dkNorm = dk.toLongOrNull()?.let { normalizeTimestamp(it) }
                                if (dkNorm == todayTs) {
                                    todayObj = factData.optJSONObject(dk)
                                    break
                                }
                            }
                        }

                        if (todayObj != null) {
                            val qKeys = todayObj.keys()
                            while (qKeys.hasNext()) {
                                val q = qKeys.next()
                                if (isQueueKey(q, todayObj.opt(q))) {
                                    result.add(q)
                                }
                            }
                        }

                        // If today had no queues, check all available dates in fact.data
                        if (result.isEmpty()) {
                            val dateKeys = factData.keys()
                            while (dateKeys.hasNext()) {
                                val dKey = dateKeys.next()
                                val dateObj = factData.optJSONObject(dKey)
                                if (dateObj != null) {
                                    val qKeys = dateObj.keys()
                                    while (qKeys.hasNext()) {
                                        val q = qKeys.next()
                                        if (isQueueKey(q, dateObj.opt(q))) {
                                            result.add(q)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Fallback: check root level keys
            if (result.isEmpty()) {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (isQueueKey(k, root.opt(k))) {
                        result.add(k)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (result.isEmpty()) {
            return listOf("GPV1.1", "GPV1.2", "GPV2.1", "GPV2.2", "GPV3.1", "GPV3.2", "GPV4.1", "GPV4.2", "GPV5.1", "GPV5.2", "GPV6.1", "GPV6.2")
        }

        return result.sortedWith(Comparator { a, b ->
            compareQueues(a, b)
        })
    }

    private fun isQueueKey(k: String, value: Any?): Boolean {
        if (k.equals("data", true) || k.equals("today", true) || k.equals("update", true) ||
            k.equals("preset", true) || k.equals("regionId", true) || k.equals("lastUpdated", true) ||
            k.equals("time_zone", true) || k.equals("time_type", true) || k.equals("fact", true)) {
            return false
        }
        if (value is JSONObject) {
            if (value.has("1") || value.has("01") || value.has("12")) return true
        }
        val clean = k.trim()
        return clean.contains("GPV", ignoreCase = true) ||
               clean.contains("черг", ignoreCase = true) ||
               clean.matches(Regex("^(?:GPV)?\\s*\\d+(?:\\.\\d+)?$", RegexOption.IGNORE_CASE))
    }

    private fun compareQueues(a: String, b: String): Int {
        val regex = Regex("(\\d+)(?:\\.(\\d+))?")
        val matchA = regex.find(a)
        val matchB = regex.find(b)
        if (matchA != null && matchB != null) {
            val majorA = matchA.groupValues[1].toIntOrNull() ?: 0
            val majorB = matchB.groupValues[1].toIntOrNull() ?: 0
            if (majorA != majorB) return majorA.compareTo(majorB)

            val minorA = matchA.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val minorB = matchB.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (minorA != minorB) return minorA.compareTo(minorB)
        }
        return a.compareTo(b, ignoreCase = true)
    }

    fun formatScheduleText(schedule: CitySchedule): String {
        val sb = StringBuilder()
        val cleanQueue = schedule.queueName.replace("GPV", "").replace("черга", "").trim()
        sb.append("📅 Графік: ${schedule.cityName} (Черга $cleanQueue)\n")
        sb.append("Дата: ${schedule.dateLabel}\n")
        if (schedule.factUpdate.isNotBlank()) {
            sb.append("Графік оновлено: ${schedule.factUpdate}\n")
        }
        sb.append("\n")

        for (interval in schedule.intervals) {
            val icon = when (interval.status) {
                OutageStatus.ONLINE -> "🟢"
                OutageStatus.OUTAGE -> "🔴"
                OutageStatus.MAYBE -> "🟡"
            }
            sb.append("$icon **${interval.startHour}–${interval.endHour}** — ${interval.description} (${interval.durationHours} год)\n")
        }
        return sb.toString()
    }
}
