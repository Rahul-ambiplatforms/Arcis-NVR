package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.R
import com.arcisai.nvr.ui.theme.AccentPurple
import com.arcisai.nvr.ui.theme.loginGradient
import com.arcisai.nvr.viewmodel.NvrViewModel
import kotlinx.coroutines.delay

/**
 * Forgot-password step 1: enter the account email, backend emails a
 * `view.arcisai.io/resetPassword/<token>` link (valid 15 min, 1 request/min).
 *
 * The link opens this app directly IF the App-Links intent-filter is verified
 * (needs the NVR package listed in view.arcisai.io/.well-known/assetlinks.json).
 * If it isn't, the user copies the link from the email and pastes it on the
 * reset screen — [onGoToReset] takes them there.
 */
@Composable
fun ForgotPasswordScreen(
    vm: NvrViewModel,
    onGoToReset: () -> Unit,
    onBackToLogin: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var sent  by remember { mutableStateOf(false) }

    // Resend cooldown mirrors the backend's 1-request/min rate limit.
    var resendIn by remember { mutableStateOf(0) }
    LaunchedEffect(resendIn) { if (resendIn > 0) { delay(1000); resendIn -= 1 } }

    AuthCardScaffold(title = if (sent) "Check your email" else "Forgot password") {
        Text(
            if (sent)
                "We emailed a password-reset link to $email. Open it on this device to continue, " +
                "or copy the link and paste it on the next screen."
            else
                "Enter your account email and we'll send you a reset link.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = email, onValueChange = { email = it.trim(); vm.loginStatus = null },
            label = { Text("Email") }, singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )

        vm.loginStatus?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        vm.authNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                vm.forgotPassword(email.trim()) { ok -> if (ok) { sent = true; resendIn = 60 } }
            },
            enabled = !vm.loginBusy && email.isNotBlank() && (!sent || resendIn == 0),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (vm.loginBusy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(
                    when {
                        !sent -> "Send reset link"
                        resendIn == 0 -> "Resend link"
                        else -> "Resend in ${resendIn}s"
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                )
            }
        }

        if (sent) {
            OutlinedButton(
                onClick = { vm.loginStatus = null; onGoToReset() },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("I have the reset link") }
        }

        TextButton(
            onClick = { vm.loginStatus = null; vm.authNotice = null; onBackToLogin() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Back to sign in", fontSize = 13.sp) }
    }
}

/**
 * Forgot-password step 2: the two-field new/confirm form.
 *
 * The token comes from either the deep link ([NvrViewModel.pendingResetToken])
 * or a link the user pastes here. Both passwords are AES-encrypted before the
 * `auth/resetPassword` call (handled in the ViewModel).
 */
@Composable
fun ResetPasswordScreen(
    vm: NvrViewModel,
    onDone: () -> Unit,
) {
    // Pre-fill the paste field from a deep-link token (consume it so backing
    // out doesn't re-trigger LoginScreen's redirect).
    val initialToken = remember {
        vm.pendingResetToken.also { vm.pendingResetToken = null } ?: ""
    }
    var linkOrToken by remember { mutableStateOf(initialToken) }
    var pwd         by remember { mutableStateOf("") }
    var confirm     by remember { mutableStateOf("") }
    var pwdVis      by remember { mutableStateOf(false) }
    var confirmVis  by remember { mutableStateOf(false) }
    var success     by remember { mutableStateOf(false) }

    LaunchedEffect(success) { if (success) { delay(1400); onDone() } }

    AuthCardScaffold(title = if (success) "Password updated" else "Reset password") {
        if (success) {
            Text("Your password has been updated. Taking you to sign in…",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            return@AuthCardScaffold
        }

        // Only show the paste field when we didn't already get a token from
        // the deep link — otherwise it's just noise.
        if (initialToken.isBlank()) {
            Text(
                "Paste the reset link from your email, then choose a new password.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = linkOrToken, onValueChange = { linkOrToken = it; vm.loginStatus = null },
                label = { Text("Reset link or code") }, singleLine = true,
                placeholder = { Text("https://view.arcisai.io/resetPassword/…") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("Choose a new password for your account.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedTextField(
            value = pwd, onValueChange = { pwd = it; vm.loginStatus = null },
            label = { Text("New password") }, singleLine = true,
            visualTransformation = if (pwdVis) VisualTransformation.None else PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                Text("Min 8 chars, number, lowercase, special char",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                IconButton(onClick = { pwdVis = !pwdVis }) {
                    Icon(if (pwdVis) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (pwdVis) "Hide password" else "Show password")
                }
            },
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it; vm.loginStatus = null },
            label = { Text("Confirm new password") }, singleLine = true,
            visualTransformation = if (confirmVis) VisualTransformation.None else PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirm.isNotEmpty() && confirm != pwd,
            trailingIcon = {
                IconButton(onClick = { confirmVis = !confirmVis }) {
                    Icon(if (confirmVis) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (confirmVis) "Hide password" else "Show password")
                }
            },
        )

        vm.loginStatus?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val token = vm.extractResetToken(linkOrToken)
                vm.resetPassword(token, pwd, confirm) { ok -> if (ok) success = true }
            },
            enabled = !vm.loginBusy && pwd.length >= 8 && confirm.isNotEmpty() &&
                      (initialToken.isNotBlank() || linkOrToken.isNotBlank()),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (vm.loginBusy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Update password", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }

        TextButton(
            onClick = { vm.loginStatus = null; onDone() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Back to sign in", fontSize = 13.sp) }
    }
}

/** Shared gradient + branded card wrapper so the reset screens match the
 *  login / register look exactly. */
@Composable
private fun AuthCardScaffold(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(loginGradient()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuthBrandHeader()
            Spacer(Modifier.height(28.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    content()
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("ArcisAI · NVR companion", fontSize = 11.sp, color = Color(0xFF8E8AA0))
        }
    }
}

@Composable
private fun AuthBrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.arcisai_full_logo),
            contentDescription = "ArcisAI",
            modifier = Modifier.height(56.dp).widthIn(max = 240.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text("NVR", color = AccentPurple, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, letterSpacing = 4.sp)
    }
}
