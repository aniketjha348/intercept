"""App config — env-driven, safe defaults for hackathon demo."""
from __future__ import annotations

import os

try:
    from dotenv import load_dotenv
    load_dotenv()  # local .env (gitignored) — env vars always win
except ImportError:
    pass


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default)


LLM_PROVIDER = _env("LLM_PROVIDER", "auto").lower()  # auto | openai | gemini | off
OPENAI_API_KEY = _env("OPENAI_API_KEY")
GOOGLE_API_KEY = _env("GOOGLE_API_KEY")
LLM_MODEL = _env("LLM_MODEL")
# Guardian voice: Gemini TTS model chain + per-language voices (empty = code
# defaults; voices are 30 prebuilt names like Sulafat/Kore — hear them in AI Studio).
# NOTE: Gemini 2.5 shuts down Oct 2026 → then pin TTS_MODEL to a 3.x TTS model.
TTS_MODEL = _env("TTS_MODEL")
TTS_VOICE_HI = _env("TTS_VOICE_HI")
TTS_VOICE_EN = _env("TTS_VOICE_EN")
# Realtime voice bridge model (probed: gemini-3.1-flash-live-preview).
LIVE_MODEL = _env("LIVE_MODEL")
GEMINI_LIVE_URL = _env("GEMINI_LIVE_URL")
# WhatsApp bot number (forward-to-verify). Empty = endpoints report OFF.
WHATSAPP_TOKEN = _env("WHATSAPP_TOKEN")
WHATSAPP_PHONE_ID = _env("WHATSAPP_PHONE_ID")
WHATSAPP_VERIFY = _env("WHATSAPP_VERIFY", "intercept-verify")
ALLOW_NETWORK_FETCH = _env("ALLOW_NETWORK_FETCH", "false").lower() in ("1", "true", "yes")
DATABASE_URL = _env("DATABASE_URL", "sqlite:///./intercept.db")
APP_NAME = "INTERCEPT"

# Distribution feed (/app/latest): point at the GitHub Release APK.
APK_URL = _env("APK_URL", "")
