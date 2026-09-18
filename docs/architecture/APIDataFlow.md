# API Data Flow Documentation

> **Version:** v0.4.4  
> **Purpose:** Understanding how data flows between the Android application and the backend

---

## Overview

The Intercept application communicates with the backend through a REST API for AI-powered analysis. This document explains the complete data flow from user interaction to AI response.

---

## 1. Device Registration Flow

### Initial Setup Sequence

```
┌─────────────────┐
│  First Launch   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ AppContainer    │
│ Create User ID  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ API Call: POST  │─────────────────────┐
│ /register         │                     │
│ {device_id: "..."}│                    │
└────────┬────────┘                     │
         │                                │
         ▼                                │
┌─────────────────┐                      │
│ Backend: Generate│                     │
│ Stable User ID   │                     │
└────────┬────────┘                     │
         │                                │
         ▼                                │
┌─────────────────┐                      │
│ Response:       │                      │
│ {user_id: "..."}│                      │
└────────┬────────┘                      │
         │                                │
         ▼                                │
┌─────────────────┐                      │
│ Store locally   │                      │
│ in preferences  │                      │
└────────┬────────┘                      │
         │                                │
         └────────────────────────────────┘
```

### Code Implementation

```kotlin
// AppContainer.kt - User ID generation
val userId: String
    get() {
        // Check if already registered
        prefs.getString("user_id", null)
            ?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Generate device ID
        val device = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString()
        
        // Register with backend
        if (registering.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    api.register(mapOf("device_id" to device))["user_id"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { prefs.edit().putString("user_id", it).apply() }
                } catch (_: Exception) { }
            }
        }
        return userId
    }
```

---

## 2. Call Screening Data Flow

### Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    CALL SCREENING FLOW                          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────┐     1. Device detects call
│ Incoming Call   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     2. CallScreeningService.onScreenCall()
│ InterceptScreeningService                        │
│ decides: auto-answer or route to UI              │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼ AUTO    ▼ MANUAL
┌─────────┐ ┌─────────────────────┐
│ Answer  │ │Full-screen Notify  │
│ Immediate│ │User taps to screen  │
└────┬────┘ └──────────┬──────────┘
     │                   │
     ▼                   ▼
┌─────────────────┐ ┌─────────────────────┐
│ InterceptInCallService      │ │ MainActivity     │
│ answer()          │ │ → IncomingCallScreen│
└────────┬──────────┘ └──────────┬──────────┘
         │                         │
         └───────────┬─────────────┘
                     │
                     ▼
         ┌─────────────────────┐
         │ AutoScreenService   │
         │ starts screening    │
         └──────────┬──────────┘
                     │
    ┌────────────────┼────────────────┐
    │                │                │
    ▼                ▼                ▼
┌──────────┐  ┌─────────────┐  ┌─────────────┐
│  STT     │  │  AI Analysis│  │   TTS       │
│(Speech→Text)│ │(Risk check) │  │(Text→Speech)│
└────┬─────┘  └──────┬──────┘  └──────┬──────┘
     │               │                │
     └───────────────┼────────────────┘
                     │
                     ▼
         ┌─────────────────────┐
         │ LiveCallScreen      │
         │ shows transcript    │
         └─────────────────────┘
```

### Detailed Steps

#### Step 1: Call Detection
```kotlin
// InterceptScreeningService.kt
override fun onScreenCall(callDetails: Call.Details) {
    val number = callDetails.handle?.schemeSpecificPart
    
    // Decision point
    val shouldAutoAnswer = container.setupDone && 
                           container.autoCalls && 
                           ContactHelper.isUnknown(this, number)
}
```

#### Step 2: Auto-Answer Path
```kotlin
// If shouldAutoAnswer == true
val response = CallResponse.Builder()
    .setAllowCall(true)        // Critical: allows call through
    .setSkipCallLog(false)
    .build()
respondToCall(callDetails, response)
```

#### Step 3: Call Answering
```kotlin
// InterceptInCallService.kt
private fun maybeAutoAnswer(call: Call): Boolean {
    // Immediate answer - no delay!
    call.answer(0)  // Answer on speaker
    setAudioRoute(CallAudioState.ROUTE_SPEAKER)
    
    // Start AI screening
    AutoScreenService.screenCall(applicationContext, number)
    return true
}
```

#### Step 4: STT Processing
```kotlin
// AutoScreenService.kt - startEars()
ears.start(
    onPartialText = { /* Update interim */ },
    onFinalText = { text ->
        // Send to AI for analysis
        val turn = container.repo.sendCallerTurn(sid, text)
        if (!turn.reply.isBlank()) {
            container.speakBest(sid, turn.reply, forCall = true)
        }
    }
)
```

#### Step 5: AI Analysis Request
```kotlin
// InterceptRepositoryImpl.kt - sendCallerTurn()
suspend fun sendCallerTurn(sessionId: String, text: String): CallerTurn {
    val request = mapOf(
        "session_id" to sessionId,
        "transcript" to text,
        "type" to "call"
    )
    
    return api.analyze(request)
        .let { response ->
            CallerTurn(
                reply = response.reply ?: "",
                risk = response.risk,
                level = response.level,
                mustTerminate = response.mustTerminate
            )
        }
}
```

#### Step 6: AI Response
```json
// Request to /analyze
POST /analyze HTTP/1.1
Content-Type: application/json
X-User-Id: u_abc123...

{
  "session_id": "sess_123456",
  "transcript": "Hello, I'm calling about your account...",
  "type": "call"
}

