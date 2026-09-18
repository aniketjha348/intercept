"""Risk scorer: capped additive weights + severity overrides (§9–§10)."""
from __future__ import annotations

from app.schemas import Signal

LEVELS = [(24, "LOW"), (49, "SUSPICIOUS"), (74, "HIGH"), (100, "CRITICAL")]


def level_for(score: int) -> str:
    for cap, name in LEVELS:
        if score <= cap:
            return name
    return "CRITICAL"


def _has(signals: list[Signal], *codes: str) -> Signal | None:
    for s in signals:
        if s.code in codes:
            return s
    return None


def _authority_present(signals: list[Signal]) -> Signal | None:
    return _has(signals, "AUTHORITY_BANK", "AUTHORITY_POLICE", "AUTHORITY_GOVT",
                "AUTHORITY_SUPPORT", "AUTHORITY_EMPLOYER")


def score_signals(signals: list[Signal]) -> tuple[int, str, list[str]]:
    """Return (score, level, reasons). Deterministic; LLM never overrides this."""
    codes = {s.code for s in signals}
    # Capped additive base: each distinct code once, capped per category.
    cat_totals: dict[str, int] = {}
    for s in signals:
        cat_totals[s.category] = cat_totals.get(s.category, 0) + s.weight
    caps = {"AUTHORITY": 20, "PRESSURE": 30, "INCENTIVE": 30,
            "ACTION": 100, "URL": 30, "PAYMENT": 35, "THREAT_INTEL": 20}
    score = sum(min(total, caps.get(cat, 30)) for cat, total in cat_totals.items())
    score = min(100, score)

    reasons = [f"{s.code} (+{s.weight})" for s in signals]
    auth = _authority_present(signals)

    # Severity overrides (§9): sensitive request inside impersonation → CRITICAL.
    if _has(signals, "OTP_REQUEST") and auth:
        score = max(score, 90)
        reasons.append("OVERRIDE: OTP request during impersonation → CRITICAL floor 90")
    elif _has(signals, "OTP_REQUEST"):
        score = max(score, 75)
        reasons.append("OVERRIDE: OTP request → CRITICAL floor 75")
    if _has(signals, "REMOTE_ACCESS") and auth:
        score = max(score, 80)
        reasons.append("OVERRIDE: remote-access under impersonation → floor 80")
    if _has(signals, "CREDENTIAL_REQUEST") and auth:
        score = max(score, 78)
        reasons.append("OVERRIDE: credential request under impersonation → floor 78")
    if _has(signals, "SUSPICIOUS_URL", "PHISHING_URL") and _has(
            signals, "CREDENTIAL_REQUEST", "PAYMENT_REQUEST", "OTP_REQUEST"):
        score = max(score, 78)
        reasons.append("OVERRIDE: suspicious URL + sensitive request → floor 78")
    if _has(signals, "INCENTIVE_PRIZE") and _has(
            signals, "LINK_CLICK", "SUSPICIOUS_URL", "PHISHING_URL"):
        score = max(score, 55)
        reasons.append("OVERRIDE: prize lure + link → floor 55")

    void = not codes
    if void:
        reasons = ["no risk signals"]
    return score, level_for(score), reasons
