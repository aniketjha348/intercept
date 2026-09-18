"""Round 2 of the audit — regressions for bugs found after the first sweep.

Each test pins a bug that was live in the request path, so a future change that
reintroduces it fails here instead of silently degrading detection.
"""
from __future__ import annotations

import hashlib
import hmac

from app import core_config as cfg
from app.detection.signals import extract_urls
from app.i18n import detect_language


# ---- Scheme-less "www." links must reach the URL engine ----


def test_www_link_is_extracted_from_text():
    """URL_RE only matched https?:// — so the most common form of scam link in
    an SMS or chat ('www.bank-kyc.xyz/verify') was never handed to the engine."""
    urls = extract_urls("Dear user, verify at www.sbi-kyc-verify.xyz/update now")
    assert urls == ["www.sbi-kyc-verify.xyz/update"]


def test_www_link_produces_url_signals_end_to_end():
    from app.pipeline import analyze
    from app.schemas import Channel, Content, InterceptInput, Source

    res = analyze(InterceptInput(
        channel=Channel.SMS, source=Source(type="MESSAGE"),
        content=Content(text="Your KYC expires today: www.sbi-kyc-verify.xyz/update"),
    ), use_llm=False)
    assert res.risk_score > 0
    assert any(s.code in ("PHISHING_URL", "SUSPICIOUS_URL") for s in res.signals)


def test_prose_is_not_mistaken_for_a_hostname():
    """Bare domains are deliberately not extracted: in prose, 'no.However'
    is shaped exactly like a hostname."""
    assert extract_urls("I said no.However it still happened.") == []
    assert extract_urls("Call me at 10.Then we talk") == []


def test_http_links_still_extracted():
    assert extract_urls("go to https://example.com/x") == ["https://example.com/x"]


# ---- Language detection must not turn English into Hinglish ----


def test_english_containing_main_is_not_hinglish():
    """'main', 'mat', 'double', 'sun' and 'hum' are ordinary English words, so
    they used to flip English input to Hinglish — changing the reply language,
    the policy text and the TTS voice."""
    assert detect_language("Please check your main account statement") == "en"
    assert detect_language("Double check the main door please") == "en"
    assert detect_language("The sun is bright today") == "en"


def test_real_hinglish_is_still_detected():
    assert detect_language("Main apna khata check kar raha hoon") == "hinglish"
    assert detect_language("aap turant paise bhejo") == "hinglish"


def test_hindi_script_still_detected():
    assert detect_language("आपका खाता बंद हो जाएगा") == "hi"


# ---- WhatsApp webhook verification ----


def test_whatsapp_signature_verification(monkeypatch):
    from app.api import whatsapp

    body = b'{"entry": []}'
    secret = "app-secret-xyz"
    good = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()

    monkeypatch.setattr(cfg, "WHATSAPP_APP_SECRET", secret)
    assert whatsapp._signature_ok(body, good) is True
    assert whatsapp._signature_ok(body, "sha256=deadbeef") is False
    assert whatsapp._signature_ok(body, None) is False
    # A body swapped after signing must not pass.
    assert whatsapp._signature_ok(b'{"entry": [1]}', good) is False


def test_whatsapp_without_secret_still_works_but_is_flagged(monkeypatch):
    from app.api import whatsapp

    monkeypatch.setattr(cfg, "WHATSAPP_APP_SECRET", "")
    # Documented activation flow predates the secret, so it must not hard-fail...
    assert whatsapp._signature_ok(b"{}", None) is True


# ---- Takeover must silence the AI on the realtime socket too ----


def _turn(ws, text: str) -> list[dict]:
    """Send a caller turn and read until the turn's closing event."""
    ws.send_json({"type": "caller_turn", "text": text})
    out: list[dict] = []
    for _ in range(60):
        try:
            msg = ws.receive_json()
        except Exception:
            break
        out.append(msg)
        # Every caller turn ends with one of these, so the read is bounded.
        if msg.get("event") in ("TAKEOVER_AVAILABLE", "CALL_TERMINATED"):
            break
    return out


def test_takeover_silences_the_ai_on_the_socket():
    """The REST turn path honours human_mode; the socket path did not, so the
    guardian kept talking over the owner who had just taken the call."""
    from fastapi.testclient import TestClient
    from app.main import app

    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "audit-takeover"}).json()["session_id"]
    try:
        with c.websocket_connect(f"/ws/calls/{sid}") as ws:
            ws.receive_json()  # CALL_STARTED
            before = _turn(ws, "Namaste, main bank se bol raha hoon.")
            assert any(m.get("event") == "AI_RESPONSE_FINISHED" for m in before)

            ws.send_json({"type": "takeover"})
            assert ws.receive_json().get("human_mode") is True

            after = _turn(ws, "Aapka account verify karna hai.")
            # Risk still flows (monitoring continues)...
            assert any(m.get("event") == "RISK_UPDATED" for m in after)
            # ...but the AI says nothing while the human is speaking.
            assert not any(m.get("event") == "AI_RESPONSE_FINISHED" for m in after)
    finally:
        c.post(f"/calls/{sid}/end", json={})
