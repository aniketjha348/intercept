#!/usr/bin/env python3
"""End-to-end check: does a forwarded call actually reach the live AI agent?

Three questions, three modes:

  (default)   Is the wiring in place? The LiveKit inbound trunk + dispatch rule,
              the backend's forwarding number, and the join token the app gets.
              Read-only — safe to run any time, changes nothing.
  --simulate  Push the REAL worker (`intercept-agent`) into a test room, prove it
              joins, prove the app can be handed a token for that exact room, and
              prove the agent can link its session on the backend. Cleans up after
              itself.
  --watch N   You dial the number; the script waits up to N seconds and reports
              whether the call landed in LiveKit, whether the caller's SIP line
              and the agent are both in the room, and whether the caller's turns
              reach our risk engine.

Why the call is never placed *by* this script: that needs a phone. Everything up
to the dial is mechanical, and --watch closes the last gap by watching the cloud
for the call you place.

Why mint the JWT by hand: the SIP service rejects a plain video grant with
"permissions denied" — admin calls need a `sip.admin` claim. That one detail is
the difference between "the API says 401" and seeing your own trunks.

Stdlib only: this runs on the demo laptop next to agent.py, with no venv.
Exit code is 0 only when nothing FAILed, so it can gate a demo.

    python scripts/e2e_livekit.py
    python scripts/e2e_livekit.py --simulate
    python scripts/e2e_livekit.py --watch 120
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import pathlib
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# A Windows console is cp1252 by default, where one '->' arrow in a diagnostic —
# or a caller's name in Devanagari — kills the run with UnicodeEncodeError. The
# output IS the product here, so make it UTF-8 and never let an encoding gap hide
# a FAIL. (errors="replace": a stray glyph degrades to '?', it never aborts.)
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - exotic stdout (pipes, frozen apps)
    pass

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENT_DIR = ROOT / "livekit-agent"
AGENT_ENV = AGENT_DIR / ".env.local"


def venv_python() -> str:
    """The interpreter that actually runs the worker.

    agent.py is started from `livekit-agent/.venv`, but this script runs on
    whatever `python` the shell resolves. Checking the wrong interpreter reports
    "SDK not installed" on a machine where the worker is perfectly installed —
    and sends you reinstalling something that is already there.
    """
    for rel in ("Scripts/python.exe", "Scripts/python", "bin/python"):
        p = AGENT_DIR / ".venv" / rel
        if p.exists():
            return str(p)
    return sys.executable


def reexec_in_venv() -> None:
    """Continue this run under the venv interpreter, once, when it has the SDK.

    Re-exec instead of shelling out per check: --simulate and --watch build on
    the same imports, and a run that mixes two interpreters is a run whose PASS
    means nothing.
    """
    if os.environ.get("INTERCEPT_E2E_REEXEC") == "1":
        return
    py = venv_python()
    if py == sys.executable:
        return
    try:
        import livekit.agents  # noqa: F401

        return  # this interpreter already has the SDK — stay here
    except Exception:
        pass
    os.environ["INTERCEPT_E2E_REEXEC"] = "1"
    os.execv(py, [py, str(pathlib.Path(__file__).resolve()), *sys.argv[1:]])
BACKEND_ENV = ROOT / "intercept-backend" / ".env"

# Must match `cli.run_app(WorkerOptions(..., agent_name=...))` in agent.py and the
# dispatch rule's roomConfig.agents[].agentName. A typo here means the call rings
# and nobody answers — the single most common way this pipeline "doesn't work".
AGENT_NAME = "intercept-agent"

# The room prefix the rule must use. The app asks the backend for a token for
# `intercept-<session id>`; agent.py can recover a session id out of a room name
# only when it starts with this. Since the backend hands back the room the agent
# actually landed in, a mismatch degrades instead of breaking — but it degrades.
ROOM_PREFIX = "intercept-"

E164 = re.compile(r"^\+?[0-9]{7,15}$")

PASS, FAIL, WARN, INFO = "PASS", "FAIL", "WARN", "INFO"
_failed = 0


def report(status: str, name: str, detail: str = "") -> None:
    global _failed
    if status == FAIL:
        _failed += 1
    print(f"{status:5} {name}")
    for line in str(detail).splitlines():
        if line.strip():
            print(f"      {line}")


def section(title: str) -> None:
    print(f"\n{title}\n{'-' * len(title)}")


def env_file(path: pathlib.Path) -> dict:
    """KEY=VALUE from a dotenv file. Missing file = empty dict, never a crash."""
    data: dict[str, str] = {}
    try:
        text = path.read_text(encoding="utf-8-sig")
    except OSError:
        return data
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        data[key.strip()] = value.strip().strip('"').strip("'")
    return data


def http_json(method: str, url: str, body=None, headers=None, timeout: int = 25):
    """(status, parsed-body-or-text). Never raises: the caller reports failure."""
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode() or "{}"
            try:
                return resp.status, json.loads(raw)
            except ValueError:
                return resp.status, raw
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode()
        try:
            return exc.code, json.loads(raw)
        except ValueError:
            return exc.code, raw
    except Exception as exc:  # DNS, TLS, no network…
        return None, str(exc)


def b64url(raw: bytes) -> bytes:
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


class LiveKit:
    """Just enough of the server API, signing our own tokens."""

    def __init__(self, ws_url: str, key: str, secret: str) -> None:
        self.key, self.secret = key, secret
        self.base = (ws_url.replace("wss://", "https://")
                     .replace("ws://", "http://").rstrip("/"))

    def _jwt(self, sip: bool, room: str = "") -> str:
        """[room] scopes the video grant to ONE room.

        Agent dispatch refuses a room-scoped call made with a wildcard grant
        (`video.room = ""`, even with roomAdmin): it answers 401 'permissions
        denied', which reads like bad credentials and is really a missing scope.
        """
        now = int(time.time())
        claims = {
            "iss": self.key,
            "sub": "intercept-e2e",
            "jti": "intercept-e2e",
            "nbf": now - 30,
            "exp": now + 900,
            "video": {"roomJoin": True, "room": room, "roomAdmin": True,
                      "roomCreate": True, "roomList": True},
        }
        if sip:
            claims["sip"] = {"admin": True, "call": True}
        head = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
        body = b64url(json.dumps(claims).encode())
        sig = hmac.new(self.secret.encode(), head + b"." + body, hashlib.sha256).digest()
        return b".".join((head, body, b64url(sig))).decode()

    def call(self, service: str, method: str, body: dict, sip: bool = False,
             room: str = ""):
        return http_json(
            "POST", f"{self.base}/twirp/livekit.{service}/{method}", body,
            {"Authorization": f"Bearer {self._jwt(sip, room)}"},
        )


class Backend:
    def __init__(self, base: str) -> None:
        self.base = (base or "").rstrip("/")

    def get(self, path: str, params: dict | None = None):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        return http_json("GET", url)

    def post(self, path: str, body: dict | None = None):
        return http_json("POST", self.base + path, body or {})


# ---------------------------------------------------------------- LiveKit state

def rule_agent(rule: dict) -> str:
    agents = ((rule.get("room_config") or {}).get("agents")) or []
    return str(agents[0].get("agent_name") or "") if agents else ""


def rule_room_prefix(rule: dict) -> str:
    inner = rule.get("rule") or {}
    for key in ("dispatch_rule_individual", "dispatch_rule_direct", "dispatch_rule_callee"):
        block = inner.get(key)
        if isinstance(block, dict) and block.get("room_prefix"):
            return str(block["room_prefix"])
    return ""


def list_participants(lk: LiveKit, room: str) -> list:
    status, res = lk.call("RoomService", "ListParticipants", {"room": room})
    if status != 200 or not isinstance(res, dict):
        return []
    return res.get("participants") or res.get("items") or []


def p_kind(p: dict) -> str:
    """Participant kind as a NAME.

    LiveKit's Twirp JSON encodes enums by name, not by number — a real answer
    looks like {"kind": "AGENT"}, never {"kind": 4}. Comparing against the proto
    numbers matches nothing and reads as "the worker never joined" while it is
    sitting in the room.
    """
    k = p.get("kind")
    if isinstance(k, str):
        return k.upper()
    return {0: "STANDARD", 1: "INGRESS", 2: "EGRESS", 3: "SIP", 4: "AGENT"}.get(k, "")


def wait_for_agent(lk: LiveKit, room: str, seconds: int) -> str:
    """The agent appears as kind=AGENT; identity is a fallback signal."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        for p in list_participants(lk, room):
            if p_kind(p) == "AGENT" or str(p.get("identity") or "").startswith("agent"):
                return f"{p.get('identity') or 'agent'} (kind={p.get('kind')})"
        time.sleep(2)
    return ""


