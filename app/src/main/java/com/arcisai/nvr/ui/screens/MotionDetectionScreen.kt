package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionDetectionScreen(
    vm: NvrViewModel,
    channelId: Int,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.loadMotionDetection() }
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    val cfg   = vm.motionDetectionCfg

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Motion Detection – Ch ${channelId + 1}",
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            cfg == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val mdArr = cfg.optJSONArray("MotionDetection")
                val chEntry = mdArr?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optInt("ID") == channelId }
                }

                var mdEnabled by remember(channelId, cfg) {
                    mutableStateOf(chEntry?.optString("MDEnable") == "True")
                }
                val humanArr = chEntry?.optJSONArray("humanDetect")
                var humanEnabled by remember(channelId, cfg) {
                    val firstHuman = humanArr?.let { if (it.length() > 0) it.getJSONObject(0) else null }
                    mutableStateOf(firstHuman?.optString("HumanEnable") == "True")
                }
                val actions = chEntry?.optJSONObject("Actions")
                var appAlarm by remember(channelId, cfg) {
                    mutableStateOf(actions?.optString("AppAlarm") == "True")
                }

                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        "Configure motion alerts for Channel ${channelId + 1}.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Column {
                            MdToggleRow(
                                icon  = Icons.AutoMirrored.Filled.DirectionsRun,
                                title = "Motion Detection",
                                sub   = "Detect any movement in the camera view",
                                value = mdEnabled,
                                onToggle = { mdEnabled = it },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 60.dp), thickness = 0.5.dp)
                            MdToggleRow(
                                icon  = Icons.Default.Person,
                                title = "Human Detection",
                                sub   = "Detect people specifically (reduces false alarms)",
                                value = humanEnabled && mdEnabled,
                                enabled = mdEnabled,
                                onToggle = { humanEnabled = it },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 60.dp), thickness = 0.5.dp)
                            MdToggleRow(
                                icon  = Icons.Default.Notifications,
                                title = "Push Notification",
                                sub   = "Send app alert when motion is detected",
                                value = appAlarm && mdEnabled,
                                enabled = mdEnabled,
                                onToggle = { appAlarm = it },
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { vm.saveMotionDetection(channelId, mdEnabled, humanEnabled, appAlarm) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Save", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun MdToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    value: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(26.dp).padding(end = 2.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, fontSize = 15.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Text(
                sub, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
            )
        }
        Switch(
            checked  = value,
            onCheckedChange = if (enabled) onToggle else null,
            enabled  = enabled,
        )
    }
}
