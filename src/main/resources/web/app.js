// SEssentials Webpanel – lädt /api/stats und rendert. Daten werden nur per textContent eingesetzt (kein innerHTML),
// damit Spielertexte wie Report-Gründe kein HTML einschleusen können.
"use strict";

const REFRESH_MS = 30000;
const nf = new Intl.NumberFormat("de-DE");
const dtf = new Intl.DateTimeFormat("de-DE", { dateStyle: "short", timeStyle: "short" });
const SVG = "http://www.w3.org/2000/svg";

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else e.setAttribute(k, v);
  }
  for (const c of children) {
    if (c == null) continue;
    e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return e;
}

function table(headers, rows, numeric = []) {
  if (rows.length === 0) return el("p", { class: "empty" }, "Keine Einträge.");
  const head = el("tr", {}, ...headers.map((h, i) => el("th", numeric.includes(i) ? { class: "num" } : {}, h)));
  const body = rows.map(r => el("tr", {}, ...r.map((c, i) => el("td", numeric.includes(i) ? { class: "num" } : {}, c))));
  return el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, head), el("tbody", {}, ...body)));
}

function duration(ms) {
  const m = Math.floor(ms / 60000), d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60), mm = m % 60;
  if (d > 0) return `${d} T ${h} Std`;
  if (h > 0) return `${h} Std ${mm} Min`;
  return `${mm} Min`;
}

function money(n, eco) {
  return `${nf.format(n)} ${n === 1 ? eco.currencySingular : eco.currencyPlural}`;
}

function playerCell(name, uuid) {
  const img = el("img", { src: `https://mc-heads.net/avatar/${encodeURIComponent(uuid || name)}/20`, alt: "" });
  return el("span", { class: "player" }, img, name);
}

function delta(now, before) {
  if (before < 0) return el("span", { class: "empty" }, "–");
  const d = now - before;
  if (d === 0) return el("span", { class: "empty" }, "±0");
  return el("span", { class: d > 0 ? "up" : "down" }, (d > 0 ? "+" : "") + nf.format(d));
}

function replace(id, node) {
  document.getElementById(id).replaceChildren(node);
}

function renderKpis(s) {
  const eco = s.economy;
  const kpis = [
    ["Online", `${s.server.online} / ${s.server.maxPlayers}`],
    ["TPS", s.server.tps.toFixed(2)],
    ["MSPT", s.server.mspt.toFixed(1)],
    ["Laufzeit", duration(s.server.uptimeMillis)],
    [`${eco.currencyPlural} im Umlauf`, nf.format(eco.total)],
    ["Offene Reports", s.moderation.openReports.length],
    ["Aktive Strafen", s.moderation.activePunishments.length],
    ["Chestshops", eco.chestShops],
  ];
  if (s.server.softrestartSeconds >= 0) kpis.push(["Neustart in", duration(s.server.softrestartSeconds * 1000)]);
  document.getElementById("kpis").replaceChildren(
    ...kpis.map(([label, value]) => el("div", { class: "kpi" }, el("div", { class: "label" }, label), el("div", { class: "value" }, value))));
}

function renderPlayers(s) {
  const eco = s.economy;
  replace("players", table(["Spieler", "Welt", "Modus", "Ping", "Kontostand"],
    s.players.map(p => {
      const name = playerCell(p.name, p.uuid);
      if (p.vanished) name.append(el("span", { class: "badge" }, "Vanish"));
      return [name, p.world, p.gamemode, `${p.ping} ms`, money(p.balance, eco)];
    }), [3, 4]));
}

function renderModeration(s) {
  replace("reports", table(["#", "Spieler", "Grund", "Von", "Zeit"],
    s.moderation.openReports.slice().reverse().map(r => [r.id, r.reported, r.reason, r.reporter, dtf.format(new Date(r.time))])));
  const now = Date.now();
  replace("punishments", table(["#", "Typ", "Spieler", "Grund", "Noch"],
    s.moderation.activePunishments.map(p => [p.id, p.type, p.target, p.reason, p.expires < 0 ? "permanent" : duration(p.expires - now)])));
}

