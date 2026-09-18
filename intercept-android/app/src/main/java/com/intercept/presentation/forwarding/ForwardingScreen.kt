package com.intercept.presentation.forwarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.domain.model.Forwarding
import com.intercept.presentation.components.InlineLoader
import com.intercept.presentation.components.SectionLabel
import com.intercept.presentation.components.StatusDot
import com.intercept.presentation.theme.Ink
import com.intercept.presentation.theme.Muted
import com.intercept.presentation.theme.Paper
import com.intercept.presentation.theme.RiskCritical
import com.intercept.presentation.theme.RiskLow
import com.intercept.telecom.CallForwarding

/**
 * Turn an unknown call into a conversation the AI has on the owner's behalf.
 *
 * Arming this dials the carrier's forwarding code, so when the owner declines
 * an unknown call the network hands it to our number and the voice agent
 * answers as the other party — the caller hears the AI, the owner does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardingScreen(nav: NavController, container: AppContainer) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<Forwarding?>(null) }
    var loading by remember { mutableStateOf(true) }
    var armed by remember { mutableStateOf(container.forwardingOn) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val f = container.repo.forwarding()
        info = f
        if (f.configured) container.forwardNumber = f.number
        loading = false
    }

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("AI answers your calls") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Paper,
                    titleContentColor = Ink,
                ),
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "When an unknown number calls, Intercept declines it and the network " +
                    "hands it to the AI, which speaks with the caller on your behalf. " +
                    "The caller hears the AI — you do not. You can watch and join any call.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            Spacer(Modifier.height(20.dp))

            SectionLabel("Status")
            Spacer(Modifier.height(6.dp))
            StatusLine(armed)
            Spacer(Modifier.height(16.dp))

            when {
                loading -> InlineLoader()
                info == null -> Text("Checking…", color = Muted)
                info?.configured != true -> {
                    Text(
                        "AI answering is not set up on the server yet. Add " +
                            "ASSISTANT_FORWARD_NUMBER on the backend, then come back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
                else -> {
                    val f = info!!
                    Text(
                        "Calls will be forwarded to ${f.number}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val ok = CallForwarding.activateWhenBusy(ctx, f.number)
                            if (ok) {
                                CallForwarding.activateWhenUnanswered(ctx, f.number)
                                armed = true
                                failed = false
                                container.forwardingOn = true
                                message = "Forwarding on. Decline an unknown call and the AI takes it."
                            } else {
                                failed = true
                                message = "Could not dial the forwarding code — allow phone permission."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (armed) "Re-arm forwarding" else "Turn on AI answering") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            CallForwarding.disableWhenBusy(ctx)
                            CallForwarding.disableWhenUnanswered(ctx)
                            armed = false
                            failed = false
                            container.forwardingOn = false
                            message = "Forwarding off. Calls ring on your phone again."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Turn off and let my phone ring") }
                }
            }

            message?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) RiskCritical else Ink,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Forwarding is a network setting, billed by your carrier. If you " +
                    "uninstall Intercept, turn it off here first — otherwise calls keep " +
                    "going to the number.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusLine(armed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(if (armed) RiskLow else Muted)
        Text(
            if (armed) "AI answering is on" else "AI answering is off",
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
        )
    }
}
