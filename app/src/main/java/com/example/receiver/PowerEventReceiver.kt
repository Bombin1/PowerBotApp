package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppPreferences
import com.example.service.PowerMonitorService

class PowerEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val preferences = AppPreferences(context)

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_POWER_CONNECTED ||
            action == Intent.ACTION_POWER_DISCONNECTED
        ) {
            if (preferences.backgroundServiceEnabled) {
                PowerMonitorService.startService(context)
            }
        }
    }
}