def check_env_agreement(agent_env: dict, backend_env: dict) -> None:
    if AGENT_ENV.exists():
        report(PASS, "livekit-agent/.env.local exists")
    else:
        report(FAIL, "livekit-agent/.env.local exists",
               "the worker cannot join a room without LIVEKIT_URL / LIVEKIT_API_KEY / "
               "LIVEKIT_API_SECRET. Copy .env.example and fill it in.")

    if not backend_env:
        report(WARN, "backend .env readable",
               "skipped — the deployed backend is checked live further down. The two "
               "sides only have to agree on which project (key/url/secret) they use.")
        return

    # The names differ (the agent SDK's vs. ours) but the values are the same.
    for agent_key, backend_key in (("LIVEKIT_URL", "LIVEKIT_URL"),
                                   ("LIVEKIT_API_KEY", "LIVEKIT_KEY"),
                                   ("LIVEKIT_API_SECRET", "LIVEKIT_SECRET")):
        left = agent_env.get(agent_key, "").rstrip("/")
        right = backend_env.get(backend_key, "").rstrip("/")
        if not left or not right:
            report(WARN, f"{agent_key} == backend {backend_key}",
                   f"agent={'set' if left else 'missing'} backend={'set' if right else 'missing'}")
        elif left != right:
            report(FAIL, f"{agent_key} == backend {backend_key}",
                   "the agent and the app tokens must come from ONE LiveKit project, "
                   "or the agent is never in the room the app joins.")
        else:
            report(PASS, f"{agent_key} == backend {backend_key}")


