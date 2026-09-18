"""Multilingual core: Hindi / Hinglish / English (§4 text analysis, §12 replies).

AI has native multilingual capability — detection patterns, explanations,
policy messages and guardian replies all resolve to the caller's language.
`auto` detects per input; callers can pin hi/en/hinglish.
"""
from __future__ import annotations

import re

SUPPORTED = ("hi", "hinglish", "en")

_DEVA = re.compile(r"[\u0900-\u097F]")

# Roman-script Hindi markers (kept free of common English words — "me",
# "band", "hi" and bare "ho"/"na"/"se" deliberately excluded: they collide).
_HINGLISH = {
    "turant", "turunt", "jaldi", "fauran", "foran", "abhi",
    "batao", "batayein", "bataiye", "bhejo", "bhejein", "bhej",
    "paisa", "paise", "paison", "khata", "khatte",
    "giraftar", "giraftar", "kanooni", "karwai", "karvai",
    "inaam", "badhai", "dhokha", "dhokhadhadi", "farzi", "farziwada",
    "dhamki", "lut", "lutega", "fas", "fasega", "atka", "jama",
    "dugna", "double", "ghar", "baithe", "karo", "karna", "chahiye",
    "sarkari", "daftar", "shakha", "bharosa",
    # Everyday roman-Hindi function words (never English standalone words).
    "main", "mein", "hoon", "hun", "hai", "hain",
    "raha", "rahe", "rahi", "raho", "kya", "nahi", "nahin",
    "mera", "meri", "tum", "tumhara", "aap", "hum",
    "ko", "ka", "ki", "ke", "bol", "bolo", "sun", "suno",
    "wala", "wale", "wali", "karke", "kar", "mat",
}


def detect_language(text: str) -> str:
    t = (text or "").strip()
    if not t:
        return "en"
    if _DEVA.search(t):
        return "hi"
    words = set(re.findall(r"[a-zA-Z]+", t.lower()))
    if words & _HINGLISH:
        return "hinglish"
    return "en"


def resolve_language(explicit: str | None, text: str) -> str:
    if explicit and explicit.lower() in SUPPORTED:
        return explicit.lower()
    return detect_language(text)


# ---- Human explanations per signal code (§15 "why is this suspicious?") ----

_HUMAN_EN = {
    "AUTHORITY_BANK": "Claimed to represent a bank / financial institution.",
    "AUTHORITY_POLICE": "Claimed police / investigative authority.",
    "AUTHORITY_GOVT": "Claimed government authority (TRAI/customs/tax).",
    "AUTHORITY_SUPPORT": "Claimed technical/customer support.",
    "AUTHORITY_EMPLOYER": "Claimed employer / job authority.",
    "PRESSURE_URGENCY": "Created time pressure ('act now').",
    "PRESSURE_THREAT": "Threatened harm (block/freeze/legal action).",
    "PRESSURE_FEAR": "Used fear (fraud/arrest/compromise claims).",
    "INCENTIVE_PRIZE": "Offered reward/refund/job/investment bait.",
    "OTP_REQUEST": "Requested an OTP / verification code.",
    "CREDENTIAL_REQUEST": "Requested password / card credentials.",
    "PAYMENT_REQUEST": "Requested money transfer / UPI payment.",
    "REMOTE_ACCESS": "Requested remote access / app installation.",
    "LINK_CLICK": "Pushed a link to click.",
    "SUSPICIOUS_URL": "Link looks suspicious (shortener/spoof/structure).",
    "PHISHING_URL": "Link shows phishing indicators (brand spoof / login trap).",
}

