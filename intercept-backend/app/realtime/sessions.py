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
    memory: CallMemory = field(default_factory=CallMemory)
    scam_memory: ScamMemory = field(default_factory=ScamMemory)
    transcript: list[dict] = field(default_factory=list)
    last_result: AnalysisResult | None = None
    human_mode: bool = False
    active: bool = True


class SessionManager:
    def __init__(self) -> None:
        self.calls: dict[str, CallSession] = {}

    def create(self, call_id: str, caller: str, language: str = "auto") -> CallSession:
        sess = CallSession(id=call_id, caller=caller, language=language)
        self.calls[call_id] = sess
        return sess

    def get(self, call_id: str) -> CallSession | None:
        return self.calls.get(call_id)


MANAGER = SessionManager()
