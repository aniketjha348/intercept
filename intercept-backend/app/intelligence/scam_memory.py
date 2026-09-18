"""Personal Scam Memory / Scam DNA (§17) + RAG knowledge hook.

MVP: in-memory tactic fingerprints with Jaccard similarity (no PII stored).
Later: swap `_similarity` for embeddings + vector store; keep the same API.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from app.i18n import KNOWLEDGE_BASE_HI

KNOWLEDGE_BASE = [
    "Banks never ask for OTP, CVV, PIN or passwords on calls or links.",
    "RBI/TRAI/police never demand payment over call to stop an arrest.",
    "KYC updates happen inside official apps, never via APK or remote-access apps.",
    "UPI QR scan = PAYING money, never receiving. Verify payee handle first.",
    "Refund/prize requiring an advance fee or OTP is an advance-fee scam.",
]

@dataclass
class Fingerprint:
    tactics: set[str]
    objective: str
    risk: int


@dataclass
class ScamMemory:
    """Per-user attack-pattern store (tactics only, never secrets)."""
    prints: list[Fingerprint] = field(default_factory=list)

    def store(self, tactics: set[str], objective: str, risk: int) -> None:
        if risk >= 50 and tactics:
            self.prints.append(Fingerprint(set(tactics), objective, risk))
            self.prints = self.prints[-50:]

    def find_similar(self, tactics: set[str]) -> Fingerprint | None:
        best: Fingerprint | None = None
        best_score = 0.0
        for fp in self.prints:
            if not fp.tactics or not tactics:
                continue
            inter = len(fp.tactics & tactics)
            union = len(fp.tactics | tactics)
            score = inter / union if union else 0.0
            if score > best_score:
                best_score, best = score, fp
        return best if best_score >= 0.5 else None


_USERS: dict[str, ScamMemory] = {}


def for_user(user_id: str | None) -> ScamMemory:
    """Per-user memory keyed by the app's X-User-Id header. Unknown/blank ids
    share one anonymous bucket (never crash, never mix into real users)."""
    key = (user_id or "").strip() or "anonymous"
    mem = _USERS.get(key)
    if mem is None:
        mem = ScamMemory()
        _USERS[key] = mem
        if len(_USERS) > 10000:  # bounded: drop the oldest bucket
            _USERS.pop(next(iter(_USERS)))
    return mem


def retrieve_context(text: str, limit: int = 2, language: str = "en") -> list[str]:
    """Tiny keyword RAG over official guidance (EN + HI). Replace with vector RAG later."""
    from app.i18n import detect_language
    lang = language if language in ("hi", "hinglish") else detect_language(text)
    base = KNOWLEDGE_BASE_HI if lang in ("hi", "hinglish") else KNOWLEDGE_BASE
    t = text.lower()
    hits = [k for k in base
            if any(w in t for w in k.lower().split()[:8])]
    return (hits or base[:1])[:limit]
