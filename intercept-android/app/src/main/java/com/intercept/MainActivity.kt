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

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INCOMING = "extra_incoming"
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
        val startAtIncoming = intent?.getBooleanExtra(EXTRA_INCOMING, false) == true
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
                        startAtAnalyze = !shared.isNullOrBlank() && !startAtIncoming,
                    )
                }
            }
        }
    }
}
