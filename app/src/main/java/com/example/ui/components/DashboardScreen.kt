package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PlugType
import com.example.ui.MainViewModel
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricYellow
import com.example.ui.theme.LightAmberMaybe
import com.example.ui.theme.LightGreenOnline
import com.example.ui.theme.LightRedOutage
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val batteryInfo by viewModel.batteryInfo.collectAsState()
    val durationMinutes by viewModel.stateDurationMinutes.collectAsState()
    val isSentinelActive by viewModel.isSentinelActive.collectAsState()
    val isTelegramEnabled by viewModel.isTelegramEnabled.collectAsState()

    val isOnline = batteryInfo.isPlugged

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_scroll_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. HERO POWER STATUS CARD
        item {
            val heroBgBrush = if (isOnline) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF064E3B),
                        Color(0xFF022C22)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF7F1D1D),
                        Color(0xFF450A0A)
                    )
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hero_power_card"),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(heroBgBrush)
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Status Badge
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isOnline) LightGreenOnline.copy(alpha = 0.25f) else LightRedOutage.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isOnline) LightGreenOnline else LightRedOutage
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isOnline) LightGreenOnline else LightRedOutage)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isOnline) "ЕЛЕКТРОМЕРЕЖА АКТИВНА" else "ЖИВЛЕННЯ ВІДСУТНЄ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Big Glowing Icon
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(96.dp)
                                .scale(if (isOnline) pulseScale else 1f)
                                .clip(CircleShape)
                                .background(
                                    if (isOnline) LightGreenOnline.copy(alpha = 0.2f) else LightRedOutage.copy(alpha = 0.2f)
                                )
                                .border(
                                    2.dp,
                                    if (isOnline) LightGreenOnline else LightRedOutage,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (isOnline) Icons.Default.Bolt else Icons.Default.PowerOff,
                                contentDescription = if (isOnline) "Світло є" else "Світла немає",
                                tint = if (isOnline) ElectricYellow else Color.White,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (isOnline) "СВІТЛО Є" else "СВІТЛА НЕМАЄ",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        val durationFormatted = formatDuration(durationMinutes)
                        Text(
                            text = if (isOnline) "Триває вже $durationFormatted" else "Відсутнє вже $durationFormatted",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE2E8F0)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Interactive Sentinel status badge
                        Surface(
                            onClick = { viewModel.toggleBackgroundService(!isSentinelActive) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSentinelActive) {
                                Color.Black.copy(alpha = 0.28f)
                            } else {
                                Color.Black.copy(alpha = 0.42f)
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSentinelActive) {
                                    ElectricYellow.copy(alpha = 0.45f)
                                } else {
                                    Color.White.copy(alpha = 0.2f)
                                }
                            ),
                            modifier = Modifier.testTag("sentinel_status_badge")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSentinelActive) {
                                        Icons.Default.NotificationsActive
                                    } else {
                                        Icons.Default.NotificationsOff
                                    },
                                    contentDescription = if (isSentinelActive) {
                                        "PowerBot Sentinel активний, натисніть щоб вимкнути"
                                    } else {
                                        "PowerBot Sentinel не активний, натисніть щоб увімкнути"
                                    },
                                    tint = if (isSentinelActive) ElectricYellow else Color(0xFFCBD5E1),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val statusText = if (isSentinelActive) {
                                    "PowerBot Sentinel: Активний" + if (isTelegramEnabled) " • Telegram 🔔" else ""
                                } else {
                                    "PowerBot Sentinel: Не активний"
                                }
                                Text(
                                    text = statusText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSentinelActive) Color.White.copy(alpha = 0.95f) else Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. BATTERY & HARDWARE SENSORS GRID (2x2)
        item {
            Text(
                text = "Телеметрія пристрою",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Battery % Card
                    SensorMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Power,
                        iconTint = if (batteryInfo.percent > 20) LightGreenOnline else LightRedOutage,
                        label = "Заряд батареї",
                        value = "${batteryInfo.percent}%",
                        subValue = if (isOnline) "Заряджається" else "Розряджається"
                    )

                    // Battery Temperature Card
                    val tempColor = when {
                        batteryInfo.temperatureCelsius > 42 -> LightRedOutage
                        batteryInfo.temperatureCelsius > 36 -> LightAmberMaybe
                        else -> LightGreenOnline
                    }
                    SensorMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Thermostat,
                        iconTint = tempColor,
                        label = "Температура АКБ",
                        value = String.format(Locale.US, "%.1f°C", batteryInfo.temperatureCelsius),
                        subValue = "Стан: ${batteryInfo.health}"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Power Source
                    val sourceName = when (batteryInfo.plugType) {
                        PlugType.AC -> "Мережа 220V (AC)"
                        PlugType.USB -> "USB Порт"
                        PlugType.WIRELESS -> "Бездротова"
                        PlugType.NONE -> "Акумулятор"
                    }
                    SensorMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.ElectricMeter,
                        iconTint = ElectricBlue,
                        label = "Джерело живлення",
                        value = sourceName,
                        subValue = if (isOnline) "Підключено до мережі" else "Працює автономно"
                    )

                    // Battery Voltage
                    SensorMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Bolt,
                        iconTint = ElectricYellow,
                        label = "Напруга батареї",
                        value = String.format(Locale.US, "%.2f V", batteryInfo.voltageMv / 1000f),
                        subValue = "Номінал Li-Ion"
                    )
                }
            }
        }

        // 3. TELEGRAM LINK SHORTCUT CARD
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("telegram_banner_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = ElectricBlue.copy(alpha = 0.12f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = ElectricBlue,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Telegram Релей",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val isConfigured = viewModel.preferences.telegramBotToken.isNotBlank()
                            Text(
                                text = if (isConfigured) "Бот налаштований і готовий" else "Вкажіть токен та чат бота",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Налаштувати", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SensorMetricCard(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
