package com.intercept.presentation.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.domain.model.ChatLine
import com.intercept.presentation.navigation.Routes
import com.intercept.service.AutoScreenService
import com.intercept.telecom.InterceptInCallService

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
    ) { granted -> if (granted) vm.startListening() }

    fun ensureMicThenListen() {
        if (s.listening) {
            vm.stopListening()
            return
        }
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) vm.startListening() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(sid) {
        vm.connect()
        // Service already driving this session? Just watch — no second mic/TTS.
        if (AutoScreenService.activeCallSession == sid) return@LaunchedEffect
        // Real telecom call up? Route audio + start ears automatically.
        if (InterceptInCallService.hasCall()) {
            vm.beginRealScreening()
            ensureMicThenListen()
        }
    }
    LaunchedEffect(s.transcript.size) {
        if (s.transcript.isNotEmpty()) listState.animateScrollToItem(s.transcript.size - 1)
    }

    val levelColor = Color(s.level.color)
    Scaffold(topBar = { TopAppBar(title = { Text("LIVE CALL") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Text("Caller: $caller", style = MaterialTheme.typography.bodyMedium)
            if (s.realCall) {
                Text(
                    "📞 Real call on speaker — AI is screening live",
                    color = Color(0xFF1565C0), style = MaterialTheme.typography.bodySmall
                )
            }
            if (s.listening) {
                Text(
                    "🎙 Listening…" + if (s.interim.isNotEmpty()) " “${s.interim}”" else "",
                    color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(6.dp))

            if (simpleMode) {
                // §16 family mode: plain warning, no jargon.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (s.risk >= 50) Color(0xFFC62828) else Color(0xFF2E7D32)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (s.risk >= 50) "🚨 THIS PERSON MAY BE TRYING TO TRICK YOU."
                            else "✅ Call looks safe so far.",
                            color = Color.White, style = MaterialTheme.typography.titleMedium
                        )
                        if (s.simple.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(s.simple.ifEmpty { "Stay alert." }, color = Color.White)
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Risk: ${s.risk}", style = MaterialTheme.typography.titleMedium)
                            Text(s.level.label, color = levelColor, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { s.risk / 100f },
                            color = levelColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (s.chain.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.chain.forEach { AssistChip(onClick = {}, label = { Text(it.stage) }) }
                            }
                        }
                    }
                }
            }

            if (s.humanMode) {
                Text(
                    "● YOU ARE SPEAKING — AI monitoring continues",
                    color = Color(0xFFC62828), modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            if (s.similar != null) {
                Text("⚠ ${s.similar}", color = Color(0xFFEF6C00), style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                items(s.transcript) { line -> ChatBubble(line) }
            }

            if (!simpleMode && s.guardianText.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                    Text("INTERCEPT: ${s.guardianText}", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
            }
            if (!simpleMode && s.why.isNotEmpty()) {
                Text("Why flagged:", style = MaterialTheme.typography.labelLarge)
                s.why.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
            }

            if (s.ended) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828))) {
                    Text(
                        s.endedReason.ifEmpty { "Call ended." },
                        color = Color.White, modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { nav.navigate(Routes.REPORTS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("View security report")
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = { ensureMicThenListen() }) {
                        Icon(
                            if (s.listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Listen to caller",
                            tint = if (s.listening) Color(0xFFC62828) else Color.Gray
                        )
                    }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Caller says… (mic or type)") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.sendCallerText(input); input = "" }) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Scripted demo stays out of real screenings: fake turns would
                    // pollute a genuine call's report (and the caller's ears).
                    if (!s.realCall && AutoScreenService.activeCallSession != sid) {
                        OutlinedButton(onClick = { vm.playDemo() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text("Demo scam")
                        }
                    }
                    if (s.offerTakeover && !s.humanMode) {
                        OutlinedButton(onClick = { vm.takeover() }, modifier = Modifier.weight(1f)) {
                            Text("Take over")
                        }
                    }
                    Button(
                        onClick = { vm.endCall() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = null)
                        Text("End")
                    }
                }
            }
            s.error?.let { Text(it, color = Color.Red, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    val isGuardian = line.speaker != "caller"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isGuardian) Arrangement.End else Arrangement.Start) {
        Text(
            line.text,
            modifier = Modifier
                .background(
                    if (isGuardian) Color(0xFFE3F2FD) else Color(0xFFF5F5F5),
                    MaterialTheme.shapes.medium
                )
                .padding(8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Spacer(Modifier.height(4.dp))
}