def check_livekit(lk: LiveKit) -> tuple[list, dict]:
    """Returns (inbound trunks, the dispatch rule aimed at our agent)."""
    status, res = lk.call("RoomService", "ListRooms", {})
    if status != 200:
        report(FAIL, "LiveKit API reachable", f"RoomService/ListRooms -> {status} {res}")
        return [], {}
    rooms = res.get("rooms", []) if isinstance(res, dict) else []
    report(PASS, "LiveKit API reachable", f"{len(rooms)} room(s) open right now")

    status, res = lk.call("SIP", "ListSIPInboundTrunk", {"pageSize": 50}, sip=True)
    trunks = res.get("items") or [] if (status == 200 and isinstance(res, dict)) else []
    if status != 200:
        report(FAIL, "SIP admin API reachable", f"ListSIPInboundTrunk -> {status} {res}")
    elif not trunks:
        # Docs: "If you're using LiveKit Phone Numbers, you do not need to create
        # an inbound trunk." A number bought from LiveKit is bound to the rule
        # directly, so an empty trunk list is the NORMAL case — the check below
        # on the rule (PN_… id) is what decides whether calls can arrive.
        report(WARN, "an inbound trunk exists",
               "no inbound trunk. Expected with LiveKit Phone Numbers (they are "
               "attached to the rule directly) — only a problem when your calls "
               "arrive through a trunk you configured with your own carrier.")
    else:
        report(PASS, "an inbound trunk exists", f"{len(trunks)} trunk(s)")

    status, res = lk.call("SIP", "ListSIPDispatchRule", {"pageSize": 50}, sip=True)
    rules = res.get("items") or [] if (status == 200 and isinstance(res, dict)) else []
    ours = [r for r in rules if rule_agent(r) == AGENT_NAME]
    if not ours:
        report(FAIL, f"a dispatch rule routes to '{AGENT_NAME}'",
               f"found {len(rules)} rule(s), none for '{AGENT_NAME}'.\n"
               "Telephony -> Dispatch rules -> roomConfig.agents[].agentName must be "
               f"exactly '{AGENT_NAME}' (the name agent.py registers with).")
        return trunks, {}

    rule = ours[0]
    report(PASS, f"a dispatch rule routes to '{AGENT_NAME}'",
           rule.get("name") or rule.get("sip_dispatch_rule_id", ""))

    pn_ids = phone_number_ids(rule)
    if pn_ids:
        report(PASS, "the rule is bound to a LiveKit phone number",
               f"{', '.join(pn_ids)} — LiveKit-managed, so it is not listed as an "
               "inbound trunk. The DID itself lives in Telephony -> Phone numbers.")

    prefix = rule_room_prefix(rule)
    if prefix == ROOM_PREFIX:
        report(PASS, f"the rule names rooms '{ROOM_PREFIX}<caller>'")
    else:
        # Not a failure: an individual rule names the room after the CALLER, and
        # the agent reports the room it landed in (/calls/inbound -> session.room,
        # served by /calls/live) so the app joins that room instead of guessing.
        report(PASS, "room naming is carried by the session, not the prefix",
               f"rooms look like '{prefix or ''}<caller-number><random>' (docs: "
               "individual rule). The app must join the room the agent reports, "
               "never its own 'intercept-<session id>' guess.")

    known = {t.get("sip_trunk_id") for t in trunks}
    known |= {n for t in trunks for n in (t.get("numbers") or [])}
    trunk_ids = rule.get("trunk_ids") or []
    if not trunk_ids and not (rule.get("numbers") or []):
        report(WARN, "the rule is bound to a trunk or number",
               "trunk_ids and numbers are both empty — the rule has nothing to match.")
    for tid in trunk_ids:
        if str(tid).startswith("PN_"):
            # A LiveKit phone number is not an inbound trunk: GetSIPInboundTrunk
            # answers 404 for it even while the number is live and answering.
            report(PASS, f"rule number '{tid}' is LiveKit-managed",
                   "cannot be resolved through the trunk API by design — the real "
                   "proof is a call landing (--watch while you dial).")
            continue
        if tid in known:
            report(PASS, f"rule trunk '{tid}' resolves")
            continue
        status, _ = lk.call("SIP", "GetSIPInboundTrunk", {"sip_trunk_id": tid}, sip=True)
        if status == 200:
            report(PASS, f"rule trunk '{tid}' resolves")
        else:
            report(FAIL, f"rule trunk '{tid}' resolves",
                   "the dispatch rule points at a trunk that no longer exists, so an "
                   "incoming call has nothing to arrive on. Recreate the trunk and "
                   "re-attach it to the rule.")

    if rule.get("krisp_enabled"):
        report(WARN, "noise cancellation runs in exactly one place",
               "the rule has Krisp ON and agent.py also applies Krisp NC. Two stacked "
               "models make a narrowband line sound worse, not better — pick one.")
    else:
        report(PASS, "noise cancellation is not stacked",
               "rule krisp_enabled=false; the agent applies it (room_options)")

    if not (rule.get("room_config") or {}).get("metadata"):
        report(WARN, "the rule carries routing metadata",
               "room_config.metadata is empty, so the worker gets no owner_name and "
               "says 'the person who owns this number' instead of the owner's name. "
               "Optional: put {\"owner_name\":\"...\"} in the rule's roomConfig.")
    return trunks, rule


