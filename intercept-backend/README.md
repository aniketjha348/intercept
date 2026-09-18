# INTERCEPT backend — AI Social Engineering Firewall (modular monolith)

Every input (call, SMS, screenshot, URL, QR, voice) enters one
**Security Intelligence Pipeline**: normalize → multimodal → signals
(rules + LLM + threat intel) → risk engine → attack reconstruction →
policy engine → security events.

Center of the architecture: **Risk Engine + Security Event model + Policy Engine**.
Calls are one input channel, not a separate feature.

## Run (MVP, no keys needed)

```powershell
cd D:\intercept\intercept-backend
python -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

Docs: http://localhost:8000/docs — try `POST /analyze/text`,
`POST /calls/start`, `POST /calls/{id}/transcript`, `WS /ws/calls/{id}`.

## Languages: Hindi / Hinglish / English

Every input auto-detects `hi` (Devanagari), `hinglish` (roman), or `en` —
or pin it via `language` on any request. Detection patterns, explanations,
policy messages, guardian replies (and TTS voice in the app) all follow it.
Try: `POST /analyze/text` with
`"मैं SBI बैंक KYC विभाग से बोल रहा हूँ। ... तुरंत OTP बताएं।"` → CRITICAL + Hindi report.

## LLM: Gemini free tier

Set `GOOGLE_API_KEY` from Google AI Studio (free tier) — guardian replies and
supporting signal extraction switch on automatically (`LLM_PROVIDER=auto` tries
Gemini, then OpenAI, else offline templates). Rules engine always carries risk.
`app/ai/gemini_live.py` holds the streaming Live-API hook (P1).

## Layout (matches architecture doc §22)

```text
app/
  main.py            FastAPI wiring
  schemas.py         Universal Input Object + Security Events
  pipeline.py        Unified Security Intelligence Pipeline
  api/               Channel gateway (calls, messages, screenshots, urls, users)
  realtime/          WebSocket event stream + session manager
  ai/                LangGraph guardian workflow, LLM abstraction, prompts, memory
  detection/         Rules, LLM classifiers, social-engineering merge, signal taxonomy
  risk/              Deterministic scorer (+caps/overrides) + policy engine
  multimodal/        OCR / vision / audio / QR stubs with real-lib hooks
  intelligence/      URL intel, QR payload, threat-intel stub, RAG scam memory
  reports/           Post-incident security report generator
  db/                SQLAlchemy models (Postgres-ready) + in-memory repo for MVP
```

## AI stack roles

| Tech | Role |
|---|---|
| LangGraph | Stateful call/interaction workflow (auto-used if installed, else sequential fallback) |
| LLM API (OpenAI/Gemini) | Guardian replies + supporting signal extraction; rules carry risk without keys |
| RAG / Scam DNA | In-memory tactic-fingerprint store (`intelligence/scam_memory.py`); swap in embeddings later |
| LangChain | Optional supporting plumbing only — never the center |
