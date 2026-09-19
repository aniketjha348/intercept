# Intercept AI voice agent (hackathon: live calls that wow judges)

Python stack (Kotlin + Python only — no Node): **LiveKit** carries the audio,
**Gemini native-audio** is the voice *and* the brain (speech in, speech out —
no text round-trip, so tone and barge-in survive, like ChatGPT's advanced
voice). Every caller turn also hits **our production risk engine**
(`/analyze/text` — Hindi/Hinglish/English, OTP traps, bank impersonation).

The agent is written to sound like a person, not a phone tree: it matches the
caller's language and register on the fly, reacts before it answers, varies its
wording, and stops mid-sentence when interrupted. Personality lives in
`system_prompt()`; the voice is `LIVE_VOICE` (default `Aoede`).

## Run it (5 min, demo laptop)

```powershell
cd livekit-agent
pip install -r requirements-agent.txt
Copy-Item .env.example .env.local   # then fill keys, never commit
python agent.py dev
```

Worker registers as `intercept-agent`. Keep it running on the demo laptop
(phone hotspot as backup network). `.env.local` needs:
`LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `GOOGLE_API_KEY`
(+ optional `LIVE_MODEL`, `INTERCEPT_API`).

On startup it logs `agent joining intercept-… for caller …` — if you never see
that line while a call is ringing, the agent is not the problem, the call never
reached LiveKit (forwarding not armed).

Two things that silently break phone audio:

- **The realtime import.** Python uses `livekit.plugins.google.realtime`; the
  `google.beta.realtime` path is the Node layout. `agent.py` tries both, because
  the wrong one raises *inside the job* — the phone connects and nobody speaks.
- **Noise suppression.** Phone audio is 8 kHz narrowband. `room_options()` feeds
  LiveKit Cloud's Krisp NC (`livekit-plugins-noise-cancellation`, already in
  `requirements-agent.txt`) to the model. Do not also switch noise cancellation
  on in the SIP trunk: stacking two models makes the line worse, not cleaner.

## Wire the number (dashboard, once)

Telephony → Phone Numbers → your number → Assign dispatch rule
(`Intercept Incoming Calls`, room prefix `intercept-`, agent `intercept-agent`).
Put who the call is for in the rule's `roomConfig` metadata — the agent reads it
as `ctx.job.metadata` and links the call to a backend session via
`POST /calls/inbound` (reusing the app's session when the call was answered on
the phone first). It also passes the dialled number (`sip.trunkPhoneNumber`), so
with a DID per owner the backend binds the session to the right person.
Full JSON + troubleshooting: [`docs/deployment/LiveKitSIP.md`](../docs/deployment/LiveKitSIP.md).

## Judge demo (60 seconds)

1. Call the US number from any phone (watch out for international charges).
2. Say: *"I am calling from your bank. Give me the OTP immediately."*
3. Terminal shows `RISK: CRITICAL … [AUTHORITY_BANK, OTP_REQUEST, …]` + 🚨.
4. Say it in Hindi — detection + reply follow the language automatically.

## Verify the chain before you demo

```powershell
python ../scripts/e2e_livekit.py              # trunk, rule, number, token, worker deps
python ../scripts/e2e_livekit.py --simulate   # dispatch THIS worker into a test room and prove it joins
python ../scripts/e2e_livekit.py --watch 120  # dial the number while it watches LiveKit
```

`--simulate` is the fastest way to tell "the worker is not running" apart from
"the call never reached LiveKit" — it exits non-zero on any failure.

## If AI doesn't answer

Dashboard → Agents → Sessions must show the worker (`intercept-agent`
must match the dispatch rule exactly) → `lk room list` after the call.
