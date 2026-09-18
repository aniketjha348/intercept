"""Engine + table init. Postgres (Neon) when DATABASE_URL is postgresql://,
else nothing (repository falls back to in-memory)."""
from __future__ import annotations

from app import core_config as cfg

ENGINE = None


def _sa_url(url: str) -> str:
    if url.startswith("postgresql://"):
        return "postgresql+psycopg://" + url[len("postgresql://"):]
    return url


def get_engine():
    global ENGINE
    if ENGINE is not None:
        return ENGINE
    url = (cfg.DATABASE_URL or "").strip()
    if not url.startswith("postgresql"):
        return None
    from sqlalchemy import create_engine
    ENGINE = create_engine(_sa_url(url), pool_pre_ping=True, pool_size=5,
                           max_overflow=10, pool_timeout=20)
    return ENGINE


def init_db() -> bool:
    """Create tables if Postgres is configured. Returns True when DB active."""
    eng = get_engine()
    if eng is None:
        return False
    from app.db import models
    models.Base.metadata.create_all(eng)
    return True


def ping() -> bool:
    try:
        eng = get_engine()
        if eng is None:
            return False
        from sqlalchemy import text
        with eng.connect() as c:
            c.execute(text("SELECT 1"))
        return True
    except Exception:
        return False
