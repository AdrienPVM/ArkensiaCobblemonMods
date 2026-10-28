// ── FactionWar — "Guerre de Factions" ────────────────────────────────────────
//
// Admin + live monitoring view for the FactionWar event mod. Follows the same
// build*(app) pattern as the other CheeseDash views: an async builder that
// fetches data, stores mutable state on window._fw, returns an HTML string, and
// re-renders via app.navigate(). Admin actions are wired through onclick handlers
// on window._fw, mirroring buildStore / buildMusic.
//
// This view aims at TOTAL admin control: it surfaces a UI control for every
// /factionwar command family via the mod's REST endpoints (API.factionwar.*).
// Each action posts, toasts the {result} string, and reloads ONLY the affected
// fragment (never a global re-render that would stomp the 3s poll / input focus).
// Endpoints a given mod build may not implement just 404 — every handler catches
// and toasts, so a missing endpoint degrades gracefully and never breaks the view.
//
// Sections (top → bottom):
//   Tableau de guerre (live board)          ·  Phase, jour & finale
//   Fenêtre PvP, gel & mort subite          ·  Vote & draft
//   Factions / Roster (noms, couleurs,         ·  Scores & pools
//     capitaines, spawns, affectations)      ·  Zones de capture
//   Objectifs                                ·  Origine de faction
//   Modération                               ·  Broadcast
//   Config (à chaud)                         ·  Reset
//
// REST contract (FactionWarApi.java + FactionWarCommands.java, base /api/v1, prefix factionwar):
//   GET  /state    -> { phase, factions{A,B{name,color,count,captain,members[]}},
//                       lifePool{A,B}, scores{A,B}, captures{zones:[…]},
//                       objectives{objectives:[…]}, pvpWindow:0|1, frozen:bool,
//                       phaseState{day,maxDay,finale,autoAdvance,…}, moderation{muted[],jailed[]},
//                       factionOrigins{available:[{id,name}],default,A,B} }

