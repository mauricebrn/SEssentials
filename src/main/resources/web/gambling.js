// Glücksrad-Statistik – liest den Abschnitt "gambling" aus /api/stats.
// Daten werden nur per textContent eingesetzt (kein innerHTML).
"use strict";

const REFRESH_MS = 30000;
const DAYS = 30;
const nf = new Intl.NumberFormat("de-DE");
const pf = new Intl.NumberFormat("de-DE", { style: "percent", maximumFractionDigits: 1 });
const mf = new Intl.NumberFormat("de-DE", { maximumFractionDigits: 2 });
const dtf = new Intl.DateTimeFormat("de-DE", { dateStyle: "short", timeStyle: "short" });
const df = new Intl.DateTimeFormat("de-DE", { day: "2-digit", month: "2-digit" });
const SVG = "http://www.w3.org/2000/svg";

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}

function table(headers, rows, numeric = []) {
  if (rows.length === 0) return el("p", { class: "empty" }, "Noch keine Drehungen.");
  const head = el("tr", {}, ...headers.map((h, i) => el("th", numeric.includes(i) ? { class: "num" } : {}, h)));
  const body = rows.map(r => el("tr", {}, ...r.map((c, i) => el("td", numeric.includes(i) ? { class: "num" } : {}, c))));
  return el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, head), el("tbody", {}, ...body)));
}

const replace = (id, node) => document.getElementById(id).replaceChildren(node);
const multi = (m, jackpot) => jackpot ? "Jackpot" : "x" + mf.format(m);

/** Betrag mit Vorzeichen, grün/rot eingefärbt. */
function signed(n) {
  if (n === 0) return el("span", { class: "empty" }, "±0");
  return el("span", { class: n > 0 ? "up" : "down" }, (n > 0 ? "+" : "") + nf.format(n));
}

function renderKpis(g, eco) {
  const actual = g.staked > 0 ? g.paidOut / g.staked : null;
  const kpis = [
    ["Topf", `${nf.format(g.pot)} ${eco.currencyPlural}`],
    ["Drehungen", nf.format(g.spins)],
    ["Spieler", nf.format(g.players)],
    ["Eingesetzt", nf.format(g.staked)],
    ["Ausgezahlt", nf.format(g.paidOut)],
    ["Gebühren", nf.format(g.fees)],
    ["Ergebnis Server", signed(g.staked + g.fees - g.paidOut)],
    ["Auszahlungsquote", actual == null ? "–" : pf.format(actual)],
    ["Quote laut Config", pf.format(g.expectedReturn)],
    ["Größter Gewinn", g.biggest ? `${nf.format(g.biggest.win)} (${g.biggest.name})` : "–"],
  ];
  document.getElementById("kpis").replaceChildren(
    ...kpis.map(([label, value]) => el("div", { class: "kpi" }, el("div", { class: "label" }, label), el("div", { class: "value" }, value))));
}

/** Letzte 30 Tage lückenlos; Tage ohne Drehung zählen als 0. */
function dailySeries(days) {
  const byDate = new Map(days.map(d => [d.date, d]));
  const out = [];
  const today = new Date();
  for (let i = DAYS - 1; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    const day = byDate.get(key);
    out.push({ date: d, staked: day ? day.staked : 0, paidOut: day ? day.paidOut : 0 });
  }
  return out;
}

function renderChart(days) {
  const box = document.getElementById("chart");
  if (days.length === 0) {
    box.replaceChildren(el("p", { class: "empty" }, "Noch keine Drehungen in den letzten 30 Tagen."));
    return;
  }
  const pts = dailySeries(days);
  const W = 800, H = 220, L = 70, R = 12, T = 12, B = 28;
  const y1 = Math.max(1, ...pts.map(p => Math.max(p.staked, p.paidOut)));
  const sx = i => L + i / (pts.length - 1) * (W - L - R);
  const sy = v => T + (1 - v / y1) * (H - T - B);

  const svg = document.createElementNS(SVG, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.setAttribute("role", "img");
  const sum = k => pts.reduce((a, p) => a + p[k], 0);
  svg.setAttribute("aria-label", `30 Tage: ${nf.format(sum("staked"))} eingesetzt, ${nf.format(sum("paidOut"))} ausgezahlt`);
  const add = (tag, attrs, text) => {
    const n = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
    if (text != null) n.textContent = text;
    svg.append(n);
    return n;
  };
  for (const v of [0, y1 / 2, y1]) {
    add("line", { x1: L, x2: W - R, y1: sy(v), y2: sy(v), class: "grid-line" });
    add("text", { x: L - 8, y: sy(v) + 4, "text-anchor": "end", class: "axis" }, nf.format(Math.round(v)));
  }
  add("text", { x: L, y: H - 8, class: "axis" }, df.format(pts[0].date));
  add("text", { x: W - R, y: H - 8, "text-anchor": "end", class: "axis" }, df.format(pts[pts.length - 1].date));
  for (const [key, cls] of [["staked", "series"], ["paidOut", "series alt"]]) {
    add("polyline", { points: pts.map((p, i) => `${sx(i)},${sy(p[key])}`).join(" "), class: cls });
  }
  // Tooltips je Tag
  pts.forEach((p, i) => {
    const hit = add("rect", { x: sx(i) - 6, y: T, width: 12, height: H - T - B, class: "hit" });
    const t = document.createElementNS(SVG, "title");
    t.textContent = `${df.format(p.date)}: ${nf.format(p.staked)} eingesetzt, ${nf.format(p.paidOut)} ausgezahlt`;
    hit.append(t);
  });
  box.replaceChildren(svg);
}

function renderSegments(g) {
  const total = g.segments.reduce((a, s) => a + s.hits, 0);
  replace("segments", table(["Feld", "Chance", "Treffer", "Tatsächlich"],
    g.segments.map(s => [multi(s.multiplier, s.jackpot), pf.format(s.chance), nf.format(s.hits), total > 0 ? pf.format(s.hits / total) : "–"]),
    [1, 2, 3]));
}

function renderPlayers(g) {
  replace("players", table(["Spieler", "Drehungen", "Eingesetzt", "Ergebnis"],
    g.topPlayers.map(p => [p.name, nf.format(p.spins), nf.format(p.staked), signed(p.won - p.staked)]),
    [1, 2, 3]));
}

function renderRecent(g) {
  replace("recent", table(["Zeit", "Spieler", "Einsatz", "Feld", "Ergebnis"],
    g.recent.map(r => [dtf.format(new Date(r.time)), r.name, nf.format(r.stake), multi(r.multiplier, r.jackpot), signed(r.win - r.stake)]),
    [2, 4]));
}

async function load() {
  try {
    const res = await fetch("/api/stats", { credentials: "same-origin", cache: "no-store" });
    if (res.status === 401) { location.reload(); return; }
    const s = await res.json();
    if (!s.gambling) return;
    const g = s.gambling;
    renderKpis(g, s.economy);
    renderChart(g.days);
    renderSegments(g);
    renderPlayers(g);
    renderRecent(g);
    document.getElementById("meta").textContent = `Stand ${dtf.format(new Date(s.generatedAt))}`;
  } catch (e) {
    document.getElementById("meta").textContent = "Server nicht erreichbar – neuer Versuch in 30 Sekunden.";
  }
}

fetch("/api/me", { credentials: "same-origin" }).then(r => r.ok ? r.json() : null).then(me => {
  if (me && me.creativeLog) document.getElementById("nav-creativelog").hidden = false;
}).catch(() => {});

load();
setInterval(load, REFRESH_MS);
