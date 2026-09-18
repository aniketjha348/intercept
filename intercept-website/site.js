/* ==========================================================================
   Intercept AI — Modern Client Engine
   Theme switching, interactive call simulator with Web Audio sound FX,
   tactical risk calculator, and release feed loader.
   ========================================================================== */

const API_BASE =
  new URLSearchParams(location.search).get("api") ||
  window.INTERCEPT_API ||
  "https://YOUR-API-URL";

async function getJSON(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

async function loadFeed() {
  try {
    if (API_BASE.includes("YOUR-API-URL")) {
      throw new Error("Local fallback mode");
    }
    const [latest, all] = await Promise.all([
      getJSON(API_BASE + "/app/latest"),
      getJSON(API_BASE + "/app/updates"),
    ]);
    return { latest, updates: all.updates || [] };
  } catch (e) {
    const local = await getJSON("updates.json");
    const items = local.updates || local;
    const latest = items[items.length - 1] || {};
    return {
      latest: {
        version_code: latest.version_code,
        version_name: latest.version_name,
        apk_url: latest.apk_url || "#",
        force: !!latest.force,
        updated_at: latest.date || "",
        notes_en: latest.notes_en || [],
        notes_hi: latest.notes_hi || [],
      },
      updates: items,
    };
  }
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
  }[c]));
}

/* ——— Theme Toggle System ——— */
function initTheme() {
  const savedTheme = localStorage.getItem("intercept_theme");
  const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  const initialTheme = savedTheme || (prefersDark ? "dark" : "light");

  if (initialTheme === "dark") {
    document.documentElement.setAttribute("data-theme", "dark");
  } else {
    document.documentElement.removeAttribute("data-theme");
  }

  const toggleBtn = document.getElementById("theme-toggle");
  if (toggleBtn) {
    toggleBtn.addEventListener("click", () => {
      const isDark = document.documentElement.getAttribute("data-theme") === "dark";
      if (isDark) {
        document.documentElement.removeAttribute("data-theme");
        localStorage.setItem("intercept_theme", "light");
      } else {
        document.documentElement.setAttribute("data-theme", "dark");
        localStorage.setItem("intercept_theme", "dark");
      }
    });
  }
}

/* ——— Web Audio Sound Design (Safe, Zero Bandwidth, Client-Side) ——— */
let audioCtx = null;
let soundEnabled = false;

function getAudioContext() {
  if (!audioCtx && (window.AudioContext || window.webkitAudioContext)) {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    audioCtx = new AudioContextClass();
  }
  if (audioCtx && audioCtx.state === "suspended") {
    audioCtx.resume();
  }
  return audioCtx;
}

function playTone(freq, type, duration, gainVal = 0.08) {
  if (!soundEnabled) return;
  try {
    const ctx = getAudioContext();
    if (!ctx) return;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, ctx.currentTime);
    gain.gain.setValueAtTime(gainVal, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + duration);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + duration);
  } catch (err) {
    // Audio playback non-critical
  }
}

function playShieldChime() {
  if (!soundEnabled) return;
  playTone(587.33, "sine", 0.12, 0.07); // D5
  setTimeout(() => playTone(880, "sine", 0.2, 0.07), 80); // A5
}

function playDisconnectBeep() {
  if (!soundEnabled) return;
  for (let i = 0; i < 3; i++) {
    setTimeout(() => {
      playTone(425, "square", 0.12, 0.09);
    }, i * 160);
  }
}

function playAlertChirp() {
  if (!soundEnabled) return;
  playTone(330, "sawtooth", 0.1, 0.08);
  setTimeout(() => playTone(220, "sawtooth", 0.18, 0.08), 90);
}