async function buildFactionWar(app) {
  const _fw = window._fw || {};
  _fw._app = app;
  _fw.state = _fw.state || {};
  _fw.config = _fw.config || {};
  _fw.origins = _fw.origins || null;        // { available:[{id,name}], default, A, B } | null when unavailable
  _fw.players = _fw.players || { online: [], known: [] };  // GET /players -> picker data for player datalists
  window._fw = _fw;

  // Load the player roster used to populate every player <datalist> (online + known).
  // Optional endpoint: a 404/older build just leaves the lists empty (inputs stay free-text).
  _fw.loadPlayers = async function() {
    try {
      const p = await API.factionwar.players();
      _fw.players = {
        online: Array.isArray(p && p.online) ? p.online : [],
        known:  Array.isArray(p && p.known)  ? p.known  : [],
      };
    } catch (e) { /* keep last list; inputs stay usable as free text */ }
    _fwRepaintPlayerLists();
  };

  // ── Data load ────────────────────────────────────────────────────────────
  _fw.load = async function() {
    try {
      _fw.state = await API.factionwar.state();
    } catch (e) {
      app.toast(e.message, 'error');
      _fw.state = null;
    }
    try {
      _fw.config = await API.factionwar.config();
    } catch (e) {
      _fw.config = {};
    }
    // Faction origins are optional (endpoint may not exist on older mod builds).
    // The live /state already carries `factionOrigins`; fall back to that, then to
    // the dedicated endpoint, then to null => graceful "indisponible" panel.
    _fw.origins = _fwExtractOrigins(_fw.state);
    if (!_fw.origins) {
      try { _fw.origins = await API.factionwar.origins(); }
      catch (e) { _fw.origins = null; }
    }
  };

  await _fw.load();
  await _fw.loadPlayers();

  if (_fw.state === null) {
    return `<div class="empty-state"><span class="empty-state-icon">&#x26A0;</span><span class="empty-state-text">FactionWar indisponible (le serveur de jeu est-il démarré ?)</span></div>`;
  }

  // Re-render the whole view (used rarely — after structural changes like reset).
  _fw._rerender = function() { app.viewHtml = ''; app.navigate('factionwar'); };

  // Targeted reloads — refresh one fragment from a fresh /state, never the whole view,
  // so we don't clobber input focus or fight the poll.
  _fw._reloadState = async function() {
    try { _fw.state = await API.factionwar.state(); } catch (e) { return; }
    _fwRepaint(_fw.state);
  };

  // ── Live refresh ──────────────────────────────────────────────────────────
  // Light self-managed poll: refresh state every 3s while this view is open,
  // then repaint only the live fragments (avoids stomping on input focus).
  if (_fw._pollTimer) { clearInterval(_fw._pollTimer); _fw._pollTimer = null; }
  _fw._pollTimer = setInterval(async () => {
    if (app.currentView !== 'factionwar') { clearInterval(_fw._pollTimer); _fw._pollTimer = null; return; }
    try {
      _fw.state = await API.factionwar.state();
      _fwRepaint(_fw.state);
    } catch (e) { /* transient — keep last board */ }
  }, 3000);

  // Slower poll for the player picker lists (online roster changes far less often).
  if (_fw._playersTimer) { clearInterval(_fw._playersTimer); _fw._playersTimer = null; }
  _fw._playersTimer = setInterval(async () => {
    if (app.currentView !== 'factionwar') { clearInterval(_fw._playersTimer); _fw._playersTimer = null; return; }
    await _fw.loadPlayers();
  }, 15000);

  // ── Admin action plumbing ────────────────────────────────────────────────
  _fw._toastResult = function(r) {
    const msg = (r && (r.result || (r.ok ? 'OK' : null))) || 'OK';
    app.toast(msg, 'success');
  };
  // Run an action, toast its result, then reload the given fragment(s).
  // `reload` is a function (defaults to a full /state repaint).
  _fw._do = async function(fn, reload) {
    try {
      const r = await fn();
      _fw._toastResult(r);
      if (reload) await reload(); else await _fw._reloadState();
    } catch (e) { app.toast(e.message, 'error'); }
  };
  const val = (id) => { const el = document.getElementById(id); return el ? el.value : ''; };
  const numVal = (id) => parseInt(val(id), 10);

  // ── PvP / freeze / finale ─────────────────────────────────────────────────
  _fw.pvpOn  = () => { if (_fw.state) _fw.state.pvpWindow = 1; _fw._do(() => API.factionwar.pvpOn()); };
  _fw.pvpOff = () => { if (_fw.state) _fw.state.pvpWindow = 0; _fw._do(() => API.factionwar.pvpOff()); };
  _fw.freeze = (on) => _fw._do(() => API.factionwar.freeze(on));
  _fw.finale = (on) => _fw._do(() => API.factionwar.phaseAction('finale', { on: !!on }));

  // ── Phase / day ────────────────────────────────────────────────────────────
  _fw.phaseSetDay = () => {
    const d = numVal('fw-phase-day');
    if (isNaN(d)) { app.toast('Jour invalide.', 'error'); return; }
    _fw._do(() => API.factionwar.phaseAction('set', { day: d }));
  };
  _fw.phaseNext = () => _fw._do(() => API.factionwar.phaseAction('next'));
  _fw.phaseAuto = (on) => _fw._do(() => API.factionwar.phaseAction('auto', { on: !!on }));

  // ── Vote & draft ─────────────────────────────────────────────────────────
  _fw.voteStart = () => {
    const m = numVal('fw-vote-minutes');
    _fw._do(() => API.factionwar.voteStart(isNaN(m) ? null : m));
  };
  _fw.voteClose = () => _fw._do(() => API.factionwar.voteClose());
  _fw.voteCast = () => {
    const voter = val('fw-vote-voter').trim(), target = val('fw-vote-target').trim();
    if (!voter || !target) { app.toast('Votant et cible requis.', 'error'); return; }
    _fw._do(() => API.factionwar.voteCast(voter, target));
  };
  _fw.draftPick = () => {
    const cap = val('fw-draft-captain').trim(), player = val('fw-draft-player').trim();
    if (!cap || !player) { app.toast('Capitaine et joueur requis.', 'error'); return; }
    _fw._do(() => API.factionwar.draftPick(cap, player));
  };

  // ── Scores & pools ───────────────────────────────────────────────────────
  _fw.setPool = () => {
    const f = val('fw-pool-faction'), v = numVal('fw-pool-value');
    if (isNaN(v)) { app.toast('Valeur invalide.', 'error'); return; }
    _fw._do(() => API.factionwar.setPool(f, v));
  };
  _fw.poolRespawn = () => {
    const p = val('fw-pool-respawn-player').trim();
    if (!p) { app.toast('Pseudo requis.', 'error'); return; }
    _fw._do(() => API.factionwar.poolRespawn(p));
  };
  _fw.setPoints = () => {
    const f = val('fw-points-faction'), v = numVal('fw-points-value');
    if (isNaN(v)) { app.toast('Valeur invalide.', 'error'); return; }
    _fw._do(() => API.factionwar.setPoints(f, v));
  };
  _fw.addPoints = () => {
    const f = val('fw-points-add-faction'), d = numVal('fw-points-add-delta');
    if (isNaN(d)) { app.toast('Delta invalide.', 'error'); return; }
    _fw._do(() => API.factionwar.addPoints(f, d));
  };
  _fw.pointsRecap   = () => _fw._do(() => API.factionwar.pointsRecap());
  _fw.pointsResolve = () => _fw._do(() => API.factionwar.pointsResolve());

  // Read-only readiness check. Renders the result INTO the page (not just a toast),
  // so the admin actually sees what is/isn't ready before launching.
  _fw.preflight = async () => {
    const out = document.getElementById('fw-preflight-out');
    if (out) out.innerHTML = '<span style="color:var(--muted)">Vérification…</span>';
    try {
      const r = await API.factionwar.preflight();
      if (out) out.innerHTML = _fwRenderPreflightResult(r);
    } catch (e) {
      if (out) out.innerHTML = `<span style="color:var(--red,#ef4444)">${escHtml(e.message)}</span>`;
      else app.toast(e.message, 'error');
    }
  };

  // ── Roster / factions ────────────────────────────────────────────────────
  _fw.setFaction = () => {
    const p = val('fw-faction-player').trim(), f = val('fw-faction-faction');
    if (!p) { app.toast('Pseudo requis.', 'error'); return; }
    _fw._do(() => API.factionwar.setFaction(p, f), () => _fw._reloadRoster());
  };
  _fw.setCaptain = () => {
    const p = val('fw-captain-player').trim(), f = val('fw-captain-faction');
    if (!p) { app.toast('Pseudo requis.', 'error'); return; }
    _fw._do(() => API.factionwar.setCaptain(p, f), () => _fw._reloadRoster());
  };
  _fw.setName = (faction) => {
    const name = val('fw-name-' + faction).trim();
    if (!name) { app.toast('Nom requis.', 'error'); return; }
    _fw._do(() => API.factionwar.setName(faction, name), () => _fw._reloadRoster());
  };
  _fw.setColor = (faction) => {
    const color = val('fw-color-' + faction);
    _fw._do(() => API.factionwar.setColor(faction, color), () => _fw._reloadRoster());
  };
  _fw.setSpawn = (faction) =>
    _fw._do(() => API.factionwar.setSpawn(faction), async () => { _fw.config = await API.factionwar.config().catch(() => _fw.config); });

  // ── Objectives ───────────────────────────────────────────────────────────
  _fw.objComplete = () => {
    const id = val('fw-obj-id').trim(), f = val('fw-obj-faction');
    if (!id) { app.toast('ID objectif requis.', 'error'); return; }
    _fw._do(() => API.factionwar.objComplete(id, f), () => _fw._reloadObjectives());
  };
  // Per-row quick complete (→A / →B buttons in the objectives table).
  _fw.objCompleteId = (id, faction) => _fw._do(() => API.factionwar.objComplete(id, faction), () => _fw._reloadObjectives());
  _fw.objResetDaily = () => _fw._do(() => API.factionwar.objResetDaily(), () => _fw._reloadObjectives());

  // Targeted fragment reloads (fresh /state -> repaint just that block).
  _fw._reloadRoster = async function() {
    try { _fw.state = await API.factionwar.state(); } catch (e) { return; }
    try { _fw.config = await API.factionwar.config(); } catch (e) { /* keep */ }
    _fwReloadFragment('fw-roster', _fwRenderRosterInner(_fw.state));
    _fwReloadFragment('fw-board', _fwRenderBoardInner(_fw.state));
  };
  _fw._reloadObjectives = async function() {
    try { _fw.state = await API.factionwar.state(); } catch (e) { return; }
    _fwReloadFragment('fw-objectives', _fwRenderObjectivesInner(_fw.state));
  };

  // ── Respec ───────────────────────────────────────────────────────────────
  _fw.respec = () => {
    const p = val('fw-respec-player').trim(), c = val('fw-respec-class');
    if (!p) { app.toast('Pseudo requis.', 'error'); return; }
    _fw._do(() => API.factionwar.respec(p, c));
  };

  // ── Capture zones ────────────────────────────────────────────────────────
  _fw.zoneAdd = () => {
    const id = val('fw-zone-id').trim();
    const radius = parseFloat(val('fw-zone-radius'));
    const points = numVal('fw-zone-points');
    const seconds = numVal('fw-zone-seconds');
    if (!id) { app.toast('ID de zone requis.', 'error'); return; }
    if (isNaN(radius) || isNaN(points) || isNaN(seconds)) { app.toast('Rayon / points / secondes requis.', 'error'); return; }
    _fw._do(() => API.factionwar.zoneAdd({ id, radius, points, seconds }), () => _fw._reloadState());
  };
  _fw.zoneRemove = (id) => _fw._do(() => API.factionwar.zoneRemove(id), () => _fw._reloadState());
  _fw.zoneMajor  = (id, value) => _fw._do(() => API.factionwar.zoneMajor(id, value), () => _fw._reloadState());

  // ── Faction origins ──────────────────────────────────────────────────────
  _fw._reloadOrigins = async function() {
    _fw.origins = _fwExtractOrigins(_fw.state);
    if (!_fw.origins) { try { _fw.origins = await API.factionwar.origins(); } catch (e) { _fw.origins = null; } }
    const el = document.getElementById('fw-origins');
    if (el) el.innerHTML = _fwRenderOriginsInner(_fw.origins);
  };
  _fw.setOrigin = (faction) => {
    const sel = document.getElementById('fw-origin-' + faction);
    if (!sel) return;
    const originId = sel.value;
    if (!originId) { app.toast('Choisis une origine.', 'error'); return; }
    _fw._do(() => API.factionwar.setOrigin(faction, originId), async () => { await _fw._reloadState(); await _fw._reloadOrigins(); });
  };
  _fw.clearOrigin = (faction) =>
    _fw._do(() => API.factionwar.setOrigin(faction, null), async () => { await _fw._reloadState(); await _fw._reloadOrigins(); });

  // Apply an origin to a whole faction *via its captain* (player → that player's faction).
  _fw.setOriginCaptain = () => {
    const player = val('fw-origin-captain-player').trim();
    const originId = val('fw-origin-captain-id');
    if (!player) { app.toast('Pseudo du capitaine requis.', 'error'); return; }
    if (!originId) { app.toast('Choisis une origine.', 'error'); return; }
    _fw._do(() => API.factionwar.setOriginCaptain(player, originId),
      async () => { await _fw._reloadState(); await _fw._reloadOrigins(); });
  };

  // ── Supply drop ─────────────────────────────────────────────────────────────
  _fw.supplyDrop = () => {
    const x = numVal('fw-supply-x'), y = numVal('fw-supply-y'), z = numVal('fw-supply-z');
    const dimension = val('fw-supply-dim').trim();
    if (isNaN(x) || isNaN(y) || isNaN(z)) { app.toast('Coordonnées x / y / z requises.', 'error'); return; }
    _fw._do(() => API.factionwar.supplyDrop({ x, y, z, dimension: dimension || null }), async () => {});
  };

  // ── Moderation ───────────────────────────────────────────────────────────
  _fw.mod = (action) => {
    const p = val('fw-mod-player').trim();
    if (!p) { app.toast('Pseudo requis.', 'error'); return; }
    _fw._do(() => API.factionwar.mod(action, p), () => _fw._reloadState());
  };
  _fw.modOn = (action, player) => _fw._do(() => API.factionwar.mod(action, player), () => _fw._reloadState());

  // ── Broadcast ────────────────────────────────────────────────────────────
  _fw.broadcast = () => {
    const kind = val('fw-bc-kind');
    const text = val('fw-bc-text').trim();
    const sound = val('fw-bc-sound').trim();
    const scope = val('fw-bc-scope'); // "all" | "A" | "B"
    if (!text) { app.toast('Texte requis.', 'error'); return; }
    const fn = scope === 'all'
      ? () => API.factionwar.broadcast(kind, text, sound)
      : () => API.factionwar.broadcastFaction(scope, kind, text, sound);
    _fw._do(fn, async () => {});
  };

  // ── Reset ────────────────────────────────────────────────────────────────
  _fw.reset = () => {
    if (!confirm('Réinitialiser TOUT l\'état FactionWar (rosters, vote, draft) ? Action irréversible.')) return;
    _fw._do(() => API.factionwar.reset(), () => _fw._rerender());
  };

  // ── Config ───────────────────────────────────────────────────────────────
  _fw.saveConfig = async function() {
    // Build a partial patch from the edited fields only (PUT /config is a patch).
    const patch = {};
    document.querySelectorAll('[data-fw-cfg]').forEach((el) => {
      const key = el.getAttribute('data-fw-cfg');
      const type = el.getAttribute('data-fw-type');
      if (type === 'bool') patch[key] = el.checked;
      else if (type === 'number') patch[key] = Number(el.value);
      else patch[key] = el.value;
    });
    try {
      const r = await API.factionwar.updateConfig(patch);
      app.toast(r && r.ok ? 'Config enregistrée.' : 'Échec de la sauvegarde.', r && r.ok ? 'success' : 'error');
      _fw.config = await API.factionwar.config();
    } catch (e) { app.toast(e.message, 'error'); }
  };

  // ── Render ─────────────────────────────────────────────────────────────────
  const card = (title, bodyHtml, extraStyle) => `
    <div class="section-card slide-in" style="margin-top:1rem${extraStyle ? ';' + extraStyle : ''}">
      <div class="section-card-header"><span class="section-card-title">${title}</span></div>
      <div class="section-card-body">${bodyHtml}</div>
    </div>`;

  return `<div class="max-w-6xl mx-auto slide-in">
    ${_fwPlayersDatalist()}
    <div class="flex items-center gap-3 mb-6">
      <span style="font-size:var(--type-2xl)">⚔️</span>
      <div>
        <h1 style="font-size:var(--type-xl);font-weight:700">Guerre de Factions</h1>
        <p style="color:var(--muted);font-size:var(--type-sm)">Suivi en direct & contrôle admin total de l'événement PvP</p>
      </div>
    </div>

    <!-- Live war board -->
    <div class="section-card slide-in">
      <div class="section-card-header"><span class="section-card-title">Tableau de guerre</span></div>
      <div class="section-card-body"><div id="fw-board">${_fwRenderBoardInner(_fw.state)}</div></div>
    </div>

    ${card('Phase, jour & finale', `<div id="fw-phase">${_fwRenderPhaseInner(_fw.state)}</div>`)}

    ${card('Fenêtre PvP, gel & mort subite', `<div id="fw-pvp">${_fwRenderPvpInner(_fw.state)}</div>`)}

    ${card('Vote & draft', _fwRenderVoteDraft())}

    ${card('Factions / Roster', `<div id="fw-roster">${_fwRenderRosterInner(_fw.state)}</div>`)}

    ${card('Scores & pools de vies', _fwRenderScores())}

    ${card('Zones de capture', `<div id="fw-zones-admin">${_fwRenderZonesAdminInner(_fw.state)}</div>`)}

    ${card('Objectifs', `<div id="fw-objectives">${_fwRenderObjectivesInner(_fw.state)}</div>`)}

    ${card('Origine de faction', `<div id="fw-origins">${_fwRenderOriginsInner(_fw.origins)}</div>`)}

    ${card('Ravitaillement (supply drop)', _fwRenderSupplyDrop())}

    ${card('Modération', `<div id="fw-mod">${_fwRenderModInner(_fw.state)}</div>`)}

    ${card('Broadcast', _fwRenderBroadcast())}

    ${card('Config (appliquée à chaud)', _fwRenderConfig(_fw.config))}

    ${card('Zone de danger', _fwRenderReset(), 'border-left:3px solid var(--red,#ef4444)')}
  </div>`;
}

