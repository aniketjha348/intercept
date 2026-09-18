"""LLM providers over plain HTTPS (no SDK needed).

Gemini: free-tier key from Google AI Studio (aistudio.google.com), set GOOGLE_API_KEY.
  generateContent REST handles guardian replies + structured signal extraction.
OpenAI: set OPENAI_API_KEY. LLM_PROVIDER=auto tries Gemini first, then OpenAI.
LLM_PROVIDER=off disables all cloud calls — rules engine carries detection.

Every function returns None on any failure; the pipeline never depends on this.
"""
from __future__ import annotations

import json

import httpx

from app import core_config as cfg

_GEMINI_MODEL = cfg.LLM_MODEL or "gemini-2.5-flash-lite"
_OPENAI_MODEL = cfg.LLM_MODEL if cfg.LLM_PROVIDER == "openai" and cfg.LLM_MODEL else "gpt-4o-mini"


def _gemini(prompt: str, max_tokens: int, temperature: float, want_json: bool) -> str | None:
    if not cfg.GOOGLE_API_KEY:
        return None
    url = (f"https://generativelanguage.googleapis.com/v1beta/models/{_GEMINI_MODEL}"
           f":generateContent?key={cfg.GOOGLE_API_KEY}")
    body: dict = {"contents": [{"parts": [{"text": prompt}]}],
                  "generationConfig": {"temperature": temperature,
                                       "maxOutputTokens": max_tokens}}
    if want_json:
        body["generationConfig"]["responseMimeType"] = "application/json"
    try:
        r = httpx.post(url, json=body, timeout=20)
        if r.status_code != 200:
            return None
        parts = r.json()["candidates"][0]["content"]["parts"]
        return "".join(p.get("text", "") for p in parts).strip() or None
    except Exception:
        return None


def _openai(system: str, prompt: str, max_tokens: int, temperature: float) -> str | None:
    if not cfg.OPENAI_API_KEY:
        return None
    try:
        r = httpx.post(
            "https://api.openai.com/v1/chat/completions",
            headers={"Authorization": f"Bearer {cfg.OPENAI_API_KEY}"},
            json={"model": _OPENAI_MODEL, "temperature": temperature,
                  "max_tokens": max_tokens,
                  "messages": [{"role": "system", "content": system},
                               {"role": "user", "content": prompt}]},
            timeout=20)
        if r.status_code != 200:
            return None
        return (r.json()["choices"][0]["message"]["content"] or "").strip() or None
    except Exception:
        return None


def complete_text(system: str, prompt: str, max_tokens: int = 160,
                 temperature: float = 0.4) -> str | None:
    """Guardian-style free-text completion. None when off/unconfigured/failing."""
    if cfg.LLM_PROVIDER == "off":
        return None
    if cfg.LLM_PROVIDER in ("auto", "gemini"):
        out = _gemini(f"{system}\n\n{prompt}", max_tokens, temperature, want_json=False)
        if out:
            return out
    if cfg.LLM_PROVIDER in ("auto", "openai"):
        out = _openai(system, prompt, max_tokens, temperature)
        if out:
            return out
    return None


def complete_json(prompt: str) -> dict | None:
    """Structured JSON completion for signal extraction. None on any failure."""
    if cfg.LLM_PROVIDER == "off":
        return None
    raw: str | None = None
    if cfg.LLM_PROVIDER in ("auto", "gemini"):
        raw = _gemini(prompt + "\nReply with JSON ONLY.", 400, 0.0, want_json=True)
    if raw is None and cfg.LLM_PROVIDER in ("auto", "openai"):
        raw = _openai("Reply with JSON ONLY. No markdown.", prompt, 400, 0.0)
    if not raw:
        return None
    try:
        return json.loads(raw[raw.index("{"): raw.rindex("}") + 1])
    except Exception:
        return None
