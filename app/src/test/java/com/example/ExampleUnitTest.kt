package com.example

import com.example.data.model.CitySchedule
import com.example.data.model.HourSlot
import com.example.data.model.OutageInterval
import com.example.data.model.OutageStatus
import com.example.data.model.PowerEvent
import com.example.data.model.ScheduleParseResult
import com.example.data.model.ScheduleSnapshot
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import com.example.util.GitHubUpdater
import com.example.util.MonthKey
import com.example.util.PowerStatsHelper
import com.example.util.ScheduleCombiner
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
class ExampleUnitTest {

    @Test
    fun testCitySourcesCount() {
        assertEquals(16, OutageSources.CITIES.size)
        assertNotNull(OutageSources.getCitySource("Київ"))
        assertNotNull(OutageSources.getCitySource("Хмельницький"))
        assertNotNull(OutageSources.getCitySource("Житомир"))
    }

    /**
     * ТЗ Критерій 1: Дата
     * Вхід: fact.today = 1792184400
     * Очікування: 17.10.2026
     */
    @Test
    fun test1_ScheduleDateFromTimestamp() {
        val todayTimestamp = 1792184400L
        val formattedDate = ScheduleParser.getCurrentScheduleDate(todayTimestamp)
        assertEquals("17.10.2026", formattedDate)
    }

    /**
     * ТЗ Критерій 2: Черга GPV3.1
     * Очікування:
     * 10:00–13:00 — Світла немає
     * 19:00–22:00 — Світла немає
     */
    @Test
    fun test2_QueueGPV31_Intervals() {
        val json = """
        {
          "regionId": "Khmelnytskoblenerho",
          "lastUpdated": "2026-10-17T13:45:51.544Z",
          "fact": {
            "data": {
              "1792184400": {
                "GPV3.1": {
                  "1": "yes",  "2": "yes",  "3": "yes",  "4": "yes",  "5": "yes",
                  "6": "yes",  "7": "yes",  "8": "yes",  "9": "yes",  "10": "yes",
                  "11": "no",  "12": "no",  "13": "no",
                  "14": "yes", "15": "yes", "16": "yes", "17": "yes", "18": "yes", "19": "yes",
                  "20": "no",  "21": "no",  "22": "no",
                  "23": "yes", "24": "yes"
                }
              }
            },
            "update": "17.10.2026 16:45",
            "today": 1792184400
          },
          "preset": {
            "time_type": {
              "yes": "Світло є",
              "maybe": "Можливе відключення",
              "no": "Світла немає"
            }
          }
        }
        """.trimIndent()

        val parseResult = ScheduleParser.parseScheduleJson(json, "Хмельницький", "GPV3.1")
        assertTrue("Parsing must succeed", parseResult is ScheduleParseResult.Success)

        val schedule = (parseResult as ScheduleParseResult.Success).schedule
        assertEquals("17.10.2026", schedule.dateLabel)
        assertEquals("17.10.2026 16:45", schedule.factUpdate)

        // Find outage intervals
        val outageIntervals = schedule.intervals.filter { it.status == OutageStatus.OUTAGE }
        assertEquals(2, outageIntervals.size)

        // 1st outage: 10:00 - 13:00
        assertEquals("10:00", outageIntervals[0].startHour)
        assertEquals("13:00", outageIntervals[0].endHour)
        assertEquals("Світла немає", outageIntervals[0].description)
        assertEquals(3, outageIntervals[0].durationHours)

        // 2nd outage: 19:00 - 22:00
        assertEquals("19:00", outageIntervals[1].startHour)
        assertEquals("22:00", outageIntervals[1].endHour)
        assertEquals("Світла немає", outageIntervals[1].description)
        assertEquals(3, outageIntervals[1].durationHours)

        // Total 5 intervals
        assertEquals(5, schedule.intervals.size)
        assertEquals("00:00", schedule.intervals[0].startHour)
        assertEquals("10:00", schedule.intervals[0].endHour)
        assertEquals(OutageStatus.ONLINE, schedule.intervals[0].status)
    }

    /**
     * ТЗ Критерій 3: Оновився тільки lastUpdated
     * Старий: lastUpdated = 2026-10-17T13:45:51.544Z
     * Новий: lastUpdated = 2026-10-17T14:45:51.544Z
     * Якщо fact.today та всі 24 статуси однакові:
     * Графік не змінився.
     */
    @Test
    fun test3_OnlyLastUpdatedChanged_NoScheduleChange() {
        val statuses = List(24) { "yes" }
        val oldSnapshot = ScheduleSnapshot(
            regionId = "Khmelnytskoblenerho",
            scheduleDate = "17.10.2026",
            todayTimestamp = 1792184400L,
            queueName = "GPV3.1",
            hourlyStatuses = statuses,
            factUpdate = "17.10.2026 13:45",
            lastUpdated = "2026-10-17T13:45:51.544Z"
        )

        val newSnapshot = ScheduleSnapshot(
            regionId = "Khmelnytskoblenerho",
            scheduleDate = "17.10.2026",
            todayTimestamp = 1792184400L,
            queueName = "GPV3.1",
            hourlyStatuses = statuses,
            factUpdate = "17.10.2026 14:45",
            lastUpdated = "2026-10-17T14:45:51.544Z"
        )

        val changeResult = ScheduleParser.detectScheduleChanges(oldSnapshot, newSnapshot)
        assertFalse("Schedule must NOT be detected as changed if only timestamps changed", changeResult.isChanged)
        assertFalse(changeResult.isDateChanged)
        assertEquals("Графік не змінився", changeResult.reason)
    }

