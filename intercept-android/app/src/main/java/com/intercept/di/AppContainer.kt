package com.intercept.di

import android.content.Context
import android.content.SharedPreferences
import com.intercept.audio.InCallAudio
import com.intercept.data.InterceptRepositoryImpl
import com.intercept.data.api.InterceptApiService
import com.intercept.domain.model.CallRecord
import com.intercept.domain.repository.InterceptRepository
import com.intercept.speech.CallerStt
import com.intercept.speech.GuardianAudio
import com.intercept.speech.GuardianTts
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DEFAULT_BACKEND_URL =
    "http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com"

/** How many screened calls the phone remembers. Enough to look back over a
 *  bad week, small enough that prefs never becomes a database. */
private const val HISTORY_MAX = 50

/**
 * Local / loopback / emulator host. The only place a missing scheme may be
 * filled in as http:// — guessing it for a public host would silently downgrade
 * a real user to plaintext, when the app's whole promise is that call texts
 * (which carry OTPs) never travel in the clear.
 */
fun isLocalHost(raw: String): Boolean {
    val u = raw.trim().lowercase()
    return u.contains("localhost") || u.contains("10.0.2.2") ||
        u.contains("127.0.0.1") || u.contains("192.168.") ||
        Regex("https?://10\\.").containsMatchIn(u) ||
        Regex("https?://172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(u)
}

/**
 * The base URL we can actually hand Retrofit, or null when [raw] can't be one.
 *
 * Retrofit validates at build time and throws — which is why this must be
 * checked *before* a value is stored, not after.
 */
fun normalizeBackendUrl(raw: String): String? {
    val t = raw.trim().trimEnd('/')
    if (t.isEmpty()) return null
    val lower = t.lowercase()
    val explicit = lower.startsWith("http://") || lower.startsWith("https://")
    val candidate = when {
        explicit -> t
        isLocalHost(t) -> "http://$t"
        else -> return null
    }
    val parsed = candidate.toHttpUrlOrNull() ?: return null
    return if (parsed.host.isBlank()) null else candidate
}

/** Manual DI (no Hilt → zero setup). Emulator reaches host backend via 10.0.2.2. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("intercept", Context.MODE_PRIVATE)

    /** Production backend ships as the default (fresh installs just work);
     *  emulator devs override it in Settings to http://10.0.2.2:8000. */
    var backendUrl: String
        get() = prefs.getString("backend_url", DEFAULT_BACKEND_URL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_BACKEND_URL
        set(v) {
            // Validate here rather than only in the UI. The bad value used to be
            // persisted first and only then rejected by rebuild() — which the
            // *next* launch runs from init(), so one Save on an empty field
            // bricked the app on every start until its data was cleared.
            val clean = normalizeBackendUrl(v) ?: return
            prefs.edit().putString("backend_url", clean).apply()
            rebuild()
        }

    /** Saves [raw] when it can be a base URL. Returns what was saved, or null
     *  when the text was rejected — the caller says so, we never store it. */
    fun trySetBackendUrl(raw: String): String? {
        val clean = normalizeBackendUrl(raw) ?: return null
        backendUrl = clean
        return clean
    }

    var simpleMode: Boolean
        get() = prefs.getBoolean("simple_mode", false)
        set(v) = prefs.edit().putBoolean("simple_mode", v).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts", true)
        set(v) = prefs.edit().putBoolean("tts", v).apply()

    /** auto | hi | hinglish | en — sent with every request; server replies in it. */
    var language: String
        get() = prefs.getString("language", "auto") ?: "auto"
        set(v) = prefs.edit().putString("language", v).apply()

    /** One-time onboarding finished (permissions + roles granted). */
    var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(v) = prefs.edit().putBoolean("setup_done", v).apply()

    /** How many setup gates are green (shown on Home until setup completes). */
    var setupProgress: Int
        get() = prefs.getInt("setup_progress", 0)
        set(v) = prefs.edit().putInt("setup_progress", v).apply()

    /** True once we asked runtime permissions at least once (drives Settings fallback). */
    var permAsked: Boolean
        get() = prefs.getBoolean("perm_asked", false)
        set(v) = prefs.edit().putBoolean("perm_asked", v).apply()

    /** Auto-answer unknown calls and let the AI screen them (needs dialer role). */
    var autoCalls: Boolean
        get() = prefs.getBoolean("auto_calls", false)
        set(v) = prefs.edit().putBoolean("auto_calls", v).apply()

    /** Auto-scan incoming SMS from strangers (needs SMS permission). */
    var autoSms: Boolean
        get() = prefs.getBoolean("auto_sms", false)
        set(v) = prefs.edit().putBoolean("auto_sms", v).apply()

    /** Auto-scan WhatsApp/Telegram message notifications (needs notification access). */
    var autoApps: Boolean
        get() = prefs.getBoolean("auto_apps", false)
        set(v) = prefs.edit().putBoolean("auto_apps", v).apply()

    /** Floating bubble over any app (needs draw-over-apps permission). */
    var overlayOn: Boolean
        get() = prefs.getBoolean("overlay_on", false)
        set(v) = prefs.edit().putBoolean("overlay_on", v).apply()

    /**
     * Carrier forwarding is armed: unknown calls are declined so the network
     * hands them to the cloud AI, which talks to the caller as the other party.
     * Off = the on-device screening path (notification + tap to screen).
     */
    var forwardingOn: Boolean
        get() = prefs.getBoolean("forwarding_on", false)
        set(v) = prefs.edit().putBoolean("forwarding_on", v).apply()

    /** The number forwarding points at, cached when the screen fetched it. */
    var forwardNumber: String
        get() = prefs.getString("forward_number", "") ?: ""
        set(v) = prefs.edit().putString("forward_number", v.trim()).apply()

    /**
     * Owner's first name (optional, session-only): the guardian talks like
     * family — "Ramesh ji is busy". Never gates setup, never persisted server-side.
     */
    var ownerName: String
        get() = prefs.getString("owner_name", "") ?: ""
        set(v) = prefs.edit().putString("owner_name", v.trim().take(60)).apply()

    /** Set by screening service / home demo so routes stay free of special chars. */
    var pendingIncomingCaller: String? = null
    val sessionCallers = mutableMapOf<String, String>()
    var lastSessionId: String? = null

    /**
     * Screened calls this phone knows about, newest first.
     *
     * Reports opened on an empty "Session id" field, which only the developer
     * could fill in — the owner saw their own protection as a blank box. The
     * list is small and local: sid, caller, final risk, and when.
     */
    fun callHistory(): List<CallRecord> {
        val raw = prefs.getString("call_history", null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(CallRecord.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<CallRecord>) {
        try {
            prefs.edit()
                .putString("call_history",
                    json.encodeToString(ListSerializer(CallRecord.serializer()), list.take(HISTORY_MAX)))
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Remembered before we know how the call goes, so a crash mid-call still
     *  leaves the owner something to open. */
    fun noteCallStarted(sid: String, caller: String) {
        val entry = CallRecord(sid = sid, caller = caller, at = System.currentTimeMillis())
        saveHistory(listOf(entry) + callHistory().filterNot { it.sid == sid })
    }

    /** The call is over: keep the final reading so history says how bad it got. */
    fun noteCallEnded(sid: String, risk: Int, level: String, action: String) {
        val current = callHistory()
        val existing = current.firstOrNull { it.sid == sid }
            ?: CallRecord(sid = sid, at = System.currentTimeMillis())
        saveHistory(
            listOf(existing.copy(risk = risk, level = level, action = action)) +
                current.filterNot { it.sid == sid }
        )
    }

    /** Text shared from another app (Share → INTERCEPT); Analyze consumes it once. */
    var pendingSharedText: String? = null

    /**
     * Watching a forwarded call's session: transcript only, never touch the
     * backend session (the agent owns it) and never speak. Set when entering
     * Live from the Live-now feed; cleared on leave.
     */
    var watchOnlySid: String? = null

    /**
     * True when the user started screening a REAL ringing call we could not pick
     * up ourselves (not default dialer): they answer on speaker, AI listens
     * through the mic. Consumed once by the Live screen.
     */
    var pendingRealRinging: Boolean = false

    /**
     * Zero-friction sign-in: one random device id, server derives the stable
     * opaque user id (no name/phone/password anywhere). Until the server
     * confirms, a stable temp id keeps this user's Scam DNA consistent.
     */
    private val registering = java.util.concurrent.atomic.AtomicBoolean(false)

    val userId: String
        get() {
            prefs.getString("user_id", null)?.takeIf { it.isNotBlank() }?.let { return it }
            val device = prefs.getString("device_id", null)
                ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString("device_id", it).apply()
                }
            if (registering.compareAndSet(false, true)) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        api.register(mapOf("device_id" to device))["user_id"]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { prefs.edit().putString("user_id", it).apply() }
                    } catch (_: Exception) {
                    } finally {
                        // Still unregistered? Allow one retry on a later launch.
                        if (prefs.getString("user_id", null).isNullOrBlank()) {
                            registering.set(false)
                        }
                    }
                }
            }
            return prefs.getString("user_id", null)?.takeIf { it.isNotBlank() }
                ?: ("u_" + device.replace("-", "").take(16))
        }

    val tts = GuardianTts(appContext)

    /** Human-voice player for server-rendered guardian audio. */
    val guardianAudio = GuardianAudio(appContext)

    /**
     * Voice-first reply: server voice when available, device TTS otherwise.
     * Respects the guardian-voice toggle. Never throws.
     */
    suspend fun speakBest(sessionId: String, text: String, forCall: Boolean) {
        if (!ttsEnabled || text.isBlank()) return
        try {
            val bytes = repo.speak(sessionId, text)
            if (bytes != null && bytes.isNotEmpty()) {
                guardianAudio.play(bytes, forCall)
                return
            }
        } catch (_: Exception) {
        }
        try {
            if (forCall) tts.speakForCall(text) else tts.speak(text)
        } catch (_: Exception) {
        }
    }

    /** Barge-in / hangup: cut our own voice instantly, like an interrupted human. */
    fun stopVoice() {
        try {
            guardianAudio.stop()
        } catch (_: Exception) {
        }
        try {
            tts.stop()
        } catch (_: Exception) {
        }
    }

    /** Speaker routing for live screening of real calls. */
    val audio = InCallAudio(appContext)

    /** Fresh speech recognizer per screening session (don't reuse across calls). */
    fun callerStt(): CallerStt = CallerStt(appContext) { language }

    /** Realtime voice (beta) on/off. Off = proven STT+TTS turn path. */
    var liveVoice: Boolean
        get() = prefs.getBoolean("live_voice", false)
        set(v) = prefs.edit().putBoolean("live_voice", v).apply()

    /** LiveKit studio transport (beta) on/off. Off = raw-WS bridge path. */
    var livekitTransport: Boolean
        get() = prefs.getBoolean("livekit_transport", false)
        set(v) = prefs.edit().putBoolean("livekit_transport", v).apply()

    fun appContextForVoice(): android.content.Context = appContext

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Last base URL Retrofit actually accepted — the fallback if a bad one slips in. */
    private var lastGoodUrl = DEFAULT_BACKEND_URL

    lateinit var http: OkHttpClient
        private set
    lateinit var api: InterceptApiService
        private set
    lateinit var repo: InterceptRepository
        private set

    init {
        rebuild()
    }

    fun rebuild() {
        val base = backendUrl
        try {
            build(base)
            lastGoodUrl = base
        } catch (_: Exception) {
            // A base URL Retrofit refuses must never take the app down: restore
            // the last one it accepted (bounded — lastGoodUrl built once already).
            if (base != lastGoodUrl) {
                prefs.edit().putString("backend_url", lastGoodUrl).apply()
                rebuild()
            }
        }
    }

    private fun build(base: String) {
        http = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            // Who is calling: per-user Scam DNA without accounts or passwords.
            .addInterceptor { chain ->
                val id = try {
                    userId
                } catch (_: Exception) {
                    ""
                }
                val req = if (id.isBlank()) chain.request() else chain.request().newBuilder()
                    .header("X-User-Id", id)
                    .build()
                chain.proceed(req)
            }
            // Cold cloud backends (free-tier spin-up) need patience: one slow
            // first call must not fail the screening. Retries are on by default.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        api = Retrofit.Builder()
            .baseUrl(base.trimEnd('/') + "/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(InterceptApiService::class.java)
        repo = InterceptRepositoryImpl(
            api,
            { language },
            { sid, caller -> noteCallStarted(sid, caller) },
            { sid, risk, level, action -> noteCallEnded(sid, risk, level, action) },
        )
    }
}
