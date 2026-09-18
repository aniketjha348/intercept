"""Persistent store (§24). Postgres (Neon) when DATABASE_URL is set,
transparent in-memory fallback otherwise — same API, zero behavior change."""
from __future__ import annotations

import re

# Privacy promise: OTPs/passwords are analyzed, never stored. Persisted
# transcripts + event evidence get digit-runs scrubbed (live in-memory view
# keeps full text only for the active session).
_SCRUB_RE = re.compile(r"\b\d{4,8}\b")


def scrub_secrets(text: str) -> str:
    return _SCRUB_RE.sub("••••", text or "")


def scrub_report(report: dict) -> dict:
    """The report quotes the caller, so it needs the same digit-run scrub as the
    transcript rows. Without this the SecurityReport row was the back door that
    stored the very OTP we promise never to keep. The in-memory copy stays
    unscrubbed so a live session can still read what was actually said."""
    out = dict(report or {})
    turns = out.get("transcript")
    if isinstance(turns, list):
        out["transcript"] = [
            {**t, "text": scrub_secrets(str(t.get("text") or ""))}
            if isinstance(t, dict) else t
            for t in turns
        ]
    for key in ("summary", "action", "claimed_org"):
        if isinstance(out.get(key), str):
            out[key] = scrub_secrets(out[key])
    why = out.get("why")
    if isinstance(why, list):
        out["why"] = [scrub_secrets(w) if isinstance(w, str) else w for w in why]
    return out