    /**
     * ТЗ Критерій 4: Змінився статус години
     * Старий: 11 = no
     * Новий: 11 = yes
     * Очікування: Графік змінився.
     */
    @Test
    fun test4_HourStatusChanged_ScheduleChanged() {
        val oldStatuses = MutableList(24) { "yes" }
        oldStatuses[10] = "no" // 11th hour (0-indexed 10)

        val newStatuses = MutableList(24) { "yes" } // 11th hour changed to yes

        val oldSnapshot = ScheduleSnapshot(
            regionId = "Khmelnytskoblenerho",
            scheduleDate = "17.10.2026",
            todayTimestamp = 1792184400L,
            queueName = "GPV3.1",
            hourlyStatuses = oldStatuses
        )

        val newSnapshot = ScheduleSnapshot(
            regionId = "Khmelnytskoblenerho",
            scheduleDate = "17.10.2026",
            todayTimestamp = 1792184400L,
            queueName = "GPV3.1",
            hourlyStatuses = newStatuses
        )

        val changeResult = ScheduleParser.detectScheduleChanges(oldSnapshot, newSnapshot)
        assertTrue("Schedule MUST be detected as changed", changeResult.isChanged)
        assertFalse("Date itself did not change", changeResult.isDateChanged)
    }

    /**
     * ТЗ Критерій 5: Змінився fact.today
     * Старий: fact.today = 1792184400 (17.10.2026)
     * Новий: fact.today = 1792270800 (18.10.2026)
     * Очікування: Змінилася дата графіка.
     */
    @Test
    fun test5_TodayTimestampChanged_DateChanged() {
        val statuses = List(24) { "yes" }
        val oldSnapshot = ScheduleSnapshot(
            regionId = "Khmelnytskoblenerho",
            scheduleDate = "17.10.2026",
            todayTimestamp = 1792184400L,
            queueName = "GPV3.1",
            hourlyStatuses = statuses
        )

        val newSnapshot = ScheduleSnapshot(
            regionId = "Khmelnytskoblenerho",
            scheduleDate = "18.10.2026",
            todayTimestamp = 1792270800L,
            queueName = "GPV3.1",
            hourlyStatuses = statuses
        )

        val changeResult = ScheduleParser.detectScheduleChanges(oldSnapshot, newSnapshot)
        assertTrue("Schedule MUST be flagged as changed", changeResult.isChanged)
        assertTrue("Date change MUST be flagged", changeResult.isDateChanged)
    }

    /**
     * ТЗ Критерій 6: Інша черга
     * Якщо користувач вибрав GPV3.1, не можна показувати дані GPV3.2 або GPV4.1.
     * Черга має визначатися виключно за вибором користувача.
     */
    @Test
    fun test6_OtherQueue_StrictQueueMatching() {
        val json = """
        {
          "regionId": "Khmelnytskoblenerho",
          "fact": {
            "today": 1792184400,
            "data": {
              "1792184400": {
                "GPV3.2": {
                  "1": "no", "2": "no", "3": "no", "4": "no", "5": "no", "6": "no",
                  "7": "no", "8": "no", "9": "no", "10": "no", "11": "no", "12": "no",
                  "13": "no", "14": "no", "15": "no", "16": "no", "17": "no", "18": "no",
                  "19": "no", "20": "no", "21": "no", "22": "no", "23": "no", "24": "no"
                }
              }
            }
          }
        }
        """.trimIndent()

        // User asks for GPV3.1, but only GPV3.2 is present
        val result = ScheduleParser.parseScheduleJson(json, "Хмельницький", "GPV3.1")
        assertTrue("Must fail when chosen queue is missing", result is ScheduleParseResult.Failure)
        assertEquals("Обрана черга відсутня у графіку.", (result as ScheduleParseResult.Failure).errorMessage)
    }

    /**
     * ТЗ Розділ 13: Обробка помилок
     */
    @Test
    fun testErrorHandling_MissingFactToday() {
        val json = """{"fact": {}}"""
        val result = ScheduleParser.parseScheduleJson(json, "Хмельницький", "GPV3.1")
        assertTrue(result is ScheduleParseResult.Failure)
        assertEquals("Дата графіка не визначена.", (result as ScheduleParseResult.Failure).errorMessage)
    }

    @Test
    fun testErrorHandling_MissingFactDataForToday() {
        val json = """{"fact": {"today": 1792184400, "data": {}}}"""
        val result = ScheduleParser.parseScheduleJson(json, "Хмельницький", "GPV3.1")
        assertTrue(result is ScheduleParseResult.Failure)
        assertEquals("Графік на поточну дату відсутній.", (result as ScheduleParseResult.Failure).errorMessage)
    }

    @Test
    fun testErrorHandling_IncompleteStatuses() {
        val json = """
        {
          "fact": {
            "today": 1792184400,
            "data": {
              "1792184400": {
                "GPV3.1": {
                  "1": "yes", "2": "yes"
                }
              }
            }
          }
        }
        """.trimIndent()
        val result = ScheduleParser.parseScheduleJson(json, "Хмельницький", "GPV3.1")
        assertTrue(result is ScheduleParseResult.Failure)
        assertEquals("Графік неповний або некоректний.", (result as ScheduleParseResult.Failure).errorMessage)
    }

