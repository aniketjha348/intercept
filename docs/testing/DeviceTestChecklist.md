# Device Test Checklist — Message Blocking, Threat Banner, Always-On

> **Version:** v0.5.2
> **Covers:** risky-message auto-dismiss, overlay threat banner, always-on daemon
> **Why this document exists:** none of the three paths can be unit tested. They
> depend on OS behaviour (notification access, overlay windows, process death,
> OEM battery policy) that only exists on a real device. `verify_contract.py`
> proves the routes and fields line up; this proves the feature actually works.

---

## 0. Before you start

### Thresholds these tests are built on

Everything below is written against the numbers in the code. If a test fails,
check the threshold before you call it a bug.

| Behaviour | Value | Where |
|---|---|---|
| Alert (warn only, message kept) | risk ≥ **25** | `RISK_THRESHOLD` |
| Auto-dismiss the notification | risk ≥ **60** | `BLOCK_THRESHOLD` |
| Same-sender re-alert cooldown | **10 min** | `COOLDOWN_MS` |
| Threat banner visible | **9 s** | `BANNER_MS` |
| Bubble heal heartbeat | **60 s** | `HEARTBEAT_MS` |
| Minimum message length scanned | 4 chars | `length < 4` guard |

### Setup preconditions

- [ ] Two phones (or one phone + one sender), both able to reach the backend
- [ ] INTERCEPT v0.5.2 installed and **Setup completed** (backend reachable)
- [ ] **Auto-scan app messages** ON (Settings → Auto-protect) — nothing scans without it
- [ ] **Notification access** granted (Settings → Notifications → Device & app notifications → INTERCEPT)
- [ ] **Display over other apps** granted, bubble toggle ON (for Path B only)
- [ ] WhatsApp/Telegram logged in on the test device, notifications **not** muted for that chat
- [ ] Battery unrestricted + Autostart ON if the device is Xiaomi/Vivo/Oppo (Path C)

### Helper commands

```bash
# is the daemon alive?
adb shell dumpsys activity services com.intercept | grep -i alwayson

# is the bubble alive?
adb shell dumpsys activity services com.intercept | grep -i overlayservice

# live logs for just this app
adb logcat --pid=$(adb shell pidof com.intercept) | grep -i -e intercept -e notify

# simulate a battery-saver kill window
adb shell dumpsys deviceidle force-idle && adb shell dumpsys deviceidle unforce
```

---

## 1. Step 0 — Calibrate the test link (do not skip)

You cannot test auto-dismiss with a link nobody agrees is dangerous. The backend
decides, so ask the backend first.

```bash
curl -s -X POST http://intercept-backend-1446503107.ap-south-1.elb.amazonaws.com/analyze/url \
  -H 'Content-Type: application/json' \
  -d '{"url":"http://sbi-kyc-verify.xyz/update","context":"Your KYC is expiring today, update now"}'
```

- [ ] Reply shows `"risk": 60` or higher → use this link for **Path A**
- [ ] Record the exact `risk` and `level` you got: `______`
- [ ] Open INTERCEPT → Analyze → URL, paste the same link
- [ ] Same score appears (this also proves the deep-link path works)

**Gate:** if the score is **25–59**, your link only tests warn-only behaviour.
Pick a stronger lookalike until you clear 60, otherwise every dismiss test
below will "fail" while the code is behaving exactly as designed.

---

## 2. Path A — Auto-dismiss of a risky message

Send the calibrated link as a WhatsApp (and once as Signal — see A8).

### A1 — Notification is removed by itself
- [ ] With the screen **on and unlocked in another app**, send the risky message
- [ ] Within a few seconds the chat notification **disappears on its own**
- [ ] You did not tap anything

**Expected:** notification gone. **If it fails:** is the score ≥ 60 and is auto-scan ON?

### A2 — The alert replaces it
- [ ] INTERCEPT alert appears: `🛡 Removed a risky message from <name> — <LEVEL> <risk>`
- [ ] Alert is high priority (heads-up / on lock screen)

### A3 — Alert tap lands in Analyze, prefilled
- [ ] Tap the alert
- [ ] Analyze opens on the **URL** tab with the link already verifying
- [ ] No typing, no pasting

