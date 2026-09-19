"""Intercept AI voice agent — Gemini Live voice + OUR risk engine as the brain.

Every caller turn is scored by the production backend (/analyze/text):
Hindi/Hinglish/English, OTP traps, bank impersonation — not keyword toys.

Run (laptop, demo network):
    pip install -r requirements-agent.txt
    copy .env.example .env.local   (fill keys, never commit)
    python agent.py dev
Keep it running; point the LiveKit dispatch rule at agent `intercept-agent`.

Session linking: an inbound SIP call has no Android app in front of it, so the
agent asks the backend for its session via POST /calls/inbound (reusing the
app's session when the call was answered on the phone first). The dispatch
rule's roomConfig metadata carries who the call is for — read with ctx.job.
metadata. Room names are never trusted as session ids: LiveKit names an
individual-rule room after the CALLER'S NUMBER, not our session.
"""
from __future__ import annotations

import asyncio
import inspect
import logging
import os
import urllib.error
import urllib.request
import json as _json

from dotenv import load_dotenv
from livekit import agents
from livekit.agents import JobContext, WorkerOptions, cli, voice
from livekit.plugins import google

# room_io carries the room input options (noise suppression). A build without it
# must still import: this module failing to load means the worker never
# registers, and the call rings out with nobody answering.
try:
    from livekit.agents import room_io
except ImportError:  # pragma: no cover - older agents build
    room_io = None  # type: ignore[assignment]

load_dotenv(".env.local")

logger = logging.getLogger("intercept-agent")

# The Google plugin's realtime class left its `beta` namespace when the Live API
# left beta — Python is now `livekit.plugins.google.realtime`, while `beta.` is
# the Node layout. Importing the wrong one raises INSIDE the job, which to the
# caller looks exactly like "the agent never answered": the phone connects and
# nobody speaks. Try both layouts instead of betting on the installed version.
try:  # livekit-plugins-google >= 1.5
    from livekit.plugins.google.realtime import RealtimeModel as GeminiLive
except ImportError:  # pragma: no cover — older plugin layout
    from livekit.plugins.google.beta.realtime import RealtimeModel as GeminiLive

BACKEND = os.environ.get(
    "INTERCEPT_API",
    "http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com",
)
MODEL = os.environ.get(
    "LIVE_MODEL", "gemini-2.5-flash-native-audio-preview-12-2025")
# Native-audio voice. Aoede is the most conversational of the prebuilt set;
# override per deployment (Kore, Leda, Charon, Fenrir …).
VOICE = os.environ.get("LIVE_VOICE", "Aoede")

# Rooms our own backend/app creates are `intercept-<session id>`.
ROOM_PREFIX = "intercept-"


def room_options():
    """Room I/O tuned for a phone line, or None when this build cannot take it.

    Telephony audio is 8 kHz narrowband with line hiss and DTX comfort noise, and
    a realtime AUDIO model feeds straight on whatever arrives — which is where a
    "the caller sounds like static" complaint comes from. LiveKit Cloud's noise
    suppression (Krisp NC, included in the plan) is what agents are meant to run
    on SIP legs; apply it here and NOT on the trunk as well, or the two models
    stack and the audio gets worse, not better.

    Every step is optional on purpose: a laptop that has not installed the plugin,
    or an older SDK, must still start the agent — this process not starting is the
    difference between "the AI answered" and "the call rang out".
    """
    if room_io is None:
        return None
    try:
        from livekit.plugins import noise_cancellation

        return room_io.RoomOptions(
            audio_input=room_io.AudioInputOptions(
                noise_cancellation=noise_cancellation.NC(),
            ),
        )
    except Exception as exc:
        logger.warning("noise suppression unavailable (%s) — running raw audio", exc)
        try:
            return room_io.RoomOptions()
        except Exception:
            return None


def _start_kwargs() -> dict:
    """`room_options` only exists on newer agents builds; probe, never assume.
    A wrong kwarg to session.start() aborts the job before it says a word."""
    try:
        params = inspect.signature(voice.AgentSession.start).parameters
    except (TypeError, ValueError):
        return {}
    if "room_options" not in params:
        return {}
    opts = room_options()
    return {"room_options": opts} if opts is not None else {}


class CallState:
    """Everything one caller's conversation needs to remember.

    This used to be module-level globals, which quietly bound the worker to a
    single call: a second concurrent call overwrote the session id, and
    `closing_sent` never reset, so only the FIRST critical caller ever got the
    closing line. One instance per job — no state crosses calls.
    """

    __slots__ = ("sid", "post_ok", "closing_sent")

    def __init__(self, sid: str | None) -> None:
        self.sid = sid
        self.post_ok = True
        self.closing_sent = False


