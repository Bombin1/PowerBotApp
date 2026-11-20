package com.example.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.model.PowerEvent
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import com.example.data.remote.TelegramClient
import com.example.data.remote.TgUpdate
import com.example.util.BatteryHelper
import com.example.util.GitHubUpdater
import com.example.util.NotificationHelper
import com.example.util.PowerStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var preferences: AppPreferences
    private lateinit var database: AppDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var scheduleCheckJob: Job? = null
    private var telegramSupervisorJob: Job? = null
    private var telegramPollingJob: Job? = null
    private var activePollingToken: String = ""
    private var adminNotifiedToken: String = ""

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
        if (telegramSupervisorJob?.isActive == true) return
        telegramSupervisorJob = serviceScope.launch {
            while (isActive) {
                val token = preferences.telegramBotToken.trim()
                val shouldRun = preferences.telegramEnabled &&
                    preferences.telegramBotCommandsEnabled &&
                    token.isNotBlank()

                if (!shouldRun) {
                    if (telegramPollingJob?.isActive == true) {
                        telegramPollingJob?.cancel()
                        telegramPollingJob = null
                        activePollingToken = ""
                    }
                    delay(2000)
                    continue
                }

                if (telegramPollingJob?.isActive != true || activePollingToken != token) {
                    telegramPollingJob?.cancel()
                    activePollingToken = token
                    telegramPollingJob = launchTelegramPollingLoop(token)
                }

                delay(2000)
            }
        }
    }

    private fun launchTelegramPollingLoop(token: String): Job {
        return serviceScope.launch {
            val tokenHash = token.hashCode()
            if (preferences.lastTelegramTokenHash != tokenHash) {
                preferences.lastTelegramUpdateId = 0L
                preferences.lastTelegramTokenHash = tokenHash
            }

            // Clear any active webhook so getUpdates never hits HTTP 409 Conflict
            try {
                TelegramClient.deleteWebhook(token)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val adminIdLong = preferences.telegramAdminId.trim().toLongOrNull() ?: 0L
            val adminIds = if (adminIdLong != 0L) listOf(adminIdLong) else emptyList()

            // Register commands menu in Telegram (groups, default/channels, admin)
            try {
                TelegramClient.setBotCommands(token, adminIds)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // First-run activation check in Telegram chat
            if (!preferences.telegramFirstRunNotified && preferences.telegramChatId.isNotBlank()) {
                try {
                    val me = TelegramClient.getMe(token)
                    val username = me?.optString("username", "") ?: ""
                    val botLink = if (username.isNotBlank()) "https://t.me/$username" else "Telegram"
                    val msg = "🤖 *PowerBot активований!*\n\n" +
                            "👤 *Адмін*, щоб я міг надсилати сповіщення та відкрити налаштування, " +
                            "ви маєте написати мені першим.\n\n" +
                            "👉 *Натисніть сюди:* $botLink\n" +
                            "Та натисніть кнопку *START*."

                    TelegramClient.sendMessage(token, preferences.telegramChatId, msg, withKeyboard = true)
                    preferences.telegramFirstRunNotified = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Admin startup notification (at most once per service run for this token)
            if (adminIdLong != 0L && adminNotifiedToken != token) {
                adminNotifiedToken = token
                try {
                    TelegramClient.sendMessage(
                        token,
                        adminIdLong.toString(),
                        "✅ *Бот запущений!* (v${BuildConfig.VERSION_NAME})",
                        withKeyboard = true
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            var offset = preferences.lastTelegramUpdateId
            while (isActive) {
                val currentToken = preferences.telegramBotToken.trim()
                if (currentToken != token || !preferences.telegramEnabled || !preferences.telegramBotCommandsEnabled) {
                    break
                }

                try {
                    val updates = TelegramClient.getUpdates(currentToken, offset, timeoutSeconds = 15)
                    for (update in updates) {
                        offset = update.updateId + 1
                        preferences.lastTelegramUpdateId = offset

                        serviceScope.launch {
                            try {
                                handleIncomingTelegramMessage(currentToken, update)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                } catch (e: Exception) {
                    delay(3000)
                }
            }
        }
    }

    private fun isCommand(text: String, command: String): Boolean {
        val clean = text.trim()
        if (clean.equals("/$command", ignoreCase = true)) return true
        if (clean.startsWith("/$command@", ignoreCase = true)) return true
        if (clean.startsWith("/$command ", ignoreCase = true)) return true
        return false
    }

    private fun buildMainAdminMarkup(): org.json.JSONArray {
        return org.json.JSONArray().apply {
            put(org.json.JSONArray().apply {
                put(org.json.JSONObject().apply { put("text", "📊 Графік"); put("callback_data", "set_graph") })
            })
            put(org.json.JSONArray().apply {
                put(org.json.JSONObject().apply { put("text", "🔄 Оновлення"); put("callback_data", "exec_update") })
                put(org.json.JSONObject().apply { put("text", "↩️ Відкат"); put("callback_data", "exec_rollback") })
            })
            put(org.json.JSONArray().apply {
                put(org.json.JSONObject().apply { put("text", "🔃 Перезапуск"); put("callback_data", "exec_restart") })
            })
        }
    }

    private suspend fun handleIncomingTelegramMessage(botToken: String, update: TgUpdate) {
        val chatIdStr = update.chatId.toString()

        // --- A. Handle Callback Queries (Inline buttons from /set admin menu) ---
        if (update.callbackData != null) {
            val callData = update.callbackData
            val cbId = update.callbackQueryId ?: ""

            when {
                callData == "set_graph" -> {
                    val markup = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "✅ Увімкнути"); put("callback_data", "notify_on") })
                            put(org.json.JSONObject().apply { put("text", "❌ Вимкнути"); put("callback_data", "notify_off") })
                        })
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "⬅️ Назад"); put("callback_data", "back_to_main_set") })
                        })
                    }
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "Сповіщення про графік:", markup)
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                }

                callData == "notify_on" -> {
                    preferences.scheduleNotificationsEnabled = true
                    val markup = org.json.JSONArray()
                    val cities = OutageSources.CITIES.map { it.name }
                    for (i in cities.indices step 2) {
                        val row = org.json.JSONArray()
                        row.put(org.json.JSONObject().apply { put("text", cities[i]); put("callback_data", "city_${cities[i]}") })
                        if (i + 1 < cities.size) {
                            row.put(org.json.JSONObject().apply { put("text", cities[i + 1]); put("callback_data", "city_${cities[i + 1]}") })
                        }
                        markup.put(row)
                    }
                    markup.put(org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("text", "⬅️ Назад"); put("callback_data", "back_to_main_set") })
                    })
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "🏙️ *Оберіть місто:*", markup)
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                }

                callData == "notify_off" -> {
                    preferences.scheduleNotificationsEnabled = false
                    val markup = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "⬅️ Назад"); put("callback_data", "back_to_main_set") })
                        })
                    }
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "🔕 Сповіщення про графік *вимкнено*.", markup)
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                }

                callData.startsWith("city_") -> {
                    val city = callData.removePrefix("city_")
                    preferences.selectedCity = city
                    val source = OutageSources.getCitySource(city)
                    val queues = if (source != null) {
                        val json = ScheduleParser.fetchJson(source.jsonUrl)
                        if (json != null) {
                            preferences.cachedScheduleJson = json
                            ScheduleParser.extractQueues(json)
                        } else {
                            listOf("GPV1.1", "GPV1.2", "GPV2.1", "GPV2.2", "GPV3.1", "GPV3.2", "GPV4.1", "GPV4.2", "GPV5.1", "GPV5.2", "GPV6.1", "GPV6.2")
                        }
                    } else {
                        listOf("GPV1.1", "GPV1.2", "GPV2.1", "GPV2.2", "GPV3.1", "GPV3.2", "GPV4.1", "GPV4.2", "GPV5.1", "GPV5.2", "GPV6.1", "GPV6.2")
                    }

                    val markup = org.json.JSONArray()
                    for (i in queues.indices step 3) {
                        val row = org.json.JSONArray()
                        for (j in 0..2) {
                            if (i + j < queues.size) {
                                val q = queues[i + j]
                                val qLabel = q.replace("GPV", "").replace("черга", "").trim()
                                row.put(org.json.JSONObject().apply { put("text", qLabel); put("callback_data", "queue_$q") })
                            }
                        }
                        markup.put(row)
                    }
                    markup.put(org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("text", "⬅️ Назад до міст"); put("callback_data", "notify_on") })
                    })
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "🔢 *Черга для м. $city:*", markup)
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                }

                callData.startsWith("queue_") -> {
                    val queue = callData.removePrefix("queue_")
                    preferences.selectedQueue = queue
                    val cleanQueue = queue.replace("GPV", "").replace("черга", "").trim()
                    val markup = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "⬅️ В налаштування"); put("callback_data", "back_to_main_set") })
                        })
                    }
                    TelegramClient.editMessageText(
                        botToken,
                        update.chatId,
                        update.messageId,
                        "✅ *Збережено!*\n📍 ${preferences.selectedCity}, Черга: $cleanQueue",
                        markup
                    )
                    TelegramClient.answerCallbackQuery(botToken, cbId, "✅ Збережено!")
                }

                callData == "exec_update" -> {
                    val markup = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "🔍 Перевірити зараз"); put("callback_data", "manual_check_now") })
                        })
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "🤖 Бот"); put("callback_data", "upd_bot") })
                            put(org.json.JSONObject().apply { put("text", "🛫 Лаунчер"); put("callback_data", "upd_launcher") })
                        })
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "⬅️ Назад"); put("callback_data", "back_to_main_set") })
                        })
                    }
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "🔄 *Що оновити?*", markup)
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                }

                callData == "manual_check_now" -> {
                    TelegramClient.answerCallbackQuery(botToken, cbId, "🔍 Перевіряю GitHub...")
                    val markup = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "⬅️ Назад"); put("callback_data", "back_to_main_set") })
                        })
                    }
                    val currentVer = BuildConfig.VERSION_NAME
                    val releaseRes = GitHubUpdater.fetchLatestRelease()
                    val statusMsg = if (releaseRes.isSuccess) {
                        val rel = releaseRes.getOrThrow()
                        if (GitHubUpdater.isNewerVersion(rel.versionName, currentVer)) {
                            "🚀 *Доступне оновлення:* `${rel.tagName}` (поточна: `$currentVer`)\n" +
                                    "Оновіть додаток у меню *Налаштування -> Перевірити оновлення* або на [GitHub Releases](${rel.htmlUrl})."
                        } else {
                            "✅ *Версія актуальна:* `$currentVer`\nОстанній реліз на GitHub: `${rel.tagName}`."
                        }
                    } else {
                        "✅ *Поточна версія:* `$currentVer`\nНе вдалося перевірити GitHub."
                    }
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, statusMsg, markup)
                }

                callData == "upd_bot" || callData == "upd_launcher" -> {
                    TelegramClient.answerCallbackQuery(botToken, cbId, "Перевірте оновлення в Налаштуваннях додатку (v${BuildConfig.VERSION_NAME})", showAlert = true)
                }

                callData == "exec_rollback" -> {
                    val markup = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "⬅️ Назад"); put("callback_data", "back_to_main_set") })
                        })
                    }
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "↩️ *Відкат:*\nПоточна стабільна версія: `${BuildConfig.VERSION_NAME}`.", markup)
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                }

                callData == "exec_restart" -> {
                    TelegramClient.answerCallbackQuery(botToken, cbId, "♻️ Перезапуск...")
                    val markup = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply { put("text", "⬅️ Назад"); put("callback_data", "back_to_main_set") })
                        })
                    }
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "♻️ *PowerBot активний і працює на пристрої (v${BuildConfig.VERSION_NAME})*", markup)
                }

                callData == "back_to_main_set" -> {
                    TelegramClient.editMessageText(botToken, update.chatId, update.messageId, "🛠️ *Адмін-панель:*", buildMainAdminMarkup())
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                }

                callData == "cmd_status" -> {
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                    sendStatusReply(botToken, chatIdStr, forceInline = update.chatType == "channel")
                }

                callData == "cmd_stats" -> {
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                    sendStatsReply(botToken, chatIdStr, forceInline = update.chatType == "channel")
                }

                callData == "cmd_info" -> {
                    TelegramClient.answerCallbackQuery(botToken, cbId)
                    sendInfoReply(botToken, chatIdStr, forceInline = update.chatType == "channel")
                }
            }
            return
        }

        // --- B. Handle Text Messages & Channel Posts ---
        val text = update.text.trim()
        if (text.isBlank()) return
        val normalizedText = text.replace("\uFE0F", "").trim()
        val isChannel = update.chatType == "channel"

        when {
            normalizedText.contains("Перевірити наявність", ignoreCase = true) || isCommand(normalizedText, "status") -> {
                serviceScope.launch { TelegramClient.deleteMessage(botToken, update.chatId, update.messageId) }
                sendStatusReply(botToken, chatIdStr, forceInline = isChannel)
            }

            normalizedText.contains("Статистика", ignoreCase = true) || isCommand(normalizedText, "stats") -> {
                serviceScope.launch { TelegramClient.deleteMessage(botToken, update.chatId, update.messageId) }
                sendStatsReply(botToken, chatIdStr, forceInline = isChannel)
            }

            normalizedText.contains("Графік", ignoreCase = true) || isCommand(normalizedText, "graph") || isCommand(normalizedText, "schedule") -> {
                serviceScope.launch { TelegramClient.deleteMessage(botToken, update.chatId, update.messageId) }

                val city = preferences.selectedCity
                val queue = preferences.selectedQueue
                val source = OutageSources.getCitySource(city)
                val json = if (source != null) {
                    ScheduleParser.fetchJson(source.jsonUrl) ?: preferences.cachedScheduleJson
                } else {
                    preferences.cachedScheduleJson
                }

                val schedule = if (json.isNotBlank()) {
                    ScheduleParser.parseSchedule(json, city, queue)
                } else {
                    null
                }

                val scheduleText = if (schedule != null) {
                    ScheduleParser.formatScheduleText(schedule)
                } else {
                    "📅 Графік для $city ($queue) ще не завантажено або відсутній на поточну дату."
                }

                TelegramClient.sendMessage(
                    botToken = botToken,
                    chatId = chatIdStr,
                    text = scheduleText,
                    withKeyboard = true,
                    forceInlineForChannel = isChannel
                )
            }

            normalizedText.contains("Інфо", ignoreCase = true) || isCommand(normalizedText, "info") -> {
                serviceScope.launch { TelegramClient.deleteMessage(botToken, update.chatId, update.messageId) }
                sendInfoReply(botToken, chatIdStr, forceInline = isChannel)
            }

            isCommand(text, "set") -> {
                TelegramClient.deleteMessage(botToken, update.chatId, update.messageId)

                val adminIdStr = preferences.telegramAdminId.trim()
                val adminIdLong = adminIdStr.toLongOrNull() ?: 0L
                val isPrivate = update.chatType == "private" || update.chatId > 0
                val isAdmin = adminIdLong == 0L || update.fromUserId == adminIdLong || update.chatId == adminIdLong

                if (isPrivate && isAdmin) {
                    val markup = buildMainAdminMarkup()
                    TelegramClient.sendInlineMessage(botToken, chatIdStr, "🛠️ *Адмін-панель:*", markup)
                }
            }

            isCommand(text, "start") -> {
                val welcome = "🤖 *PowerBot підключено та працює!*\n\n" +
                        "📱 Пристрій моніторить живлення в реальному часі.\n" +
                        "Натисніть кнопку нижче або напишіть /status для перевірки наявності світла."

                TelegramClient.sendMessage(
                    botToken = botToken,
                    chatId = chatIdStr,
                    text = welcome,
                    withKeyboard = true,
                    forceInlineForChannel = isChannel
                )
            }
        }
    }

    private suspend fun sendStatusReply(botToken: String, chatIdStr: String, forceInline: Boolean) {
        val info = BatteryHelper.getBatteryInfo(this@PowerMonitorService)
        val statusText = if (info.isPlugged) "💡 *Світло є*" else "🕯️ *Світла немає*"
        val tempStr = String.format(Locale.US, "%.1f", info.temperatureCelsius)
        val response = "$statusText\n🔋: *${info.percent}%* | 🌡️: ~*${tempStr}°C*"

        TelegramClient.sendMessage(
            botToken = botToken,
            chatId = chatIdStr,
            text = response,
            withKeyboard = true,
            forceInlineForChannel = forceInline
        )
    }

    private suspend fun sendStatsReply(botToken: String, chatIdStr: String, forceInline: Boolean) {
        val nowMs = System.currentTimeMillis()
        val eightDaysAgoMs = nowMs - 8L * 24 * 60 * 60 * 1000L
        val recentEvents = database.powerEventDao().getEventsSince(eightDaysAgoMs)
        val ongoingOutageStart = if (preferences.lastPowerState == false) {
            maxOf(preferences.lastStateChangeTime, preferences.journalResetTimeMs)
        } else null

        val statsText = PowerStatsHelper.formatWeeklyStatsForTelegram(
            events = recentEvents,
            nowMs = nowMs,
            ongoingOutageStartMs = ongoingOutageStart
        )

        TelegramClient.sendMessage(
            botToken = botToken,
            chatId = chatIdStr,
            text = statsText,
            withKeyboard = true,
            forceInlineForChannel = forceInline
        )
    }

    private suspend fun sendInfoReply(botToken: String, chatIdStr: String, forceInline: Boolean) {
        val infoText = "ℹ️ *Інформація:*\n\n" +
                "🤖 *PowerBot (v${BuildConfig.VERSION_NAME})*\n" +
                "🤝 Особлива подяка автору [yaroslav2901](https://github.com/yaroslav2901/OE_OUTAGE_DATA.git) " +
                "за парсер графіків відключень.\n\n" +
                "🔗 [GitHub](https://github.com/Bombin1/PowerBotApp) | ☕ [На каву](https://send.monobank.ua/jar/8WFAPWLdPu)"

        TelegramClient.sendMessage(
            botToken = botToken,
            chatId = chatIdStr,
            text = infoText,
            withKeyboard = true,
            forceInlineForChannel = forceInline
        )
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
                                when (val parseResult = ScheduleParser.parseScheduleJson(json, city, queue)) {
                                    is com.example.data.model.ScheduleParseResult.Success -> {
                                        val newSnapshot = parseResult.snapshot
                                        val oldSnapshot = preferences.lastScheduleSnapshot
                                        val changeResult = ScheduleParser.detectScheduleChanges(oldSnapshot, newSnapshot)

                                        // Persist new snapshot
                                        ScheduleParser.saveScheduleSnapshot(preferences, newSnapshot)

                                        if (changeResult.isChanged) {
                                            val formatted = ScheduleParser.formatScheduleText(parseResult.schedule)
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
                                    is com.example.data.model.ScheduleParseResult.Failure -> {
                                        // Do not notify on incomplete or erroneous data (Section 13)
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