// ── Reload helpers (defined as plain functions so the poll + actions share them) ──
async function _fwReloadFragment(id, html) { const el = document.getElementById(id); if (el) el.innerHTML = html; }

// ── Player picker datalists ───────────────────────────────────────────────────
// All player-name inputs share two hidden <datalist>s: one biased toward online
// players (offered first) and a full list. We point every input at #fw-dl-players.
// Inputs stay free-text — the datalist is just an autocomplete affordance — so an
// empty/404 player endpoint never blocks an admin from typing a name.
const _FW_PLAYERS_DATALIST_ID = 'fw-dl-players';

function _fwPlayerOptionsHtml() {
  const fw = window._fw || {};
  const p = fw.players || {};
  const online = Array.isArray(p.online) ? p.online : [];
  const known = Array.isArray(p.known) ? p.known : [];
  // Online first (labelled), then known names not already online, de-duplicated.
  const seen = new Set();
  const out = [];
  online.forEach((n) => {
    if (!n || seen.has(n)) return; seen.add(n);
    out.push(`<option value="${escHtml(n)}">en ligne</option>`);
  });
  known.forEach((n) => {
    if (!n || seen.has(n)) return; seen.add(n);
    out.push(`<option value="${escHtml(n)}"></option>`);
  });
  return out.join('');
}

