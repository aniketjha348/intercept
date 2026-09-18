package com.intercept.presentation.reports

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.domain.model.RiskLevel
import com.intercept.domain.model.SecurityReport
import com.intercept.presentation.components.RiskMeter
import com.intercept.presentation.components.SectionLabel
import com.intercept.presentation.components.StatusDot
import com.intercept.presentation.theme.Band
import com.intercept.presentation.theme.Ink
import com.intercept.presentation.theme.Machine
import com.intercept.presentation.theme.Muted
import com.intercept.presentation.theme.Paper
import com.intercept.presentation.theme.RiskCritical
import com.intercept.presentation.theme.RiskLow
import com.intercept.presentation.theme.Wire
import com.intercept.presentation.theme.risk
import com.intercept.presentation.theme.riskTint
import kotlinx.coroutines.launch

/**
 * The record of one interception, laid out as a document rather than a stack of
 * cards: header, what happened, what was protected, and the transcript. It is
 * the receipt for a call the user never had to take.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(nav: NavController, container: AppContainer) {
    // rememberSaveable: a rotation must not drop the id you typed and swap the
    // report out for whatever session happened to run last.
    var sid by rememberSaveable { mutableStateOf(container.lastSessionId.orEmpty()) }
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

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Security report") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Paper,
                    titleContentColor = Ink,
                ),
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            TextField(
                value = sid,
                onValueChange = { sid = it },
                label = { Text("Session id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = ::load, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator() else Text("Load report")
            }

            if (sid.isBlank() && report == null && error == null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "No session yet — screen a call or run the demo first, then its report lands here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(RiskCritical, size = 8.dp)
                    Text(it, color = RiskCritical, style = MaterialTheme.typography.bodySmall)
                }
            }

            report?.let { r ->
                val level = RiskLevel.of(r.level)
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                "Interception record",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Ink,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                buildString {
                                    append(sid.trim())
                                    append("  ·  ")
                                    append("${r.turns} turns")
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Machine),
                                color = Muted,
                            )
                            Spacer(Modifier.height(18.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(level.riskTint)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    RiskMeter(score = r.risk, level = level)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Action taken: ${r.action}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink,
                                    )
                                }
                            }
                        }
                    }

                    if (r.summary.isNotBlank()) {
                        item {
                            Spacer(Modifier.height(26.dp))
                            SectionLabel("In plain words")
                            Spacer(Modifier.height(6.dp))
                            Text(r.summary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    item {
                        Spacer(Modifier.height(26.dp))
                        SectionLabel("The call")
                        Spacer(Modifier.height(6.dp))
                        FactRow("Caller", r.caller, machine = true)
                        FactRow("Claimed to be", r.claimedOrg)
                        FactRow("Objective", r.objective)
                    }

                    if (r.tactics.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(26.dp))
                            SectionLabel("What the caller tried")
                            Spacer(Modifier.height(10.dp))
                            r.tactics.forEach { Bullet(it, level.risk) }
                        }
                    }

                    if (r.why.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(26.dp))
                            SectionLabel("Why it was flagged")
                            Spacer(Modifier.height(10.dp))
                            r.why.forEach { Bullet(it, level.risk) }
                        }
                    }

                    if (r.protected.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(26.dp))
                            SectionLabel("What was protected")
                            Spacer(Modifier.height(10.dp))
                            r.protected.forEach { Bullet(it, RiskLow) }
                        }
                    }

                    if (r.transcript.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(26.dp))
                            SectionLabel("Transcript")
                        }
                        items(r.transcript) { line ->
                            TranscriptLine(
                                speaker = if (line.speaker == "caller") "Caller" else "Intercept",
                                text = line.text,
                            )
                        }
                    }

                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, machine: Boolean = false) {
    if (value.isBlank()) return
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Wire)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = if (machine) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = Machine)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = Ink,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun Bullet(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(color, size = 6.dp, modifier = Modifier.padding(top = 7.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

/** Speaker in the machine voice; the utterance stays in the reading face. */
@Composable
private fun TranscriptLine(speaker: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Box(
            Modifier
                .clip(CircleShape)
                .background(Band)
                .padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
            Text(
                speaker,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Machine),
                color = Muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}
