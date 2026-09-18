"""Post-incident intelligence (§19): report + fingerprint, no sensitive secrets."""
from __future__ import annotations

from app.ai.memory import CallMemory
from app.schemas import AnalysisResult


def _friendly_summary(caller: str, level: str, objective: str,
                      tactics: list, protected: list, lang: str,
                      turns: list[dict]) -> str:
    """Plain-words call summary for humans (Equal-AI-style). LLM when possible,
    honest template otherwise — never raises."""
    try:
        from app.ai import providers
        convo = "\n".join(f"{t.get('speaker')}: {t.get('text')}"
                          for t in (turns or [])[-12:])
        out = providers.complete_text(
            "You write 2-short-sentence call summaries for non-technical users. "
            "Plain words, no jargon.",
            f"Summarize this screened call in language '{lang}': caller {caller}, "
            f"verdict {level}, objective {objective}. "
            f"Say what happened and what to do next.\n{convo}",
            max_tokens=120)
        if out and len(out.strip()) > 10:
            return out.strip()
    except Exception:
        pass
    verdict = {"CRITICAL": "This was a scam attempt. ",
               "HIGH": "This looked quite suspicious. ",
               "SUSPICIOUS": "This had some warning signs. "}.get(level, "This looked safe. ")
    keep = ", ".join(protected) if protected else "nothing"
    return (verdict + f"Caller claimed to be {caller or 'unknown'}" +
            (f" ({objective})" if objective and objective != "Unknown" else "") +
            f". You shared: {keep}.")


def generate(call_id: str, caller: str, memory: CallMemory,
             final: AnalysisResult | None, transcript: list[dict]) -> dict:
    tactics = list(memory.risk_signals)
    protected = [r for r in memory.requested]
    action = "Call terminated by INTERCEPT" if (final and final.policy.must_terminate) \
        else "User ended call" if final else "Session closed"
    lang = final.language if final else "en"
    level = final.risk_level if final else "UNKNOWN"
    objective = final.likely_objective if final else "Unknown"
    return {
        "call_id": call_id,
        "caller": caller,
        "language": lang,
        "risk": final.risk_score if final else memory.current_risk,
        "level": level,
        "claimed_org": memory.claimed_org,
        "tactics": tactics,
        "protected": protected or ["nothing shared"],
        "action": action,
        "why": final.explanation if final else [],
        "likely_objective": objective,
        "summary": _friendly_summary(caller, level, objective, tactics,
                                     protected or ["nothing shared"], lang,
                                     transcript),
        "turns": memory.turns,
        "transcript": transcript[-50:],
    }
