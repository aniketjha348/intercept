"""LiveKit join tokens for the hackathon voice demo (judges join from phones).

GET /livekit/token?identity=xxx -> {token, url, room}. The agent worker
(./livekit-agent) joins the same room as `intercept-agent`. Without LiveKit
env configured this returns 503 — never a crash.
"""
from __future__ import annotations

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


@router.get("/token", response_model=TokenOut)
def token(identity: str = Query(default="judge", max_length=64)):
    if not (cfg.LIVEKIT_URL and cfg.LIVEKIT_KEY and cfg.LIVEKIT_SECRET):
        raise HTTPException(503, "live voice demo not configured")
    try:
        from livekit import api as lk
    except ImportError:
        raise HTTPException(503, "livekit server SDK not installed")
    ident = (identity or "judge").strip()[:64] or "judge"
    room = f"intercept-{_secrets.token_hex(4)}"
    tk = (lk.AccessToken(cfg.LIVEKIT_KEY, cfg.LIVEKIT_SECRET)
          .with_identity(ident)
          .with_grants(lk.VideoGrants(room_join=True, room=room)))
    return TokenOut(token=tk.to_jwt(), url=cfg.LIVEKIT_URL, room=room,
                    identity=ident)