// The shared datalist element (rendered once near the top of the view).
function _fwPlayersDatalist() {
  return `<datalist id="${_FW_PLAYERS_DATALIST_ID}">${_fwPlayerOptionsHtml()}</datalist>`;
}

// Refresh just the <option> list inside the shared player datalist (called by the
// 15s player poll and after a manual loadPlayers), without touching any input.
function _fwRepaintPlayerLists() {
  const dl = document.getElementById(_FW_PLAYERS_DATALIST_ID);
  if (dl) dl.innerHTML = _fwPlayerOptionsHtml();
}

// Build a zone-id <datalist> from current state (used by the zone admin form).
function _fwZonesDatalist(s) {
  const zones = (s && s.captures && s.captures.zones) || [];
  const opts = zones.map((z) => {
    const label = z.name && z.name !== z.id ? z.name : '';
    return `<option value="${escHtml(String(z.id))}">${escHtml(label)}</option>`;
  }).join('');
  return `<datalist id="fw-dl-zones">${opts}</datalist>`;
}

// Build an objective-id <datalist> from current state (used by the objectives form).
function _fwObjectivesDatalist(s) {
  const objState = (s && s.objectives) || {};
  const objs = Array.isArray(objState.objectives) ? objState.objectives : [];
  const opts = objs.map((o) => `<option value="${escHtml(String(o.id))}">${escHtml(o.label || '')}</option>`).join('');
  return `<datalist id="fw-dl-objectives">${opts}</datalist>`;
}

// Repaint every live fragment from a fresh state object. Each setter guards on
// element presence so it works whether or not a given section is in the DOM.
function _fwRepaint(s) {
  if (!s) return;
  const fw = window._fw || {};
  fw.state = s;
  _fwReloadFragment('fw-board', _fwRenderBoardInner(s));
  _fwReloadFragment('fw-pvp', _fwRenderPvpInner(s));
  _fwReloadFragment('fw-phase', _fwRenderPhaseInner(s));
  _fwReloadFragment('fw-roster', _fwRenderRosterInner(s));
  _fwReloadFragment('fw-zones-admin', _fwRenderZonesAdminInner(s));
  _fwReloadFragment('fw-objectives', _fwRenderObjectivesInner(s));
  _fwReloadFragment('fw-mod', _fwRenderModInner(s));
}

// ── PvP / freeze fragment ────────────────────────────────────────────────────
function _fwRenderPvpInner(s) {
  const war = (s && (s.pvpWindow === 1 || s.pvpWindow === true));
  const frozen = !!(s && s.frozen);
  const ph = (s && s.phaseState) || {};
  const finale = !!ph.finale;
  return `
    <div style="display:flex;flex-direction:column;gap:0.75rem">
      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
        <label class="field-label" style="min-width:140px">Fenêtre PvP</label>
        <button class="btn-cg ${war ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.pvpOn()">Guerre ON</button>
        <button class="btn-cg ${!war ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.pvpOff()">Guerre OFF</button>
        <span class="status-dot ${war ? 's-online' : 's-offline'}"></span>
        <strong>${war ? 'GUERRE' : 'TRÊVE'}</strong>
      </div>
      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
        <label class="field-label" style="min-width:140px">Gel (soft-freeze)</label>
        <button class="btn-cg ${frozen ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.freeze(true)">Geler</button>
        <button class="btn-cg ${!frozen ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.freeze(false)">Dégeler</button>
        <span class="status-dot ${frozen ? 's-warn' : 's-offline'}"></span>
        <strong>${frozen ? 'GELÉ' : 'actif'}</strong>
      </div>
      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
        <label class="field-label" style="min-width:140px">Mort subite (finale)</label>
        <button class="btn-cg ${finale ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.finale(true)">Activer</button>
        <button class="btn-cg ${!finale ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.finale(false)">Désactiver</button>
        <span class="status-dot ${finale ? 's-warn' : 's-offline'}"></span>
        <strong>${finale ? '☠ MORT SUBITE' : 'normal'}</strong>
      </div>
    </div>`;
}

// ── Phase / day fragment ─────────────────────────────────────────────────────
function _fwRenderPhaseInner(s) {
  const ph = (s && s.phaseState) || {};
  const v = (x) => (x == null ? '—' : x);
  const day = ph.day != null ? ph.day : '—';
  const maxDay = ph.maxDay != null ? ph.maxDay : '—';
  const auto = !!ph.autoAdvance;
  return `<div style="display:flex;flex-direction:column;gap:0.75rem">
    <div style="display:flex;align-items:center;gap:0.75rem;flex-wrap:wrap">
      <span style="font-weight:600">Jour <span style="color:var(--amber)">${day}</span> / ${maxDay}</span>
      ${ph.label ? `<span style="color:var(--muted);font-size:var(--type-sm)">${escHtml(ph.label)}</span>` : ''}
      ${ph.finalDay ? '<span style="color:var(--amber)">★ dernier jour</span>' : ''}
      ${ph.endSealed ? '<span style="color:var(--red,#ef4444)">scellé</span>' : ''}
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Définir le jour</label>
      <input id="fw-phase-day" type="number" min="1" class="input input-sm input-bordered" style="width:90px" placeholder="jour" value="${day !== '—' ? day : ''}">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.phaseSetDay()">Appliquer</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.phaseNext()">Jour suivant →</button>
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Avancement auto</label>
      <button class="btn-cg ${auto ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.phaseAuto(true)">Auto ON</button>
      <button class="btn-cg ${!auto ? 'btn-cg-primary' : 'btn-cg-ghost'} btn-cg-sm" onclick="window._fw.phaseAuto(false)">Auto OFF</button>
      <span style="color:var(--muted);font-size:var(--type-sm)">${auto ? 'le jour avance automatiquement' : 'avancement manuel'}</span>
    </div>
  </div>`;
}

// ── Vote & draft fragment (static form) ──────────────────────────────────────
function _fwRenderVoteDraft() {
  return `<div style="display:flex;flex-direction:column;gap:0.75rem">
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Vote des capitaines</label>
      <input id="fw-vote-minutes" type="number" min="1" max="60" class="input input-sm input-bordered" style="width:90px" placeholder="minutes">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.voteStart()">Démarrer</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.voteClose()">Clôturer</button>
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Voter pour</label>
      <input id="fw-vote-voter" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:150px" placeholder="votant (pseudo)">
      <span style="color:var(--muted)">→</span>
      <input id="fw-vote-target" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:150px" placeholder="candidat (pseudo)">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.voteCast()">Enregistrer le vote</button>
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Draft — pick</label>
      <input id="fw-draft-captain" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:150px" placeholder="capitaine (pseudo)">
      <span style="color:var(--muted)">choisit</span>
      <input id="fw-draft-player" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:150px" placeholder="joueur (pseudo)">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.draftPick()">Drafter</button>
    </div>
  </div>`;
}

