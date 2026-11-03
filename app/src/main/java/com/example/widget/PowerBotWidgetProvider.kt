package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.model.OutageStatus
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import com.example.util.BatteryHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PowerBotWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action == ACTION_REFRESH_WIDGET ||
            action == Intent.ACTION_POWER_CONNECTED ||
            action == Intent.ACTION_POWER_DISCONNECTED
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Try refreshing schedule in background if refresh button tapped
                    if (action == ACTION_REFRESH_WIDGET) {
                        val prefs = AppPreferences(context)
                        val source = OutageSources.getCitySource(prefs.selectedCity)
                        if (source != null) {
                            val json = ScheduleParser.fetchJson(source.jsonUrl)
                            if (json != null) {
                                prefs.cachedScheduleJson = json
                            }
                        }
                    }
                    updateAllWidgets(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.example.widget.ACTION_REFRESH_WIDGET"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val widgetComponent = ComponentName(context, PowerBotWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
            if (widgetIds.isEmpty()) return

            val prefs = AppPreferences(context)
            val batteryInfo = BatteryHelper.getBatteryInfo(context)
            val isOnline = batteryInfo.isPlugged

            // Calculate duration in current state
            val lastChange = prefs.lastStateChangeTime
            val diffMs = System.currentTimeMillis() - lastChange
            val durationMins = (diffMs / (1000 * 60)).coerceAtLeast(0)
            val durationText = formatDuration(durationMins)

            // Parse schedule for region
            val city = prefs.selectedCity
            val queue = prefs.selectedQueue
            val cleanQueue = queue.replace("GPV", "").replace("черга", "").trim()
            val cachedJson = prefs.cachedScheduleJson

            var scheduleText = "Зараз за графіком: 🟢 Світло є"
            if (cachedJson.isNotBlank()) {
                val schedule = ScheduleParser.parseSchedule(cachedJson, city, queue)
                if (schedule != null) {
                    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val currentSlot = schedule.hours.getOrNull(currentHour)
                    if (currentSlot != null) {
                        val icon = when (currentSlot.status) {
                            OutageStatus.ONLINE -> "🟢"
                            OutageStatus.OUTAGE -> "🔴"
                            OutageStatus.MAYBE -> "🟡"
                        }
                        scheduleText = "Зараз: $icon ${currentSlot.statusDescription}"
                    }
                }
            }

            val timeNow = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            for (widgetId in widgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_power_status)

                // Header region
                views.setTextViewText(R.id.widget_region_text, "⚡ PowerBot • $city ($cleanQueue)")

                // Main power status & color
                val statusText = if (isOnline) "💡 СВІТЛО Є" else "🕯️ СВІТЛА НЕМАЄ"
                val statusColor = ContextCompat.getColor(
                    context,
                    if (isOnline) R.color.widget_status_online else R.color.widget_status_outage
                )
                views.setTextViewText(R.id.widget_power_status_text, statusText)
                views.setTextColor(R.id.widget_power_status_text, statusColor)

                // Duration text
                views.setTextViewText(R.id.widget_duration_text, durationText)

                // Schedule banner
                views.setTextViewText(R.id.widget_schedule_text, scheduleText)

                // Telemetry footer
                views.setTextViewText(R.id.widget_battery_text, "🔋 ${batteryInfo.percent}%")
                views.setTextViewText(
                    R.id.widget_temp_text,
                    String.format(Locale.US, "🌡️ %.1f°C", batteryInfo.temperatureCelsius)
                )
                views.setTextViewText(R.id.widget_update_time_text, "🕒 $timeNow")

                // Open App on widget click
                val openAppIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openAppPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

                // Manual refresh button click
                val refreshIntent = Intent(context, PowerBotWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH_WIDGET
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun formatDuration(minutes: Long): String {
            if (minutes < 1) return "менше 1 хв"
            val hours = minutes / 60
            val mins = minutes % 60
            return when {
                hours > 0 && mins > 0 -> "$hours год $mins хв"
                hours > 0 -> "$hours год"
                else -> "$mins хв"
            }
        }
    }
}
