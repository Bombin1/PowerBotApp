package com.example.data.local

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("powerbot_prefs", Context.MODE_PRIVATE)

    var selectedCity: String
        get() = prefs.getString("selected_city", "Київ") ?: "Київ"
        set(value) = prefs.edit().putString("selected_city", value).apply()

    var selectedQueue: String
        get() = prefs.getString("selected_queue", "GPV1.1") ?: "GPV1.1"
        set(value) = prefs.edit().putString("selected_queue", value).apply()

    var scheduleNotificationsEnabled: Boolean
        get() = prefs.getBoolean("schedule_notifications", false)
        set(value) = prefs.edit().putBoolean("schedule_notifications", value).apply()

    var powerAlertsEnabled: Boolean
        get() = prefs.getBoolean("power_alerts", true)
        set(value) = prefs.edit().putBoolean("power_alerts", value).apply()

    var soundAlertsEnabled: Boolean
        get() = prefs.getBoolean("sound_alerts", true)
        set(value) = prefs.edit().putBoolean("sound_alerts", value).apply()

    var vibrationAlertsEnabled: Boolean
        get() = prefs.getBoolean("vibration_alerts", true)
        set(value) = prefs.edit().putBoolean("vibration_alerts", value).apply()

    var backgroundServiceEnabled: Boolean
        get() = prefs.getBoolean("background_service", true)
        set(value) = prefs.edit().putBoolean("background_service", value).apply()

    var telegramEnabled: Boolean
        get() = prefs.getBoolean("telegram_enabled", false)
        set(value) = prefs.edit().putBoolean("telegram_enabled", value).apply()

    var telegramBotToken: String
        get() = com.example.util.CryptoHelper.decrypt(prefs.getString("telegram_bot_token", "") ?: "")
        set(value) = prefs.edit().putString("telegram_bot_token", com.example.util.CryptoHelper.encrypt(value)).apply()

    var telegramChatId: String
        get() = com.example.util.CryptoHelper.decrypt(prefs.getString("telegram_chat_id", "") ?: "")
        set(value) = prefs.edit().putString("telegram_chat_id", com.example.util.CryptoHelper.encrypt(value)).apply()

    var telegramAdminId: String
        get() = com.example.util.CryptoHelper.decrypt(prefs.getString("telegram_admin_id", "") ?: "")
        set(value) = prefs.edit().putString("telegram_admin_id", com.example.util.CryptoHelper.encrypt(value)).apply()

    var lastPowerState: Boolean?
        get() {
            return if (prefs.contains("last_power_state")) {
                prefs.getBoolean("last_power_state", false)
            } else null
        }
        set(value) {
            if (value != null) {
                prefs.edit().putBoolean("last_power_state", value).apply()
            } else {
                prefs.edit().remove("last_power_state").apply()
            }
        }

    var lastStateChangeTime: Long
        get() = prefs.getLong("last_state_change_time", System.currentTimeMillis())
        set(value) = prefs.edit().putLong("last_state_change_time", value).apply()

    var lastScheduleText: String
        get() = prefs.getString("last_schedule_text", "") ?: ""
        set(value) = prefs.edit().putString("last_schedule_text", value).apply()

    var cachedScheduleJson: String
        get() = prefs.getString("cached_schedule_json", "") ?: ""
        set(value) = prefs.edit().putString("cached_schedule_json", value).apply()

    var telegramBotCommandsEnabled: Boolean
        get() = prefs.getBoolean("telegram_bot_commands_enabled", true)
        set(value) = prefs.edit().putBoolean("telegram_bot_commands_enabled", value).apply()

    var lastTelegramUpdateId: Long
        get() = prefs.getLong("last_telegram_update_id", 0L)
        set(value) = prefs.edit().putLong("last_telegram_update_id", value).apply()

    var telegramFirstRunNotified: Boolean
        get() = prefs.getBoolean("telegram_first_run_notified", false)
        set(value) = prefs.edit().putBoolean("telegram_first_run_notified", value).apply()

    var lastScheduleSnapshot: com.example.data.model.ScheduleSnapshot?
        get() {
            val json = prefs.getString("last_schedule_snapshot", "") ?: ""
            if (json.isBlank()) return null
            return try {
                val obj = org.json.JSONObject(json)
                val list = mutableListOf<String>()
                val arr = obj.optJSONArray("hourlyStatuses")
                if (arr != null) {
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                }
                com.example.data.model.ScheduleSnapshot(
                    regionId = obj.optString("regionId", ""),
                    scheduleDate = obj.optString("scheduleDate", ""),
                    todayTimestamp = obj.optLong("todayTimestamp", 0L),
                    queueName = obj.optString("queueName", ""),
                    hourlyStatuses = list,
                    factUpdate = obj.optString("factUpdate", ""),
                    lastUpdated = obj.optString("lastUpdated", "")
                )
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove("last_schedule_snapshot").apply()
            } else {
                try {
                    val obj = org.json.JSONObject().apply {
                        put("regionId", value.regionId)
                        put("scheduleDate", value.scheduleDate)
                        put("todayTimestamp", value.todayTimestamp)
                        put("queueName", value.queueName)
                        put("hourlyStatuses", org.json.JSONArray(value.hourlyStatuses))
                        put("factUpdate", value.factUpdate)
                        put("lastUpdated", value.lastUpdated)
                    }
                    prefs.edit().putString("last_schedule_snapshot", obj.toString()).apply()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
}