    /**
     * Сценарій: Ужгород (Zakarpattiaoblenerho)
     * fact.today = 1792454400 (20.10.2026)
     * fact.data містить ключ "1792195200" (17.10.2026)
     * Очікування: графік зчитується з "1792195200", дата показується "17.10.2026".
     */
    @Test
    fun testUzhhorod_FallbackDate() {
        val json = """
        {
          "regionId": "Uzhhorod",
          "lastUpdated": "2026-10-19T21:02:40.938Z",
          "fact": {
            "data": {
              "1792195200": {
                "GPV1.1": {
                  "1": "yes", "2": "yes", "3": "yes", "4": "yes", "5": "yes", "6": "yes",
                  "7": "yes", "8": "yes", "9": "yes", "10": "yes", "11": "no", "12": "no",
                  "13": "no", "14": "yes", "15": "yes", "16": "yes", "17": "yes", "18": "yes",
                  "19": "yes", "20": "no", "21": "no", "22": "no", "23": "yes", "24": "yes"
                }
              }
            },
            "update": "20.10.2026 00:02",
            "today": 1792454400
          }
        }
        """.trimIndent()

        val result = ScheduleParser.parseScheduleJson(json, "Ужгород", "GPV1.1")
        assertTrue("Parsing Uzhhorod with fallback date must succeed", result is ScheduleParseResult.Success)
        val schedule = (result as ScheduleParseResult.Success).schedule
        assertEquals("17.10.2026", schedule.dateLabel)
        assertEquals(1792195200L, schedule.todayTimestamp)
        assertEquals(24, schedule.hours.size)
    }

    /**
     * Сценарій: Київ (kyiv.json) зі статусами "first", "second" (підгрупи)
     */
    @Test
    fun testKyiv_FirstSecondStatuses() {
        val json = """
        {
          "regionId": "kyiv",
          "lastUpdated": "2026-11-04T16:06:07.732Z",
          "fact": {
            "today": 1793743200,
            "update": "04.11.2026 13:41",
            "data": {
              "1793743200": {
                "GPV1.1": {
                  "1": "yes", "2": "yes", "3": "first", "4": "second", "5": "no", "6": "yes",
                  "7": "yes", "8": "yes", "9": "yes", "10": "yes", "11": "yes", "12": "yes",
                  "13": "yes", "14": "yes", "15": "yes", "16": "yes", "17": "yes", "18": "yes",
                  "19": "yes", "20": "yes", "21": "yes", "22": "yes", "23": "yes", "24": "yes"
                }
              }
            }
          }
        }
        """.trimIndent()

        val result = ScheduleParser.parseScheduleJson(json, "Київ", "GPV1.1")
        assertTrue("Parsing Kyiv must succeed", result is ScheduleParseResult.Success)
        val schedule = (result as ScheduleParseResult.Success).schedule
        assertEquals("04.11.2026", schedule.dateLabel)
        assertEquals(OutageStatus.MAYBE, schedule.hours[2].status) // "first" -> maybe
        assertEquals(OutageStatus.MAYBE, schedule.hours[3].status) // "second" -> maybe
        assertEquals(OutageStatus.OUTAGE, schedule.hours[4].status) // "no" -> outage
    }

    /**
     * Сценарій: Київ коли відключень немає (fact.data = [])
     */
    @Test
    fun testKyiv_EmptyDataArray() {
        val json = """
        {
          "regionId": "kyiv",
          "lastUpdated": "2026-11-04T16:06:07.732Z",
          "fact": {
            "today": 1793743200,
            "update": "04.11.2026 13:41",
            "data": []
          }
        }
        """.trimIndent()

        val result = ScheduleParser.parseScheduleJson(json, "Київ", "GPV1.1")
        assertTrue("Empty fact.data array should fail gracefully", result is ScheduleParseResult.Failure)
        assertEquals("Графік на поточну дату відсутній.", (result as ScheduleParseResult.Failure).errorMessage)
    }

    /**
     * Сценарій: Варіант C (черга на рівні root)
     */
    @Test
    fun testVariantC_RootLevelQueue() {
        val json = """
        {
          "regionId": "SomeRegion",
          "lastUpdated": "2026-10-17T13:45:51.544Z",
          "GPV2.1": {
            "1": "yes", "2": "yes", "3": "yes", "4": "yes", "5": "yes", "6": "yes",
            "7": "yes", "8": "yes", "9": "yes", "10": "yes", "11": "no", "12": "no",
            "13": "yes", "14": "yes", "15": "yes", "16": "yes", "17": "yes", "18": "yes",
            "19": "yes", "20": "yes", "21": "yes", "22": "yes", "23": "yes", "24": "yes"
          }
        }
        """.trimIndent()

        val result = ScheduleParser.parseScheduleJson(json, "Місто", "GPV2.1")
        assertTrue("Parsing root level queue must succeed", result is ScheduleParseResult.Success)
        val schedule = (result as ScheduleParseResult.Success).schedule
        assertEquals("17.10.2026", schedule.dateLabel)
    }