/* ——— Interactive Call Simulator ——— */
const SCENARIOS = {
  kyc: {
    title: "Bank KYC Freeze",
    callerNumber: "+91 98XXX XXXXX",
    initialRisk: 18,
    finalRisk: 95,
    authorityWidth: "18%",
    otpWidth: "77%",
    cutText: "Line cut — OTP demand intercepted before caller spoke",
    isSafe: false,
    turns: [
      {
        who: "Caller",
        time: "00:12",
        class: "from-caller",
        said: "Main SBI bank KYC department se bol raha hoon. 2 ghante me khata band ho jayega.",
        signal: { text: "⚑ Authority claim · Impersonation", delta: "+18" },
      },
      {
        who: "Intercept AI",
        time: "00:24",
        class: "from-guard",
        said: "Namaste! Main Intercept hoon. Kripya apna employee ID aur branch code batayein.",
        guardianBadge: "AI Shield",
      },
      {
        who: "Caller",
        time: "00:39",
        class: "from-caller is-crit",
        said: "Turant OTP batao, verification ke liye. Time nahi hai!",
        critBadge: "Critical Alert",
        signal: { text: "⚑ OTP demand · Urgency pressure", delta: "+77" },
      },
    ],
  },
  police: {
    title: "Police / Digital Arrest",
    callerNumber: "+91 11 2345 XXXX",
    initialRisk: 28,
    finalRisk: 98,
    authorityWidth: "28%",
    otpWidth: "70%",
    cutText: "Line cut — Digital arrest threat & extortive transfer demand blocked",
    isSafe: false,
    turns: [
      {
        who: "Caller",
        time: "00:15",
        class: "from-caller",
        said: "Main Crime Branch se DCP Sharma bol raha hoon. Aapke Aadhaar par illegal courier pakda gaya hai.",
        signal: { text: "⚑ Law enforcement claim · Intimidation", delta: "+28" },
      },
      {
        who: "Intercept AI",
        time: "00:27",
        class: "from-guard",
        said: "Law enforcement communications require official summon. Intercept is recording this screening session.",
        guardianBadge: "AI Shield",
      },
      {
        who: "Caller",
        time: "00:43",
        class: "from-caller is-crit",
        said: "Video call lagao turant aur bail verification account me Rs 50,000 transfer karo!",
        critBadge: "Extortion Trap",
        signal: { text: "⚑ Immediate payment demand · Threat", delta: "+70" },
      },
    ],
  },
  power: {
    title: "Electricity Cutoff",
    callerNumber: "+91 80XXX XXXXX",
    initialRisk: 22,
    finalRisk: 91,
    authorityWidth: "22%",
    otpWidth: "69%",
    cutText: "Line cut — Fake utility bill APK installation & UPI trap blocked",
    isSafe: false,
    turns: [
      {
        who: "Caller",
        time: "00:10",
        class: "from-caller",
        said: "Dear consumer, aapka bijli connection aaj raat 9:30 baje cut kar diya jayega. Bill unpaid hai.",
        signal: { text: "⚑ Utility disconnection deadline", delta: "+22" },
      },
      {
        who: "Intercept AI",
        time: "00:21",
        class: "from-guard",
        said: "Kripya Consumer Account ID batayein, hum electricity board portal se verify kar rahe hain.",
        guardianBadge: "AI Shield",
      },
      {
        who: "Caller",
        time: "00:35",
        class: "from-caller is-crit",
        said: "WhatsApp par bheja hua APK install karo aur Rs 10 ka test recharge karo.",
        critBadge: "Malicious APK",
        signal: { text: "⚑ Rogue APK install · Remote access link", delta: "+69" },
      },
    ],
  },
  family: {
    title: "Safe Family Call",
    callerNumber: "+91 94XXX XXXXX",
    initialRisk: 5,
    finalRisk: 8,
    authorityWidth: "5%",
    otpWidth: "3%",
    cutText: "Call connected normally — Trusted contact, zero fraud markers",
    isSafe: true,
    turns: [
      {
        who: "Caller",
        time: "00:08",
        class: "from-caller",
        said: "Namaste beta! Sham ko train se aate waqt mithai le aana, yaad se.",
      },
      {
        who: "Intercept AI",
        time: "00:15",
        class: "from-guard",
        said: "Family contact verified. Ringing through to recipient phone directly.",
        guardianBadge: "Safe Pass",
      },
    ],
  },
};

