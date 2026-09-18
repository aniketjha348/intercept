package com.intercept

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.intercept.presentation.navigation.NavGraph
import com.intercept.presentation.theme.InterceptTheme
import com.intercept.service.AutoScreenService

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INCOMING = "extra_incoming"
        const val EXTRA_ANALYZE = "extra_analyze"
        const val EXTRA_WATCH_SID = "extra_watch_sid"
        const val EXTRA_CALL_NUMBER = "extra_call_number"
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** Mic + phone permissions up front so screening works on first real call. */
    private fun requestCallPermissions() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECEIVE_SMS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            try {
                permissionLauncher.launch(missing.toTypedArray())
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCallPermissions()
        // Process death kills the bubble but keeps the toggle: heal the gap.
        try {
            val container = appContainer()
            if (container.overlayOn &&
                android.provider.Settings.canDrawOverlays(this)
            ) {
                com.intercept.overlay.OverlayService.start(this)
            }
        } catch (_: Exception) {
        }

        // Auto-protect daemon: match the running service to the user's switches.
        // Started from a foreground entry point on purpose — a background start
        // is banned on Android 12+, so it never happens from Application.onCreate.
        try {
            com.intercept.service.AlwaysOnService.sync(this)
        } catch (_: Exception) {
        }

        // Check if this is an auto-screened call (headless path)
        val caller = try {
            AutoScreenService.activeCallNumber ?: intent?.getStringExtra(EXTRA_CALL_NUMBER)
        } catch (_: Exception) {
            null
        }

        // Auto-screened calls start at the live screen directly
        val startLiveSid = try {
            AutoScreenService.activeCallSession
        } catch (_: Exception) {
            null
        }

        // Only set startAtIncoming if not already in auto-screening session
        val startAtIncoming = intent?.getBooleanExtra(EXTRA_INCOMING, false) == true &&
            startLiveSid == null

        val startAtAnalyze = intent?.getBooleanExtra(EXTRA_ANALYZE, false) == true &&
            !startAtIncoming
        // Notification tap during headless screening: jump straight to the transcript.
        val watchSid = intent?.getStringExtra(EXTRA_WATCH_SID)?.takeIf { it.isNotBlank() }
            ?.takeUnless { startAtIncoming } ?: startLiveSid
        // Share-sheet entry: verify the shared link/text immediately.
        val shared = if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        } else {
            null
        }
        if (!shared.isNullOrBlank()) {
            try {
                appContainer().pendingSharedText = shared
            } catch (_: Exception) {
            }
        }
        setContent {
            InterceptTheme {
                Surface {
                    NavGraph(
                        container = appContainer(),
                        startAtIncoming = startAtIncoming,
                        startAtAnalyze = (!shared.isNullOrBlank() || startAtAnalyze) && !startAtIncoming,
                        startLiveSid = watchSid,
                    )
                }
            }
        }
    }
}
