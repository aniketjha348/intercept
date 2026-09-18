"""Post-incident intelligence (§19): report + fingerprint, no sensitive secrets."""
from __future__ import annotations

from app.ai.memory import CallMemory
from app.schemas import AnalysisResult


def generate(call_id: str, caller: str, memory: CallMemory,
             final: AnalysisResult | None, transcript: list[dict]) -> dict:
    tactics = list(memory.risk_signals)
    protected = [r for r in memory.requested]
    action = "Call terminated by INTERCEPT" if (final and final.policy.must_terminate) \
        else "User ended call" if final else "Session closed"
    return {
        "call_id": call_id,
        "caller": caller,
        "language": final.language if final else "en",
        "risk": final.risk_score if final else memory.current_risk,
        "level": final.risk_level if final else "UNKNOWN",
        "claimed_org": memory.claimed_org,
        "tactics": tactics,
        "protected": protected or ["nothing shared"],
        "action": action,
        "why": final.explanation if final else [],
        "likely_objective": final.likely_objective if final else "Unknown",
        "turns": memory.turns,
        "transcript": transcript[-50:],
    }
