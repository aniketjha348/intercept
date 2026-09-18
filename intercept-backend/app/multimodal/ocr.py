"""OCR stub (§4): uses pytesseract when installed, else supplied text."""
from __future__ import annotations

import base64
import io


def extract_text(image_b64: str | None, fallback_text: str | None = None) -> str:
    if fallback_text:
        try_ocr = _try_ocr(image_b64) if image_b64 else ""
        combined = f"{try_ocr}\n{fallback_text}".strip() if try_ocr else fallback_text
        return combined
    if image_b64:
        return _try_ocr(image_b64)
    return ""


def _try_ocr(image_b64: str) -> str:
    try:
        from PIL import Image
        import pytesseract

        raw = base64.b64decode(image_b64)
        img = Image.open(io.BytesIO(raw))
        return pytesseract.image_to_string(img).strip()
    except Exception:
        return ""
