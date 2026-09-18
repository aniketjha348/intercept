package com.intercept.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Detection itself stays automatic (SMS receiver + notification listener), and
 * when a threat lands the bubble also carries the warning banner over whatever
 * the user is doing, so the warning arrives before the tap does.
 * User-toggled, movable, closable. Needs SYSTEM_ALERT_WINDOW (asked once).
 */
class OverlayService : Service() {

    companion object {
        private const val BANNER_MS = 9000L
        private const val ACTION_FLASH = "com.intercept.action.FLASH"

        /**
         * Threat banner over whatever the user is looking at.
         *
         * Deliberately an in-process call and not startService(): a notification
         * listener callback can arrive while the app is backgrounded, and
         * Android 12+ bans starting a background service from there. When the
         * bubble is running we already share a process, so we just talk to it.
         * When it is not, the high-priority alert notification still fires and
         * AlwaysOnService puts the bubble back within a minute.
         */
        fun flash(heading: String, body: String, link: String? = null) {
            try {
                live?.showBanner(heading, body, link)
            } catch (_: Exception) {
            }
        }

        @Volatile
        private var live: OverlayService? = null

        /** Survives tab switches; shown in the panel so a missed banner is not lost. */
        @Volatile
        private var lastHeading: String? = null

        @Volatile
        private var lastLink: String? = null

        /**
         * MIUI runs a SECOND, undocumented gate ("Display pop-up windows while
         * running in the background", default OFF) that canDrawOverlays()
         * cannot see. Detect MIUI and send the user to its Security editor.
         */
        fun isMiui(): Boolean = try {
            val cl = Class.forName("android.os.SystemProperties")
            val m = cl.getMethod("get", String::class.java)
            ((m.invoke(null, "ro.miui.ui.version.name") as? String)?.isNotBlank()) == true
        } catch (_: Exception) {
            val maker = android.os.Build.MANUFACTURER ?: ""
            maker.equals("Xiaomi", true) || maker.equals("Redmi", true) ||
                maker.equals("POCO", true)
        }

        fun openMiuiPermEditor(context: Context): Boolean = try {
            val intent = android.content.Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                .putExtra("extra_pkgname", context.packageName)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }

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
    private var banner: View? = null
    private val handler = Handler(Looper.getMainLooper())

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
        live = this
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
        try {
            banner?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        bubble = null
        panel = null
        banner = null
        wm = null
        if (live === this) live = null
        handler.removeCallbacksAndMessages(null)
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
            // Silent death is why users say "it doesn't work": say it out loud,
            // point at the fix, and uncheck the toggle so state stays honest.
            try {
                android.widget.Toast.makeText(
                    this,
                    if (isMiui()) "Bubble blocked: open Security app → Permissions → allow pop-up windows for Intercept AI"
                    else "Bubble blocked: allow Display over other apps in Settings",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Exception) {
            }
            try {
                applicationContext.appContainer().overlayOn = false
            } catch (_: Exception) {
            }
            stopSelf()
        }
    }

    /**
     * The warning that beats the tap: a card over the current app the moment a
     * risky link is seen, tappable straight into Analyze with that exact URL.
     */
    private fun showBanner(heading: String, body: String, link: String?) {
        val wm = wm ?: return
        lastHeading = heading
        lastLink = link
        handler.post {
            try {
                banner?.let { wm.removeView(it) }
                banner = null
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xF214181D.toInt())
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
                box.addView(TextView(this).apply {
                    text = heading
                    textSize = 15f
                    setTextColor(0xFFFFD7A8.toInt())
                })
                if (body.isNotBlank()) {
                    box.addView(TextView(this).apply {
                        text = body
                        textSize = 13f
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                box.addView(TextView(this).apply {
                    text = if (link != null) "Tap to check this link" else "Tap to see why"
                    textSize = 13f
                    setTextColor(0xFF7CC7FF.toInt())
                    setPadding(0, dp(8), 0, 0)
                })
                box.setOnClickListener {
                    if (link != null) {
                        try {
                            applicationContext.appContainer().pendingSharedText = link
                        } catch (_: Exception) {
                        }
                    }
                    openApp(analyze = true)
                }
                val params = overlayParams(dp(12), dp(40), dp(320), WindowManager.LayoutParams.WRAP_CONTENT)
                wm.addView(box, params)
                banner = box
                handler.postDelayed({ removeBanner() }, BANNER_MS)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeBanner() {
        try {
            banner?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        banner = null
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
        // A banner that faded before the user looked is not lost — the panel
        // keeps the last verdict, with the link still one tap from a check.
        lastHeading?.let { heading ->
            box.addView(TextView(this).apply {
                text = heading
                textSize = 13f
                setTextColor(0xFFC1121F.toInt())
                setPadding(0, dp(8), 0, 0)
            })
            val link = lastLink
            box.addView(actionButton(if (link != null) "Check that link" else "See why") {
                if (link != null) {
                    try {
                        c.pendingSharedText = link
                    } catch (_: Exception) {
                    }
                }
                openApp(analyze = true)
            })
        }
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
