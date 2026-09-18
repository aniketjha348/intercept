# INTERCEPT on Play Store — later, when ready (sideload first)

The app is already built Play-compatible: `applicationId com.intercept` (never
change it after first upload), `targetSdk 34`, 64-bit, no debug flags in release.
CI already proves `bundleRelease` on every push (artifact: `intercept-release-aab`).

## One-time: upload key

```powershell
keytool -genkeypair -v -keystore intercept-upload.jks -alias intercept `
  -keyalg RSA -keysize 2048 -validity 10000
# THEN: back it up in 2 places (lost key = lost app listing, Google can't recover it)
```

CI signing (creates a signed AAB automatically when these exist):

| Secret | Value |
|---|---|
| `INTERCEPT_KEYSTORE_B64` | `certutil -encode intercept-upload.jks tmp.b64` output |
| `INTERCEPT_STORE_PASSWORD` | keystore password |
| `INTERCEPT_KEY_ALIAS` | `intercept` |
| `INTERCEPT_KEY_PASSWORD` | key password |

Workflow addition needed then (`.github/workflows/build-apk.yml`): decode step
before the AAB build —
`echo "$INTERCEPT_KEYSTORE_B64" | base64 -d > upload.jks` + `INTERCEPT_KEYSTORE=upload.jks`.
`app/build.gradle.kts` already consumes these env vars; without them it falls
back to debug signing (sideload only, Play rejects it — by design).

## Console submission order

1. Create app → upload the signed `app-release.aab` → `versionCode` must rise every release.
2. **Privacy policy URL** → host `intercept-website/privacy.html`, paste its URL.
3. **Data safety form** — declare per permission (all are core-functionality):
   CALL_LOG/PHONE (screen + answer unknown calls), SMS (scan stranger texts),
   CONTACTS (skip saved contacts), MICROPHONE (caller STT), NOTIFICATIONS (alerts).
   Data collected: call/message content for on-device + backend risk analysis;
   OTPs/passwords are never stored (see `intercept-backend/app/db/repository.py: scrub_secrets`).
   Data shared: none with third parties (Gemini API processes text for replies only).
4. **Sensitive-permission declarations** (the hard part — record a demo video):
   - Default-dialer / call screening → show auto-answer + AI screening + hangup.
   - SMS → show stranger-SMS auto-scan + warning (contacts untouched).
5. Content rating questionnaire → target audience 18+ (fraud victims skew adult).
6. Staged rollout 20% → monitor → 100%.

## Never do

- Never change `applicationId`, never lose `intercept-upload.jks`.
- Never upload a debug-signed AAB (Play rejects it; CI artifact is proof-of-build only).
- Never add a new dangerous permission without updating this file + the form.
