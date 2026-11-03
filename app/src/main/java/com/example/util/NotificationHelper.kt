package com.example.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {

    const val CHANNEL_MONITORING = "powerbot_monitoring_channel"
    const val CHANNEL_ALERTS = "powerbot_alerts_channel"

    const val FOREGROUND_NOTIF_ID = 1001
    const val ALERT_NOTIF_ID = 1002
    const val SCHEDULE_NOTIF_ID = 1003

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Ongoing monitoring channel
            val monitoringChannel = NotificationChannel(
                CHANNEL_MONITORING,
                "Статус моніторингу PowerBot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постійне сповіщення про роботу фонового моніторингу електромережі"
                setShowBadge(false)
            }

            // High-priority alert channel for power on/off
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Сповіщення про світло (Увімк/Вимк)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Термінові сповіщення про появу чи зникнення світла"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }

            manager.createNotificationChannel(monitoringChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    fun buildForegroundNotification(
        context: Context,
        isPlugged: Boolean,
        percent: Int,
        tempCelsius: Float
    ): Notification {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPlugged) "⚡ Світло Є (Мережа підключена)" else "🕯️ Світла Немає (Автономно)"
        val content = "Батарея: $percent% | Температура: ${String.format(java.util.Locale.US, "%.1f", tempCelsius)}°C"

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showPowerAlertNotification(
        context: Context,
        isRestored: Boolean,
        durationMinutes: Long,
        batteryPercent: Int,
        tempCelsius: Float,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRestored) "💡 Світло з'явилось!" else "🕯️ Світло зникло!"

        val durationStr = formatDuration(durationMinutes)
        val subtitle = if (isRestored) {
            "Світла не було: $durationStr"
        } else {
            "Світло було: $durationStr"
        }

        val body = "$subtitle\n🔋 Батарея: $batteryPercent% | 🌡️ Температура: ${String.format(java.util.Locale.US, "%.1f", tempCelsius)}°C"

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(if (isRestored) android.R.drawable.ic_lock_idle_charging else android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (soundEnabled) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        } else {
            builder.setSilent(true)
        }

        if (vibrationEnabled) {
            builder.setVibrate(longArrayOf(0, 500, 200, 500))
        }

        manager.notify(ALERT_NOTIF_ID, builder.build())
    }

    fun showScheduleNotification(context: Context, title: String, content: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText("Графік погодинних відключень оновлено")
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(SCHEDULE_NOTIF_ID, notification)
    }

    private fun formatDuration(minutes: Long): String {
        if (minutes < 1) return "менше хвилини"
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> "$hours год $mins хв"
            hours > 0 -> "$hours год"
            else -> "$mins хв"
        }
    }
}
