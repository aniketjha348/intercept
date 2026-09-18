"""Production-guard tests — CORS, payload cap, rate limit (no network)."""
from fastapi.testclient import TestClient

from app import guards
from app.main import app


def test_cors_allows_any_origin():
    c = TestClient(app)
    r = c.get("/health", headers={"Origin": "https://example.com"})
    assert r.status_code == 200
    assert r.headers.get("access-control-allow-origin") == "*"


def test_payload_cap_413():
    c = TestClient(app)
    big = "x" * (2 * 1024 * 1024 + 1)
    r = c.post("/analyze/text", json={"text": big})
    assert r.status_code == 413


def test_register_is_stable_and_opaque():
    c = TestClient(app)
    a = c.post("/users/register", json={"device_id": "dev-123"})
    b = c.post("/users/register", json={"device_id": "dev-123"})
    other = c.post("/users/register", json={"device_id": "dev-999"})
    assert a.status_code == 200 and a.json()["user_id"] == b.json()["user_id"]
    assert "dev-123" not in a.json()["user_id"]
    assert other.json()["user_id"] != a.json()["user_id"]


def test_memory_isolated_per_user():
    from app.intelligence import scam_memory
    c = TestClient(app)
    scam = {"text": "SBI bank KYC blocked, share OTP now", "channel": "SMS"}
    c.post("/analyze/text", json=scam, headers={"X-User-Id": "u_aaa"})
    r2 = c.post("/analyze/text", json={"text": "HDFC KYC frozen, share OTP immediately", "channel": "SMS"},
                headers={"X-User-Id": "u_aaa"})
    stranger = c.post("/analyze/text", json={"text": "HDFC KYC frozen, share OTP immediately", "channel": "SMS"},
                      headers={"X-User-Id": "u_bbb"})
    assert r2.json()["similar_pattern"], "same user must recall the pattern"
    assert not stranger.json()["similar_pattern"], "strangers must not share memory"
    scam_memory._USERS.clear()


def test_persisted_text_scrubs_otp_like_digits():
    from app.db.repository import scrub_secrets
    assert scrub_secrets("my OTP is 482913, card 411111") == "my OTP is ••••, card ••••"
    assert scrub_secrets("call me at 5pm") == "call me at 5pm"
    assert scrub_secrets("") == ""


def test_whatsapp_verify_handshake(monkeypatch):
    import app.core_config as cfg
    from fastapi.testclient import TestClient
    from app.main import app
    monkeypatch.setattr(cfg, "WHATSAPP_VERIFY", "s3cr3t")
    c = TestClient(app)
    ok = c.get("/webhooks/whatsapp", params={"hub.mode": "subscribe",
               "hub.verify_token": "s3cr3t", "hub.challenge": "CHAL"})
    assert ok.status_code == 200 and ok.text == "CHAL"
    bad = c.get("/webhooks/whatsapp", params={"hub.mode": "subscribe",
                "hub.verify_token": "nope", "hub.challenge": "CHAL"})
    assert bad.status_code == 403


def test_whatsapp_off_without_env(monkeypatch):
    import app.core_config as cfg
    from fastapi.testclient import TestClient
    from app.main import app
    monkeypatch.setattr(cfg, "WHATSAPP_TOKEN", "")
    monkeypatch.setattr(cfg, "WHATSAPP_PHONE_ID", "")
    c = TestClient(app)
    r = c.post("/webhooks/whatsapp", json={"entry": []})
    assert r.status_code == 200 and r.json()["status"] == "off"


def test_whatsapp_forwards_verdict(monkeypatch):
    import app.api.whatsapp as wa
    import app.core_config as cfg
    from fastapi.testclient import TestClient
    from app.main import app
    monkeypatch.setattr(cfg, "WHATSAPP_TOKEN", "tok")
    monkeypatch.setattr(cfg, "WHATSAPP_PHONE_ID", "123")
    sent = []
    monkeypatch.setattr(wa, "_send_text",
                        lambda to, body: sent.append((to, body)) or True)
    c = TestClient(app)
    msgs = [
        {"type": "text", "from": "919999999999",
         "text": {"body": "SBI KYC blocked, share OTP now"}},
        {"type": "text", "from": "918888888888",
         "text": {"body": "Hi, lunch tomorrow?"}},
    ]
    payload = {"entry": [{"changes": [{"value": {"messages": msgs}}]}]}
    r = c.post("/webhooks/whatsapp", json=payload)
    assert r.status_code == 200 and r.json()["replied"] == 2, r.text
    by_to = dict(sent)
    assert "CRITICAL" in by_to["919999999999"] or "DANGER" in by_to["919999999999"]
    assert "safe" in by_to["918888888888"].lower()


def test_rate_limit_429(monkeypatch):
    monkeypatch.setattr(guards, "MAX_REQUESTS_PER_MINUTE", 2)
    guards._hits.clear()
    try:
        c = TestClient(app)
        assert c.get("/app/latest").status_code == 200
        assert c.get("/app/latest").status_code == 200
        assert c.get("/app/latest").status_code == 429
        # Exempt paths never limited.
        assert c.get("/health").status_code == 200
    finally:
        guards._hits.clear()
