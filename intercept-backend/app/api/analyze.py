"""Channel gateway: one analysis endpoint per non-call channel, same pipeline."""
from __future__ import annotations

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.intelligence import scam_memory
from app.pipeline import analyze
from app.schemas import Channel, Content, InterceptInput, Source

router = APIRouter(tags=["analyze"])


def _mem(request: Request):
    """Per-user Scam DNA via the app's X-User-Id header (anonymous fallback)."""
    return scam_memory.for_user(request.headers.get("x-user-id"))


class TextIn(BaseModel):
    text: str
    channel: Channel = Channel.SMS
    source_id: str | None = None
    language: str = "auto"


class ScreenshotIn(BaseModel):
    text: str | None = None
    image_b64: str | None = None
    source_id: str | None = None
    language: str = "auto"


class UrlIn(BaseModel):
    url: str
    context_text: str | None = None
    language: str = "auto"


class QrIn(BaseModel):
    qr_text: str | None = None
    image_b64: str | None = None
    context_text: str | None = None
    language: str = "auto"


def _out(res):
    return {"risk": res.risk_score, "level": res.risk_level,
            "language": res.language, "policy": res.policy.action,
            "signals": [s.model_dump() for s in res.signals],
            "attack_chain": [c.model_dump() for c in res.attack_chain],
            "events": [e.model_dump() for e in res.events],
            "why": res.explanation, "likely_objective": res.likely_objective,
            "similar_pattern": res.similar_pattern,
            "user_message": res.policy.user_message,
            "simple_mode": res.policy.simple_mode_message}


@router.post("/analyze/text")
def analyze_text(body: TextIn, request: Request):
    res = analyze(InterceptInput(channel=body.channel,
                                 source=Source(type="MESSAGE", identifier=body.source_id),
                                 content=Content(text=body.text)),
                  user_memory=_mem(request), language=body.language)
    return _out(res)


@router.post("/analyze/screenshot")
def analyze_screenshot(body: ScreenshotIn, request: Request):
    res = analyze(InterceptInput(channel=Channel.SCREENSHOT,
                                 source=Source(type="SCREENSHOT", identifier=body.source_id),
                                 content=Content(text=body.text, image_b64=body.image_b64)),
                  user_memory=_mem(request), language=body.language)
    return _out(res)


@router.post("/analyze/url")
def analyze_url(body: UrlIn, request: Request):
    res = analyze(InterceptInput(channel=Channel.URL,
                                 source=Source(type="LINK"),
                                 content=Content(text=body.context_text, url=body.url)),
                  user_memory=_mem(request), language=body.language)
    return _out(res)


@router.post("/analyze/qr")
def analyze_qr(body: QrIn, request: Request):
    res = analyze(InterceptInput(channel=Channel.QR, source=Source(type="QR"),
                                 content=Content(text=body.context_text,
                                                 qr_text=body.qr_text,
                                                 image_b64=body.image_b64)),
                  user_memory=_mem(request), language=body.language)
    return _out(res)