let currentScenarioKey = "kyc";
let simTimeoutIds = [];

function runSimulation(key) {
  const room = document.getElementById("room");
  if (!room) return;

  // Clear pending timeouts
  simTimeoutIds.forEach(clearTimeout);
  simTimeoutIds = [];

  const sc = SCENARIOS[key] || SCENARIOS.kyc;
  currentScenarioKey = key;

  // Update pills active state
  document.querySelectorAll(".scenario-pill").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-scenario") === key);
  });

  // Reset elements
  const scoreEl = room.querySelector(".score");
  const numEl = room.querySelector(".room-bar .num");
  const clockEl = room.querySelector(".room-bar .clock");
  const segAuth = room.querySelector(".seg-authority");
  const segOtp = room.querySelector(".seg-otp");
  const turnsOl = room.querySelector(".turns");
  const cutline = room.querySelector(".cutline");

  if (numEl) {
    numEl.innerHTML =
      '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20.01 15.38c-1.23 0-2.42-.2-3.53-.56a.977.977 0 00-1.01.24l-2.2 2.2a15.053 15.053 0 01-6.59-6.59l2.2-2.21a.96.96 0 00.25-1A11.36 11.36 0 018.57 3.9c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1 0 9.39 7.61 17 17 17 .55 0 1-.45 1-1v-3.52c0-.55-.45-1-.99-1z"/></svg> ' +
      esc(sc.callerNumber);
  }

  if (clockEl) clockEl.textContent = "00:00";
  if (scoreEl) scoreEl.innerHTML = "0 <span class='score-max'>/ 100</span>";
  if (segAuth) segAuth.style.width = "0%";
  if (segOtp) segOtp.style.width = "0%";
  if (turnsOl) turnsOl.innerHTML = "";
  if (cutline) {
    cutline.style.opacity = "0";
    cutline.className = sc.isSafe ? "cutline is-safe" : "cutline";
    cutline.innerHTML =
      '<span class="bar"></span><span class="cut-icon">' +
      (sc.isSafe ? "✓" : "⚡") +
      "</span> " +
      esc(sc.cutText);
  }

  // Turn 1 playback
  simTimeoutIds.push(
    setTimeout(() => {
      if (clockEl) clockEl.textContent = sc.turns[0]?.time || "00:12";
      if (scoreEl) scoreEl.innerHTML = esc(sc.initialRisk) + " <span class='score-max'>/ 100</span>";
      if (segAuth) segAuth.style.width = sc.authorityWidth;
      appendTurn(turnsOl, sc.turns[0]);
      if (!sc.isSafe) playAlertChirp();
    }, 400)
  );

  // Turn 2 (Intercept AI response)
  if (sc.turns[1]) {
    simTimeoutIds.push(
      setTimeout(() => {
        if (clockEl) clockEl.textContent = sc.turns[1].time || "00:24";
        appendTurn(turnsOl, sc.turns[1]);
        playShieldChime();
      }, 1500)
    );
  }

  // Turn 3 (Scam demand & Cutline)
  if (sc.turns[2]) {
    simTimeoutIds.push(
      setTimeout(() => {
        if (clockEl) clockEl.textContent = sc.turns[2].time || "00:39";
        if (scoreEl) scoreEl.innerHTML = esc(sc.finalRisk) + " <span class='score-max'>/ 100</span>";
        if (segOtp) segOtp.style.width = sc.otpWidth;
        appendTurn(turnsOl, sc.turns[2]);
        playAlertChirp();
      }, 2800)
    );

    // Emergency Line Severance
    simTimeoutIds.push(
      setTimeout(() => {
        if (cutline) cutline.style.opacity = "1";
        playDisconnectBeep();
      }, 3600)
    );
  } else if (sc.isSafe) {
    simTimeoutIds.push(
      setTimeout(() => {
        if (scoreEl) scoreEl.innerHTML = esc(sc.finalRisk) + " <span class='score-max'>/ 100</span>";
        if (cutline) cutline.style.opacity = "1";
      }, 2300)
    );
  }
}