    /**
     * Сценарій: Нормалізація timestamp у мілісекундах (> 10_000_000_000L)
     */
    @Test
    fun testTimestampNormalization() {
        val msTimestamp = 1792184400000L
        val normalized = ScheduleParser.normalizeTimestamp(msTimestamp)
        assertEquals(1792184400L, normalized)

        val secTimestamp = 1792184400L
        assertEquals(1792184400L, ScheduleParser.normalizeTimestamp(secTimestamp))

        assertEquals("17.10.2026", ScheduleParser.getCurrentScheduleDate(msTimestamp))
    }

    /**
     * Сценарій: Отримання команд з Telegram-каналу (channel_post)
     */
    @Test
    fun testTelegramChannelPostParsing() {
        val jsonString = """
        {
          "ok": true,
          "result": [
            {
              "update_id": 10001,
              "channel_post": {
                "message_id": 501,
                "chat": {
                  "id": -1001234567890,
                  "title": "Power Channel",
                  "type": "channel"
                },
                "text": "/status@PowerBot"
              }
            }
          ]
        }
        """.trimIndent()

        val json = org.json.JSONObject(jsonString)
        val resultArr = json.optJSONArray("result")
        assertNotNull(resultArr)
        val item = resultArr!!.optJSONObject(0)
        assertNotNull(item)

        val channelPost = item!!.optJSONObject("channel_post")
        assertNotNull(channelPost)
        assertEquals(-1001234567890L, channelPost!!.optJSONObject("chat")!!.optLong("id"))
        assertEquals("/status@PowerBot", channelPost.optString("text"))
    }

    /**
     * Сценарій: Отримання натискань кнопок адмін-панелі (callback_query)
     */
    @Test
    fun testTelegramCallbackQueryParsing() {
        val jsonString = """
        {
          "ok": true,
          "result": [
            {
              "update_id": 10002,
              "callback_query": {
                "id": "query_999",
                "from": {
                  "id": 123456789,
                  "username": "admin_user"
                },
                "message": {
                  "message_id": 502,
                  "chat": {
                    "id": 123456789,
                    "type": "private"
                  }
                },
                "data": "set_graph"
              }
            }
          ]
        }
        """.trimIndent()

        val json = org.json.JSONObject(jsonString)
        val resultArr = json.optJSONArray("result")
        assertNotNull(resultArr)
        val item = resultArr!!.optJSONObject(0)
        assertNotNull(item)

        val cb = item!!.optJSONObject("callback_query")
        assertNotNull(cb)
        assertEquals("set_graph", cb!!.optString("data"))
        assertEquals("query_999", cb.optString("id"))
    }

    /**
     * v3.5.2 Критерій 2: В повідомленні "Графік оновлено" відсутня строка "Дата:"
     */
    @Test
    fun testV352_FormatScheduleText_NoDateLine() {
        val schedule = CitySchedule(
            cityName = "Хмельницький",
            queueName = "GPV3.1",
            dateLabel = "20.11.2026",
            hours = emptyList(),
            intervals = listOf(
                OutageInterval("00:00", "10:00", OutageStatus.ONLINE, "Світло є", 10),
                OutageInterval("10:00", "12:00", OutageStatus.OUTAGE, "Світла немає", 2)
            ),
            factUpdate = "20.11.2026 08:30"
        )

        val formatted = ScheduleParser.formatScheduleText(schedule)
        assertFalse("Formatted schedule must NOT contain 'Дата:' line", formatted.contains("Дата:"))
        assertTrue(formatted.contains("📅 Графік: Хмельницький (Черга 3.1)"))
        assertTrue(formatted.contains("Графік оновлено: 20.11.2026 08:30"))
        assertTrue(formatted.contains("🔴 **10:00–12:00** — Світла немає (2 год)"))
    }

