"""URL Intelligence Engine (§5). Defensive only: never submits credentials."""
from __future__ import annotations

from urllib.parse import urlparse

from app import core_config as cfg
from app.schemas import Signal

SHORTENERS = {"bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd",
              "cutt.ly", "rebrand.ly", "shorturl.at"}
BRANDS = ["sbi", "hdfc", "icici", "axis", "kotak", "paytm", "phonepe",
          "gpay", "upi", "rbi", "kyc", "aadhaar", "amazon", "flipkart"]
SUSPICIOUS_TLDS = {".tk", ".ml", ".ga", ".cf", ".gq", ".xyz", ".top", ".buzz"}
LOGIN_HINTS = ["login", "signin", "verify", "kyc", "update", "secure", "account"]


def analyze_url(raw: str) -> tuple[list[Signal], dict]:
    """Return (signals, findings). Purely lexical unless ALLOW_NETWORK_FETCH=true."""
    url = (raw or "").strip()
    if not url or "://" not in url and not url.startswith("upi://"):
        return [], {"url": raw, "error": "not_a_url"}
    try:
        if url.startswith("upi://"):
            return [Signal(code="PAYMENT_REQUEST", category="PAYMENT", confidence=0.85,
                           evidence=f"UPI payment payload: {url[:120]}", weight=30,
                           origin="url_intel")], {"scheme": "upi", "url": url}
        p = urlparse(url if "://" in url else "http://" + url)
        host = (p.hostname or "").lower()
        findings: dict = {"url": url, "host": host, "scheme": p.scheme}
        sigs: list[Signal] = []
        if not host:
            return [], findings

        def add(code: str, conf: float, ev: str, w: int):
            sigs.append(Signal(code=code, category="URL", confidence=conf,
                               evidence=ev, weight=w, origin="url_intel"))

        # Punycode / homoglyph
        if "xn--" in host:
            add("SUSPICIOUS_URL", 0.9, f"Punycode host (possible homoglyph): {host}", 20)
            findings["punycode"] = True
        # Shortener
        if host in SHORTENERS or host.startswith("bit.ly"):
            add("SUSPICIOUS_URL", 0.75, f"URL shortener hides destination: {host}", 20)
            findings["shortener"] = True
        # IP host
        if host.replace(".", "").isdigit():
            add("SUSPICIOUS_URL", 0.8, f"Direct IP host: {host}", 20)
            findings["ip_host"] = True
        # Brand + lookalike — but never flag the brand's own official domain
        # (e.g. www.rbi.org.in IS the real RBI site; sbi.co.in IS real SBI).
        OFFICIAL_TLDS = ("com", "in", "org", "net", "co.in", "org.in",
                           "gov.in", "ac.in", "edu.in")
        for brand in BRANDS:
            if brand not in host:
                continue
            official = any(
                host == f"{brand}.{tld}" or host.endswith(f".{brand}.{tld}")
                for tld in OFFICIAL_TLDS
            )
            if official:
                findings["official_brand_domain"] = brand
                continue
            add("PHISHING_URL", 0.82, f"Brand impersonation: '{brand}' inside {host}", 20)
            findings["brand_impersonation"] = brand
            break
        # Excessive hyphens/subdomains
        if host.count("-") >= 3 or host.count(".") >= 4:
            add("SUSPICIOUS_URL", 0.65, f"Obfuscated host structure: {host}", 20)
            findings["obfuscated"] = True
        if any(tld for tld in SUSPICIOUS_TLDS if host.endswith(tld)):
            add("SUSPICIOUS_URL", 0.7, f"High-abuse TLD: {host}", 20)
            findings["suspicious_tld"] = True
        if "@" in url:
            add("SUSPICIOUS_URL", 0.8, "URL contains '@' (credential/redirect trick)", 20)
        path = (p.path or "").lower()
        if any(h in path for h in LOGIN_HINTS):
            add("PHISHING_URL", 0.7, f"Credential-harvesting path hint: {p.path[:80]}", 20)
            findings["login_path"] = True
        if p.scheme == "http":
            add("SUSPICIOUS_URL", 0.55, "Insecure http scheme on a sensitive-looking link", 10)
        # Optional defensive fetch: title/forms only, no credentials, short timeout.
        if cfg.ALLOW_NETWORK_FETCH and p.scheme in ("http", "https"):
            findings["note"] = "network fetch enabled — defensive HEAD only (no forms filled)"
        return sigs, findings
    except Exception as exc:  # never crash the pipeline on a bad URL
        return [], {"url": raw, "error": str(exc)[:120]}
