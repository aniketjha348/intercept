# Deep Audit — Findings & Fixes

> **Date:** 2026-09-19 · **Scope:** `intercept-backend` (~2.8k LOC), `intercept-android` (41 Kotlin files), `livekit-agent`
> **Method:** full read of the request path (API → pipeline → risk/policy → persistence → realtime), the Android
> call/message/audio services, and the app↔backend contract. Every finding below was read in the source, not inferred.
> **Verification:** `pytest` 63 passed (28 new regression tests), `verify_contract.py` 0 failures, `agent.py` compiles.

---

## Fixed

### 1. The URL engine silently skipped scheme-less links — HIGH
`app/intelligence/url_intel.py`

`if not url or "://" not in url and not url.startswith("upi://")` rejected any bare host. Messages carry
`www.sbi-kyc-verify.xyz/verify` and `bit.ly/x` constantly — exactly the links people tap — so the engine returned
`not_a_url` for the most common attack form. The dead `urlparse(url if "://" in url else "http://" + url)` fallback
below it proved the intent.

Now: assume `http://`, but remember the scheme was absent so a bare domain is never reported as an
*insecure-http* finding (assuming a scheme must not manufacture evidence).

**Why this mattered most:** v0.5.2's notification scanner extracts `www.` links and hands them to this engine. It was
handing them straight into a `not_a_url` wall. *Tests: 5.*

### 2. The security report stored the OTPs we promise never to keep — HIGH (privacy)
`app/db/repository.py`

