# Deployment Guide

> **Version:** v0.4.4  
> **Purpose:** Step-by-step guide for deploying Intercept to production

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Build Configuration](#build-configuration)
3. [Signing Configuration](#signing-configuration)
4. [System Role Setup](#system-role-setup)
5. [Google Play Store](#google-play-store)
6. [Testing Checklist](#testing-checklist)
7. [Troubleshooting Deployment](#troubleshooting-deployment)

---

## Prerequisites

### Development Environment
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- Minimum SDK: 29 (Android 10)

### Account Requirements
- Google Play Developer account ($25 one-time fee)
- Keystore file for signing
- Backend server running and accessible

### Device Requirements
- Android 10+ (API 29+)
- Phone with Telecom framework
- Microphone access
- Internet connectivity

---

## Build Configuration

### App Version Scheme

```
Version Code: Increment by 1 for each release
Version Name: Major.Minor.Patch

Examples:
- v0.4.3 → build.gradle: versionCode = 10, versionName = "0.4.3"
- v0.4.4 → build.gradle: versionCode = 11, versionName = "0.4.4"
```

### build.gradle.kts Configuration

```kotlin
android {
    namespace = "com.intercept"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.intercept"
        minSdk = 29
        targetSdk = 34
        versionCode = 11  // Increment for each release
        versionName = "0.4.4"
    }

    signingConfigs {
        create("prod") {
            val ks = System.getenv("INTERCEPT_KEYSTORE") ?: ""
            if (ks.isNotBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("INTERCEPT_STORE_PASSWORD")
                keyAlias = System.getenv("INTERCEPT_KEY_ALIAS")
                keyPassword = System.getenv("INTERCEPT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false  // Keep false for debugging; enable for production
            val ks = System.getenv("INTERCEPT_KEYSTORE") ?: ""
            signingConfig = if (ks.isNotBlank()) signingConfigs.getByName("prod")
                         else signingConfigs.getByName("debug")
        }
    }
}
```

### Environment Variables for CI/CD

```bash
# Required for release builds
export INTERCEPT_KEYSTORE="/path/to/keystore.jks"
export INTERCEPT_STORE_PASSWORD="your_store_password"
export INTERCEPT_KEY_ALIAS="your_key_alias"
export INTERCEPT_KEY_PASSWORD="your_key_password"

# Backend URL (if different from default)
export INTERCEPT_BACKEND_URL="http://your-backend.com"
```

---

## Signing Configuration

### Generate Debug Keystore (for development)

```bash
# Generate debug keystore
keytool -genkey -v -keystore ../keystore/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

### Generate Production Keystore

```bash
# Generate production keystore
keytool -genkey -v -keystore intercept-release.jks \
  -alias release -storepass <password> -keypass <password> \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, OU=Your Org, O=Your Company, L=Your City, ST=Your State, C=US"
```

### Upload to Google Play Console

1. Go to Play Console → Setup → Keystore uploads
2. Upload your production keystore
3. Google Play verifies the upload

**⚠️ CRITICAL: Never commit production keystores to version control!**

---

## System Role Setup

### Required System Roles

#### 1. Call Screening Role

```kotlin
// Request in SettingsScreen.kt
val rm = ctx.getSystemService(RoleManager::class.java)
if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
}
```

**Manual User Steps:**
1. Open Settings → Apps → Special app access
2. Tap "Call screening"
3. Select "INTERCEPT AI"
4. Confirm

#### 2. Default Dialer Role

```kotlin
// Request in SettingsScreen.kt
val rm = ctx.getSystemService(RoleManager::class.java)
if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
} else {
    // Fallback for older Android versions
    ctx.startActivity(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER))
}
```

**Manual User Steps:**
1. Open Settings → Apps → Default apps
2. Tap "Phone app" or "Dialer"
3. Select "INTERCEPT AI"
4. Confirm

---

## Google Play Store

### Publishing Process

1. **Generate Release APK/AAB**
   ```bash
   ./gradlew assembleRelease
   # For Android App Bundle:
   ./gradlew bundleRelease
   ```

2. **Internal Testing Track**
   - Upload AAB to Internal track
   - Add testers (your email, team emails)
   - Collect feedback

3. **Alpha/Beta Testing**
   - Move to Alpha track for wider testing
   - Google review takes 1-3 days

4. **Production Release**
   - Move to Production track
   - Add release notes
   - Submit for review

### Release Notes Template

```
v0.4.4 - Auto-answer Fixed

🎉 Bug Fixes:
• Fixed auto-answer race condition (1.5s delay removed)
• Improved call screening service integration
• Better handling of auto-screened calls

🔍 What's Fixed:
• Unknown calls now answer immediately when auto-answer is enabled
• Call screening service properly allows calls through
• MainActivity correctly routes auto-screened call notifications

🔧 Technical Changes:
- InterceptInCallService: Immediate call answering
- InterceptScreeningService: Added setAllowCall() for auto-path
- MainActivity: Direct jump to live screen for auto calls
```

### Store Listing Requirements

**Screenshots Required:**
1. Home screen (main view)
2. Live call screening (transcript view)
3. Settings screen (auto-answer toggle)
4. Setup screen (role configuration)
5. Security report

**Privacy Policy URL:**
```
https://yourdomain.com/privacy
```

**Category:** Tools (or Communications)

---

## Testing Checklist

### Pre-Release Testing

#### Internal Testing (Before Play Store)

| Test Case | Expected Result | Status |
|-----------|-----------------|--------|
| Install from APK | App installs and opens | ⬜ |
| First launch permissions | All permissions requested | ⬜ |
| Backend connection | Health check passes | ⬜ |
| Device registration | User ID generated | ⬜ |
| Setup flow | All gates can be green | ⬜ |
| Call screening role | Role granted successfully | ⬜ |
| Default dialer role | Role granted successfully | ⬜ |

#### Auto-Answer Testing

| Test Case | Expected Result | Status |
|-----------|-----------------|--------|
| Unknown call (auto on) | Answers within 2s, AI starts | ⬜ |
| Unknown call (auto off) | Notification shown, tap → screen | ⬜ |
| Known contact call | Rings through normally | ⬜ |
| Call from blocked | Blocked, not processed | ⬜ |
| Spam number | Risk detected, call terminated | ⬜ |

#### Voice Screening Testing

| Test Case | Expected Result | Status |
|-----------|-----------------|--------|
| Caller speaks | Transcribed correctly | ⬜ |
| AI responds | Speaks through speaker | ⬜ |
| AI terminates | Call ends, report generated | ⬜ |
| User takeover | Mic captured, human response | ⬜ |

#### SMS Screening Testing

| Test Case | Expected Result | Status |
|-----------|-----------------|--------|
| Unknown sender SMS | Analyzed for risk | ⬜ |
| Risky SMS (≥25) | High priority notification | ⬜ |
| Safe SMS | Silently processed | ⬜ |
| Contact SMS | Not processed | ⬜ |

### Device Compatibility Testing

| Device Type | Android Version | Status |
|-------------|-----------------|--------|
| Pixel 4a | Android 13 | ⬜ |
| Samsung S21 | Android 12 | ⬜ |
| OnePlus 9 | Android 11 | ⬜ |
| Xiaomi Redmi Note | Android 10 | ⬜ |
| Huawei P40 | Android 10 | ⬜ |

---

## Troubleshooting Deployment

### Common Issues

#### Issue: App doesn't appear in default dialer options

**Cause:** Device manufacturer restrictions  
**Solution:** 
- Use stock Android emulator for testing
- Check OEM-specific settings (MIUI, Samsung OneUI)
- Provide manual instructions in app

#### Issue: Call screening role unavailable

**Cause:** Android version or OEM restrictions  
**Solution:**
- Works on AOSP Android 10+
- Samsung/OneUI: Settings → Biometrics and security → Other security settings
- Xiaomi: Security app → Permissions → Autostart → INTERCEPT

#### Issue: Foreground service killed

**Cause:** Battery optimization  
**Solution:**
- User must disable battery optimization for INTERCEPT
- Add instructional text in app
- Consider using a foreground service declaration

#### Issue: Unable to answer calls

**Cause:** Missing ROLE_DIALER  
**Solution:**
- Verify role is granted
- Check if device is rooted (breaks Telecom)
- Some carriers block third-party dialers

### Logcat Commands

```bash
# All Intercept logs
adb logcat | grep -i intercept

# Specific service logs
adb logcat | grep "InterceptInCallService"
adb logcat | grep "AutoScreenService"
adb logcat | grep "CallScreeningService"

# Filter by tag
adb logcat -s "InterceptInCallService" -s "AutoScreenService"
```

### Debug Flags

Enable in `InterceptApp.kt`:

```kotlin
if (BuildConfig.DEBUG) {
    // Enable verbose logging
    Log.isLoggable("Intercept", Log.VERBOSE)
}
```

### Permissions Check

```bash
# Check granted permissions
adb shell dumpsys package com.intercept | grep granted

# Check default dialer
adb shell cmd telecom get-default-dialer
```

---

## Release Checklist

### Before Committing Release

- [ ] Version code incremented
- [ ] versionName updated
- [ ] Commit message includes version
- [ ] CHANGELOG.md updated
- [ ] README.md documented changes
- [ ] GitHub release created
- [ ] Tag pushed

### Before Play Store Upload

- [ ] `assembleRelease` builds successfully
- [ ] No ProGuard/R8 warnings
- [ ] All tests pass
- [ ] Release notes written
- [ ] Screenshot assets ready
- [ ] Privacy policy URL verified

### After Release

- [ ] Monitor crash reports
- [ ] Check Play Console metrics
- [ ] Watch for user feedback
- [ ] Update documentation if needed

---

## Appendix

### Required Android Permissions

```xml
<!-- Essential for call handling -->
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_CONTACTS" />

<!-- Essential for AI screening -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />

<!-- Essential for notifications -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Essential for foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<!-- Essential for overlay floating button -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Essential for TTS -->
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

### Service Declarations

```xml
<!-- Default dialer service -->
<service android:name=".telecom.InterceptInCallService"
    android:exported="true"
    android:permission="android.permission.BIND_INCALL_SERVICE">
    <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI"
        android:value="true" />
    <intent-filter>
        <action android:name="android.telecom.InCallService" />
    </intent-filter>
</service>

<!-- Call screening service -->
<service android:name=".telecom.InterceptScreeningService"
    android:exported="true"
    android:permission="android.permission.BIND_SCREENING_SERVICE">
    <intent-filter>
        <action android:name="android.telecom.CallScreeningService" />
    </intent-filter>
</service>

<!-- Headless AI service -->
<service android:name=".service.AutoScreenService"
    android:exported="false"
    android:foregroundServiceType="microphone|phoneCall" />
```

---

*For deployment questions, contact the development team or open an issue on GitHub.*