_HUMAN_HI = {
    "AUTHORITY_BANK": "बैंक / वित्तीय संस्थान का प्रतिनिधि होने का दावा किया।",
    "AUTHORITY_POLICE": "पुलिस / जांच एजेंसी होने का दावा किया।",
    "AUTHORITY_GOVT": "सरकारी अधिकारी (TRAI/कस्टम/टैक्स) होने का दावा किया।",
    "AUTHORITY_SUPPORT": "तकनीकी / कस्टमर सपोर्ट होने का दावा किया।",
    "AUTHORITY_EMPLOYER": "नियोक्ता / नौकरी देने वाले होने का दावा किया।",
    "PRESSURE_URGENCY": "समय का दबाव बनाया ('अभी करो')।",
    "PRESSURE_THREAT": "नुकसान की धमकी दी (ब्लॉक/फ्रीज/कानूनी कार्रवाई)।",
    "PRESSURE_FEAR": "डर फैलाया (धोखाधड़ी/गिरफ्तारी/समझौते के दावे)।",
    "INCENTIVE_PRIZE": "इनाम/रिफंड/नौकरी/निवेश का लालच दिया।",
    "OTP_REQUEST": "ओटीपी / सत्यापन कोड मांगा।",
    "CREDENTIAL_REQUEST": "पासवर्ड / कार्ड की जानकारी मांगी।",
    "PAYMENT_REQUEST": "पैसे ट्रांसफर / UPI पेमेंट मांगा।",
    "REMOTE_ACCESS": "रिमोट एक्सेस / ऐप इंस्टॉल करने को कहा।",
    "LINK_CLICK": "लिंक पर क्लिक करने का दबाव डाला।",
    "SUSPICIOUS_URL": "लिंक संदिग्ध लग रहा है (शॉर्टनर/नकली/अजीब बनावट)।",
    "PHISHING_URL": "लिंक में फिशिंग के संकेत हैं (नकली ब्रांड / लॉगिन जाल)।",
}

_HUMAN_HINGLISH = {
    "AUTHORITY_BANK": "Bank / financial institution ka representative hone ka daava kiya.",
    "AUTHORITY_POLICE": "Police / investigation agency hone ka daava kiya.",
    "AUTHORITY_GOVT": "Sarkari adhikari (TRAI/customs/tax) hone ka daava kiya.",
    "AUTHORITY_SUPPORT": "Technical / customer support hone ka daava kiya.",
    "AUTHORITY_EMPLOYER": "Employer / naukri dene wale hone ka daava kiya.",
    "PRESSURE_URGENCY": "Time pressure banaya ('abhi karo').",
    "PRESSURE_THREAT": "Nuksaan ki dhamki di (block/freeze/kanooni karwai).",
    "PRESSURE_FEAR": "Darr failaya (dhokhadhadi/giraftari ke daave).",
    "INCENTIVE_PRIZE": "Inaam/refund/naukri/nivesh ka lalach diya.",
    "OTP_REQUEST": "OTP / verification code manga.",
    "CREDENTIAL_REQUEST": "Password / card details mange.",
    "PAYMENT_REQUEST": "Paise transfer / UPI payment manga.",
    "REMOTE_ACCESS": "Remote access / app install karne ko kaha.",
    "LINK_CLICK": "Link par click karne ka dabav dala.",
    "SUSPICIOUS_URL": "Link sandigdh lag raha hai (shortener/nakli/ajeeb banavat).",
    "PHISHING_URL": "Link me phishing ke sanket hain (nakli brand / login jaal).",
}

_HUMAN = {"en": _HUMAN_EN, "hi": _HUMAN_HI, "hinglish": _HUMAN_HINGLISH}


def explain(code: str, language: str) -> str:
    table = _HUMAN.get(language, _HUMAN_EN)
    return table.get(code, _HUMAN_EN.get(code, code))


# ---- Guardian fallback replies (§12), offline-safe per language ----

