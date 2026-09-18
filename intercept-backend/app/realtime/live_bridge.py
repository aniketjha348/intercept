"""Live voice bridge (§12+): phone <-> INTERCEPT <-> Gemini Live voice session.

Equal-AI-style realtime path. Caller audio (PCM16 16kHz mono) streams to a
Gemini Live native-audio session (barge-in built in, 70+ langs); the guardian
speaks THROUGH the same session (text injected -> model voices it). Our
deterministic risk engine watches every input transcript and can terminate —
the model never overrides it.

Phone protocol on /ws/live/{session_id} (JSON):
  app -> server: {"type": "audio", "data": b64 PCM16 16kHz mono}
                 {"type": "say", "text": "..."}   (voice this via Live)
                 {"type": "end"}
  server -> app: {"event": "VOICE_STARTED", "session_id": ...}
                 {"event": "VOICE_AUDIO", "data": b64 PCM16 24kHz mono}
                 {"event": "TRANSCRIPT_UPDATED", "speaker": ..., "text": ...}
                 {"event": "RISK_UPDATED", "risk": n, "level": ...}
                 {"event": "CALL_TERMINATED", "reason": ..., "risk": n}

No key / Live unreachable -> socket closes immediately; the app falls back
to the STT+TTS turn path. Nothing here ever raises past the socket.
"""
from __future__ import annotations

import asyncio
import base64
import json

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app import core_config as cfg
from app.pipeline import analyze
from app.realtime.sessions import MANAGER
from app.schemas import Channel, Content, InterceptInput, Source

router = APIRouter()

