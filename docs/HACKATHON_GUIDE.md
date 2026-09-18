# INTERCEPT - Hackathon Guide 🚀

> **Version:** v0.4.4  
> **Ready for Team Hackathon!** Simple Hindi-English guide for anyone to understand and deploy

---

## 1. Project Overview ☕

### Kya hai Intercept?

INTERCEPT ek **AI-powered call screening app** hai jo:

- 📞 **Calls ko intercept karta hai** - Unknown numbers ko pehle screen karta hai
- 🤖 **AI samajhta hai** - Kya ye scam hai ya safe talk hai
- 🗣️ **Voice deta hai** - AI directly caller se baat karta hai
- 📱 **Zero interaction** - Setup ke baad kuchh interaction nahi

### Simple Flow

```
📞 Caller aata hai → ❓ Pata nahi kis se → 🤖 AI dhundhta hai → 💬 AI kehta hai → ✅ User jod sakta hai
```

### Key Features

| Feature | Kya hai? | Kaam Karta Hai? |
|---------|----------|-----------------|
| Auto-Answer | जब ऑन होता है | Unknown call में तुरंत answer करता है |
| AI Screening | AI वॉयस मोड | Caller को समझता और फैसला लगाता है |
| Risk Detection | 1-100 स्कोर | Scam पहचानने वाला है |
| SMS Screening | मोबाइल में | SMS को भी सुरक्षित चेक करता है |

---

## 2. Quick Start - Important Files

### Key Components

```
intercept-android/app/src/main/java/com/intercept/
├── MainActivity.kt          # Entry point - जहाँ सब कुछ शुरू होता है
├── appContainer.kt          # Data manager - सारा data यहाँ होता है
├── telecom/
│   ├── InterceptScreeningService.kt  # Call gatekeeper - decide किया जाता है call को आओ या न आओ
│   └── InterceptInCallService.kt       # Default dialer service
└── service/
    └── AutoScreenService.kt          # AI engine - यहीं AI काम करता है
```

### Flow Summary

1. **InterceptScreeningService** - Call आने पर decision लेता है: Auto-answer या Manual
2. **InterceptInCallService** - Auto-answer होने पर answer करता है
3. **AutoScreenService** - AI चलाता है, STT लेता है, API भेजता है, TTS देता है

---

## 3. Deployment - Kaise deploy karenge? 🚀

### Build Command

```bash
# Release build
cd intercept-android
./gradlew assembleRelease

# या Play Store के लिए
./gradlew bundleRelease
```

### Version Update (Before Release)

**File:** `intercept-android/app/build.gradle.kts`

```groovy
defaultConfig {
    versionCode = 11   # → बढ़ा कर 12 कर दे
    versionName = "0.4.4"  # → बदल कर "0.4.5" कर दे
}
```

### Signing Setup (One-time)

```bash
# Generate signing key
keytool -genkeypair -v -keystore intercept-upload.jks -alias intercept -keyalg RSA -keysize 2048 -validity 10000

# Set environment variables
export INTERCEPT_KEYSTORE="/path/to/intercept-upload.jks"
export INTERCEPT_STORE_PASSWORD="your_password"
export INTERCEPT_KEY_ALIAS="intercept"
export INTERCEPT_KEY_PASSWORD="your_key_password"
```

### Play Store Upload Steps

1. **Create App** → Play Console में नया app बनाओ
2. **Upload AAB** → `app/build/outputs/bundle/release/app-release.aab`
3. **Privacy Policy** → सही URL डालो
4. **Screenshots** → 5 screenshots चाहिए
5. **Submit** → भेजो

---

## 4. Testing - Quick Test Plan

### Auto-Answer Test

1. Settings → Auto-protect → ON करो
2. Default Dialer → INTERCEPT set करो
3. Unknown number को कॉल करो
4. Verification: तुरंत answer होना चाहिए (2 seconds से कम)

### Manual Screening Test

1. Auto-protect → OFF करो
2. Unknown number को कॉल करो
3. Verification: Notification आना चाहिए
4. टैप करो → Live screen खुलना चाहिए

### SMS Test

1. कोई unknown sender SMSभेजो
2. Verification: Risk notification दिखाना चाहिए

---

## 5. Troubleshooting 🛠️

### Common Problems

| Problem | Solution |
|---------|----------|
| Call नहीं answer हो रहा | Settings → Default apps → Phone app = INTERCEPT |
| Notification नहीं आ रही | Settings → Apps → Special app access → Call screening ON |
| App crash कर रहा | Debug build डालो और logcat देखो |
| AI बोल रहा नहीं | Microphone permission ON करो |

### Quick Debug Commands

```bash
# App force stop
adb shell am force-stop com.intercept

# App restart
adb shell am start -n com.intercept/.MainActivity

# Logs dekhna
adb logcat | grep -i intercept
```

---

## 6. Team Roles Recommendation

| Role | Expectation |
|------|-------------|
| **Build Master** | सभी build commands चलाएगा |
| **Tester** | सभी test स्केनेरियो चलाएगा |
| **Doc Writer** | सभी डॉक दस्तावेज़ रखेगा |
| **Troubleshooter** | Issues solve करेगा |

---

## 7. Quick Reference Card

```
BUILD: ./gradlew assembleRelease
TEST:  ./gradlew test
DEBUG: adb logcat | grep intercept
KEY:   Don't share! Use env variables
```

---

**Ready to build!** 🔥