`save_transcript()` scrubs 4–8 digit runs before persisting; `save_report()` wrote the full report — including
`transcript` with raw caller text — straight into `SecurityReport.report`. The documented promise ("OTPs/passwords
are analyzed, never stored") had a back door.

Now `scrub_report()` applies the same scrub to the persisted copy only; the in-memory report a live session reads
stays intact. *Tests: 3.*

### 3. Sessions were never evicted — HIGH (reliability)
`app/realtime/sessions.py`

`MANAGER.calls` grew forever: every call the app ever screened stayed in memory, and `/calls/live` walked all of
them. (`scam_memory` was already bounded at 10k — this was the inconsistent one.)

Now `MAX_SESSIONS = 500`, evicting oldest **inactive** sessions only. Live screenings are never dropped; reports stay
reachable after eviction through `REPO`. *Tests: 2.*

### 4. A join token could be minted for any live call's room — HIGH (security)
`app/api/livekit.py`

`GET /livekit/token?room=intercept-<sid>` validated the *shape* of the room name but never checked the session.
App rooms are named after their sessions, so anyone who learned or guessed a session id got a microphone token for a
live screened call. Now an `intercept-` room is only minted for a session that exists **and is still active**;
public demo rooms are unaffected. *Code-reviewed — needs LiveKit env to exercise.*

### 5. Auto-answer claimed calls it never answered — HIGH
`InterceptInCallService.maybeAutoAnswer`

The guard was `if (ringing && we hold it) { answer() }` followed by an unconditional `return true`. Returning true
means "don't show the in-call UI" — so for a call that was no longer ringing, the user was left holding a connected
call with **no screen at all**. Now it returns true only when it actually answered.

### 6. The auto path could leave a call completely unprotected — HIGH (protection gap)
`InterceptScreeningService.onScreenCall`

`shouldAutoAnswer` was `setupDone && autoCalls && isUnknown(...)` — with no check for the dialer role. Without
ROLE_DIALER, `InterceptInCallService` never fires, so the early `return` meant: no auto-answer **and** no
tap-to-screen prompt. Protection silently did nothing on exactly the calls it exists for.

Now the dialer role is required for the auto path; otherwise the user gets the tap-to-screen prompt.

### 7. Rate-limit table grew without limit — MEDIUM
`app/guards.py`

One `deque` per client IP, never removed, on a public endpoint — a stranger could drive it to OOM. Now pruned back
to the active window past `MAX_TRACKED_CLIENTS` (5000). *Tests: 2.*

### 8. The version/changelog feed was cached forever — MEDIUM
`app/api/appcast.py`

`@lru_cache(maxsize=1)` on the file read meant a release that landed without a process restart was invisible — so
the in-app updater would never tell users about a build they could already install. Now keyed on the file's
mtime+size. *Test: 1.*

### 9. The realtime path left no audit trail — MEDIUM
`app/realtime/websocket.py`

The REST turn path persisted transcript/events/risk; the WebSocket path — the app's primary live route — persisted
nothing, so a report differed depending on which transport won. A session born on the socket also had no DB row, so
its transcript inserts failed the foreign key and vanished silently. Both fixed; `note_risk` added to the live
bridge too. *Test: live-feed intent test exercises turn handling.*

### 10. QR decoding ran on non-QR content — LOW/MEDIUM
`app/pipeline.py`

`A or B or C and D` parsed as `A or B or (C and D)`: **every** QR-channel request ran the decoder, with context text
passed in as if it were a scanned code. Now decodes when a QR payload exists, or when the request is a QR decode with
an image.

### 11. Smaller ones
- `app/api/users.py` — unauthenticated endpoints grew two module-level sets without limit → capped at 5000.
- `app/api/livekit.py` — `/dispatch` truncated the room name where `/token` validated it strictly → same validation.
- `speech/CallerStt.kt` — `stop()` cleared the text callbacks but kept `onStopped`/`onReady`, which close over the
  ViewModel → cleared too.
- `presentation/incoming/IncomingCallScreen.kt` — duplicate `remember` import removed.

---

## Enhanced for the AI system

- **Escalation detection.** `CallSession.note_risk()` keeps a 20-turn trail and `escalating` flags a call whose risk
  is climbing *while already suspicious* (≥50, +15 over two turns). A scam escalates on purpose — authority, then
  urgency, then the ask — and a rising trend is a stronger signal than any single turn's score, which is precisely
  what a per-turn LLM is bad at noticing. *Tests: 2.*
- **`/calls/live` now answers "what do they want?"**: `objective`, `claimed_org`, `escalating` join the feed, so the
  Home "Live now" card shows intent and escalation *before* you tap into the call.
- **Bounded session retention** (finding 3) — the reliability half of the same change.

---

## Round 2 — deeper sweep (same day)

After the first pass I had still not read `whatsapp.py`, `rules.py`, `i18n.py`, the multimodal stubs, or
`AnalyzeScreen`/`CallActiveActivity`. The second read found four more live bugs, all on the request path.

### 12. The AI kept talking after the owner took over — HIGH
`app/realtime/websocket.py`

The REST turn path has always honoured `human_mode` ("human speaking → monitor silently, no guardian
reply"). The socket path — the app's primary live route — never checked it, so tapping **Take over**
left the guardian talking over the owner who had just joined the call. Now the reply, its transcript row
and the `AI_RESPONSE_*` events are all suppressed while a human drives. Risk/signals/chain still flow, so
monitoring is unaffected. *Test: a WebSocket session that takes over and asserts silence.*

### 13. `www.` links were never extracted — HIGH
`app/detection/signals.py`

This is finding 1 at the *extraction* layer: `URL_RE` matched only `https?://`, so a message reading
"verify at www.sbi-kyc.xyz/update" produced no URL signals at all. Fixing `analyze_url` alone was not
enough — the engine never received the link. Bare domains are still deliberately not matched, because in
prose `no.However` is shaped exactly like a hostname. *Tests: 4.*

### 14. English was sometimes read as Hinglish — MEDIUM
`app/i18n.py`

`detect_language()` matched a word list containing `main`, `double`, `mat`, `sun` and `hum` — all ordinary
English words. "Please check your main account" was classified as Hinglish, which changes the guardian's
reply language, the policy wording and the TTS voice for an English speaker. Colliding words are gone;
real roman-Hindi always carries a function word (`hai`/`ka`/`raha`/`kya`) from the remaining set.
*Tests: 3.*

**Note:** my own test caught that this fix was half-done — `"double"` survived on an earlier line of the
same set, so "Double check the main door" still came back Hinglish. Worth knowing that the first attempt
looked complete.

### 15. The WhatsApp webhook accepted unverified payloads — MEDIUM (security)
`app/api/whatsapp.py`

Meta signs every webhook POST with the app secret, and nothing checked it. Anyone who learned the URL
could post a payload and make **our business number** send messages to arbitrary numbers — a spam relay,
and a WhatsApp policy violation. Now `X-Hub-Signature-256` is verified whenever `WHATSAPP_APP_SECRET` is
set; when it is not, the response says the payload was unverified instead of failing silently (the
documented activation flow predates the secret). Also: the bot kept **one** Scam DNA bucket for every
WhatsApp user on earth — now keyed per sender. *Tests: 2.*

---

## Round 3 — the remaining UI layer (same day)

Settings/Setup, Reports, theme and components. Same question as before: what here can mislead, crash,
or quietly do nothing?

### 16. One Save with an empty URL bricked the app, permanently — HIGH (crash)
`di/AppContainer.kt`, `presentation/settings/SettingsScreen.kt`

The setter wrote the raw text to prefs **first** and called `rebuild()` **second** — and `rebuild()` builds a
Retrofit instance, which *throws* on a base URL it cannot parse. So clearing the field and tapping Save
(`""` → `/`) crashed at `baseUrl()`, and because the bad value was already persisted, the **next launch**
hit it again inside `init { }` — a brick that survives restarts and can only be cleared from app settings.
A missing scheme (`10.0.2.2:8000`, `api.example.com`) did the same. Now the value is validated *before* it is
stored (`normalizeBackendUrl`), the setter refuses anything Retrofit would reject, and `rebuild()` is
wrapped so a bad URL heals to the last one Retrofit actually accepted instead of taking the process down.
A public host without a scheme is deliberately **rejected** rather than assumed http:// — guessing would
silently downgrade a real user to plaintext while the same screen warns them not to.

### 17. "Test" tested the saved URL, not the one you typed — MEDIUM
The health check and the https warning both read `container.backendUrl` (the stored value) while the field on
screen held the new one. Change the URL, tap Test, get a confident verdict about the **previous** backend.
Test now saves-and-tests what is typed, and names the URL it reached (or failed to reach). The duplicated
"is this a local host" regex that lived in the screen moved into `di/AppContainer.kt` next to the parser, so
there is one rule instead of two that can drift.

### 18. A risk level the app did not recognise was rendered as safe — MEDIUM (fail-open)
`domain/model/Models.kt`

`RiskLevel.of` mapped anything unknown to `LOW`. Nothing triggers this today — the backend vocabulary is
exactly `LOW/SUSPICIOUS/HIGH/CRITICAL` — but the failure mode is the worst one available on a safety
product: add a level server-side (`MEDIUM`, `DANGER`), and every older app in the wild paints that call
green with a low-risk tint. Blank still means LOW; **unrecognised now means SUSPICIOUS**. Fail closed.
Also removed `RiskLevel.color` — a second copy of the same four hexes that already live in
`theme/Color.kt`, i.e. a value you could have edited with no effect.

### 19. Setup counted "not on this device" as "passing" — MEDIUM (truthfulness)
`presentation/setup/SetupScreen.kt`

The headline said *"6 of 6 checks passing"* while rows underneath it read *"Not on this device"*, because
`readyCount` counted every non-TODO state. READY only, now — and the header names the unavailable ones
instead of folding them into a green total. `Done` still does not require a gate the device does not offer
(soft-locking setup on older Android would be worse), but the count no longer overstates what is on.

### 20. Reports forgot the session id you typed — LOW
`presentation/reports/ReportsScreen.kt`

`remember` is lost on rotation, so the field fell back to `lastSessionId` and the screen loaded a *different*
session's report than the one on screen a second earlier. `rememberSaveable`, and the id field is
`singleLine` (a pasted id with a newline in it became a malformed request path).

### 21. The dark room used the paper error colour — LOW (latent)
`presentation/theme/Theme.kt`

`RoomScheme` existed for one stated reason — "no Material default can leak a light-on-light or dark-on-dark
pair" — and its `error` slot held `#C1121F`, the ramp built for white. Nothing reads `colorScheme.error`
today (screens pass `RiskCritical` explicitly), so this is latent rather than visible: it would surface the
first time a Material error path renders in the room, e.g. a TextField with `isError = true`. Now
`RiskCriticalOnRoom` with matching `onError`.

### 22. Checked and clean (so the next sweep can skip them)
Simple-mode cards and buttons pass an explicit `contentColor`, so the `containerColor = RiskCritical`
buttons in Live/Reports/Analyze are white-on-red as intended; `RiskMeter` clamps to 0–100 and skips a
zero-width bar; `RiskChip` on dark uses `RoomRaised` + the room ramp; report field mapping from the backend
(`likely_objective` → `objective`, `claimed_org`) is correct in both directions; `Common.kt` components have
no state to get wrong.

## Round 4 — size, history, and honest copy

A pass over what the app *ships* and what it *says*, rather than what it computes. No backend change.

### 23. Every download carried WebRTC for CPUs a phone cannot have — HIGH (size)
`app/build.gradle.kts`

`ndk.abiFilters` was never set, so the LiveKit `.so` shipped for `x86` and `x86_64` as well as the two ARM ABIs —
11–15 MB each, roughly 27 MB of native code that only emulators can load, paid by every real phone on download.
`defaultConfig` now keeps `arm64-v8a`/`armeabi-v7a`; `debug` adds `x86_64` back so emulators still run.

### 24. The release build shipped everything R8 would have removed — HIGH (size)
`app/build.gradle.kts`

`release { isMinifyEnabled = false }`. `material-icons-extended` alone is thousands of vectors the UI never draws,
and LiveKit carries Java the app never calls; with the shrinker off all of it rode along in the bundle. Now
`isMinifyEnabled = true` + `isShrinkResources = true`, with the `proguard-rules.pro` added in this change spelling
out the one thing R8 must *not* remove: `@Serializable` classes are reached only by generated `$$serializer`
Companions, so deleting them would make every decode throw at runtime.

**Not verifiable here** — see the note at the end; CI's `bundleRelease` is the first build to run R8.

### 25. Reports opened on a blank box only the developer could fill — HIGH
`presentation/reports/ReportsScreen.kt`, `di/AppContainer.kt`, `domain/model/Models.kt`

After a screened call the owner landed on Reports and found a `Session id` text field — a value they have no way
of knowing. The report existed; the way in did not. The phone now records each call itself: `CallRecord`
(sid, caller, final risk, level, action, time) is written the moment `/calls/start` returns and finalised when the
report does, capped at 50, and Reports lists them newest-first with a relative time. The id field survives as a
fallback behind "Have a session id?", hidden entirely once there is history. Recording lives in
`InterceptRepositoryImpl`'s call/end hooks rather than at each call site, so mic, auto-answer and tap-to-screen all
land in history without any one path being able to forget.

### 26. The image you attached followed you to the next tab — MEDIUM
`presentation/analyze/AnalyzeScreen.kt`

Switching Analyze tabs cleared the result but not `imageB64`, so a screenshot taken on SCREENSHOT could be sent to
the QR decoder, and a hidden image kept the Analyze button looking ready on a tab that showed nothing. The tab
switch now clears it, and the button's enabled state is computed per tab — an image counts only where an image is
actually read. Because this build ships no OCR/QR decode, an image-only run is also labelled as limited rather than
allowed to come back clean having read nothing.

### 27. Progress was a full-size spinner or a literal "..." — LOW
`presentation/components/Common.kt`, `AnalyzeScreen.kt`, `ReportsScreen.kt`, `HomeScreen.kt`,
`setup/SetupScreen.kt`

`CircularProgressIndicator()` is 40dp and shoved the button's height around the instant work started; Home's update
button said `"..."`, which is punctuation, not progress. One `InlineLoader` (18dp, 2dp stroke, the button's own
`LocalContentColor`) replaces all five.

### 28. Errors showed the exception, not the problem — MEDIUM
`AnalyzeScreen.kt`, `ReportsScreen.kt`

`"Backend unreachable: ${e.message}"` and `"No report: ${e.message}"` handed a worried owner a stack-trace fragment
or an HTTP status. Both are now sentences about their call — "could not reach Intercept", "that report is not
available right now" — with no internal text on screen.

### 29. AI screening played the guardian voice out of the speaker — MEDIUM (privacy)
`telecom/InterceptInCallService.kt`, `audio/InCallAudio.kt`, `speech/CallerStt.kt`,
`service/AutoScreenService.kt`

Screening answered the call on `ROUTE_SPEAKER` and `InCallAudio` forced `isSpeakerphoneOn = true`, so the guardian's
prompt and the scammer's replies played audibly beside the owner — the exact person the feature exists to shield.
The `InCallService` now answers to the **earpiece** and `InCallAudio` keeps it there; speaker is left to the owner's
own takeover button. The caller still hears the guardian because that is uplink (`STREAM_VOICE_CALL`), which local
routing does not affect.

Pulling on that thread exposed a second problem: `MODE_IN_COMMUNICATION` had *three* writers — `InCallAudio`,
`AutoScreenService` and every `CallerStt.begin()`/`stop()` — so the recognizer restarting mid-call (or stopping as
the caller went quiet) reset the mode to `MODE_NORMAL` and silently undid the routing for the rest of the screening.
`InCallAudio` now owns the mode for the whole call — it already saved and restored the previous state around
`enter()`/`exit()` — and the other two no longer touch it.

**Not verifiable here** — earpiece/speaker is a device behaviour; see the checklist note at the end.

---

## Deliberately not changed

- **Unknown callers are not actually silenced**, despite the old comment and the docs saying so. Silencing means
  `setDisallowCall(true)`, which prevents the call from being shown — and the tap-to-screen flow needs the call still
  alive to answer it. Fixing the *comment and docs* was correct; changing the behaviour would break the flow.
- **`specialUse` foreground service + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** — a Play policy conversation, not a
  code fix.
- **Scam fingerprints are global, not per-user.** They store tactic codes and an objective, never message text or
  identifiers. Cross-user "this pattern was seen before" is a product decision, so it was left alone.
- **Public demo LiveKit rooms stay open** — the API has no auth model at all (anonymous `X-User-Id`, by design).
  The same applies to `GET /calls/{id}/report`: the Reports screen will display any session's transcript if the id
  is known, and there is no ownership to check against because `/calls/start` never records the user id. Worth a
  product decision, not a silent UI change.
- **The simple-mode card in the live room still uses the paper ramp** (`RiskLow`/`RiskCritical` rather than
  `riskOnRoom`). It is legible today, the room ramp exists precisely for that surface, and the fix is one line —
  left alone because it changes the look of the signature screen and cannot be eyeballed from here.
- **`SettingsScreen.kt` has a blank line after every line of code** (LF + `\n\n`). Harmless to Kotlin, ~370 lines
  of noise in the diff, so reformatting it did not belong in a bug-fix commit.

---

## Cannot be verified in this workspace

- **That the app compiles.** No Gradle wrapper and no Android SDK here; CI's `gradle assembleDebug` is the first real
  compile, and a manifest mistake is a runtime crash rather than a build error.
- **Telecom behaviour** (findings 5–6) needs a device + `ROLE_DIALER` / `ROLE_CALL_SCREENING`. See
  [Device Test Checklist](./testing/DeviceTestChecklist.md) → *Path D*.
- **LiveKit token/dispatch** (finding 4) needs `LIVEKIT_URL`/`KEY`/`SECRET` configured.
- **The round-3 URL fixes are the exception** — no permissions needed, so they are checkable on any build:
  Settings → clear the backend field → Save (must show a red *"Not a usable URL"*, not crash), then relaunch
  (must still start); type `10.0.2.2:8000` → Save (accepted as `http://10.0.2.2:8000`); type `api.example.com`
  → Save (rejected — no scheme); Test with the field edited (the status must name the URL you typed).
  Also: type a session id in Reports, rotate the screen, confirm the id and its report survive.
