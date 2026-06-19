package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordScreen(vm: NvrViewModel, onBack: () -> Unit) {
    val currentUser = vm.credentials?.username ?: "admin"

    var oldPwd      by remember { mutableStateOf("") }
    var newPwd      by remember { mutableStateOf("") }
    var confirmPwd  by remember { mutableStateOf("") }
    var showOld     by remember { mutableStateOf(false) }
    var showNew     by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var busy        by remember { mutableStateOf(false) }
    var result      by remember { mutableStateOf<String?>(null) }
    var isError     by remember { mutableStateOf(false) }

    val mismatch = newPwd.isNotEmpty() && confirmPwd.isNotEmpty() && newPwd != confirmPwd
    val canSubmit = oldPwd.isNotEmpty() && newPwd.isNotEmpty() && confirmPwd.isNotEmpty()
        && !mismatch && !busy

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(result) {
        result?.let { snack.showSnackbar(it); result = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Account: $currentUser",
                fontSize  = 12.sp,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier  = Modifier.padding(start = 4.dp, bottom = 12.dp),
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    PwdField(
                        label    = "Enter Current Password",
                        value    = oldPwd,
                        show     = showOld,
                        onToggle = { showOld = !showOld },
                        onChange = { oldPwd = it },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    PwdField(
                        label    = "Set New Password",
                        value    = newPwd,
                        show     = showNew,
                        onToggle = { showNew = !showNew },
                        onChange = { newPwd = it },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    PwdField(
                        label       = "Confirm New Password",
                        value       = confirmPwd,
                        show        = showConfirm,
                        onToggle    = { showConfirm = !showConfirm },
                        onChange    = { confirmPwd = it },
                        isError     = mismatch,
                        errorLabel  = "Passwords do not match",
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick  = {
                        oldPwd = ""; newPwd = ""; confirmPwd = ""
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                    enabled  = !busy,
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        busy = true
                        vm.changePassword(currentUser, oldPwd, newPwd) { ok ->
                            busy = false
                            isError = !ok
                            result = if (ok) "Password changed" else (vm.settingStatus ?: "Change failed")
                            if (ok) { oldPwd = ""; newPwd = ""; confirmPwd = "" }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled  = canSubmit,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Confirm")
                }
            }
        }
    }
}

@Composable
private fun PwdField(
    label: String,
    value: String,
    show: Boolean,
    onToggle: () -> Unit,
    onChange: (String) -> Unit,
    isError: Boolean = false,
    errorLabel: String = "",
) {
    OutlinedTextField(
        value            = value,
        onValueChange    = onChange,
        placeholder      = { Text(label, fontSize = 14.sp) },
        singleLine       = true,
        isError          = isError,
        supportingText   = if (isError) ({ Text(errorLabel) }) else null,
        visualTransformation = if (show) VisualTransformation.None
                               else PasswordVisualTransformation(),
        keyboardOptions  = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon     = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (show) "Hide" else "Show",
                )
            }
        },
        modifier         = Modifier.fillMaxWidth(),
        colors           = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedBorderColor   = androidx.compose.ui.graphics.Color.Transparent,
            errorBorderColor     = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}