def phone_number_ids(rule: dict) -> list:
    """LiveKit Phone Numbers appear on a rule as `PN_…` ids, not as trunks.

    Docs (Telephony -> Accepting calls -> Inbound trunk): "If you're using
    LiveKit Phone Numbers, you do not need to create an inbound trunk." So a rule
    bound to a PN_ id is a complete, working inbound path — and is also why the
    number cannot be listed through ListSIPInboundTrunk.
    """
    return [str(t) for t in (rule.get("trunk_ids") or []) if str(t).startswith("PN_")]


def attached_numbers(trunks: list, rule: dict) -> list:
    numbers = [n for t in trunks for n in (t.get("numbers") or [])]
    numbers += list(rule.get("numbers") or []) + list(rule.get("inbound_numbers") or [])
    return [n for n in numbers if n]


# ------------------------------------------------------------------- the backend

def check_backend(api: Backend, agent_url: str) -> str:
    """Returns the DID the owner's calls should be forwarded to ("" = unknown)."""
    status, _ = api.get("/health")
    report(PASS if status == 200 else FAIL, "backend is up", f"/health -> {status}")

    status, fwd = api.get("/assistant/forwarding")
    did = ""
    if status != 200 or not isinstance(fwd, dict):
        report(FAIL, "GET /assistant/forwarding", f"-> {status} {fwd}")
    elif not fwd.get("configured"):
        report(FAIL, "the backend knows a forwarding number",
               "configured=false: the app's 'AI answers your calls' screen says "
               "'not set up on the server yet', so forwarding can never be armed and "
               "no call is ever handed to LiveKit.\n"
               "Fix on the deployed backend: ASSISTANT_FORWARD_NUMBER=<your DID>\n"
               "  terraform apply -var=\"assistant_forward_number=+1XXXXXXXXXX\"\n"
               "  (or per owner: POST /assistant/forwarding {\"number\":\"+1...\"})")
    else:
        did = str(fwd.get("number") or "")
        if E164.match(did):
            report(PASS, "the backend knows a forwarding number", f"{did} (source: {fwd.get('source')})")
        else:
            report(FAIL, "the backend knows a forwarding number", f"'{did}' is not dialable E.164")
        code = str(fwd.get("busy_activate") or "")
        if did and did.lstrip("+") in code.replace("+", ""):
            report(PASS, "the USSD arm code points at that number", code)
        else:
            report(FAIL, "the USSD arm code points at that number",
                   f"busy_activate='{code}' — the phone would dial a code that arms "
                   "forwarding to somewhere else.")

    if did:
        status, owner = api.get("/assistant/forwarding/owner", {"number": did})
        who = (owner or {}).get("user_id") if isinstance(owner, dict) else ""
        if status == 200 and who:
            report(PASS, "the DID reverse-maps to an owner", who)
        else:
            report(WARN, "the DID reverse-maps to an owner",
                   "nobody is bound to this number, so the agent cannot say whose "
                   "assistant is answering (it still answers). Bind it with "
                   "POST /assistant/forwarding + X-User-Id for a per-owner DID.")

    status, token = api.get("/livekit/token", {"identity": "e2e"})
    room = (token or {}).get("room") if isinstance(token, dict) else ""
    url = (token or {}).get("url", "") if isinstance(token, dict) else ""
    if status == 200 and room:
        if url.rstrip("/") == agent_url.rstrip("/"):
            report(PASS, "the app can mint a LiveKit join token", f"room {room}")
        else:
            report(FAIL, "the app can mint a LiveKit join token",
                   f"token url '{url}' != agent LIVEKIT_URL '{agent_url}' — two projects.")
    else:
        report(FAIL, "the app can mint a LiveKit join token", f"-> {status} {token}")
    return did


