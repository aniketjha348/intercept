"""Social-engineering merge: rules + LLM + URL intel → one signal set (§7)."""
from __future__ import annotations

from app.detection import classifiers
from app.detection.rules import extract_rule_signals
from app.schemas import Signal


def analyze_text(text: str, url_signals: list[Signal] | None = None,
                 use_llm: bool = True) -> list[Signal]:
    merged: dict[str, Signal] = {}
    for sig in extract_rule_signals(text):
        merged[sig.code] = sig
    if use_llm:
        for sig in classifiers.llm_extract(text):
            prev = merged.get(sig.code)
            if prev is None or sig.confidence > prev.confidence:
                merged[sig.code] = sig
    for sig in url_signals or []:
        prev = merged.get(sig.code)
        if prev is None or sig.confidence > prev.confidence:
            merged[sig.code] = sig
    # If a bare URL/QR was supplied without link-click wording, still record the action.
    return list(merged.values())
