"""Call conversation memory (§13): short-term, cross-turn reasoning."""
from __future__ import annotations

from dataclasses import dataclass, field

from app.schemas import Signal


@dataclass
class CallMemory:
    claimed_identity: str = "Unknown"
    claimed_org: str = "Unknown"
    purpose: str = "Unknown"
    requested: list[str] = field(default_factory=list)
    risk_signals: list[str] = field(default_factory=list)
    turns: int = 0
    current_risk: int = 0
    # Who we protect (session-only, never persisted — see privacy promise).
    owner: str = ""

    def update(self, caller_text: str, signals: list[Signal], risk: int) -> None:
        import re
        self.turns += 1
        self.current_risk = risk
        t = caller_text.lower()
        m = re.search(r"(bank|police|trai|rbi|customs|cbi|support|kyc|delivery|hr)[\w ]{0,30}", t)
        if m and self.claimed_org == "Unknown":
            self.claimed_org = m.group(0).strip()[:60]
        for s in signals:
            if s.code not in self.risk_signals:
                self.risk_signals.append(s.code)
            if s.category in ("ACTION", "PAYMENT") and s.code not in self.requested:
                self.requested.append(s.code)

    def summary(self) -> str:
        base = (f"org={self.claimed_org} purpose={self.purpose} "
                f"requested={self.requested} risk={self.current_risk} turns={self.turns}")
        return base + (f" owner={self.owner}" if self.owner else "")
