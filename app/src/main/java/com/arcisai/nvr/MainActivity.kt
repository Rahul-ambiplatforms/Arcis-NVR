package com.arcisai.nvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.ui.Tab
import com.arcisai.nvr.ui.screens.AboutDeviceScreen
import com.arcisai.nvr.ui.screens.ChannelSettingsScreen
import com.arcisai.nvr.ui.screens.DeviceInfoScreen
import com.arcisai.nvr.ui.screens.DiskScreen
import com.arcisai.nvr.ui.screens.EncodingScreen
import com.arcisai.nvr.ui.screens.GeneralScreen
import com.arcisai.nvr.ui.screens.ImageColorScreen
import com.arcisai.nvr.ui.screens.LiveScreen
import com.arcisai.nvr.ui.screens.LiveTabScreen
import com.arcisai.nvr.ui.screens.LogsScreen
import com.arcisai.nvr.ui.screens.LoginScreen
import com.arcisai.nvr.ui.screens.MaintenanceScreen
import com.arcisai.nvr.ui.screens.ManageScreen
import com.arcisai.nvr.ui.screens.MyNvrsScreen
import com.arcisai.nvr.ui.screens.NetworkScreen
import com.arcisai.nvr.ui.screens.OsdScreen
import com.arcisai.nvr.ui.screens.PasswordScreen
import com.arcisai.nvr.ui.screens.PppoeScreen
import com.arcisai.nvr.ui.screens.UsersScreen
import com.arcisai.nvr.ui.screens.PlaybackTabScreen
import com.arcisai.nvr.ui.screens.SettingsHubScreen
import com.arcisai.nvr.ui.screens.SmtpScreen
import com.arcisai.nvr.ui.screens.TimeScreen
import com.arcisai.nvr.ui.screens.WifiScreen
import com.arcisai.nvr.ui.theme.ArcisNvrTheme
import com.arcisai.nvr.viewmodel.NvrViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: NvrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArcisNvrTheme {
                Surface(modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    val rootNav = rememberNavController()
                    NavHost(navController = rootNav, startDestination = "loading") {
                        // Loading: shown briefly while ViewModel checks saved session.
                        composable("loading") {
                            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            val dest = viewModel.startDestination
                            LaunchedEffect(dest) {
                                if (dest != null) {
                                    rootNav.navigate(dest) {
                                        popUpTo("loading") { inclusive = true }
                                    }
                                }
                            }
                        }
                        composable("login") {
                            LoginScreen(
                                vm = viewModel,
                                onLanConnected = {
                                    rootNav.navigate("main") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                },
                                onCloudAuthenticated = {
                                    rootNav.navigate("my_nvrs") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("my_nvrs") {
                            MyNvrsScreen(
                                vm = viewModel,
                                onNvrSelected = {
                                    rootNav.navigate("main") {
                                        popUpTo("my_nvrs") { inclusive = true }
                                    }
                                },
                                onLogout = {
                                    viewModel.logout()
                                    rootNav.navigate("login") {
                                        popUpTo("my_nvrs") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("main") {
                            MainScaffold(
                                vm = viewModel,
                                onLogout = {
                                    viewModel.logout()
                                    rootNav.navigate("login") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onSwitchNvr = if (viewModel.accountSignedIn) {
                                    {
                                        viewModel.releaseSelectedNvr()
                                        rootNav.navigate("my_nvrs") {
                                            popUpTo("main") { inclusive = true }
                                        }
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(
    vm: NvrViewModel,
    onLogout: () -> Unit,
    onSwitchNvr: (() -> Unit)?,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    // Hide the main bottom tab bar while the user is inside a live channel view —
    // LiveScreen provides its own bottom navigation bar.
    val showBottomBar = backStack?.destination?.route?.let { r ->
        !r.startsWith("live/") && r != "nvr-settings" &&
        !r.startsWith("channel-settings") && r != "about-device"
    } ?: true

    Scaffold(
        bottomBar = { if (showBottomBar) BottomTabBar(nav) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.LIVE.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.LIVE.route) {
                LiveTabScreen(
                    vm,
                    onChannelTap = { ch -> nav.navigate("live/$ch") },
                    onOpenSettings = { nav.navigate("nvr-settings") },
                    onOpenPlayback = { nav.navigate(Tab.PLAYBACK.route) },
                    onDisconnect = onLogout,
                )
            }
            composable("live/{ch}") { entry ->
                val ch = entry.arguments?.getString("ch")?.toIntOrNull() ?: 0
                LiveScreen(
                    vm,
                    channelId = ch,
                    onBack = { nav.popBackStack() },
                    onNavigateToPlayback = {
                        // Keep live/{ch} in the back stack so Playback can
                        // offer a back-arrow that returns to the PTZ screen.
                        nav.navigate(Tab.PLAYBACK.route)
                    },
                    onNavigateToSettings = { selectedCh ->
                        nav.navigate("channel-settings/$selectedCh")
                    },
                )
            }
            composable(Tab.PLAYBACK.route) {
                val prevRoute = nav.previousBackStackEntry?.destination?.route
                val fromLive = prevRoute?.startsWith("live/") == true
                PlaybackTabScreen(
                    vm = vm,
                    onBack = if (fromLive) ({ nav.popBackStack() }) else null,
                )
            }
            composable(Tab.MANAGE.route)   { ManageScreen(vm) }
            // Me / profile tab – content to be defined later
            composable(Tab.SETTINGS.route) {
                MeScreen(
                    email = vm.accountEmail,
                    onLogout = onLogout,
                    onOpenNvrSettings = { nav.navigate("nvr-settings") },
                )
            }
            // NVR settings – opened from card ⋮ menu, not a tab
            composable("nvr-settings") {
                SettingsHubScreen(
                    vm = vm,
                    onPick = { key -> nav.navigate("settings/$key") },
                    onLogout = onLogout,
                    onBack = { nav.popBackStack() },
                    onSwitchNvr = onSwitchNvr,
                    currentNvrName = vm.credentials?.accountAbdName?.ifBlank { vm.credentials?.deviceId },
                    accountEmail = vm.accountEmail,
                    onChannelSettings = { ch -> nav.navigate("channel-settings/$ch") },
                    onAboutDevice = { nav.navigate("about-device") },
                )
            }
            composable("settings/{key}") { entry ->
                when (entry.arguments?.getString("key")) {
                    "encode"   -> EncodingScreen(vm, onBack = { nav.popBackStack() })
                    "device"   -> DeviceInfoScreen(vm, onBack = { nav.popBackStack() })
                    "general"  -> GeneralScreen(vm, onBack = { nav.popBackStack() })
                    "network"  -> NetworkScreen(vm, onBack = { nav.popBackStack() })
                    "smtp"     -> SmtpScreen(vm, onBack = { nav.popBackStack() })
                    "wifi"     -> WifiScreen(vm, onBack = { nav.popBackStack() })
                    "time"     -> TimeScreen(vm, onBack = { nav.popBackStack() })
                    "maint"    -> MaintenanceScreen(vm, onBack = { nav.popBackStack() })
                    "password" -> PasswordScreen(vm, onBack = { nav.popBackStack() })
                    "color"    -> ImageColorScreen(vm, onBack = { nav.popBackStack() })
                    "osd"      -> OsdScreen(vm, onBack = { nav.popBackStack() })
                    "pppoe"    -> PppoeScreen(vm, onBack = { nav.popBackStack() })
                    "disk"     -> DiskScreen(vm, onBack = { nav.popBackStack() })
                    "users"    -> UsersScreen(vm, onBack = { nav.popBackStack() })
                    "log"      -> LogsScreen(vm, onBack = { nav.popBackStack() })
                    else -> SettingsHubScreen(
                        vm = vm,
                        onPick = { key -> nav.navigate("settings/$key") },
                        onLogout = onLogout,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            composable("channel-settings/{ch}") { entry ->
                val ch = entry.arguments?.getString("ch")?.toIntOrNull() ?: 0
                ChannelSettingsScreen(
                    vm         = vm,
                    channelId  = ch,
                    onBack     = { nav.popBackStack() },
                    onNavigate = { key -> nav.navigate("settings/$key") },
                )
            }
            composable("about-device") {
                AboutDeviceScreen(
                    vm     = vm,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeScreen(
    email: String?,
    onLogout: () -> Unit,
    onOpenNvrSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Me") })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).padding(bottom = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!email.isNullOrBlank()) {
                    Text(email, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "Profile — coming soon",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onOpenNvrSettings) {
                    Text("NVR Settings")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onLogout) {
                    Text("Logout", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BottomTabBar(nav: NavController) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    NavigationBar {
        Tab.values().forEach { tab ->
            val selected = current?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    // The Live tab must always reopen on the all-channels grid,
                    // never the single channel the user last drilled into — so we
                    // don't save/restore its nested "live/{ch}" back stack. Other
                    // tabs keep their state across switches as before.
                    val keepState = tab != Tab.LIVE
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = keepState }
                        launchSingleTop = true
                        restoreState = keepState
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