### A4 — The safe path is not broken (the most important test here)
- [ ] Send a normal message with a normal link (e.g. a YouTube link)
- [ ] Notification **stays**, no alert, no banner
- [ ] Send a mildly suspicious message (calibrated to 25–59)
- [ ] You get the **warning** but the notification is **kept** — the message is never eaten

**Expected:** below 60 we warn and never touch the user's message. A false
positive that deletes a real message is worse than a missed scam.

### A5 — Cooldown
- [ ] From the **same** sender, send another risky message within 10 minutes
- [ ] No second alert
- [ ] After 10 minutes, another risky message **does** alert

### A6 — No self-loop
- [ ] After an alert fires, watch for a moment
- [ ] INTERCEPT never scans its own alert notification (no alert-about-alert)

### A7 — Respects the switch
- [ ] Turn **Auto-scan app messages** OFF
- [ ] Send a risky message → nothing happens at all
- [ ] Turn it back ON → works again

### A8 — Signal is actually scanned now
- [ ] Send the risky link over **Signal**
- [ ] Auto-dismiss + alert fire as in A1/A2

**Expected:** works. v0.5.2 fixed the package name — before this, Signal was
never scanned (`org.signal.private_messenger` is not Signal's real package).
If this fails, that fix regressed.

### A9 — Documented OS limits (expected to do nothing)
- [ ] **Mute** the chat, then send the risky message → no notification exists, so
      nothing is scanned. This is an Android limit, not a bug.
- [ ] Send a message while **the chat is open on screen** → often no notification
      is posted, so nothing to dismiss; the banner (Path B) is the only cover.
- [ ] Send a WhatsApp **incoming internet call** → you get the full-screen
      caution instead of a dismiss (calls are not messages)

---

## 3. Path B — Overlay threat banner

Needs the bubble toggle ON and display-over-other-apps granted.

### B1 — Banner appears over the current app
- [ ] Open a *different* app and keep it in the foreground
- [ ] Have the risky message arrive
- [ ] The dark warning card appears **over** that app, near the top

**Expected:** appears a few seconds after the message lands (the verdict needs a
backend round trip), and does not steal focus — you can keep typing in the app
underneath.

### B2 — Banner tap opens the check
- [ ] Tap the banner → Analyze opens with that link prefilled
- [ ] If there was no link, the banner still opens Analyze (message-only risk)

### B3 — Banner clears
- [ ] Leave it alone → it disappears after ~9 s with no trace

### B4 — Missed banner is not lost
- [ ] Let a banner fade without tapping
- [ ] Tap the **bubble** → panel shows the last threat heading (red) and a
      **Check that link** action

### B5 — Honest failure when the permission is missing
- [ ] Revoke display-over-other-apps, toggle bubble ON
- [ ] A toast explains the block (MIUI gets the Security-app wording)
- [ ] The bubble toggle flips back OFF — state never lies
- [ ] Messages still get scanned: the alert notification still fires (banner is
      optional cover, not the detection path)

### B6 — No crash while backgrounded (this is the one that used to be a crash)
- [ ] Put INTERCEPT in the background (another app in front), keep the bubble ON
- [ ] Send the risky message
- [ ] Banner shows **and** the app does not crash

**Expected:** banner appears, no crash. The banner is an in-process call
precisely because Android 12+ bans starting a service from a background
notification-listener callback.

---

## 4. Path C — Always-On

The headline test: **swipe the app off Recents and protection must keep working.**

### C1 — Recents swipe does not kill protection
- [ ] All three auto toggles ON; ongoing "INTERCEPT auto-protect" notification visible
- [ ] Swipe INTERCEPT away from Recents
- [ ] Notification is **still there**
- [ ] `dumpsys ... | grep -i alwayson` still lists the service

### C2 — Detection still works after the swipe (the real point)
- [ ] Still swiped-away, send the risky message from the other phone
- [ ] Auto-dismiss + alert + banner still fire

**Expected:** everything still works. If C1 passes but C2 fails, the process
survived but the listener did not — report it, that is a different bug.

### C3 — System kill → sticky restart
```bash
adb shell am kill com.intercept
```
- [ ] Wait ~10 s
- [ ] Service is listed again and the notification is back

**Expected:** returns. `START_STICKY` brings the process back after a
low-memory/background kill.

### C4 — Force-stop is deliberately final
```bash
adb shell am force-stop com.intercept
```
- [ ] Service does **not** come back on its own
- [ ] It comes back when you open the app, or reboot the phone

**Expected:** stays dead. Force-stop is an explicit user act; Android intends it
to be final and no app can (or should) fight it.

### C5 — Reboot
- [ ] Reboot with toggles ON
- [ ] **Without opening the app**, unlock the phone
- [ ] Protection notification returns on its own

**Expected:** returns via `BOOT_COMPLETED`. On MIUI/Vivo/Oppo you may also need
Autostart enabled — if it fails there, that is the OEM gate, not the receiver.

### C6 — App update
- [ ] Install a build over the running one (`adb install -r app-debug.apk`)
- [ ] Protection comes back without opening the app

**Expected:** returns via `MY_PACKAGE_REPLACED` — this is what stops an in-app
update from silently leaving the phone unprotected.

### C7 — Switches off = service off
- [ ] Turn all three auto toggles OFF
- [ ] Ongoing notification disappears, service stops
- [ ] Turn one back ON → service and notification return

**Expected:** the daemon never lingers once there is nothing to protect.

### C8 — Bubble heals itself
- [ ] Bubble ON, then `adb shell am kill com.intercept` while backgrounded
- [ ] Wait up to ~60 s
- [ ] Bubble is back on screen without reopening the app

**Expected:** healed within the heartbeat. The toggle said ON, so ON is restored.

### C9 — Overnight / doze reality
- [ ] Battery **restricted**, run `dumpsys deviceidle force-idle`, wait
- [ ] Record whether the service survives: `______`
- [ ] Battery **unrestricted**, repeat
- [ ] Record: `______`

**Expected:** restricted may be killed; unrestricted should survive. This is the
test that tells you whether the Setup battery gate is genuinely required on the
target OEMs.

### C10 — The notification is quiet
- [ ] Watch the status bar when the daemon (re)starts
- [ ] No sound, no heads-up flash — it is `IMPORTANCE_MIN`

### C11 — One ongoing notification, even mid-call
- [ ] Note the ongoing **INTERCEPT auto-protect** entry (id 1001, channel `intercept_auto`)
- [ ] Start a screened call (auto-answer path)
- [ ] Check the shade: it is the **same single** entry, now reading
      "Screening call from <number>…"
- [ ] Tap it → the transcript opens (live-screening deep link still works)
- [ ] End the call → the same entry returns to "Watching calls, messages and links."
- [ ] Scan a stranger SMS (auto-scan SMS ON) → same entry, back to idle text

**Expected:** exactly one ongoing entry throughout, never two, and never zero.
Both foreground services post this same id; the screener hands the line back with
`STOP_FOREGROUND_DETACH` instead of removing it.

**If it disappears after a call** — that is the detach path failing and the
daemon losing its own notification. Check with step C1's command.

---

## 5. What this checklist cannot prove

- **That the APK compiles.** There is no Gradle wrapper or Android SDK in this
  workspace, so the first real compile is CI's `gradle assembleDebug` on push.
  A manifest error here is a runtime crash, not a build error — so treat C1 and
  B6 as manifest smoke tests.
- **Play policy.** A `specialUse` foreground service and
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` may both be questioned in review. That
  is a policy conversation, not a device test.
- **Every chat app.** Only the packages in `CHAT_APPS` are scanned. Anything
  not on that list is invisible to us.
- **That muted chats are covered.** They cannot be — no notification, no text.

---

## 6. Result log

| Test | Device / OEM | Android ver | Result | Notes |
|---|---|---|---|---|
| A1 dismiss | | | | |
| A4 safe path | | | | |
| A8 Signal | | | | |
| B6 no crash bg | | | | |
| C2 after swipe | | | | |
| C5 reboot | | | | |
| C9 doze | | | | |

---

*Run order matters: Step 0 (calibration) → A → B → C. Path C's swipe test
invalidates Path A/B preconditions, so do it last.*
