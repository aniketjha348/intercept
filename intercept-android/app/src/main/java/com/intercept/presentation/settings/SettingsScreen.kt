package com.intercept.presentation.settings



import android.app.role.RoleManager

import android.content.Intent

import android.net.Uri

import android.provider.Settings

import android.os.Build

import android.telecom.TelecomManager

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background

import androidx.compose.foundation.border

import androidx.compose.foundation.clickable

import androidx.compose.foundation.horizontalScroll

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.OutlinedButton

import androidx.compose.material3.Scaffold

import androidx.compose.material3.Switch

import androidx.compose.material3.Text

import androidx.compose.material3.TextField

import androidx.compose.material3.TopAppBar

import androidx.compose.material3.TopAppBarDefaults

import androidx.compose.runtime.Composable

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.unit.dp

import androidx.navigation.NavController

import com.intercept.di.AppContainer

import com.intercept.presentation.components.SectionLabel

import com.intercept.presentation.components.StatusDot

import com.intercept.presentation.theme.Band

import com.intercept.presentation.theme.Ink

import com.intercept.presentation.theme.Machine

import com.intercept.overlay.OverlayService
import com.intercept.service.AlwaysOnService

import com.intercept.presentation.theme.Muted

import com.intercept.presentation.theme.Paper

import com.intercept.presentation.theme.RiskCritical

import com.intercept.presentation.theme.RiskLow

import com.intercept.presentation.theme.RiskSuspicious

import com.intercept.presentation.theme.Wire

import androidx.compose.runtime.saveable.rememberSaveable

import com.intercept.di.isLocalHost

import kotlinx.coroutines.launch



/** What a status line is telling you, so the colour can say it before the words do. */

private enum class Tone { OK, WARN, BAD, INFO }



private data class StatusMsg(val text: String, val tone: Tone)



@OptIn(ExperimentalMaterial3Api::class)

@Composable

