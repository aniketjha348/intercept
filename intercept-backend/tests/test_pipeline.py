"""Pipeline contract tests — deterministic, no network, no keys."""
from app.ai.memory import CallMemory
from app.intelligence.scam_memory import ScamMemory
from app.pipeline import analyze
from app.schemas import Channel, Content, InterceptInput, Source


def _inp(text="", channel=Channel.CALL, **kw):
    return InterceptInput(channel=channel, source=Source(type="TEST"),
                          content=Content(text=text, **kw))


def test_benign_is_low():
    r = analyze(_inp("Hi, can we meet for lunch tomorrow at noon?"))
    assert r.risk_level == "LOW" and r.risk_score <= 24, r
    assert r.policy.action == "CONTINUE"


def test_bank_otp_is_critical():
    r = analyze(_inp("I am calling from SBI bank KYC department. Your account will be blocked "
                     "within 2 hours. Share your OTP immediately to verify."),
                memory=CallMemory(), user_memory=ScamMemory())
    assert r.risk_level == "CRITICAL" and r.risk_score >= 75, r
    assert r.policy.must_terminate is True
    assert any(s.code == "OTP_REQUEST" for s in r.signals)
    assert any(c.stage == "AUTHORITY" for c in r.attack_chain)


def test_phishing_url_scores_high():
    r = analyze(_inp("Verify now", channel=Channel.URL),
                memory=None, user_memory=ScamMemory(),
                use_llm=False) if False else analyze(
        InterceptInput(channel=Channel.URL, source=Source(type="LINK"),
                       content=Content(text="click to verify your account login",
                                       url="https://sbi-security-verify.example.com/login")),
        user_memory=ScamMemory(), use_llm=False)
    codes = {s.code for s in r.signals}
    assert "PHISHING_URL" in codes or "SUSPICIOUS_URL" in codes, r
    assert r.risk_score >= 50, r


def test_upi_qr_is_payment_request():
    r = analyze(InterceptInput(channel=Channel.QR, source=Source(type="QR"),
                               content=Content(qr_text="upi://pay?pa=scammer@upi&pn=X")),
                user_memory=ScamMemory(), use_llm=False)
    assert any(s.code == "PAYMENT_REQUEST" for s in r.signals), r


def test_prize_lure_is_suspicious():
    r = analyze(_inp("Congratulations! You won a lottery prize of Rs 50000. Claim cashback now."),
                user_memory=ScamMemory(), use_llm=False)
    assert any(s.code == "INCENTIVE_PRIZE" for s in r.signals), r
    assert r.risk_score >= 25, r  # never LOW for a prize lure


def test_official_brand_domain_not_high():
    r = analyze(InterceptInput(channel=Channel.URL, source=Source(type="LINK"),
                               content=Content(text="RBI info page",
                                               url="https://www.rbi.org.in/commonman")),
                user_memory=ScamMemory(), use_llm=False)
    assert r.risk_score < 50, r  # real rbi.org.in must not be flagged


def test_spoofed_brand_domain_still_high():
    r = analyze(InterceptInput(channel=Channel.URL, source=Source(type="LINK"),
                               content=Content(text="verify now",
                                               url="https://sbi-security-verify.example.com/login")),
                user_memory=ScamMemory(), use_llm=False)
    assert r.risk_score >= 50, r


def test_speak_graceful_without_key(monkeypatch):
    monkeypatch.setattr("app.core_config.GOOGLE_API_KEY", "")
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "voice-test"}).json()["session_id"]
    c.post(f"/calls/{sid}/transcript", json={"text": "SBI KYC, OTP batao", "speaker": "caller"})
    r = c.post(f"/calls/{sid}/speak", json={})
    assert r.status_code == 200, r.text
    assert r.json()["voice"] is False and r.json()["audio_b64"] is None
    assert c.post("/calls/NOPE/speak", json={}).status_code == 404


def test_speak_cached_wav(monkeypatch):
    import base64 as _b64
    import app.ai.voice as v
    v._CACHE.clear()
    monkeypatch.setattr(v, "_synthesize", lambda t, lang: b"PCM" * 100)
    b1, cold = v.speak_cached("hello there", "en")
    b2, hot = v.speak_cached("hello there", "en")
    assert b1 and cold is False and hot is True and b2 == b1
    assert _b64.b64decode(b1)[:4] == b"RIFF"  # real WAV container, plays anywhere


def test_scam_memory_flags_repeat_pattern():
    mem = ScamMemory()
    analyze(_inp("SBI bank KYC blocked, share OTP now"), user_memory=mem, use_llm=False)
    r2 = analyze(_inp("HDFC KYC frozen, share OTP immediately"), user_memory=mem, use_llm=False)
    assert r2.similar_pattern is not None, r2
