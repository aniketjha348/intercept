/* Single source of truth = backend /app/*.
   Set API_BASE at deploy (or ?api= override). Falls back to local updates.json. */
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
        version_code: latest.version_code, version_name: latest.version_name,
        apk_url: latest.apk_url || "#", force: !!latest.force,
        updated_at: latest.date || "", notes_en: latest.notes_en || [], notes_hi: latest.notes_hi || [],
      },
      updates: items,
    };
  }
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

document.addEventListener("DOMContentLoaded", async () => {
  const feed = await loadFeed().catch(() => null);
  if (!feed) return;

  // download.html — latest card
  const v = document.getElementById("dl-version");
  if (v) {
    const L = feed.latest;
    v.innerHTML = "INTERCEPT " + esc(L.version_name || "");
    document.getElementById("dl-date").textContent = "Updated " + (L.updated_at || "");
    document.getElementById("dl-notes").innerHTML =
      (L.notes_en || []).map((n) => "<li>" + esc(n) + "</li>").join("");
    const btn = document.getElementById("dl-btn");
    btn.href = L.apk_url || "#";
    // QR to the APK (no backend dependency)
    const qr = document.getElementById("dl-qr");
    if (qr && L.apk_url && L.apk_url !== "#") {
      const img = document.createElement("img");
      img.width = 180; img.height = 180; img.alt = "Download QR";
      img.src = "https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=" + encodeURIComponent(L.apk_url);
      qr.appendChild(img);
    }
    // older versions
    const olds = feed.updates.slice(0, -1).reverse();
    document.getElementById("old-versions").innerHTML = olds.length
      ? olds.map((u) => "<p><strong>" + esc(u.version_name) + "</strong> — " + esc(u.date || "") + "</p>").join("")
      : "<p>Only one release so far.</p>";
  }

  // updates.html — changelog timeline
  const cl = document.getElementById("changelog");
  if (cl) {
    cl.innerHTML = feed.updates.slice().reverse().map((u) =>
      '<div class="rel"><h3>' + esc(u.version_name) +
      (u.force ? '<span class="badge">REQUIRED</span>' : "") +
      '</h3><p class="muted small">' + esc(u.date || "") + "</p><ul>" +
      (u.notes_en || []).map((n) => "<li>" + esc(n) + "</li>").join("") +
      "</ul><ul class='notes-hi'>" +
      (u.notes_hi || []).map((n) => "<li>" + esc(n) + "</li>").join("") +
      "</ul></div>"
    ).join("");
  }
});
