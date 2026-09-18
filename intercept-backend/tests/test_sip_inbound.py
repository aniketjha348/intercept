"""SIP path: an inbound call must get a session the report can attach to.

The voice agent asks POST /calls/inbound on entry, because a forwarded call has
no Android app in front of it to call /start. Distinct caller strings per test
keep them isolated inside the process-wide session manager.
"""
from fastapi.testclient import TestClient

from app.main import app


def test_inbound_creates_session_when_none_exists():
    c = TestClient(app)
    r = c.post("/calls/inbound",
               json={"caller": "sip-create-1", "owner_name": "Ramesh"})
    assert r.status_code == 200
    d = r.json()
    assert d["reused"] is False
    assert d["session_id"]
    assert d["caller"] == "sip-create-1"
    assert d["owner_name"] == "Ramesh"


def test_inbound_reuses_app_session_by_id():
    c = TestClient(app)
    sid = c.post("/calls/start",
                 json={"caller": "sip-byid-1"}).json()["session_id"]
    d = c.post("/calls/inbound",
               json={"caller": "sip-byid-1", "session_id": sid}).json()
    assert d["session_id"] == sid
    assert d["reused"] is True


def test_inbound_matches_active_session_by_caller():
    c = TestClient(app)
    sid = c.post("/calls/start",
                 json={"caller": "sip-bycaller-1"}).json()["session_id"]
    d = c.post("/calls/inbound", json={"caller": "sip-bycaller-1"}).json()
    assert d["session_id"] == sid
    assert d["reused"] is True


def test_inbound_ignores_ended_session():
    c = TestClient(app)
    sid = c.post("/calls/start",
                 json={"caller": "sip-ended-1"}).json()["session_id"]
    c.post(f"/calls/{sid}/end", json={})
    d = c.post("/calls/inbound",
               json={"caller": "sip-ended-1", "session_id": sid}).json()
    assert d["session_id"] != sid
    assert d["reused"] is False


def test_inbound_stores_the_room_the_agent_landed_in():
    from app.realtime.sessions import MANAGER
    c = TestClient(app)
    d = c.post("/calls/inbound",
               json={"caller": "sip-room-1", "room": "intercept-9876543210"}).json()
    # A SIP rule names the room after the caller, so the app cannot derive it.
    assert d["room"] == "intercept-9876543210"
    assert MANAGER.get(d["session_id"]).room == "intercept-9876543210"


def test_inbound_session_accepts_turns_and_shows_live():
    c = TestClient(app)
    sid = c.post("/calls/inbound",
                 json={"caller": "sip-turns-1"}).json()["session_id"]
    # Benign turn on purpose: an OTP line correctly terminates the session
    # (must_terminate), which would drop it from the live feed.
    t = c.post(f"/calls/{sid}/transcript",
               json={"text": "Hello, I am calling about my delivery",
                     "speaker": "caller"})
    assert t.status_code == 200
    assert "risk" in t.json()
    live = c.get("/calls/live").json()["live"]
    assert any(x["session_id"] == sid for x in live)