// Response from /analyze
{
  "risk": 85,
  "level": "CRITICAL",
  "simple": "High risk: potential scam detected",
  "why": [
    "Urgent language detected",
    "Threats to shut off service",
    "Request for sensitive information"
  ],
  "reply": "This call has been identified as a potential scam. Ending call.",
  "must_terminate": true
}
```

#### Step 7: Voice Response Generation
```json
// Request to /speak
POST /speak HTTP/1.1
Content-Type: application/json

{
  "session_id": "sess_123456",
  "text": "This call has been identified as a potential scam. Ending call."
}

// Response: Binary audio stream
```

---

## 3. SMS Screening Data Flow

```
┌─────────────────┐
│ SMS Received    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ SmsReceiver     │
│ onReceive()     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Extract sender  │
│ and body        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Check:          │
│ - setupDone?    │
│ - autoSms?      │
│ - unknown?      │
└────────┬────────┘
         │ YES
         ▼
┌─────────────────┐
│ AutoScreenService│
│ .screenSms()     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ API: POST /analyze│
│ {text: ..., type: "sms"}│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Check risk level│
│ If >= 25: Alert  │
└─────────────────┘
```

---

## 4. Error Handling Flow

```
┌─────────────────┐
│ API Request     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Success?        │
└────┬───────┬────┘
     │ YES    │ NO
     ▼         ▼
┌─────────┐ ┌───────────────┐
│ Process │ │ Handle Error  │
│ Response│ │ - Log         │
│         │ │ - Retry       │
│         │ │ - Fallback    │
└─────────┘ └───────────────┘
```

### Error Types

| Error Type | Handling | Impact |
|------------|----------|--------|
| Network timeout | Retry 3x, then fail | Screening delayed |
| API unreachable | Cache locally, warn user | Feature unavailable |
| Invalid response | Log, skip analysis | No risk detected |
| Permission denied | Request permission | Feature blocked |

---

## 5. State Management

### Key State Variables

```kotlin
// AppContainer.kt - Persistent state
var backendUrl: String           // API endpoint
var setupDone: Boolean           // Setup completed
var autoCalls: Boolean           // Auto-answer enabled
var autoSms: Boolean             // Auto-SMS enabled
var ttsEnabled: Boolean          // Voice responses enabled
var liveVoice: Boolean           // Realtime voice mode

// Runtime state (not persistent)
var pendingIncomingCaller: String?    // Current call number
var lastSessionId: String?          // Last AI session
val sessionCallers: Map<String, String>  // Call ID → Number

// AutoScreenService companion object (static)
@Volatile var activeCallSession: String? = null
@Volatile var activeCallNumber: String? = null
```

### State Flow During Call

```
Call Ringing → onCallAdded() → maybeAutoAnswer()
                    │
                    ▼
            [Answer] + [Screen] → activeCallSession = "sess_123"
                    │
                    ▼
            AI Analysis Loop (STT → API → TTS)
                    │
                    ▼
            [User Override] OR [AI Terminate]
                    │
                    ▼
         finishCall() → activeCallSession = null
```

---

## 6. Request/Response Schema

### Header Format

```
POST /analyze HTTP/1.1
Host: intercept-backend-1446503107.ap-south-1.elb.amazonaws.com
Content-Type: application/json
X-User-Id: u_abc123def456...
```

### Analysis Request

```typescript
interface AnalyzeRequest {
  session_id: string;        // Unique session identifier
  transcript: string;        // Audio transcribed to text
  type: "call" | "sms";      // Content type
}
```

### Analysis Response

```typescript
interface AnalyzeResponse {
  risk: number;              // 0-100 risk score
  level: string;             // LOW | MEDIUM | HIGH | CRITICAL
  simple: string;            // Plain explanation
  why: string[];             // Detailed reasons
  reply: string;             // AI response text
  must_terminate: boolean;   // Should call be ended?
}
```

### Voice Request

```typescript
interface SpeakRequest {
  session_id: string;
  text: string;              // Text to convert to speech
}
```

### Speak Response

```
HTTP 200 OK
Content-Type: audio/wav

[Binary audio data - 16kHz, 16-bit PCM]
```

---

## 7. Performance Considerations

### Latency Budget

| Component | Target | Critical Path |
|-----------|--------|---------------|
| API round-trip | < 500ms | High |
| STT processing | < 200ms | Medium |
| TTS generation | < 300ms | Medium |
| Total call setup | < 2s | Critical |

### Optimization Strategies

1. **Connection Pooling**
   ```kotlin
   OkHttpClient.Builder()
       .connectTimeout(20, TimeUnit.SECONDS)
       .readTimeout(60, TimeUnit.SECONDS)
   ```

2. **Request Caching**
   - User ID cached locally
   - Session state in memory

3. **Parallel Processing**
   - STT runs in background
   - API calls non-blocking

---

## 8. Security Considerations

### Data Flow Security

```
Device Audio
     │
     ▼
┌─────────┐
│ STT (on-device) │  ← Audio never leaves device here
└─────────┘
     │
     ▼
┌─────────┐
│ Text to API │  ← Only transcript sent (not raw audio)
└─────────┘
     │
     ▼
┌─────────┐
│ AI Analysis │  ← Risk assessment
└─────────┘
```

### User Headers

All requests include the `X-User-Id` header for session tracking without personal data.

---

*Document Version: v0.4.4*  
*Last Updated: September 2026*