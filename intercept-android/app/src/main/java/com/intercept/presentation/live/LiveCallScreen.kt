package com.intercept.presentation.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.intercept.data.DemoScript
import com.intercept.di.AppContainer
import com.intercept.domain.model.ChatLine
import com.intercept.presentation.components.RiskMeter
import com.intercept.presentation.components.StatusDot
import com.intercept.presentation.navigation.Routes
import com.intercept.presentation.theme.InterceptTheme
import com.intercept.presentation.theme.Machine
import com.intercept.presentation.theme.Paper
import com.intercept.presentation.theme.RiskCritical
import com.intercept.presentation.theme.RiskCriticalOnRoom
import com.intercept.presentation.theme.RiskLow
import com.intercept.presentation.theme.RiskLowOnRoom
import com.intercept.presentation.theme.RiskSuspiciousOnRoom
import com.intercept.presentation.theme.Room
import com.intercept.presentation.theme.RoomInk
import com.intercept.presentation.theme.RoomMuted
import com.intercept.presentation.theme.RoomRaised
import com.intercept.presentation.theme.RoomWire
import com.intercept.presentation.theme.riskOnRoom
import com.intercept.telecom.InterceptInCallService

/**
 * The room. The one dark surface in the app, and the same surface the website
 * opens with — this is the moment the product exists for, so it gets its own
 * light (or lack of it) rather than the paper chrome used everywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCallScreen(nav: NavController, container: AppContainer, sid: String) {
    val caller = container.sessionCallers[sid] ?: "Unknown"
    val vm: LiveCallViewModel = viewModel(
        key = sid,
        factory = LiveCallViewModel.provideFactory(sid, caller, container)
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val simpleMode = container.simpleMode
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startLiveKitTransport()
    }

    fun withMic(action: () -> Unit) {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) action() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * The mic control. In the room it mutes/unmutes this phone's mic; outside it
     * joins the call's LiveKit room with the mic on. When the room is unreachable
     * the ViewModel falls back to on-device recognition by itself.
     */
    fun ensureMicThenListen() {
        if (s.lkTransport) {
            vm.setMicEnabled(!s.listening)
            return
        }
        if (s.listening) {
            vm.stopListening()
            return
        }
        withMic { vm.startLiveKitTransport() }
    }

    /** Hand this call to the owner: mic live in the room, backend in human mode. */
    fun joinLiveCall() {
        vm.joinCall()
        if (s.lkTransport) vm.setMicEnabled(true)
        else withMic { vm.startLiveKitTransport() }
    }

    // Speaker path: user answered a real call on speaker (we are not the
    // dialer). Consumed once — AI listens through the mic from here.
    val speakerPath = remember {
        container.pendingRealRinging.also { container.pendingRealRinging = false }
    }

    LaunchedEffect(sid) {
        vm.connect()
        // Watching the agent's own call: join the room MUTED so the owner hears
        // the agent without ever talking over it.
        if (container.watchOnlySid == sid) {
            vm.startWatching()
            return@LaunchedEffect
        }
        // Live demo (Home → demo ring): put the agent in the room and open the
        // mic here, so the AI starts talking to the caller on this screen with
        // no extra tap. This is the path a pitch runs on.
        if (container.demoLiveSid == sid) {
            container.demoLiveSid = null
            ensureMicThenListen()
            return@LaunchedEffect
        }
        // Real telecom call up? Start hearing and transcribing automatically.
        if (InterceptInCallService.hasCall()) {
            vm.beginRealScreening()
            ensureMicThenListen()
        } else if (speakerPath) {
            ensureMicThenListen()
        }
    }
    LaunchedEffect(s.transcript.size) {
        if (s.transcript.isNotEmpty()) listState.animateScrollToItem(s.transcript.size - 1)
    }

    InterceptTheme(room = true) {
        Scaffold(
            containerColor = Room,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Live screening", style = MaterialTheme.typography.titleMedium)
                            Text(
                                caller,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Machine),
                                color = RoomMuted,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Room,
                        titleContentColor = RoomInk,
                    ),
                )
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                if (s.watchOnly) {
                    StatusLine("Watching — the AI is handling this call", RiskLowOnRoom)
                }
                if (s.realCall) {
                    StatusLine("Real call — AI reading it live on screen", RoomMuted)
                }
                if (speakerPath && !s.realCall) {
                    StatusLine(
                        "You answered on speaker — AI hears through your mic",
                        RiskSuspiciousOnRoom,
                    )
                }
                if (s.lkTransport && s.listening) {
                    StatusLine("LiveKit — mic live, agent in the room", RiskCriticalOnRoom)
                } else if (s.lkTransport) {
                    StatusLine("LiveKit — hearing the agent, mic muted", RiskSuspiciousOnRoom)
                } else if (s.listening) {
                    StatusLine(
                        "Listening…" + if (s.interim.isNotEmpty()) " “${s.interim}”" else "",
                        RiskLowOnRoom,
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (simpleMode) {
                    // §16 family mode: plain warning, no jargon.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (s.risk >= 50) RiskCritical else RiskLow,
                            contentColor = Paper,
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StatusDot(Paper, size = 10.dp)
                                Text(
                                    if (s.risk >= 50) "This person may be trying to trick you"
                                    else "This call looks safe so far",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Paper,
                                )
                            }
                            if (s.simple.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(s.simple.ifEmpty { "Stay alert." }, color = Paper)
                            }
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = RoomRaised,
                            contentColor = RoomInk,
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            RiskMeter(score = s.risk, level = s.level, onDark = true)
                            if (s.chain.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    s.chain.forEach { StageChip(it.stage) }
                                }
                            }
                        }
                    }
                }

                // Intent banner: who the caller claims to be, and what they are
                // after. The two plain answers anyone looks for mid-call.
                val claims = s.claimed.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                val goal = s.objective.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                if (!simpleMode && (claims != null || goal != null)) {
                    Spacer(Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = RoomRaised,
                            contentColor = RoomInk,
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "Intent",
                                style = MaterialTheme.typography.labelSmall,
                                color = RoomMuted,
                            )
                            claims?.let {
                                Spacer(Modifier.height(4.dp))
                                Text("Claims to be from $it", style = MaterialTheme.typography.bodyMedium)
                            }
                            goal?.let {
                                Spacer(Modifier.height(4.dp))
                                Text("Likely goal: $it", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                if (s.humanMode) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusDot(RiskCriticalOnRoom, size = 10.dp)
                        Text(
                            "You are speaking — AI monitoring continues",
                            color = RiskCriticalOnRoom,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                s.similar?.let { similar ->
                    Spacer(Modifier.height(8.dp))
                    Text(similar, color = RoomMuted, style = MaterialTheme.typography.bodySmall)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                ) {
                    items(s.transcript) { line -> ChatBubble(line) }
                }

                if (!simpleMode && s.guardianText.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = RoomRaised,
                            contentColor = RoomInk,
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Intercept",
                                style = MaterialTheme.typography.labelSmall,
                                color = RoomMuted,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(s.guardianText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (!simpleMode && s.why.isNotEmpty()) {
                    Text(
                        "Why it was flagged",
                        style = MaterialTheme.typography.labelMedium,
                        color = RoomMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    s.why.take(5).forEach {
                        Text("— $it", style = MaterialTheme.typography.bodySmall, color = RoomMuted)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (s.ended) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = RoomRaised,
                            contentColor = RoomInk,
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StatusDot(s.level.riskOnRoom, size = 10.dp)
                                Text(
                                    s.endedReason.ifEmpty { "Call ended." },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { nav.navigate(Routes.REPORTS) }, modifier = Modifier.fillMaxWidth()) {
                        Text("View security report")
                    }
                } else if (s.watchOnly) {
                    // Silent watch: no typing, no speaking — the agent owns the
                    // call. Joining hands it to this phone (mic goes into the
                    // agent's room); stopping only ends the watch, never the call.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { joinLiveCall() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Join call")
                        }
                        OutlinedButton(onClick = { vm.endCall() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.CallEnd, contentDescription = null)
                            Text("Stop watching")
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { ensureMicThenListen() }) {
                            Icon(
                                if (s.listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = "Listen to caller",
                                tint = if (s.listening) RiskCriticalOnRoom else RoomMuted,
                            )
                        }
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Caller says… (mic or type)", color = RoomMuted) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.sendCallerText(input); input = "" }) {
                            Icon(Icons.Filled.Send, contentDescription = "Send", tint = RoomInk)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Demo exists in exactly one place: the simulated scam call
                        // from Home. A real caller is never DemoScript's number, so
                        // fake turns can never pollute a genuine call again.
                        if (caller == DemoScript.callerNumber) {
                            OutlinedButton(onClick = { vm.playDemo() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text("Demo")
                            }
                        }
                        // Your call, your choice: read the transcript and grab the
                        // mic any time — never gated on the AI offering it.
                        if (!s.humanMode) {
                            OutlinedButton(onClick = { vm.takeover() }, modifier = Modifier.weight(1f)) {
                                Text("Take over")
                            }
                        }
                        Button(
                            onClick = { vm.endCall() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RiskCritical,
                                contentColor = Paper,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.CallEnd, contentDescription = null)
                            Text("End")
                        }
                    }
                }
                s.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = RiskCriticalOnRoom, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color, size = 7.dp)
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

/** A chain stage is model output, so it is set in the machine voice. */
@Composable
private fun StageChip(text: String) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, RoomWire, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Machine),
        color = RoomMuted,
    )
}

@Composable
private fun ChatBubble(line: ChatLine) {
    val isGuardian = line.speaker != "caller"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isGuardian) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(if (isGuardian) RoomRaised else RoomWire.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                if (isGuardian) "Intercept" else "Caller",
                style = MaterialTheme.typography.labelSmall,
                color = RoomMuted,
            )
            Spacer(Modifier.height(3.dp))
            Text(line.text, style = MaterialTheme.typography.bodyMedium, color = RoomInk)
        }
    }
    Spacer(Modifier.height(8.dp))
}
