package com.intercept.presentation.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.domain.model.SecurityReport
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(nav: NavController, container: AppContainer) {
    var sid by remember { mutableStateOf(container.lastSessionId.orEmpty()) }
    var report by remember { mutableStateOf<SecurityReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (sid.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                report = container.repo.getReport(sid.trim())
            } catch (e: Exception) {
                error = "No report: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { if (sid.isNotBlank()) load() }

    Scaffold(topBar = { TopAppBar(title = { Text("Security report") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = sid, onValueChange = { sid = it }, label = { Text("Session id") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = ::load, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator() else Text("Load report")
            }
            error?.let { Text(it, color = Color.Red) }
            report?.let { r ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("SECURITY REPORT", style = MaterialTheme.typography.titleMedium)
                                Text("Caller: ${r.caller}")
                                Text("Risk: ${r.risk} (${r.level})")
                                Text("Claimed: ${r.claimedOrg}")
                                Text("Action: ${r.action}")
                                Text("Likely objective: ${r.objective}")
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Tactics used:", style = MaterialTheme.typography.labelLarge)
                                r.tactics.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                Spacer(Modifier.height(6.dp))
                                Text("Protected:", style = MaterialTheme.typography.labelLarge)
                                r.protected.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (r.why.isNotEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Why flagged:", style = MaterialTheme.typography.labelLarge)
                                    r.why.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    items(r.transcript) { line ->
                        Text(
                            "${if (line.speaker == "caller") "Caller" else "INTERCEPT"}: ${line.text}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
