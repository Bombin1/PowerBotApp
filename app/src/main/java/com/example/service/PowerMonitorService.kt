package com.example.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.model.PowerEvent
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import com.example.data.remote.TelegramClient
import com.example.data.remote.TgUpdate
import com.example.util.BatteryHelper
import com.example.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PowerMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var preferences: AppPreferences
    private lateinit var database: AppDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var scheduleCheckJob: Job? = null
    private var telegramPollingJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || context == null) return
            val action = intent.action
            if (action == Intent.ACTION_BATTERY_CHANGED ||
                action == Intent.ACTION_POWER_CONNECTED ||
                action == Intent.ACTION_POWER_DISCONNECTED
            ) {
                handleBatteryUpdate(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        database = AppDatabase.getDatabase(this)

        NotificationHelper.createNotificationChannels(this)

        // WakeLock to keep telemetry and polling alive when screen is locked (mimicking termux-wake-lock)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerBot:MonitorWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours renewal
        }

        val initialInfo = BatteryHelper.getBatteryInfo(this)
        val notif = NotificationHelper.buildForegroundNotification(
            this,
            initialInfo.isPlugged,
            initialInfo.percent,
            initialInfo.temperatureCelsius
        )
        startForeground(NotificationHelper.FOREGROUND_NOTIF_ID, notif)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)

        startPeriodicScheduleChecker()
        startTelegramPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleBatteryUpdate(this)
        startTelegramPolling()
        return START_STICKY
    }

    private fun handleBatteryUpdate(context: Context) {
        val info = BatteryHelper.getBatteryInfo(context)

        // Update ongoing foreground notification
        val notif = NotificationHelper.buildForegroundNotification(
            context,
            info.isPlugged,
            info.percent,
            info.temperatureCelsius
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.FOREGROUND_NOTIF_ID, notif)

        // Refresh home screen widgets with current status
        com.example.widget.PowerBotWidgetProvider.updateAllWidgets(context)

        val lastState = preferences.lastPowerState
        val now = System.currentTimeMillis()

        if (lastState == null) {
            // First run, record initial baseline
            preferences.lastPowerState = info.isPlugged
            preferences.lastStateChangeTime = now
            return
        }

        if (lastState != info.isPlugged) {
            val previousDurationMs = now - preferences.lastStateChangeTime
            val durationMinutes = (previousDurationMs / (1000 * 60)).coerceAtLeast(0)

            // Update stored state
            preferences.lastPowerState = info.isPlugged
            preferences.lastStateChangeTime = now

            val eventType = if (info.isPlugged) "POWER_CONNECTED" else "POWER_DISCONNECTED"

            serviceScope.launch {
                // 1. Log to Room Database
                database.powerEventDao().insertEvent(
                    PowerEvent(
                        timestamp = now,
                        eventType = eventType,
                        batteryPercent = info.percent,
                        temperatureCelsius = info.temperatureCelsius,
                        durationMinutes = durationMinutes
                    )
                )

                // 2. Local Android Alert Notification
                if (preferences.powerAlertsEnabled) {
                    NotificationHelper.showPowerAlertNotification(
                        context = context,
                        isRestored = info.isPlugged,
                        durationMinutes = durationMinutes,
                        batteryPercent = info.percent,
                        tempCelsius = info.temperatureCelsius,
                        soundEnabled = preferences.soundAlertsEnabled,
                        vibrationEnabled = preferences.vibrationAlertsEnabled
                    )
                }

                // 3. Telegram Alert Message
                if (preferences.telegramEnabled && preferences.telegramBotToken.isNotBlank() && preferences.telegramChatId.isNotBlank()) {
                    val durationStr = formatDuration(durationMinutes)
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

                    val tgText = if (info.isPlugged) {
                        "💡 *Світло з'явилось!*\n\n" +
                                "🕒 Час: $timeStr\n" +
                                "⏱️ Світла не було: *$durationStr*\n" +
                                "🔋 Батарея: *${info.percent}%*\n" +
                                "🌡️ Температура: *${String.format(Locale.US, "%.1f", info.temperatureCelsius)}°C*"
                    } else {
                        "🕯️ *Світло зникло!*\n\n" +
                                "🕒 Час: $timeStr\n" +
                                "⏱️ Світло було: *$durationStr*\n" +
                                "🔋 Батарея: *${info.percent}%*\n" +
                                "🌡️ Температура: *${String.format(Locale.US, "%.1f", info.temperatureCelsius)}°C*"
                    }

                    TelegramClient.sendMessage(
                        botToken = preferences.telegramBotToken,
                        chatId = preferences.telegramChatId,
                        text = tgText,
                        withKeyboard = true
                    )
                }

                _stateChangeEvents.emit(info.isPlugged)
            }
        }
    }

    private fun startTelegramPolling() {
        telegramPollingJob?.cancel()
        if (!preferences.telegramEnabled || !preferences.telegramBotCommandsEnabled || preferences.telegramBotToken.isBlank()) {
            return
        }

        telegramPollingJob = serviceScope.launch {
            var offset = preferences.lastTelegramUpdateId
            while (isActive) {
                val token = preferences.telegramBotToken
                if (token.isBlank() || !preferences.telegramEnabled || !preferences.telegramBotCommandsEnabled) {
                    delay(4000)
                    continue
                }

                try {
                    val updates = TelegramClient.getUpdates(token, offset, timeoutSeconds = 15)
                    for (update in updates) {
                        offset = update.updateId + 1
                        preferences.lastTelegramUpdateId = offset

                        handleIncomingTelegramMessage(token, update)
                    }
                } catch (e: Exception) {
                    delay(3000)
                }
            }
        }
    }

    private suspend fun handleIncomingTelegramMessage(botToken: String, update: TgUpdate) {
        val text = update.text.trim()
        val chatId = update.chatId.toString()

        when {
            text == "💡 Перевірити наявність" || text.startsWith("/status") -> {
                // Delete user command message if in group/chat (like in Termux bot)
                TelegramClient.deleteMessage(botToken, update.chatId, update.messageId)

                val info = BatteryHelper.getBatteryInfo(this@PowerMonitorService)
                val statusText = if (info.isPlugged) "💡 *Світло є*" else "🕯️ *Світла немає*"
                val response = "$statusText\n🔋: *${info.percent}%* | 🌡️: ~*${String.format(Locale.US, "%.1f", info.temperatureCelsius)}°C*"

                TelegramClient.sendMessage(
                    botToken = botToken,
                    chatId = chatId,
                    text = response,
                    withKeyboard = true
                )
            }

            text == "📊 Графік" || text.startsWith("/graph") || text.startsWith("/schedule") -> {
                val city = preferences.selectedCity
                val queue = preferences.selectedQueue
                val cached = preferences.cachedScheduleJson
                val schedule = if (cached.isNotBlank()) {
                    ScheduleParser.parseSchedule(cached, city, queue)
                } else {
                    ScheduleParser.generateDefaultSchedule(city, queue, "Сьогодні")
                }

                val scheduleText = if (schedule != null) {
                    ScheduleParser.formatScheduleText(schedule)
                } else {
                    "📅 Графік для $city ($queue) поки не завантажено в додатку."
                }

                TelegramClient.sendMessage(
                    botToken = botToken,
                    chatId = chatId,
                    text = scheduleText,
                    withKeyboard = true
                )
            }

            text == "ℹ️ Інфо" || text.startsWith("/info") -> {
                val infoText = "ℹ️ *Інформація:*\n\n" +
                        "🤖 *PowerBot (v3.5 Android Native)*\n" +
                        "Працює як апаратний сенсор мережі 220V на телефоні.\n\n" +
                        "🤝 Подяка автору парсерів @yaroslav2901.\n" +
                        "☕ [Підтримати автора (Monobank)](https://send.monobank.ua/jar/8WFAPWLdPu)"

                TelegramClient.sendMessage(
                    botToken = botToken,
                    chatId = chatId,
                    text = infoText,
                    withKeyboard = true
                )
            }

            text.startsWith("/start") -> {
                val welcome = "🤖 *PowerBot підключено та працює!*\n\n" +
                        "📱 Пристрій моніторить живлення в реальному часі.\n" +
                        "Натисніть кнопку нижче або напишіть /status для перевірки."

                TelegramClient.sendMessage(
                    botToken = botToken,
                    chatId = chatId,
                    text = welcome,
                    withKeyboard = true
                )
            }
        }
    }

    private fun startPeriodicScheduleChecker() {
        scheduleCheckJob?.cancel()
        scheduleCheckJob = serviceScope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L) // Check every 30 minutes
                try {
                    if (preferences.scheduleNotificationsEnabled) {
                        val city = preferences.selectedCity
                        val queue = preferences.selectedQueue
                        val source = OutageSources.getCitySource(city)
                        if (source != null) {
                            val json = ScheduleParser.fetchJson(source.jsonUrl)
                            if (json != null) {
                                val schedule = ScheduleParser.parseSchedule(json, city, queue)
                                if (schedule != null) {
                                    val formatted = ScheduleParser.formatScheduleText(schedule)
                                    val last = preferences.lastScheduleText
                                    if (formatted != last) {
                                        preferences.lastScheduleText = formatted
                                        NotificationHelper.showScheduleNotification(
                                            this@PowerMonitorService,
                                            "⚠️ Оновлення графіка: $city ($queue)",
                                            formatted
                                        )

                                        if (preferences.telegramEnabled && preferences.telegramBotToken.isNotBlank() && preferences.telegramChatId.isNotBlank()) {
                                            TelegramClient.sendMessage(
                                                preferences.telegramBotToken,
                                                preferences.telegramChatId,
                                                "⚠️ *Графік оновлено!*\n\n$formatted",
                                                withKeyboard = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        telegramPollingJob?.cancel()
        scheduleCheckJob?.cancel()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val _stateChangeEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        val stateChangeEvents: SharedFlow<Boolean> = _stateChangeEvents.asSharedFlow()

        fun startService(context: Context) {
            val intent = Intent(context, PowerMonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PowerMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
