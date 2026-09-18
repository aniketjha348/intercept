"""WhatsApp bot number (§2b): forward ANY suspicious message to it, get a
verdict back in chat. Zero paste, zero app needed — works on iPhone too.

Activation (founder does once in Meta dashboard):
  1. developers.facebook.com → Create app → add WhatsApp product.
  2. API Setup → temporary/permanent token → env WHATSAPP_TOKEN.
  3. Configuration → webhook URL https://<API>/webhooks/whatsapp
     ALSO set WHATSAPP_APP_SECRET (Settings → Basic → App secret). Every
     inbound POST is signed with it; without the secret the webhook accepts
     unverified payloads, which would let a stranger drive our bot number.
     (NOTE: Meta demands HTTPS — needs the API Gateway step first),
     verify token = env WHATSAPP_VERIFY, Subscribe to `messages`.
  4. Send a message to the bot number (or forward any chat to it).

Without env set, these endpoints politely report OFF (never crash).
"""
from __future__ import annotations

import hashlib
import hmac
import json

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, PlainTextResponse

from app import core_config as cfg
from app.intelligence import scam_memory
from app.pipeline import analyze
from app.schemas import Channel, Content, InterceptInput, Source

router = APIRouter(prefix="/webhooks/whatsapp", tags=["whatsapp"])

GRAPH = "https://graph.facebook.com/v21.0"


def _enabled() -> bool:
    return bool(cfg.WHATSAPP_TOKEN and cfg.WHATSAPP_PHONE_ID)


@router.get("")
async def verify(request: Request):
    """Meta webhook verification handshake."""
    q = request.query_params
    if (q.get("hub.mode") == "subscribe"
            and q.get("hub.verify_token")
            and q.get("hub.verify_token") == cfg.WHATSAPP_VERIFY):
        return PlainTextResponse(q.get("hub.challenge", ""))
    return JSONResponse({"detail": "bad verify token"}, status_code=403)


def _send_text(to: str, body: str) -> bool:
    try:
        r = httpx.post(
            f"{GRAPH}/{cfg.WHATSAPP_PHONE_ID}/messages",
            headers={"Authorization": f"Bearer {cfg.WHATSAPP_TOKEN}"},
            json={"messaging_product": "whatsapp", "to": to,
                  "text": {"body": body[:1500]}},
            timeout=20)
        return r.status_code == 200
    except Exception:
        return False


def _signature_ok(raw: bytes, header: str | None) -> bool:
    """Verify Meta's X-Hub-Signature-256 over the raw body.

    Unverified, anyone who learns the webhook URL can post a payload and make
    our business number send messages to any number — a spam relay, and a
    WhatsApp policy violation waiting to happen. With the secret configured a
    forged payload is rejected. Without it we still work (the documented
    activation flow predates this), but the response says so out loud.
    """
    secret = cfg.WHATSAPP_APP_SECRET
    if not secret:
        return True
    if not header:
        return False
    expected = hmac.new(secret.encode("utf-8"), raw, hashlib.sha256).hexdigest()
    return hmac.compare_digest(f"sha256={expected}", header.strip())


def _verdict(text: str, sender: str) -> str:
    # Per-sender Scam DNA, not one bucket for every WhatsApp user on earth.
    res = analyze(InterceptInput(channel=Channel.WHATSAPP,
                                 source=Source(type="WHATSAPP_BOT"),
                                 content=Content(text=text)),
                  user_memory=scam_memory.for_user(f"wa:{sender}"))
    simple = res.policy.simple_mode_message or res.policy.user_message
    head = {"CRITICAL": "🛡 DANGER", "HIGH": "⚠️ RISKY",
            "SUSPICIOUS": "⚠️ CAREFUL"}.get(res.risk_level, "✅ Looks safe")
    lines = [f"{head} — {res.risk_level} {res.risk_score}"]
    if simple:
        lines.append(simple)
    if res.risk_level in ("CRITICAL", "HIGH") and res.likely_objective not in ("", "Unknown"):
        lines.append(f"Likely aim: {res.likely_objective}")
    return "\n".join(lines)


@router.post("")
async def incoming(request: Request):
    if not _enabled():
        return {"status": "off",
                "hint": "Set WHATSAPP_TOKEN + WHATSAPP_PHONE_ID to activate the bot."}
    try:
        raw = await request.body()
        payload = json.loads(raw.decode("utf-8") or "{}")
    except Exception:
        return {"status": "ignored"}
    if not _signature_ok(raw, request.headers.get("x-hub-signature-256")):
        return JSONResponse({"detail": "bad signature"}, status_code=403)
    replied = 0
    try:
        for entry in payload.get("entry", []):
            for change in entry.get("changes", []):
                value = change.get("value", {})
                for msg in value.get("messages", []):
                    if msg.get("type") != "text":
                        continue
                    text = ((msg.get("text") or {}).get("body") or "").strip()
                    sender = msg.get("from", "")
                    if not text or not sender:
                        continue
                    if _send_text(sender, _verdict(text, sender)):
                        replied += 1
    except Exception:
        pass
    if replied and not cfg.WHATSAPP_APP_SECRET:
        return {"status": "ok", "replied": replied,
                "warning": "WHATSAPP_APP_SECRET not set — inbound webhooks are unverified"}
    return {"status": "ok", "replied": replied}
