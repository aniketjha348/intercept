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


def test_owner_name_echoed_never_persisted():
    from fastapi.testclient import TestClient
    from app.ai.memory import CallMemory
    from app.main import app
    c = TestClient(app)
    r = c.post("/calls/start",
               json={"caller": "x", "owner_name": "Ramesh"}).json()
    assert r["owner_name"] == "Ramesh"
    mem = CallMemory()
    mem.owner = "Ramesh"
    assert "owner=Ramesh" in mem.summary()
    assert CallMemory().summary().find("owner=") == -1


def test_say_relay_queues_message():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "relay-test"}).json()["session_id"]
    r = c.post(f"/calls/{sid}/say", json={"text": "Who is calling, please?"})
    assert r.status_code == 200 and r.json()["event"] == "RELAY_QUEUED"
    assert c.post(f"/calls/{sid}/say", json={"text": "  "}).status_code == 422
    assert c.post("/calls/NOPE/say", json={"text": "hi"}).status_code == 404


def test_language_sticks_per_turn():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "lang-test"}).json()["session_id"]
    # Roman Hindi -> hinglish, and the session follows it.
    c.post(f"/calls/{sid}/transcript",
           json={"text": "SBI bank KYC vibhag se bol raha hoon", "speaker": "caller"})
    hg = c.post(f"/calls/{sid}/speak", json={"text": "hello"}).json()
    assert hg["language"] == "hinglish", hg
    # Devanagari -> hi, session follows again.
    c.post(f"/calls/{sid}/transcript",
           json={"text": "मैं SBI बैंक से बोल रहा हूँ", "speaker": "caller"})
    hi = c.post(f"/calls/{sid}/speak", json={"text": "hello"}).json()
    assert hi["language"] == "hi", hi
    # Plain English -> en.
    c.post(f"/calls/{sid}/transcript",
           json={"text": "Hello, I am calling from SBI bank head office", "speaker": "caller"})
    en = c.post(f"/calls/{sid}/speak", json={"text": "hello"}).json()
    assert en["language"] == "en", en


def test_turn_carries_claimed_org():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "org-test"}).json()["session_id"]
    r = c.post(f"/calls/{sid}/transcript",
               json={"text": "SBI bank here", "speaker": "caller"}).json()
    assert "claimed_org" in r, r.keys()


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


def test_live_bridge_builders():
    from app.realtime import live_bridge as lb
    s = lb.build_setup()
    assert "gemini" in s["setup"]["model"]
    assert s["setup"]["generationConfig"]["responseModalities"] == ["AUDIO"]
    assert lb.build_audio_chunk("AAA")["realtimeInput"]["audio"]["data"] == "AAA"
    evs = lb.parse_server_msg({"serverContent": {
        "modelTurn": {"parts": [{"inlineData": {"data": "XYZ"}}]},
        "inputTranscription": {"text": "hello"},
        "outputTranscription": {"text": "hi"},
        "turnComplete": True}})
    assert {"audio", "input_text", "output_text", "turn_complete"} <= {k for k, _ in evs}
    assert lb.parse_server_msg({}) == []


def test_live_bridge_no_key_closes(monkeypatch):
    import app.core_config as cfg
    monkeypatch.setattr(cfg, "GOOGLE_API_KEY", "")
    from fastapi.testclient import TestClient
    from starlette.websockets import WebSocketDisconnect
    from app.main import app
    import pytest
    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "live-test"}).json()["session_id"]
    with pytest.raises(WebSocketDisconnect):
        with c.websocket_connect(f"/ws/live/{sid}") as ws:
            ws.receive_json()


def test_report_has_summary():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "sum-test"}).json()["session_id"]
    c.post(f"/calls/{sid}/transcript",
           json={"text": "SBI KYC, share OTP now", "speaker": "caller"})
    rep = c.post(f"/calls/{sid}/end", json={}).json()["report"]
    assert isinstance(rep.get("summary"), str) and len(rep["summary"]) > 10


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
