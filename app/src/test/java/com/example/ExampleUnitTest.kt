package com.example

import com.example.data.model.OutageStatus
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
    @Test
    fun testCitySourcesCount() {
        // Verify all 16 Ukrainian regions from the Python bot are present
        assertEquals(16, OutageSources.CITIES.size)
        assertNotNull(OutageSources.getCitySource("Київ"))
        assertNotNull(OutageSources.getCitySource("Дніпро"))
        assertNotNull(OutageSources.getCitySource("Львів"))
        assertNotNull(OutageSources.getCitySource("Одеса"))
    }

    @Test
    fun testScheduleParserExtraction() {
        val sampleJson = """
        {
            "fact": {
                "today": "02.11.2026",
                "data": {
                    "02.11.2026": {
                        "GPV1.1": {
                            "1": "yes", "2": "yes", "3": "no", "4": "no",
                            "5": "yes", "6": "yes", "7": "yes", "8": "yes",
                            "9": "no", "10": "no", "11": "yes", "12": "yes",
                            "13": "yes", "14": "yes", "15": "no", "16": "no",
                            "17": "yes", "18": "yes", "19": "yes", "20": "yes",
                            "21": "no", "22": "no", "23": "yes", "24": "yes"
                        },
                        "GPV1.2": {
                            "1": "no", "2": "no", "3": "yes", "4": "yes"
                        }
                    }
                }
            }
        }
        """.trimIndent()

        val queues = ScheduleParser.extractQueues(sampleJson)
        assertTrue(queues.contains("GPV1.1"))
        assertTrue(queues.contains("GPV1.2"))

        val schedule = ScheduleParser.parseSchedule(sampleJson, "Київ", "GPV1.1")
        assertNotNull(schedule)
        assertEquals(24, schedule!!.hours.size)
        assertEquals(OutageStatus.ONLINE, schedule.hours[0].status)
        assertEquals(OutageStatus.ONLINE, schedule.hours[1].status)
        assertEquals(OutageStatus.OUTAGE, schedule.hours[2].status)
        assertEquals(OutageStatus.OUTAGE, schedule.hours[3].status)

        // Check intervals
        assertTrue(schedule.intervals.isNotEmpty())
        val formatted = ScheduleParser.formatScheduleText(schedule)
        assertTrue(formatted.contains("Київ"))
        assertTrue(formatted.contains("🟢"))
        assertTrue(formatted.contains("🔴"))
    }

    @Test
    fun testLastUpdatedFormat() {
        val iso = "2026-10-17T13:45:51.544Z"
        val formatted = ScheduleParser.formatLastUpdated(iso)
        assertEquals("17.10.2026 (оновл. 13:45)", formatted)

        val jsonWithLastUpdated = """
        {
            "lastUpdated": "2026-10-17T13:45:51.544Z",
            "GPV1.1": {
                "1": "yes", "2": "no"
            }
        }
        """.trimIndent()
        val parsed = ScheduleParser.parseSchedule(jsonWithLastUpdated, "Київ", "GPV1.1")
        assertNotNull(parsed)
        assertEquals("17.10.2026 (оновл. 13:45)", parsed!!.dateLabel)
    }

    @Test
    fun testZhytomyrRealSchema() {
        val json = """
        {
          "regionId": "Zhytomyr",
          "lastUpdated": "2025-12-08T06:54:55.423Z",
          "fact": {
            "data": {
              "1765144800": {
                "GPV1.1": {
                  "1": "no",
                  "2": "yes"
                }
              }
            },
            "update": "08.12.2025 08:30",
            "today": 1765144800
          }
        }
        """.trimIndent()

        val parsed = ScheduleParser.parseSchedule(json, "Житомир", "GPV1.1")
        assertNotNull(parsed)
        assertEquals("08.12.2025 (оновл. 08:30)", parsed!!.dateLabel)
        assertEquals(OutageStatus.OUTAGE, parsed.hours[0].status)
        assertEquals(OutageStatus.ONLINE, parsed.hours[1].status)
    }

    @Test
    fun testDefaultFallbackSchedule() {
        val defaultSchedule = ScheduleParser.generateDefaultSchedule("Львів", "GPV2.1", "Сьогодні")
        assertEquals(24, defaultSchedule.hours.size)
        assertTrue(defaultSchedule.intervals.isNotEmpty())
    }
}
