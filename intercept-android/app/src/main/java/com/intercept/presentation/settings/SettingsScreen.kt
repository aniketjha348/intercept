package com.intercept.presentation.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.intercept.di.AppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(container.backendUrl) }
    var simple by remember { mutableStateOf(container.simpleMode) }
    var tts by remember { mutableStateOf(container.ttsEnabled) }
    var lang by remember { mutableStateOf(container.language) }
    var autoCalls by remember { mutableStateOf(container.autoCalls) }
    var autoSms by remember { mutableStateOf(container.autoSms) }
    var autoApps by remember { mutableStateOf(container.autoApps) }
    var status by remember { mutableStateOf<String?>(null) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        status = "Screening role requested — enable INTERCEPT in system settings if needed."
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Backend", style = MaterialTheme.typography.labelLarge)
            TextField(value = url, onValueChange = { url = it }, label = { Text("Backend URL (emulator: http://10.0.2.2:8000)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    container.backendUrl = url
                    status = "Backend saved: ${container.backendUrl}"
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = container.repo.checkHealth()
                        val u = container.backendUrl.trim().lowercase()
                        val local = u.contains("localhost") || u.contains("10.0.2.2") ||
                            u.contains("192.168.") || u.contains("127.0.0.1") ||
                            Regex("https?://10\\.").containsMatchIn(u) ||
                            Regex("https?://172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(u)
                        status = when {
                            !ok -> "❌ Backend unreachable"
                            !u.startsWith("https") && !local ->
                                "⚠ Online — but use https for real users (call texts may carry OTPs)."
                            else -> "✅ Backend online"
                        }
                    }
                }) { Text("Test") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Family / simple mode")
                    Text("Big plain warnings, no jargon.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = simple, onCheckedChange = { simple = it; container.simpleMode = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Guardian voice")
                    Text("Speak AI replies aloud.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = tts, onCheckedChange = { tts = it; container.ttsEnabled = it })
            }
            Text("Auto-protect (after one-time setup)", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-answer unknown calls")
                    Text("AI screens strangers on its own.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = autoCalls, onCheckedChange = { autoCalls = it; container.autoCalls = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-scan stranger SMS")
                    Text("Risky texts raise an alert.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = autoSms, onCheckedChange = { autoSms = it; container.autoSms = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-scan app messages")
                    Text("WhatsApp/Telegram notifications, zero paste.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = autoApps, onCheckedChange = { autoApps = it; container.autoApps = it })
            }
            Text("Language (auto-detects per message)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to "Auto", "hi" to "हिंदी", "hinglish" to "Hinglish", "en" to "English").forEach { (code, label) ->
                    AssistChip(
                        onClick = { lang = code; container.language = code },
                        label = { Text(label) },
                        leadingIcon = { if (lang == code) Text("✓") }
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    try {
                        val rm = ctx.getSystemService(RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                        } else {
                            status = "Call-screening role not available on this device."
                        }
                    } catch (e: Exception) {
                        status = "Role request failed: ${e.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enable call screening (system role)") }
            OutlinedButton(
                onClick = {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val rm = ctx.getSystemService(RoleManager::class.java)
                            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                                status = "Choose INTERCEPT as the Phone app to auto-answer real calls."
                                return@OutlinedButton
                            }
                        }
                        ctx.startActivity(
                            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                                .putExtra(
                                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                    ctx.packageName
                                )
                        )
                    } catch (e: Exception) {
                        status = "Dialer request failed: ${e.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Set as default Phone app (answer real calls)") }
            Text(
                "Protection ID: ${container.userId}",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

