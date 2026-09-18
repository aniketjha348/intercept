package com.intercept.presentation.home

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.intercept.BuildConfig
import com.intercept.data.DemoScript
import com.intercept.di.AppContainer
import com.intercept.domain.model.UpdateInfo
import com.intercept.presentation.navigation.Routes
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

    // In-app updater: compare with /app/latest on every launch.
    LaunchedEffect(Unit) {
        scope.launch {
            update = container.repo.checkUpdate(BuildConfig.VERSION_CODE)
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
        ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
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
                ) { Text(if (downloading) "..." else if (container.language == "hi") "डाउनलोड" else "Download") }
            },
            dismissButton = {
                if (!u.force) TextButton(onClick = { update = null }) { Text("Later") }
            }
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text("INTERCEPT") }) }) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF102027))) {
                Column(Modifier.padding(16.dp)) {
                    Text("🛡 AI Social Engineering Firewall", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Something stands between you and dangerous communication.",
                        color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (!container.setupDone) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("🛡 Auto-protect is OFF", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "2-minute setup → unknown calls auto-answered + AI-screened, stranger SMS auto-scanned.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { nav.navigate(Routes.SETUP) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn on auto-protect") }
                    }
                }
            } else {
                val autoWhat = listOf(
                    if (container.autoCalls) "calls" else null,
                    if (container.autoSms) "SMS" else null,
                ).filterNotNull()
                Text(
                    if (autoWhat.isEmpty()) "Auto-protect on — enable call/SMS toggles in Setup."
                    else "✅ Auto-protect on (${autoWhat.joinToString(" + ")}). Strangers handled, contacts ring through.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32)
                )
            }
            Button(
                onClick = {
                    container.pendingIncomingCaller = DemoScript.callerNumber
                    nav.navigate(Routes.INCOMING)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Text("  Simulate incoming scam call")
            }
            OutlinedButton(
                onClick = { nav.navigate(Routes.ANALYZE) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text("  Analyze message / screenshot / URL / QR")
            }
            OutlinedButton(
                onClick = { nav.navigate(Routes.REPORTS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null)
                Text("  Last security report")
            }
            OutlinedButton(
                onClick = { nav.navigate(Routes.SETTINGS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Text("  Settings")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "SCREEN unknown calls  •  ANALYZE any message  •  PROTECT with AI guardian",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
        }
    }
}
