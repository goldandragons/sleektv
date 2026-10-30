package com.sleektv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleektv.BuildConfig
import com.sleektv.ui.Nav
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekButton
import com.sleektv.ui.theme.LocalSleekColors
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(nav: Nav) {
    val session = nav.app.session
    val c = LocalSleekColors.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SText("Account", size = 36.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            SText("Signed in as ${session.userName ?: "unknown"}", size = 18.sp)
            SText(session.serverUrl.orEmpty(), size = 15.sp, color = c.muted)
            SText("SleekTV ${BuildConfig.VERSION_NAME}", size = 13.sp, color = c.faint)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SleekButton(
                    text = "Reload layout",
                    primary = true,
                    modifier = Modifier.focusRequester(focus),
                    onClick = {
                        status = "Reloading…"
                        scope.launch {
                            nav.refreshLayout()
                            status = "Layout updated from your server"
                        }
                    },
                )
                SleekButton(
                    text = if (confirmSignOut) "Press again to sign out" else "Sign out",
                    onClick = { if (confirmSignOut) nav.logout() else confirmSignOut = true },
                )
            }
            status?.let { SText(it, size = 14.sp, color = c.muted) }
        }
    }
}
