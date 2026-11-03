package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import com.example.ui.components.DashboardScreen
import com.example.ui.components.HistoryScreen
import com.example.ui.components.ScheduleScreen
import com.example.ui.components.SettingsScreen
import com.example.ui.theme.ElectricYellow
import com.example.ui.theme.PowerBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PowerBotTheme {
                MainApp()
            }
        }
    }
}

enum class NavigationTab(val title: String) {
    DASHBOARD("Статус"),
    SCHEDULE("Графік"),
    HISTORY("Журнал"),
    SETTINGS("Налаштування")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel = viewModel()) {
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val currentTab = NavigationTab.entries[selectedTabOrdinal]

    // Request notification permission on Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val context = viewModel.getApplication<PowerBotApp>()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Handle back button on secondary tabs
    if (selectedTabOrdinal != 0) {
        BackHandler {
            selectedTabOrdinal = 0
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentTab) {
                            NavigationTab.DASHBOARD -> "PowerBot • Моніторинг"
                            NavigationTab.SCHEDULE -> "Графік відключень (ГПВ)"
                            NavigationTab.HISTORY -> "Журнал відключень"
                            NavigationTab.SETTINGS -> "Налаштування"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("main_navigation_bar"),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = currentTab == NavigationTab.DASHBOARD,
                    onClick = { selectedTabOrdinal = NavigationTab.DASHBOARD.ordinal },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.DASHBOARD) Icons.Filled.Bolt else Icons.Outlined.Bolt,
                            contentDescription = "Статус"
                        )
                    },
                    label = { Text("Статус") },
                    modifier = Modifier.testTag("nav_item_dashboard")
                )

                NavigationBarItem(
                    selected = currentTab == NavigationTab.SCHEDULE,
                    onClick = { selectedTabOrdinal = NavigationTab.SCHEDULE.ordinal },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.SCHEDULE) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth,
                            contentDescription = "Графік"
                        )
                    },
                    label = { Text("Графік") },
                    modifier = Modifier.testTag("nav_item_schedule")
                )

                NavigationBarItem(
                    selected = currentTab == NavigationTab.HISTORY,
                    onClick = { selectedTabOrdinal = NavigationTab.HISTORY.ordinal },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.HISTORY) Icons.Filled.History else Icons.Outlined.History,
                            contentDescription = "Журнал"
                        )
                    },
                    label = { Text("Журнал") },
                    modifier = Modifier.testTag("nav_item_history")
                )

                NavigationBarItem(
                    selected = currentTab == NavigationTab.SETTINGS,
                    onClick = { selectedTabOrdinal = NavigationTab.SETTINGS.ordinal },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Налаштування"
                        )
                    },
                    label = { Text("Опції") },
                    modifier = Modifier.testTag("nav_item_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                NavigationTab.DASHBOARD -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToSchedule = { selectedTabOrdinal = NavigationTab.SCHEDULE.ordinal },
                    onNavigateToSettings = { selectedTabOrdinal = NavigationTab.SETTINGS.ordinal }
                )
                NavigationTab.SCHEDULE -> ScheduleScreen(
                    viewModel = viewModel
                )
                NavigationTab.HISTORY -> HistoryScreen(
                    viewModel = viewModel
                )
                NavigationTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel
                )
            }
        }
    }
}
