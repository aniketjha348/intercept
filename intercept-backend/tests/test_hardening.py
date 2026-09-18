"""Regressions for the audit fixes — each one pins a bug that was live.

Every test here failed (or would have) before the change it guards, so a
regression shows up as a failed test rather than another silent bypass.
"""
from __future__ import annotations

import time

from app.db.repository import scrub_report, scrub_secrets
from app.intelligence.url_intel import analyze_url
from app.realtime.sessions import MANAGER, CallSession
from app.schemas import Channel, Content, InterceptInput, Source


# ---- URL engine: scheme-less links are the most common form of the attack ----


def test_scheme_less_link_is_analyzed():
    """`www.` / bare-domain links used to be rejected as not_a_url, so the URL
    engine silently skipped exactly the links people tap in chat messages."""
    sigs, findings = analyze_url("www.sbi-kyc-verify.xyz/update")
    codes = {s.code for s in sigs}
    assert "PHISHING_URL" in codes, findings
    assert findings.get("scheme_assumed") == "http"


def test_bare_domain_without_scheme_is_analyzed():
    sigs, _ = analyze_url("sbi-kyc-verify.xyz/verify")
    assert any(s.code in ("PHISHING_URL", "SUSPICIOUS_URL") for s in sigs)


def test_scheme_less_domain_is_not_flagged_as_insecure_http():
    """Assuming http must not manufacture an 'insecure scheme' finding that the
    original text never contained."""
    sigs, _ = analyze_url("sbi-kyc-verify.xyz/update")
    assert not any("Insecure http scheme" in (s.evidence or "") for s in sigs)
    # ...but an explicitly http:// link still is.
    sigs2, _ = analyze_url("http://sbi-kyc-verify.xyz/update")
    assert any("Insecure http scheme" in (s.evidence or "") for s in sigs2)


def test_empty_url_still_rejected():
    sigs, findings = analyze_url("   ")
    assert sigs == []
    assert findings.get("error") == "not_a_url"


def test_bare_domain_reaches_the_risk_engine():
    """End to end: a scheme-less link in a message body must produce risk."""
    from app.pipeline import analyze
    res = analyze(InterceptInput(
        channel=Channel.SMS, source=Source(type="MESSAGE"),
        content=Content(text="Your KYC is expiring, update at www.sbi-kyc-verify.xyz/update"),
    ), use_llm=False)
    assert res.risk_score > 0


# ---- Privacy: the report must not be the back door that keeps the OTP ----


def test_report_is_scrubbed_before_persist():
    report = {
        "transcript": [{"speaker": "caller", "text": "Your OTP is 4821, tell me now"}],
        "summary": "Caller asked for OTP 4821.",
        "why": ["asked for a 6 digit code 123456"],
        "action": "Call terminated by INTERCEPT",
        "risk": 90,
        "turns": 3,
    }
    stored = scrub_report(report)
    assert "4821" not in stored["transcript"][0]["text"]
    assert "4821" not in stored["summary"]
    assert "123456" not in stored["why"][0]
    # Numbers that matter to the report survive (score/turns are not strings).
    assert stored["risk"] == 90 and stored["turns"] == 3


def test_scrub_report_does_not_mutate_the_live_copy():
    """The in-memory report is what a live session reads — scrubbing it in place
    would blind the session that is still running."""
    report = {"transcript": [{"speaker": "caller", "text": "code 4821"}]}
    scrub_report(report)
    assert report["transcript"][0]["text"] == "code 4821"


def test_scrub_report_survives_missing_fields():
    assert scrub_report({}) == {}
    assert scrub_report({"transcript": None})["transcript"] is None


# ---- Sessions: bounded, and the escalation trail ----


def test_sessions_are_bounded():
    saved = dict(MANAGER.calls)
    try:
        MANAGER.calls.clear()
        for i in range(MANAGER.MAX_SESSIONS):
            MANAGER.create(f"audit_old_{i}", "x").active = False
        for i in range(50):
            MANAGER.create(f"audit_new_{i}", "x")
        assert len(MANAGER.calls) <= MANAGER.MAX_SESSIONS
    finally:
        MANAGER.calls.clear()
        MANAGER.calls.update(saved)


