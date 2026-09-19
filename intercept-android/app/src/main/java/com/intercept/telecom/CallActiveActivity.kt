package com.intercept.telecom

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.intercept.presentation.components.StatusDot
import com.intercept.presentation.theme.InterceptTheme
import com.intercept.presentation.theme.Machine
import com.intercept.presentation.theme.Paper
import com.intercept.presentation.theme.RiskCritical
import com.intercept.presentation.theme.RiskCriticalOnRoom
import com.intercept.presentation.theme.RiskLowOnRoom
import com.intercept.presentation.theme.Room
import com.intercept.presentation.theme.RoomInk
import com.intercept.presentation.theme.RoomMuted
import com.intercept.presentation.theme.RoomRaised

/**
 * On-screen call controls. As the default Phone app, INTERCEPT owns the in-call
 * UI — without this, incoming, outgoing and contact calls would have no screen
 * at all. It is controls only: the app never answers a call by itself and never
 * plays AI audio into one (see InterceptInCallService for why that cannot work).
 */
class CallActiveActivity : ComponentActivity() {

    companion object {
        fun show(context: Context) {
            val intent = Intent(context, CallActiveActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var number by mutableStateOf("Call")
    private var stateText by mutableStateOf("")
    private var speaker by mutableStateOf(false)

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (!isFinishing) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent { CallActiveUi() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        number = InterceptInCallService.currentNumber() ?: "Call"
        stateText = when (InterceptInCallService.currentState()) {
            Call.STATE_DIALING -> "Dialing…"
            Call.STATE_RINGING -> "Ringing…"
            Call.STATE_ACTIVE -> "On call"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "Ended"
            else -> ""
        }
        speaker = InterceptInCallService.speakerOn()
        if (!InterceptInCallService.hasCall() && !isFinishing) {
            try {
                finish()
            } catch (_: Exception) {
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CallActiveUi() {
        InterceptTheme(room = true) {
            Scaffold(
                containerColor = Room,
                topBar = {
                    TopAppBar(
                        title = { Text("Intercept call") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Room,
                            titleContentColor = RoomInk,
                        ),
                    )
                }
            ) { pad ->
                Column(
                    Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(RoomRaised),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = null,
                            tint = RoomInk,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        number,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Machine),
                        color = RoomInk,
                    )
                    if (stateText.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusDot(
                                if (stateText == "Ended") RiskCriticalOnRoom else RiskLowOnRoom,
                                size = 8.dp,
                            )
                            Text(stateText, style = MaterialTheme.typography.bodyMedium, color = RoomMuted)
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                InterceptInCallService.setSpeaker(!speaker)
                                refresh()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (speaker) Room else RoomInk,
                                containerColor = if (speaker) RoomInk else Room,
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text(if (speaker) "Speaker on" else "Speaker") }
                        Button(
                            onClick = {
                                InterceptInCallService.hangup()
                                try {
                                    finish()
                                } catch (_: Exception) {
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RiskCritical,
                                contentColor = Paper,
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("Hang up") }
                    }
                }
            }
        }
    }
}
