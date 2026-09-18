package com.intercept.presentation.analyze

import android.util.Base64
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.intercept.domain.model.Analysis
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(nav: NavController, container: AppContainer) {
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
            } catch (e: Exception) {
                error = "Backend unreachable: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // Share-sheet entry: prefill + verify immediately, once.
    androidx.compose.runtime.LaunchedEffect(Unit) {
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

    Scaffold(topBar = { TopAppBar(title = { Text("Analyze") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEach {
                    AssistChip(onClick = { tab = it; result = null }, label = { Text(it) })
                }
            }
            TextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (tab == "URL") "Paste link" else if (tab == "QR") "QR payload / context" else "Paste message text") },
                modifier = Modifier.fillMaxWidth(), minLines = 3
            )
            if (tab == "SCREENSHOT" || tab == "QR") {
                OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (imageB64 == null) "Attach image (OCR / QR decode)" else "Image attached ✓ (tap to change)")
                }
            }
            Button(
                onClick = { doAnalyze() },
                enabled = !busy && (input.isNotBlank() || imageB64 != null),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator() else Text("Analyze with INTERCEPT")
            }
            error?.let { Text(it, color = Color.Red) }
            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Risk ${r.risk} — ${r.level.label}", color = Color(r.level.color), style = MaterialTheme.typography.titleMedium)
                        Text("Decision: ${r.policy}", style = MaterialTheme.typography.bodySmall)
                        if (r.signals.isNotEmpty()) {
                            Text("Signals:", style = MaterialTheme.typography.labelLarge)
                            r.signals.forEach { Text("• ${it.code} (${(it.confidence * 100).toInt()}%)", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (r.why.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Why flagged:", style = MaterialTheme.typography.labelLarge)
                            r.why.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (r.objective.isNotEmpty() && r.objective != "Unknown") {
                            Text("Likely objective: ${r.objective}", style = MaterialTheme.typography.bodySmall)
                        }
                        r.similar?.let { Text("⚠ $it", color = Color(0xFFEF6C00), style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(4.dp))
                        Text("Simple mode:", style = MaterialTheme.typography.labelLarge)
                        Text(r.simple.ifEmpty { r.userMessage }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
