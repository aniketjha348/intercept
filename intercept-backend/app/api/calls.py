"""Channel gateway: calls (§12–§14)."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.ai import voice
from app.pipeline import analyze
from app.db.repository import REPO
from app.intelligence.scam_memory import Fingerprint
from app.realtime.sessions import MANAGER
from app.reports import generator
from app.schemas import Channel, Content, InterceptInput, Source, new_session_id

router = APIRouter(prefix="/calls", tags=["calls"])


class StartCall(BaseModel):
    caller: str = "unknown"
    session_id: str | None = None
    language: str = "auto"  # auto | hi | hinglish | en
    owner_name: str | None = None  # session-only, never persisted


class Turn(BaseModel):
    text: str
    speaker: str = "caller"
    language: str | None = None


class SpeakIn(BaseModel):
    text: str | None = None  # default: last guardian reply of this session


@router.post("/start")
def start(body: StartCall):
    sid = body.session_id or new_session_id("call")
    sess = MANAGER.create(sid, body.caller, body.language)
    sess.owner_name = (body.owner_name or "").strip()[:60]
    sess.memory.owner = sess.owner_name
    REPO.ensure_session(sid, body.caller)
    for fp in REPO.load_fingerprints():  # Scam DNA from previous sessions
        try:
            sess.scam_memory.prints.append(
                Fingerprint(set(fp.get("tactics", [])), fp.get("objective", ""), fp.get("risk", 0)))
        except Exception:
            continue
    return {"session_id": sid, "event": "CALL_STARTED", "caller": sess.caller,
            "language": sess.language, "owner_name": sess.owner_name}


@router.post("/{session_id}/transcript")
def transcript(session_id: str, turn: Turn):
    sess = MANAGER.get(session_id)
    if not sess or not sess.active:
        raise HTTPException(404, "call session not found or ended")
    sess.transcript.append({"speaker": turn.speaker, "text": turn.text})
    # A previously DETECTED session language must never pose as a user pin:
    # "auto"/blank means detect fresh every turn so mid-call switches work.
    pinned = (turn.language or "").lower()
    lang = pinned if pinned in ("hi", "hinglish", "en") else "auto"
    if lang != "auto":
        sess.language = lang
    if turn.speaker != "caller" or sess.human_mode:
        # Human speaking (takeover): monitor silently, no guardian reply.
        res = analyze(InterceptInput(session_id=session_id, channel=Channel.CALL,
                                     source=Source(type="UNKNOWN_CALLER", identifier=sess.caller),
                                     content=Content(text=turn.text)),
                       memory=sess.memory, user_memory=sess.scam_memory, language=lang)
        sess.last_result = res
        sess.note_risk(res.risk_score)
        if lang == "auto":
            sess.language = res.language
        return {"risk": res.risk_score, "level": res.risk_level,
                "language": res.language,
                "human_mode": True, "warning": res.policy.user_message,
                "must_terminate": res.policy.must_terminate}
    res = analyze(InterceptInput(session_id=session_id, channel=Channel.CALL,
                                 source=Source(type="UNKNOWN_CALLER", identifier=sess.caller),
                                 content=Content(text=turn.text)),
                   memory=sess.memory, user_memory=sess.scam_memory, language=lang)
    sess.last_result = res
    sess.note_risk(res.risk_score)
    if lang == "auto":
        sess.language = res.language
    sess.transcript.append({"speaker": "intercept", "text": res.guardian_reply})
    REPO.save_transcript(session_id, "caller", turn.text)
    REPO.save_transcript(session_id, "intercept", res.guardian_reply)
    REPO.add_events(session_id, [e.model_dump() for e in res.events])
    REPO.touch_session_risk(session_id, res.risk_score, res.risk_level)
    if res.policy.must_terminate:
        sess.active = False
    return {"guardian_reply": res.guardian_reply, "risk": res.risk_score,
            "level": res.risk_level, "language": res.language, "policy": res.policy.action,
            "signals": [s.model_dump() for s in res.signals],
            "attack_chain": [c.model_dump() for c in res.attack_chain],
            "why": res.explanation, "likely_objective": res.likely_objective,
            "claimed_org": sess.memory.claimed_org,
            "similar_pattern": res.similar_pattern,
            "simple_mode": res.policy.simple_mode_message,
            "offer_takeover": res.policy.offer_takeover,
            "must_terminate": res.policy.must_terminate}


@router.post("/{session_id}/speak")
def speak(session_id: str, body: SpeakIn):
    """Human-like voice for the guardian reply: base64 WAV (Gemini TTS) or
    audio_b64=null when unavailable — the app then falls back to device TTS."""
    sess = MANAGER.get(session_id)
    if not sess:
        raise HTTPException(404, "call session not found")
    text = (body.text or "").strip()
    if not text and sess.last_result:
        text = (sess.last_result.guardian_reply or "").strip()
    if not text:
        return {"audio_b64": None, "mime": "audio/wav",
                "voice": False, "cached": False, "language": sess.language}
    audio_b64, cached = voice.speak_cached(text, sess.language)
    return {"audio_b64": audio_b64, "mime": "audio/wav",
            "voice": audio_b64 is not None, "cached": cached,
            "language": sess.language}


class SayIn(BaseModel):
    text: str


@router.post("/{session_id}/say")
def say(session_id: str, body: SayIn):
    """Relay: speak the USER's message to the caller through the guardian
    voice (Equal-AI-style 'send a message'). Stored in the transcript so the
    report stays complete. No analysis — the human is driving."""
    sess = MANAGER.get(session_id)
    if not sess or not sess.active:
        raise HTTPException(404, "call session not found or ended")
    text = (body.text or "").strip()[:500]
    if not text:
        raise HTTPException(422, "empty message")
    sess.transcript.append({"speaker": "intercept-relay", "text": text})
    REPO.save_transcript(session_id, "intercept", "[you] " + text)
    return {"event": "RELAY_QUEUED", "text": text}


@router.get("/live")
def live_sessions():
    """Sessions being screened RIGHT NOW (forwarded or local) — the app's
    Live-now feed. Read-only; never touches call state."""
    out = []
    for sid, sess in MANAGER.calls.items():
        if not sess.active:
            continue
        last = sess.last_result
        out.append({
            "session_id": sid,
            "caller": sess.caller,
            "language": sess.language,
            "risk": last.risk_score if last else 0,
            "level": last.risk_level if last else "LOW",
            "turns": sess.memory.turns,
            # Why, before the user even opens the call: the feed is the one
            # place a glance has to answer "what does this person want?".
            "objective": last.likely_objective if last else "",
            "claimed_org": sess.memory.claimed_org,
            "escalating": sess.escalating,
        })
    return {"live": out}


@router.post("/{session_id}/takeover")
def takeover(session_id: str):
    sess = MANAGER.get(session_id)
    if not sess:
        raise HTTPException(404, "call session not found")
    sess.human_mode = True
    return {"event": "TAKEOVER_AVAILABLE", "human_mode": True,
            "note": "YOU ARE SPEAKING — AI monitoring continues.",
            "risk": sess.memory.current_risk}


@router.post("/{session_id}/end")
def end(session_id: str):
    sess = MANAGER.get(session_id)
    if not sess:
        raise HTTPException(404, "call session not found")
    sess.active = False
    report = generator.generate(session_id, sess.caller, sess.memory,
                                sess.last_result, sess.transcript)
    REPO.save_report(session_id, report)
    return {"event": "CALL_TERMINATED", "report": report}


@router.get("/{session_id}/report")
def report(session_id: str):
    sess = MANAGER.get(session_id)
    if sess:
        return generator.generate(session_id, sess.caller, sess.memory,
                                  sess.last_result, sess.transcript)
    saved = REPO.load_report(session_id)  # survives restarts via Postgres
    if saved:
        return saved
    raise HTTPException(404, "call session not found")
