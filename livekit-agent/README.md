# Intercept AI voice agent (hackathon: live calls that wow judges)

Gemini Live voice + **our production risk engine** as the detection brain
(every caller turn hits `/analyze/text` — Hindi/Hinglish/English, OTP traps,
bank impersonation — not keyword toys).

## Run it (5 min)

```powershell
cd livekit-agent
npm install
Copy-Item .env.example .env.local   # then fill keys
node src/agent.mjs
```

Worker registers as `intercept-agent`. Keep it running on the demo laptop
(phone hotspot as backup network).

## Wire the free US number (dashboard, once)

Telephony → Phone Numbers → your number → Assign dispatch rule
(`Intercept Incoming Calls`, room prefix `intercept-`, agent `intercept-agent`).

## Judge demo (60 seconds)

1. Call the US number from any phone (watch out for international charges).
2. Say: *"I am calling from your bank. Give me the OTP immediately."*
3. Terminal shows `RISK: CRITICAL … [AUTHORITY_BANK, OTP_REQUEST, …]` + 🚨.
4. Say it in Hindi — detection + reply follow the language automatically.

## If AI doesn't answer

Dashboard → Agents → Sessions must show the worker (`intercept-agent`
must match the dispatch rule exactly) → `lk room list` after the call.