# ------------------------------------------------------------ this machine (host)

def check_host(agent_env: dict) -> None:
    where = "venv" if sys.executable == venv_python() else f"system python ({sys.executable})"
    try:
        import livekit.agents as agents_pkg

        report(PASS, "the worker SDK is installed",
               f"livekit-agents {getattr(agents_pkg, '__version__', '?')} in the {where}")
    except Exception as exc:
        report(FAIL, "the worker SDK is installed",
               f"{type(exc).__name__}: {exc} ({where})\n"
               "  cd livekit-agent && python -m venv .venv && "
               ".venv/Scripts/python -m pip install -r requirements-agent.txt")

    try:
        from livekit.plugins import noise_cancellation  # noqa: F401

        report(PASS, "noise suppression plugin installed",
               "Krisp NC will clean the 8 kHz phone audio before the model hears it")
    except Exception:
        report(WARN, "noise suppression plugin installed",
               "pip install -r livekit-agent/requirements-agent.txt — without it the "
               "realtime model hears raw line hiss as if it were speech.")

    # agent.py already resolves this defensively; importing it proves the file the
    # worker will actually run imports cleanly on THIS machine's plugin versions.
    sys.path.insert(0, str(AGENT_DIR))
    try:
        import agent as agent_module  # noqa: E402

        symbol = getattr(agent_module, "GeminiLive", None)
        if symbol is None:
            report(FAIL, "agent.py imports and finds the Gemini realtime class",
                   "no GeminiLive symbol — agent.py is not the file you think it is")
        else:
            report(PASS, "agent.py imports and finds the Gemini realtime class",
                   f"{symbol.__module__}.{symbol.__name__}")
        report(INFO, "worker defaults",
               f"agent_name={AGENT_NAME} model={agent_module.MODEL} voice={agent_module.VOICE}")
    except Exception as exc:
        report(FAIL, "agent.py imports", f"{type(exc).__name__}: {exc}")

    key = agent_env.get("GOOGLE_API_KEY", "")
    report(PASS if key else FAIL, "GOOGLE_API_KEY is set for the worker",
           "" if key else "the worker cannot reach Gemini without it")


