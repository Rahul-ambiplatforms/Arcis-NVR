package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.R
import com.arcisai.nvr.ui.theme.AccentPurple
import com.arcisai.nvr.ui.theme.loginGradient
import com.arcisai.nvr.viewmodel.NvrViewModel
import kotlinx.coroutines.delay

/**
 * Arcis-account registration — same backend flow as the production
 * ArcisAI-Android client:
 *
 *   1. POST /auth/register  {name, mobile, email, password(AES), acceptedTerms}
 *   2. backend emails an OTP
 *   3. POST /auth/verify    {email, otp}
 *   4. back to the login screen ("email verified" notice)
 *
 * Also serves as the verify-only landing when a login bounces with
 * "please verify your email" — [NvrViewModel.pendingVerificationEmail]
 * pre-selects the OTP step for that address.
 */
@Composable
fun RegisterScreen(
    vm: NvrViewModel,
    onBackToLogin: () -> Unit,
) {
    // A login that bounced on "verify your email" lands here with the email
    // pre-filled and the form skipped. Consume the flag on first composition —
    // leaving it set would make LoginScreen's redirect effect re-fire if the
    // user backs out without verifying.
    val pendingEmail = remember {
        vm.pendingVerificationEmail.also { vm.pendingVerificationEmail = null }
    }

    var verifying by remember { mutableStateOf(pendingEmail != null) }
    var name      by remember { mutableStateOf("") }
    var mobile    by remember { mutableStateOf("") }
    var email     by remember { mutableStateOf(pendingEmail ?: "") }
    var pwd       by remember { mutableStateOf("") }
    var pwdVis    by remember { mutableStateOf(false) }
    var terms     by remember { mutableStateOf(false) }
    var otp       by remember { mutableStateOf("") }

    // Resend cooldown so the button can't be hammered.
    var resendIn by remember { mutableStateOf(0) }
    LaunchedEffect(resendIn) {
        if (resendIn > 0) { delay(1000); resendIn -= 1 }
    }

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
            RegisterBrandHeader()
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
                    Text(
                        if (verifying) "Verify email" else "Create account",
                        fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    )
                    Text(
                        if (verifying)
                            "Enter the one-time code we emailed to $email."
                        else
                            "Register an Arcis account. You'll confirm your email with an OTP right after.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!verifying) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it; vm.loginStatus = null },
                            label = { Text("Full name") }, singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = mobile,
                            onValueChange = { mobile = it.filter(Char::isDigit).take(15); vm.loginStatus = null },
                            label = { Text("Mobile number") }, singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        )
                        OutlinedTextField(
                            value = email, onValueChange = { email = it.trim(); vm.loginStatus = null },
                            label = { Text("Email") }, singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        OutlinedTextField(
                            value = pwd, onValueChange = { pwd = it; vm.loginStatus = null },
                            label = { Text("Password") }, singleLine = true,
                            visualTransformation = if (pwdVis) VisualTransformation.None else PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            supportingText = {
                                Text("Min 8 chars, number, lowercase, special char",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = {
                                IconButton(onClick = { pwdVis = !pwdVis }) {
                                    Icon(
                                        imageVector = if (pwdVis) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (pwdVis) "Hide password" else "Show password",
                                    )
                                }
                            },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { terms = !terms },
                        ) {
                            Checkbox(checked = terms, onCheckedChange = { terms = it })
                            Text(
                                "I agree to the Terms & Conditions",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = otp,
                            onValueChange = { otp = it.filter(Char::isDigit).take(6); vm.loginStatus = null },
                            label = { Text("One-time code") }, singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = LocalTextStyle.current.copy(letterSpacing = 6.sp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                enabled = resendIn == 0,
                                onClick = {
                                    vm.resendRegistrationOtp(email)
                                    resendIn = 30
                                },
                            ) {
                                Text(if (resendIn == 0) "Resend OTP" else "Resend in ${resendIn}s")
                            }
                        }
                    }

                    vm.loginStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    vm.authNotice?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (verifying) {
                                vm.verifyRegistrationOtp(email, otp) { onBackToLogin() }
                            } else {
                                vm.accountRegister(name.trim(), mobile, email, pwd) {
                                    vm.authNotice = "OTP sent to $email"
                                    otp = ""
                                    verifying = true
                                }
                            }
                        },
                        enabled = !vm.loginBusy && (
                            if (verifying) otp.length >= 4
                            else name.isNotBlank() && mobile.isNotBlank() &&
                                 email.isNotBlank() && pwd.length >= 8 && terms
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (vm.loginBusy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                if (verifying) "Verify email" else "Create account",
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            )
                        }
                    }

                    if (verifying && pendingEmail == null) {
                        TextButton(
                            onClick = { verifying = false; vm.loginStatus = null },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) { Text("Back to details") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                vm.loginStatus = null
                onBackToLogin()
            }) {
                Text("Already have an account?  Sign in", fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "ArcisAI · NVR companion",
                fontSize = 11.sp,
                color = Color(0xFF8E8AA0),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RegisterBrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.arcisai_full_logo),
            contentDescription = "ArcisAI",
            modifier = Modifier.height(56.dp).widthIn(max = 240.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "NVR",
            color = AccentPurple,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 4.sp,
        )
    }
}
