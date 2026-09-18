"""Threat-intel layer (§18). Optional enrichment; core works without it."""
from __future__ import annotations

from app.schemas import Signal


def lookup(identifier: str, kind: str = "generic") -> list[Signal]:
    """Stub hook for phone/domain/IP reputation feeds.

    Returns [] offline. Plug real providers here and emit origin="threat_intel"
    signals with category "THREAT_INTEL". Never let failures affect the pipeline.
    """
    _ = (identifier, kind)
    return []
