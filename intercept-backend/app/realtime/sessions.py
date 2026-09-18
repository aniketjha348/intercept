"""Session manager: live call state (§13) + realtime fan-out."""
from __future__ import annotations

from dataclasses import dataclass, field

from app.ai.memory import CallMemory
from app.intelligence.scam_memory import ScamMemory
from app.schemas import AnalysisResult


@dataclass
class CallSession:
    id: str
    caller: str
    language: str = "auto"
    owner_name: str = ""
    memory: CallMemory = field(default_factory=CallMemory)
    scam_memory: ScamMemory = field(default_factory=ScamMemory)
    transcript: list[dict] = field(default_factory=list)
    last_result: AnalysisResult | None = None
    human_mode: bool = False
    active: bool = True
    # Per-turn score trail (newest last). Kept small: it exists to answer one
    # question an LLM is bad at answering — is this call getting worse?
    risk_history: list[int] = field(default_factory=list)

    def note_risk(self, risk: int) -> None:
        self.risk_history.append(int(risk))
        del self.risk_history[:-20]

    @property
    def escalating(self) -> bool:
        """Social engineering escalates on purpose: authority, then urgency,
        then the ask. A caller whose risk is climbing while already suspicious
        is a stronger signal than any single turn's score."""
        h = self.risk_history
        if len(h) < 3:
            return False
        return h[-1] >= 50 and h[-1] - h[-3] >= 15


class SessionManager:
    # Sessions used to live in this dict forever — every call the app ever
    # screened stayed in memory (and /calls/live walked all of them). Reports
    # stay reachable after eviction through REPO, so ended sessions are cheap
    # to drop and live ones are never touched.
    MAX_SESSIONS = 500

    def __init__(self) -> None:
        self.calls: dict[str, CallSession] = {}

    def create(self, call_id: str, caller: str, language: str = "auto") -> CallSession:
        sess = CallSession(id=call_id, caller=caller, language=language)
        self.calls[call_id] = sess
        self._trim()
        return sess

    def get(self, call_id: str) -> CallSession | None:
        return self.calls.get(call_id)

    def _trim(self) -> None:
        """Evict oldest inactive sessions past the cap, never a live one."""
        if len(self.calls) <= self.MAX_SESSIONS:
            return
        for sid in list(self.calls):
            if len(self.calls) <= self.MAX_SESSIONS:
                return
            if not self.calls[sid].active:
                self.calls.pop(sid, None)


MANAGER = SessionManager()
