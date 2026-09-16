/* Energie Web-Anzeige: liest, was die Zentrale nach Supabase schreibt, und schickt
   Handschalter und Laderegeln als Auftraege zurueck. Kein Rahmenwerk, kein Build-Schritt. */
(function () {
  "use strict";
  const CFG = window.ENERGIE_CONFIG;
  const SESSION_KEY = "energie.session";
  const HOLD_MS = 3 * 60 * 1000;          // nach eigenem Auftrag: eingehenden Stand so lange nicht ueber das Formular legen
  const MAX_GAP_S = 90 * 60;              // Luecken darueber zaehlen nicht in die Energiesumme (wie in der App)
  const $ = (id) => document.getElementById(id);

  // ------------------------------------------------------------------ Format
  const deNum = (v, digits) => v.toLocaleString("de-DE", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  const fmtW = (w) => w == null ? "–" : Math.abs(w) >= 1000 ? deNum(w / 1000, 2) + " kW" : deNum(Math.round(w), 0) + " W";
  const fmtKw = (w) => w == null ? "–" : deNum(w / 1000, 1) + " kW";
  const fmtKwh = (wh) => wh == null ? "–" : deNum(wh / 1000, 1) + " kWh";
  const fmtPct = (p, digits = 0) => p == null ? "–" : deNum(p, digits) + " %";
  const fmtEur = (e) => e == null ? "–" : deNum(e, 2) + " €";
  const fmtTime = (d) => d ? d.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }) : "–";
  const fmtTimeS = (d) => d ? d.toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit", second: "2-digit" }) : "–";
  const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  function fmtStamp(d) {
    if (!d) return "–";
    const now = new Date();
    if (sameDay(d, now)) return "Heute, " + fmtTimeS(d) + " Uhr";
    const y = new Date(now); y.setDate(y.getDate() - 1);
    if (sameDay(d, y)) return "Gestern, " + fmtTimeS(d) + " Uhr";
    return d.toLocaleDateString("de-DE") + " " + fmtTimeS(d) + " Uhr";
  }
  const fmtAgo = (d) => {
    if (!d) return "–";
    const m = Math.round((Date.now() - d.getTime()) / 60000);
    if (m < 1) return "gerade eben";
    if (m < 60) return "vor " + m + " min";
    const h = Math.floor(m / 60);
    return "vor " + h + " h " + (m % 60) + " min";
  };
  const asDate = (s) => (s ? new Date(s) : null);

  // ------------------------------------------------------------------ Sitzung
  let session = null;
  try { session = JSON.parse(localStorage.getItem(SESSION_KEY) || "null"); } catch (e) { session = null; }
  const saveSession = (s) => { session = s; try { s ? localStorage.setItem(SESSION_KEY, JSON.stringify(s)) : localStorage.removeItem(SESSION_KEY); } catch (e) { /* privater Modus */ } };

  async function authRequest(grant, body) {
    const r = await fetch(`${CFG.supabaseUrl}/auth/v1/token?grant_type=${grant}`, {
      method: "POST", headers: { apikey: CFG.supabaseAnonKey, "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error_description || j.msg || j.error || `Anmeldung fehlgeschlagen (HTTP ${r.status})`);
    return { access: j.access_token, refresh: j.refresh_token, expiresAt: (j.expires_at || Math.floor(Date.now() / 1000) + (j.expires_in || 3600)) * 1000 };
  }
  async function ensureFreshSession() {
    if (!session) throw new Error("Nicht angemeldet");
    if (Date.now() < session.expiresAt - 60 * 1000) return;
    saveSession(await authRequest("refresh_token", { refresh_token: session.refresh }));
  }

  // ------------------------------------------------------------------ REST
  async function api(path, opts = {}, retry = true) {
    await ensureFreshSession();
    const r = await fetch(`${CFG.supabaseUrl}/rest/v1/${path}`, {
      method: opts.method || "GET",
      headers: Object.assign({ apikey: CFG.supabaseAnonKey, Authorization: `Bearer ${session.access}`, Accept: "application/json" },
        opts.body ? { "Content-Type": "application/json", Prefer: "return=minimal" } : {}),
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    });
    if (r.status === 401 && retry) {
      saveSession(await authRequest("refresh_token", { refresh_token: session.refresh }));
      return api(path, opts, false);
    }
    if (!r.ok) throw new Error(`Datenbank: HTTP ${r.status} ${await r.text().catch(() => "")}`.trim());
    return r.status === 204 || opts.method === "POST" ? null : r.json();
  }
  const sendCommand = (kind, payload) => api("commands", { method: "POST", body: { kind, payload } });

  // ------------------------------------------------------------------ Zustand
  const state = {
    live: null, hubSeenAt: null, plain: null, rules: null, latest: null, alerts: [], commands: [],
    daySamples: new Map(), // Tagesschluessel -> Liste
    day: new Date(), tab: "overview",
    overridePending: null, rulesHoldUntil: 0, formRules: null,
  };
  const dayKey = (d) => d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
  const startOfDay = (d) => { const x = new Date(d); x.setHours(0, 0, 0, 0); return x; };

  // ------------------------------------------------------------------ Laden
  async function loadCore() {
    const [status, settings, latest, alerts, commands] = await Promise.all([
      api("status?select=live,hub_seen_at"),
      api("settings?select=plain,updated_at"),
      api("samples?select=at,data&order=at.desc&limit=1"),
      api("alerts?select=id,created_at,kind,title,body&order=created_at.desc&limit=15"),
      api("commands?select=id,created_at,kind,payload,done_at,result&order=created_at.desc&limit=8"),
    ]);
    state.live = status[0] ? status[0].live : null;
    state.hubSeenAt = status[0] ? asDate(status[0].hub_seen_at) : null;
    state.plain = settings[0] ? settings[0].plain : {};
    try { state.rules = state.plain.chargeRules ? JSON.parse(state.plain.chargeRules) : null; } catch (e) { state.rules = null; }
    state.latest = latest[0] ? latest[0].data : null;
    state.alerts = alerts; state.commands = commands;
  }
  async function loadDay(d) {
    const from = startOfDay(d), to = new Date(from); to.setDate(to.getDate() + 1);
    const out = [];
    for (let offset = 0; ; offset += 1000) {
      const page = await api(`samples?select=data&at=gte.${from.toISOString()}&at=lt.${to.toISOString()}&order=at.asc&limit=1000&offset=${offset}`);
      page.forEach((r) => out.push(r.data));
      if (page.length < 1000) break;
    }
    state.daySamples.set(dayKey(d), out);
    return out;
  }

  // ------------------------------------------------------------------ Rechnen (wie DailyEnergy.of in der App)
  const gridOf = (s) => s.meterGridPowerW != null ? s.meterGridPowerW : s.senecGridPowerW;
  function totalsOf(samples) {
    let prod = 0, cons = 0, imp = 0, exp = 0, chg = 0, dis = 0, car = 0, carGrid = 0;
    const mean = (x, y) => (x != null && y != null) ? (x + y) / 2 : (x != null ? x : y);
    for (let i = 1; i < samples.length; i++) {
      const a = samples[i - 1], b = samples[i];
      const dt = (new Date(b.at) - new Date(a.at)) / 1000;
      if (dt <= 0 || dt > MAX_GAP_S) continue;
      const h = dt / 3600;
      let m;
      if ((m = mean(a.productionW, b.productionW)) != null) prod += m * h;
      if ((m = mean(a.consumptionW, b.consumptionW)) != null) cons += m * h;
      if ((m = mean(gridOf(a), gridOf(b))) != null) { if (m >= 0) imp += m * h; else exp += -m * h; }
      if ((m = mean(a.batteryPowerW, b.batteryPowerW)) != null) { if (m >= 0) chg += m * h; else dis += -m * h; }
      if (a.carChargePowerW != null || b.carChargePowerW != null) {
        const carW = ((a.carChargePowerW || 0) + (b.carChargePowerW || 0)) / 2;
        car += carW * h;
        const consW = mean(a.consumptionW, b.consumptionW), gridW = mean(gridOf(a), gridOf(b));
        if (consW != null && consW > 0 && gridW != null) carGrid += carW * h * Math.min(1, Math.max(0, gridW / consW));
      }
    }
    const firstOf = (k) => { const s = samples.find((x) => x[k] != null); return s ? s[k] : null; };
    const lastOf = (k) => { for (let i = samples.length - 1; i >= 0; i--) if (samples[i][k] != null) return samples[i][k]; return null; };
    const fi = firstOf("meterImportWh"), li = lastOf("meterImportWh"), fe = firstOf("meterExportWh"), le = lastOf("meterExportWh");
    return {
      productionWh: prod, consumptionWh: cons, gridImportWh: imp, gridExportWh: exp, batteryChargeWh: chg, batteryDischargeWh: dis,
      carChargeWh: car, carFromGridWh: carGrid,
      meterImportWh: fi != null && li != null ? li - fi : null, meterExportWh: fe != null && le != null ? le - fe : null,
    };
  }
  function hourlyOf(samples) {
    const bins = Array.from({ length: 24 }, () => ({ prod: 0, cons: 0, car: 0, soc: null }));
    const mean = (x, y) => (x != null && y != null) ? (x + y) / 2 : (x != null ? x : y);
    for (let i = 1; i < samples.length; i++) {
      const a = samples[i - 1], b = samples[i];
      const ta = new Date(a.at), tb = new Date(b.at), dt = (tb - ta) / 1000;
      if (dt <= 0 || dt > MAX_GAP_S) continue;
      const h = dt / 3600, bin = bins[ta.getHours()];
      let m;
      if ((m = mean(a.productionW, b.productionW)) != null) bin.prod += m * h;
      if ((m = mean(a.consumptionW, b.consumptionW)) != null) bin.cons += m * h;
      bin.car += ((a.carChargePowerW || 0) + (b.carChargePowerW || 0)) / 2 * h;
      if (b.batterySocPercent != null) bin.soc = b.batterySocPercent;
    }
    return bins;
  }

  // ------------------------------------------------------------------ Anzeige: Kopf
  function renderHeader() {
    const at = state.latest ? asDate(state.latest.at) : null;
    $("stamp").textContent = at ? "Stand " + fmtStamp(at) : "Noch keine Messung";
    $("hub-line").textContent = state.hubSeenAt ? `Zentrale zuletzt ${fmtTime(state.hubSeenAt)} (${fmtAgo(state.hubSeenAt)})` : "Zentrale noch nie gesehen";
    const silent = state.hubSeenAt && (Date.now() - state.hubSeenAt.getTime()) > CFG.hubSilentMinutes * 60000;
    const banner = $("banner");
    if (silent) { banner.textContent = `Die Zentrale hat seit ${fmtAgo(state.hubSeenAt).replace("vor ", "")} nichts geschrieben.`; banner.hidden = false; }
    else if (state.live && (state.live.senecError || state.live.fritzError)) { banner.textContent = "Zentrale meldet: " + (state.live.senecError || state.live.fritzError); banner.hidden = false; }
    else banner.hidden = true;
  }

  // ------------------------------------------------------------------ Anzeige: Energiefluss
  function renderFlow() {
    const s = state.latest || {};
    const svg = $("flow");
    const prod = s.productionW, cons = s.consumptionW, grid = gridOf(s), bat = s.batteryPowerW, soc = s.batterySocPercent;
    const carW = s.carChargePowerW || (state.live && state.live.car && state.live.car.isCharging ? state.live.car.chargePowerW : null);
    const N = { sun: [64, 44], grid: [296, 44], house: [180, 126], bat: [64, 208], car: [296, 208] };
    const lines = [
      { from: "sun", to: "house", w: prod, color: "var(--sun)" },
      grid != null && grid < 0 ? { from: "house", to: "grid", w: -grid, color: "var(--export)" } : { from: "grid", to: "house", w: grid, color: "var(--grid)" },
      bat != null && bat > 0 ? { from: "house", to: "bat", w: bat, color: "var(--battery)" } : { from: "bat", to: "house", w: bat == null ? null : -bat, color: "var(--battery)" },
      { from: "house", to: "car", w: carW, color: "var(--car)" },
    ];
    let out = `<style>.fl{fill:none;stroke-width:4;stroke-linecap:round;opacity:.35}.fl.run{opacity:1;stroke-dasharray:8 10;animation:dash 1.2s linear infinite}@keyframes dash{to{stroke-dashoffset:-18}}
      .nd{fill:var(--card);stroke-width:3}.lb{font:600 12px sans-serif;fill:var(--text);text-anchor:middle}.vl{font:700 15px sans-serif;fill:var(--text);text-anchor:middle}.sm{font:11px sans-serif;fill:var(--muted);text-anchor:middle}.pw{font:600 12px sans-serif;text-anchor:middle}</style>`;
    for (const l of lines) {
      const a = N[l.from], b = N[l.to], run = l.w != null && Math.abs(l.w) >= 50;
      out += `<line class="fl${run ? " run" : ""}" x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}" stroke="${l.color}"/>`;
      if (run) {
        const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2 - 6;
        out += `<rect x="${mx - 30}" y="${my - 11}" width="60" height="16" rx="8" fill="var(--card)"/><text class="pw" x="${mx}" y="${my + 1}" fill="${l.color}">${fmtKw(l.w)}</text>`;
      }
    }
    const node = (k, color, label, value, sub) => {
      const [x, y] = N[k];
      return `<circle class="nd" cx="${x}" cy="${y}" r="32" stroke="${color}"/><text class="lb" x="${x}" y="${y - 6}">${label}</text><text class="vl" x="${x}" y="${y + 12}">${value}</text><text class="sm" x="${x}" y="${y + 46}">${sub || ""}</text>`;
    };
    const gridSub = grid == null ? "" : grid < -50 ? "Einspeisung" : grid > 50 ? "Bezug" : "ausgeglichen";
    const batSub = bat == null ? "" : bat > 50 ? "lädt " + fmtKw(bat) : bat < -50 ? "gibt " + fmtKw(-bat) : "ruht";
    const car = state.live && state.live.car;
    const carSub = carW ? "lädt " + fmtKw(carW) : car && car.isPluggedIn ? "steckt" : car ? "nicht angesteckt" : "";
    out += node("sun", "var(--sun)", "PV", fmtKw(prod), prod != null && prod < 50 ? "keine Erzeugung" : "");
    out += node("grid", "var(--grid)", "Netz", fmtKw(grid == null ? null : Math.abs(grid)), gridSub);
    out += node("house", "var(--house)", "Haus", fmtKw(cons), "Verbrauch");
    out += node("bat", "var(--battery)", "Speicher", fmtPct(soc), batSub);
    out += node("car", "var(--car)", "Auto", car && car.socPercent != null ? fmtPct(car.socPercent) : "–", carSub);
    svg.innerHTML = out;
    // Speicherbalken unter dem Diagramm als Legende
    $("flow-legend").innerHTML = `<span><i style="background:var(--sun)"></i>Erzeugung</span><span><i style="background:var(--grid)"></i>Netzbezug</span><span><i style="background:var(--export)"></i>Einspeisung</span><span><i style="background:var(--battery)"></i>Speicher</span><span><i style="background:var(--car)"></i>Auto</span>`;
  }

  // ------------------------------------------------------------------ Anzeige: Auto
  function kv(rows) {
    return rows.filter((r) => r).map(([k, v, cls, sub]) => `<div class="k">${k}</div><div class="v${cls ? " " + cls : ""}">${v}${sub ? `<span class="sub">${sub}</span>` : ""}</div>`).join("");
  }
  function overrideOn() {
    if (state.overridePending && Date.now() - state.overridePending.at < HOLD_MS) return state.overridePending.on;
    if (state.live && typeof state.live.chargeOverride === "boolean") return state.live.chargeOverride;
    return !!(state.live && /Handschalter/.test(state.live.automationStatus || ""));
  }
  function renderCar() {
    const car = state.live && state.live.car;
    const body = $("car-body");
    if (!car) { body.innerHTML = `<p class="muted">Kein Autozustand von der Zentrale.</p>`; }
    else {
      const at = asDate(car.at), statusAt = asDate(car.chargeStatusAt);
      const charging = car.isCharging === true ? "lädt" + (car.chargePowerW ? " mit " + fmtKw(car.chargePowerW) : "") : car.isPluggedIn === true ? "steckt, lädt nicht" : car.isPluggedIn === false ? "nicht angesteckt" : "unbekannt";
      const lockAt = car.extra && asDate(car.extra.lockUpdatedAt);
      const lockStale = lockAt && at && (at - lockAt) > 24 * 3600 * 1000;
      const lock = car.lockState == null ? null : lockStale && car.lockState !== "LOCKED" ? ["keine aktuelle Meldung", "", `Ford meldet seit ${fmtStamp(lockAt)} nicht neu`]
        : car.lockState === "LOCKED" ? ["abgeschlossen", "", lockAt ? "von Ford gemeldet " + fmtStamp(lockAt) : ""]
        : car.lockState === "PARTLY_LOCKED" ? ["teilweise offen", "bad", lockAt ? "von Ford gemeldet " + fmtStamp(lockAt) : ""] : ["NICHT abgeschlossen", "bad", lockAt ? "von Ford gemeldet " + fmtStamp(lockAt) : ""];
      const home = car.distanceHomeM == null ? null : car.distanceHomeM <= 300 ? "zu Hause" : deNum(car.distanceHomeM / 1000, 1) + " km entfernt";
      body.innerHTML = `<div class="kv">` + kv([
        ["Akku", fmtPct(car.socPercent), "", car.rangeKm != null ? deNum(car.rangeKm, 0) + " km Reichweite" : ""],
        ["Laden", charging, "", statusAt ? "Ladestatus von " + fmtTime(statusAt) : ""],
        car.extra && car.extra.timeToFullMinutes ? ["Voll in", Math.round(car.extra.timeToFullMinutes) + " min"] : null,
        lock ? ["Verriegelung", lock[0], lock[1], lock[2]] : null,
        home ? ["Standort", home] : null,
        car.extra && car.extra.odometerKm != null ? ["Kilometerstand", deNum(car.extra.odometerKm, 0) + " km"] : null,
        ["Abgerufen", at ? fmtTime(at) : "–"],
      ]) + `</div>`;
    }
    const on = overrideOn();
    const btn = $("override-button");
    btn.textContent = on ? "Voll laden beenden" : "Jetzt voll laden";
    btn.classList.toggle("on", on);
    btn.disabled = !state.live;
    $("override-hint").textContent = on ? "Handschalter aktiv: Das Auto lädt bis voll, die Regeln pausieren. Endet beim Abstecken." : "Lädt das Auto bis voll, unabhängig vom Speicherstand. Endet beim Abstecken.";
    $("automation-line").textContent = state.live && state.live.automationStatus ? "Automatik: " + state.live.automationStatus : "";
  }

  // ------------------------------------------------------------------ Anzeige: Heute / Statistik
  const price = () => parseFloat(state.plain && state.plain.pricePerKwh) || 0;
  const feedIn = () => parseFloat(state.plain && state.plain.feedInPerKwh) || 0;
  function totalsRows(t, compact) {
    const imp = t.meterImportWh != null ? t.meterImportWh : t.gridImportWh;
    const exp = t.meterExportWh != null ? t.meterExportWh : t.gridExportWh;
    const meter = t.meterImportWh != null;
    const autark = t.consumptionWh > 0 ? Math.min(100, Math.max(0, (t.consumptionWh - imp) / t.consumptionWh * 100)) : null;
    const own = t.productionWh > 0 ? Math.min(100, Math.max(0, (t.productionWh - exp) / t.productionWh * 100)) : null;
    return [
      ["Erzeugung", fmtKwh(t.productionWh)],
      ["Verbrauch", fmtKwh(t.consumptionWh)],
      ["Netzbezug", fmtKwh(imp), "", (meter ? "Zähler · " : "") + fmtEur(imp / 1000 * price())],
      ["Einspeisung", fmtKwh(exp), "", (meter ? "Zähler · " : "") + fmtEur(exp / 1000 * feedIn())],
      compact ? null : ["Speicher geladen", fmtKwh(t.batteryChargeWh)],
      compact ? null : ["Speicher entladen", fmtKwh(t.batteryDischargeWh)],
      t.carChargeWh > 50 ? ["Auto geladen", fmtKwh(t.carChargeWh), "", "davon Netz " + fmtKwh(t.carFromGridWh)] : null,
      ["Autarkie", fmtPct(autark)],
      compact ? null : ["Eigenverbrauch", fmtPct(own), "", "Anteil der Erzeugung, der im Haus blieb"],
    ];
  }
  function renderToday() {
    const samples = state.daySamples.get(dayKey(new Date())) || [];
    $("today-body").innerHTML = samples.length < 2 ? `<div class="k muted">Noch keine Tageswerte.</div>` : kv(totalsRows(totalsOf(samples), true));
  }
  function renderStats() {
    const d = state.day, today = new Date();
    $("day-label").textContent = sameDay(d, today) ? "Heute" : d.toLocaleDateString("de-DE", { weekday: "short", day: "2-digit", month: "2-digit", year: "numeric" });
    $("day-next").disabled = sameDay(d, today);
    const samples = state.daySamples.get(dayKey(d));
    if (!samples) { $("stats-body").innerHTML = `<div class="k muted">Lade …</div>`; $("chart").innerHTML = ""; return; }
    if (samples.length < 2) { $("stats-body").innerHTML = `<div class="k muted">Keine Messpunkte an diesem Tag.</div>`; $("chart").innerHTML = ""; $("plugs-card").hidden = true; return; }
    const t = totalsOf(samples);
    $("stats-body").innerHTML = kv(totalsRows(t, false));
    renderChart(hourlyOf(samples));
    renderPlugs(samples, t);
  }
  function renderChart(bins) {
    const W = 360, H = 180, left = 30, right = 36, top = 18, bottom = 22, cw = (W - left - right) / 24;
    const maxWh = Math.max(500, ...bins.map((b) => Math.max(b.prod, b.cons)));
    const y = (wh) => top + (H - top - bottom) * (1 - wh / maxWh);
    let out = `<style>.ax{font:10px sans-serif;fill:var(--muted)}.gl{stroke:var(--line);stroke-width:1}</style>`;
    const steps = maxWh > 4000 ? 2000 : maxWh > 2000 ? 1000 : 500;
    for (let v = 0; v <= maxWh; v += steps) out += `<line class="gl" x1="${left}" x2="${W - right}" y1="${y(v)}" y2="${y(v)}"/><text class="ax" x="${left - 4}" y="${y(v) + 3}" text-anchor="end">${deNum(v / 1000, v % 1000 ? 1 : 0)}</text>`;
    bins.forEach((b, h) => {
      const x = left + h * cw;
      out += `<rect x="${x + 1}" y="${y(b.prod)}" width="${cw / 2 - 1}" height="${y(0) - y(b.prod)}" fill="var(--sun)"/>`;
      out += `<rect x="${x + cw / 2}" y="${y(b.cons)}" width="${cw / 2 - 1}" height="${y(0) - y(b.cons)}" fill="var(--house)"/>`;
      if (b.car > 10) out += `<rect x="${x + cw / 2}" y="${y(Math.min(b.car, b.cons))}" width="${cw / 2 - 1}" height="${y(0) - y(Math.min(b.car, b.cons))}" fill="var(--car)"/>`;
      if (h % 6 === 0) out += `<text class="ax" x="${x}" y="${H - 8}">${h} Uhr</text>`;
    });
    // Speicherstand als Linie (rechte Achse 0-100 %)
    const pts = bins.map((b, h) => b.soc == null ? null : `${left + (h + 0.5) * cw},${top + (H - top - bottom) * (1 - b.soc / 100)}`).filter(Boolean);
    if (pts.length > 1) out += `<polyline points="${pts.join(" ")}" fill="none" stroke="var(--battery)" stroke-width="2"/>`;
    out += `<text class="ax" x="${W - right + 4}" y="${top + 4}">100 %</text><text class="ax" x="${W - right + 4}" y="${y(0)}">0 %</text><text class="ax" x="2" y="9">kWh</text><text class="ax" x="${W - 2}" y="9" text-anchor="end">Speicher</text>`;
    $("chart").innerHTML = out;
  }
  function renderPlugs(samples, t) {
    let plugs = [];
    try { plugs = JSON.parse(state.plain.plugs || "[]"); } catch (e) { plugs = []; }
    const card = $("plugs-card");
    if (!plugs.length) { card.hidden = true; return; }
    const rows = [];
    let measured = 0;
    for (const p of plugs) {
      const rs = samples.filter((s) => s.plugs && s.plugs[p.id]);
      if (!rs.length) continue;
      let wh = null;
      const withCounter = rs.filter((s) => s.plugs[p.id].energyWh != null);
      if (withCounter.length >= 2) {
        const diff = withCounter[withCounter.length - 1].plugs[p.id].energyWh - withCounter[0].plugs[p.id].energyWh;
        if (diff >= 0) wh = diff;
      }
      if (wh == null) {
        wh = 0;
        for (let i = 1; i < rs.length; i++) {
          const dt = (new Date(rs[i].at) - new Date(rs[i - 1].at)) / 1000;
          if (dt <= 0 || dt > MAX_GAP_S) continue;
          wh += ((rs[i - 1].plugs[p.id].powerW || 0) + (rs[i].plugs[p.id].powerW || 0)) / 2 * dt / 3600;
        }
      }
      measured += wh;
      const share = t.consumptionWh > 0 ? wh / t.consumptionWh * 100 : null;
      rows.push([p.name, fmtKwh(wh), "", (share != null ? deNum(share, 0) + " % · " : "") + fmtEur(wh / 1000 * price())]);
    }
    if (!rows.length) { card.hidden = true; return; }
    const rest = t.consumptionWh - t.carChargeWh - measured;
    if (rest > 0) rows.push(["Übrige Verbraucher", fmtKwh(rest), "", "nicht gemessener Rest"]);
    card.hidden = false;
    $("plugs-body").innerHTML = kv(rows);
  }

  // ------------------------------------------------------------------ Anzeige: Hinweise, Auftraege, Protokoll
  const kindLabel = (c) => {
    if (c.kind === "CHARGE_OVERRIDE") return "Handschalter " + (c.payload && c.payload.on === false ? "aus" : "ein");
    if (c.kind === "SET_SETTINGS") return "Einstellungen (" + Object.keys((c.payload && c.payload.plain) || {}).join(", ") + ")";
    if (c.kind === "FORD") return "Ford: " + (c.payload && c.payload.command);
    if (c.kind === "REFRESH") return "Auto neu abfragen";
    return c.kind;
  };
  function renderLists() {
    $("alerts").innerHTML = state.alerts.length ? state.alerts.map((a) => `<li><div class="when">${fmtStamp(asDate(a.created_at))}</div><div class="title">${esc(a.title)}</div><div>${esc(a.body)}</div></li>`).join("") : `<li class="muted">Keine Hinweise.</li>`;
    $("commands").innerHTML = state.commands.length ? state.commands.map((c) => `<li><div class="when">${fmtStamp(asDate(c.created_at))}</div><div class="title">${esc(kindLabel(c))}</div><div class="${c.done_at ? "" : "muted"}">${c.done_at ? esc(c.result || "erledigt") : "wartet auf die Zentrale …"}</div></li>`).join("") : `<li class="muted">Noch keine Aufträge.</li>`;
    $("charge-log").textContent = (state.plain && state.plain.chargeLog) || "Noch kein Protokoll.";
    $("auto-status").textContent = state.live && state.live.automationStatus ? "Zuletzt: " + state.live.automationStatus : "Noch keine Rückmeldung der Automatik.";
  }
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  // ------------------------------------------------------------------ Regeln
  const R = { enabled: "r-enabled", batteryOnPercent: "r-on", batteryOffPercent: "r-off", surplusOnW: "r-surplus", carReservePercent: "r-reserve", minCommandGapMinutes: "r-gap" };
  const minutesToTime = (m) => String(Math.floor((m || 0) / 60)).padStart(2, "0") + ":" + String((m || 0) % 60).padStart(2, "0");
  const timeToMinutes = (t) => { const [h, m] = (t || "0:0").split(":").map(Number); return (h || 0) * 60 + (m || 0); };
  function formRules() {
    return {
      enabled: $("r-enabled").checked,
      batteryOnPercent: +$("r-on").value, batteryOffPercent: +$("r-off").value, surplusOnW: +$("r-surplus").value,
      nightStartMinutes: timeToMinutes($("r-night-start").value), nightEndMinutes: timeToMinutes($("r-night-end").value),
      carReservePercent: +$("r-reserve").value, minCommandGapMinutes: +$("r-gap").value,
    };
  }
  function fillRules(r) {
    const d = Object.assign({ enabled: false, batteryOnPercent: 70, batteryOffPercent: 50, surplusOnW: 2000, nightStartMinutes: 0, nightEndMinutes: 0, carReservePercent: 50, minCommandGapMinutes: 15 }, r || {});
    $("r-enabled").checked = !!d.enabled;
    for (const k of ["batteryOnPercent", "batteryOffPercent", "surplusOnW", "carReservePercent", "minCommandGapMinutes"]) $(R[k]).value = d[k];
    $("r-night-start").value = minutesToTime(d.nightStartMinutes);
    $("r-night-end").value = minutesToTime(d.nightEndMinutes);
    state.formRules = formRules();
    updateRuleOutputs();
  }
  function updateRuleOutputs() {
    const f = formRules();
    $("r-on-out").textContent = f.batteryOnPercent + " %";
    $("r-off-out").textContent = f.batteryOffPercent + " %";
    $("r-surplus-out").textContent = fmtW(f.surplusOnW);
    $("r-reserve-out").textContent = f.carReservePercent + " %";
    $("r-gap-out").textContent = f.minCommandGapMinutes + " min";
    $("rules-warning").hidden = f.batteryOffPercent < f.batteryOnPercent;
    const dirty = JSON.stringify(f) !== JSON.stringify(state.formRules);
    $("rules-save").disabled = !dirty;
  }
  function renderRules() {
    // Waehrend der Nutzer aendert oder gerade gespeichert hat, nicht ueber das Formular schreiben.
    if (Date.now() < state.rulesHoldUntil) return;
    if (state.formRules && JSON.stringify(formRules()) !== JSON.stringify(state.formRules)) return;
    fillRules(state.rules);
  }

  // ------------------------------------------------------------------ Aktionen
  async function toggleOverride() {
    const on = !overrideOn();
    const btn = $("override-button");
    btn.disabled = true;
    try {
      await sendCommand("CHARGE_OVERRIDE", { on });
      state.overridePending = { on, at: Date.now() };
      $("override-hint").textContent = on ? "Auftrag gesendet: Die Zentrale schaltet mit ihrem nächsten Lauf auf „voll laden“ (bis zu einer Minute)." : "Auftrag gesendet: Der Handschalter wird mit dem nächsten Lauf der Zentrale ausgeschaltet.";
      renderCar();
      setTimeout(refresh, 70 * 1000);
    } catch (e) { $("override-hint").textContent = "Auftrag fehlgeschlagen: " + e.message; }
    btn.disabled = false;
  }
  async function saveRules(ev) {
    ev.preventDefault();
    let r = formRules();
    if (r.batteryOffPercent >= r.batteryOnPercent) r.batteryOffPercent = Math.max(0, r.batteryOnPercent - 10);
    $("rules-save").disabled = true;
    try {
      await sendCommand("SET_SETTINGS", { plain: { chargeRules: JSON.stringify(r) } });
      state.rulesHoldUntil = Date.now() + HOLD_MS;
      fillRules(r);
      $("rules-message").textContent = "Gesendet " + fmtTime(new Date()) + ". Die Zentrale übernimmt die Regeln mit ihrem nächsten Lauf; die Bestätigung erscheint unter „Aufträge“.";
      setTimeout(refresh, 70 * 1000);
    } catch (e) { $("rules-message").textContent = "Speichern fehlgeschlagen: " + e.message; $("rules-save").disabled = false; }
  }

  // ------------------------------------------------------------------ Ablauf
  let refreshing = false;
  async function refresh() {
    if (!session || refreshing || document.hidden) return;
    refreshing = true;
    $("refresh").classList.add("spin");
    try {
      await loadCore();
      const today = new Date();
      await loadDay(today);
      if (state.tab === "stats" && !sameDay(state.day, today) && !state.daySamples.has(dayKey(state.day))) await loadDay(state.day);
      renderAll();
    } catch (e) {
      if (/Nicht angemeldet|refresh_token|invalid_grant|HTTP 401/i.test(e.message)) return logout();
      const b = $("banner"); b.textContent = "Abgleich fehlgeschlagen: " + e.message; b.hidden = false;
    } finally { refreshing = false; $("refresh").classList.remove("spin"); }
  }
  function renderAll() { renderHeader(); renderFlow(); renderCar(); renderToday(); renderStats(); renderLists(); renderRules(); }

  function showTab(name) {
    state.tab = name;
    document.querySelectorAll(".tab").forEach((el) => { el.hidden = el.id !== "tab-" + name; });
    document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    if (name === "stats" && !state.daySamples.has(dayKey(state.day))) loadDay(state.day).then(renderStats).catch(() => {});
    window.scrollTo(0, 0);
  }
  function shiftDay(delta) {
    const d = new Date(state.day); d.setDate(d.getDate() + delta);
    if (d > new Date()) return;
    state.day = d;
    renderStats();
    if (!state.daySamples.has(dayKey(d))) loadDay(d).then(renderStats).catch((e) => { $("stats-body").innerHTML = `<div class="k error">${esc(e.message)}</div>`; });
  }

  async function login(ev) {
    ev.preventDefault();
    const btn = $("login-button"), err = $("login-error");
    btn.disabled = true; err.hidden = true;
    try {
      saveSession(await authRequest("password", { email: $("login-email").value.trim(), password: $("login-password").value }));
      $("login-password").value = "";
      show();
    } catch (e) { err.textContent = e.message; err.hidden = false; }
    btn.disabled = false;
  }
  function logout() { saveSession(null); state.live = null; show(); }
  function show() {
    const inApp = !!session;
    $("login").hidden = inApp; $("app").hidden = !inApp;
    if (inApp) refresh();
  }

  // Ereignisse
  $("login-form").addEventListener("submit", login);
  $("logout").addEventListener("click", logout);
  $("refresh").addEventListener("click", refresh);
  $("override-button").addEventListener("click", toggleOverride);
  $("rules-form").addEventListener("submit", saveRules);
  $("rules-form").addEventListener("input", updateRuleOutputs);
  $("day-prev").addEventListener("click", () => shiftDay(-1));
  $("day-next").addEventListener("click", () => shiftDay(1));
  document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => showTab(b.dataset.tab)));
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
  setInterval(refresh, (CFG.refreshSeconds || 60) * 1000);
  setInterval(() => { if (session) renderHeader(); }, 30 * 1000);
  show();
})();
