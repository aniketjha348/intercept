package com.intercept.presentation.home

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.intercept.BuildConfig
import com.intercept.data.DemoScript
import com.intercept.di.AppContainer
import com.intercept.domain.model.LiveSession
import com.intercept.domain.model.UpdateInfo
import com.intercept.presentation.components.ActionRow
import com.intercept.presentation.components.BrandMark
import com.intercept.presentation.components.InlineLoader
import com.intercept.presentation.components.SectionLabel
import com.intercept.presentation.components.StatusDot
import com.intercept.presentation.navigation.Routes
import com.intercept.presentation.theme.Ink
import com.intercept.presentation.theme.Muted
import com.intercept.presentation.theme.Paper
import com.intercept.presentation.theme.RiskLow
import com.intercept.presentation.theme.RiskSuspicious
import com.intercept.update.UpdateManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf(-1L) }
    var live by remember { mutableStateOf<List<LiveSession>>(emptyList()) }

    // In-app updater: compare with /app/latest on every launch.
    LaunchedEffect(Unit) {
        scope.launch {
            update = container.repo.checkUpdate(BuildConfig.VERSION_CODE)
        }
    }
    // Live-now: calls the AI is screening right now (forwarded or local).
    // Quiet by default — the section only exists when something is happening.
    LaunchedEffect(Unit) {
        while (true) {
            live = container.repo.liveSessions()
            kotlinx.coroutines.delay(10_000)
        }
    }

    DisposableEffect(downloadId) {
        if (downloadId < 0) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    UpdateManager.install(ctx, downloadId)
                    downloading = false
                }
            }
        }
        // Android 13+ demands an explicit export flag at registration; the
        // compat helper keeps it working back to Android 10 (our minSdk).
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    update?.let { u ->
        val notes = if (container.language == "hi") u.notesHi.ifEmpty { u.notesEn } else u.notesEn
        AlertDialog(
            onDismissRequest = { if (!u.force) update = null },
            title = { Text(if (container.language == "hi") "नया अपडेट उपलब्ध" else "Update available") },
            text = {
                Column {
                    Text("${u.versionName} • ${notes.size} changes")
                    notes.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !downloading,
                    onClick = {
                        downloading = true
                        downloadId = UpdateManager.download(ctx, u.apkUrl)
                        if (!u.force) update = null
                    }
                ) {
                    // A literal "..." is not progress. The download keeps running
                    // in the system notification either way, so show that it is
                    // working rather than pretending the button is idle.
                    if (downloading) InlineLoader()
                    else Text(if (container.language == "hi") "डाउनलोड" else "Download")
                }
            },
            dismissButton = {
                if (!u.force) TextButton(onClick = { update = null }) { Text("Later") }
            }
        )
    }

    val ready = container.setupDone
    val autoWhat = listOf(
        if (container.autoCalls) "calls" else null,
        if (container.autoSms) "SMS" else null,
        if (container.autoApps) "apps" else null,
    ).filterNotNull()

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BrandMark(size = 28.dp)
                        Text("Intercept", style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Paper,
                    titleContentColor = Ink,
                ),
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // The hero answers the only question that matters on opening: am I safe?
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(if (ready) RiskLow else RiskSuspicious, size = 12.dp)
                Text(
                    if (ready) "Protected" else "Setup unfinished",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Ink,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !ready -> "${container.setupProgress} of 6 checks done. Auto-protect stays off until they all pass."
                    autoWhat.isEmpty() -> "Auto-protect is on. Turn on calls or SMS in Setup to cover more."
                    else -> "Watching ${autoWhat.joinToString(", ")}. Strangers are handled; your contacts ring through."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            if (!ready) {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { nav.navigate(Routes.SETUP) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Finish setup") }
            }

            if (live.isNotEmpty()) {
                Spacer(Modifier.height(32.dp))
                SectionLabel("Live now")
                live.forEach { s ->
                    val ask = listOfNotNull(
                        s.claimedOrg.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                            ?.let { "claims $it" },
                        s.objective.takeIf { it.isNotBlank() && !it.equals("Unknown", true) },
                    ).joinToString(" • ")
                    ActionRow(
                        title = "AI is screening ${s.caller}",
                        // What they want, and whether it is getting worse — the
                        // two things a glance has to answer before you tap in.
                        subtitle = (if (s.escalating) "Escalating — " else "") +
                            "Risk ${s.risk} • ${s.level.label} • ${s.turns} turns" +
                            (if (ask.isNotEmpty()) " • $ask" else "") +
                            " — tap to watch",
                        onClick = {
                            // Silent watch: the agent keeps the call, we only look.
                            container.watchOnlySid = s.sessionId
                            // The room the call is actually in (SIP rooms are not
                            // our own convention), so watching hears the agent.
                            container.watchOnlyRoom = s.room
                            container.sessionCallers[s.sessionId] = s.caller
                            nav.navigate(Routes.live(s.sessionId))
                        },
                        showRule = false,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            SectionLabel("What you can do")
            ActionRow(
                title = "Analyze a message",
                subtitle = "Link, screenshot, QR or WhatsApp text",
                onClick = { nav.navigate(Routes.ANALYZE) },
                showRule = false,
            )
            ActionRow(
                title = "Let the AI answer my calls",
                subtitle = if (container.forwardingOn)
                    "On — unknown calls go to the AI" else "Off — turn on call forwarding",
                onClick = { nav.navigate(Routes.FORWARDING) },
            )
            ActionRow(
                title = "Last security report",
                subtitle = "What the caller tried, and what was protected",
                onClick = { nav.navigate(Routes.REPORTS) },
            )
            ActionRow(
                title = "Settings",
                subtitle = "Backend, language and protection switches",
                onClick = { nav.navigate(Routes.SETTINGS) },
            )

            Spacer(Modifier.height(20.dp))
            // A test affordance, not a feature — so it stays out of the main list.
            TextButton(
                onClick = {
                    container.pendingIncomingCaller = DemoScript.callerNumber
                    nav.navigate(Routes.INCOMING)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Live demo — ring a scam call, let the AI take it", color = Ink) }

            Spacer(Modifier.height(24.dp))
        }
    }
}
