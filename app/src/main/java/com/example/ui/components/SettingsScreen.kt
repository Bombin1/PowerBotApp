package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import com.example.BuildConfig
import com.example.ui.MainViewModel
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricYellow
import com.example.ui.theme.LightGreenOnline
import com.example.ui.theme.LightRedOutage
import com.example.util.GitHubUpdater
import com.example.util.UpdateState

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = viewModel.preferences

    var telegramEnabled by remember { mutableStateOf(prefs.telegramEnabled) }
    var botToken by remember { mutableStateOf(prefs.telegramBotToken) }
    var chatId by remember { mutableStateOf(prefs.telegramChatId) }
    var adminId by remember { mutableStateOf(prefs.telegramAdminId) }
    var isTokenVisible by remember { mutableStateOf(false) }
    var botCommandsEnabled by remember { mutableStateOf(prefs.telegramBotCommandsEnabled) }

    var backgroundServiceEnabled by remember { mutableStateOf(prefs.backgroundServiceEnabled) }
    var soundAlertsEnabled by remember { mutableStateOf(prefs.soundAlertsEnabled) }
    var vibrationAlertsEnabled by remember { mutableStateOf(prefs.vibrationAlertsEnabled) }
    var scheduleNotificationsEnabled by remember { mutableStateOf(prefs.scheduleNotificationsEnabled) }

    val telegramStatus by viewModel.telegramTestStatus.collectAsState()
    val isTestingTelegram by viewModel.isTestingTelegram.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onInstallPermissionReturned()
    }

    LaunchedEffect(updateState) {
        val state = updateState
        if (state is UpdateState.ReadyToInstall) {
            try {
                val installIntent = GitHubUpdater.createInstallIntent(context, state.apkFile)
                context.startActivity(installIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_scroll_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. TELEGRAM BOT INTEGRATION SECTION
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("telegram_settings_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                tint = ElectricBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Telegram-Бот Релей",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "Надсилання сповіщень у ваш чат/канал",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = telegramEnabled,
                            onCheckedChange = {
                                telegramEnabled = it
                                prefs.telegramEnabled = it
                            },
                            modifier = Modifier.testTag("telegram_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (telegramEnabled) {
                        // Bot Token Input
                        OutlinedTextField(
                            value = botToken,
                            onValueChange = {
                                botToken = it
                                prefs.telegramBotToken = it
                            },
                            label = { Text("BOT TOKEN (@BotFather)") },
                            placeholder = { Text("Наприклад: 123456789:ABCdef-...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bot_token_input"),
                            singleLine = true,
                            visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                    Icon(
                                        imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Показати/сховати токен"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Chat ID Input
                        OutlinedTextField(
                            value = chatId,
                            onValueChange = {
                                chatId = it
                                prefs.telegramChatId = it
                            },
                            label = { Text("CHAT ID (Група або канал)") },
                            placeholder = { Text("Наприклад: -10012345678 або ваш ID") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_id_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Admin ID (Optional)
                        OutlinedTextField(
                            value = adminId,
                            onValueChange = {
                                adminId = it
                                prefs.telegramAdminId = it
                            },
                            label = { Text("ADMIN ID (Опціонально)") },
                            placeholder = { Text("Ваш особистий Telegram ID") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_id_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Interactive Bot Commands in Groups / Chats
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Відповідати на команди в чатах",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Обробка кнопок «💡 Перевірити наявність», «📊 Статистика», /status, /stats, /info",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = botCommandsEnabled,
                                onCheckedChange = {
                                    botCommandsEnabled = it
                                    prefs.telegramBotCommandsEnabled = it
                                    // Restart service to apply polling state
                                    viewModel.toggleBackgroundService(prefs.backgroundServiceEnabled)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                viewModel.testTelegramConnection(botToken, chatId)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("test_telegram_button"),
                            enabled = !isTestingTelegram && botToken.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            if (isTestingTelegram) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Надсилання...")
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Тестове повідомлення в Telegram")
                            }
                        }

                        if (telegramStatus != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (telegramStatus!!.startsWith("✅")) LightGreenOnline.copy(alpha = 0.15f) else LightRedOutage.copy(alpha = 0.15f)
                                )
                            ) {
                                Text(
                                    text = telegramStatus!!,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "💡 Підказка: створіть бота через @BotFather і скопіюйте токен. Дізнатися свій Chat ID можна в @userinfobot.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 2. MONITORING & ALERT SETTINGS
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("alerts_settings_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Системний моніторинг",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Background service toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Фонова служба (Sentinel)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Залишає додаток активним навіть при вимкненому екрані", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = backgroundServiceEnabled,
                            onCheckedChange = {
                                backgroundServiceEnabled = it
                                viewModel.toggleBackgroundService(it)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Sound Alert
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Звуковий сигнал", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Гучний сигнал при появі або зникненні світла", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = soundAlertsEnabled,
                            onCheckedChange = {
                                soundAlertsEnabled = it
                                prefs.soundAlertsEnabled = it
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Vibration Alert
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Вібрація", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Вібросигнал під час перемикання живлення", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = vibrationAlertsEnabled,
                            onCheckedChange = {
                                vibrationAlertsEnabled = it
                                prefs.vibrationAlertsEnabled = it
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Schedule Update Notifications
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Оновлення графіка ГПВ", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Перевірка змін у графіку кожні 30 хв", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = scheduleNotificationsEnabled,
                            onCheckedChange = {
                                scheduleNotificationsEnabled = it
                                prefs.scheduleNotificationsEnabled = it
                            }
                        )
                    }
                }
            }
        }

        // 3. ABOUT & CREDITS
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("about_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Про додаток PowerBot",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Версія ${BuildConfig.VERSION_NAME} (Повноцінний Android клієнт на заміну скрипта в Termux)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Check for GitHub Updates button
                    Button(
                        onClick = { viewModel.checkForUpdates() },
                        enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("check_updates_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        if (updateState is UpdateState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Перевірка оновлень...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Перевірити оновлення")
                        }
                    }

                    // Update status display
                    when (val state = updateState) {
                        is UpdateState.UpToDate -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = LightGreenOnline.copy(alpha = 0.15f)
                                )
                            ) {
                                Text(
                                    text = "✅ У вас остання версія (${state.currentVersion}). Реліз на GitHub: ${state.latestTag}",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        is UpdateState.UpdateAvailable -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_available_card"),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = ElectricBlue.copy(alpha = 0.12f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "🚀 Доступне оновлення: ${state.release.tagName}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (state.release.changelog.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = state.release.changelog,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    if (!state.release.apkDownloadUrl.isNullOrBlank()) {
                                        Button(
                                            onClick = { viewModel.downloadAndInstallUpdate(state.release) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("download_update_button"),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = LightGreenOnline)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Завантажити та встановити (${state.release.versionName})")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.release.htmlUrl))
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Відкрити реліз ${state.release.tagName} на GitHub")
                                        }
                                    }
                                }
                            }
                        }

                        is UpdateState.Downloading -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = ElectricBlue.copy(alpha = 0.10f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "⬇️ Завантаження оновлення ${state.release.tagName}: ${state.progressPercent}%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { state.progressPercent / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        is UpdateState.NeedsInstallPermission -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = ElectricYellow.copy(alpha = 0.15f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "⚠️ Потрібен дозвіл на встановлення оновлень (APK) з цього додатку.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            val settingsIntent = GitHubUpdater.createUnknownSourcesSettingsIntent(context)
                                            if (settingsIntent != null) {
                                                unknownSourcesLauncher.launch(settingsIntent)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Надати дозвіл на встановлення")
                                    }
                                }
                            }
                        }

                        is UpdateState.ReadyToInstall -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = LightGreenOnline.copy(alpha = 0.15f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "✅ Оновлення ${state.release.tagName} завантажено!",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            val installIntent = GitHubUpdater.createInstallIntent(context, state.apkFile)
                                            context.startActivity(installIntent)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = LightGreenOnline)
                                    ) {
                                        Text("Встановити зараз")
                                    }
                                }
                            }
                        }

                        is UpdateState.Error -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = LightRedOutage.copy(alpha = 0.15f)
                                )
                            ) {
                                Text(
                                    text = "❌ ${state.message}",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        UpdateState.Idle, UpdateState.Checking -> Unit
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "🤝 Особлива подяка автору @yaroslav2901 за надійні парсери графіків відключень OE_OUTAGE_DATA та Baskerville42 (outage-data-ua).",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdater.RELEASES_PAGE_URL))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("GitHub Релізи", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://send.monobank.ua/jar/8WFAPWLdPu"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Coffee, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("На каву", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
