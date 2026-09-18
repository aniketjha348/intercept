package com.intercept.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.intercept.MainActivity
import com.intercept.appContainer

/**
 * Floating Intercept bubble over any app (chat-head style). Honest scope:
 * it CANNOT read chat text (no accessibility snooping by design) — it is a
 * one-tap remote: protection status, jump to Analyze, jump to Reports.
 * Detection itself stays automatic (SMS receiver + notification listener).
 * User-toggled, movable, closable. Needs SYSTEM_ALERT_WINDOW (asked once).
 */
class OverlayService : Service() {

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            try {
                context.startService(Intent(context, OverlayService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, OverlayService::class.java))
            } catch (_: Exception) {
            }
        }

        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            @Suppress("DEPRECATION")
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == OverlayService::class.java.name }
        }
    }

    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var panel: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: run {
            stopSelf()
            return
        }
        showBubble()
    }

    override fun onDestroy() {
        try {
            bubble?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            panel?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        bubble = null
        panel = null
        wm = null
        super.onDestroy()
    }

    private fun overlayParams(x: Int, y: Int, w: Int, h: Int) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val wm = wm ?: return
        val icon = ImageView(this).apply {
            setImageResource(com.intercept.R.drawable.ic_bird)
            setBackgroundColor(0xFF14181D.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Intercept AI quick actions"
        }
        val params = overlayParams(dp(300), dp(220), dp(56), dp(56))
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        icon.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (dx * dx + dy * dy > 100) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        wm.updateViewLayout(icon, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(icon, params)
            bubble = icon
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun togglePanel() {
        if (panel != null) {
            try {
                panel?.let { wm?.removeView(it) }
            } catch (_: Exception) {
            }
            panel = null
            return
        }
        val wm = wm ?: return
        val c = try {
            applicationContext.appContainer()
        } catch (_: Exception) {
            return
        }
        val on = listOfNotNull(
            if (c.autoCalls) "calls" else null,
            if (c.autoSms) "SMS" else null,
            if (c.autoApps) "apps" else null,
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2FFFFFF.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val status = TextView(this).apply {
            text = if (on.isEmpty()) "Auto-protect is off — open setup."
            else "Protecting: ${on.joinToString(" + ")}."
            textSize = 14f
            setTextColor(0xFF16191E.toInt())
        }
        box.addView(status)
        box.addView(actionButton("Analyze a message") { openApp(analyze = true) })
        box.addView(actionButton("View security report") { openApp(analyze = false) })
        box.addView(actionButton("Hide bubble") {
            try {
                c.overlayOn = false
            } catch (_: Exception) {
            }
            stopSelf()
        })
        val params = overlayParams(dp(40), dp(300), dp(280), WindowManager.LayoutParams.WRAP_CONTENT)
        try {
            wm.addView(box, params)
            panel = box
        } catch (_: Exception) {
        }
    }

    private fun actionButton(label: String, onTap: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF0066CC.toInt())
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                try {
                    onTap()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun openApp(analyze: Boolean) {
        try {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (analyze) intent.putExtra(MainActivity.EXTRA_ANALYZE, true)
            startActivity(intent)
        } catch (_: Exception) {
        }
        try {
            panel?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        panel = null
    }
}