    /**
     * v3.5.2 Критерій 3: Журнал зберігає події за попередні місяці при переході на новий місяць
     */
    @Test
    fun testV352_MonthlyJournalPreservesPreviousMonths() {
        val tz = TimeZone.getTimeZone("Europe/Kyiv")

        fun makeTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
            return Calendar.getInstance(tz).apply {
                set(year, month, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        val octOutageStart = makeTime(2026, Calendar.OCTOBER, 25, 10, 0)
        val octOutageEnd = makeTime(2026, Calendar.OCTOBER, 25, 12, 0) // 120 min
        val novOutageStart = makeTime(2026, Calendar.NOVEMBER, 20, 14, 0)
        val novOutageEnd = makeTime(2026, Calendar.NOVEMBER, 20, 15, 30) // 90 min
        val nowNov = makeTime(2026, Calendar.NOVEMBER, 20, 18, 0)

        val events = listOf(
            PowerEvent(id = 1, timestamp = novOutageEnd, eventType = "POWER_CONNECTED", batteryPercent = 80, temperatureCelsius = 25f, durationMinutes = 90),
            PowerEvent(id = 2, timestamp = novOutageStart, eventType = "POWER_DISCONNECTED", batteryPercent = 90, temperatureCelsius = 25f, durationMinutes = 0),
            PowerEvent(id = 3, timestamp = octOutageEnd, eventType = "POWER_CONNECTED", batteryPercent = 70, temperatureCelsius = 24f, durationMinutes = 120),
            PowerEvent(id = 4, timestamp = octOutageStart, eventType = "POWER_DISCONNECTED", batteryPercent = 85, temperatureCelsius = 24f, durationMinutes = 0)
        )

        val months = PowerStatsHelper.getAvailableMonths(events, nowMs = nowNov, timeZone = tz)
        assertEquals(2, months.size)
        assertEquals(MonthKey(2026, Calendar.NOVEMBER), months[0])
        assertEquals(MonthKey(2026, Calendar.OCTOBER), months[1])

        val octEvents = PowerStatsHelper.filterEventsForMonth(events, MonthKey(2026, Calendar.OCTOBER), timeZone = tz)
        assertEquals(2, octEvents.size)

        val octStats = PowerStatsHelper.calculateMonthStats(events, MonthKey(2026, Calendar.OCTOBER), nowMs = nowNov, timeZone = tz)
        assertEquals(2.0f, octStats.outageHours, 0.01f)
        assertEquals(1, octStats.outageCount)

        val novStats = PowerStatsHelper.calculateMonthStats(events, MonthKey(2026, Calendar.NOVEMBER), nowMs = nowNov, timeZone = tz)
        assertEquals(1.5f, novStats.outageHours, 0.01f)
        assertEquals(1, novStats.outageCount)
    }

    /**
     * v3.5.2 Критерій 4: Скорочена тижнева статистика для Telegram ("20.11.2026 - 🔌не було 2 години")
     * Показує тільки ті дні за тиждень, коли були відключення.
     */
    @Test
    fun testV352_WeeklyTelegramStats_OnlyOutageDaysAndFormatting() {
        val tz = TimeZone.getTimeZone("Europe/Kyiv")

        fun makeTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
            return Calendar.getInstance(tz).apply {
                set(year, month, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        val nowMs = makeTime(2026, Calendar.NOVEMBER, 20, 20, 0)
        val day18End = makeTime(2026, Calendar.NOVEMBER, 18, 13, 0) // 1h outage
        val day20End = makeTime(2026, Calendar.NOVEMBER, 20, 14, 0) // 2h outage
        val oldDayEnd = makeTime(2026, Calendar.NOVEMBER, 5, 12, 0) // older than 7 days

        val events = listOf(
            PowerEvent(id = 1, timestamp = day20End, eventType = "POWER_CONNECTED", batteryPercent = 80, temperatureCelsius = 25f, durationMinutes = 120),
            PowerEvent(id = 2, timestamp = day18End, eventType = "POWER_CONNECTED", batteryPercent = 85, temperatureCelsius = 24f, durationMinutes = 60),
            PowerEvent(id = 3, timestamp = oldDayEnd, eventType = "POWER_CONNECTED", batteryPercent = 90, temperatureCelsius = 24f, durationMinutes = 180)
        )

        val text = PowerStatsHelper.formatWeeklyStatsForTelegram(events, nowMs = nowMs, timeZone = tz)
        assertTrue(text.contains("18.11.2026 - 🔌не було 1 година"))
        assertTrue(text.contains("20.11.2026 - 🔌не було 2 години"))
        assertFalse("Days without outages (e.g. 19.11.2026) must be omitted", text.contains("19.11.2026"))
        assertFalse("Days older than 7 days must be omitted", text.contains("05.11.2026"))

        // Pluralization checks
        assertEquals("45 хв", PowerStatsHelper.formatOutageDurationUk(45))
        assertEquals("1 година", PowerStatsHelper.formatOutageDurationUk(60))
        assertEquals("2 години", PowerStatsHelper.formatOutageDurationUk(120))
        assertEquals("5 годин", PowerStatsHelper.formatOutageDurationUk(300))
        assertEquals("2 год 30 хв", PowerStatsHelper.formatOutageDurationUk(150))
    }

    /**
     * v3.5.2 Критерій 5: Перевірка оновлень GitHub (порівняння версій та парсинг релізу)
     */
    @Test
    fun testV352_GitHubUpdater_VersionComparisonAndReleaseParsing() {
        assertFalse(GitHubUpdater.isNewerVersion("v3.5.1", "3.5.2"))
        assertFalse(GitHubUpdater.isNewerVersion("v3.5.2", "3.5.2"))
        assertTrue(GitHubUpdater.isNewerVersion("v3.5.3", "3.5.2"))
        assertTrue(GitHubUpdater.isNewerVersion("v3.6", "3.5.2"))
        assertTrue(GitHubUpdater.isNewerVersion("4.0.0", "3.5.2"))

        val releaseJsonWithApk = """
        {
          "tag_name": "v3.5.3",
          "name": "PowerBot v3.5.3",
          "body": "Виправлено помилки та покращено стабільність.",
          "html_url": "https://github.com/Bombin1/PowerBotApp/releases/tag/v3.5.3",
          "assets": [
            {
              "name": "PowerBot-v3.5.3.apk",
              "size": 12345678,
              "browser_download_url": "https://github.com/Bombin1/PowerBotApp/releases/download/v3.5.3/PowerBot-v3.5.3.apk"
            }
          ]
        }
        """.trimIndent()

        val release = GitHubUpdater.parseReleaseJson(releaseJsonWithApk)
        assertEquals("v3.5.3", release.tagName)
        assertEquals("3.5.3", release.versionName)
        assertEquals("PowerBot v3.5.3", release.releaseName)
        assertEquals("PowerBot-v3.5.3.apk", release.apkFileName)
        assertEquals(12345678L, release.apkSizeBytes)
        assertEquals(
            "https://github.com/Bombin1/PowerBotApp/releases/download/v3.5.3/PowerBot-v3.5.3.apk",
            release.apkDownloadUrl
        )
    }

    /**
     * v3.5.3 Критерій 3: Місячна статистика рахує лише фактичний відстежуваний час (не з 1-го числа місяця),
     * і форматує тривалість у годинах і хвилинах ("9 хв", "1 год 15 хв").
     */
    @Test
    fun testV353_MonthlyStats_AccurateOnlineAndMinutesFormat() {
        val tz = TimeZone.getTimeZone("Europe/Kyiv")

        fun makeTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
            return Calendar.getInstance(tz).apply {
                set(year, month, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        // Nov 20: app started at 14:00, outage 15:00..15:09 (9 min), now is 16:00 (41 min online since 15:09? wait: 16:00 - 15:09 = 51 min)
        val disconnectAt = makeTime(2026, Calendar.NOVEMBER, 20, 15, 0)
        val reconnectAt = makeTime(2026, Calendar.NOVEMBER, 20, 15, 9)
        val nowMs = makeTime(2026, Calendar.NOVEMBER, 20, 16, 0)

        val events = listOf(
            PowerEvent(id = 2, timestamp = reconnectAt, eventType = "POWER_CONNECTED", batteryPercent = 85, temperatureCelsius = 25f, durationMinutes = 9),
            PowerEvent(id = 1, timestamp = disconnectAt, eventType = "POWER_DISCONNECTED", batteryPercent = 90, temperatureCelsius = 25f, durationMinutes = 60)
        )

        val stats = PowerStatsHelper.calculateMonthStats(
            events = events,
            monthKey = MonthKey(2026, Calendar.NOVEMBER),
            nowMs = nowMs,
            ongoingPowerState = true,
            ongoingStateStartMs = reconnectAt,
            timeZone = tz
        )

        // Online: 60 min (14:00-15:00) + 51 min (15:09-16:00) = 111 min = 1 год 51 хв (NOT 474 hours!)
        assertEquals(111L, stats.onlineMinutes)
        assertEquals("1 год 51 хв", PowerStatsHelper.formatDurationHoursMinutesUk(stats.onlineMinutes))

        // Outage: 9 min = "9 хв" (NOT "0.1 год" / "0.9 год")
        assertEquals(9L, stats.outageMinutes)
        assertEquals("9 хв", PowerStatsHelper.formatDurationHoursMinutesUk(stats.outageMinutes))
        assertEquals(1, stats.outageCount)

        // Formatting edge cases
        assertEquals("0 хв", PowerStatsHelper.formatDurationHoursMinutesUk(0L))
        assertEquals("54 хв", PowerStatsHelper.formatDurationHoursMinutesUk(54L))
        assertEquals("2 год", PowerStatsHelper.formatDurationHoursMinutesUk(120L))
        assertEquals("2 год 15 хв", PowerStatsHelper.formatDurationHoursMinutesUk(135L))
    }

    @Test
    fun testGitHubUpdater_FallbackRepoApkAndGradleParsing() {
        val candidates = GitHubUpdater.buildFallbackApkCandidates("v3.5.4")
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/v3.5.4/PowerBot.apk",
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/v3.5.4/app-debug.apk",
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/main/PowerBot.apk",
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/main/app-debug.apk"
            ),
            candidates
        )

        val gradleSnippet = """
            defaultConfig {
              applicationId = "com.aistudio.powerbot.ukrlgt"
              versionCode = 354
              versionName = "3.5.4"
            }
        """.trimIndent()
        assertEquals("3.5.4", GitHubUpdater.parseGradleVersionName(gradleSnippet))
    }

    /**
     * v3.5.4: Перевірка сумування кількох графіків у спільному "Розкладі за інтервалами"
     */
    @Test
    fun testV354_ScheduleCombiner_EmptySingleAndMultiple() {
        // 0 schedules
        val emptyResult = ScheduleCombiner.combineSchedules(emptyList())
        assertTrue(emptyResult.intervals.isEmpty())
        assertEquals(0, emptyResult.onlineHours)
        assertEquals(0, emptyResult.outageHours)

        fun buildSchedule(queue: String, statuses: List<OutageStatus>): CitySchedule {
            val hours = statuses.mapIndexed { idx, st ->
                HourSlot(
                    hour = idx + 1,
                    timeLabel = String.format("%02d:00-%02d:00", idx, idx + 1),
                    status = st,
                    statusDescription = when (st) {
                        OutageStatus.ONLINE -> "Світло є"
                        OutageStatus.OUTAGE -> "Світла немає"
                        OutageStatus.MAYBE -> "Можливе відключення"
                    }
                )
            }
            val intervals = mutableListOf<OutageInterval>()
            var cur = statuses[0]
            var start = 0
            for (h in 1 until 24) {
                if (statuses[h] != cur) {
                    intervals.add(
                        OutageInterval(
                            startHour = String.format("%02d:00", start),
                            endHour = String.format("%02d:00", h),
                            status = cur,
                            description = "",
                            durationHours = h - start
                        )
                    )
                    cur = statuses[h]
                    start = h
                }
            }
            intervals.add(
                OutageInterval(
                    startHour = String.format("%02d:00", start),
                    endHour = "24:00",
                    status = cur,
                    description = "",
                    durationHours = 24 - start
                )
            )
            return CitySchedule("Київ", queue, "24.11.2026", hours, intervals)
        }

        val q1Statuses = MutableList(24) { OutageStatus.ONLINE }.apply {
            // Outage 02:00 - 05:00 (hours 2, 3, 4)
            this[2] = OutageStatus.OUTAGE
            this[3] = OutageStatus.OUTAGE
            this[4] = OutageStatus.OUTAGE
            // Maybe 10:00 - 12:00 (hours 10, 11)
            this[10] = OutageStatus.MAYBE
            this[11] = OutageStatus.MAYBE
        }
        val q2Statuses = MutableList(24) { OutageStatus.ONLINE }.apply {
            // Outage 04:00 - 07:00 (overlaps hour 4 with q1)
            this[4] = OutageStatus.OUTAGE
            this[5] = OutageStatus.OUTAGE
            this[6] = OutageStatus.OUTAGE
            // Outage 11:00 - 13:00 (hour 11 overrides q1's MAYBE -> OUTAGE)
            this[11] = OutageStatus.OUTAGE
            this[12] = OutageStatus.OUTAGE
        }

        val s1 = buildSchedule("GPV1.1", q1Statuses)
        val s2 = buildSchedule("GPV2.2", q2Statuses)

        // Single schedule preserves its own intervals
        val singleResult = ScheduleCombiner.combineSchedules(listOf(s1))
        assertEquals(listOf("1.1"), singleResult.activeQueueLabels)
        assertEquals(3, singleResult.outageHours)
        assertEquals(2, singleResult.maybeHours)
        assertEquals(19, singleResult.onlineHours)

        // Two schedules merge with priority OUTAGE > MAYBE > ONLINE
        val merged = ScheduleCombiner.combineSchedules(listOf(s1, s2))
        assertEquals(listOf("1.1", "2.2"), merged.activeQueueLabels)
        // Outage hours: 2,3,4,5,6 (5h) + 11,12 (2h) = 7h
        assertEquals(7, merged.outageHours)
        // Maybe hours: 10 (1h)
        assertEquals(1, merged.maybeHours)
        // Online hours: 16h
        assertEquals(16, merged.onlineHours)

        val outage0207 = merged.intervals.find { it.startHour == "02:00" && it.endHour == "07:00" }
        assertNotNull(outage0207)
        assertEquals(OutageStatus.OUTAGE, outage0207!!.status)
        assertEquals(5, outage0207.durationHours)
        assertEquals(listOf("1.1", "2.2"), merged.affectedQueuesByIntervalKey["02:00-07:00"])

        val maybe1011 = merged.intervals.find { it.startHour == "10:00" && it.endHour == "11:00" }
        assertNotNull(maybe1011)
        assertEquals(OutageStatus.MAYBE, maybe1011!!.status)
        assertEquals(listOf("1.1"), merged.affectedQueuesByIntervalKey["10:00-11:00"])

        val outage1113 = merged.intervals.find { it.startHour == "11:00" && it.endHour == "13:00" }
        assertNotNull(outage1113)
        assertEquals(OutageStatus.OUTAGE, outage1113!!.status)
        assertEquals(listOf("2.2", "1.1"), merged.affectedQueuesByIntervalKey["11:00-13:00"])
    }

    @Test
    fun testV354_ScheduleCombiner_MultipleCitiesIncludesCityNames() {
        fun buildCitySchedule(city: String, queue: String, outageHour: Int): CitySchedule {
            val statuses = MutableList(24) { OutageStatus.ONLINE }.apply { this[outageHour] = OutageStatus.OUTAGE }
            val hours = statuses.mapIndexed { idx, st ->
                HourSlot(idx + 1, String.format("%02d:00-%02d:00", idx, idx + 1), st, "")
            }
            return CitySchedule(city, queue, "24.11.2026", hours, emptyList())
        }

        val kyiv = buildCitySchedule("Київ", "GPV1.1", 8)
        val khmelnytskyi = buildCitySchedule("Хмельницький", "GPV1.1", 14)

        val merged = ScheduleCombiner.combineSchedules(listOf(kyiv, khmelnytskyi))
        assertEquals(listOf("Київ 1.1", "Хмельницький 1.1"), merged.activeQueueLabels)
        assertEquals(2, merged.outageHours)
        assertEquals(listOf("Київ 1.1"), merged.affectedQueuesByIntervalKey["08:00-09:00"])
        assertEquals(listOf("Хмельницький 1.1"), merged.affectedQueuesByIntervalKey["14:00-15:00"])
    }

    @Test
    fun testV354_ScheduleParser_BuildFreshUrlBypassesCache() {
        val baseUrl = "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/kyiv.json"
        assertEquals(baseUrl, ScheduleParser.buildFreshUrl(baseUrl, bypassCache = false, timestampMs = 123456L))
        assertEquals(
            "$baseUrl?_ts=123456",
            ScheduleParser.buildFreshUrl(baseUrl, bypassCache = true, timestampMs = 123456L)
        )
        assertEquals(
            "$baseUrl?foo=1&_ts=123456",
            ScheduleParser.buildFreshUrl("$baseUrl?foo=1", bypassCache = true, timestampMs = 123456L)
        )
    }

    /**
     * v3.5.5: Хмельницький оновився о 22:06 — у fact.data є старий графік "1795644000" (26.11.2026)
     * і новий графік "1795730400" (27.11.2026). Парсер має обрати "1795730400" і виявити зміну.
     */
    @Test
    fun testV355_KhmelnytskyiMultiDateUpdate_PicksNewerScheduleAndDetectsChange() {
        val oldOnlyJson = """
        {
          "regionId": "Khmelnytskoblenerho",
          "lastUpdated": "2026-11-26T10:00:00.000Z",
          "fact": {
            "today": 1795644000,
            "update": "26.11.2026 12:00",
            "data": {
              "1795644000": {
                "GPV3.1": {
                  "1": "yes", "2": "yes", "3": "yes", "4": "yes", "5": "yes", "6": "yes",
                  "7": "yes", "8": "yes", "9": "yes", "10": "yes", "11": "yes", "12": "yes",
                  "13": "yes", "14": "yes", "15": "yes", "16": "yes", "17": "yes", "18": "yes",
                  "19": "yes", "20": "yes", "21": "yes", "22": "yes", "23": "yes", "24": "yes"
                }
              }
            }
          }
        }
        """.trimIndent()

        val updatedMultiDateJson = """
        {
          "regionId": "Khmelnytskoblenerho",
          "lastUpdated": "2026-11-26T20:06:00.000Z",
          "fact": {
            "today": 1795644000,
            "update": "26.11.2026 22:06",
            "data": {
              "1795644000": {
                "GPV3.1": {
                  "1": "yes", "2": "yes", "3": "yes", "4": "yes", "5": "yes", "6": "yes",
                  "7": "yes", "8": "yes", "9": "yes", "10": "yes", "11": "yes", "12": "yes",
                  "13": "yes", "14": "yes", "15": "yes", "16": "yes", "17": "yes", "18": "yes",
                  "19": "yes", "20": "yes", "21": "yes", "22": "yes", "23": "yes", "24": "yes"
                }
              },
              "1795730400": {
                "GPV3.1": {
                  "1": "yes", "2": "yes", "3": "yes", "4": "yes", "5": "yes", "6": "yes",
                  "7": "no",  "8": "no",  "9": "no",  "10": "yes", "11": "yes", "12": "yes",
                  "13": "yes", "14": "yes", "15": "yes", "16": "no", "17": "no", "18": "no",
                  "19": "yes", "20": "yes", "21": "yes", "22": "yes", "23": "yes", "24": "yes"
                }
              }
            }
          }
        }
        """.trimIndent()

        val oldResult = ScheduleParser.parseScheduleJson(oldOnlyJson, "Хмельницький", "GPV3.1")
        assertTrue(oldResult is ScheduleParseResult.Success)
        val oldSnapshot = (oldResult as ScheduleParseResult.Success).snapshot
        assertEquals("26.11.2026", oldResult.schedule.dateLabel)

        val newResult = ScheduleParser.parseScheduleJson(updatedMultiDateJson, "Хмельницький", "GPV3.1")
        assertTrue("Updated multi-date JSON must parse cleanly", newResult is ScheduleParseResult.Success)
        val newSuccess = newResult as ScheduleParseResult.Success
        assertEquals("27.11.2026", newSuccess.schedule.dateLabel)
        assertEquals(1795730400L, newSuccess.schedule.todayTimestamp)
        assertEquals("26.11.2026 22:06", newSuccess.schedule.factUpdate)

        val outageIntervals = newSuccess.schedule.intervals.filter { it.status == OutageStatus.OUTAGE }
        assertEquals(2, outageIntervals.size)
        assertEquals("06:00", outageIntervals[0].startHour)
        assertEquals("09:00", outageIntervals[0].endHour)
        assertEquals("15:00", outageIntervals[1].startHour)
        assertEquals("18:00", outageIntervals[1].endHour)

        val changeResult = ScheduleParser.detectScheduleChanges(oldSnapshot, newSuccess.snapshot)
        assertTrue("Change MUST be detected when newer date appears in fact.data", changeResult.isChanged)
        assertTrue(changeResult.isDateChanged)
    }

    @Test
    fun testV355_GitHubUpdater_PreferMainCandidatesAndCacheBuster() {
        val candidates = GitHubUpdater.buildFallbackApkCandidates("v3.5.5", preferMain = true)
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/main/PowerBot.apk",
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/main/app-debug.apk",
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/v3.5.5/PowerBot.apk",
                "https://raw.githubusercontent.com/Bombin1/PowerBotApp/v3.5.5/app-debug.apk"
            ),
            candidates
        )

        val rawUrl = "https://raw.githubusercontent.com/Bombin1/PowerBotApp/main/PowerBot.apk"
        assertEquals(
            "$rawUrl?_ts=99999",
            GitHubUpdater.appendCacheBuster(rawUrl, timestampMs = 99999L)
        )

        val signedUrl = "https://objects.githubusercontent.com/github-production-release-asset/123?X-Amz-Signature=abc"
        assertEquals(
            signedUrl,
            GitHubUpdater.appendCacheBuster(signedUrl, timestampMs = 99999L)
        )
    }
}




