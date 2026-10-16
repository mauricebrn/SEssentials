// Fairplay – liest den Abschnitt "fairplay" aus /api/stats.
// Daten werden nur per textContent eingesetzt (kein innerHTML).
"use strict";

const REFRESH_MS = 30000;
const RANKING_LIMIT = 100;
const nf = new Intl.NumberFormat("de-DE");
const dtf = new Intl.DateTimeFormat("de-DE", { dateStyle: "short", timeStyle: "short" });

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}

function table(headers, rows, numeric = [], empty = "Keine Einträge.") {
  if (rows.length === 0) return el("p", { class: "empty" }, empty);
  const head = el("tr", {}, ...headers.map((h, i) => el("th", numeric.includes(i) ? { class: "num" } : {}, h)));
  const body = rows.map(r => el("tr", {}, ...r.map((c, i) => el("td", numeric.includes(i) ? { class: "num" } : {}, c))));
  return el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, head), el("tbody", {}, ...body)));
}

const replace = (id, node) => document.getElementById(id).replaceChildren(node);

function player(p) {
  return el("span", { class: "player" },
    el("img", { src: `https://mc-heads.net/avatar/${encodeURIComponent(p.uuid)}/20`, alt: "" }), p.name);
}

/** Punkte, rot ab Strike-Schwelle + 10. */
function score(p, f) {
  return el("span", { class: p.score <= f.strikeThreshold + 10 ? "down" : "up" }, `${nf.format(p.score)} / ${nf.format(f.startScore)}`);
}

function strikes(p, f) {
  const text = f.strikesForPunishment > 0 ? `${p.strikes} / ${f.strikesForPunishment}` : String(p.strikes);
  return p.strikes > 0 ? el("span", { class: "down" }, text) : text;
}

function renderKpis(f) {
  const players = f.players;
  const avg = players.length ? players.reduce((a, p) => a + p.score, 0) / players.length : 0;
  const week = Object.values(f.weekByType).reduce((a, n) => a + n, 0);
  const kpis = [
    ["Spieler", nf.format(players.length)],
    ["Ø Punkte", nf.format(Math.round(avg))],
    ["Mit Strikes", nf.format(players.filter(p => p.strikes > 0).length)],
    ["Aktive Strikes", nf.format(players.reduce((a, p) => a + p.strikes, 0))],
    ["Verstöße (7 Tage)", nf.format(week)],
    ["Geldstrafen (7 Tage)", nf.format(f.finesWeek)],
    ["Strike bei", `≤ ${nf.format(f.strikeThreshold)}`],
    ["Strafe ab", f.strikesForPunishment > 0 && f.punishmentTemplate
      ? `${f.strikesForPunishment} Strikes (Vorlage ${f.punishmentTemplate})` : "aus"],
    ["Strikes verfallen", f.strikeExpireDays > 0 ? `nach ${f.strikeExpireDays} Tagen` : "nie"],
  ];
  document.getElementById("kpis").replaceChildren(
    ...kpis.map(([label, value]) => el("div", { class: "kpi" }, el("div", { class: "label" }, label), el("div", { class: "value" }, value))));
}

function renderCritical(f) {
  const critical = f.players
    .filter(p => p.strikes > 0 || p.score <= f.strikeThreshold + 10)
    .sort((a, b) => b.strikes - a.strikes || a.score - b.score)
    .slice(0, 15);
  replace("critical", table(["Spieler", "Punkte", "Strikes"],
    critical.map(p => [player(p), score(p, f), strikes(p, f)]), [1, 2], "Niemand ist auffällig."));
}

function renderWeek(f) {
  const rows = Object.entries(f.weekByType).sort((a, b) => b[1] - a[1]);
  replace("week", table(["Verstoß", "Anzahl"], rows.map(([type, n]) => [type, nf.format(n)]), [1], "Keine Verstöße in den letzten 7 Tagen."));
}

function renderRanking(f) {
  replace("ranking", table(["Rang", "Spieler", "Punkte", "Strikes", "Verstöße"],
    f.players.slice(0, RANKING_LIMIT).map(p => ["#" + p.rank, player(p), score(p, f), strikes(p, f), nf.format(p.violations)]),
    [0, 2, 3, 4]));
}

function renderRecent(f) {
  const minus = n => n > 0 ? el("span", { class: "down" }, "-" + nf.format(n)) : "–";
  replace("recent", table(["Zeit", "Spieler", "Verstoß", "Punkte", "Geldstrafe"],
    f.recent.map(v => [dtf.format(new Date(v.time)), v.name, v.type, minus(v.points), minus(v.fine)]),
    [3, 4], "Noch keine Verstöße."));
}

async function load() {
  try {
    const res = await fetch("/api/stats", { credentials: "same-origin", cache: "no-store" });
    if (res.status === 401) { location.reload(); return; }
    const s = await res.json();
    if (!s.fairplay) return;
    const f = s.fairplay;
    renderKpis(f);
    renderCritical(f);
    renderWeek(f);
    renderRanking(f);
    renderRecent(f);
    document.getElementById("meta").textContent = `Stand ${dtf.format(new Date(s.generatedAt))}`;
  } catch (e) {
    document.getElementById("meta").textContent = "Server nicht erreichbar – neuer Versuch in 30 Sekunden.";
  }
}

fetch("/api/me", { credentials: "same-origin" }).then(r => r.ok ? r.json() : null).then(me => {
  if (me && me.creativeLog) document.getElementById("nav-creativelog").hidden = false;
  if (me && me.gambling) document.getElementById("nav-gambling").hidden = false;
}).catch(() => {});

load();
setInterval(load, REFRESH_MS);
