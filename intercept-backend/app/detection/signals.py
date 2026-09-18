"""Signal taxonomy (§7): AUTHORITY / PRESSURE / INCENTIVE / ACTION + URL/PAYMENT."""
from __future__ import annotations

import re

# code -> (category, weight, patterns)
TAXONOMY: dict[str, tuple[str, int, list[str]]] = {
    # AUTHORITY (+15)
    "AUTHORITY_BANK": ("AUTHORITY", 15, [r"\bbank\b", r"\bsbi\b", r"\bhdfc\b", r"\bicici\b", r"\brbi\b", r"\bkyc\b", r"account department"]),
    "AUTHORITY_POLICE": ("AUTHORITY", 15, [r"\bpolice\b", r"\bcbi\b", r"\bcyber ?cell\b", r"\barrest\b.*\bwarrant\b", r"\bfir\b"]),
    "AUTHORITY_GOVT": ("AUTHORITY", 15, [r"\btrai\b", r"\bcustoms\b", r"\bincome tax\b", r"\baadhaar\b", r"\bgovernment\b", r"\bsarkar\b"]),
    "AUTHORITY_SUPPORT": ("AUTHORITY", 15, [r"technical support", r"customer care", r"microsoft support", r"service center"]),
    "AUTHORITY_EMPLOYER": ("AUTHORITY", 15, [r"\bhr\b.*\bcompany\b", r"your employer", r"offer letter.*fee"]),
    # PRESSURE
    "PRESSURE_URGENCY": ("PRESSURE", 15, [r"\burgent\b", r"\bimmediately\b", r"within \d+ (minutes?|hours?)", r"right now", r"at once", r"turant", r"jaldi"]),
    "PRESSURE_THREAT": ("PRESSURE", 20, [r"\bblock(ed)?\b", r"\bfreez(e|ing)\b", r"\bsuspend(ed)?\b", r"\bclos(e|ure|ing)\b.*account", r"legal action", r"account.*deactivat"]),
    "PRESSURE_FEAR": ("PRESSURE", 15, [r"\bfraud\b.*your account", r"unauthori[sz]ed", r"compromised", r"arrest", r"police case", r"money.*stuck"]),
    # INCENTIVE
    "INCENTIVE_PRIZE": ("INCENTIVE", 25, [r"\bprize\b", r"\blottery\b", r"\bwinner\b", r"\bcongratulations\b.*won", r"\bcashback\b", r"\brefund\b", r"\bjob\b.*\bfee\b", r"work from home.*deposit", r"investment.*double", r"guaranteed returns?"]),
    # ACTION — high weights per §9
    "OTP_REQUEST": ("ACTION", 50, [r"\botp\b", r"one[- ]time password", r"verification code", r"share.*code"]),
    "CREDENTIAL_REQUEST": ("ACTION", 35, [r"\bpassword\b", r"\bcvv\b", r"\bpin\b", r"card number", r"expiry", r"login.*credential", r"netbanking"]),
    "PAYMENT_REQUEST": ("ACTION", 30, [r"\bupi\b", r"\bpay\b.*\bnow\b", r"transfer.*money", r"send.*money", r"qr.*scan", r"advance.*payment", r"processing fee"]),
    "REMOTE_ACCESS": ("ACTION", 40, [r"anydesk", r"teamviewer", r"quicksupport", r"screen shar", r"remote access", r"install.*app", r"download.*apk"]),
    "LINK_CLICK": ("ACTION", 20, [r"click.*link", r"open.*link", r"verify.*link", r"https?://", r"bit\.ly", r"tinyurl"]),
}

URL_RE = re.compile(r"https?://[^\s)\"'<>]+", re.IGNORECASE)
UPI_RE = re.compile(r"upi://[^\s)\"'<>]+", re.IGNORECASE)

_COMPILED: dict[str, list[re.Pattern]] = {
    code: [re.compile(p, re.IGNORECASE) for p in pats]
    for code, (_, _, pats) in TAXONOMY.items()
}

