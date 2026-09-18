package com.intercept.presentation.analyze

import android.util.Base64
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.intercept.di.AppContainer
import com.intercept.domain.model.Analysis
import com.intercept.domain.model.RiskLevel
import com.intercept.presentation.components.InlineLoader
import com.intercept.presentation.components.RiskMeter
import com.intercept.presentation.components.SectionLabel
import com.intercept.presentation.components.StatusDot
import com.intercept.presentation.theme.Band
import com.intercept.presentation.theme.Ink
import com.intercept.presentation.theme.Machine
import com.intercept.presentation.theme.Muted
import com.intercept.presentation.theme.Paper
import com.intercept.presentation.theme.RiskCritical
import com.intercept.presentation.theme.RiskHigh
import com.intercept.presentation.theme.RiskLow
import com.intercept.presentation.theme.Wire
import com.intercept.presentation.theme.risk
import com.intercept.presentation.theme.riskTint
import kotlinx.coroutines.launch

private val TABS = listOf("SMS", "WhatsApp", "URL", "QR", "Screenshot")

/** The verdict in words, so the number is not the only thing carrying the answer. */
private fun verdictOf(level: RiskLevel): String = when (level) {
    RiskLevel.LOW -> "Nothing dangerous here"
    RiskLevel.SUSPICIOUS -> "Worth a second look"
    RiskLevel.HIGH -> "Most likely a scam"
    RiskLevel.CRITICAL -> "This is an attack"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(nav: NavController, container: AppContainer) {
    // Wire codes are model values; the labels are ours.
    val tabs = listOf("SMS", "WHATSAPP", "URL", "QR", "SCREENSHOT")
    var tab by remember { mutableStateOf("SMS") }
    var input by remember { mutableStateOf("") }
    var imageB64 by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Analysis?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sharedConsumed by remember { mutableStateOf(false) }

    fun doAnalyze() {
        if (busy) return
        busy = true; error = null; result = null
        scope.launch {
            try {
                result = when (tab) {
                    "URL" -> container.repo.analyzeUrl(input.trim(), null)
                    "QR" -> container.repo.analyzeQr(input.trim().ifEmpty { null }, imageB64)
                    "SCREENSHOT" -> container.repo.analyzeScreenshot(input.trim().ifEmpty { null }, imageB64)
                    else -> container.repo.analyzeText(input, tab)
                }
            } catch (_: Exception) {
                error = "Could not reach Intercept — check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    // Share-sheet entry: prefill + verify immediately, once.
    LaunchedEffect(Unit) {
        val shared = container.pendingSharedText
        if (!shared.isNullOrBlank() && !sharedConsumed) {
            sharedConsumed = true
            container.pendingSharedText = null
            input = shared
            if (shared.contains("http", ignoreCase = true) || shared.contains("upi://")) {
                tab = "URL"
            }
            doAnalyze()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    imageB64 = Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                error = "Could not read image: ${e.message}"
            }
        }
    }

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text("Analyze") },
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
            Spacer(Modifier.height(8.dp))

            // Five channels will not fit on a narrow phone; let the strip scroll.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.zip(TABS).forEach { (code, label) ->
                    ChoicePill(label = label, selected = tab == code) {
                        tab = code
                        result = null
                        // An attachment belongs to the tab it was picked on.
                        // Carrying it over sent an old screenshot to the QR
                        // decoder, and left a hidden image deciding whether the
                        // button looked ready on a tab that showed nothing.
                        imageB64 = null
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                label = {
                    Text(
                        when (tab) {
                            "URL" -> "Paste link"
                            "QR" -> "QR payload / context"
                            else -> "Paste message text"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            if (tab == "SCREENSHOT" || tab == "QR") {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (imageB64 == null) "Attach a screenshot" else "Image attached (tap to change)")
                }
                if (imageB64 != null && input.isBlank()) {
                    // Reading a picture needs OCR/QR support that this build does
                    // not ship, so an image-only check can come back clean having
                    // read nothing. Better to say so than to show a green verdict
                    // the app never actually earned.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reading a picture is limited on this build — paste the message text above as well for a check you can trust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // An image only counts where an image is actually read, so the button
            // can never run a check on nothing and call it safe.
            val hasInput = when (tab) {
                "QR", "SCREENSHOT" -> input.isNotBlank() || imageB64 != null
                else -> input.isNotBlank()
            }
            Button(
                onClick = { doAnalyze() },
                enabled = !busy && hasInput,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) InlineLoader() else Text("Analyze with Intercept")
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(RiskCritical, size = 8.dp)
                    Text(it, color = RiskCritical, style = MaterialTheme.typography.bodySmall)
                }
            }

            result?.let { r ->
                Spacer(Modifier.height(20.dp))

                if (container.simpleMode) {
                    // §16 family mode: plain warning, no jargon, no ambiguity.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (r.risk >= 50) RiskCritical else RiskLow,
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
                                    if (r.risk >= 50) "This looks dangerous. Do not follow its instructions."
                                    else "This looks safe.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Paper,
                                )
                            }
                            if (r.simple.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(r.simple, color = Paper)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // The verdict, then the reading behind it.
                Card(
                    colors = CardDefaults.cardColors(containerColor = r.level.riskTint),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            verdictOf(r.level),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Ink,
                        )
                        Spacer(Modifier.height(12.dp))
                        RiskMeter(score = r.risk, level = r.level)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Decision: ${r.policy}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Machine),
                            color = Ink,
                        )
                    }
                }

                if (r.why.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel("Why it was flagged")
                    Spacer(Modifier.height(10.dp))
                    r.why.forEach {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatusDot(r.level.risk, size = 6.dp, modifier = Modifier.padding(top = 7.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink)
                        }
                    }
                }

                // The loud family card above already carries this in simple mode — unless
                // it had nothing to carry, in which case the plain reading still shows.
                val plainWords = r.simple.ifEmpty { r.userMessage }
                if (plainWords.isNotEmpty() && (!container.simpleMode || r.simple.isEmpty())) {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel("In plain words")
                    Spacer(Modifier.height(10.dp))
                    Text(plainWords, style = MaterialTheme.typography.bodyLarge, color = Ink)
                }

                if (r.signals.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel("Signals found")
                    Spacer(Modifier.height(6.dp))
                    r.signals.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    s.code,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Machine),
                                    color = Ink,
                                )
                                Text(
                                    s.category,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                            Text(
                                "${(s.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Machine),
                                color = Muted,
                            )
                        }
                        HorizontalDivider(color = Wire)
                    }
                }

                if (r.objective.isNotEmpty() && r.objective != "Unknown") {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Likely objective: ${r.objective}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
                r.similar?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusDot(RiskHigh, size = 7.dp, modifier = Modifier.padding(top = 6.dp))
                        Text(it, color = RiskHigh, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** A single channel, in ink when it is the one in force. */
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
