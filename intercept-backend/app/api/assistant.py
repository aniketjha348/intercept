"""Assistant setup: how a phone points its calls at the cloud AI.

Equal-AI-style activation. A normal Android app cannot inject its voice into a
live cellular call (the call audio path is closed to store apps), so the AI
only reaches the caller one way: the app DECLINES the call and the carrier
forwards it to our number, where the voice agent answers as the other party.

That forwarding is a carrier setting, so this endpoint hands the app the number
to forward to and the exact USSD codes to set and clear it. No secrets here —
the number is dialled by the user's own phone, billed to the user.
"""
from __future__ import annotations

from fastapi import APIRouter

from app import core_config as cfg

router = APIRouter(prefix="/assistant", tags=["assistant"])


@router.get("/forwarding")
def forwarding():
    """The forwarding target + the USSD codes to arm and clear it.

    Codes are the standard GSM ones the Indian carriers (Airtel/Jio/Vi) use:
    *67 = forward when busy (a declined call reads as busy — our path),
    *61 = forward when not answered, *21 = forward all calls; the matching
    ##xx# clears each. Some carriers want a doubled `**xx*`; the dialer's
    business, not ours — we return the spec form.
    """
    number = (cfg.FORWARD_NUMBER or "").strip()
    if not number:
        return {
            "configured": False,
            "number": "",
            "busy_activate": "", "busy_deactivate": "##67#",
            "noanswer_activate": "", "noanswer_deactivate": "##61#",
            "reason": "ASSISTANT_FORWARD_NUMBER not set",
        }
    return {
        "configured": True,
        "number": number,
        "busy_activate": f"*67*{number}#",
        "busy_deactivate": "##67#",
        "noanswer_activate": f"*61*{number}#",
        "noanswer_deactivate": "##61#",
    }