LIVE_URL = (cfg.GEMINI_LIVE_URL or
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
# Probed 2026-09-18 on our key: 3.1-live-preview OK, 2.5-native-audio OK,
# 3/3.8-live-preview IDs do not exist. First setupComplete wins.
LIVE_MODELS = [cfg.LIVE_MODEL or "gemini-3.1-flash-live-preview",
               "gemini-2.5-flash-native-audio-preview-12-2025"]
LIVE_MODELS = list(dict.fromkeys(m for m in LIVE_MODELS if m))

GUARDIAN_VOICE_SYSTEM = (
    "You are INTERCEPT, screening a possibly malicious phone call. "
    "Speak briefly like a calm family member (1-2 short sentences). "
    "Never share OTPs, passwords, card details, or agree to payments. "
    "If asked for a code, refuse politely and end the call. "
    "Match the caller's language (Hindi, Hinglish, or English)."
)


def build_setup(model: str | None = None) -> dict:
    return {
        "setup": {
            "model": f"models/{model or LIVE_MODELS[0]}",
            "generationConfig": {"responseModalities": ["AUDIO"]},
            "systemInstruction": {"parts": [{"text": GUARDIAN_VOICE_SYSTEM}]},
            "inputAudioTranscription": {},
            "outputAudioTranscription": {},
        }
    }


async def open_live_session() -> tuple | None:
    """Connect + handshake, trying each model in order. (conn, model) or None."""
    import websockets

    for model in LIVE_MODELS:
        try:
            gem = await asyncio.wait_for(
                websockets.connect(LIVE_URL + "?key=" + cfg.GOOGLE_API_KEY,
                                   max_size=8 * 1024 * 1024),
                timeout=20)
            try:
                await gem.send(json.dumps(build_setup(model)))
                raw = _text(await asyncio.wait_for(gem.recv(), timeout=20))
                if "setupComplete" in raw:
                    return gem, model
            except Exception:
                pass
            try:
                await gem.close()
            except Exception:
                pass
        except Exception:
            continue
    return None


def build_audio_chunk(pcm_b64: str) -> dict:
    return {"realtimeInput": {"audio": {"mimeType": "audio/pcm;rate=16000",
                                       "data": pcm_b64}}}


def build_say(text: str) -> dict:
    return {"clientContent": {"turns": [{"role": "user",
             "parts": [{"text": text}]}], "turnComplete": True}}


def parse_server_msg(msg: dict) -> list[tuple]:
    """Pull (kind, payload) events out of one BidiGenerateContent message."""
    out: list[tuple] = []
    sc = msg.get("serverContent") or {}
    turn = (sc.get("modelTurn") or {}).get("parts") or []
    for part in turn:
        data = (part.get("inlineData") or {}).get("data")
        if data:
            out.append(("audio", data))
    it = (sc.get("inputTranscription") or {}).get("text") or ""
    if it.strip():
        out.append(("input_text", it.strip()))
    ot = (sc.get("outputTranscription") or {}).get("text") or ""
    if ot.strip():
        out.append(("output_text", ot.strip()))
    if sc.get("turnComplete"):
        out.append(("turn_complete", True))
    if sc.get("interrupted"):
        out.append(("interrupted", True))
    return out


def _ev(t: str, session_id: str, **data) -> dict:
    return {"event": t, "session_id": session_id, **data}


def _text(raw) -> str:
    # Gemini Live answers control frames as bytes on some paths — normalize.
    if isinstance(raw, (bytes, bytearray)):
        try:
            return bytes(raw).decode("utf-8", "replace")
        except Exception:
            return ""
    return raw if isinstance(raw, str) else ""


async def _watch_risk(ws: WebSocket, session_id: str, text: str) -> bool:
    """Run one finished caller turn through the risk engine. True = terminate."""
    try:
        sess = MANAGER.get(session_id)
        if sess is None or not text.strip():
            return False
        res = analyze(InterceptInput(
            session_id=session_id, channel=Channel.CALL,
            source=Source(type="UNKNOWN_CALLER", identifier=sess.caller),
            content=Content(text=text)), memory=sess.memory,
            user_memory=sess.scam_memory, language=sess.language)
        sess.last_result = res
        sess.language = res.language
        sess.transcript.append({"speaker": "caller", "text": text})
        await ws.send_json(_ev("RISK_UPDATED", session_id,
                               risk=res.risk_score, level=res.risk_level))
        if res.policy.must_terminate:
            sess.active = False
            await ws.send_json(_ev("CALL_TERMINATED", session_id,
                                   reason=res.policy.user_message,
                                   risk=res.risk_score))
            return True
    except Exception:
        pass
    return False


@router.websocket("/ws/live/{session_id}")
async def live_socket(ws: WebSocket, session_id: str):
    await ws.accept()
    if not cfg.GOOGLE_API_KEY:
        await ws.close(code=1011)
        return
    sess = MANAGER.get(session_id)
    if sess is None:
        sess = MANAGER.create(session_id, "unknown")

    opened = await open_live_session()
    if opened is None:
        await ws.close(code=1011)
        return
    gem, _live_model = opened

    await ws.send_json(_ev("VOICE_STARTED", session_id, caller=sess.caller))
    stop = asyncio.Event()
    pending_input: list[str] = []

    async def app_to_gemini() -> None:
        try:
            while not stop.is_set():
                try:
                    msg = await asyncio.wait_for(ws.receive_json(), timeout=120)
                except (asyncio.TimeoutError, WebSocketDisconnect):
                    break
                kind = msg.get("type")
                if kind == "audio" and msg.get("data"):
                    await gem.send(json.dumps(build_audio_chunk(msg["data"])))
                elif kind == "say" and (msg.get("text") or "").strip():
                    await gem.send(json.dumps(build_say(msg["text"].strip()[:600])))
                elif kind == "end":
                    break
        except Exception:
            pass
        finally:
            stop.set()

    async def gemini_to_app() -> None:
        try:
            while not stop.is_set():
                try:
                    raw_msg = _text(await asyncio.wait_for(gem.recv(), timeout=130))
                except (asyncio.TimeoutError, Exception):
                    break
                if not raw_msg:
                    continue
                try:
                    events = parse_server_msg(json.loads(raw_msg))
                except Exception:
                    continue
                for kind, payload in events:
                    if kind == "audio":
                        await ws.send_json(_ev("VOICE_AUDIO", session_id, data=payload))
                    elif kind == "output_text":
                        sess.transcript.append({"speaker": "intercept", "text": payload})
                        await ws.send_json(_ev("TRANSCRIPT_UPDATED", session_id,
                                               speaker="intercept", text=payload))
                    elif kind == "input_text":
                        pending_input.append(payload)
                        await ws.send_json(_ev("TRANSCRIPT_UPDATED", session_id,
                                               speaker="caller", text=payload))
                    elif kind == "turn_complete":
                        text = " ".join(pending_input).strip()
                        pending_input.clear()
                        if text and await _watch_risk(ws, session_id, text):
                            stop.set()
                            return
        except Exception:
            pass
        finally:
            stop.set()

    try:
        await asyncio.gather(app_to_gemini(), gemini_to_app())
    finally:
        stop.set()
        try:
            await gem.close()
        except Exception:
            pass
        try:
            await ws.close()
        except Exception:
            pass