# Hindi (Devanagari) + Hinglish (roman) patterns — same taxonomy, same weights.
HI_PATTERNS: dict[str, list[str]] = {
    "AUTHORITY_BANK": [r"बैंक", r"खाता", r"केवाईसी", r"सत्यापन विभाग", r"khata", r"\bkyc\b"],
    "AUTHORITY_POLICE": [r"पुलिस", r"गिरफ्तारी", r"वारंट", r"एफआईआर", r"साइबर सेल", r"giraftar"],
    "AUTHORITY_GOVT": [r"सरकार", r"आधार", r"आयकर", r"कस्टम", r"sarkari"],
    "AUTHORITY_SUPPORT": [r"कस्टमर केयर", r"तकनीकी सहायता", r"customer care"],
    "AUTHORITY_EMPLOYER": [r"नौकरी.*फीस", r"offer letter"],
    "PRESSURE_URGENCY": [r"तुरंत", r"तुरन्त", r"जल्दी", r"अभी", r"फौरन", r"turant", r"jaldi", r"fauran", r"foran"],
    "PRESSURE_THREAT": [r"बंद", r"ब्लॉक", r"फ्रीज", r"निलंबित", r"रद्द", r"कानूनी कार्रवाई",
                        r"खाता.*बंद", r"band ho jaye", r"kanooni", r"karwai", r"dhamki"],
    "PRESSURE_FEAR": [r"धोखाधड़ी", r"गिरफ्तार", r"खतरा", r"चोरी", r"फर्जीवाड़ा", r"अनधिकृत",
                      r"dhokha", r"dhokhadhadi", r"farzi"],
    "INCENTIVE_PRIZE": [r"इनाम", r"लॉटरी", r"बधाई", r"कैशबैक", r"रिफंड", r"नौकरी", r"निवेश",
                        r"दोगुना", r"घर बैठे", r"inaam", r"badhai", r"ghar baithe"],
    "OTP_REQUEST": [r"ओटीपी", r"सत्यापन कोड", r"कोड.*बताएं", r"कोड.*भेजें",
                    r"otp.*batao", r"otp.*bhejo", r"otp.*share", r"code.*batao"],
    "CREDENTIAL_REQUEST": [r"पासवर्ड", r"पिन", r"सीवीवी", r"कार्ड नंबर", r"लॉगिन",
                           r"password.*batao", r"pin.*batao", r"cvv"],
    "PAYMENT_REQUEST": [r"यूपीआई", r"पैसे.*भेज", r"भुगतान", r"क्यूआर.*स्कैन", r"फीस.*जमा",
                        r"paisa.*bhejo", r"paisa.*transfer", r"payment.*karo", r"fees.*jama"],
    "REMOTE_ACCESS": [r"स्क्रीन शेयर", r"रिमोट", r"ऐप.*इंस्टॉल", r"ऐप.*डाउनलोड",
                      r"screen.*share", r"app.*download", r"app.*install"],
    "LINK_CLICK": [r"लिंक.*क्लिक", r"लिंक.*खोल", r"link.*click", r"link.*kholo"],
}

for _code, _pats in HI_PATTERNS.items():
    _COMPILED[_code].extend(re.compile(p, re.IGNORECASE) for p in _pats)


def match_text(text: str) -> list[tuple[str, str]]:
    """Return (code, matched_snippet) pairs found in text."""
    out: list[tuple[str, str]] = []
    for code, patterns in _COMPILED.items():
        for pat in patterns:
            m = pat.search(text)
            if m:
                out.append((code, m.group(0)[:120]))
                break
    return out


def extract_urls(text: str) -> list[str]:
    urls = URL_RE.findall(text or "")
    upis = UPI_RE.findall(text or "")
    seen: list[str] = []
    for u in urls + upis:
        if u not in seen:
            seen.append(u)
    return seen
