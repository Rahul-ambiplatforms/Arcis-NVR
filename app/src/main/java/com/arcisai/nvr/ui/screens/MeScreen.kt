package com.arcisai.nvr.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arcisai.nvr.ui.theme.ArcisGreen
import com.arcisai.nvr.viewmodel.NvrViewModel
import java.text.SimpleDateFormat
import java.util.*

private enum class MeNav { Profile, LoginActivity, SystemPrivacy }

@Composable
fun MeScreen(
    vm: NvrViewModel,
    onLogout: () -> Unit,
    onOpenNvrSettings: () -> Unit,
) {
    var nav by remember { mutableStateOf(MeNav.Profile) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    when (nav) {
        MeNav.Profile -> ProfilePage(
            vm = vm,
            onLogout = onLogout,
            onOpenNvrSettings = onOpenNvrSettings,
            onLoginActivity = { nav = MeNav.LoginActivity },
            onSystemPrivacy = { nav = MeNav.SystemPrivacy },
            onChangePassword = { showChangePassword = true },
            onHelp = { showHelp = true },
            onAboutArcis = { showAbout = true },
        )
        MeNav.LoginActivity -> LoginActivityPage(
            vm = vm,
            onBack = { nav = MeNav.Profile },
        )
        MeNav.SystemPrivacy -> SystemPrivacyPage(
            onBack = { nav = MeNav.Profile },
        )
    }

    if (showChangePassword) {
        AlertDialog(
            onDismissRequest = { showChangePassword = false },
            confirmButton = { TextButton(onClick = { showChangePassword = false }) { Text("OK") } },
            title = { Text("Change Password") },
            text = { Text("To change your Arcis account password, please visit the Arcis web portal or contact support.") },
        )
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("OK") } },
            title = { Text("Help") },
            text = { Text("For assistance with your NVR or Arcis account, contact:\ntech.support@adiance.com") },
        )
    }
    if (showAbout) {
        AboutArcisAISheet(onDismiss = { showAbout = false })
    }
}

// ── Profile page ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePage(
    vm: NvrViewModel,
    onLogout: () -> Unit,
    onOpenNvrSettings: () -> Unit,
    onLoginActivity: () -> Unit,
    onSystemPrivacy: () -> Unit,
    onChangePassword: () -> Unit,
    onHelp: () -> Unit,
    onAboutArcis: () -> Unit,
) {
    val name = vm.accountName
    val email = vm.accountEmail
    val initial = (name?.firstOrNull() ?: email?.firstOrNull())?.uppercaseChar() ?: 'A'

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Me", fontWeight = FontWeight.SemiBold) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Avatar + name header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            initial.toString(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        if (!name.isNullOrBlank()) {
                            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        }
                        if (!email.isNullOrBlank()) {
                            Text(
                                email,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Profile info card
            item {
                MeSectionLabel("Profile")
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    MeInfoRow("Email", email ?: "—")
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    MeInfoRow("Phone", "—")
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                    MeInfoRow("Region", "—")
                }
            }

            // ── Account actions
            item {
                MeSectionLabel("Account")
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    MeNavRow("Change Password", Icons.Default.Lock, onClick = onChangePassword)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    MeNavRow("Login Activity", Icons.Default.History, onClick = onLoginActivity)
                }
            }

            // ── App settings
            item {
                MeSectionLabel("Settings")
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    MeNavRow("NVR Settings", Icons.Default.Settings, onClick = onOpenNvrSettings)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    MeNavRow("About ArcisAI", Icons.Outlined.Info, onClick = onAboutArcis)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    MeNavRow("Help", Icons.AutoMirrored.Filled.Help, onClick = onHelp)
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    MeNavRow("System Privacy Settings", Icons.Default.AdminPanelSettings, onClick = onSystemPrivacy)
                }
            }

            // ── App version
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("App Version", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("v1.0.0", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Sign out
            item {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Text("Sign Out", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Login Activity page ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginActivityPage(vm: NvrViewModel, onBack: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy  ·  h:mm a", Locale.getDefault()) }
    val logs = vm.loginActivity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Login Activity", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No login activity recorded yet.\nActivity is logged after your next sign-in.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(logs.asReversed()) { event ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    event.email,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    fmt.format(Date(event.epochMs)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── System Privacy Settings page ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemPrivacyPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh permission state when the user returns from system settings
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationGranted = remember(refreshKey) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val micGranted = remember(refreshKey) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        else true
    }

    fun openAppSettings() {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Privacy Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "While using the app, we may request the following permissions to provide core features. Tap a permission to adjust it in your device settings.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            PermissionCard(
                icon = Icons.Default.LocationOn,
                title = "Location",
                description = "Used to find device hotspots, help add NVRs on your local network, and verify your Wi-Fi connection.",
                granted = locationGranted,
                onClick = { openAppSettings() },
            )
            PermissionCard(
                icon = Icons.Default.Mic,
                title = "Microphone",
                description = "Required for two-way audio in Live View so you can speak to the NVR's connected microphone.",
                granted = micGranted,
                onClick = { openAppSettings() },
            )
            PermissionCard(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                description = "Allows the app to display alarm alerts and motion-detection events in the status bar and lock screen.",
                granted = notifGranted,
                onClick = { openAppSettings() },
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (granted) "On" else "Off",
                        fontSize = 13.sp,
                        color = if (granted) ArcisGreen else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun MeSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun MeInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MeNavRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
