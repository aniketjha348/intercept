"""Policy engine — separate from risk (§11). Risk decides severity; policy decides action."""
from __future__ import annotations

from app.i18n import policy_texts
from app.schemas import PolicyDecision, Signal

_SENSITIVE_CODES = ("OTP_REQUEST", "CREDENTIAL_REQUEST", "PAYMENT_REQUEST", "REMOTE_ACCESS")


def decide(level: str, signals: list[Signal], score: int, language: str = "en") -> PolicyDecision:
    codes = [s.code for s in signals]
    sensitive = next((c for c in codes if c in _SENSITIVE_CODES), None)
    user_msg, simple_msg, instruction = policy_texts(level, language, score, sensitive)
    return PolicyDecision(
        action={"LOW": "CONTINUE", "SUSPICIOUS": "WARN", "HIGH": "WARN"}.get(level, "PROTECT"),
        level=level,
        user_message=user_msg,
        simple_mode_message=simple_msg,
        guardian_instruction=instruction,
        must_terminate=(level == "CRITICAL"),
        offer_takeover=(level != "LOW"),
    )
