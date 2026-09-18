"""Audio stub (§4): MVP takes caller transcript text (client-side STT).

Structure is ready for streaming STT: replace `to_text` with a streamer that
yields partial transcripts + speaker labels into the same pipeline.
"""
from __future__ import annotations


def to_text(audio_ref: str | None, transcript_hint: str | None = None) -> str:
    return transcript_hint or ""
