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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
 * This deployment's own AI number — the LiveKit phone number the dispatch rule
 * answers on.
 *
 * Baked in so setup is zero-touch: otherwise the owner must paste a DID the
 * backend already knows, and a screen that says "not set up on the server yet"
 * is the one step a live demo cannot absorb. Type a different number in the
 * field below to override it.
 */
private const val DEFAULT_FORWARD_NUMBER = "+14843174128"

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
    var number by remember { mutableStateOf("") }
    var binding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        var f = container.repo.forwarding()
        // Zero-touch setup. Binding the number is a plain HTTP call with no
        // carrier involvement, so it is safe to do silently; the USSD arming
        // below still goes to the network and is done once, visibly.
        if (!f.configured && DEFAULT_FORWARD_NUMBER.isNotBlank()) {
            if (container.repo.bindForwarding(DEFAULT_FORWARD_NUMBER)) {
                f = container.repo.forwarding()
            }
        }
        info = f
        if (f.configured) container.forwardNumber = f.number
        loading = false
        // Arm it for the owner: dialing the carrier code needs this phone, and
        // missing calls while setting it up is the whole problem we are solving.
        if (f.configured && !armed) {
            val ok = CallForwarding.activateWhenBusy(ctx, f.number)
            if (ok) {
                CallForwarding.activateWhenUnanswered(ctx, f.number)
                armed = true
                failed = false
                container.forwardingOn = true
                message = "Forwarding is on — unknown calls go to the AI now."
            } else {
                failed = true
                message = "Allow phone permission, then tap Turn on AI answering."
            }
        }
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
                        "No AI number is set for you yet. Paste the number your " +
                            "calls should go to (your telephony provider's DID, e.g. a " +
                            "LiveKit phone number) and save it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        label = { Text("AI number (+919000000000)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                binding = true
                                failed = false
                                val ok = container.repo.bindForwarding(number)
                                if (ok) {
                                    val f = container.repo.forwarding()
                                    info = f
                                    if (f.configured) container.forwardNumber = f.number
                                    message = "Saved. Now turn on AI answering below."
                                } else {
                                    failed = true
                                    message = "Could not save — check the number (country code, no spaces)."
                                }
                                binding = false
                            }
                        },
                        enabled = !binding && number.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (binding) "Saving…" else "Save number") }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "PS: don't have a number yet? Get one in your LiveKit Cloud " +
                            "dashboard → Telephony → Phone numbers, then attach it to the " +
                            "Intercept AI dispatch rule.",
                        style = MaterialTheme.typography.bodySmall,
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (f.source == "user")
                            "This is your own dedicated number."
                        else
                            "Shared number — a dedicated line for you can be assigned.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
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
