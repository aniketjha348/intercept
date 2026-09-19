"""Assistant forwarding endpoint: the number + USSD codes the app dials.

The forwarding target is config, so the endpoint is driven from
cfg.FORWARD_NUMBER and must degrade to `configured: false` (not a crash, not a
blank number) when it is unset.
"""
from fastapi.testclient import TestClient

from app import core_config as cfg
from app.main import app


def test_forwarding_reports_not_configured_when_unset():
    c = TestClient(app)
    old = cfg.FORWARD_NUMBER
    cfg.FORWARD_NUMBER = ""
    try:
        d = c.get("/assistant/forwarding").json()
        assert d["configured"] is False
        assert d["number"] == ""
        assert d["busy_activate"] == ""
    finally:
        cfg.FORWARD_NUMBER = old


def test_forwarding_returns_number_and_gsm_codes():
    c = TestClient(app)
    old = cfg.FORWARD_NUMBER
    cfg.FORWARD_NUMBER = "+919000000000"
    try:
        d = c.get("/assistant/forwarding").json()
        assert d["configured"] is True
        assert d["number"] == "+919000000000"
        # `**` matters: GSM registers a forward with `**<code>*<number>#`. The
        # single-star form is not an MMI code, so the dialer swallows it and
        # forwarding silently stays off while the app claims it is on.
        assert d["busy_activate"] == "**67*+919000000000#"
        assert d["busy_deactivate"] == "##67#"
        assert d["noanswer_activate"] == "**61*+919000000000#"
        assert d["noanswer_deactivate"] == "##61#"
    finally:
        cfg.FORWARD_NUMBER = old


def test_forwarding_trims_whitespace_in_number():
    c = TestClient(app)
    old = cfg.FORWARD_NUMBER
    cfg.FORWARD_NUMBER = "  +911234567890  "
    try:
        d = c.get("/assistant/forwarding").json()
        assert d["number"] == "+911234567890"
    finally:
        cfg.FORWARD_NUMBER = old


def test_per_user_number_overrides_shared_default():
    c = TestClient(app)
    old = cfg.FORWARD_NUMBER
    cfg.FORWARD_NUMBER = "+911111111111"
    try:
        c.post("/assistant/forwarding", json={"number": "+912222222222"},
               headers={"X-User-Id": "u_peruser"})
        mine = c.get("/assistant/forwarding",
                     headers={"X-User-Id": "u_peruser"}).json()
        assert mine["number"] == "+912222222222"
        assert mine["source"] == "user"
        assert mine["busy_activate"] == "**67*+912222222222#"
        # A different owner still gets the shared default.
        theirs = c.get("/assistant/forwarding",
                       headers={"X-User-Id": "u_someone_else"}).json()
        assert theirs["number"] == "+911111111111"
        assert theirs["source"] == "default"
    finally:
        cfg.FORWARD_NUMBER = old


def test_reverse_lookup_maps_did_to_owner():
    c = TestClient(app)
    c.post("/assistant/forwarding", json={"number": "+913333333333"},
           headers={"X-User-Id": "u_reverse"})
    d = c.get("/assistant/forwarding/owner",
              params={"number": "+913333333333"}).json()
    assert d["user_id"] == "u_reverse"
    unknown = c.get("/assistant/forwarding/owner",
                    params={"number": "+919999999998"}).json()
    assert unknown["user_id"] == ""


def test_set_forwarding_rejects_bad_number_and_missing_user():
    c = TestClient(app)
    assert c.post("/assistant/forwarding",
                  json={"number": "+913333333333"}).status_code == 422
    assert c.post("/assistant/forwarding", json={"number": "not-a-number"},
                  headers={"X-User-Id": "u_bad"}).status_code == 422


def test_inbound_binds_session_to_owner_from_dialled_did():
    c = TestClient(app)
    c.post("/assistant/forwarding", json={"number": "+914444444444"},
           headers={"X-User-Id": "u_inbound"})
    d = c.post("/calls/inbound",
               json={"caller": "sip-dialed-1",
                     "dialed": "+914444444444"}).json()
    assert d["owner_id"] == "u_inbound"
    # A call with no dialled DID is nobody's in particular — empty, not guessed.
    plain = c.post("/calls/inbound", json={"caller": "sip-dialed-2"}).json()
    assert plain["owner_id"] == ""
