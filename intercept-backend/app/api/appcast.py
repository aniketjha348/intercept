"""App distribution feed — single source of truth for:
website download page, changelog page, and in-app updater."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from fastapi import APIRouter
from pydantic import BaseModel

from app import core_config as cfg

router = APIRouter(prefix="/app", tags=["distribution"])

_UPDATES_FILE = Path(__file__).resolve().parent.parent / "updates.json"


class LatestOut(BaseModel):
    version_code: int
    version_name: str
    apk_url: str
    force: bool = False
    updated_at: str = ""
    notes_en: list[str] = []
    notes_hi: list[str] = []


@lru_cache(maxsize=1)
def _updates() -> list[dict]:
    try:
        return json.loads(_UPDATES_FILE.read_text(encoding="utf-8"))
    except Exception:
        return []


@router.get("/updates")
def updates():
    """Full changelog — website fetches this (falls back to its copy)."""
    items = [{**u, "apk_url": cfg.APK_URL} for u in _updates()]
    return {"updates": items}


@router.get("/latest", response_model=LatestOut)
def latest():
    """What the Android updater + website download button read."""
    items = _updates()
    cur = items[-1] if items else {"version_code": 1, "version_name": "0.1.0-mvp"}
    return LatestOut(
        version_code=int(cur.get("version_code", 1)),
        version_name=str(cur.get("version_name", "0.1.0-mvp")),
        apk_url=cfg.APK_URL,
        force=bool(cur.get("force", False)),
        updated_at=str(cur.get("date", "")),
        notes_en=list(cur.get("notes_en", [])),
        notes_hi=list(cur.get("notes_hi", [])),
    )
