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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.presentation.navigation.Routes
import kotlinx.coroutines.launch

private val NEEDED = listOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.RECEIVE_SMS,
)

private enum class Gate { READY, TODO, NA }

/**
 * Hard-gated setup: Done stays locked until every gate is green (or proven
 * impossible on this device, which auto-skips). No half-protected users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(nav: NavController, container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableStateOf(0) }
    var autoCalls by remember { mutableStateOf(container.autoCalls) }
    var autoSms by remember { mutableStateOf(container.autoSms) }
    var autoApps by remember { mutableStateOf(container.autoApps) }
    var backend by remember { mutableStateOf<Gate?>(null) }
    var testingBackend by remember { mutableStateOf(false) }

    @Suppress("UNUSED_VARIABLE")
    val refresh = tick // re-evaluate every gate after each grant

    // System screens (role grants, app settings) don't call us back — re-check on return.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun neededPerms(): List<String> {
        val list = NEEDED.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    fun permsGate(): Gate =
        if (neededPerms().all {
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            }
        ) Gate.READY else Gate.TODO

    fun roleGate(role: String): Gate {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Gate.NA
            } else {
                val rm = ctx.getSystemService(RoleManager::class.java) ?: return Gate.NA
                if (!rm.isRoleAvailable(role)) Gate.NA
                else if (rm.isRoleHeld(role)) Gate.READY else Gate.TODO
            }
        } catch (_: Exception) {
            Gate.NA
        }
    }

    fun batteryGate(): Gate {
        return try {
            val pm = ctx.getSystemService(PowerManager::class.java) ?: return Gate.NA
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) Gate.READY else Gate.TODO
        } catch (_: Exception) {
            Gate.NA
        }
    }

    fun notifGate(): Gate {
        return try {
            if (NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) Gate.READY
            else Gate.TODO
        } catch (_: Exception) {
            Gate.NA
        }
    }

    fun testBackend() {
        testingBackend = true
        scope.launch {
            val ok = try {
                container.repo.checkHealth()
            } catch (_: Exception) {
                false
            }
            backend = if (ok) Gate.READY else Gate.TODO
            testingBackend = false
            tick++
        }
    }

    LaunchedEffect(Unit) { testBackend() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        container.permAsked = true
        tick++
    }

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

    fun openAppSettings() {
        try {
            ctx.startActivity(
                Intent(
                    SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        } catch (_: Exception) {
        }
    }

    val gates = listOf(
        "Backend reachable" to (backend ?: Gate.TODO),
        "Permissions (mic, phone, SMS, contacts)" to permsGate(),
        "Call-screening role" to roleGate(RoleManager.ROLE_CALL_SCREENING),
        "Default Phone app" to roleGate(RoleManager.ROLE_DIALER),
        "Battery unrestricted" to batteryGate(),
        "Notification access" to notifGate(),
    )
    val readyCount = gates.count { it.second != Gate.TODO }
    val allReady = gates.all { it.second != Gate.TODO }
    LaunchedEffect(readyCount) { container.setupProgress = readyCount }

    Scaffold(topBar = { TopAppBar(title = { Text("Turn on auto-protect") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Protection starts only when everything below is green — $readyCount of ${gates.size} ready.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "One setup, then INTERCEPT works on its own — no taps per call or message.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )

            GateRow("Backend reachable", gates[0].second, "The app is useless without its brain. Fix the URL in Settings if this fails.") {
                OutlinedButton(
                    onClick = { testBackend() },
                    enabled = !testingBackend,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testingBackend) CircularProgressIndicator() else Text("Test ${container.backendUrl}")
                }
            }

            GateRow("Permissions (mic, phone, SMS, contacts)", gates[1].second, "Mic hears callers, phone answers, SMS/Contacts know strangers.") {
                if (!container.permAsked) {
                    OutlinedButton(
                        onClick = { permLauncher.launch(neededPerms().toTypedArray()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow permissions") }
                } else {
                    OutlinedButton(
                        onClick = { openAppSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open app settings (enable all permissions)") }
                }
            }

            GateRow("Call-screening role", gates[2].second, "Lets INTERCEPT silence unknown callers.") {
                OutlinedButton(
                    onClick = { requestRole(RoleManager.ROLE_CALL_SCREENING) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Enable screening") }
            }

            GateRow("Default Phone app", gates[3].second, "Lets INTERCEPT auto-answer strangers.") {
                OutlinedButton(
                    onClick = { requestRole(RoleManager.ROLE_DIALER) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set as Phone app") }
            }

            GateRow("Battery unrestricted", gates[4].second, "Otherwise Xiaomi/Vivo/Oppo kill protection overnight. Also enable Autostart + lock in Recents.") {
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

            GateRow("Notification access", gates[5].second, "Lets INTERCEPT scan WhatsApp/Telegram messages with zero paste.") {
                OutlinedButton(
                    onClick = {
                        try {
                            ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (_: Exception) {
                        } finally {
                            tick++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow notification access") }
            }

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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto-scan app messages")
                        Switch(
                            checked = autoApps,
                            onCheckedChange = { autoApps = it; container.autoApps = it; tick++ }
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    container.setupDone = true
                    nav.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } }
                },
                enabled = allReady,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (allReady) "Done — protect me automatically" else "Finish all green steps first ($readyCount/${gates.size})") }
        }
    }
}

@Composable
private fun GateRow(label: String, gate: Gate, hint: String, action: @Composable () -> Unit) {
    val (mark, color) = when (gate) {
        Gate.READY -> "✅ " to Color(0xFF2E7D32)
        Gate.TODO -> "○ " to Color.Unspecified
        Gate.NA -> "– " to Color.Gray
    }
    Text(mark + label + if (gate == Gate.NA) " (not on this device — skipped)" else "", color = color)
    Text(hint, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    if (gate == Gate.TODO) action()
}

