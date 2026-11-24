package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CitySchedule
import com.example.data.model.OutageInterval
import com.example.data.model.OutageStatus
import com.example.data.model.SelectedScheduleSlot
import com.example.data.remote.OutageSources
import com.example.ui.MainViewModel
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LightAmberMaybe
import com.example.ui.theme.LightGreenOnline
import com.example.ui.theme.LightRedOutage
import com.example.util.ScheduleCombiner
import java.util.Calendar

private val DEFAULT_FALLBACK_QUEUES = listOf(
    "GPV1.1", "GPV1.2", "GPV2.1", "GPV2.2", "GPV3.1", "GPV3.2",
    "GPV4.1", "GPV4.2", "GPV5.1", "GPV5.2", "GPV6.1", "GPV6.2"
)

@Composable
fun ScheduleScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val selectedCity by viewModel.selectedCity.collectAsState()
    val selectedSlots by viewModel.selectedSlots.collectAsState()
    val availableQueues by viewModel.availableQueues.collectAsState()
    val cityQueuesCache by viewModel.cityQueuesCache.collectAsState()
    val isLoadingCityQueues by viewModel.isLoadingCityQueues.collectAsState()
    val schedulesBySlotKey by viewModel.schedulesBySlotKey.collectAsState()
    val intervalEnabledSlotKeys by viewModel.intervalEnabledSlotKeys.collectAsState()
    val queueLimitMessage by viewModel.queueLimitMessage.collectAsState()
    val isLoading by viewModel.isLoadingSchedule.collectAsState()
    val errorMessage by viewModel.scheduleError.collectAsState()
    val refreshFeedback by viewModel.refreshFeedback.collectAsState()

    var cityDropdownExpanded by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<SelectedScheduleSlot?>(null) }

    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    val activeIntervalSchedules = remember(selectedSlots, schedulesBySlotKey, intervalEnabledSlotKeys) {
        selectedSlots
            .filter { it.slotKey in intervalEnabledSlotKeys }
            .mapNotNull { schedulesBySlotKey[it.slotKey] }
    }
    val combinedIntervals = remember(activeIntervalSchedules) {
        ScheduleCombiner.combineSchedules(activeIntervalSchedules)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("schedule_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. CITY & MULTI-QUEUE SELECTOR (up to 5 cards)
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("city_selector_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Регіон та обленерго",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        IconButton(
                            onClick = { viewModel.refreshSchedule(userInitiated = true) },
                            enabled = !isLoading,
                            modifier = Modifier.testTag("refresh_schedule_button")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Оновити графіки з GitHub")
                            }
                        }
                    }

                    if (refreshFeedback != null) {
                        val isWarning = refreshFeedback!!.startsWith("⚠️")
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.clearRefreshFeedback() }
                                .testTag("refresh_feedback_banner"),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isWarning) {
                                LightAmberMaybe.copy(alpha = 0.16f)
                            } else {
                                LightGreenOnline.copy(alpha = 0.14f)
                            }
                        ) {
                            Text(
                                text = refreshFeedback ?: "",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Box {
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { cityDropdownExpanded = true }
                                .testTag("city_dropdown_trigger"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = selectedCity,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        )
                                        val obl = OutageSources.getCitySource(selectedCity)?.oblenergo ?: ""
                                        Text(
                                            text = obl,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Вибрати місто",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = cityDropdownExpanded,
                            onDismissRequest = { cityDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            OutageSources.CITIES.forEach { citySource ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(citySource.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                citySource.oblenergo,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setCity(citySource.name)
                                        cityDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // MULTI-QUEUE SELECTOR HEADER
                    Text(
                        text = "Черги відключень (${selectedSlots.size}/${MainViewModel.MAX_SELECTED_QUEUES}):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val queuesList = if (availableQueues.isNotEmpty()) availableQueues else DEFAULT_FALLBACK_QUEUES

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        queuesList.forEach { q ->
                            val cleanLabel = ScheduleCombiner.cleanQueueLabel(q)
                            val isSelected = SelectedScheduleSlot(selectedCity, q) in selectedSlots
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.toggleQueue(q) },
                                modifier = Modifier.testTag("queue_chip_$q"),
                                label = {
                                    Text(
                                        text = "Черга $cleanLabel",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    if (queueLimitMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.clearQueueLimitMessage() },
                            colors = CardDefaults.cardColors(containerColor = LightAmberMaybe.copy(alpha = 0.16f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = LightAmberMaybe,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = queueLimitMessage ?: "",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. INDIVIDUAL "Графік на добу" CARDS (one per selected city+queue)
        if (selectedSlots.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("no_schedules_selected_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Немає вибраних графіків. Оберіть чергу вище, щоб додати плашку «Графік на добу».",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(selectedSlots, key = { it.slotKey }) { slot ->
                val queueSchedule = schedulesBySlotKey[slot.slotKey]
                val isIntervalsEnabled = slot.slotKey in intervalEnabledSlotKeys

                DailyScheduleTimelineCard(
                    slot = slot,
                    schedule = queueSchedule,
                    currentHour = currentHour,
                    isLoading = isLoading,
                    isIntervalsEnabled = isIntervalsEnabled,
                    onToggleIntervals = { enabled ->
                        viewModel.toggleIntervalSwitch(slot.slotKey, enabled)
                    },
                    onEditClick = { editingSlot = slot },
                    onDeleteClick = { viewModel.deleteScheduleSlot(slot) }
                )
            }
        }

        // Error message if any
        if (errorMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LightAmberMaybe.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = LightAmberMaybe)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(errorMessage ?: "", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. COMBINED "Розклад за інтервалами" SECTION
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Розклад за інтервалами",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                if (combinedIntervals.activeQueueLabels.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ElectricBlue.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = if (combinedIntervals.activeQueueLabels.size == 1) {
                                "Черга ${combinedIntervals.activeQueueLabels.first()}"
                            } else {
                                "Сума: ${combinedIntervals.activeQueueLabels.joinToString(" + ")}"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ElectricBlue,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (activeIntervalSchedules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_intervals_hint_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Увімкніть перемикач «Інтервали» на потрібних плашках «Графік на добу», щоб переглянути або підсумувати розклад.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            if (activeIntervalSchedules.size > 1) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("combined_intervals_summary_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = ElectricBlue.copy(alpha = 0.10f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Спільний підсумок (${activeIntervalSchedules.size} графіки: ${combinedIntervals.activeQueueLabels.joinToString(", ")})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("🟢 Зі світлом: ${combinedIntervals.onlineHours}г", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("🔴 Без світла: ${combinedIntervals.outageHours}г", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if (combinedIntervals.maybeHours > 0) {
                                    Text("🟡 Можливо: ${combinedIntervals.maybeHours}г", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            items(
                items = combinedIntervals.intervals,
                key = { "${it.startHour}-${it.endHour}-${it.status}" }
            ) { interval ->
                val affected = combinedIntervals.affectedQueuesByIntervalKey["${interval.startHour}-${interval.endHour}"].orEmpty()
                IntervalCard(
                    interval = interval,
                    affectedQueues = if (activeIntervalSchedules.size > 1) affected else emptyList()
                )
            }
        }
    }

    // Edit Dialog triggered by long-press -> "Редагувати"
    editingSlot?.let { slotToEdit ->
        EditScheduleSlotDialog(
            initialSlot = slotToEdit,
            cityQueuesCache = cityQueuesCache,
            isLoadingCityQueues = isLoadingCityQueues,
            onRequestCityQueues = { city -> viewModel.loadQueuesForCity(city) },
            onDismiss = { editingSlot = null },
            onConfirm = { newCity, newQueue ->
                viewModel.updateScheduleSlot(slotToEdit, newCity, newQueue)
                editingSlot = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyScheduleTimelineCard(
    slot: SelectedScheduleSlot,
    schedule: CitySchedule?,
    currentHour: Int,
    isLoading: Boolean,
    isIntervalsEnabled: Boolean,
    onToggleIntervals: (Boolean) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val cleanLabel = ScheduleCombiner.cleanQueueLabel(slot.queue)
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth()) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    }
                )
                .testTag("timeline_card_${slot.slotKey}"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Графік на добу • ${slot.city} • Черга $cleanLabel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = schedule?.let {
                                val updatedSuffix = if (it.factUpdate.isNotBlank()) " • Оновл.: ${it.factUpdate}" else ""
                                "Дата: ${it.dateLabel}$updatedSuffix"
                            } ?: if (isLoading) "Завантаження..." else "Дані відсутні",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Інтервали",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isIntervalsEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = isIntervalsEnabled,
                            onCheckedChange = onToggleIntervals,
                            modifier = Modifier.testTag("interval_switch_${slot.slotKey}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (schedule != null) {
                    val hours = schedule.hours
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    ) {
                        hours.forEachIndexed { index, hourSlot ->
                            val isCurrent = index == currentHour
                            val color = when (hourSlot.status) {
                                OutageStatus.ONLINE -> LightGreenOnline
                                OutageStatus.OUTAGE -> LightRedOutage
                                OutageStatus.MAYBE -> LightAmberMaybe
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(color)
                                    .border(
                                        if (isCurrent) 2.dp else 0.5.dp,
                                        if (isCurrent) Color.White else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCurrent) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("00:00", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("06:00", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("12:00", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("18:00", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("24:00", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        LegendItem(color = LightGreenOnline, label = "Світло є")
                        LegendItem(color = LightRedOutage, label = "Відключення")
                        LegendItem(color = LightAmberMaybe, label = "Можливе")
                    }

                    val onlineHours = hours.count { it.status == OutageStatus.ONLINE }
                    val outageHours = hours.count { it.status == OutageStatus.OUTAGE }
                    val maybeHours = hours.count { it.status == OutageStatus.MAYBE }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Text("🟢 Зі світлом: ${onlineHours}г", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("🔴 Без світла: ${outageHours}г", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (maybeHours > 0) {
                            Text("🟡 Можливо: ${maybeHours}г", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.testTag("card_context_menu_${slot.slotKey}")
        ) {
            DropdownMenuItem(
                text = { Text("Редагувати") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null
                    )
                },
                onClick = {
                    menuExpanded = false
                    onEditClick()
                },
                modifier = Modifier.testTag("edit_slot_menu_${slot.slotKey}")
            )
            DropdownMenuItem(
                text = { Text("Видалити", color = LightRedOutage) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = LightRedOutage
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDeleteClick()
                },
                modifier = Modifier.testTag("delete_slot_menu_${slot.slotKey}")
            )
        }
    }
}

@Composable
private fun EditScheduleSlotDialog(
    initialSlot: SelectedScheduleSlot,
    cityQueuesCache: Map<String, List<String>>,
    isLoadingCityQueues: Boolean,
    onRequestCityQueues: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (city: String, queue: String) -> Unit
) {
    var chosenCity by remember(initialSlot) { mutableStateOf(initialSlot.city) }
    var chosenQueue by remember(initialSlot) { mutableStateOf(initialSlot.queue) }
    var cityMenuExpanded by remember { mutableStateOf(false) }

    val queuesForCity = cityQueuesCache[chosenCity].orEmpty().ifEmpty { DEFAULT_FALLBACK_QUEUES }

    LaunchedEffect(chosenCity) {
        onRequestCityQueues(chosenCity)
    }

    LaunchedEffect(chosenCity, queuesForCity) {
        if (queuesForCity.isNotEmpty() && chosenQueue !in queuesForCity) {
            chosenQueue = queuesForCity.first()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Редагувати графік",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Місто та обленерго:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { cityMenuExpanded = true }
                            .testTag("edit_dialog_city_trigger"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chosenCity,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                val obl = OutageSources.getCitySource(chosenCity)?.oblenergo ?: ""
                                Text(
                                    text = obl,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Вибрати місто"
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = cityMenuExpanded,
                        onDismissRequest = { cityMenuExpanded = false }
                    ) {
                        OutageSources.CITIES.forEach { citySource ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(citySource.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            citySource.oblenergo,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    chosenCity = citySource.name
                                    cityMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Черга:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLoadingCityQueues && !cityQueuesCache.containsKey(chosenCity)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    queuesForCity.forEach { q ->
                        val cleanLabel = ScheduleCombiner.cleanQueueLabel(q)
                        val isSelected = q == chosenQueue
                        FilterChip(
                            selected = isSelected,
                            onClick = { chosenQueue = q },
                            label = {
                                Text(
                                    text = "Черга $cleanLabel",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(chosenCity, chosenQueue) },
                modifier = Modifier.testTag("edit_dialog_save_button")
            ) {
                Text("Зберегти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати")
            }
        }
    )
}

@Composable
fun IntervalCard(
    interval: OutageInterval,
    affectedQueues: List<String> = emptyList()
) {
    val (statusColor, statusIcon, label) = when (interval.status) {
        OutageStatus.ONLINE -> Triple(LightGreenOnline, "🟢", "Світло гарантовано є")
        OutageStatus.OUTAGE -> Triple(LightRedOutage, "🔴", "Відключення електроенергії")
        OutageStatus.MAYBE -> Triple(LightAmberMaybe, "🟡", "Можливе відключення")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("interval_card_${interval.startHour}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = statusIcon, fontSize = 18.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "${interval.startHour} - ${interval.endHour}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (affectedQueues.isNotEmpty()) {
                        Text(
                            text = "Черги: ${affectedQueues.joinToString(", ")}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "${interval.durationHours} год",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