# -------------------------------------------------------------------- simulate

def simulate(lk: LiveKit, api: Backend, did: str) -> None:
    """Prove the agent half without needing a phone."""
    room = f"{ROOM_PREFIX}e2e-{secrets.token_hex(3)}"
    status, res = lk.call("RoomService", "CreateRoom", {"name": room, "emptyTimeout": 120})
    if status != 200:
        report(FAIL, "test room created", f"-> {status} {res}")
        return
    report(PASS, "test room created", room)

    # The Python SDK method is `agent_dispatch.create_dispatch`, but the Twirp
    # method is plain `CreateDispatch` — asking for `CreateAgentDispatch` 404s as
    # a bad route, which reads like "agent dispatch does not exist".
    status, res = lk.call("AgentDispatchService", "CreateDispatch",
                          {"room": room, "agentName": AGENT_NAME}, room=room)
    if status != 200:
        report(FAIL, "the agent was dispatched", f"-> {status} {res}")
        lk.call("RoomService", "DeleteRoom", {"room": room})
        return
    dispatch_id = str((res or {}).get("id") or "")
    report(PASS, "the agent was dispatched", "waiting for the worker to join…")

    # 90s, not 45: the worker's FIRST job after a cold start downloads its
    # inference models, and a short window times out just before the join lands —
    # reporting "the worker never showed up" for a worker that is joining fine.
    joined = wait_for_agent(lk, room, 90)
    if joined:
        report(PASS, "the worker joined the room", joined)
    else:
        report(FAIL, "the worker joined the room",
               f"'{AGENT_NAME}' never showed up in {room} (waited 90s). The worker is "
               "not registered or is still warming up:\n"
               "  cd livekit-agent && python agent.py dev   (keep it running)\n"
               "  the first job downloads models — wait for it, then re-run.\n"
               "and check it logged 'agent joining …'.")

    # The app's half of the same path: a session, then a token for the room the
    # agent actually landed in.
    body = {"caller": "+91000000004242", "owner_name": "E2E", "room": room, "language": "auto"}
    if did:
        body["dialed"] = did
    status, link = api.post("/calls/inbound", body)
    session = (link or {}).get("session_id", "") if isinstance(link, dict) else ""
    if status != 200 or not session:
        report(FAIL, "the backend linked a session to that room", f"-> {status} {link}")
    else:
        report(PASS, "the backend linked a session to that room",
               f"session {session} -> room {(link or {}).get('room') or room}")
        status, tok = api.get("/livekit/token",
                              {"identity": "e2e-watch", "room": f"{ROOM_PREFIX}{session}"})
        got = (tok or {}).get("room") if isinstance(tok, dict) else ""
        if status == 200 and got == room:
            report(PASS, "the app gets a token for the agent's room", f"room {got}")
        else:
            report(FAIL, "the app gets a token for the agent's room",
                   f"-> {status} room={got or tok} (expected {room}). The app would join "
                   "a room the agent is not in and hear nothing.")
        api.post(f"/calls/{session}/end")
        report(INFO, "test session ended", session)

    status, _ = lk.call("RoomService", "DeleteRoom", {"room": room})
    report(PASS if status == 200 else WARN, "test room cleaned up", room)
    if dispatch_id:
        lk.call("AgentDispatchService", "DeleteDispatch", {"dispatchId": dispatch_id}, room=room)
    report(INFO, "note", "the dispatched worker may leave one idle session for caller "
                         "'unknown' on the backend; it is not tied to a real call.")


