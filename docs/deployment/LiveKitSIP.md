# LiveKit SIP — AI answers the caller

This is the path that makes the AI talk to **Person A (the caller)**, not to
Person B. It works because the call is *terminated at LiveKit*, where the agent
is the endpoint — no audio-injection into a native cellular call is involved
(a normal Android app cannot do that; see `docs/AUDIT.md` finding 29 and the
Android "Sharing audio input" rules).

```
A dials the SIP number  ──►  LiveKit inbound trunk  ──►  dispatch rule
                                                          │
                                       room (intercept-…) + agent `intercept-agent`
                                                          │
                        agent ── POST /calls/inbound ──► backend session
                                                          │
                        every caller turn ── /analyze/text + /calls/{sid}/transcript
```

## 1. One project for both sides

The backend mints room tokens and the agent joins rooms; they must share one
LiveKit project, or the agent is never in the room the backend sends callers to.

| File | Vars |
| --- | --- |
| `intercept-backend/.env` | `LIVEKIT_URL`, `LIVEKIT_KEY`, `LIVEKIT_SECRET` |
| `livekit-agent/.env.local` | `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` |

`LIVEKIT_URL` must match exactly, and `KEY == API_KEY`, `SECRET == API_SECRET`.
(They are the same values under two naming conventions — the agent SDK's and
ours.)

## 2. Inbound trunk + number (dashboard)

LiveKit Cloud → **Telephony**:

1. **Inbound trunks** → create one with your provider's SIP credentials.
2. **Phone numbers** → buy/attach the number, assign it to that trunk.
   With LiveKit Phone Numbers the trunk is implicit — just attach the number.

## 3. Dispatch rule (dashboard → Telephony → Dispatch rules → JSON editor)

Use an **individual** rule so each caller gets their own room, and put the
routing info in `roomConfig` metadata — that is how the agent learns who the
call is for.

```json
{
  "rule": { "dispatchRuleIndividual": { "roomPrefix": "intercept-" } },
  "name": "Intercept Incoming Calls",
  "roomConfig": {
    "agents": [
      { "agentName": "intercept-agent", "metadata": "{\"owner_name\":\"Aniket\"}" }
    ]
  }
}
```

- `agentName` **must** be `intercept-agent` — it matches `agent_name` in
  `livekit-agent/agent.py`. A typo here means the call rings and nobody answers.
- `metadata` is free-form JSON, read by the agent as `ctx.job.metadata`. Put
  whatever routing you need (`owner_name`, `session_id`, a store id…).
- Omit `trunk_ids` to match every inbound trunk (wildcard).
- License note: an individual rule names the room after the **caller's phone
  number**, which LiveKit does not redact from logs. If that matters, use a
  callee rule (room named after the dialed number) or route to a specific room
  — see LiveKit's "Route each call to a specific room with a unique ID".

## 4. How the session is linked

The agent does **not** trust the room name as a session id (the room is named
after the caller's number, not our session). On entry it calls:

```
POST /calls/inbound  { caller, session_id?, owner_name?, language }
```

and the backend returns the session to use — reusing the app's session when a
call was answered on the phone first, matching an active session for the same
caller, or creating one. Transcript, risk history and the report then attach to
a real session either way.

`metadata.session_id` is optional: supply it only if you create the session
*before* the call arrives.

## 5. Run + verify

```bash
# agent worker (demo laptop)
cd livekit-agent && python agent.py dev      # registers as `intercept-agent`

# backend must be reachable at INTERCEPT_API
curl -X POST $INTERCEPT_API/calls/inbound -H 'content-type: application/json' \
     -d '{"caller":"+919999999999","owner_name":"Aniket"}'
```

Then:

1. Call the SIP number from any phone.
2. Agent dashboard → **Agents → Sessions** shows the worker (`intercept-agent`).
3. Terminal prints `CALLER: …` / `RISK: …`; say *"I am from your bank, give me
   the OTP"* to see `🚨 HIGH RISK`.
4. `GET /calls/live` shows the session; `GET /calls/{sid}/report` renders after
   the call ends.

## 6. Troubleshooting

| Symptom | Cause |
| --- | --- |
| Call rings, AI never speaks | `agentName` in the rule ≠ `intercept-agent`, or the worker isn't running |
| AI speaks, but `/calls/live` and the report are empty | backend unreachable from the agent, or wrong `INTERCEPT_API` |
| Two calls interfere / closing line only once | should be fixed by per-job `CallState`; if seen, the worker is running an old build |
| Room name leaks the caller's number | expected for an individual rule — switch to a specific-room rule |
