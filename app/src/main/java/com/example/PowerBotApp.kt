package com.example

import android.app.Application
import com.example.data.local.AppPreferences
import com.example.service.PowerMonitorService
import com.example.util.NotificationHelper

class PowerBotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)

        val prefs = AppPreferences(this)
        if (prefs.backgroundServiceEnabled) {
            try {
                PowerMonitorService.startService(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