class Repository:
    def __init__(self) -> None:
        self.reports: dict[str, dict] = {}
        self.events: list[dict] = []
        # user_id -> forwarding DID. In-memory mirror so the mapping survives
        # without Postgres (same pattern as reports above).
        self._forward: dict[str, str] = {}
        self._db = False
        self._retry_at = 0.0  # next allowed DB reconnect attempt (monotonic)
        self._ensure()

    def _ensure(self) -> None:
        """Connect lazily + retry with cooldown: a cold Neon DB at startup must
        not trap the backend in memory-mode forever."""
        if self._db:
            return
        try:
            import time
            if time.monotonic() < self._retry_at:
                return
            from app.db import session as dbs
            self._db = bool(dbs.init_db())
            if not self._db:
                self._retry_at = time.monotonic() + 300.0
        except Exception:
            try:
                import time
                self._retry_at = time.monotonic() + 300.0
            except Exception:
                pass

    @property
    def persistent(self) -> bool:
        self._ensure()
        return self._db

    # ---- sessions / transcripts ----

    def ensure_session(self, session_id: str, caller: str) -> None:
        self._ensure()
        if not self._db:
            return
        try:
            from app.db import models
            from app.db.session import get_engine
            from sqlalchemy.dialects.postgresql import insert
            eng = get_engine()
            with eng.begin() as c:
                c.execute(insert(models.CallSession).values(
                    id=session_id, caller=caller).on_conflict_do_nothing())
        except Exception:
            pass

    def save_transcript(self, session_id: str, speaker: str, text: str) -> None:
        self._ensure()
        if not self._db:
            return
        try:
            from app.db import models
            from app.db.session import get_engine
            eng = get_engine()
            with eng.begin() as c:
                c.execute(models.TranscriptSegment.__table__.insert().values(
                    session_id=session_id, speaker=speaker,
                    text=scrub_secrets(text)[:4000]))
        except Exception:
            pass

    def touch_session_risk(self, session_id: str, risk: int, level: str) -> None:
        self._ensure()
        if not self._db:
            return
        try:
            from app.db import models
            from app.db.session import get_engine
            eng = get_engine()
            with eng.begin() as c:
                c.execute(models.CallSession.__table__.update().where(
                    models.CallSession.id == session_id).values(risk=risk, level=level))
        except Exception:
            pass

    # ---- events / reports ----

    def add_events(self, session_id: str, events: list[dict]) -> None:
        self.events.extend(events)
        self._ensure()
        if not self._db:
            return
        try:
            from app.db import models
            from app.db.session import get_engine
            eng = get_engine()
            rows = [{"session_id": session_id, "event_type": e.get("event_type", "?"),
                     "channel": str(e.get("channel", "")), "severity": e.get("severity", "LOW"),
                     "evidence": scrub_secrets(e.get("evidence") or "")[:1000],
                     "confidence": int((e.get("confidence") or 0) * 100)} for e in events]
            if rows:
                with eng.begin() as c:
                    c.execute(models.SecurityEvent.__table__.insert(), rows)
        except Exception:
            pass

    def save_report(self, session_id: str, report: dict) -> None:
        self.reports[session_id] = report
        self._ensure()
        if not self._db:
            return
        try:
            from app.db import models
            from app.db.session import get_engine
            from sqlalchemy.dialects.postgresql import insert
            eng = get_engine()
            with eng.begin() as c:
                stored = scrub_report(report)
                c.execute(insert(models.SecurityReport).values(
                    session_id=session_id, report=stored).on_conflict_do_update(
                        index_elements=["session_id"], set_={"report": stored}))
        except Exception:
            pass

    def load_report(self, session_id: str) -> dict | None:
        if session_id in self.reports:
            return self.reports[session_id]
        self._ensure()
        if not self._db:
            return None
        try:
            from app.db import models
            from app.db.session import get_engine
            from sqlalchemy import select
            eng = get_engine()
            with eng.connect() as c:
                row = c.execute(select(models.SecurityReport.report).where(
                    models.SecurityReport.session_id == session_id)).scalar_one_or_none()
            if row is not None:
                self.reports[session_id] = row
            return row
        except Exception:
            return None

    # ---- scam fingerprints (Scam DNA survives restarts) ----

    def save_fingerprint(self, tactics: list[str], objective: str, risk: int) -> None:
        if not self._db or not tactics or risk < 50:
            return
        try:
            from app.db import models
            from app.db.session import get_engine
            eng = get_engine()
            with eng.begin() as c:
                c.execute(models.ScamFingerprint.__table__.insert().values(
                    tactics=tactics, objective=objective, risk=risk))
        except Exception:
            pass

    def load_fingerprints(self, limit: int = 50) -> list[dict]:
        self._ensure()
        if not self._db:
            return []
        try:
            from app.db import models
            from app.db.session import get_engine
            from sqlalchemy import select
            eng = get_engine()
            with eng.connect() as c:
                rows = c.execute(select(models.ScamFingerprint.tactics,
                                        models.ScamFingerprint.objective,
                                        models.ScamFingerprint.risk)
                                 .order_by(models.ScamFingerprint.id.desc())
                                 .limit(limit)).all()
            return [{"tactics": r[0], "objective": r[1], "risk": r[2]} for r in rows]
        except Exception:
            return []


    # ---- forwarding numbers (one DID per owner) ----

    def set_forward_number(self, user_id: str, number: str) -> None:
        """Bind an owner to the DID their calls forward to. Blank input is a
        no-op — an empty mapping is worse than none (the app would dial `*67*#`)."""
        user_id = (user_id or "").strip()
        number = (number or "").strip()
        if not user_id or not number:
            return
        self._forward[user_id] = number
        self._ensure()
        if not self._db:
            return
        try:
            from app.db import models
            from app.db.session import get_engine
            from sqlalchemy.dialects.postgresql import insert
            eng = get_engine()
            with eng.begin() as c:
                c.execute(insert(models.UserForwarding).values(
                    user_id=user_id, number=number).on_conflict_do_update(
                        index_elements=["user_id"], set_={"number": number}))
        except Exception:
            pass

    def get_forward_number(self, user_id: str) -> str | None:
        user_id = (user_id or "").strip()
        if not user_id:
            return None
        if user_id in self._forward:
            return self._forward[user_id]
        self._ensure()
        if not self._db:
            return None
        try:
            from app.db import models
            from app.db.session import get_engine
            from sqlalchemy import select
            eng = get_engine()
            with eng.connect() as c:
                val = c.execute(select(models.UserForwarding.number).where(
                    models.UserForwarding.user_id == user_id)).scalar_one_or_none()
            if val:
                self._forward[user_id] = val
            return val
        except Exception:
            return None

    def user_for_forward_number(self, number: str) -> str | None:
        """Reverse lookup: an inbound call to a DID belongs to its owner."""
        number = (number or "").strip()
        if not number:
            return None
        for uid, num in self._forward.items():
            if num == number:
                return uid
        self._ensure()
        if not self._db:
            return None
        try:
            from app.db import models
            from app.db.session import get_engine
            from sqlalchemy import select
            eng = get_engine()
            with eng.connect() as c:
                uid = c.execute(select(models.UserForwarding.user_id).where(
                    models.UserForwarding.number == number)).scalar_one_or_none()
            if uid:
                self._forward[uid] = number
            return uid
        except Exception:
            return None


REPO = Repository()
