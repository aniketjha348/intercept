"""Realtime event stream (§25) — no polling; server pushes every state change."""
from __future__ import annotations

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

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
    await ws.send_json(_ev("CALL_STARTED", session_id, caller=sess.caller))
    try:
        while True:
            msg = await ws.receive_json()
            kind = msg.get("type", "caller_turn")
            if kind == "caller_turn":
                text = msg.get("text", "")
                if msg.get("language"):
                    sess.language = msg["language"]
                sess.transcript.append({"speaker": "caller", "text": text})
                await ws.send_json(_ev("TRANSCRIPT_UPDATED", session_id,
                                       speaker="caller", text=text))
                result = analyze(InterceptInput(
                    session_id=session_id, channel=Channel.CALL,
                    source=Source(type="UNKNOWN_CALLER", identifier=sess.caller),
                    content=Content(text=text)), memory=sess.memory,
                    user_memory=sess.scam_memory, language=sess.language)
                sess.last_result = result
                sess.transcript.append({"speaker": "intercept", "text": result.guardian_reply})
                for s in result.signals:
                    await ws.send_json(_ev("SIGNAL_DETECTED", session_id,
                                           code=s.code, category=s.category,
                                           confidence=s.confidence, evidence=s.evidence))
                await ws.send_json(_ev("RISK_UPDATED", session_id,
                                       risk=result.risk_score, level=result.risk_level))
                await ws.send_json(_ev("ATTACK_STAGE_CHANGED", session_id,
                                       chain=[c.model_dump() for c in result.attack_chain]))
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
