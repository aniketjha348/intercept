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
        assert d["busy_activate"] == "*67*+919000000000#"
        assert d["busy_deactivate"] == "##67#"
        assert d["noanswer_activate"] == "*61*+919000000000#"
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
