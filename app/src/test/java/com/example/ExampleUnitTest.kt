package com.example

import com.example.data.model.OutageStatus
import com.example.data.model.ScheduleParseResult
import com.example.data.model.ScheduleSnapshot
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import org.junit.Assert.*
import org.junit.Test
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
}

