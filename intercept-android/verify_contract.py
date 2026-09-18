"""Sanity checks without the Android SDK: package/path match + backend contract match."""
import re
import sys
from pathlib import Path

ANDROID = Path("D:/intercept/intercept-android/app/src/main/java/com/intercept")
BACKEND = Path("D:/intercept/intercept-backend")
failures = []


def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        failures.append(name)


# 1. package declaration matches file path
kt_files = list(ANDROID.rglob("*.kt"))
check("kt files present", len(kt_files) >= 20, f"found {len(kt_files)}")
for f in kt_files:
    rel = f.relative_to(ANDROID.parent.parent).with_suffix("")
    expected_pkg = ".".join(rel.parts[:-1])
    text = f.read_text(encoding="utf-8")
    m = re.search(r"^package\s+([\w.]+)", text, re.M)
    check(f"pkg {f.name}", m and m.group(1) == expected_pkg,
          f"got {m.group(1) if m else None}, want {expected_pkg}")

# 2. Retrofit routes exist in backend
api = (ANDROID / "data/api/InterceptApiService.kt").read_text()
routes = re.findall(r'@(?:GET|POST)\("([^"]+)"\)', api)
backend_code = "\n".join(
    p.read_text(encoding="utf-8") for p in
    list((BACKEND / "app/api").glob("*.py")) + list((BACKEND / "app/realtime").glob("*.py"))
    + [BACKEND / "app/main.py"])
for r in routes:
    key = r.replace("{id}", "").strip("/")
    check(f"route {r}", key.split("/")[0] in backend_code and key.split("/")[-1] in backend_code, key)

# 3. DTO field names exist backend-side
dto = (ANDROID / "data/api/Dto.kt").read_text()
serials = set(re.findall(r'@SerialName\("([^"]+)"\)', dto))
schemas = "\n".join(
    p.read_text(encoding="utf-8") for p in (BACKEND / "app").rglob("*.py"))
for s in serials:
    check(f"field {s}", s in schemas, "missing backend-side")

# 4. WS event names match backend
ws = (ANDROID / "data/api/CallWebSocket.kt").read_text()
ws_backend = (BACKEND / "app/realtime/websocket.py").read_text()
for ev in re.findall(r'"([A-Z_]{4,})"', ws):
    if ev in ("UTF",):
        continue
    check(f"ws-event {ev}", ev in ws_backend, "missing backend-side")

# 5. Screens referenced by NavGraph all exist
nav = (ANDROID / "presentation/navigation/NavGraph.kt").read_text()
for screen in ["HomeScreen", "IncomingCallScreen", "LiveCallScreen", "AnalyzeScreen", "ReportsScreen", "SettingsScreen"]:
    check(f"screen {screen}", (ANDROID / "presentation").rglob(f"{screen}.kt") and screen in nav)

print(f"\n{len(failures)} failures out of checks above.")
sys.exit(1 if failures else 0)
