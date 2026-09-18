package com.intercept.presentation.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.presentation.navigation.Routes

private val NEEDED = listOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.RECEIVE_SMS,
)

/**
 * One-time auto-protect setup. Android forces these consent taps (no app can
 * take call/SMS/mic roles silently) — after this screen, everything runs
 * with zero taps: unknown calls auto-answered + AI-screened, stranger SMS
 * auto-scanned. Saved contacts always ring through normally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(nav: NavController, container: AppContainer) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var autoCalls by remember { mutableStateOf(container.autoCalls) }
    var autoSms by remember { mutableStateOf(container.autoSms) }

    @Suppress("UNUSED_VARIABLE")
    val refresh = tick // re-check permissions/roles after every grant

    fun hasPerms(): Boolean {
        val list = NEEDED.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun roleHeld(role: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                false
            } else {
                val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
                rm.isRoleHeld(role)
            }
        } catch (_: Exception) {
            false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { tick++ }

    fun requestRole(role: String) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return
            if (rm.isRoleAvailable(role)) {
                roleLauncher.launch(rm.createRequestRoleIntent(role))
            }
        } catch (_: Exception) {
        }
    }

    fun batteryOk(): Boolean {
        return try {
            val pm = ctx.getSystemService(PowerManager::class.java)
                ?: return false
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    val permsOk = hasPerms()
    val screeningOk = roleHeld(RoleManager.ROLE_CALL_SCREENING)
    val dialerOk = roleHeld(RoleManager.ROLE_DIALER)
    val battOk = batteryOk()
    val ready = permsOk && screeningOk && dialerOk

    Scaffold(topBar = { TopAppBar(title = { Text("Turn on auto-protect") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "One setup, then INTERCEPT works on its own — no taps per call or message.",
                style = MaterialTheme.typography.bodyMedium
            )
            StatusRow("1. Permissions (mic, phone, SMS, contacts)", permsOk)
            if (!permsOk) {
                OutlinedButton(
                    onClick = {
                        val list = NEEDED.toMutableList()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            list.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permLauncher.launch(list.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow permissions") }
            }
            StatusRow("2. Call-screening role (silence strangers)", screeningOk)
            if (!screeningOk) {
                OutlinedButton(
                    onClick = { requestRole(RoleManager.ROLE_CALL_SCREENING) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Enable screening") }
            }
            StatusRow("3. Default Phone app (auto-answer strangers)", dialerOk)
            if (!dialerOk) {
                OutlinedButton(
                    onClick = { requestRole(RoleManager.ROLE_DIALER) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set as Phone app") }
            }
            StatusRow("4. Battery unrestricted (recommended)", battOk)
            if (!battOk) {
                OutlinedButton(
                    onClick = {
                        try {
                            ctx.startActivity(
                                Intent(
                                    SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        } catch (_: Exception) {
                        } finally {
                            tick++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow background running") }
            }
            Text(
                "Xiaomi / Vivo / Oppo / Realme: also enable Autostart for INTERCEPT and lock " +
                    "it in Recent apps — otherwise the phone kills auto-protect overnight.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto-answer unknown calls")
                        Switch(
                            checked = autoCalls,
                            onCheckedChange = { autoCalls = it; container.autoCalls = it; tick++ }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto-scan stranger SMS")
                        Switch(
                            checked = autoSms,
                            onCheckedChange = { autoSms = it; container.autoSms = it; tick++ }
                        )
                    }
                    Text(
                        "Saved contacts always ring through. Warnings appear as notifications.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    container.setupDone = true
                    nav.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (ready) "Done — protect me automatically" else "Finish steps 1–3 first") }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Text(
        (if (ok) "✅ " else "○ ") + label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) Color(0xFF2E7D32) else Color.Unspecified
    )
}
