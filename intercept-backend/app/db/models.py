"""Postgres-ready models (§24). MVP runtime uses the in-memory repo; point
DATABASE_URL at Postgres and run these via Alembic/SQLAlchemy when ready."""
from __future__ import annotations

try:
    from sqlalchemy import JSON, Column, DateTime, ForeignKey, Integer, String, Text, func
    from sqlalchemy.orm import declarative_base

    Base = declarative_base()

    class CallSession(Base):
        __tablename__ = "call_sessions"
        id = Column(String, primary_key=True)
        caller = Column(String, default="unknown")
        risk = Column(Integer, default=0)
        level = Column(String, default="LOW")
        created_at = Column(DateTime(timezone=True), server_default=func.now())

    class TranscriptSegment(Base):
        __tablename__ = "transcript_segments"
        id = Column(Integer, primary_key=True, autoincrement=True)
        session_id = Column(String, ForeignKey("call_sessions.id"))
        speaker = Column(String)
        text = Column(Text)

    class SecurityEvent(Base):
        __tablename__ = "security_events"
        id = Column(Integer, primary_key=True, autoincrement=True)
        session_id = Column(String)
        event_type = Column(String)
        channel = Column(String)
        severity = Column(String)
        evidence = Column(Text)
        confidence = Column(Integer, default=80)

    class SecurityReport(Base):
        __tablename__ = "security_reports"
        id = Column(Integer, primary_key=True, autoincrement=True)
        session_id = Column(String, unique=True)
        report = Column(JSON)

    class ScamFingerprint(Base):
        __tablename__ = "scam_fingerprints"
        id = Column(Integer, primary_key=True, autoincrement=True)
        tactics = Column(JSON)
        objective = Column(String)
        risk = Column(Integer, default=0)

    class UserForwarding(Base):
        """One DID per owner: the number their calls are forwarded to. Unique on
        the number so two owners can never claim the same one (that would route
        a caller into someone else's assistant)."""
        __tablename__ = "user_forwarding"
        user_id = Column(String, primary_key=True)
        number = Column(String, unique=True)
        updated_at = Column(DateTime(timezone=True), server_default=func.now(),
                            onupdate=func.now())

except Exception:  # SQLAlchemy optional for MVP boot
    Base = object  # type: ignore
