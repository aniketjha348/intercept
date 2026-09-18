"""Multilingual + provider contract tests — deterministic, no network, no keys."""
from app.ai import llm, providers
from app.ai.memory import CallMemory
from app.i18n import detect_language, resolve_language
from app.intelligence.scam_memory import ScamMemory
from app.pipeline import analyze
from app.schemas import Channel, Content, InterceptInput, Source


def _inp(text="", channel=Channel.CALL, **kw):
    return InterceptInput(channel=channel, source=Source(type="TEST"),
                          content=Content(text=text, **kw))


def test_detect_hindi_devanagari():
    assert detect_language("मैं SBI बैंक से बोल रहा हूँ") == "hi"


def test_detect_hinglish():
    assert detect_language("turant OTP batao warna account band ho jayega") == "hinglish"


def test_detect_english():
    assert detect_language("Hi, can we meet for lunch tomorrow?") == "en"


def test_explicit_language_wins():
    assert resolve_language("hi", "hello there") == "hi"


def test_hindi_scam_is_critical_with_hindi_messages():
    r = analyze(_inp("मैं SBI बैंक KYC विभाग से बोल रहा हूँ। 2 घंटे में आपका खाता बंद हो जाएगा। "
                     "तुरंत अपना OTP बताएं।"),
                memory=CallMemory(), user_memory=ScamMemory(), use_llm=False)
    assert r.language == "hi", r.language
    assert r.risk_level == "CRITICAL" and r.risk_score >= 75, r
    assert r.policy.must_terminate is True
    assert any("ओटीपी" in w for w in r.explanation), r.explanation
    assert "ओटीपी" in r.policy.simple_mode_message or "OTP" in r.policy.simple_mode_message
    # Offline fallback guardian reply must be Hindi, not English.
    assert any("\u0900" <= ch <= "\u097f" for ch in r.guardian_reply), r.guardian_reply


def test_hinglish_scam_is_critical():
    r = analyze(_inp("Main SBI bank KYC department se bol raha hoon. 2 ghante me khata band ho jayega. "
                     "Turant OTP batao."),
                user_memory=ScamMemory(), use_llm=False)
    assert r.language == "hinglish", r.language
    assert r.risk_level == "CRITICAL", r
    assert "OTP" in r.policy.simple_mode_message


def test_english_still_low_and_english():
    r = analyze(_inp("Hi, can we meet for lunch tomorrow at noon?"), use_llm=False)
    assert r.language == "en" and r.risk_level == "LOW", r


def test_providers_graceful_and_live(monkeypatch):
    # Provider off → always None, never an exception (rules carry the risk).
    monkeypatch.setattr("app.core_config.LLM_PROVIDER", "off")
    assert providers.complete_text("sys", "hi") is None
    assert providers.complete_json('{"a": 1} text') is None
    assert "Hello" in llm.guardian_reply("LOW", "x", "hi", "", "en")
    # With real keys (local .env), Gemini answers — live integration proof.
    from app import core_config as cfg
    if cfg.GOOGLE_API_KEY and cfg.LLM_PROVIDER != "off":
        out = providers.complete_text("Reply briefly.", "Say OK", max_tokens=10)
        assert out and "OK" in out.upper()
