# Intercept - AI Call Screening Documentation

> **Version:** v0.4.4  
> **Last Updated:** September 2026

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [System Architecture](#system-architecture)
3. [Core Components](#core-components)
4. [Backend Integration](#backend-integration)
5. [API Documentation](#api-documentation)
6. [Deployment Guide](#deployment-guide)
7. [Testing Guide](#testing-guide)
8. [Troubleshooting](#troubleshooting)

---

## Executive Summary

**Intercept** is an AI-powered call screening application that intercepts unknown calls and messages, analyzes them for security risks, and allows users to decide whether to engage before speaking to the caller.

### Key Features:
- 🤖 **AI Screening** - Real-time voice-to-text analysis of incoming calls
- 🛡 **Auto-Answer** - Automatic pickup for unknown callers (when enabled)
- 📱 **Headless Operation** - Works without user interaction after setup
- 📊 **Risk Detection** - Identifies scams, spam, and suspicious calls
- 💬 **Voice Responses** - AI speaks responses through TTS

[Jump to Architecture](#system-architecture) | [Jump to API](#api-documentation) | [Jump to Testing](#testing-guide)

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        ANDROID DEVICE                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────┐       ┌──────────────────┐                 │
│  │   MainActivity  │──────▶│    NavGraph      │                 │
│  │  (Entry Point)  │       │ (Navigation)     │                 │
│  └─────────────────┘       └────────┬─────────┘                 │
│                                     │                           │
│  ┌─────────────────┐       ┌────────▼─────────┐                 │
│  │ LiveCallScreen  │◀──────│ CallScreening    │                 │
│  │   (UI)          │       │ Service          │                 │
│  └────────┬────────┘       └────────┬─────────┘                 │
│           │                         │                           │
│  ┌────────▼────────┐       ┌────────▼─────────┐                 │
│  │ AutoScreenService│◀─────│ InCallService  │                 │
│  │  (Headless AI)   │       │ (Default Dialer)│                 │
│  └────────┬────────┘       └────────┬─────────┘                 │
│           │                         │                           │
│  ┌────────▼────────┐       ┌────────▼─────────┐                 │
│  │ Voice Processing│       │ Call Handling    │                 │
│  │ (STT/TTS)       │       │ (Telecom API)    │                 │
│  └─────────────────┘       └──────────────────┘                 │
│                                     ▲                           │
│  ┌──────────────────────────────────┼──────────────────┐      │
│  │                                  │                  │      │
│  │        BACKEND SERVER             │                  │      │
│  │  ┌─────────────────────────────┐  │                  │      │
│  │  │    Intercept API            │◀─┘                  │      │
│  │  │  - /analyze                 │                     │      │
│  │  │  - /register                │                     │      │
│  │  │  - /speak                   │                     │      │
│  │  │  - /health                  │                     │      │
│  │  └─────────────────────────────┘                     │      │
│  └───────────────────────────────────────────────────────┘      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Data Flow Diagram

```
INCOMING CALL
      │
      ▼
┌─────────────────┐
│CallScreeningService│ ◄── System intercepts call
└─────────────────┘
      │
      ▼
┌─────────────────┐
│  Should Auto-   │
│  Answer?        │
└─────────────────┘
      │ YES
      ▼
┌─────────────────┐       ┌──────────────────┐
│InterceptInCallService│──▶│AutoScreenService │──▶ AI Analysis Loop
└─────────────────┘       └──────────────────┘
                                  │
                                  ▼
                           ┌─────────────┐
                           │ Live UI     │
                           │ (Composable)│
                           └─────────────┘
```

---

## Core Components

### 1. MainActivity.kt
**Entry Point** - Application launcher and intent router

```mermaid
graph TD
    A[MainActivity] --> B{Permission Check}
    B --> C[Request Permissions]
    B --> D{Call Type?}
    D -->|Auto-Screened| E[LiveCallScreen{sid}]
    D -->|Manual| F[NavGraph]
    D -->|Share Sheet| G[AnalyzeScreen]
```

**Key Responsibilities:**
- Request runtime permissions
- Route incoming intents to appropriate screens
- Handle application-wide initialization

### 2. InterceptInCallService.kt
**Default Dialer Service** - Answers calls when app is set as Phone

```kotlin
class InterceptInCallService : InCallService()
```

**Flow:**
1. `onCallAdded()` - Detect incoming call
2. `maybeAutoAnswer()` - Check if should auto-answer
3. `answer(0)` - Answer on speaker
4. Forward to `AutoScreenService.screenCall()`

### 3. AutoScreenService.kt
**Headless AI Engine** - Runs without UI

```kotlin
class AutoScreenService : Service()
```

**Key Methods:**
- `handleCall()` - Main screening loop
- `startEars()` - Start STT listening
- `finishCall()` - End call and report

### 4. InterceptScreeningService.kt
**Call Screening Gateway** - System-level call filter

```kotlin
class InterceptScreeningService : CallScreeningService()
```

**Decision Logic:**
```
Unknown Caller
       │
       ├─ Auto-Calls ON + Dialer Role → setAllowCall(true) → AutoAnswer
       └─ Auto-Calls OFF → Silence + Full-Screen Notification → Manual Tap
```

---

## Backend Integration

### API Base URL
```
http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com
```

### Mobile Client Flow

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Device    │───▶│   Backend   │◀───│   Server    │
│             │    │             │    │             │
│  Register   │───▶│ /register   │───▶│ Generate ID │
│             │    │             │    │             │
│  Analyze    │───▶│ /analyze    │───▶│ AI Model    │
│  Text/Call  │    │             │    │             │
│             │    │             │    │             │
│  Speak      │───▶│ /speak      │───▶│ TTS Service │
│  Response   │    │             │    │             │
│             │    │             │    │             │
│  Health     │───▶│ /health     │───▶│ Status OK   │
│  Check      │    │             │    │             │
└─────────────┘    └─────────────┘    └─────────────┘
```

### Data Flow

1. **Registration Phase:**
 ```
 Device ID → /register → User ID → Stored locally
 ```

2. **Call Screening Phase:**
 ```
 Call Audio → STT (on device) → Transcript → /analyze → 
 Risk Score → Response Text → /speak → TTS → Speaker
 ```

3. **State Persistence:**
 - `sessionCallers` - Maps call IDs to phone numbers
 - `lastSessionId` - Most recent AI session
 - `activeCallSession` - Currently screening call (static var)

---

## API Documentation

### Base Endpoint
```
http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com
```

### Endpoints

#### POST `/register`
**Purpose:** Device registration and user ID generation

**Request:**
```json
{
  "device_id": "uuid-string"
}
```

**Response:**
```json
{
  "user_id": "u_abc123..."
}
```

**Headers:**
```
X-User-Id: u_abc123...
```

#### POST `/analyze`
**Purpose:** Analyze call transcript or text for risk

**Request:**
```json
{
  "session_id": "sess_123",
  "transcript": "Hello, I'm calling about your account...",
  "type": "call" | "sms"
}
```

**Response:**
```json
{
  "risk": 75,
  "level": "HIGH",
  "simple": "This looks like a scam",
  "why": ["Urgent language", "Threats mentioned"],
  "reply": "Please speak to a human agent",
  "must_terminate": true
}
```

#### POST `/speak`
**Purpose:** Generate voice response for AI output

**Request:**
```json
{
  "session_id": "sess_123",
  "text": "Hello, this is an automated message..."
}
```

**Response:** Binary audio file (WAV/OGG format)

#### GET `/health`
**Purpose:** Check backend availability

**Response:**
```json
{
  "status": "healthy",
  "latency_ms": 120
}
```

---

## Deployment Guide

### Android Manifest Requirements

```xml
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Service Declarations

```xml
<service android:name=".telecom.InterceptInCallService"
    android:permission="android.permission.BIND_INCALL_SERVICE" />

<receiver android:name=".sms.SmsReceiver"
    android:permission="android.permission.BROADCAST_SMS" />

<service android:name=".service.AutoScreenService"
    android:foregroundServiceType="microphone|phoneCall" />
```

### System Role Requirements

1. **Call Screening Role** (`ROLE_CALL_SCREENING`)
   - Allows intercepting calls
   - Silencing unknown callers

2. **Default Dialer Role** (`ROLE_DIALER`)
   - Required for auto-answer functionality
   - App becomes the Phone UI

### Setup Flow

```
1st Launch
       │
       ▼
┌─────────────────┐
│ Check Backend   │
│ URL             │
└─────────────────┘
       │
       ▼
┌─────────────────┐
│ Request         │
│ Permissions     │
└─────────────────┘
       │
       ▼
┌─────────────────┐
│ Grant Roles:    │
│ - Call Screening│
│ - Default dialer│
└─────────────────┘
       │
       ▼
┌─────────────────┐
│ Enable Features:│
│ - Auto-calls    │
│ - Auto-SMS      │
└─────────────────┘
       │
       ▼
   Setup Complete!
```

---

## Testing Guide

### Manual Testing Checklist

#### Phase 1: Setup Verification
- [ ] Install APK on test device
- [ ] Grant all runtime permissions
- [ ] Enable "Call screening" role
- [ ] Set as default dialer
- [ ] Verify "Setup Complete" status

#### Phase 2: Auto-Answer Testing
```
Test Scenario 1: Unknown Number Call
1. Get call from unknown number
2. Should auto-answer within 2 seconds
3. AI should start screening immediately
4. Live transcript should show

Test Scenario 2: Known Contact Call
1. Call from saved contact
2. Should ring through normally
3. Auto-screening should NOT trigger

Test Scenario 3: Auto-Calls Disabled
1. Turn off auto-answer in Settings
2. Get unknown number call
3. Notification should appear
4. Manual tap should start screening
```

#### Phase 3: Voice Screening Testing
```
Test Scenario 4: Caller Speaks
1. During live screening, speak as caller
2. AI should transcribe and reply
3. Caller should hear AI response

Test Scenario 5: AI Hangup
1. AI detects scam/high risk
2. Call should auto-hangup
3. Security report generated
```

### Automated Tests

#### Unit Tests Location
```
app/src/test/java/com/intercept/
├── CallScreeningTest.kt
├── AutoScreenServiceTest.kt
└── ContactHelperTest.kt
```

#### Run Tests
```bash
./gradlew test
./gradlew connectedAndroidTest
```

### Debug Logs
Enable verbose logging in `InterceptApp.kt`:
```kotlin
if (BuildConfig.DEBUG) {
    // Enable debug logs
}
```

---

## Troubleshooting

### Common Issues

#### "Auto-answer not working"
**Check:**
1. Default dialer role granted? (`Settings → Apps → Default apps`)
2. `autoCalls` toggle enabled? (`Settings → Auto-protect`)
3. Call screening role granted? (`Settings → Phone → Call screening`)
4. Permissions granted? (`Settings → App permissions`)

#### "Call screening delayed"
**Root Cause:** Fixed in v0.4.4 - removed 1.5s delay
**Solution:** Update to latest version

#### "No audio during screening"
**Check:**
1. Microphone permission granted
2. Speaker volume up
3. `liveVoice` or TTS enabled

#### "Connect to backend fails"
**Check:**
1. Device internet connection
2. Backend URL correct
3. Firewall/VPN not blocking

### Logcat Tags
```
InterceptInCallService: Call answering logic
AutoScreenService: AI screening loop
InterceptScreeningService: Call screening decisions
MainActivity: App lifecycle
```

### Force Debug Mode
```bash
adb shell am start -n com.intercept/.MainActivity
adb logcat | grep Intercept
```

---

## Technical References

- [Android Telecom Documentation](https://developer.android.com/guide/topics/connectivity/telecom)
- [CallScreeningService API](https://developer.android.com/reference/android/telecom/CallScreeningService)
- [InCallService API](https://developer.android.com/reference/android/telecom/InCallService)
- [Foreground Services](https://developer.android.com/guide/components/foreground-services)

---

*For questions or bug reports, please open an issue on the project repository.*