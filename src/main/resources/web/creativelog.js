// CreativeLog-Viewer – Filter (Spieler, Art, Suche, Zeitraum), Seiten und CSV-Export.
// Daten werden nur per textContent eingesetzt (kein innerHTML), Suchtreffer per <mark>-Element hervorgehoben.
"use strict";

const PAGE = 100;
const LABELS = {
  ENTER: "Kreativ betreten", LEAVE: "Kreativ verlassen", SESSION: "Join/Quit", ITEM: "Items",
  BLOCK: "Blöcke", INTERACT: "Interaktion", COMBAT: "Kampf", COMMAND: "Befehle",
};
const dtf = new Intl.DateTimeFormat("de-DE", { dateStyle: "short", timeStyle: "medium" });
const nf = new Intl.NumberFormat("de-DE");

const $ = id => document.getElementById(id);
const state = { player: "", kinds: new Set(), q: "", from: "", to: "", offset: 0, total: 0 };
let requestId = 0;

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}

/** Text mit hervorgehobenen Suchtreffern – nur Text-Knoten und <mark>, kein HTML aus den Daten. */
function highlight(text, query) {
  const span = el("span");
  if (!query) {
    span.textContent = text;
    return span;
  }
  const lower = text.toLowerCase();
  let i = 0;
  for (let hit = lower.indexOf(query); hit >= 0; hit = lower.indexOf(query, i)) {
    span.append(text.slice(i, hit), el("mark", {}, text.slice(hit, hit + query.length)));
    i = hit + query.length;
  }
  span.append(text.slice(i));
  return span;
}

// ------------------------------------------------------------
// Zustand <-> URL (Filter lassen sich als Link teilen)
// ------------------------------------------------------------

function toDateInput(ms) {
  if (!ms) return "";
  const d = new Date(Number(ms));
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function readHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  state.player = p.get("player") || "";
  state.kinds = new Set((p.get("kinds") || "").split(",").filter(Boolean));
  state.q = p.get("q") || "";
  state.from = p.get("from") || "";
  state.to = p.get("to") || "";
}

function params(extra = {}) {
  const p = new URLSearchParams();
  if (state.player) p.set("player", state.player);
  if (state.kinds.size) p.set("kinds", [...state.kinds].join(","));
  if (state.q) p.set("q", state.q);
  if (state.from) p.set("from", state.from);
  if (state.to) p.set("to", state.to);
  for (const [k, v] of Object.entries(extra)) p.set(k, v);
  return p;
}

function writeHash() {
  history.replaceState(null, "", "#" + params().toString());
  $("csv").href = "/api/creativelog.csv?" + params().toString();
}

// ------------------------------------------------------------
// Laden und Anzeigen
// ------------------------------------------------------------

async function load(append = false) {
  const id = ++requestId;
  if (!append) state.offset = 0;
  writeHash();
  $("summary").textContent = "Lade …";
  const res = await fetch("/api/creativelog?" + params({ offset: state.offset, limit: PAGE }), { credentials: "same-origin", cache: "no-store" });
  if (res.status === 401) { location.href = "/"; return; }
  if (!res.ok) { $("summary").textContent = "Fehler beim Laden (" + res.status + ")."; return; }
  const data = await res.json();
  if (id !== requestId) return; // eine neuere Anfrage läuft schon

  state.total = data.total;
  renderCounts(data.counts);
  renderRows(data.entries, append);
  state.offset += data.entries.length;
  $("more").hidden = state.offset >= state.total;
  $("summary").textContent = state.total === 0 ? "Keine Einträge für diese Filter."
    : `${nf.format(Math.min(state.offset, state.total))} von ${nf.format(state.total)} Einträgen`;
}

function renderCounts(counts) {
  for (const [kind, n] of Object.entries(counts)) {
    const c = document.querySelector(`[data-count="${kind}"]`);
    if (c) c.textContent = nf.format(n);
  }
}

function renderRows(entries, append) {
  const box = $("results");
  let body = box.querySelector("tbody");
  if (!append || !body) {
    if (entries.length === 0) {
      box.replaceChildren(el("p", { class: "empty" }, "Keine Einträge."));
      return;
    }
    body = el("tbody");
    const head = el("tr", {}, el("th", {}, "Zeit"), el("th", {}, "Spieler"), el("th", {}, "Art"), el("th", {}, "Eintrag"));
    box.replaceChildren(el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, head), body)));
  }
  const q = state.q.toLowerCase();
  for (const e of entries) {
    const playerLink = el("a", { href: "#", "data-player": e.uuid }, e.player);
    body.append(el("tr", {},
      el("td", { class: "time" }, dtf.format(new Date(e.time))),
      el("td", {}, playerLink),
      el("td", {}, el("span", { class: "kind kind-" + e.kind }, LABELS[e.kind] || e.kind)),
      el("td", { class: "text" }, highlight(e.text, q))));
  }
}

async function loadPlayers() {
  const res = await fetch("/api/creativelog/players", { credentials: "same-origin", cache: "no-store" });
  if (!res.ok) return;
  const data = await res.json();
  const select = $("player");
  const players = data.players.sort((a, b) => b.last - a.last);
  for (const p of players) {
    const label = `${p.name} (${nf.format(p.entries)})${p.inCreative ? " – gerade im Kreativmodus" : ""}`;
    select.append(el("option", { value: p.uuid }, label));
  }
  select.value = state.player;

  const box = $("kinds");
  for (const kind of data.kinds) {
    const input = el("input", { type: "checkbox", value: kind });
    input.checked = state.kinds.has(kind);
    input.addEventListener("change", () => {
      input.checked ? state.kinds.add(kind) : state.kinds.delete(kind);
      load();
    });
    box.append(el("label", { class: "chip" }, input, el("span", { class: "kind kind-" + kind }, LABELS[kind] || kind),
      el("span", { class: "count", "data-count": kind }, "")));
  }
}

// ------------------------------------------------------------
// Bedienung
// ------------------------------------------------------------

function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

function bind() {
  $("player").addEventListener("change", e => { state.player = e.target.value; load(); });
  $("q").addEventListener("input", debounce(e => { state.q = e.target.value.trim(); load(); }, 300));
  for (const id of ["from", "to"]) {
    $(id).addEventListener("change", e => {
      state[id] = e.target.value ? String(new Date(e.target.value).getTime()) : "";
      load();
    });
  }
  $("more").addEventListener("click", () => load(true));
  $("reset").addEventListener("click", () => {
    Object.assign(state, { player: "", kinds: new Set(), q: "", from: "", to: "" });
    syncInputs();
    load();
  });
  // Klick auf einen Spielernamen filtert nach diesem Spieler
  $("results").addEventListener("click", e => {
    const a = e.target.closest("a[data-player]");
    if (!a) return;
    e.preventDefault();
    state.player = a.dataset.player;
    $("player").value = state.player;
    load();
  });
}

function syncInputs() {
  $("player").value = state.player;
  $("q").value = state.q;
  $("from").value = toDateInput(state.from);
  $("to").value = toDateInput(state.to);
  for (const input of document.querySelectorAll("#kinds input")) input.checked = state.kinds.has(input.value);
}

(async () => {
  readHash();
  await loadPlayers();
  syncInputs();
  bind();
  load();
})();

// Navigation: Glücksrad nur zeigen, wenn das Modul an ist
fetch("/api/me", { credentials: "same-origin" }).then(r => r.ok ? r.json() : null).then(me => {
  if (me && me.gambling) document.getElementById("nav-gambling").hidden = false;
  if (me && me.fairplay) document.getElementById("nav-fairplay").hidden = false;
}).catch(() => {});