def test_live_sessions_are_never_evicted():
    saved = dict(MANAGER.calls)
    try:
        MANAGER.calls.clear()
        live = MANAGER.create("audit_live", "x")
        for i in range(MANAGER.MAX_SESSIONS + 20):
            MANAGER.create(f"audit_dead_{i}", "x").active = False
        assert MANAGER.get("audit_live") is live
    finally:
        MANAGER.calls.clear()
        MANAGER.calls.update(saved)


def test_escalating_detects_a_rising_call():
    sess = CallSession(id="s", caller="x")
    assert not sess.escalating  # too little history to call it
    for r in (20, 30, 40):
        sess.note_risk(r)
    assert not sess.escalating  # rising, but not yet dangerous
    sess.note_risk(75)
    assert sess.escalating


def test_escalating_ignores_a_flat_call():
    sess = CallSession(id="s", caller="x")
    for _ in range(5):
        sess.note_risk(80)
    assert not sess.escalating


def test_risk_history_is_capped():
    sess = CallSession(id="s", caller="x")
    for i in range(200):
        sess.note_risk(i % 100)
    assert len(sess.risk_history) <= 20


# ---- Live feed carries intent ----


def test_live_feed_carries_intent_fields():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    sid = c.post("/calls/start", json={"caller": "audit-intent"}).json()["session_id"]
    try:
        # Deliberately mild: a CRITICAL turn correctly ends the session, and an
        # ended session is supposed to be absent from the live feed.
        c.post(f"/calls/{sid}/transcript", json={
            "text": "Hello, main bank se bol raha hoon.",
            "speaker": "caller",
        })
        row = next(x for x in c.get("/calls/live").json()["live"]
                   if x["session_id"] == sid)
        assert "objective" in row and "claimed_org" in row and "escalating" in row
        assert row["claimed_org"]
    finally:
        c.post(f"/calls/{sid}/end", json={})


# ---- Guards + version feed ----


def test_rate_limit_table_prunes_idle_clients():
    from app import guards
    saved = dict(guards._hits)
    try:
        guards._hits.clear()
        old = time.monotonic() - (guards.WINDOW_SECONDS * 10)
        for i in range(100):
            guards._hits[f"10.0.0.{i}"].append(old)
        guards._prune(time.monotonic())
        assert len(guards._hits) == 0
    finally:
        guards._hits.clear()
        guards._hits.update(saved)


def test_recent_clients_survive_pruning():
    from app import guards
    saved = dict(guards._hits)
    try:
        guards._hits.clear()
        now = time.monotonic()
        guards._hits["10.1.1.1"].append(now)
        guards._prune(now)
        assert "10.1.1.1" in guards._hits
    finally:
        guards._hits.clear()
        guards._hits.update(saved)


def test_updates_cache_follows_the_file(tmp_path):
    """The feed used to be cached forever, so a release that landed without a
    process restart never reached the in-app updater."""
    from app.api import appcast
    p = tmp_path / "updates.json"
    p.write_text('[{"version_code": 1}]', encoding="utf-8")
    first = appcast._read_updates(str(p), 1.0, 21)
    assert first == [{"version_code": 1}]
    p.write_text('[{"version_code": 1}, {"version_code": 2}]', encoding="utf-8")
    second = appcast._read_updates(str(p), 2.0, 44)
    assert len(second) == 2
    # Same key → served from cache (no re-read).
    assert appcast._read_updates(str(p), 2.0, 44) == second


def test_scrub_secrets_keeps_phone_numbers():
    """The report identifies the caller; the scrubber must not eat the number."""
    assert "9876543210" in scrub_secrets("called from 9876543210")
    assert "4821" not in scrub_secrets("otp 4821")
