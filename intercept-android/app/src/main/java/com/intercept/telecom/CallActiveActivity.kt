package com.intercept.telecom

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intercept.service.AutoScreenService

/**
 * On-screen call controls. As the default Phone app, INTERCEPT owns the
 * in-call UI — without this, outgoing calls and contact calls would have no
 * screen at all. Auto-screened scam calls run headless instead (see
 * AutoScreenService) with hangup available from the alert notification.
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
    private var screening by mutableStateOf(false)

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

    private fun screenCurrentCall() {
        AutoScreenService.screenCall(this, number)
        Toast.makeText(
            this,
            "AI screening in background — see Reports after the call.",
            Toast.LENGTH_LONG
        ).show()
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
        screening = AutoScreenService.activeCallSession != null
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
        MaterialTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("INTERCEPT call") }) }) { pad ->
                Column(
                    Modifier.fillMaxSize().padding(pad).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📞", style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(number, style = MaterialTheme.typography.headlineSmall)
                    Text(stateText, style = MaterialTheme.typography.bodyMedium)
                    if (screening) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "🛡 AI is screening this call — risky callers are cut automatically.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                InterceptInCallService.setSpeaker(!speaker)
                                refresh()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (speaker) "Speaker ON" else "Speaker") }
                        Button(
                            onClick = {
                                InterceptInCallService.hangup()
                                try {
                                    finish()
                                } catch (_: Exception) {
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Hang up") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { screenCurrentCall() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🛡 Screen this call with AI") }
                }
            }
        }
    }
}
