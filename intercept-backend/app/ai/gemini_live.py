"""Gemini Live API hook (P1) — bidirectional streaming for real-time voice.

The REST provider in providers.py already covers text turns (free tier).
When ready for streaming caller audio <-> guardian voice:

  1. Open wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent
  2. Send setup { model, generation_config { response_modalities: [AUDIO] },
     system_instruction: <prompts.GUARDIAN_SYSTEM> }
  3. Stream client PCM chunks (16kHz) in realtime_input.audio,
     receive server audio + text, route text through pipeline.analyze()
     per partial transcript so risk/policy keep driving the conversation.
  4. Keep the Risk Engine as the decision maker — Live API is transport,
     never the judge (same rule as the REST path).

Kept as a documented hook so the MVP stays shippable; wire it here later.
"""
from __future__ import annotations

LIVE_WSS = ("wss://generativelanguage.googleapis.com/ws/"
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")


def is_configured() -> bool:
    from app import core_config as cfg
    return bool(cfg.GOOGLE_API_KEY) and cfg.LLM_PROVIDER != "off"


def build_setup(system_instruction: str, voice: str = "Puck") -> dict:
    return {
        "setup": {
            "model": "models/gemini-2.0-flash-exp",
            "generation_config": {"response_modalities": ["AUDIO"]},
            "system_instruction": {"parts": [{"text": system_instruction}]},
            "speech_config": {"voice_config": {"prebuilt_voice_config": {"voice_name": voice}}},
        }
    }
