"""Realtime event stream (§25) — no polling; server pushes every state change."""
from __future__ import annotations

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.db.repository import REPO
from app.pipeline import analyze
from app.realtime.sessions import MANAGER
from app.schemas import Channel, Content, InterceptInput, Source

router = APIRouter()


def _ev(t: str, session_id: str, **data) -> dict:
    return {"event": t, "session_id": session_id, **data}


@router.websocket("/ws/calls/{session_id}")
async def call_socket(ws: WebSocket, session_id: str):
    await ws.accept()
    sess = MANAGER.get(session_id)
    if sess is None:
        sess = MANAGER.create(session_id, "unknown")
        # A session born on the socket still needs its row, or the transcript
        # inserts below fail the foreign key and vanish silently.
        REPO.ensure_session(session_id, sess.caller)
    await ws.send_json(_ev("CALL_STARTED", session_id, caller=sess.caller))
    try:
        while True:
            msg = await ws.receive_json()
            kind = msg.get("type", "caller_turn")
            if kind == "caller_turn":
                text = msg.get("text", "")
                pinned = (msg.get("language") or "").lower()
                if pinned in ("hi", "hinglish", "en"):
                    sess.language = pinned
                    lang = pinned
                else:
                    lang = "auto"
                sess.transcript.append({"speaker": "caller", "text": text})
                await ws.send_json(_ev("TRANSCRIPT_UPDATED", session_id,
                                       speaker="caller", text=text))
                result = analyze(InterceptInput(
                    session_id=session_id, channel=Channel.CALL,
                    source=Source(type="UNKNOWN_CALLER", identifier=sess.caller),
                    content=Content(text=text)), memory=sess.memory,
                    user_memory=sess.scam_memory, language=lang)
                sess.last_result = result
                sess.note_risk(result.risk_score)
                sess.language = result.language
                # Human has taken over: the AI monitors silently and says nothing.
                # The REST turn path has always honoured this; without it here the
                # guardian kept talking over the owner who just joined the call.
                if not sess.human_mode:
                    sess.transcript.append({"speaker": "intercept", "text": result.guardian_reply})
                # Same persistence as the REST turn path: a call screened over
                # the realtime socket must leave the same audit trail, or the
                # report quietly differs depending on which transport won.
                REPO.save_transcript(session_id, "caller", text)
                if not sess.human_mode:
                    REPO.save_transcript(session_id, "intercept", result.guardian_reply)
                REPO.add_events(session_id, [e.model_dump() for e in result.events])
                REPO.touch_session_risk(session_id, result.risk_score, result.risk_level)
                for s in result.signals:
                    await ws.send_json(_ev("SIGNAL_DETECTED", session_id,
                                           code=s.code, category=s.category,
                                           confidence=s.confidence, evidence=s.evidence))
                await ws.send_json(_ev("RISK_UPDATED", session_id,
                                       risk=result.risk_score, level=result.risk_level))
                await ws.send_json(_ev("ATTACK_STAGE_CHANGED", session_id,
                                       chain=[c.model_dump() for c in result.attack_chain]))
                if not sess.human_mode:
                    await ws.send_json(_ev("AI_RESPONSE_STARTED", session_id))
                    await ws.send_json(_ev("AI_RESPONSE_FINISHED", session_id,
                                           text=result.guardian_reply))
                await ws.send_json(_ev("TAKEOVER_AVAILABLE", session_id,
                                       offer=result.policy.offer_takeover,
                                       human_mode=sess.human_mode))
                if result.policy.must_terminate:
                    sess.active = False
                    await ws.send_json(_ev("CALL_TERMINATED", session_id,
                                           reason=result.policy.user_message,
                                           risk=result.risk_score))
            elif kind == "takeover":
                sess.human_mode = True
                await ws.send_json(_ev("TAKEOVER_AVAILABLE", session_id,
                                       offer=True, human_mode=True))
            elif kind == "end":
                sess.active = False
                await ws.send_json(_ev("CALL_TERMINATED", session_id, reason="User ended call"))
                break
    except WebSocketDisconnect:
        pass
