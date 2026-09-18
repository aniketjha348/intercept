"""QR intelligence (§6): decode → URL/payment payload → URL intel."""
from __future__ import annotations

import base64
import io
from urllib.parse import parse_qs, urlparse

from app.intelligence import url_intel
from app.schemas import Signal


def analyze_qr(image_b64: str | None = None, qr_text: str | None = None):
    payload = (qr_text or "").strip() or _try_decode(image_b64)
    signals: list[Signal] = []
    info: dict = {"payload": payload[:300] if payload else None}
    if not payload:
        return signals, info
    if payload.startswith("upi://"):
        try:
            q = parse_qs(urlparse(payload).query)
            pa = (q.get("pa") or ["unknown"])[0]
            info.update({"type": "upi_payment", "payee": pa})
        except Exception:
            info["type"] = "upi_payment"
        signals.append(Signal(code="PAYMENT_REQUEST", category="PAYMENT",
                              confidence=0.88, evidence=f"QR requests UPI payment: {payload[:120]}",
                              weight=30, origin="url_intel"))
        return signals, info
    if payload.startswith("http"):
        sigs, findings = url_intel.analyze_url(payload)
        info.update({"type": "url", **findings})
        return sigs, info
    info["type"] = "text"
    return signals, info


def _try_decode(image_b64: str | None) -> str:
    if not image_b64:
        return ""
    try:
        from PIL import Image
        from pyzbar.pyzbar import decode

        raw = base64.b64decode(image_b64)
        for obj in decode(Image.open(io.BytesIO(raw))):
            return obj.data.decode("utf-8", "ignore")
    except Exception:
        pass
    return ""
