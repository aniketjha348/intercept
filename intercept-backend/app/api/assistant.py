"""Assistant setup: how a phone points its calls at the cloud AI.

Equal-AI-style activation. A normal Android app cannot inject its voice into a
live cellular call (the call audio path is closed to store apps), so the AI
only reaches the caller one way: the app DECLINES the call and the carrier
forwards it to our number, where the voice agent answers as the other party.

Each owner can have their **own** DID — that is what identifies whose assistant
is answering when the call lands, so the mapping lives here (user → number) and
the reverse is used on the inbound leg.
"""
from __future__ import annotations

import re

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app import core_config as cfg
from app.db.repository import REPO

router = APIRouter(prefix="/assistant", tags=["assistant"])

# E.164-ish: optional +, 7–15 digits. Deliberately strict — this value is dialled
# by a user's own phone, and a malformed one becomes a paid USSD session.
_NUMBER_RE = re.compile(r"^\+?[0-9]{7,15}$")


class ForwardNumber(BaseModel):
    number: str


def _codes(number: str) -> dict:
    """Standard GSM USSD codes the Indian carriers (Airtel/Jio/Vi) accept:
    *67 = forward when busy (a declined call reads as busy — our path),
    *61 = forward when not answered; ##xx# clears each."""
    return {
        "busy_activate": f"*67*{number}#",
        "busy_deactivate": "##67#",
        "noanswer_activate": f"*61*{number}#",
        "noanswer_deactivate": "##61#",
    }


@router.get("/forwarding")
def forwarding(x_user_id: str | None = Header(default=None)):
    """The number THIS owner's calls are forwarded to.

    Resolution order: the owner's own DID (bound via POST), else the shared
    default from ASSISTANT_FORWARD_NUMBER, else not configured. `source` says
    which one it was, so the app can tell a dedicated line from the fallback.
    """
    own = REPO.get_forward_number(x_user_id or "")
    number = (own or cfg.FORWARD_NUMBER or "").strip()
    if not number:
        return {
            "configured": False, "number": "", "source": "unset",
            "busy_activate": "", "busy_deactivate": "##67#",
            "noanswer_activate": "", "noanswer_deactivate": "##61#",
            "reason": "no forwarding number for this owner",
        }
    return {"configured": True, "number": number,
            "source": "user" if own else "default", **_codes(number)}


@router.post("/forwarding")
def set_forwarding(body: ForwardNumber,
                   x_user_id: str | None = Header(default=None)):
    """Bind an owner to their DID. Provisioning/support calls this; the app reads."""
    uid = (x_user_id or "").strip()
    if not uid:
        raise HTTPException(422, "X-User-Id header required")
    number = (body.number or "").strip()
    if not _NUMBER_RE.match(number):
        raise HTTPException(422, "number must look like +919000000000")
    REPO.set_forward_number(uid, number)
    return {"status": "bound", "user_id": uid, "number": number, **_codes(number)}


@router.get("/forwarding/owner")
def forwarding_owner(number: str):
    """Reverse lookup: which owner does this DID belong to. The voice agent uses
    this when a forwarded call arrives, so the session lands on the right person."""
    uid = REPO.user_for_forward_number(number)
    return {"number": number.strip(), "user_id": uid or ""}
