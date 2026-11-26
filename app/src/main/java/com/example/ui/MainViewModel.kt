package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.model.BatteryInfo
import com.example.data.model.CitySchedule
import com.example.data.model.HourSlot
import com.example.data.model.OutageStatus
import com.example.data.model.PowerEvent
import com.example.data.model.ScheduleParseResult
import com.example.data.model.SelectedScheduleSlot
import com.example.data.remote.OutageSources
import com.example.data.remote.ScheduleParser
import com.example.data.remote.TelegramClient
import com.example.service.PowerMonitorService
import com.example.util.BatteryHelper
import com.example.util.GitHubRelease
import com.example.util.GitHubUpdater
import com.example.util.MonthKey
import com.example.util.MonthStats
import com.example.util.PowerStatsHelper
import com.example.util.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val preferences = AppPreferences(application)
    private val database = AppDatabase.getDatabase(application)
    private val dao = database.powerEventDao()

    // Battery & Hardware Power
    private val _batteryInfo = MutableStateFlow(BatteryHelper.getBatteryInfo(application))
    val batteryInfo: StateFlow<BatteryInfo> = _batteryInfo.asStateFlow()

    private val _stateDurationMinutes = MutableStateFlow(0L)
    val stateDurationMinutes: StateFlow<Long> = _stateDurationMinutes.asStateFlow()

    // Service & Telegram reactive state
    private val _isSentinelActive = MutableStateFlow(preferences.backgroundServiceEnabled)
    val isSentinelActive: StateFlow<Boolean> = _isSentinelActive.asStateFlow()

    private val _isTelegramEnabled = MutableStateFlow(preferences.telegramEnabled)
    val isTelegramEnabled: StateFlow<Boolean> = _isTelegramEnabled.asStateFlow()

    // Outage Schedule (supports up to 5 independent city+queue cards)
    private val _selectedCity = MutableStateFlow(preferences.selectedCity)
    val selectedCity: StateFlow<String> = _selectedCity.asStateFlow()

    private val _selectedSlots = MutableStateFlow(preferences.selectedScheduleSlots)
    val selectedSlots: StateFlow<List<SelectedScheduleSlot>> = _selectedSlots.asStateFlow()

    private val _selectedQueues = MutableStateFlow(preferences.selectedScheduleSlots.map { it.queue })
    val selectedQueues: StateFlow<List<String>> = _selectedQueues.asStateFlow()

    private val _selectedQueue = MutableStateFlow(
        preferences.selectedScheduleSlots.firstOrNull()?.queue ?: preferences.selectedQueue
    )
    val selectedQueue: StateFlow<String> = _selectedQueue.asStateFlow()

    private val _availableQueues = MutableStateFlow<List<String>>(emptyList())
    val availableQueues: StateFlow<List<String>> = _availableQueues.asStateFlow()

    private val _cityQueuesCache = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val cityQueuesCache: StateFlow<Map<String, List<String>>> = _cityQueuesCache.asStateFlow()

    private val _isLoadingCityQueues = MutableStateFlow(false)
    val isLoadingCityQueues: StateFlow<Boolean> = _isLoadingCityQueues.asStateFlow()

    private val _schedulesBySlotKey = MutableStateFlow<Map<String, CitySchedule>>(emptyMap())
    val schedulesBySlotKey: StateFlow<Map<String, CitySchedule>> = _schedulesBySlotKey.asStateFlow()

    private val _slotErrorsByKey = MutableStateFlow<Map<String, String>>(emptyMap())
    val slotErrorsByKey: StateFlow<Map<String, String>> = _slotErrorsByKey.asStateFlow()

    private val _schedules = MutableStateFlow<List<CitySchedule>>(emptyList())
    val schedules: StateFlow<List<CitySchedule>> = _schedules.asStateFlow()

    private val _schedule = MutableStateFlow<CitySchedule?>(null)
    val schedule: StateFlow<CitySchedule?> = _schedule.asStateFlow()

    private val _intervalEnabledSlotKeys = MutableStateFlow(preferences.intervalEnabledSlotKeys)
    val intervalEnabledSlotKeys: StateFlow<Set<String>> = _intervalEnabledSlotKeys.asStateFlow()

    private val _queueLimitMessage = MutableStateFlow<String?>(null)
    val queueLimitMessage: StateFlow<String?> = _queueLimitMessage.asStateFlow()

    private val _isLoadingSchedule = MutableStateFlow(false)
    val isLoadingSchedule: StateFlow<Boolean> = _isLoadingSchedule.asStateFlow()

    private val _scheduleError = MutableStateFlow<String?>(null)
    val scheduleError: StateFlow<String?> = _scheduleError.asStateFlow()

    private val _refreshFeedback = MutableStateFlow<String?>(null)
    val refreshFeedback: StateFlow<String?> = _refreshFeedback.asStateFlow()

    private var refreshJob: Job? = null
    private var feedbackClearJob: Job? = null
    private val refreshRequestId = java.util.concurrent.atomic.AtomicLong(0L)

    private val cityJsonCache = ConcurrentHashMap<String, String>()

    // History
    val historyEvents: StateFlow<List<PowerEvent>> = dao.getAllEventsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Telegram Testing State
    private val _telegramTestStatus = MutableStateFlow<String?>(null)
    val telegramTestStatus: StateFlow<String?> = _telegramTestStatus.asStateFlow()

    private val _isTestingTelegram = MutableStateFlow(false)
    val isTestingTelegram: StateFlow<Boolean> = _isTestingTelegram.asStateFlow()

    // GitHub App Update State
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

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

        // Load schedules on startup
        refreshSchedule()

        // Periodically check GitHub for schedule updates once per hour while app is active
        viewModelScope.launch {
            while (isActive) {
                delay(60 * 60 * 1000L)
                refreshSchedule()
            }
        }
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
        _queueLimitMessage.value = null

        val currentSlots = _selectedSlots.value
        if (currentSlots.size == 1) {
            val oldSlot = currentSlots.first()
            val newSlot = SelectedScheduleSlot(city = city, queue = oldSlot.queue)
            val wasIntervalEnabled = oldSlot.slotKey in _intervalEnabledSlotKeys.value
            val updatedIntervals = if (wasIntervalEnabled) {
                (_intervalEnabledSlotKeys.value - oldSlot.slotKey) + newSlot.slotKey
            } else {
                _intervalEnabledSlotKeys.value - oldSlot.slotKey
            }
            updateSlotsState(listOf(newSlot), updatedIntervals)
        }

        refreshSchedule()
    }

    fun setQueue(queue: String) {
        val slot = SelectedScheduleSlot(_selectedCity.value, queue)
        _queueLimitMessage.value = null
        updateSlotsState(listOf(slot), setOf(slot.slotKey))
        parseSlotsFromCacheOrNetwork(listOf(slot))
    }

    fun toggleQueue(queue: String) {
        val city = _selectedCity.value
        val slot = SelectedScheduleSlot(city, queue)
        val current = _selectedSlots.value
        _queueLimitMessage.value = null

        if (slot in current) {
            if (current.size <= 1) {
                return
            }
            deleteScheduleSlot(slot)
            return
        }

        if (current.size >= MAX_SELECTED_QUEUES) {
            _queueLimitMessage.value = "Можна обрати до $MAX_SELECTED_QUEUES графіків одночасно"
            return
        }

        val updated = current + slot
        val updatedIntervals = _intervalEnabledSlotKeys.value + slot.slotKey
        updateSlotsState(updated, updatedIntervals)
        parseSlotsFromCacheOrNetwork(updated)
    }

    fun updateScheduleSlot(oldSlot: SelectedScheduleSlot, newCity: String, newQueue: String) {
        val newSlot = SelectedScheduleSlot(city = newCity.trim(), queue = newQueue.trim())
        if (newSlot.city.isBlank() || newSlot.queue.isBlank()) return
        if (oldSlot == newSlot) return

        _queueLimitMessage.value = null
        val current = _selectedSlots.value
        val oldIndex = current.indexOf(oldSlot)
        if (oldIndex == -1) return

        val wasIntervalEnabled = oldSlot.slotKey in _intervalEnabledSlotKeys.value
        val updatedIntervals = (_intervalEnabledSlotKeys.value - oldSlot.slotKey).toMutableSet()

        val updatedSlots = if (newSlot in current) {
            // Target already exists; remove oldSlot and keep existing target
            if (wasIntervalEnabled) updatedIntervals.add(newSlot.slotKey)
            current.filterNot { it == oldSlot }
        } else {
            if (wasIntervalEnabled) updatedIntervals.add(newSlot.slotKey)
            current.toMutableList().apply { set(oldIndex, newSlot) }
        }

        preferences.setSlotScheduleSnapshot(oldSlot.slotKey, null)
        updateSlotsState(updatedSlots, updatedIntervals)
        parseSlotsFromCacheOrNetwork(updatedSlots)
    }

    fun deleteScheduleSlot(slot: SelectedScheduleSlot) {
        _queueLimitMessage.value = null
        val current = _selectedSlots.value
        if (slot !in current) return

        preferences.setSlotScheduleSnapshot(slot.slotKey, null)
        val updatedSlots = current - slot
        val updatedIntervals = _intervalEnabledSlotKeys.value - slot.slotKey
        updateSlotsState(updatedSlots, updatedIntervals)

        val nextMap = _schedulesBySlotKey.value.toMutableMap().apply { remove(slot.slotKey) }
        _schedulesBySlotKey.value = nextMap
        val nextErrors = _slotErrorsByKey.value.toMutableMap().apply { remove(slot.slotKey) }
        _slotErrorsByKey.value = nextErrors

        _schedules.value = updatedSlots.mapNotNull { nextMap[it.slotKey] }
        _schedule.value = _schedules.value.firstOrNull()
    }

    fun toggleIntervalSwitch(slotKey: String, enabled: Boolean) {
        val current = _intervalEnabledSlotKeys.value
        val updated = if (enabled) current + slotKey else current - slotKey
        _intervalEnabledSlotKeys.value = updated
        preferences.intervalEnabledSlotKeys = updated
    }

    fun clearQueueLimitMessage() {
        _queueLimitMessage.value = null
    }

    fun loadQueuesForCity(city: String) {
        if (_cityQueuesCache.value.containsKey(city)) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingCityQueues.value = true
            val json = getOrFetchCityJson(city, forceNetwork = false)
            if (json != null) {
                val queues = ScheduleParser.extractQueues(json)
                if (queues.isNotEmpty()) {
                    _cityQueuesCache.value = _cityQueuesCache.value + (city to queues)
                    if (city == _selectedCity.value) {
                        _availableQueues.value = queues
                    }
                }
            }
            _isLoadingCityQueues.value = false
        }
    }

    private fun updateSlotsState(
        slots: List<SelectedScheduleSlot>,
        enabledIntervalKeys: Set<String>
    ) {
        _selectedSlots.value = slots
        _selectedQueues.value = slots.map { it.queue }
        slots.firstOrNull()?.let { primary ->
            _selectedQueue.value = primary.queue
        }
        preferences.selectedScheduleSlots = slots

        val validKeys = slots.map { it.slotKey }.toSet()
        val sanitizedIntervals = enabledIntervalKeys.filter { it in validKeys }.toSet()
        _intervalEnabledSlotKeys.value = sanitizedIntervals
        preferences.intervalEnabledSlotKeys = sanitizedIntervals
    }

    private fun parseSlotsFromCacheOrNetwork(slots: List<SelectedScheduleSlot>) {
        viewModelScope.launch(Dispatchers.IO) {
            val missingCities = slots.map { it.city }.distinct().filter { city ->
                !cityJsonCache.containsKey(city) && preferences.getCachedCityJson(city).isNullOrBlank()
            }
            if (missingCities.isNotEmpty()) {
                _isLoadingSchedule.value = true
                coroutineScope {
                    missingCities.map { city ->
                        async { getOrFetchCityJson(city, forceNetwork = true) }
                    }.awaitAll()
                }
            }
            emitParsedSlots(slots)
            _isLoadingSchedule.value = false
        }
    }

    fun clearRefreshFeedback() {
        feedbackClearJob?.cancel()
        _refreshFeedback.value = null
    }

    private fun showTransientRefreshFeedback(message: String) {
        feedbackClearJob?.cancel()
        _refreshFeedback.value = message
        feedbackClearJob = viewModelScope.launch {
            delay(4000L)
            _refreshFeedback.value = null
        }
    }

    fun refreshSchedule(userInitiated: Boolean = false) {
        val currentRequestId = refreshRequestId.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoadingSchedule.value = true
            _scheduleError.value = null
            if (userInitiated) {
                _refreshFeedback.value = null
            }

            try {
                val slots = _selectedSlots.value
                val citiesToFetch = (listOf(_selectedCity.value) + slots.map { it.city }).distinct()

                val results = coroutineScope {
                    citiesToFetch.map { city ->
                        async { city to fetchAndCacheCityJsonFromNetwork(city) }
                    }.awaitAll()
                }

                emitParsedSlots(_selectedSlots.value)

                if (userInitiated && refreshRequestId.get() == currentRequestId) {
                    if (preferences.backgroundServiceEnabled && preferences.scheduleNotificationsEnabled) {
                        PowerMonitorService.triggerScheduleCheck(getApplication())
                    }
                    val failedCities = results.filter { it.second == null }.map { it.first }
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    if (failedCities.isEmpty()) {
                        showTransientRefreshFeedback("✅ Графіки оновлено з GitHub о $timeStr")
                    } else {
                        showTransientRefreshFeedback(
                            "⚠️ Не вдалося оновити з GitHub (${failedCities.joinToString(", ")})"
                        )
                    }
                }
            } finally {
                if (refreshRequestId.get() == currentRequestId) {
                    _isLoadingSchedule.value = false
                }
            }
        }
    }

    private fun fetchAndCacheCityJsonFromNetwork(city: String): String? {
        val source = OutageSources.getCitySource(city) ?: return null
        val fetched = ScheduleParser.fetchJson(source.jsonUrl, bypassCache = true)
        if (fetched != null) {
            cityJsonCache[city] = fetched
            preferences.setCachedCityJson(city, fetched)
        }
        return fetched
    }

    private fun getOrFetchCityJson(city: String, forceNetwork: Boolean): String? {
        if (!forceNetwork) {
            cityJsonCache[city]?.let { return it }
            preferences.getCachedCityJson(city)?.let {
                cityJsonCache[city] = it
                return it
            }
        }

        val fetched = fetchAndCacheCityJsonFromNetwork(city)
        return fetched ?: cityJsonCache[city] ?: preferences.getCachedCityJson(city)
    }

    private fun emitParsedSlots(requestedSlots: List<SelectedScheduleSlot>) {
        val queuesMap = _cityQueuesCache.value.toMutableMap()

        // Ensure availableQueues is populated for the top selectedCity
        val topCityJson = cityJsonCache[_selectedCity.value] ?: preferences.getCachedCityJson(_selectedCity.value)
        if (topCityJson != null) {
            val topQueues = ScheduleParser.extractQueues(topCityJson)
            if (topQueues.isNotEmpty()) {
                queuesMap[_selectedCity.value] = topQueues
                _availableQueues.value = topQueues
            }
        }

        // Normalize slots against extracted queues for each city
        val normalizedSlots = mutableListOf<SelectedScheduleSlot>()
        val normalizedIntervals = _intervalEnabledSlotKeys.value.toMutableSet()

        for (slot in requestedSlots) {
            val cityJson = cityJsonCache[slot.city] ?: preferences.getCachedCityJson(slot.city)
            val available = if (cityJson != null) {
                ScheduleParser.extractQueues(cityJson).also {
                    if (it.isNotEmpty()) queuesMap[slot.city] = it
                }
            } else {
                emptyList()
            }

            val resolvedQueue = if (available.isNotEmpty() && !available.contains(slot.queue)) {
                available.first()
            } else {
                slot.queue
            }
            val resolvedSlot = SelectedScheduleSlot(slot.city, resolvedQueue)
            if (resolvedSlot !in normalizedSlots) {
                normalizedSlots.add(resolvedSlot)
                if (slot.slotKey != resolvedSlot.slotKey && slot.slotKey in normalizedIntervals) {
                    normalizedIntervals.remove(slot.slotKey)
                    normalizedIntervals.add(resolvedSlot.slotKey)
                }
            }
        }

        _cityQueuesCache.value = queuesMap

        if (normalizedSlots != requestedSlots) {
            updateSlotsState(normalizedSlots, normalizedIntervals)
        }

        val parsedMap = linkedMapOf<String, CitySchedule>()
        val errorsMap = linkedMapOf<String, String>()
        var firstError: String? = null

        normalizedSlots.forEach { slot ->
            val cityJson = cityJsonCache[slot.city] ?: preferences.getCachedCityJson(slot.city)
            if (cityJson == null) {
                val err = "Не вдалося завантажити графік для ${slot.city}."
                errorsMap[slot.slotKey] = err
                if (firstError == null) firstError = err
            } else {
                when (val result = ScheduleParser.parseScheduleJson(cityJson, slot.city, slot.queue)) {
                    is ScheduleParseResult.Success -> {
                        parsedMap[slot.slotKey] = result.schedule
                    }
                    is ScheduleParseResult.Failure -> {
                        errorsMap[slot.slotKey] = result.errorMessage
                        if (firstError == null) firstError = result.errorMessage
                    }
                }
            }
        }

        _schedulesBySlotKey.value = parsedMap
        _slotErrorsByKey.value = errorsMap
        val orderedSchedules = normalizedSlots.mapNotNull { parsedMap[it.slotKey] }
        _schedules.value = orderedSchedules
        _schedule.value = orderedSchedules.firstOrNull()
        _scheduleError.value = if (normalizedSlots.isEmpty()) null else firstError
    }

    fun getCurrentHourSlot(): HourSlot? {
        val currentSchedule = _schedule.value ?: return null
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY) // 0..23
        val slotIndex = currentHour
        return currentSchedule.hours.getOrNull(slotIndex)
    }

    fun getMinutesUntilNextScheduleEvent(): Int? {
        val currentSchedule = _schedule.value ?: return null
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY) // 0..23
        val currentMinute = cal.get(Calendar.MINUTE)

        val currentSlot = currentSchedule.hours.getOrNull(currentHour) ?: return null
        val currentStatus = currentSlot.status

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

    fun setTelegramEnabled(enabled: Boolean) {
        preferences.telegramEnabled = enabled
        _isTelegramEnabled.value = enabled
        refreshTelegramPolling()
    }

    fun toggleBackgroundService(enabled: Boolean) {
        preferences.backgroundServiceEnabled = enabled
        _isSentinelActive.value = enabled
        if (enabled) {
            PowerMonitorService.startService(getApplication())
        } else {
            PowerMonitorService.stopService(getApplication())
        }
    }

    fun refreshTelegramPolling() {
        if (preferences.backgroundServiceEnabled) {
            PowerMonitorService.startService(getApplication())
        }
    }

    companion object {
        const val MAX_SELECTED_QUEUES = 5
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.journalResetTimeMs = System.currentTimeMillis()
            dao.clearAll()
        }
    }

    fun calculateMonthStats(events: List<PowerEvent>, monthKey: MonthKey): MonthStats {
        val powerState = preferences.lastPowerState
        val stateStart = maxOf(preferences.lastStateChangeTime, preferences.journalResetTimeMs)
        return PowerStatsHelper.calculateMonthStats(
            events = events,
            monthKey = monthKey,
            nowMs = System.currentTimeMillis(),
            ongoingPowerState = powerState,
            ongoingStateStartMs = if (powerState != null) stateStart else null
        )
    }

    fun checkForUpdates() {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            val currentVersion = BuildConfig.VERSION_NAME
            val result = GitHubUpdater.fetchLatestRelease()
            _updateState.value = result.fold(
                onSuccess = { release ->
                    if (GitHubUpdater.isNewerVersion(release.versionName, currentVersion)) {
                        UpdateState.UpdateAvailable(release)
                    } else {
                        UpdateState.UpToDate(currentVersion, release.tagName)
                    }
                },
                onFailure = { err ->
                    UpdateState.Error(err.message ?: "Помилка перевірки оновлень")
                }
            )
        }
    }

    fun downloadAndInstallUpdate(release: GitHubRelease) {
        val apkUrl = release.apkDownloadUrl
        if (apkUrl.isNullOrBlank()) {
            _updateState.value = UpdateState.Error("У цьому релізі на GitHub немає прикріпленого APK-файлу.")
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(release, 0)
            val context = getApplication<android.app.Application>()
            val fileName = release.apkFileName ?: "PowerBot-${release.versionName}.apk"
            val result = GitHubUpdater.downloadApk(
                context = context,
                downloadUrl = apkUrl,
                fileName = fileName,
                onProgress = { pct ->
                    _updateState.value = UpdateState.Downloading(release, pct)
                }
            )
            _updateState.value = result.fold(
                onSuccess = { file ->
                    if (GitHubUpdater.canRequestPackageInstalls(context)) {
                        UpdateState.ReadyToInstall(file, release)
                    } else {
                        UpdateState.NeedsInstallPermission(file, release)
                    }
                },
                onFailure = { err ->
                    UpdateState.Error(err.message ?: "Не вдалося завантажити оновлення")
                }
            )
        }
    }

    fun onInstallPermissionReturned() {
        val current = _updateState.value
        if (current is UpdateState.NeedsInstallPermission) {
            val context = getApplication<android.app.Application>()
            if (GitHubUpdater.canRequestPackageInstalls(context)) {
                _updateState.value = UpdateState.ReadyToInstall(current.apkFile, current.release)
            }
        }
    }

    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }
}
