package com.intercept.presentation.incoming

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import android.telephony.TelephonyManager
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.presentation.components.StatusDot
import com.intercept.presentation.navigation.Routes
import com.intercept.presentation.theme.InterceptTheme
import com.intercept.presentation.theme.Machine
import com.intercept.presentation.theme.RiskCriticalOnRoom
import com.intercept.presentation.theme.RiskSuspiciousOnRoom
import com.intercept.presentation.theme.Room
import com.intercept.presentation.theme.RoomInk
import com.intercept.presentation.theme.RoomMuted
import com.intercept.presentation.theme.RoomRaised
import com.intercept.telecom.InterceptInCallService
import kotlinx.coroutines.launch

/**
 * The ring, and the decision. This sits in the room rather than on paper
 * because it is the first half of the live interception — tapping the primary
 * button should not feel like walking into a different building.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingCallScreen(nav: NavController, container: AppContainer) {
    val caller = container.pendingIncomingCaller ?: "Unknown caller"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A REAL cellular call ringing right now (seen via telephony state even
    // when we are not the dialer and our InCallService never fires).
    val ringingNow = remember {
        try {
            ctx.getSystemService(TelephonyManager::class.java)?.callState ==
                TelephonyManager.CALL_STATE_RINGING
        } catch (_: Exception) {
            false
        }
    }

    InterceptTheme(room = true) {
        Scaffold(
            containerColor = Room,
            topBar = {
                TopAppBar(
                    title = { Text("Incoming call") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Room,
                        titleContentColor = RoomInk,
                    ),
                )
            }
        ) { pad ->
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(RoomRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = RoomInk,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(RiskSuspiciousOnRoom, size = 8.dp)
                    Text(
                        "Not in your contacts",
                        style = MaterialTheme.typography.labelMedium,
                        color = RiskSuspiciousOnRoom,
                    )
                }
                Spacer(Modifier.height(8.dp))

                Text(
                    caller,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Machine),
                    color = RoomInk,
                )
                Spacer(Modifier.height(14.dp))

                Text(
                    "Intercept listens in, works out what they want and reads the risk live — before you say a word. " +
                        "To have the AI answer the call itself, turn on AI answering from Home.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RoomMuted,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Real call? If the phone is still ringing, answer it on speaker first — then tap below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RoomMuted,
                )

                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = {
                        busy = true
                        error = null
                        // Try to pick up the real telecom call. False = we are not
                        // the default dialer: the phone keeps ringing in the system
                        // dialer, so the user answers on speaker and AI screens
                        // through the mic (flagged below — never faked as answered).
                        val answered = try {
                            InterceptInCallService.answer()
                        } catch (_: Exception) {
                            false
                        }
                        container.pendingRealRinging = ringingNow && !answered
                        scope.launch {
                            try {
                                val sid = container.repo.startCall(caller, container.ownerName)
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RoomInk,
                        contentColor = Room,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(color = Room, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Open live screening")
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { nav.popBackStack(Routes.HOME, false) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomInk),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("I'll take it myself")
                }
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusDot(RiskCriticalOnRoom, size = 8.dp)
                        Text(
                            it,
                            color = RiskCriticalOnRoom,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
