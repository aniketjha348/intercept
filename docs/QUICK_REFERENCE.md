# INTERCEPT Quick Reference 📋

## Team Hackathon Ready - One Page Guide

---

## 🎯 WHAT IS INTERCEPT?

AI-powered call screening app that:
- Auto-answers unknown calls
- Analyzes risks using AI
- Speaks through TTS
- Screens SMS messages

---

## 📁 KEY FILES

| File | Purpose |
|------|---------|
| `MainActivity.kt` | App entry point (line 16-103) |
| `InterceptScreeningService.kt` | Call gatekeeper (decides auto/manual) |
| `InterceptInCallService.kt` | Default dialer (answers calls) |
| `AutoScreenService.kt` | AI engine (STT → API → TTS) |

---

## ⚡ BUILD COMMANDS

**Release Build:**
```bash
cd intercept-android
./gradlew assembleRelease
```

**Build APK Bundle:**
```bash
./gradlew bundleRelease
```

**Output Location:**
`app/build/outputs/apk/release/app-release.apk`

---

## 🔧 VERSION UPDATE

**File:** `build.gradle.kts`
```gradle
versionCode = 11 → 12 (increment every release)
versionName = "0.4.4" → "0.4.5"
```

---

## 🔑 SIGNING SETUP

Generate keystore:
```bash
keytool -genkeypair -v -keystore intercept-upload.jks -alias intercept \
  -keyalg RSA -keysize 2048 -validity 10000
```

Env variables:
```bash
INTERCEPT_KEYSTORE=/path/to/keystore.jks
INTERCEPT_STORE_PASSWORD=your_password
INTERCEPT_KEY_ALIAS=intercept
INTERCEPT_KEY_PASSWORD=your_key_password
```

---

## 📱 TEST SCENARIOS

**Test 1: Auto-Answer Flow**
1. Settings → Auto-protect = ON
2. Default Dialer = INTERCEPT
3. Call from unknown number
4. ✓ Should auto-answer immediately

**Test 2: Manual Flow**
1. Settings → Auto-protect = OFF
2. Call from unknown number
3. ✓ Notification appears
4. Tap → Live screen

**Test 3: SMS Screening**
1. SMS from unknown sender
2. ✓ Risk notification

---

## 🛠️ TROUBLESHOOTING

**Call not answering:**
- Check Default Dialer permission
- Settings → Apps → Default apps → Phone app

**No notification:**
- Check Call Screening role
- Settings → Apps → Special app access

**Debug logs:**
```bash
adb logcat | grep -i intercept
```

---

## 📦 PLAY STORE UPLOAD

1. Upload signed AAB file
2. Add privacy policy URL
3. Add 5 screenshots
4. Fill data safety form
5. Submit for review

---

## ⚠️ CRITICAL DOs

- ✅ Increment `versionCode` every release
- ✅ Use Play Store signing key (never debug)
- ✅ Test on real device before upload
- ✅ Monitor crash reports after release

---

## 🚫 NEVER DOs

- ❌ Change `applicationId` after first upload
- ❌ Share keystore passwords
- ❌ Commit keystore files to git

---

## 📞 QUICK CONTACT

Backend URL:
```
http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com
```

API Endpoints:
- `POST /register` - Device registration
- `POST /analyze` - Risk analysis
- `POST /speak` - Voice generation
- `GET /health` - Health check

---

*Team Hackathon Ready - Best of luck! 🏆*