"""CI helper: tag vX.Y.Z -> changelog entries in BOTH update feeds.

Run on tag pushes only. Reads version_code from Gradle (source of truth),
version_name from the tag, notes from commits since the previous tag.
Prints "true" when files changed (workflow commits + pushes back),
"false" when the entry already exists (idempotent: no commit, no loop).

Local test: GITHUB_REF_NAME=v9.9.9 python scripts/release_metadata.py
then `git checkout -- intercept-backend/app/updates.json
intercept-website/updates.json` to revert the trial entry.
"""
from __future__ import annotations

import datetime
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK_URL = ("https://github.com/aniketjha348/intercept/releases"
           "/latest/download/app-debug.apk")


def _git(*args: str) -> str:
    try:
        out = subprocess.run(["git", *args], capture_output=True, text=True,
                             cwd=ROOT, timeout=30)
        return out.stdout.strip() if out.returncode == 0 else ""
    except Exception:
        return ""


def commit_notes(tag: str) -> list[str]:
    tags = [t for t in _git("tag", "--sort=-creatordate").splitlines() if t]
    prev = next((t for t in tags if t != tag), "")
    rng = f"{prev}..{tag}" if prev else tag
    subjects = [ln.strip() for ln in _git("log", rng, "--pretty=%s").splitlines()
                if ln.strip() and not ln.strip().lower().startswith("merge ")]
    notes = [s[:120] for s in subjects[:6]] or [f"Release {tag}"]
    return notes


def load(path: Path) -> list:
    return json.loads(path.read_text(encoding="utf-8"))


def save(path: Path, items: list) -> None:
    path.write_text(json.dumps(items, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")


def main() -> None:
    tag = os.environ.get("GITHUB_REF_NAME", "")
    m = re.fullmatch(r"v(\d+\.\d+\.\d+)", tag or "")
    if not m:
        print("false")
        print(f"not a version tag: {tag!r}", file=sys.stderr)
        return
    version_name = m.group(1)
    gradle = (ROOT / "intercept-android/app/build.gradle.kts").read_text(encoding="utf-8")
    code_m = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not code_m:
        print("false")
        print("versionCode not found", file=sys.stderr)
        return
    version_code = int(code_m.group(1))

    backend_p = ROOT / "intercept-backend/app/updates.json"
    website_p = ROOT / "intercept-website/updates.json"
    backend = load(backend_p)
    if any(e.get("version_code") == version_code for e in backend):
        print("false")  # already recorded — no commit, no loop
        return

    notes_en = commit_notes(tag)
    entry = {
        "version_code": version_code,
        "version_name": version_name,
        "date": datetime.date.today().isoformat(),
        "force": False,
        "notes_en": notes_en,
        "notes_hi": ["Is version me taze fixes aur sudhaar",
                     "App kholte hi update mil jayega"],
    }
    backend.append(entry)
    save(backend_p, backend)

    website = load(website_p)
    web_entry = {
        "version_code": version_code,
        "version_name": version_name,
        "date": entry["date"],
        "force": False,
        "apk_url": APK_URL,
        "notes_en": entry["notes_en"],
        "notes_hi": entry["notes_hi"],
    }
    website.append(web_entry)
    save(website_p, website)
    print("true")


if __name__ == "__main__":
    main()
