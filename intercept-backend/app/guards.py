"""Production guards (§ deploy): CORS for app/website + payload cap + per-IP rate limit.

Limits are deliberately generous — a live screened call (WS turns + REST
fallback) and the demo script must never trip them; only floods get 429s.
WebSocket traffic is unaffected (this middleware covers HTTP only).
"""
from __future__ import annotations

import time
from collections import defaultdict, deque

from fastapi import Request
from fastapi.responses import JSONResponse

MAX_BODY_BYTES = 2 * 1024 * 1024  # screenshots/QR as base64 stay well under this
WINDOW_SECONDS = 60.0
MAX_REQUESTS_PER_MINUTE = 240

_hits: dict[str, deque] = defaultdict(deque)


async def guard(request: Request, call_next):
    path = request.url.path
    if request.method in ("POST", "PUT", "PATCH"):
        try:
            size = int(request.headers.get("content-length", "0") or 0)
        except (TypeError, ValueError):
            size = 0
        if size > MAX_BODY_BYTES:
            return JSONResponse({"detail": "payload too large (2 MB max)"}, status_code=413)
    if path not in ("/", "/health", "/docs", "/openapi.json", "/redoc"):
        now = time.monotonic()
        ip = request.client.host if request.client else "unknown"
        window = _hits[ip]
        while window and now - window[0] > WINDOW_SECONDS:
            window.popleft()
        if len(window) >= MAX_REQUESTS_PER_MINUTE:
            return JSONResponse({"detail": "too many requests, slow down"}, status_code=429)
        window.append(now)
    return await call_next(request)