fun SettingsScreen(nav: NavController, container: AppContainer) {

    val ctx = LocalContext.current

    val scope = rememberCoroutineScope()

    /** One wording for "that text cannot be a backend URL", wherever it is judged. */
    fun invalidUrlStatus() = StatusMsg(
        "Not a usable URL — use https://your-server, or http://10.0.2.2:8000 for local dev.",
        Tone.BAD,
    )

    // rememberSaveable: a typed-but-unsaved URL must survive a rotation. The
    // switches below write straight through, so they only need remember.
    var url by rememberSaveable { mutableStateOf(container.backendUrl) }

    var simple by remember { mutableStateOf(container.simpleMode) }

    var tts by remember { mutableStateOf(container.ttsEnabled) }

    var lang by remember { mutableStateOf(container.language) }

    var autoCalls by remember { mutableStateOf(container.autoCalls) }

    var autoSms by remember { mutableStateOf(container.autoSms) }

    var liveVoice by remember { mutableStateOf(container.liveVoice) }

    var lkTransport by remember { mutableStateOf(container.livekitTransport) }

    var autoApps by remember { mutableStateOf(container.autoApps) }

    var owner by remember { mutableStateOf(container.ownerName) }

    var overlay by remember { mutableStateOf(container.overlayOn) }

    var status by remember { mutableStateOf<StatusMsg?>(null) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(ctx)) {
            overlay = true
            container.overlayOn = true
            OverlayService.start(ctx)
        } else {
            overlay = false
            container.overlayOn = false
            status = StatusMsg(
                "Overlay not allowed — turn it on to use the floating button.",
                Tone.WARN,
            )
        }
    }



    val roleLauncher = rememberLauncherForActivityResult(

        ActivityResultContracts.StartActivityForResult()

    ) {

        status = StatusMsg(

            "Screening role requested - enable Intercept in system settings if needed.",

            Tone.INFO,

        )

    }



    Scaffold(

        containerColor = Paper,

        topBar = {

            TopAppBar(

                title = { Text("Settings") },

                colors = TopAppBarDefaults.topAppBarColors(

                    containerColor = Paper,

                    titleContentColor = Ink,

                ),

            )

        }

    ) { pad ->

        Column(

            Modifier

                .fillMaxSize()

                .padding(pad)

                .verticalScroll(rememberScrollState())

                .padding(horizontal = 20.dp),

        ) {

            Spacer(Modifier.height(12.dp))



            SectionLabel("Backend")

            Spacer(Modifier.height(10.dp))

            TextField(

                value = url,

                onValueChange = { url = it },

                label = { Text("Backend URL (emulator devs: http://10.0.2.2:8000)") },

                modifier = Modifier.fillMaxWidth(),

            )

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                Button(onClick = {

                    val saved = container.trySetBackendUrl(url)

                    if (saved == null) {

                        status = invalidUrlStatus()

                    } else {

                        url = saved

                        status = StatusMsg("Backend saved: $saved", Tone.INFO)

                    }

                }) { Text("Save") }

                OutlinedButton(onClick = {

                    scope.launch {

                        // Test what is typed, not what was saved: this button
                        // used to probe the *stored* URL, so editing the field
                        // and testing reported on the previous backend.
                        val saved = container.trySetBackendUrl(url)

                        if (saved == null) {

                            status = invalidUrlStatus()

                            return@launch

                        }

                        val ok = container.repo.checkHealth()

                        val u = saved.lowercase()

                        val local = isLocalHost(saved)

                        status = when {

                            !ok -> StatusMsg("Backend unreachable: $saved", Tone.BAD)

                            !u.startsWith("https") && !local -> StatusMsg(

                                "Online â€” but use https for real users (call texts may carry OTPs).",

                                Tone.WARN,

                            )

                            else -> StatusMsg("Backend online", Tone.OK)

                        }

                    }

                }) { Text("Test") }

            }

            status?.let { st ->

                Spacer(Modifier.height(10.dp))

                Row(

                    verticalAlignment = Alignment.CenterVertically,

                    horizontalArrangement = Arrangement.spacedBy(8.dp),

                ) {

                    StatusDot(

                        when (st.tone) {

                            Tone.OK -> RiskLow

                            Tone.WARN -> RiskSuspicious

                            Tone.BAD -> RiskCritical

                            Tone.INFO -> Muted

                        },

                        size = 8.dp,

                    )

                    Text(st.text, style = MaterialTheme.typography.bodySmall, color = Ink)

                }

            }



            Spacer(Modifier.height(28.dp))

            SectionLabel("How it behaves")

            Spacer(Modifier.height(4.dp))

            SwitchRow(

                title = "Family / simple mode",

                subtitle = "Big plain warnings, no jargon.",

                checked = simple,

            ) { simple = it; container.simpleMode = it }

            SwitchRow(

                title = "Guardian voice",

                subtitle = "Speak AI replies aloud.",

                checked = tts,

            ) { tts = it; container.ttsEnabled = it }

            TextField(
                value = owner,
                onValueChange = { owner = it; container.ownerName = it },
                label = { Text("Your first name (optional)") },
                placeholder = { Text("Guardian talks like family") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SwitchRow(

                title = "Real-time voice (beta)",

                subtitle = "Talk live instead of turn-by-turn. Needs good network.",

                checked = liveVoice,

            ) { liveVoice = it; container.liveVoice = it }

            SwitchRow(

                title = "Studio transport (beta)",

                subtitle = "LiveKit mic publish + agent audio. Transcript still flows.",

                checked = lkTransport,

            ) { lkTransport = it; container.livekitTransport = it }



            Spacer(Modifier.height(28.dp))

            SectionLabel("Auto-protect")

            Spacer(Modifier.height(4.dp))

            SwitchRow(

                title = "Auto-answer unknown calls",

                subtitle = "AI screens strangers on its own.",

                checked = autoCalls,

            ) { autoCalls = it; container.autoCalls = it; AlwaysOnService.sync(ctx) }

            SwitchRow(

                title = "Auto-scan stranger SMS",

                subtitle = "Risky texts raise an alert.",

                checked = autoSms,

            ) { autoSms = it; container.autoSms = it; AlwaysOnService.sync(ctx) }

            SwitchRow(

                title = "Auto-scan app messages",

                subtitle = "WhatsApp, Telegram, Signal, Instagram… — zero paste.",

                checked = autoApps,

            ) { autoApps = it; container.autoApps = it; AlwaysOnService.sync(ctx) }

            SwitchRow(

                title = "Floating button",

                subtitle = "Quick actions over any app.",

                checked = overlay,

            ) {
                if (it) {
                    if (Settings.canDrawOverlays(ctx)) {
                        overlay = true
                        container.overlayOn = true
                        OverlayService.start(ctx)
                    } else {
                        try {
                            overlayLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                        } catch (_: Exception) {
                            status = StatusMsg(
                                "Could not open overlay settings.",
                                Tone.BAD,
                            )
                        }
                    }
                } else {
                    overlay = false
                    container.overlayOn = false
                    OverlayService.stop(ctx)
                }
            }

            if (OverlayService.isMiui()) {
                Text(
                    "Xiaomi needs one more switch: Security app → Permissions → allow pop-up windows for Intercept AI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                OutlinedButton(
                    onClick = {
                        if (!OverlayService.openMiuiPermEditor(ctx)) {
                            status = StatusMsg(
                                "Could not open the Xiaomi editor — find it in the Security app manually.",
                                Tone.WARN,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Xiaomi permission editor") }
            }



            Spacer(Modifier.height(28.dp))

            SectionLabel("Language")

            Spacer(Modifier.height(10.dp))

            Text(

                "Auto-detects per message.",

                style = MaterialTheme.typography.bodySmall,

                color = Muted,

            )

            Spacer(Modifier.height(10.dp))

            Row(

                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),

                horizontalArrangement = Arrangement.spacedBy(8.dp),

            ) {

                listOf("auto" to "Auto", "hi" to "हिंदी", "hinglish" to "Hinglish", "en" to "English")

                    .forEach { (code, label) ->

                        ChoicePill(label = label, selected = lang == code) {

                            lang = code

                            container.language = code

                        }

                    }

            }



            Spacer(Modifier.height(28.dp))

            SectionLabel("System roles")

            Spacer(Modifier.height(12.dp))

            OutlinedButton(

                onClick = {

                    try {

                        val rm = ctx.getSystemService(RoleManager::class.java)

                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {

                            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))

                        } else {

                            status = StatusMsg(

                                "Call-screening role not available on this device.",

                                Tone.WARN,

                            )

                        }

                    } catch (e: Exception) {

                        status = StatusMsg("Role request failed: ${e.message}", Tone.BAD)

                    }

                },

                modifier = Modifier.fillMaxWidth()

            ) { Text("Enable call screening (system role)") }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(

                onClick = {

                    try {

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                            val rm = ctx.getSystemService(RoleManager::class.java)

                            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {

                                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))

                                status = StatusMsg(

                                    "Choose Intercept as the Phone app to auto-answer real calls.",

                                    Tone.INFO,

                                )

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

                        status = StatusMsg("Dialer request failed: ${e.message}", Tone.BAD)

                    }

                },

                modifier = Modifier.fillMaxWidth()

            ) { Text("Set as default Phone app (answer real calls)") }



            Spacer(Modifier.height(28.dp))

            SectionLabel("This device")

            Spacer(Modifier.height(10.dp))

            Text(

                container.userId,

                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Machine),

                color = Muted,

            )

            Spacer(Modifier.height(32.dp))

        }

    }

}



@Composable

private fun SwitchRow(

    title: String,

    subtitle: String,

    checked: Boolean,

    onChange: (Boolean) -> Unit,

) {

    Row(

        Modifier.fillMaxWidth().padding(vertical = 10.dp),

        horizontalArrangement = Arrangement.SpaceBetween,

        verticalAlignment = Alignment.CenterVertically,

    ) {

        Column(Modifier.weight(1f).padding(end = 12.dp)) {

            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink)

            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)

        }

        Switch(checked = checked, onCheckedChange = onChange)

    }

}



/** A single choice, in ink when it is the one in force. */

@Composable

private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {

    val shape = RoundedCornerShape(percent = 50)

    Text(

        label,

        modifier = Modifier

            .clip(shape)

            .background(if (selected) Ink else Band)

            .border(1.dp, if (selected) Ink else Wire, shape)

            .clickable(onClick = onClick)

            .padding(horizontal = 16.dp, vertical = 9.dp),

        style = MaterialTheme.typography.labelLarge,

        color = if (selected) Paper else Ink,

    )

}