# CRITICAL means the engine is past doubting: the caller is being told the
# call is over, in one firm line, instead of being politely interrogated
# further. The agent never argues after this — a scammer's whole game is
# keeping the conversation alive.
CRITICAL_CLOSING = (
    "Deliver one short, firm closing line in the caller's own language, then "
    "stop. State plainly that no OTP, PIN, password, payment or remote access "
    "will be shared, that this call is being recorded and reported, and say "
    "goodbye. Do not ask any new question and do not let them restart the "
    "conversation."
)


def job_metadata(ctx: JobContext) -> dict:
    """The dispatch rule's roomConfig metadata as a dict (best effort)."""
    raw = getattr(getattr(ctx, "job", None), "metadata", "") or ""
    if not raw:
        return {}
    try:
        data = _json.loads(raw)
        return data if isinstance(data, dict) else {}
    except Exception:
        logger.warning("dispatch metadata is not JSON: %.120s", raw)
        return {}


def _sip_attribute(ctx: JobContext, key: str) -> str:
    """One SIP participant attribute from the inbound caller (best effort)."""
    try:
        for participant in ctx.room.remote_participants.values():
            attrs = getattr(participant, "attributes", None) or {}
            value = attrs.get(key)
            if value:
                return str(value)
    except Exception as exc:
        logger.warning("sip attribute %s lookup failed: %s", key, exc)
    return ""


def caller_number(ctx: JobContext) -> str:
    """Who is calling. Only if the attribute is absent do we fall back to the
    digits in the room name (an individual rule names the room after them)."""
    number = _sip_attribute(ctx, "sip.phoneNumber")
    if number:
        return number
    name = getattr(ctx.room, "name", "") or ""
    digits = "".join(ch for ch in name if ch.isdigit() or ch == "+")
    return digits or "unknown"


def dialed_number(ctx: JobContext) -> str:
    """The number that was dialled — our DID. With a DID per owner this is what
    identifies whose assistant is answering, so the backend can bind the
    session to that owner."""
    return _sip_attribute(ctx, "sip.trunkPhoneNumber")


def resolve_session(caller: str, session_id: str | None, owner_name: str,
                    dialed: str = "", room: str = "") -> str | None:
    """Ask the backend for this call's session (create or reuse). None on any
    failure, so the voice chat still runs even if the backend is unreachable."""
    try:
        payload = _json.dumps({
            "caller": caller,
            "session_id": session_id,
            "owner_name": owner_name,
            "dialed": dialed,
            # The room we actually landed in: a SIP rule names it after the
            # caller, so the app cannot derive it — it joins this one.
            "room": room,
            "language": "auto",
        }).encode()
        req = urllib.request.Request(
            BACKEND + "/calls/inbound",
            data=payload,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=25) as r:
            data = _json.load(r)
        sid = data.get("session_id")
        if sid:
            logger.info("session %s (%s)", sid,
                        "reused" if data.get("reused") else "created")
        return sid
    except Exception as exc:
        logger.warning("inbound session resolve failed: %s", exc)
        return None