// ── Scores & pools fragment (static form) ────────────────────────────────────
function _fwRenderScores() {
  const facSel = (id) => `<select id="${id}" class="select select-sm select-bordered"><option value="A">A</option><option value="B">B</option></select>`;
  return `<div style="display:flex;flex-direction:column;gap:0.75rem">
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Pool de vies (set)</label>
      ${facSel('fw-pool-faction')}
      <input id="fw-pool-value" type="number" class="input input-sm input-bordered" style="width:110px" placeholder="valeur">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.setPool()">Définir</button>
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Forcer un respawn</label>
      <input id="fw-pool-respawn-player" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:160px" placeholder="pseudo">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.poolRespawn()">Respawn</button>
    </div>
    <hr style="border-color:var(--bg-border);margin:0.25rem 0">
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Points (set)</label>
      ${facSel('fw-points-faction')}
      <input id="fw-points-value" type="number" class="input input-sm input-bordered" style="width:110px" placeholder="valeur">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.setPoints()">Définir</button>
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Points (ajouter ±)</label>
      ${facSel('fw-points-add-faction')}
      <input id="fw-points-add-delta" type="number" class="input input-sm input-bordered" style="width:110px" placeholder="delta">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.addPoints()">Ajouter</button>
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Récap / résolution</label>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.pointsRecap()">Diffuser le récap</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.pointsResolve()">Résoudre le vainqueur</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.preflight()" title="Vérifie la préparation de l'événement">Préflight ▸</button>
    </div>
    <div id="fw-preflight-out" style="font-size:var(--type-sm)"></div>
  </div>`;
}

// Render a preflight/readiness payload into a readable checklist. Tolerant of shape:
// accepts {ready, checks:[{label|name,ok|pass,detail|message}]} or a flat object of
// label -> bool, or a plain string.
function _fwRenderPreflightResult(r) {
  if (r == null) return '<span style="color:var(--muted)">Aucune donnée.</span>';
  if (typeof r === 'string') return escHtml(r);
  const line = (ok, label, detail) =>
    `<div style="display:flex;align-items:center;gap:0.4rem">
       <span style="color:${ok ? 'var(--green,#22c55e)' : 'var(--red,#ef4444)'}">${ok ? '✓' : '✗'}</span>
       <span>${escHtml(label)}</span>
       ${detail ? `<span style="color:var(--muted)">— ${escHtml(detail)}</span>` : ''}
     </div>`;
  let rows = '';
  if (Array.isArray(r.checks)) {
    rows = r.checks.map((c) => {
      const ok = !!(c.ok != null ? c.ok : c.pass);
      return line(ok, c.label || c.name || '?', c.detail || c.message || '');
    }).join('');
  } else {
    rows = Object.keys(r)
      .filter((k) => k !== 'ready' && k !== 'result')
      .map((k) => {
        const v = r[k];
        if (typeof v === 'boolean') return line(v, k, '');
        return line(true, k, typeof v === 'object' ? JSON.stringify(v) : String(v));
      }).join('');
  }
  const header = ('ready' in r)
    ? `<div style="font-weight:600;color:${r.ready ? 'var(--green,#22c55e)' : 'var(--amber)'};margin-bottom:0.35rem">${r.ready ? 'Prêt à lancer' : 'Non prêt'}</div>`
    : '';
  return `<div style="display:flex;flex-direction:column;gap:0.25rem;margin-top:0.5rem">${header}${rows || escHtml(JSON.stringify(r))}</div>`;
}

// ── Roster fragment (factions: name/color/captain/spawn + member lists) ──────
function _fwRenderRosterInner(s) {
  s = s || {};
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const cfg = (window._fw && window._fw.config) || {};

  const facBlock = (faction, fdat, accent, spawn) => {
    const name = fdat.name || ('Faction ' + faction);
    const captain = fdat.captain || '—';
    const members = Array.isArray(fdat.members) ? fdat.members : [];
    const colorVal = (faction === 'A' ? cfg.teamColorA : cfg.teamColorB) || '';
    const sp = spawn || {};
    const spawnTxt = (sp.x != null) ? `${Math.round(sp.x)}, ${Math.round(sp.y)}, ${Math.round(sp.z)}` : '—';
    return `<div class="section-card" style="border-left:3px solid ${accent}">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.5rem">
        <div style="font-weight:600;color:${accent}">${escHtml(name)} <span style="color:var(--muted);font-weight:400">(Faction ${faction})</span></div>
        <div style="font-size:var(--type-sm);color:var(--muted)">Capitaine : <span style="color:var(--text-primary)">${escHtml(captain)}</span> · Membres : <span style="color:var(--text-primary)">${members.length}</span></div>
        <div style="font-size:var(--type-xs);color:var(--muted)">Spawn : <span style="color:var(--text-primary)">${escHtml(spawnTxt)}</span></div>
        ${members.length ? `<div style="font-size:var(--type-xs);color:var(--muted-light);max-height:5rem;overflow:auto">${members.map(escHtml).join(', ')}</div>` : ''}
        <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
          <input id="fw-name-${faction}" type="text" class="input input-sm input-bordered" style="width:140px" placeholder="nom" value="${escHtml(name)}">
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.setName('${faction}')">Nom</button>
        </div>
        <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
          <select id="fw-color-${faction}" class="select select-sm select-bordered" style="min-width:130px">${_fwColorOptions(colorVal)}</select>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.setColor('${faction}')">Couleur</button>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.setSpawn('${faction}')" title="Définit le spawn à la position actuelle du joueur admin">Spawn = ma position</button>
        </div>
      </div>
    </div>`;
  };

  const spawnA = cfg.spawnA, spawnB = cfg.spawnB;

  return `
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
      ${facBlock('A', fa, 'var(--red,#ef4444)', spawnA)}
      ${facBlock('B', fb, '#3b82f6', spawnB)}
    </div>
    <div style="display:flex;flex-direction:column;gap:0.6rem;margin-top:0.75rem">
      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
        <label class="field-label" style="min-width:140px">Affecter un joueur</label>
        <input id="fw-faction-player" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:160px" placeholder="pseudo">
        <select id="fw-faction-faction" class="select select-sm select-bordered"><option value="A">A</option><option value="B">B</option></select>
        <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.setFaction()">Affecter</button>
      </div>
      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
        <label class="field-label" style="min-width:140px">Nommer capitaine</label>
        <input id="fw-captain-player" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:160px" placeholder="pseudo">
        <select id="fw-captain-faction" class="select select-sm select-bordered"><option value="A">A</option><option value="B">B</option></select>
        <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.setCaptain()">Définir capitaine</button>
      </div>
    </div>`;
}

