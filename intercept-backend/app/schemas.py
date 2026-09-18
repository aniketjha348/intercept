"""Universal Input Object + unified Security Event model (§3, §20)."""
from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Optional
from uuid import uuid4

from pydantic import BaseModel, Field


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def new_session_id(prefix: str = "sess") -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class Channel(str, Enum):
    CALL = "CALL"
    SMS = "SMS"
    WHATSAPP = "WHATSAPP"
    EMAIL = "EMAIL"
    SCREENSHOT = "SCREENSHOT"
    URL = "URL"
    QR = "QR"
    VOICE = "VOICE"
    DOCUMENT = "DOCUMENT"


class Source(BaseModel):
    type: str = "UNKNOWN"
    identifier: Optional[str] = None


class Content(BaseModel):
    text: Optional[str] = None
    audio_ref: Optional[str] = None
    image_b64: Optional[str] = None
    url: Optional[str] = None
    qr_text: Optional[str] = None


class InterceptInput(BaseModel):
    session_id: str = Field(default_factory=lambda: new_session_id())
    channel: Channel = Channel.CALL
    source: Source = Field(default_factory=Source)
    content: Content = Field(default_factory=Content)
    timestamp: str = Field(default_factory=_now)
    context: dict[str, Any] = Field(default_factory=dict)
    language: str = "auto"  # auto | hi | hinglish | en


class Signal(BaseModel):
    code: str  # e.g. OTP_REQUEST, AUTHORITY_BANK
    category: str  # AUTHORITY | PRESSURE | INCENTIVE | ACTION | URL | PAYMENT
    confidence: float = 0.8
    evidence: Optional[str] = None
    weight: int = 10
    origin: str = "rules"  # rules | llm | url_intel | threat_intel


class AttackStage(BaseModel):
    stage: str
    confidence: float
    evidence: Optional[str] = None


class SecurityEvent(BaseModel):
    event_type: str
    channel: Channel
    severity: str  # LOW | SUSPICIOUS | HIGH | CRITICAL
    timestamp: str = Field(default_factory=_now)
    evidence: Optional[str] = None
    confidence: float = 0.8


class PolicyDecision(BaseModel):
    action: str  # CONTINUE | WARN | PROTECT
    level: str  # LOW | SUSPICIOUS | HIGH | CRITICAL
    user_message: str
    simple_mode_message: str
    guardian_instruction: str
    must_terminate: bool = False
    offer_takeover: bool = False


class AnalysisResult(BaseModel):
    session_id: str
    channel: Channel
    language: str = "en"
    risk_score: int
    risk_level: str
    signals: list[Signal] = Field(default_factory=list)
    attack_chain: list[AttackStage] = Field(default_factory=list)
    events: list[SecurityEvent] = Field(default_factory=list)
    policy: PolicyDecision
    explanation: list[str] = Field(default_factory=list)
    likely_objective: str = "Unknown"
    similar_pattern: Optional[str] = None
    guardian_reply: str = ""
