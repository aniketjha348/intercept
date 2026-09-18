package com.intercept.presentation.incoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.presentation.navigation.Routes
import com.intercept.service.AutoScreenService
import com.intercept.telecom.InterceptInCallService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingCallScreen(nav: NavController, container: AppContainer) {
    val caller = container.pendingIncomingCaller ?: "Unknown caller"
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Auto-protect already screening this caller? Join it — no duplicate session.
    val liveSid = AutoScreenService.activeCallSession
        .takeIf { it != null && AutoScreenService.activeCallNumber == caller }

    Scaffold(topBar = { TopAppBar(title = { Text("Incoming call") }) }) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("📞", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(8.dp))
            Text(caller, style = MaterialTheme.typography.headlineSmall)
            Text("Unknown number — silenced by INTERCEPT", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Real call? If the phone is still ringing, answer it on speaker first — then tap below.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    // Already being screened headless? Just watch it live.
                    if (liveSid != null) {
                        nav.navigate(Routes.live(liveSid)) {
                            popUpTo(Routes.HOME)
                        }
                        return@Button
                    }
                    busy = true
                    error = null
                    // Production: pick up the real telecom call (no-op for demo/simulated).
                    try {
                        InterceptInCallService.answer()
                    } catch (_: Exception) {
                    }
                    scope.launch {
                        try {
                            val sid = container.repo.startCall(caller)
                            container.sessionCallers[sid] = caller
                            container.lastSessionId = sid
                            nav.navigate(Routes.live(sid)) {
                                popUpTo(Routes.HOME)
                            }
                        } catch (e: Exception) {
                            error = "Backend unreachable: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator() else {
                    androidx.compose.material3.Icon(Icons.Filled.Shield, contentDescription = null)
                    Text(if (liveSid != null) "  Watch live screening" else "  Let INTERCEPT answer")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { nav.popBackStack(Routes.HOME, false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Icon(Icons.Filled.Call, contentDescription = null)
                Text("  I'll take it myself")
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = androidx.compose.ui.graphics.Color.Red)
            }
        }
    }
}
