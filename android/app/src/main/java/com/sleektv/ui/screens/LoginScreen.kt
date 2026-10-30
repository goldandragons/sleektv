package com.sleektv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleektv.data.ApiException
import com.sleektv.data.QuickConnectState
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekButton
import com.sleektv.ui.theme.Inter
import com.sleektv.ui.theme.LocalSleekColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(nav: Nav) {
    val session = nav.app.session
    val api = nav.app.api
    val c = LocalSleekColors.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val firstField = remember { FocusRequester() }
    val quickFocus = remember { FocusRequester() }

    var server by remember { mutableStateOf(session.serverUrl ?: "http://") }
    var username by remember { mutableStateOf(session.userName ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf<QuickConnectState?>(null) } // non-null = showing a code

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    suspend fun finishSignIn() {
        nav.refreshLayout()
        nav.resetTo(Screen.Home)
    }

    fun submitPassword() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                api.login(server, username.trim(), password)
                finishSignIn()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't connect to the server"
            } finally {
                busy = false
            }
        }
    }

    fun startQuickConnect() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                if (!api.quickConnectEnabled(server)) {
                    error = "Quick Connect is turned off on this server. An admin can enable it in Dashboard → General."
                } else {
                    quick = api.quickConnectInitiate()
                }
            } catch (e: ApiException) {
                error = if (e.code == 401 || e.code == 403) "Quick Connect is turned off on this server." else e.message
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach the server"
            } finally {
                busy = false
            }
        }
    }

    // While a code is showing, check every 3 seconds whether it has been approved.
    val pending = quick
    LaunchedEffect(pending?.secret) {
        val secret = pending?.secret ?: return@LaunchedEffect
        runCatching { quickFocus.requestFocus() }
        val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(3_000)
            val state = try {
                api.quickConnectStatus(secret)
            } catch (e: ApiException) {
                if (e.code == 404 || e.code == 401) {
                    error = "That code expired. Try again."
                    quick = null
                    return@LaunchedEffect
                }
                continue
            } catch (e: Exception) {
                continue
            }
            if (state.authenticated) {
                try {
                    api.loginWithQuickConnect(secret)
                    finishSignIn()
                } catch (e: Exception) {
                    error = e.message ?: "Quick Connect sign-in failed"
                    quick = null
                }
                return@LaunchedEffect
            }
        }
        if (quick?.secret == secret) {
            error = "That code expired. Try again."
            quick = null
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.text.copy(alpha = 0.3f),
        focusedLabelColor = c.text,
        unfocusedLabelColor = c.muted,
        cursorColor = c.accent,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
    )
    val textStyle = TextStyle(fontFamily = Inter, fontSize = 18.sp)

    androidx.compose.material3.MaterialTheme(colorScheme = darkColorScheme(primary = c.accent)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText("Sleek", size = 44.sp, weight = FontWeight.ExtraBold)
                    SText("TV", size = 44.sp, weight = FontWeight.ExtraBold, color = c.accent)
                }

                if (pending != null) {
                    // ---------- Quick Connect code ----------
                    SText("Sign in with Quick Connect", size = 18.sp, weight = FontWeight.SemiBold)
                    SText(
                        "On your phone or computer, open Jellyfin, go to your profile, choose Quick Connect, and enter:",
                        size = 15.sp,
                        color = c.muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surface)
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SText(
                            pending.code.chunked(3).joinToString("  "),
                            size = 56.sp,
                            weight = FontWeight.ExtraBold,
                            color = c.text,
                        )
                    }
                    SText("Waiting for approval…", size = 14.sp, color = c.faint)
                    Spacer(Modifier.height(6.dp))
                    SleekButton(
                        text = "Use a password instead",
                        onClick = { quick = null },
                        modifier = Modifier.focusRequester(quickFocus),
                    )
                } else {
                    // ---------- Server + password ----------
                    SText("Sign in to your Jellyfin server", size = 16.sp, color = c.muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { SText("Server address", size = 14.sp) },
                        placeholder = { SText("http://192.168.1.50:8096", size = 14.sp, color = c.faint) },
                        singleLine = true,
                        textStyle = textStyle,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField),
                    )
                    SleekButton(
                        text = if (busy) "Connecting…" else "Sign in with Quick Connect",
                        onClick = { startQuickConnect() },
                        primary = true,
                    )
                    SText("or use your username and password", size = 13.sp, color = c.faint)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { SText("Username", size = 14.sp) },
                        singleLine = true,
                        textStyle = textStyle,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { SText("Password", size = 14.sp) },
                        singleLine = true,
                        textStyle = textStyle,
                        colors = fieldColors,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SleekButton(
                        text = if (busy) "Signing in…" else "Sign in",
                        onClick = { submitPassword() },
                    )
                }

                error?.let { SText(it, size = 14.sp, color = c.accent) }
            }
        }
    }
}
