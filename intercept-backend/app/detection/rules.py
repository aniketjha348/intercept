"""Deterministic rules — the risk engine never depends solely on the LLM (§9)."""
from __future__ import annotations

from app.detection import signals as tax
from app.schemas import Signal


def extract_rule_signals(text: str) -> list[Signal]:
    text = text or ""
    out: list[Signal] = []
    for code, snippet in tax.match_text(text):
        category, weight, _ = tax.TAXONOMY[code]
        conf = 0.9 if category in ("ACTION",) else 0.82
        # Authority claims get slightly lower confidence from a single keyword hit.
        if category == "AUTHORITY":
            conf = 0.78
        out.append(Signal(code=code, category=category, confidence=conf,
                          evidence=f"Matched {snippet!r}", weight=weight, origin="rules"))
    return out
