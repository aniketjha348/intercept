"""LLM abstraction: Gemini (free-tier REST) / OpenAI replies, offline fallback."""
from __future__ import annotations

from app import core_config as cfg
from app.ai import prompts, providers
from app.i18n import FALLBACK_REPLY, reply_instruction


def guardian_reply(level: str, instruction: str, caller_text: str,
                   memory_summary: str = "", language: str = "en") -> str:
    """LLM reply in the caller's language when keys exist; templated fallback otherwise."""
    lang = language if language in FALLBACK_REPLY else "en"
    system = f"{prompts.GUARDIAN_SYSTEM}\n{reply_instruction(lang)}"
    prompt = (f"Risk: {level}. Instruction: {instruction}\n"
              f"Memory: {memory_summary}\nCaller: {caller_text[:1500]}")
    try:
        out = providers.complete_text(system, prompt)
        if out:
            return out
    except Exception:
        pass
    return FALLBACK_REPLY[lang].get(level, FALLBACK_REPLY[lang]["LOW"])


def provider_status() -> dict:
    return {"provider": cfg.LLM_PROVIDER,
            "gemini_key": bool(cfg.GOOGLE_API_KEY),
            "openai_key": bool(cfg.OPENAI_API_KEY)}
