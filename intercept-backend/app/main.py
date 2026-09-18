"""INTERCEPT core — FastAPI wiring."""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app import core_config as cfg
from app.ai import llm
from app.api import analyze, appcast, calls, users, whatsapp
from app.guards import guard
from app.realtime import live_bridge, websocket

app = FastAPI(title="INTERCEPT", version="0.1.0",
              description="AI Social Engineering Firewall — unified Security Intelligence Pipeline")
# Website + phones call from any origin (public API, no cookies/auth to protect).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)
app.middleware("http")(guard)
app.include_router(calls.router)
app.include_router(analyze.router)
app.include_router(appcast.router)
app.include_router(users.router)
app.include_router(whatsapp.router)
app.include_router(websocket.router)
app.include_router(live_bridge.router)


@app.get("/health")
def health():
    from app.db.repository import REPO
    return {"status": "ok", "app": cfg.APP_NAME,
            "llm": cfg.LLM_PROVIDER, "network_fetch": cfg.ALLOW_NETWORK_FETCH,
            "db": "postgres" if REPO.persistent else "memory",
            **llm.provider_status(), "languages": "hi,hinglish,en"}


@app.get("/")
def root():
    return JSONResponse({"app": "INTERCEPT", "docs": "/docs", "health": "/health",
                         "demo": "POST /calls/start then POST /calls/{id}/transcript"})