// ── Capture zones admin fragment (list + add/remove/major) ───────────────────
function _fwRenderZonesAdminInner(s) {
  const zones = (s && s.captures && s.captures.zones) || [];
  let rows;
  if (!zones.length) {
    rows = `<tr><td colspan="5" class="empty-state-text" style="text-align:center;padding:1.25rem">Aucune zone de capture.</td></tr>`;
  } else {
    rows = zones.map((z) => {
      const cap = z.captureSeconds || 0;
      const pct = cap > 0 ? Math.round(Math.abs(z.progress || 0) / cap * 100) : 0;
      const holder = z.holder === 'A' ? 'A' : z.holder === 'B' ? 'B' : '—';
      const id = escHtml(z.id);
      return `<tr>
        <td>${escHtml(z.name || z.id)}${z.major ? ' <span style="color:var(--amber)">★</span>' : ''}</td>
        <td>${holder}${z.contested ? ' <span style="color:var(--amber)">(contesté)</span>' : ''}</td>
        <td class="td-mono">${pct}%</td>
        <td class="td-mono">${cap}s</td>
        <td style="white-space:nowrap">
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.zoneMajor('${id}', ${z.major ? 'false' : 'true'})">${z.major ? 'Mineure' : 'Majeure'}</button>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.zoneRemove('${id}')">Supprimer</button>
        </td>
      </tr>`;
    }).join('');
  }
  return `
    ${_fwZonesDatalist(s)}
    <table class="cg-table">
      <thead><tr><th>Zone</th><th>Détenteur</th><th>Progression</th><th>Durée</th><th></th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap;margin-top:0.75rem">
      <label class="field-label" style="min-width:140px">Ajouter / éditer (à ma position)</label>
      <input id="fw-zone-id" type="text" list="fw-dl-zones" class="input input-sm input-bordered" style="width:120px" placeholder="id">
      <input id="fw-zone-radius" type="number" min="1" max="200" class="input input-sm input-bordered" style="width:90px" placeholder="rayon">
      <input id="fw-zone-points" type="number" min="0" class="input input-sm input-bordered" style="width:90px" placeholder="points">
      <input id="fw-zone-seconds" type="number" min="1" max="3600" class="input input-sm input-bordered" style="width:90px" placeholder="secondes">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.zoneAdd()">Ajouter</button>
    </div>`;
}

// ── Objectives fragment (list + admin complete/reset) ────────────────────────
function _fwRenderObjectivesInner(s) {
  const objState = (s && s.objectives) || {};
  const objs = Array.isArray(objState.objectives) ? objState.objectives : [];
  let rows;
  if (!objs.length) {
    rows = `<tr><td colspan="5" class="empty-state-text" style="text-align:center;padding:1.25rem">Aucun objectif.</td></tr>`;
  } else {
    rows = objs.map((o) => {
      const mark = (done, prog) => done ? '✓' : (prog != null ? prog + '/' + (o.target != null ? o.target : '?') : '—');
      return `<tr>
        <td><span class="id-badge">${escHtml(o.id)}</span></td>
        <td>${escHtml(o.label || '')}<div style="font-size:var(--type-xs);color:var(--muted)">${escHtml(o.category || '')}${o.points != null ? ' · ' + o.points + ' pts' : ''}</div></td>
        <td class="td-mono" style="color:var(--red,#ef4444)">${mark(o.doneA, o.progressA)}</td>
        <td class="td-mono" style="color:#3b82f6">${mark(o.doneB, o.progressB)}</td>
        <td style="white-space:nowrap">
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.objCompleteId('${escHtml(o.id)}','A')">→A</button>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.objCompleteId('${escHtml(o.id)}','B')">→B</button>
        </td>
      </tr>`;
    }).join('');
  }
  const reset = objState.lastResetDay ? `Dernier reset : ${escHtml(objState.lastResetDay)} (heure ${objState.dailyResetHour != null ? objState.dailyResetHour : '?'}h)` : '';
  return `
    ${_fwObjectivesDatalist(s)}
    <div style="font-size:var(--type-xs);color:var(--muted);margin-bottom:0.5rem">${reset}</div>
    <table class="cg-table">
      <thead><tr><th>ID</th><th>Objectif</th><th>A</th><th>B</th><th></th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap;margin-top:0.75rem">
      <label class="field-label" style="min-width:140px">Compléter manuellement</label>
      <input id="fw-obj-id" type="text" list="fw-dl-objectives" class="input input-sm input-bordered" style="width:140px" placeholder="id objectif">
      <select id="fw-obj-faction" class="select select-sm select-bordered"><option value="A">A</option><option value="B">B</option></select>
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.objComplete()">Compléter</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.objResetDaily()">Reset journalier</button>
    </div>`;
}

// ── Moderation fragment (lists + actions + per-player quick-free) ─────────────
function _fwRenderModInner(s) {
  const m = (s && s.moderation) || {};
  const muted = Array.isArray(m.muted) ? m.muted : [];
  const jailed = Array.isArray(m.jailed) ? m.jailed : [];
  const chip = (name, action, label) =>
    `<span class="id-badge" style="margin:0 .25rem .25rem 0">${escHtml(name)} <a href="#" style="color:var(--amber)" onclick="event.preventDefault();window._fw.modOn('${action}','${escHtml(name)}')">${label}</a></span>`;
  return `<div style="display:flex;flex-direction:column;gap:0.75rem">
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Joueur</label>
      <input id="fw-mod-player" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:160px" placeholder="pseudo">
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.mod('mute')">Mute</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.mod('unmute')">Unmute</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.mod('jail')">Jail</button>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.mod('free')">Free</button>
    </div>
    <div style="font-size:var(--type-sm)"><span style="color:var(--muted)">Muets :</span> ${muted.length ? muted.map(n => chip(n, 'unmute', 'unmute')).join('') : '<span style="color:var(--muted-light)">aucun</span>'}</div>
    <div style="font-size:var(--type-sm)"><span style="color:var(--muted)">Emprisonnés :</span> ${jailed.length ? jailed.map(n => chip(n, 'free', 'free')).join('') : '<span style="color:var(--muted-light)">aucun</span>'}</div>
  </div>`;
}

// ── Broadcast fragment (static form) + respec ────────────────────────────────
function _fwRenderBroadcast() {
  const classes = ['guerrier','rodeur','archer','apothicaire','forgeron','sentinelle','saboteur'];
  return `<div style="display:flex;flex-direction:column;gap:0.75rem">
    <div style="display:flex;align-items:flex-start;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px;margin-top:0.4rem">Message</label>
      <div style="display:flex;flex-direction:column;gap:0.4rem;flex:1;min-width:260px">
        <input id="fw-bc-text" type="text" class="input input-sm input-bordered" style="width:100%" placeholder="texte (supporte les codes &amp; couleur)">
        <div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">
          <select id="fw-bc-kind" class="select select-sm select-bordered"><option value="chat">Chat</option><option value="title">Titre</option><option value="actionbar">Action bar</option></select>
          <select id="fw-bc-scope" class="select select-sm select-bordered"><option value="all">Tout le monde</option><option value="A">Faction A</option><option value="B">Faction B</option></select>
          <input id="fw-bc-sound" type="text" class="input input-sm input-bordered" style="width:200px" placeholder="son (optionnel, ex: minecraft:ui.button.click)">
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.broadcast()">Diffuser</button>
        </div>
      </div>
    </div>
    <hr style="border-color:var(--bg-border);margin:0.25rem 0">
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Respec d'un joueur</label>
      <input id="fw-respec-player" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:160px" placeholder="pseudo">
      <select id="fw-respec-class" class="select select-sm select-bordered">${classes.map(c => `<option value="${c}">${c}</option>`).join('')}</select>
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.respec()">Changer de classe</button>
    </div>
  </div>`;
}

// ── Reset fragment ───────────────────────────────────────────────────────────
function _fwRenderReset() {
  return `<div style="display:flex;align-items:center;gap:0.75rem;flex-wrap:wrap">
    <span style="color:var(--muted);font-size:var(--type-sm)">Réinitialise rosters, vote et draft. À utiliser entre deux événements.</span>
    <button class="btn-cg btn-cg-sm" style="background:var(--red,#ef4444);color:#fff;margin-left:auto" onclick="window._fw.reset()">Réinitialiser FactionWar</button>
  </div>`;
}