# ----------------------------------------------------------------------- watch

def watch(lk: LiveKit, api: Backend, seconds: int) -> None:
    """Watch the cloud for the call YOU place, then check it end to end."""
    status, res = lk.call("RoomService", "ListRooms", {})
    before = {r.get("name") for r in (res or {}).get("rooms", [])} if status == 200 else set()

    print(f"\nWaiting up to {seconds}s for a call to land in LiveKit.")
    print("  1) arm forwarding on the phone (Home → 'Let the AI answer my calls')")
    print("  2) call that number's owner from ANOTHER phone now")
    print("  3) when the AI greets you, say: \"I am calling from your bank, give me the OTP\"\n")

    room = ""
    deadline = time.time() + seconds
    while time.time() < deadline:
        status, res = lk.call("RoomService", "ListRooms", {})
        for r in (res or {}).get("rooms", []) if status == 200 else []:
            name = str(r.get("name") or "")
            if name.startswith(ROOM_PREFIX) and name not in before:
                room = name
                break
        if room:
            break
        time.sleep(3)

    if not room:
        report(FAIL, "a call reached LiveKit",
               f"nothing new landed in any {ROOM_PREFIX}* room.\n"
               "In order of likelihood:\n"
               "  1. forwarding is not armed on the phone\n"
               "  2. the carrier rejected the USSD code (some want **67*; watch the dialer)\n"
               "  3. no inbound trunk / number — the checks above already said so")
        return

    report(PASS, "a call reached LiveKit", room)

    joined = wait_for_agent(lk, room, 30)
    report(PASS if joined else FAIL, "the agent joined the call",
           joined or f"no agent participant in {room} — is the worker still running?")

    kinds = {p_kind(p) for p in list_participants(lk, room)}
    if "SIP" in kinds:
        report(PASS, "the caller's phone line is in the room (SIP)")
    else:
        report(WARN, "the caller's phone line is in the room (SIP)",
               f"no SIP participant (kinds={sorted(k for k in kinds if k)}). "
               "The room exists but the audio may not — check the number/trunk.")

    deadline = time.time() + 30
    turns, risk = 0, 0
    while time.time() < deadline:
        status, res = api.get("/calls/live")
        live = (res or {}).get("live", []) if isinstance(res, dict) else []
        turns = max([int(s.get("turns") or 0) for s in live] or [0])
        risk = max([int(s.get("risk") or 0) for s in live] or [0])
        if turns > 0:
            break
        time.sleep(3)
    if turns > 0:
        report(PASS, "caller turns reached our risk engine", f"{turns} turn(s), peak risk {risk}")
    else:
        report(FAIL, "caller turns reached our risk engine",
               "the backend session has no turns. Either nobody spoke, or the worker "
               "never linked its session (POST /calls/inbound) — check the worker log "
               "for 'session …' and the backend for a reachable INTERCEPT_API.")