FALLBACK_REPLY = {
    "en": {
        "LOW": "Hello, I'm screening this call for my owner. Who am I speaking with, please?",
        "SUSPICIOUS": ("I want to help, but I must verify unknown callers. Could you share "
                       "your official reference number and a callback number?"),
        "HIGH": ("I can't share any codes, passwords, or payments on this call. Please give me "
                 "your official employee ID and a verifiable callback number."),
        "CRITICAL": ("No — my owner will not share any OTP, password, or payment. "
                     "Please use the official app or branch. Goodbye."),
    },
    "hi": {
        "LOW": "नमस्ते, मैं अपने मालिक की ओर से इस कॉल की जांच कर रहा हूँ। आप कौन बोल रहे हैं?",
        "SUSPICIOUS": ("मैं मदद करना चाहता हूँ, लेकिन अनजान कॉलर का सत्यापन ज़रूरी है। "
                       "कृपया अपना आधिकारिक रेफरेंस नंबर और कॉलबैक नंबर बताएं।"),
        "HIGH": ("मैं इस कॉल पर कोई ओटीपी, पासवर्ड या पेमेंट साझा नहीं कर सकता। "
                 "कृपया अपनी आधिकारिक कर्मचारी आईडी और सत्यापित कॉलबैक नंबर दें।"),
        "CRITICAL": ("नहीं — मेरे मालिक कोई ओटीपी, पासवर्ड या पेमेंट साझा नहीं करेंगे। "
                     "कृपया आधिकारिक ऐप या शाखा से संपर्क करें। नमस्ते।"),
    },
    "hinglish": {
        "LOW": "Hello, main apne owner ki taraf se is call ko screen kar raha hoon. Aap kaun bol rahe hain?",
        "SUSPICIOUS": ("Main help karna chahta hoon, lekin unknown caller ka verification zaroori hai. "
                       "Kripya apna official reference number aur callback number batayein."),
        "HIGH": ("Main is call par koi OTP, password ya payment share nahi kar sakta. "
                 "Kripya apni official employee ID aur verified callback number dein."),
        "CRITICAL": ("Nahi — mere owner koi OTP, password ya payment share nahi karenge. "
                     "Kripya official app ya branch se contact karein. Goodbye."),
    },
}

# ---- Policy user messages (§11), per language ----

_SENSITIVE_EN = {"OTP_REQUEST": "an OTP / verification code", "CREDENTIAL_REQUEST": "password / card details",
                 "PAYMENT_REQUEST": "a payment", "REMOTE_ACCESS": "remote access to the device"}
_SENSITIVE_HI = {"OTP_REQUEST": "ओटीपी / सत्यापन कोड", "CREDENTIAL_REQUEST": "पासवर्ड / कार्ड की जानकारी",
                 "PAYMENT_REQUEST": "पेमेंट", "REMOTE_ACCESS": "डिवाइस का रिमोट एक्सेस"}
_SENSITIVE_HINGLISH = {"OTP_REQUEST": "OTP / verification code", "CREDENTIAL_REQUEST": "password / card details",
                       "PAYMENT_REQUEST": "payment", "REMOTE_ACCESS": "device ka remote access"}

def sensitive_label(code: str | None, language: str) -> str:
    table = {"en": _SENSITIVE_EN, "hi": _SENSITIVE_HI, "hinglish": _SENSITIVE_HINGLISH}.get(language, _SENSITIVE_EN)
    return table.get(code or "", "sensitive info" if language == "en"
                     else ("संवेदनशील जानकारी" if language == "hi" else "sensitive info"))