function appendTurn(parent, turnData) {
  if (!parent || !turnData) return;
  const li = document.createElement("li");
  li.className = "turn " + (turnData.class || "");
  li.innerHTML =
    '<div class="turn-header">' +
      '<span class="who">' + esc(turnData.who) + '</span>' +
      (turnData.guardianBadge ? '<span class="guardian-shield">' + esc(turnData.guardianBadge) + '</span>' : '') +
      (turnData.critBadge ? '<span class="crit-alert">' + esc(turnData.critBadge) + '</span>' : '') +
      '<span class="turn-time">' + esc(turnData.time || "") + '</span>' +
    '</div>' +
    '<p class="said">' + esc(turnData.said) + '</p>' +
    (turnData.signal
      ? '<p class="signal"><span>' + esc(turnData.signal.text) + '</span><span class="delta">' + esc(turnData.signal.delta) + '</span></p>'
      : '');
  parent.appendChild(li);
}

/* ——— Tactical Risk Calculator ——— */
function initRiskCalculator() {
  const toggles = document.querySelectorAll(".calc-toggle");
  const scoreNum = document.getElementById("calc-score-num");
  const needle = document.getElementById("calc-needle");
  const verdictPill = document.getElementById("calc-verdict-pill");
  const verdictDesc = document.getElementById("calc-verdict-desc");

  if (!toggles.length || !scoreNum || !needle) return;

  function updateRisk() {
    let score = 5; // Baseline quiet chatter score
    toggles.forEach((t) => {
      if (t.classList.contains("active")) {
        const pts = parseInt(t.getAttribute("data-points"), 10) || 0;
        score += pts;
      }
    });

    score = Math.max(0, Math.min(100, score));
    scoreNum.textContent = score;

    // Rotate needle from -90deg (0 score) to +90deg (100 score)
    const angle = -90 + (score / 100) * 180;
    needle.style.transform = `rotate(${angle}deg)`;

    if (score < 35) {
      scoreNum.style.color = "var(--low)";
      needle.style.backgroundColor = "var(--low)";
      verdictPill.textContent = "Safe Chatter";
      verdictPill.className = "calc-verdict-pill tier-pill-low";
      verdictDesc.textContent = "Safe normal conversation. Contacts and harmless messages ring through silently without disruption.";
    } else if (score < 70) {
      scoreNum.style.color = "var(--susp)";
      needle.style.backgroundColor = "var(--susp)";
      verdictPill.textContent = "Suspicious Warning";
      verdictPill.className = "calc-verdict-pill tier-pill-susp";
      verdictDesc.textContent = "Suspicious pressure tactics detected. Intercept AI raises a clear warning notice explaining the manipulation.";
    } else {
      scoreNum.style.color = "var(--crit)";
      needle.style.backgroundColor = "var(--crit)";
      verdictPill.textContent = "Critical Threat";
      verdictPill.className = "calc-verdict-pill tier-pill-crit";
      verdictDesc.textContent = "Critical fraud attempt detected. Intercept AI automatically terminates the call and prevents OTP forwarding.";
    }
  }

  toggles.forEach((t) => {
    t.addEventListener("click", () => {
      t.classList.toggle("active");
      updateRisk();
    });
  });

  updateRisk();
}

