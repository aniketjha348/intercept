"""LiveKit join tokens for the hackathon voice demo (judges join from phones).

GET /livekit/token?identity=xxx -> {token, url, room}. The agent worker
(./livekit-agent) joins the same room as `intercept-agent`. Without LiveKit
env configured this returns 503 — never a crash.
"""
from __future__ import annotations

import re
import secrets as _secrets

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from app import core_config as cfg

router = APIRouter(prefix="/livekit", tags=["livekit"])


class TokenOut(BaseModel):
    token: str
    url: str
    room: str
    identity: str


class DispatchIn(BaseModel):
    room: str
    agent_name: str = "intercept-agent"


@router.get("/token", response_model=TokenOut)
def token(identity: str = Query(default="judge", max_length=64),
          room: str | None = Query(default=None, max_length=128)):
    if not (cfg.LIVEKIT_URL and cfg.LIVEKIT_KEY and cfg.LIVEKIT_SECRET):
        raise HTTPException(503, "live voice demo not configured")
    try:
        from livekit import api as lk
    except ImportError:
        raise HTTPException(503, "livekit server SDK not installed")
    ident = (identity or "judge").strip()[:64] or "judge"
    # App calls pass room=intercept-<call session id> so the agent can post
    # turns into OUR session (transcript/risk/report keep working). Otherwise
    # a random room (judge demo). Strict charset: rooms become URL/path parts.
    wanted = (room or "").strip()
    if wanted and not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", wanted):
        raise HTTPException(422, "bad room name")
    room_name = wanted or f"intercept-{_secrets.token_hex(4)}"
    tk = (lk.AccessToken(cfg.LIVEKIT_KEY, cfg.LIVEKIT_SECRET)
          .with_identity(ident)
          .with_grants(lk.VideoGrants(room_join=True, room=room_name)))
    return TokenOut(token=tk.to_jwt(), url=cfg.LIVEKIT_URL, room=room_name,
                    identity=ident)


@router.post("/dispatch")
async def dispatch(body: DispatchIn):
    """Send the voice agent into an app-created room (answered call audio
    goes phone -> LiveKit room -> agent; transcript streams back)."""
    if not (cfg.LIVEKIT_URL and cfg.LIVEKIT_KEY and cfg.LIVEKIT_SECRET):
        raise HTTPException(503, "live voice demo not configured")
    room = (body.room or "").strip()[:128]
    if not room:
        raise HTTPException(422, "room required")
    try:
        from livekit import api as lk
        client = lk.LiveKitAPI(cfg.LIVEKIT_URL, cfg.LIVEKIT_KEY, cfg.LIVEKIT_SECRET)
        try:
            await client.agent_dispatch.create_dispatch(
                lk.CreateAgentDispatchRequest(
                    agent_name=body.agent_name or "intercept-agent",
                    room=room,
                )
            )
        finally:
            try:
                await client.aclose()
            except Exception:
                pass
        return {"status": "dispatched", "room": room}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"dispatch failed: {exc}")