// ── Board fragment (re-rendered on poll) ─────────────────────────────────────
function _fwRenderBoardInner(s) {
  if (!s) return '';
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const pool = s.lifePool || {};
  const score = s.scores || {};
  const war = s.pvpWindow === 1 || s.pvpWindow === true;
  const frozen = !!s.frozen;
  const v = (x) => (x == null ? '—' : x);
  const nameA = fa.name || 'Faction A', nameB = fb.name || 'Faction B';

  const zones = (s.captures && s.captures.zones) || [];
  let zoneRows;
  if (!zones.length) {
    zoneRows = `<tr><td colspan="3" class="empty-state-text" style="text-align:center;padding:1.5rem">Aucune zone de capture.</td></tr>`;
  } else {
    zoneRows = zones.map((z) => {
      const cap = z.captureSeconds || 0;
      const pct = cap > 0 ? Math.round(Math.abs(z.progress || 0) / cap * 100) : 0;
      const holder = z.holder === 'A' ? nameA : z.holder === 'B' ? nameB : '—';
      return `<tr>
        <td>${escHtml(z.name || z.id)}${z.major ? ' <span style="color:var(--amber)">★</span>' : ''}</td>
        <td>${escHtml(holder)}${z.contested ? ' <span style="color:var(--amber)">(contesté)</span>' : ''}</td>
        <td class="td-mono">${pct}%</td>
      </tr>`;
    }).join('');
  }

  return `
    <div style="display:flex;align-items:center;gap:0.75rem;margin-bottom:0.75rem;flex-wrap:wrap">
      <span class="status-dot ${war ? 's-online' : 's-offline'}"></span>
      <strong style="font-size:var(--type-lg)">${war ? 'GUERRE (PvP ON)' : 'TRÊVE (PvP OFF)'}</strong>
      ${frozen ? '<span style="color:var(--amber)">❄ GELÉ</span>' : ''}
      <span style="margin-left:auto;color:var(--muted);font-size:var(--type-sm)">Phase: ${escHtml(v(s.phase))}${(s.phaseState && s.phaseState.day != null) ? ' · Jour ' + s.phaseState.day : ''}</span>
    </div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem;margin-bottom:0.75rem">
      <div class="section-card" style="border-left:3px solid var(--red,#ef4444)">
        <div class="section-card-body">
          <div style="font-weight:600;color:var(--red,#ef4444)">${escHtml(nameA)}</div>
          <div style="font-size:var(--type-sm);color:var(--muted)">Vies: <span style="color:var(--text-primary)">${v(pool.pointsA != null ? pool.pointsA : pool.A)}</span></div>
          <div style="font-size:var(--type-sm);color:var(--muted)">Points: <span style="color:var(--text-primary)">${v(score.pointsA != null ? score.pointsA : score.A)}</span></div>
          <div style="font-size:var(--type-sm);color:var(--muted)">Membres: <span style="color:var(--text-primary)">${v(fa.count)}</span></div>
        </div>
      </div>
      <div class="section-card" style="border-left:3px solid #3b82f6">
        <div class="section-card-body">
          <div style="font-weight:600;color:#3b82f6">${escHtml(nameB)}</div>
          <div style="font-size:var(--type-sm);color:var(--muted)">Vies: <span style="color:var(--text-primary)">${v(pool.pointsB != null ? pool.pointsB : pool.B)}</span></div>
          <div style="font-size:var(--type-sm);color:var(--muted)">Points: <span style="color:var(--text-primary)">${v(score.pointsB != null ? score.pointsB : score.B)}</span></div>
          <div style="font-size:var(--type-sm);color:var(--muted)">Membres: <span style="color:var(--text-primary)">${v(fb.count)}</span></div>
        </div>
      </div>
    </div>
    <table class="cg-table">
      <thead><tr><th>Zone de capture</th><th>Détenteur</th><th>Progression</th></tr></thead>
      <tbody>${zoneRows}</tbody>
    </table>`;
}

// ── Config form ───────────────────────────────────────────────────────────────
// Scalar/boolean tunables only; nested spawnA/spawnB are not edited here (use the
// roster "Spawn = ma position" button). We only surface fields that exist on the
// loaded config, so the patch stays valid against the mod's validator.
const _FW_CONFIG_FIELDS = [
  { key: 'voteMinutes',           label: 'Durée du vote (min)',       type: 'number' },
  { key: 'draftSerpentine',       label: 'Draft serpentin',           type: 'bool'   },
  { key: 'poolStart',             label: 'Pool de vies (départ)',     type: 'number' },
  { key: 'maxPool',               label: 'Pool de vies (max)',        type: 'number' },
  { key: 'respawnBaseSeconds',    label: 'Respawn de base (s)',       type: 'number' },
  { key: 'respawnPerRecentDeath', label: 'Respawn +/mort récente (s)',type: 'number' },
  { key: 'respawnCeilingSeconds', label: 'Respawn max (s)',           type: 'number' },
  { key: 'spawnProtectSeconds',   label: 'Protection au spawn (s)',   type: 'number' },
  { key: 'pointsPerKill',         label: 'Points par kill',           type: 'number' },
  { key: 'firstBloodEnabled',     label: 'First blood activé',        type: 'bool'   },
  { key: 'firstBloodBonus',       label: 'Bonus first blood',         type: 'number' },
  { key: 'killstreaksEnabled',    label: 'Killstreaks activés',       type: 'bool'   },
  { key: 'bountyEnabled',         label: 'Primes activées',           type: 'bool'   },
  { key: 'bountyStreak',          label: 'Série pour prime',          type: 'number' },
  { key: 'underdogEnabled',       label: 'Bonus outsider activé',     type: 'bool'   },
  { key: 'underdogBonusPercent',  label: 'Bonus outsider (%)',        type: 'number' },
  { key: 'suddenDeathBonusPercent', label: 'Bonus mort subite (%)',   type: 'number' },
  { key: 'captureLifeBack',       label: 'Vies rendues / capture',    type: 'number' },
  { key: 'captureHeight',         label: 'Hauteur zone capture',      type: 'number' },
  { key: 'captureDecayPerSecond', label: 'Décroissance capture /s',   type: 'number' },
  { key: 'dailyActiveCount',      label: 'Objectifs journaliers actifs', type: 'number' },
  { key: 'dailyResetHour',        label: 'Heure reset journalier (0-23)', type: 'number' },
  { key: 'pvpCrossFaction',       label: 'PvP entre factions',        type: 'bool'   },
  { key: 'pvpSameFaction',        label: 'Tir allié (même faction)',  type: 'bool'   },
  { key: 'glowEnabled',           label: 'Glow d\'équipe (guerre)',   type: 'bool'   },
  { key: 'factionChatEnabled',    label: 'Chat de faction /f',        type: 'bool'   },
  { key: 'warScheduleEnabled',    label: 'Horaire de guerre activé',  type: 'bool'   },
  { key: 'warOpenTime',           label: 'Ouverture guerre (HH:mm)',  type: 'text'   },
  { key: 'warCloseTime',          label: 'Fermeture guerre (HH:mm)',  type: 'text'   },
  { key: 'vanillaSidebar',        label: 'Sidebar vanilla (auto/always/never)', type: 'text' },
  { key: 'respecDuringWar',       label: 'Respec pendant la guerre',  type: 'bool'   },
  { key: 'respecCooldownSeconds', label: 'Cooldown respec (s)',       type: 'number' },
];

