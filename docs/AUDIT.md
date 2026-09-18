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

## Deliberately not changed

- **Unknown callers are not actually silenced**, despite the old comment and the docs saying so. Silencing means
  `setDisallowCall(true)`, which prevents the call from being shown — and the tap-to-screen flow needs the call still
  alive to answer it. Fixing the *comment and docs* was correct; changing the behaviour would break the flow.
- **`specialUse` foreground service + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** — a Play policy conversation, not a
  code fix.
- **Scam fingerprints are global, not per-user.** They store tactic codes and an objective, never message text or
  identifiers. Cross-user "this pattern was seen before" is a product decision, so it was left alone.
- **Public demo LiveKit rooms stay open** — the API has no auth model at all (anonymous `X-User-Id`, by design).

---

## Cannot be verified in this workspace

- **That the app compiles.** No Gradle wrapper and no Android SDK here; CI's `gradle assembleDebug` is the first real
  compile, and a manifest mistake is a runtime crash rather than a build error.
- **Telecom behaviour** (findings 5–6) needs a device + `ROLE_DIALER` / `ROLE_CALL_SCREENING`. See
  [Device Test Checklist](./testing/DeviceTestChecklist.md) → *Path D*.
- **LiveKit token/dispatch** (finding 4) needs `LIVEKIT_URL`/`KEY`/`SECRET` configured.