/* ——— DOM Initialization ——— */
document.addEventListener("DOMContentLoaded", async () => {
  initTheme();
  initRiskCalculator();

  // Active navigation link tracking
  const currentPath = window.location.pathname.split("/").pop() || "index.html";
  document.querySelectorAll(".nav nav a").forEach((a) => {
    const href = a.getAttribute("href");
    if (href === currentPath || (currentPath === "" && href === "index.html")) {
      a.classList.add("active");
    }
  });

  // Call simulator sound toggle
  const soundBtn = document.getElementById("sound-toggle");
  if (soundBtn) {
    soundBtn.addEventListener("click", () => {
      soundEnabled = !soundEnabled;
      soundBtn.innerHTML = soundEnabled ? "🔊 Sound: On" : "🔇 Sound: Off";
      soundBtn.setAttribute("aria-pressed", soundEnabled);
      if (soundEnabled) {
        getAudioContext();
        playShieldChime();
      }
    });
  }

  // Replay simulation button
  const replayBtn = document.getElementById("replay-sim");
  if (replayBtn) {
    replayBtn.addEventListener("click", () => {
      runSimulation(currentScenarioKey);
    });
  }

  // Scenario pill selectors
  document.querySelectorAll(".scenario-pill").forEach((btn) => {
    btn.addEventListener("click", () => {
      const scenario = btn.getAttribute("data-scenario");
      if (scenario) runSimulation(scenario);
    });
  });

  // Download and changelog feed
  const feed = await loadFeed().catch(() => null);
  if (!feed) return;

  // download.html — latest card
  const v = document.getElementById("dl-version");
  if (v) {
    const L = feed.latest;
    v.innerHTML = "INTERCEPT " + esc(L.version_name || "");
    const dateEl = document.getElementById("dl-date");
    if (dateEl) dateEl.textContent = "Released " + (L.updated_at || "recently");
    const notesEl = document.getElementById("dl-notes");
    if (notesEl) {
      notesEl.innerHTML = (L.notes_en || []).map((n) => "<li>" + esc(n) + "</li>").join("");
    }
    const btn = document.getElementById("dl-btn");
    if (btn) btn.href = L.apk_url || "#";

    const qr = document.getElementById("dl-qr");
    if (qr && L.apk_url && L.apk_url !== "#") {
      qr.innerHTML = "";
      const img = document.createElement("img");
      img.width = 170;
      img.height = 170;
      img.alt = "Download QR";
      img.src = "https://api.qrserver.com/v1/create-qr-code/?size=170x170&data=" + encodeURIComponent(L.apk_url);
      qr.appendChild(img);
    }

    const olds = feed.updates.slice(0, -1).reverse();
    const oldContainer = document.getElementById("old-versions");
    if (oldContainer) {
      oldContainer.innerHTML = olds.length
        ? olds
            .map(
              (u) =>
                "<div style='display:flex; justify-content:space-between; align-items:center; padding:12px 0; border-bottom:1px solid var(--wire);'>" +
                "<span><strong>v" + esc(u.version_name) + "</strong></span>" +
                "<span class='muted small' style='font-family:var(--mono);'>" + esc(u.date || "") + "</span>" +
                "</div>"
            )
            .join("")
        : "<p class='muted'>Only one release so far.</p>";
    }
  }

  // updates.html — changelog timeline
  const cl = document.getElementById("changelog");
  if (cl) {
    cl.innerHTML = feed.updates
      .slice()
      .reverse()
      .map(
        (u) =>
          '<div class="rel"><h3>' +
          esc(u.version_name) +
          (u.force ? '<span class="badge">REQUIRED</span>' : "") +
          '</h3><p class="muted small">' +
          esc(u.date || "") +
          "</p><ul>" +
          (u.notes_en || []).map((n) => "<li>" + esc(n) + "</li>").join("") +
          "</ul><ul class='notes-hi'>" +
          (u.notes_hi || []).map((n) => "<li>" + esc(n) + "</li>").join("") +
          "</ul></div>"
      )
      .join("");
  }
});
