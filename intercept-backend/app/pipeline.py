"""Unified Security Intelligence Pipeline (§1): every channel → one engine."""
from __future__ import annotations

from app.ai import llm
from app.ai.agent import run_guardian_turn
from app.ai.memory import CallMemory
from app.detection.signals import extract_urls
from app.detection.social_engineering import analyze_text
from app.i18n import explain as explain_code
from app.i18n import resolve_language
from app.intelligence import scam_memory, threat_intel
from app.intelligence.url_intel import analyze_url
from app.multimodal import audio, ocr
from app.multimodal.qr import analyze_qr
from app.risk import policy, scorer
from app.schemas import (AnalysisResult, AttackStage, Channel, InterceptInput,
                         SecurityEvent, Signal)

# Canonical attack order (§8)
_CHAIN_ORDER = ["AUTHORITY", "TRUST", "URGENCY", "FEAR", "REQUEST", "ATTEMPT"]
_CODE_TO_STAGE = {
    "AUTHORITY_BANK": "AUTHORITY", "AUTHORITY_POLICE": "AUTHORITY",
    "AUTHORITY_GOVT": "AUTHORITY", "AUTHORITY_SUPPORT": "AUTHORITY",
    "AUTHORITY_EMPLOYER": "AUTHORITY",
    "PRESSURE_URGENCY": "URGENCY", "PRESSURE_THREAT": "FEAR", "PRESSURE_FEAR": "FEAR",
    "INCENTIVE_PRIZE": "TRUST",
    "LINK_CLICK": "REQUEST", "PAYMENT_REQUEST": "REQUEST",
    "CREDENTIAL_REQUEST": "ATTEMPT", "OTP_REQUEST": "ATTEMPT",
    "REMOTE_ACCESS": "ATTEMPT", "SUSPICIOUS_URL": "REQUEST", "PHISHING_URL": "ATTEMPT",
}

_OBJECTIVES = {"OTP_REQUEST": "Credential acquisition (OTP)",
               "CREDENTIAL_REQUEST": "Credential harvesting",
               "PAYMENT_REQUEST": "Financial theft", "REMOTE_ACCESS": "Device takeover",
               "PHISHING_URL": "Credential harvesting via fake site",
               "INCENTIVE_PRIZE": "Advance-fee fraud"}


def analyze(inp: InterceptInput, memory: CallMemory | None = None,
            user_memory: scam_memory.ScamMemory | None = None,
            use_llm: bool = True, language: str | None = None) -> AnalysisResult:
    text = _resolve_text(inp)
    lang = resolve_language(language or inp.language, text)

    url_signals: list[Signal] = []
    for u in extract_urls(text):
        sigs, _ = analyze_url(u)
        url_signals.extend(sigs)
    if inp.content.url:
        sigs, _ = analyze_url(inp.content.url)
        url_signals.extend(sigs)
    if inp.channel == Channel.QR or inp.content.qr_text or inp.content.image_b64 and inp.channel == Channel.QR:
        sigs, _ = analyze_qr(inp.content.image_b64, inp.content.qr_text or text)
        url_signals.extend(sigs)
    intel = threat_intel.lookup(inp.source.identifier or "", inp.channel.value)

    signals = analyze_text(text, url_signals + intel, use_llm=use_llm)
    if inp.content.url and not any(s.code == "LINK_CLICK" for s in signals):
        signals.append(Signal(code="LINK_CLICK", category="ACTION", confidence=0.7,
                              evidence=f"Link supplied for analysis: {inp.content.url[:120]}",
                              weight=20, origin="rules"))

    score, level, _reasons = scorer.score_signals(signals)
    chain = _build_chain(signals)
    decision = policy.decide(level, signals, score, language=lang)

    if memory is not None and inp.channel in (Channel.CALL, Channel.VOICE):
        memory.update(text, signals, score)
        reply = llm.guardian_reply(level, decision.guardian_instruction, text, memory.summary(), lang)
    else:
        reply = "" if inp.channel not in (Channel.CALL, Channel.VOICE) else \
            llm.guardian_reply(level, decision.guardian_instruction, text, "", lang)

    events = [SecurityEvent(event_type=s.code, channel=inp.channel,
                            severity=_sev(s.code, level), evidence=s.evidence,
                            confidence=s.confidence) for s in signals]

    explanation = _explain(signals, lang)
    objective = _objective(signals)
    similar = None
    if user_memory is not None:
        tactics = {s.code for s in signals}
        match = user_memory.find_similar(tactics)
        if match:
            similar = (f"Similar to a previous {match.objective} pattern "
                       f"(risk was {match.risk}).")
        user_memory.store(tactics, objective, score)
        try:  # Scam DNA survives restarts via Postgres (no-op on in-memory).
            from app.db.repository import REPO
            REPO.save_fingerprint(sorted(tactics), objective, score)
        except Exception:
            pass

    return AnalysisResult(session_id=inp.session_id, channel=inp.channel,
                          risk_score=score, risk_level=level, signals=signals,
                          attack_chain=chain, events=events, policy=decision,
                          explanation=explanation, likely_objective=objective,
                          similar_pattern=similar, guardian_reply=reply, language=lang)


def _resolve_text(inp: InterceptInput) -> str:
    c = inp.content
    parts: list[str] = []
    if c.text:
        # Screenshots: OCR first, then supplied text (OCR may add branding/URL context).
        if inp.channel == Channel.SCREENSHOT and c.image_b64:
            parts.append(ocr.extract_text(c.image_b64, c.text))
        else:
            parts.append(c.text)
    elif c.image_b64:
        parts.append(ocr.extract_text(c.image_b64))
    if c.audio_ref or inp.channel in (Channel.CALL, Channel.VOICE):
        parts.append(audio.to_text(c.audio_ref, None))
    if c.url:
        parts.append(c.url)
    if c.qr_text:
        parts.append(c.qr_text)
    return "\n".join(p for p in parts if p).strip()


def _build_chain(signals: list[Signal]) -> list[AttackStage]:
    best: dict[str, Signal] = {}
    for s in signals:
        stage = _CODE_TO_STAGE.get(s.code)
        if not stage:
            continue
        if stage not in best or s.confidence > best[stage].confidence:
            best[stage] = s
    return [AttackStage(stage=st, confidence=round(best[st].confidence, 2),
                        evidence=best[st].evidence)
            for st in _CHAIN_ORDER if st in best]


_NO_SIGNALS = {
    "hi": "प्रतिरूपण, दबाव या संवेदनशील अनुरोध के कोई संकेत नहीं मिले।",
    "hinglish": "Impersonation, pressure ya sensitive-request ke koi sanket nahi mile.",
    "en": "No impersonation, pressure, or sensitive-request signals found.",
}


def _explain(signals: list[Signal], language: str = "en") -> list[str]:
    if not signals:
        return [_NO_SIGNALS.get(language, _NO_SIGNALS["en"])]
    return [explain_code(s.code, language) for s in signals]


def _objective(signals: list[Signal]) -> str:
    for s in sorted(signals, key=lambda x: -x.weight):
        if s.code in _OBJECTIVES:
            return _OBJECTIVES[s.code]
    if any(s.category == "AUTHORITY" for s in signals):
        return "Trust exploitation (objective unclear yet)"
    return "Unknown"


def _sev(code: str, level: str) -> str:
    if code in ("OTP_REQUEST", "REMOTE_ACCESS", "CREDENTIAL_REQUEST", "PHISHING_URL"):
        return "CRITICAL"
    if code in ("PAYMENT_REQUEST", "SUSPICIOUS_URL", "PRESSURE_THREAT"):
        return "HIGH"
    return level