def policy_texts(level: str, language: str, score: int, sensitive: str | None) -> tuple[str, str, str]:
    """Return (user_message, simple_mode_message, guardian_instruction)."""
    label = sensitive_label(sensitive, language)
    if language == "hi":
        return {
            "LOW": ("कोई ठगी के संकेत नहीं। आप सामान्य रूप से जारी रख सकते हैं।",
                    "✅ सुरक्षित लग रहा है। सामान्य रूप से जारी रखें।",
                    "Continue the conversation naturally; stay alert for new requests."),
            "SUSPICIOUS": ("कुछ चेतावनी संकेत हैं। INTERCEPT कॉलर से सत्यापन प्रश्न पूछेगा। "
                           "अभी कोई कोड या पैसे साझा न करें।",
                           "⚠️ सावधान रहें। अभी OTP या पैसे बिल्कुल न दें।",
                           "Politely ask for an official reference/callback number; share no user data."),
            "HIGH": (f"उच्च जोखिम ({score})। संभवतः ठगी की कोशिश। INTERCEPT कॉलर से कड़ाई से पूछताछ कर रहा है। "
                     f"{label} कभी साझा न करें।",
                     f"🚨 यह ठगी लग रही है। वे {label} मांग सकते हैं। बिल्कुल न दें।",
                     "Challenge firmly: demand verifiable official proof, refuse all sensitive requests."),
            "CRITICAL": (f"गंभीर जोखिम ({score})। INTERCEPT {label} की मांग को अस्वीकार करता है। "
                         "अभी बातचीत समाप्त करने की सलाह दी जाती है।",
                         f"🚨 रुकें। यह व्यक्ति आपको {label} देकर ठग सकता है। बिल्कुल न दें। कॉल काट दें।",
                         "Refuse the sensitive request explicitly, give no user data, prepare to terminate."),
        }[level]
    if language == "hinglish":
        return {
            "LOW": ("Koi thagi ke sanket nahi. Aap normally continue kar sakte hain.",
                    "✅ Safe lag raha hai. Normally continue karein.",
                    "Continue the conversation naturally; stay alert for new requests."),
            "SUSPICIOUS": ("Kuch warning signs hain. INTERCEPT caller se verification questions puchega. "
                           "Abhi koi code ya paise share na karein.",
                           "⚠️ Savdhaan rahein. Abhi OTP ya paise bilkul na dein.",
                           "Politely ask for an official reference/callback number; share no user data."),
            "HIGH": (f"High risk ({score}). Sambhavtah thagi ki koshish. INTERCEPT caller ko challenge kar raha hai. "
                     f"{label} kabhi share na karein.",
                     f"🚨 Ye thagi lag rahi hai. Ve {label} maang sakte hain. Bilkul na dein.",
                     "Challenge firmly: demand verifiable official proof, refuse all sensitive requests."),
            "CRITICAL": (f"CRITICAL risk ({score}). INTERCEPT {label} ki demand refuse karta hai. "
                         "Abhi baat khatm karne ki salah di jati hai.",
                         f"🚨 RUKO. Ye vyakti aapko {label} dekar thag sakta hai. Bilkul na dein. Call kaat dein.",
                         "Refuse the sensitive request explicitly, give no user data, prepare to terminate."),
        }[level]
    return {
        "LOW": ("No scam signals. You can continue normally.",
                "✅ Looks safe. Continue normally.",
                "Continue the conversation naturally; stay alert for new requests."),
        "SUSPICIOUS": ("Some warning signs. INTERCEPT will ask the caller verification questions. "
                       "Do not share codes or money yet.",
                       "⚠️ Be careful. Do NOT share OTP or money yet.",
                       "Politely ask for an official reference/callback number; do not share any user data."),
        "HIGH": (f"High risk ({score}). Likely social engineering. INTERCEPT is challenging the caller. "
                 f"Never share {label}.",
                 f"🚨 This looks like a trick. They may ask for {label}. DO NOT SHARE.",
                 "Challenge firmly: demand verifiable official proof, refuse all sensitive requests."),
        "CRITICAL": (f"CRITICAL risk ({score}). INTERCEPT refuses the {label} request. "
                     "End the interaction now.",
                     f"🚨 STOP. This person may trick you into sharing {label}. DO NOT SHARE. End the call.",
                     "Refuse the sensitive request explicitly, give no user data, prepare to terminate."),
    }[level]


def reply_instruction(language: str) -> str:
    return {
        "hi": "Reply ONLY in Hindi (Devanagari script). Max 40 words.",
        "hinglish": "Reply ONLY in Hinglish (Hindi written in Roman script, simple English mix OK). Max 40 words.",
        "en": "Reply in English. Max 40 words.",
    }.get(language, "Reply in English. Max 40 words.")


KNOWLEDGE_BASE_HI = [
    "बैंक कॉल या लिंक पर कभी OTP, CVV, PIN या पासवर्ड नहीं मांगते।",
    "RBI/TRAI/पुलिस गिरफ्तारी रोकने के लिए कॉल पर पैसे नहीं मांगते।",
    "KYC अपडेट सिर्फ आधिकारिक ऐप में होता है — APK या रिमोट-एक्सेस ऐप से कभी नहीं।",
    "UPI QR स्कैन = पैसे देना, लेना नहीं। पहले payee handle जांचें।",
    "एडवांस फीस या OTP मांगने वाला रिफंड/इनाम पहले से फीस वाली ठगी है।",
]