# ------------------------------------------------------------------------ main

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--simulate", action="store_true",
                       help="dispatch the real worker into a test room and verify the link")
    parser.add_argument("--watch", type=int, metavar="SECONDS", default=0,
                       help="wait this long for a call you place to land in LiveKit")
    parser.add_argument("--api", default="", help="override the backend base URL")
    args = parser.parse_args()
    reexec_in_venv()

    agent_env = env_file(AGENT_ENV)
    backend_env = env_file(BACKEND_ENV)
    if args.api:
        backend_env["__api__"] = args.api

    api_url = (backend_env.get("__api__")
               or agent_env.get("INTERCEPT_API")
               or "http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com")
    lk_url = agent_env.get("LIVEKIT_URL", "")
    lk_key = agent_env.get("LIVEKIT_API_KEY", "")
    lk_secret = agent_env.get("LIVEKIT_API_SECRET", "")

    print(f"backend : {api_url}")
    print(f"livekit : {lk_url or '(not configured in livekit-agent/.env.local)'}")

    section("Worker env and project agreement")
    check_env_agreement(agent_env, backend_env)
    if not (lk_url and lk_key and lk_secret):
        report(FAIL, "LiveKit credentials present in livekit-agent/.env.local",
               "fill LIVEKIT_URL / LIVEKIT_API_KEY / LIVEKIT_API_SECRET, then re-run.")
        return 1

    lk = LiveKit(lk_url, lk_key, lk_secret)
    api = Backend(api_url)

    section("LiveKit Cloud: trunk, rule, numbers")
    trunks, rule = check_livekit(lk)
    numbers = attached_numbers(trunks, rule)
    pn_ids = phone_number_ids(rule)
    if numbers:
        report(PASS, "a phone number is attached", ", ".join(numbers))
    elif pn_ids:
        # The number exists but only as an id here; the DID text comes from the
        # dashboard (Telephony -> Phone numbers) and has to match ASSISTANT_FORWARD_NUMBER.
        report(PASS, "a phone number is attached",
               f"LiveKit phone number {pn_ids[0]} — copy the E.164 DID from "
               "Telephony -> Phone numbers and set it on the backend "
               "(POST /assistant/forwarding or ASSISTANT_FORWARD_NUMBER).")
    else:
        report(FAIL, "a phone number is attached",
               "no number on the rule, so there is nothing to forward to — and "
               "nothing ASSISTANT_FORWARD_NUMBER could hold.")

    section("Backend: forwarding + join token")
    did = check_backend(api, lk_url)
    hint = did or (numbers[0] if numbers else "")
    if hint and did and hint != did:
        report(WARN, "the backend DID matches the project's number",
               f"backend says {did}, LiveKit says {hint}")

    section("This machine: the worker the call will land on")
    check_host(agent_env)

    if args.simulate:
        section("Simulate: dispatch the agent and check the app's link")
        simulate(lk, api, did)

    if args.watch:
        section("Watch: place the call now")
        watch(lk, api, args.watch)

    print()
    if _failed:
        print(f"{_failed} check(s) FAILED — the caller will not reach the AI yet.")
    else:
        print("All checks passed — a forwarded call reaches the AI.")
    if not (args.simulate or args.watch):
        print("Next: --simulate to prove the worker joins, --watch 120 while you dial.")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