function _fwRenderConfig(c) {
  c = c || {};
  const rows = _FW_CONFIG_FIELDS
    .filter((f) => Object.prototype.hasOwnProperty.call(c, f.key))
    .map((f) => {
      const val = c[f.key];
      if (f.type === 'bool') {
        return `<div style="display:flex;align-items:center;gap:0.75rem">
          <label class="field-label" style="min-width:230px">${f.label}</label>
          <input type="checkbox" class="toggle toggle-sm" data-fw-cfg="${f.key}" data-fw-type="bool" ${val ? 'checked' : ''}>
        </div>`;
      }
      return `<div style="display:flex;align-items:center;gap:0.75rem">
        <label class="field-label" style="min-width:230px">${f.label}</label>
        <input type="${f.type === 'number' ? 'number' : 'text'}" class="input input-sm input-bordered" style="width:160px"
          data-fw-cfg="${f.key}" data-fw-type="${f.type}" value="${escHtml(String(val))}">
      </div>`;
    }).join('');

  if (!rows) return `<div class="empty-state-text">Config indisponible.</div>`;

  return `<div style="display:flex;flex-direction:column;gap:0.6rem;max-width:560px">
    ${rows}
    <button class="btn-cg btn-cg-primary btn-cg-sm" style="align-self:flex-start;margin-top:0.5rem" onclick="window._fw.saveConfig()">Enregistrer la config</button>
  </div>`;
}

// ── Faction origins fragment ────────────────────────────────────────────────
// Lets an admin view and assign the "faction origin" of each side (A/B). The
// endpoint is optional: when `o` is null (404 / older mod), we show a graceful
// "indisponible" notice instead of breaking the view.
//
// The mod's shape (FactionOriginManager.buildStateJson, served at GET /faction/origins
// and embedded in /state as `factionOrigins`):
//   { available: [{ id, name }], default: id|null, A: id|null, B: id|null }
// (We also tolerate a legacy {current:{A,B}} shape for forward/backward compat.)
function _fwRenderOriginsInner(o) {
  if (!o) {
    return `<div class="empty-state-text">Origines de faction indisponibles (endpoint absent ou serveur de jeu hors ligne).</div>`;
  }

  const available = Array.isArray(o.available) ? o.available : [];
  const current = o.current || { A: o.A, B: o.B };

  if (!available.length) {
    return `<div class="empty-state-text">Aucune origine de faction disponible.</div>`;
  }

  // Map id -> origin (for resolving the current selection's display name).
  const byId = {};
  available.forEach((or) => { if (or && or.id != null) byId[or.id] = or; });

  const factionRow = (faction, accent) => {
    const curId = current[faction] != null ? current[faction] : null;
    const cur = curId != null ? byId[curId] : null;
    const curLabel = cur ? cur.name : (curId != null ? curId : 'Aucune');
    const curDesc = cur && cur.description ? cur.description : '';

    const opts = [`<option value="">— Aucune —</option>`]
      .concat(available.map((or) => {
        const sel = (curId != null && String(or.id) === String(curId)) ? ' selected' : '';
        return `<option value="${escHtml(String(or.id))}"${sel}>${escHtml(or.name || or.id)}</option>`;
      }))
      .join('');

    return `<div class="section-card" style="border-left:3px solid ${accent}">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.5rem">
        <div style="font-weight:600;color:${accent}">Faction ${faction}</div>
        <div style="font-size:var(--type-sm);color:var(--muted)">Origine actuelle :
          <span style="color:var(--text-primary)">${escHtml(curLabel)}</span></div>
        ${curDesc ? `<div style="font-size:var(--type-xs);color:var(--muted)">${escHtml(curDesc)}</div>` : ''}
        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <select id="fw-origin-${faction}" class="select select-sm select-bordered" style="min-width:170px">${opts}</select>
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.setOrigin('${faction}')">Appliquer</button>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fw.clearOrigin('${faction}')">Retirer</button>
        </div>
      </div>
    </div>`;
  };

  // Captain-driven origin override: pick a captain (player) + an origin → applies
  // to THAT player's faction server-side (POST /faction/origin/captain).
  const capOpts = [`<option value="">— origine —</option>`]
    .concat(available.map((or) => `<option value="${escHtml(String(or.id))}">${escHtml(or.name || or.id)}</option>`))
    .join('');
  const captainBlock = `<div class="section-card" style="margin-top:0.75rem">
    <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.5rem">
      <div style="font-weight:600">Appliquer via le capitaine</div>
      <div style="font-size:var(--type-xs);color:var(--muted)">L'origine est appliquée à la faction du capitaine choisi.</div>
      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
        <input id="fw-origin-captain-player" type="text" list="fw-dl-players" class="input input-sm input-bordered" style="width:170px" placeholder="capitaine (pseudo)">
        <select id="fw-origin-captain-id" class="select select-sm select-bordered" style="min-width:170px">${capOpts}</select>
        <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.setOriginCaptain()">Appliquer via capitaine</button>
      </div>
    </div>
  </div>`;

  return `<div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
    ${factionRow('A', 'var(--red,#ef4444)')}
    ${factionRow('B', '#3b82f6')}
  </div>
  ${captainBlock}`;
}

// ── Supply drop fragment (static form) ───────────────────────────────────────
function _fwRenderSupplyDrop() {
  return `<div style="display:flex;flex-direction:column;gap:0.75rem">
    <div style="font-size:var(--type-sm);color:var(--muted)">Largue un ravitaillement aux coordonnées indiquées. La dimension est optionnelle (défaut serveur).</div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Coordonnées</label>
      <input id="fw-supply-x" type="number" class="input input-sm input-bordered" style="width:100px" placeholder="x">
      <input id="fw-supply-y" type="number" class="input input-sm input-bordered" style="width:100px" placeholder="y">
      <input id="fw-supply-z" type="number" class="input input-sm input-bordered" style="width:100px" placeholder="z">
    </div>
    <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <label class="field-label" style="min-width:140px">Dimension (option.)</label>
      <input id="fw-supply-dim" type="text" class="input input-sm input-bordered" style="width:240px" placeholder="ex: minecraft:overworld">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fw.supplyDrop()">Larguer un ravitaillement</button>
    </div>
  </div>`;
}

// ── Helpers ──────────────────────────────────────────────────────────────────

// Pull the faction-origins block out of a /state snapshot (key `factionOrigins`),
// returning null when absent so callers fall back to the dedicated endpoint.
function _fwExtractOrigins(s) {
  if (!s) return null;
  const fo = s.factionOrigins;
  if (fo && Array.isArray(fo.available)) return fo;
  return null;
}

// Minecraft Formatting colour names (the colour formattings only), matching the
// mod's COLOR_SUGGEST / Formatting.byName validation.
const _FW_COLORS = [
  'black','dark_blue','dark_green','dark_aqua','dark_red','dark_purple','gold',
  'gray','dark_gray','blue','green','aqua','red','light_purple','yellow','white',
];
function _fwColorOptions(selected) {
  const sel = (selected || '').toLowerCase();
  return _FW_COLORS.map((c) => `<option value="${c}"${c === sel ? ' selected' : ''}>${c}</option>`).join('');
}
