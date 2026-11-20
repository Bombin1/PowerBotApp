package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.model.BatteryInfo
import com.example.data.model.CitySchedule
import com.example.data.model.HourSlot
import com.example.data.model.OutageStatus
import com.example.data.model.PowerEvent
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import com.example.data.remote.TelegramClient
import com.example.service.PowerMonitorService
import com.example.util.BatteryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val preferences = AppPreferences(application)
    private val database = AppDatabase.getDatabase(application)
    private val dao = database.powerEventDao()

    // Battery & Hardware Power
    private val _batteryInfo = MutableStateFlow(BatteryHelper.getBatteryInfo(application))
    val batteryInfo: StateFlow<BatteryInfo> = _batteryInfo.asStateFlow()

    private val _stateDurationMinutes = MutableStateFlow(0L)
    val stateDurationMinutes: StateFlow<Long> = _stateDurationMinutes.asStateFlow()

    // Outage Schedule
    private val _selectedCity = MutableStateFlow(preferences.selectedCity)
    val selectedCity: StateFlow<String> = _selectedCity.asStateFlow()

    private val _selectedQueue = MutableStateFlow(preferences.selectedQueue)
    val selectedQueue: StateFlow<String> = _selectedQueue.asStateFlow()

    private val _availableQueues = MutableStateFlow<List<String>>(emptyList())
    val availableQueues: StateFlow<List<String>> = _availableQueues.asStateFlow()

    private val _schedule = MutableStateFlow<CitySchedule?>(null)
    val schedule: StateFlow<CitySchedule?> = _schedule.asStateFlow()

    private val _isLoadingSchedule = MutableStateFlow(false)
    val isLoadingSchedule: StateFlow<Boolean> = _isLoadingSchedule.asStateFlow()

    private val _scheduleError = MutableStateFlow<String?>(null)
    val scheduleError: StateFlow<String?> = _scheduleError.asStateFlow()

    // History
    val historyEvents: StateFlow<List<PowerEvent>> = dao.getAllEventsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Telegram Testing State
    private val _telegramTestStatus = MutableStateFlow<String?>(null)
    val telegramTestStatus: StateFlow<String?> = _telegramTestStatus.asStateFlow()

    private val _isTestingTelegram = MutableStateFlow(false)
    val isTestingTelegram: StateFlow<Boolean> = _isTestingTelegram.asStateFlow()

    init {
        // Start live ticker to update battery info and duration
        viewModelScope.launch {
            while (isActive) {
                updateBatteryAndDuration()
                delay(3000)
            }
        }

        // Listen to service state changes
        viewModelScope.launch {
            PowerMonitorService.stateChangeEvents.collect {
                updateBatteryAndDuration()
            }
        }

        // Load schedule on startup
        loadSchedule(_selectedCity.value, _selectedQueue.value)
    }

    fun updateBatteryAndDuration() {
        val info = BatteryHelper.getBatteryInfo(getApplication())
        _batteryInfo.value = info

        val lastChange = preferences.lastStateChangeTime
        val diffMs = System.currentTimeMillis() - lastChange
        _stateDurationMinutes.value = (diffMs / (1000 * 60)).coerceAtLeast(0)
    }

    fun setCity(city: String) {
        _selectedCity.value = city
        preferences.selectedCity = city
        loadSchedule(city, _selectedQueue.value)
    }

    fun setQueue(queue: String) {
        _selectedQueue.value = queue
        preferences.selectedQueue = queue
        loadSchedule(_selectedCity.value, queue)
    }

    fun loadSchedule(city: String, queue: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingSchedule.value = true
            _scheduleError.value = null

            val source = OutageSources.getCitySource(city)
            if (source == null) {
                _scheduleError.value = "Джерело для міста $city не знайдено"
                _isLoadingSchedule.value = false
                return@launch
            }

            var json = ScheduleParser.fetchJson(source.jsonUrl)
            if (json == null) {
                // Check if we have cached JSON
                json = preferences.cachedScheduleJson.ifEmpty { null }
            }

            if (json != null) {
                preferences.cachedScheduleJson = json
                val queues = ScheduleParser.extractQueues(json)
                _availableQueues.value = queues

                // Check if current queue is in available queues, else pick first
                val activeQueue = if (queues.contains(queue)) queue else (queues.firstOrNull() ?: queue)
                if (activeQueue != queue) {
                    _selectedQueue.value = activeQueue
                    preferences.selectedQueue = activeQueue
                }

                when (val result = ScheduleParser.parseScheduleJson(json, city, activeQueue)) {
                    is com.example.data.model.ScheduleParseResult.Success -> {
                        _schedule.value = result.schedule
                        _scheduleError.value = null
                        ScheduleParser.saveScheduleSnapshot(preferences, result.snapshot)
                    }
                    is com.example.data.model.ScheduleParseResult.Failure -> {
                        _schedule.value = null
                        _scheduleError.value = result.errorMessage
                    }
                }
            } else {
                _schedule.value = null
                _scheduleError.value = "Не вдалося завантажити графік."
            }

            _isLoadingSchedule.value = false
        }
    }

    fun refreshSchedule() {
        loadSchedule(_selectedCity.value, _selectedQueue.value)
    }

    fun getCurrentHourSlot(): HourSlot? {
        val currentSchedule = _schedule.value ?: return null
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY) // 0..23
        val slotIndex = currentHour // hour 1 is 00:00-01:00, so index currentHour is slot hour (currentHour + 1)
        return currentSchedule.hours.getOrNull(slotIndex)
    }

    fun getMinutesUntilNextScheduleEvent(): Int? {
        val currentSchedule = _schedule.value ?: return null
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY) // 0..23
        val currentMinute = cal.get(Calendar.MINUTE)

        val currentSlot = currentSchedule.hours.getOrNull(currentHour) ?: return null
        val currentStatus = currentSlot.status

        // Find next hour with different status
        for (h in (currentHour + 1) until 24) {
            val slot = currentSchedule.hours[h]
            if (slot.status != currentStatus) {
                val hoursDiff = h - currentHour
                return (hoursDiff * 60) - currentMinute
            }
        }
        return (24 - currentHour) * 60 - currentMinute
    }

    fun testTelegramConnection(botToken: String, chatId: String) {
        viewModelScope.launch {
            _isTestingTelegram.value = true
            _telegramTestStatus.value = "Перевірка зв'язку з серверами Telegram..."
            val result = TelegramClient.testConnection(botToken, chatId)
            _isTestingTelegram.value = false
            _telegramTestStatus.value = if (result.isSuccess) {
                "✅ " + result.getOrNull()
            } else {
                "❌ Помилка: " + (result.exceptionOrNull()?.message ?: "Не вдалося з'єднатися")
            }
        }
    }

    fun clearTelegramStatus() {
        _telegramTestStatus.value = null
    }

    fun toggleBackgroundService(enabled: Boolean) {
        preferences.backgroundServiceEnabled = enabled
        if (enabled) {
            PowerMonitorService.startService(getApplication())
        } else {
            PowerMonitorService.stopService(getApplication())
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
        }
    }

    fun calculateTodayStats(events: List<PowerEvent>): Pair<Float, Float> {
        // returns Pair(hoursWithPower, hoursWithoutPower)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis

        var outageMinutes = 0L
        for (event in events) {
            if (event.timestamp >= startOfDay && event.eventType == "POWER_CONNECTED") {
                outageMinutes += event.durationMinutes
            }
        }

        val outageHours = (outageMinutes / 60f).coerceAtMost(24f)
        val onlineHours = (24f - outageHours).coerceAtLeast(0f)
        return Pair(onlineHours, outageHours)
    }
}
