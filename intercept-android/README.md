# INTERCEPT Android app — the phone app is the product

Native Kotlin + Jetpack Compose, clean layers per architecture doc §23.
Every screen talks to the **same backend pipeline** — no separate detectors.

## Open & run

1. Start backend: `cd D:\intercept\intercept-backend` → `uvicorn app.main:app --port 8000`
2. Open `D:\intercept\intercept-android` in **Android Studio** (Ladybug+), let Gradle sync, press **Run** on an emulator (API 30+).
3. Emulator default backend URL `http://10.0.2.2:8000` already set. Real phone → same Wi-Fi, put PC's LAN IP in Settings → Test.

## Hackathon demo flow (2 min)

1. Home → **Simulate incoming scam call** → **Let INTERCEPT answer**
2. Live screen → **Demo scam**: scripted bank-KYC lines stream in, risk climbs LOW → CRITICAL, attack chain chips appear, guardian replies speak aloud (TTS).
3. At CRITICAL: red banner + **View security report** → tactics, protected OTP, transcript.
4. Home → **Analyze** → paste a message/URL/QR or attach a screenshot → same risk engine.

## Language: हिंदी / Hinglish / English

Settings → language (Auto default). Auto-detects per message; server replies,
explanations, simple-mode warnings and the TTS voice all follow it.
Try Analyze with: `मैं SBI बैंक से बोल रहा हूँ, तुरंत OTP बताएं`.

## How it maps to the doc

| Doc | App |
|---|---|
| §23 presentation/ | `presentation/home, incoming, live, analyze, reports, settings` |
| §23 domain/ | `domain/model` (RiskLevel, Sig, Stage, TurnResult, SecurityReport) |
| §23 data/ | `data/api` (Retrofit DTOs mirror backend JSON) + `CallWebSocket` (§25 events) |
| §23 telecom/ | `InterceptScreeningService` silences unknown callers + full-screen tap-to-screen |
| §12 Call Guardian | `LiveCallScreen` + TTS guardian voice + takeover + auto-terminate banner |
| §16 Simple mode | Settings toggle → big plain warnings, zero jargon |

## Real calls (production, two phones)

Receiver phone setup (once, ~2 min — Android forces these taps, no app can skip them):

1. Install the APK, open the app → **Turn on auto-protect**.
2. Allow permissions (mic, phone, SMS, contacts) → enable screening role → set as Phone app.
3. Keep both auto-toggles ON. Point Settings → backend at your public URL and **Test** it.

Then zero taps, forever: unknown call → auto-answered on speaker → AI talks →
CRITICAL → auto-cut + report notification. Stranger SMS → auto-scanned → risky
ones raise an alert. Saved contacts always ring through normally.

Honest limits: audio couples through the speaker (no root trick); Hindi STT
quality follows the phone's Google speech services; backend must be publicly
reachable (mobile data can't see your laptop — deploy it first).

## Demo shortcut (no second phone)

Home → **Simulate incoming scam call** → typed/scripted input, same risk engine.