def post_turn(state: CallState, text: str) -> None:
    """Mirror the caller turn into our backend call session. Stops posting
    after the session ends (404) — the voice chat continues regardless."""
    if not state.post_ok or not state.sid:
        return
    try:
        req = urllib.request.Request(
            f"{BACKEND}/calls/{state.sid}/transcript",
            data=_json.dumps({"text": text, "speaker": "caller"}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=25):
            pass
    except urllib.error.HTTPError as e:
        if e.code == 404:
            state.post_ok = False
    except Exception as exc:
        logger.warning("turn post failed: %s", exc)


def score_risk(text: str) -> dict | None:
    """One turn through the production risk engine. None on any failure."""
    try:
        req = urllib.request.Request(
            BACKEND + "/analyze/text",
            data=_json.dumps({"text": text, "channel": "SMS",
                              "language": "auto"}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=25) as r:
            d = _json.load(r)
        return {
            "score": d.get("risk", 0),
            "level": d.get("level", "LOW"),
            "signals": [s.get("code", "?") for s in d.get("signals", [])],
            "simple": d.get("simple_mode") or d.get("user_message") or "",
        }
    except Exception as exc:
        logger.warning("risk engine unreachable: %s", exc)
        return None


def system_prompt(owner: str, caller: str) -> str:
    """The whole personality. This is the difference between a phone tree and
    someone who sounds like a person: it names the caller, teaches turn-taking
    and repairs, and keeps the machine's job invisible."""
    who = owner.strip() or "the person who owns this number"
    from_number = f" The caller's number is {caller}." if caller and caller != "unknown" else ""
    return (
        f"You are Intercept, answering this call for {who}. You sound like a real "
        "person on the phone: warm, quick, unhurried. You are not a script, not a "
        "call-centre bot, and you never read out anything that sounds rehearsed."
        f"{from_number}\n\n"
        "How you talk:\n"
        "- Match the caller's language, accent and register automatically — Hindi "
        "for Hindi, Hinglish for Hinglish, English for English — and switch "
        "mid-sentence if they do. Never announce or name the language.\n"
        "- One short sentence per turn; people do not monologue on the phone. "
        "React first (\"haan\", \"samajh gaya\"), then answer or ask.\n"
        "- Vary your wording every time. Never repeat a question you already asked.\n"
        "- If you are interrupted, stop instantly and listen. Do not finish the "
        "sentence you were in.\n"
        "- Light natural fillers (\"ek second\", \"hmm\") are fine, sparingly. "
        "Never fake an emotion you do not have.\n"
        "- If you did not catch something, say so plainly and ask again — like a "
        "real person, not like a machine that guesses.\n\n"
        "What you are doing:\n"
        "- Find out who is calling and what they want, the way a calm receptionist "
        f"would. {who} is not available right now, and that is all you say about them.\n"
        "- If the caller asks for an OTP, PIN, password, card details, money or "
        "remote access, refuse plainly and say it looks like a scam.\n"
        "- If they push again after a refusal, stay calm, get shorter, and move to "
        "close the call.\n"
        "- Never mention risk scores, systems, policies, recordings or that "
        "anything is analysing the call. Those do not exist to the caller."
    )


class InterceptAgent(voice.Agent):
    def __init__(self, owner: str = "", caller: str = "") -> None:
        super().__init__(instructions=system_prompt(owner, caller))


async def entrypoint(ctx: JobContext):
    await ctx.connect()
    meta = job_metadata(ctx)
    caller = str(meta.get("caller") or "") or caller_number(ctx)
    owner_name = str(meta.get("owner_name") or meta.get("owner") or "")
    room_name = getattr(ctx.room, "name", "") or ""
    # A room WE brought the agent into is named `intercept-<session id>` (the app
    # asks the backend for exactly that room), while a SIP rule names the room
    # after the CALLER. Offering the suffix as a candidate session id is safe on
    # both paths: the backend reuses it only when that session exists and is
    # still active, so a phone number in the room name simply misses.
    #
    # Without this, a call the app itself delivered into LiveKit posts its turns
    # into a SECOND session — the AI speaks, and the app's screen stays empty.
    candidate_sid = (room_name[len(ROOM_PREFIX):]
                     if room_name.startswith(ROOM_PREFIX) else None)
    sid = resolve_session(
        caller,
        session_id=meta.get("session_id") or candidate_sid,
        owner_name=owner_name,
        # Prefer the dialled DID from the trunk; metadata can override for tests.
        dialed=str(meta.get("dialed") or "") or dialed_number(ctx),
        room=room_name,
    )
    if not sid:
        # Backend unreachable: fall back to the room-name convention so the app's
        # own session still catches the turns (a SIP room name simply won't
        # match any session, which is fine — the report is then session-less).
        sid = candidate_sid
    state = CallState(sid)

    session = voice.AgentSession(
        # Native audio (speech in, speech out) — the same shape as ChatGPT's
        # advanced voice: no text round-trip, so tone and barge-in survive.
        llm=GeminiLive(
            model=MODEL,
            voice=VOICE,
        ),
    )

    @session.on("user_input_transcribed")
    def on_transcript(event: voice.UserInputTranscribedEvent):
        if not event.is_final:
            return
        text = (event.transcript or "").strip()
        if not text:
            return
        print(f"\nCALLER: {text}", flush=True)
        post_turn(state, text)
        res = score_risk(text)
        if res is None:
            return
        print(f"RISK: {res['level']} {res['score']} [{', '.join(res['signals'])}]",
              flush=True)
        if res["score"] >= 60:
            print("\n🚨 HIGH RISK — probable scam", flush=True)
            if res["simple"]:
                print(res["simple"], flush=True)
        if res["level"].upper() == "CRITICAL" and not state.closing_sent:
            state.closing_sent = True
            print("\n🛑 CRITICAL — delivering the closing line", flush=True)
            asyncio.create_task(session.generate_reply(instructions=CRITICAL_CLOSING))

    # Log the room we actually landed in: with SIP this is the easiest way to see
    # that LiveKit engaged at all (and which room the app must join to watch).
    logger.info(
        "agent joining %s for caller %s (session %s)",
        getattr(ctx.room, "name", "?"),
        caller,
        sid or "unlinked",
    )
    await session.start(
        room=ctx.room,
        agent=InterceptAgent(owner_name, caller),
        **_start_kwargs(),
    )
    who = owner_name.strip() or "the owner of this number"
    await session.generate_reply(
        instructions=(
            f"Open the call the way a real person would: one short, natural line "
            f"greeting them, say you are answering for {who} and they are not "
            f"available right now, then ask who they are and what they need. "
            "Pick Hindi, Hinglish or English to match how they greeted you. "
            "Do not introduce yourself as an AI and do not list options."
        )
    )


if __name__ == "__main__":
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint,
                              agent_name="intercept-agent"))
