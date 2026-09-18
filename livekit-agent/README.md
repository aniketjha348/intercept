# Intercept AI voice agent (hackathon: live calls that wow judges)

Python stack (Kotlin + Python only — no Node): Gemini Live voice +
**our production risk engine** as the detection brain (every caller turn hits
`/analyze/text` — Hindi/Hinglish/English, OTP traps, bank impersonation).

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

## Wire the number (dashboard, once)

Telephony → Phone Numbers → your number → Assign dispatch rule
(`Intercept Incoming Calls`, room prefix `intercept-`, agent `intercept-agent`).
Put who the call is for in the rule's `roomConfig` metadata — the agent reads it
as `ctx.job.metadata` and links the call to a backend session via
`POST /calls/inbound` (reusing the app's session when the call was answered on
the phone first). Full JSON + troubleshooting: [`docs/deployment/LiveKitSIP.md`](../docs/deployment/LiveKitSIP.md).

## Judge demo (60 seconds)

1. Call the US number from any phone (watch out for international charges).
2. Say: *"I am calling from your bank. Give me the OTP immediately."*
3. Terminal shows `RISK: CRITICAL … [AUTHORITY_BANK, OTP_REQUEST, …]` + 🚨.
4. Say it in Hindi — detection + reply follow the language automatically.

## If AI doesn't answer

Dashboard → Agents → Sessions must show the worker (`intercept-agent`
must match the dispatch rule exactly) → `lk room list` after the call.