function renderEconomy(s) {
  const eco = s.economy;
  replace("items", table(["Item", "Anzahl", "7 Tage", "Wert"],
    eco.trackedItems.map(i => [i.material.toLowerCase().replaceAll("_", " "), nf.format(i.count), delta(i.count, i.weekAgo), i.value > 0 ? nf.format(i.value) : "–"]),
    [1, 2, 3]));
  replace("top", table(["#", "Spieler", "Kontostand"],
    eco.top.map((a, i) => [i + 1, a.name, money(a.balance, eco)]), [2]));
  renderChart(eco.valueHistory, eco);
}

function renderChart(points, eco) {
  const box = document.getElementById("chart");
  if (points.length < 2) {
    box.replaceChildren(el("p", { class: "empty" }, "Noch zu wenige Erfassungen für einen Verlauf."));
    return;
  }
  const W = 800, H = 220, L = 70, R = 12, T = 12, B = 28;
  const xs = points.map(p => p.time), ys = points.map(p => p.value);
  const x0 = Math.min(...xs), x1 = Math.max(...xs);
  let y0 = Math.min(...ys), y1 = Math.max(...ys);
  if (y0 === y1) { y0 -= 1; y1 += 1; }
  const sx = t => L + (t - x0) / (x1 - x0 || 1) * (W - L - R);
  const sy = v => T + (1 - (v - y0) / (y1 - y0)) * (H - T - B);

  const svg = document.createElementNS(SVG, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", `Item-Wert von ${money(ys[0], eco)} auf ${money(ys[ys.length - 1], eco)}`);
  const add = (tag, attrs, text) => {
    const n = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
    if (text != null) n.textContent = text;
    svg.append(n);
  };
  for (const v of [y0, (y0 + y1) / 2, y1]) {
    add("line", { x1: L, x2: W - R, y1: sy(v), y2: sy(v), class: "grid-line" });
    add("text", { x: L - 8, y: sy(v) + 4, "text-anchor": "end", class: "axis" }, nf.format(Math.round(v)));
  }
  add("text", { x: L, y: H - 8, class: "axis" }, dtf.format(new Date(x0)));
  add("text", { x: W - R, y: H - 8, "text-anchor": "end", class: "axis" }, dtf.format(new Date(x1)));
  add("polyline", { points: points.map(p => `${sx(p.time)},${sy(p.value)}`).join(" "), class: "series" });
  const last = points[points.length - 1];
  add("circle", { cx: sx(last.time), cy: sy(last.value), r: 3.5, class: "dot" });
  box.replaceChildren(svg);
}

function renderNews(s) {
  const prio = ["", "Info", "Normal", "Wichtig", "DRINGEND"];
  replace("news", table(["#", "Titel", "Priorität", "Erstellt"],
    s.news.map(n => [n.id, n.title, prio[n.priority] || n.priority, dtf.format(new Date(n.created))])));
}

async function load() {
  try {
    const res = await fetch("/api/stats", { credentials: "same-origin", cache: "no-store" });
    if (res.status === 401) { location.reload(); return; }
    const s = await res.json();
    if (!s.server) return;
    renderKpis(s);
    renderPlayers(s);
    renderModeration(s);
    renderEconomy(s);
    renderNews(s);
    document.getElementById("meta").textContent =
      `Minecraft ${s.server.version} · SEssentials ${s.server.plugin} · Stand ${dtf.format(new Date(s.generatedAt))}`;
  } catch (e) {
    document.getElementById("meta").textContent = "Server nicht erreichbar – neuer Versuch in 30 Sekunden.";
  }
}

// Navigation: CreativeLog nur mit Recht anzeigen
fetch("/api/me", { credentials: "same-origin" }).then(r => r.ok ? r.json() : null).then(me => {
  if (me && me.creativeLog) document.getElementById("nav-creativelog").hidden = false;
}).catch(() => {});

load();
setInterval(load, REFRESH_MS);
