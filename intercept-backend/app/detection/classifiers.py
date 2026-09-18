"""LLM supporting classifier — optional, structured, never the center."""
from __future__ import annotations

from app.ai import providers
from app.detection import signals as tax
from app.schemas import Signal

_VALID = set(tax.TAXONOMY)

_PROMPT_HEAD = ("""You are INTERCEPT's supporting signal extractor. Reply with JSON ONLY:
{"signals": [{"code": "<TAXONOMY_CODE>", "confidence": 0.0-1.0, "evidence": "<short quote>"}]}
Valid codes: %s
Text: """ % ", ".join(sorted(_VALID)))


def llm_extract(text: str) -> list[Signal]:
    """Best-effort LLM extraction. Returns [] when provider off/keys missing/failure."""
    if not (text or "").strip():
        return []
    data = providers.complete_json(_PROMPT_HEAD + text[:3000])
    if not data:
        return []
    try:
        out: list[Signal] = []
        for item in data.get("signals", [])[:12]:
            code = str(item.get("code", "")).upper()
            if code not in _VALID:
                continue
            category, weight, _ = tax.TAXONOMY[code]
            out.append(Signal(code=code, category=category,
                              confidence=float(item.get("confidence", 0.7)),
                              evidence=str(item.get("evidence", ""))[:200],
                              weight=weight, origin="llm"))
        return out
    except Exception:
        return []
