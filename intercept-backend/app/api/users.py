"""Minimal user/contact store for MVP (trusted/blocked lists feed context later)."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(prefix="/users", tags=["users"])
_TRUSTED: set[str] = set()
_BLOCKED: set[str] = set()
# Unauthenticated endpoint, module-level state: bound it so a scripted flood
# cannot grow these sets without limit.
_MAX_LIST = 5000


def _cap(items: set[str]) -> None:
    while len(items) > _MAX_LIST:
        items.pop()


class Contact(BaseModel):
    identifier: str


class Device(BaseModel):
    device_id: str


@router.post("/register")
def register(d: Device):
    """Zero-friction sign-in: the app mints a random device id once; the server
    derives a stable, opaque user id (sha256, no PII stored anywhere)."""
    import hashlib
    digest = hashlib.sha256(d.device_id.encode("utf-8")).hexdigest()[:16]
    return {"user_id": f"u_{digest}"}


@router.post("/trusted")
def add_trusted(c: Contact):
    _TRUSTED.add(c.identifier)
    _cap(_TRUSTED)
    return {"trusted": sorted(_TRUSTED)}


@router.post("/blocked")
def add_blocked(c: Contact):
    _BLOCKED.add(c.identifier)
    _cap(_BLOCKED)
    return {"blocked": sorted(_BLOCKED)}


@router.get("/lists")
def lists():
    return {"trusted": sorted(_TRUSTED), "blocked": sorted(_BLOCKED)}
