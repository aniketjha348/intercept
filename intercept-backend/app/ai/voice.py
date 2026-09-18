"""Guardian voice (§12): human-like speech via Gemini TTS, device TTS fallback.

Research (Sep 2026): Gemini 2.5/3.x TTS is GA with 30 steerable voices —
style/accent/pace via natural-language prompt, single-speaker REST, PCM 24kHz
out. We wrap PCM→WAV in-code (no ffmpeg/mobile deps) and cache repeats
(greetings hit cache → instant + free). Live voice-to-voice API stays P2
(heavier session per call); per-turn TTS + local barge-in gives ~80% of the
human feel: the caller hears a warm voice that STOPS when they start talking.

NOTE (Oct 2026): Gemini 2.5 family shuts down — model chain below tries 3.x
automatically; pin TTS_MODEL once you verify a voice on your key.
"""
from __future__ import annotations

import base64
import struct

import httpx

from app import core_config as cfg

# Tried in order; first success sticks for the process lifetime.
# Probed 2026-09-18 on our free key: lite-preview-tts + GA 2.5-tts = NOT FOUND,
# preview-tts + 3.1-flash-tts-preview = OK. Chain keeps voice alive.
_MODEL_CHAIN = [
    cfg.TTS_MODEL or "gemini-2.5-flash-preview-tts",
    "gemini-3.1-flash-tts-preview",
]
_WORKING: list[str] = []

# Warm family-member Hindi/Hinglish voice vs neutral English voice.
_VOICES = {
    "hi": cfg.TTS_VOICE_HI or "Sulafat",
    "hinglish": cfg.TTS_VOICE_HI or "Sulafat",
    "en": cfg.TTS_VOICE_EN or "Kore",
}

_STYLE = {
    "hi": "Say in a calm, warm, natural Hindi voice, like a real family member on a phone call — unhurried, human, never robotic: ",
    "hinglish": "Say in a calm, warm, natural Hinglish voice, like a real person on a phone call — unhurried, human, never robotic: ",
    "en": "Say in a calm, warm, natural voice, like a real person on a phone call — unhurried, human, never robotic: ",
}

_CACHE: dict[tuple[str, str, str], str] = {}
_CACHE_CAP = 50


def _wav(pcm: bytes, rate: int = 24000) -> bytes:
    n = len(pcm)
    head = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + n, b"WAVE",
                       b"fmt ", 16, 1, 1, rate, rate * 2, 2, 16, b"data", n)
    return head + pcm


def _synthesize(text: str, lang: str) -> bytes | None:
    """Raw WAV bytes or None. Pure HTTPS, same pattern as providers.py."""
    if not cfg.GOOGLE_API_KEY or not text.strip():
        return None
    lang = lang if lang in _VOICES else "en"
    voice = _VOICES[lang]
    prompt = _STYLE[lang] + text.strip()[:600]
    models = list(_WORKING) + [m for m in _MODEL_CHAIN if m not in _WORKING]
    for model in models:
        try:
            url = (f"https://generativelanguage.googleapis.com/v1beta/models/{model}"
                   f":generateContent?key={cfg.GOOGLE_API_KEY}")
            body = {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "temperature": 0.7,
                    "responseModalities": ["AUDIO"],
                    "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}},
                },
            }
            r = httpx.post(url, json=body, timeout=30)
            if r.status_code != 200:
                continue
            for part in r.json()["candidates"][0]["content"]["parts"]:
                data = (part.get("inlineData") or {}).get("data")
                if data:
                    if model not in _WORKING:
                        _WORKING.append(model)
                    return base64.b64decode(data)
        except Exception:
            continue
    return None


def speak_cached(text: str, lang: str = "en") -> tuple[str | None, bool]:
    """(base64-wav or None, from-cache). Never raises; None = use device TTS."""
    try:
        lang = lang if lang in _VOICES else "en"
        key = (text.strip(), lang, _VOICES[lang])
        if key in _CACHE:
            return _CACHE[key], True
        pcm = _synthesize(text, lang)
        if pcm is None:
            return None, False
        out = base64.b64encode(_wav(pcm)).decode()
        if len(_CACHE) >= _CACHE_CAP:
            _CACHE.pop(next(iter(_CACHE)))
        _CACHE[key] = out
        return out, False
    except Exception:
        return None, False
