// ── Assistant de partie — "Guerre de Factions" ──────────────────────────────
//
// A clear, step-by-step wizard for setting up a FactionWar match. Where the full
// admin view (view-factionwar.js) is a dense control panel exposing EVERY command
// at once, this wizard walks an operator through the natural setup order:
//
//   1) Vote des capitaines   → /vote/start, /vote/close, /captain/set (player dropdown)
//   2) Draft                 → /draft/pick (captain + player dropdowns), live A/B rosters
//   3) Nom des factions      → /faction/name (A & B)
//   4) Couleur des factions  → /faction/color (A & B, 16 MC colours)
//   5) Origine de faction    → /faction/origin (admin override) — origin CARDS with effects
//   6) Récap & lancement     → summary + /phase + PvP ON
//
// Follows the same build*(app) contract as the other views: an async builder that
// fetches /state + players() + origins, stores mutable state on window._fww,
// returns an HTML string, and re-renders via app.navigate('wizard'). Every admin
// action posts, toasts {result}, then reloads only the affected fragment so we
// never stomp the 3s live poll or input focus. Endpoints a given mod build may
// not implement just 404 — every handler catches and toasts, degrading like
// view-factionwar.js does. This file ONLY defines view-wizard.js globals.

async function buildWizard(app) {
  const _fww = window._fww || {};
  _fww._app = app;
  _fww.step = _fww.step || 1;          // current wizard step (1..6), persisted across re-renders
  _fww.state = _fww.state || {};       // last /state snapshot
  _fww.players = _fww.players || null; // { online:[names], known:[names] } | null
  _fww.origins = _fww.origins || null; // { available:[{id,name,...}], default, A, B } | null
  window._fww = _fww;

  // ── Data load ──────────────────────────────────────────────────────────────
  _fww.load = async function() {
    try {
      _fww.state = await API.factionwar.state();
    } catch (e) {
      _fww.state = null;
      return;
    }
    // Player picker source (online + known). Optional — fall back to roster names.
    try {
      _fww.players = await API.factionwar.players();
    } catch (e) {
      _fww.players = null;
    }
    // Faction origins (optional — embedded in /state, else dedicated endpoint, else null).
    _fww.origins = _fwwExtractOrigins(_fww.state);
    if (!_fww.origins) {
      try { _fww.origins = await API.factionwar.origins(); }
      catch (e) { _fww.origins = null; }
    }
  };

  await _fww.load();

  if (_fww.state === null) {
    return `<div class="empty-state">
      <span class="empty-state-icon">&#x26A0;</span>
      <span class="empty-state-text">Assistant indisponible — FactionWar ne répond pas (le serveur de jeu est-il démarré ?)</span>
    </div>`;
  }

  // ── Re-render helpers ────────────────────────────────────────────────────────
  // Full re-render (used when changing step). navigate() early-returns when the
  // view is unchanged + viewHtml is set, so clear viewHtml first to force a rebuild.
  _fww._rerender = function() { app.viewHtml = ''; app.navigate('wizard'); };

  // Refresh /state (+ optional reloads) without touching the DOM step structure;
  // then repaint the live fragments of the CURRENT step.
  _fww._reloadState = async function() {
    try { _fww.state = await API.factionwar.state(); } catch (e) { return; }
    _fwwRepaintLive(_fww.state);
  };

  // Go to a step (clamped 1..6).
  _fww.goStep = function(n) {
    const s = Math.max(1, Math.min(6, n | 0));
    if (s === _fww.step) return;
    _fww.step = s;
    _fww._rerender();
  };
  _fww.nextStep = function() { _fww.goStep(_fww.step + 1); };
  _fww.prevStep = function() { _fww.goStep(_fww.step - 1); };

  // ── Live poll ────────────────────────────────────────────────────────────────
  // Light 3s poll while the wizard is open; repaint only live fragments so we
  // don't clobber input focus or the step structure.
  if (_fww._pollTimer) { clearInterval(_fww._pollTimer); _fww._pollTimer = null; }
  _fww._pollTimer = setInterval(async () => {
    if (app.currentView !== 'wizard') { clearInterval(_fww._pollTimer); _fww._pollTimer = null; return; }
    try {
      _fww.state = await API.factionwar.state();
      _fwwRepaintLive(_fww.state);
    } catch (e) { /* transient — keep last snapshot */ }
  }, 3000);

  // ── Action plumbing ──────────────────────────────────────────────────────────
  _fww._toastResult = function(r) {
    const msg = (r && (r.result || (r.ok ? 'OK' : null))) || 'OK';
    app.toast(msg, 'success');
  };
  // Run an action, toast its result, then reload (defaults to a /state repaint).
  _fww._do = async function(fn, reload) {
    try {
      const r = await fn();
      _fww._toastResult(r);
      if (reload) await reload(); else await _fww._reloadState();
    } catch (e) { app.toast(e.message, 'error'); }
  };
  const val  = (id) => { const el = document.getElementById(id); return el ? el.value : ''; };
  const numV = (id) => parseInt(val(id), 10);

  // ── Step 1 — Vote des capitaines ────────────────────────────────────────────
  _fww.voteStart = () => {
    const m = numV('fww-vote-minutes');
    _fww._do(() => API.factionwar.voteStart(isNaN(m) ? null : m));
  };
  _fww.voteClose = () => _fww._do(() => API.factionwar.voteClose());
  _fww.forceCaptain = () => {
    const p = val('fww-cap-player').trim(), f = val('fww-cap-faction');
    if (!p) { app.toast('Choisis un joueur.', 'error'); return; }
    _fww._do(() => API.factionwar.setCaptain(p, f));
  };

  // ── Step 2 — Draft ──────────────────────────────────────────────────────────
  _fww.draftPick = () => {
    const cap = val('fww-draft-captain').trim(), player = val('fww-draft-player').trim();
    if (!cap || !player) { app.toast('Capitaine et joueur requis.', 'error'); return; }
    _fww._do(() => API.factionwar.draftPick(cap, player));
  };
  // Quick assign straight to a faction (admin override, bypasses draft order).
  _fww.assign = () => {
    const p = val('fww-assign-player').trim(), f = val('fww-assign-faction');
    if (!p) { app.toast('Choisis un joueur.', 'error'); return; }
    _fww._do(() => API.factionwar.setFaction(p, f));
  };

  // ── Step 3 — Nom des factions ────────────────────────────────────────────────
  _fww.setName = (faction) => {
    const name = val('fww-name-' + faction).trim();
    if (!name) { app.toast('Nom requis.', 'error'); return; }
    _fww._do(() => API.factionwar.setName(faction, name));
  };

  // ── Step 4 — Couleur des factions ────────────────────────────────────────────
  _fww.setColor = (faction) => {
    const color = val('fww-color-' + faction);
    _fww._do(() => API.factionwar.setColor(faction, color));
  };

  // ── Step 5 — Origine de faction ──────────────────────────────────────────────
  _fww._reloadOrigins = async function() {
    try { _fww.state = await API.factionwar.state(); } catch (e) { /* keep */ }
    _fww.origins = _fwwExtractOrigins(_fww.state);
    if (!_fww.origins) { try { _fww.origins = await API.factionwar.origins(); } catch (e) { _fww.origins = null; } }
    const el = document.getElementById('fww-origins');
    if (el) el.innerHTML = _fwwRenderOriginsInner(_fww.origins, _fww.state);
  };
  // Choose origin for a faction from a clicked card.
  _fww.pickOrigin = (faction, originId) =>
    _fww._do(() => API.factionwar.setOrigin(faction, originId), () => _fww._reloadOrigins());
  _fww.clearOrigin = (faction) =>
    _fww._do(() => API.factionwar.setOrigin(faction, null), () => _fww._reloadOrigins());

  // ── Step 6 — Récap & lancement ────────────────────────────────────────────────
  _fww.phaseNext = () => _fww._do(() => API.factionwar.phaseAction('next'));
  _fww.phaseSetDay = () => {
    const d = numV('fww-launch-day');
    if (isNaN(d)) { app.toast('Jour invalide.', 'error'); return; }
    _fww._do(() => API.factionwar.phaseAction('set', { day: d }));
  };
  _fww.pvpOn  = () => { if (_fww.state) _fww.state.pvpWindow = 1; _fww._do(() => API.factionwar.pvpOn()); };
  _fww.pvpOff = () => { if (_fww.state) _fww.state.pvpWindow = 0; _fww._do(() => API.factionwar.pvpOff()); };

  // ── Render ────────────────────────────────────────────────────────────────────
  return _fwwRenderShell(_fww.step, _fww.state, _fww.players, _fww.origins);
}

// ── Shell (header + stepper + active step body) ───────────────────────────────
function _fwwRenderShell(step, state, players, origins) {
  return `<div class="max-w-6xl mx-auto slide-in">
    <div class="flex items-center gap-3 mb-6">
      <span style="font-size:var(--type-2xl)">🧙</span>
      <div>
        <h1 style="font-size:var(--type-xl);font-weight:700">Assistant de partie</h1>
        <p style="color:var(--muted);font-size:var(--type-sm)">Mise en place guidée d'une Guerre de Factions, étape par étape</p>
      </div>
    </div>

    <!-- Live mini-board: always visible so the operator sees the effect of each step -->
    <div class="section-card slide-in" style="margin-bottom:1rem">
      <div class="section-card-body"><div id="fww-board">${_fwwRenderBoardInner(state)}</div></div>
    </div>

    <!-- Stepper -->
    <div class="section-card slide-in" style="margin-bottom:1rem">
      <div class="section-card-body"><div id="fww-stepper">${_fwwRenderStepper(step)}</div></div>
    </div>

    <!-- Active step -->
    <div class="section-card slide-in">
      <div class="section-card-header">
        <span class="section-card-title">Étape ${step}/6 — ${escHtml(_FWW_STEPS[step - 1].title)}</span>
      </div>
      <div class="section-card-body">
        <p style="color:var(--muted);font-size:var(--type-sm);margin-bottom:1rem">${_FWW_STEPS[step - 1].help}</p>
        <div id="fww-step-body">${_fwwRenderStepBody(step, state, players, origins)}</div>
        ${_fwwRenderNav(step)}
      </div>
    </div>
  </div>`;
}

// ── Step metadata ─────────────────────────────────────────────────────────────
const _FWW_STEPS = [
  { title: 'Vote des capitaines', short: 'Capitaines',
    help: 'Lancez un vote pour que les joueurs élisent leurs deux capitaines, ou désignez-les directement. Les capitaines drafteront ensuite leurs équipes.' },
  { title: 'Draft des équipes', short: 'Draft',
    help: 'Chaque capitaine choisit ses joueurs à tour de rôle. Vous pouvez aussi affecter un joueur directement à une faction (override admin).' },
  { title: 'Nom des factions', short: 'Noms',
    help: 'Donnez un nom à chaque faction. Il apparaît dans le HUD, le chat et les annonces.' },
  { title: 'Couleur des factions', short: 'Couleurs',
    help: 'Choisissez une couleur Minecraft pour chaque faction (glow d\'équipe, noms, sidebar).' },
  { title: 'Origine de faction', short: 'Origines',
    help: 'Attribuez une origine (bonus/malus de classe d\'équipe) à chaque faction. Le capitaine peut aussi la choisir lui-même en jeu (touche O / commande).' },
  { title: 'Récap & lancement', short: 'Lancement',
    help: 'Vérifiez la configuration, placez la partie sur le bon jour, puis ouvrez la fenêtre PvP pour démarrer la guerre.' },
];

// ── Stepper (clickable step pills) ────────────────────────────────────────────
function _fwwRenderStepper(step) {
  const pills = _FWW_STEPS.map((s, i) => {
    const n = i + 1;
    const isActive = n === step;
    const isDone = n < step;
    const bg = isActive ? 'var(--amber)' : isDone ? 'var(--bg-elevated, #2a2a2a)' : 'transparent';
    const fg = isActive ? '#1a1a1a' : isDone ? 'var(--text-primary)' : 'var(--muted)';
    const border = isActive ? 'var(--amber)' : 'var(--bg-border)';
    const circle = isDone
      ? '<span style="font-weight:700">✓</span>'
      : `<span style="font-weight:700">${n}</span>`;
    return `<button type="button" onclick="window._fww.goStep(${n})"
        style="display:flex;align-items:center;gap:0.5rem;padding:0.4rem 0.75rem;border-radius:999px;
               background:${bg};color:${fg};border:1px solid ${border};cursor:pointer;white-space:nowrap;font-size:var(--type-sm)">
        <span style="display:inline-flex;align-items:center;justify-content:center;width:1.4rem;height:1.4rem;border-radius:999px;
                     background:${isActive ? 'rgba(0,0,0,0.15)' : 'var(--bg-base, #1a1a1a)'};">${circle}</span>
        <span>${escHtml(s.short)}</span>
      </button>`;
  }).join('<span style="color:var(--muted)">›</span>');
  return `<div style="display:flex;align-items:center;gap:0.4rem;flex-wrap:wrap">${pills}</div>`;
}

// ── Prev / Next navigation row ────────────────────────────────────────────────
function _fwwRenderNav(step) {
  const prev = step > 1
    ? `<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fww.prevStep()">← Étape précédente</button>`
    : '<span></span>';
  const next = step < 6
    ? `<button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.nextStep()">Étape suivante →</button>`
    : `<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fww.goStep(1)">↺ Recommencer</button>`;
  return `<div style="display:flex;align-items:center;justify-content:space-between;margin-top:1.25rem;
                       padding-top:1rem;border-top:1px solid var(--bg-border)">${prev}${next}</div>`;
}

// ── Step body dispatcher ──────────────────────────────────────────────────────
function _fwwRenderStepBody(step, state, players, origins) {
  switch (step) {
    case 1: return _fwwStep1Vote(state, players);
    case 2: return _fwwStep2Draft(state, players);
    case 3: return _fwwStep3Names(state);
    case 4: return _fwwStep4Colors(state);
    case 5: return `<div id="fww-origins">${_fwwRenderOriginsInner(origins, state)}</div>`;
    case 6: return _fwwStep6Recap(state, origins);
    default: return '';
  }
}

// Repaint live fragments of whichever step is shown (called by poll + actions).
// Each setter guards on element presence, so it's safe regardless of current step.
function _fwwRepaintLive(s) {
  if (!s) return;
  const fww = window._fww || {};
  fww.state = s;
  _fwwSet('fww-board', _fwwRenderBoardInner(s));
  _fwwSet('fww-vote-status', _fwwRenderVoteStatusInner(s));
  _fwwSet('fww-rosters', _fwwRenderRostersInner(s));
  _fwwSet('fww-recap', _fwwRenderRecapInner(s, fww.origins));
  // Origins card grid carries live "current" highlight too.
  const oel = document.getElementById('fww-origins');
  if (oel) oel.innerHTML = _fwwRenderOriginsInner(fww.origins, s);
}
function _fwwSet(id, html) { const el = document.getElementById(id); if (el) el.innerHTML = html; }

// ── Live mini-board (compact A vs B) ──────────────────────────────────────────
function _fwwRenderBoardInner(s) {
  if (!s) return '';
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const war = s.pvpWindow === 1 || s.pvpWindow === true;
  const frozen = !!s.frozen;
  const nameA = fa.name || 'Faction A', nameB = fb.name || 'Faction B';
  const v = (x) => (x == null ? '—' : x);
  const side = (name, accent, fdat) => `
    <div style="flex:1;min-width:140px;border-left:3px solid ${accent};padding-left:0.6rem">
      <div style="font-weight:600;color:${accent}">${escHtml(name)}</div>
      <div style="font-size:var(--type-xs);color:var(--muted)">Capitaine : <span style="color:var(--text-primary)">${escHtml(fdat.captain || '—')}</span></div>
      <div style="font-size:var(--type-xs);color:var(--muted)">Membres : <span style="color:var(--text-primary)">${v(fdat.count != null ? fdat.count : (Array.isArray(fdat.members) ? fdat.members.length : '—'))}</span></div>
    </div>`;
  return `
    <div style="display:flex;align-items:center;gap:0.6rem;margin-bottom:0.6rem;flex-wrap:wrap">
      <span class="status-dot ${war ? 's-online' : 's-offline'}"></span>
      <strong>${war ? 'GUERRE (PvP ON)' : 'TRÊVE (PvP OFF)'}</strong>
      ${frozen ? '<span style="color:var(--amber)">❄ GELÉ</span>' : ''}
      <span style="margin-left:auto;color:var(--muted);font-size:var(--type-sm)">Phase : ${escHtml(v(s.phase))}${(s.phaseState && s.phaseState.day != null) ? ' · Jour ' + s.phaseState.day : ''}</span>
    </div>
    <div style="display:flex;gap:1rem;flex-wrap:wrap">
      ${side(nameA, 'var(--red,#ef4444)', fa)}
      ${side(nameB, '#3b82f6', fb)}
    </div>`;
}

// ── STEP 1 — Vote des capitaines ──────────────────────────────────────────────
function _fwwStep1Vote(s, players) {
  const playerOpts = _fwwPlayerOptions(players, s);
  const facSel = _fwwFactionSelect('fww-cap-faction');
  return `<div style="display:flex;flex-direction:column;gap:1rem">

    <div class="section-card" style="border-left:3px solid var(--amber)">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.6rem">
        <div style="font-weight:600">A. Lancer le vote</div>
        <div style="font-size:var(--type-sm);color:var(--muted)">Les joueurs votent en jeu pour élire leurs capitaines. À la clôture, les deux plus votés deviennent capitaines.</div>
        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <label class="field-label" style="min-width:120px">Durée (minutes)</label>
          <input id="fww-vote-minutes" type="number" min="1" max="60" class="input input-sm input-bordered" style="width:100px" placeholder="ex: 5">
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.voteStart()">Lancer le vote</button>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fww.voteClose()">Clôturer le vote</button>
        </div>
        <div id="fww-vote-status" style="font-size:var(--type-sm)">${_fwwRenderVoteStatusInner(s)}</div>
      </div>
    </div>

    <div class="section-card" style="border-left:3px solid var(--bg-border)">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.6rem">
        <div style="font-weight:600">B. Forcer un capitaine (override)</div>
        <div style="font-size:var(--type-sm);color:var(--muted)">Sans passer par le vote, désignez directement un capitaine pour une faction.</div>
        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <label class="field-label" style="min-width:120px">Joueur</label>
          <select id="fww-cap-player" class="select select-sm select-bordered" style="min-width:180px">${playerOpts}</select>
          <span style="color:var(--muted)">→</span>
          ${facSel}
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.forceCaptain()">Désigner capitaine</button>
        </div>
      </div>
    </div>
  </div>`;
}

// Vote status block (live). Tolerant of several /state shapes for the vote info.
function _fwwRenderVoteStatusInner(s) {
  const vote = (s && (s.vote || (s.phaseState && s.phaseState.vote))) || null;
  const fa = (s && s.factions && s.factions.A) || {};
  const fb = (s && s.factions && s.factions.B) || {};
  const capA = fa.captain || '—', capB = fb.captain || '—';

  let voteLine = '';
  if (vote) {
    const open = !!(vote.open || vote.active || vote.running);
    const remaining = vote.remainingSeconds != null ? vote.remainingSeconds
                    : (vote.secondsLeft != null ? vote.secondsLeft : null);
    const tally = vote.tally || vote.results || vote.counts || null;
    let tallyTxt = '';
    if (tally && typeof tally === 'object') {
      const entries = Object.entries(tally).sort((a, b) => (b[1] || 0) - (a[1] || 0));
      if (entries.length) tallyTxt = ' · ' + entries.map(([k, n]) => `${escHtml(k)}:${n}`).join(', ');
    }
    voteLine = `<div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
      <span class="status-dot ${open ? 's-online' : 's-offline'}"></span>
      <strong>${open ? 'Vote en cours' : 'Vote fermé'}</strong>
      ${open && remaining != null ? `<span style="color:var(--muted)">${remaining}s restantes</span>` : ''}
      <span style="color:var(--muted)">${tallyTxt}</span>
    </div>`;
  } else {
    voteLine = `<div style="color:var(--muted-light)">Aucun vote actif.</div>`;
  }
  return `${voteLine}
    <div style="margin-top:0.4rem;color:var(--muted)">Capitaines actuels —
      <span style="color:var(--red,#ef4444)">A : ${escHtml(capA)}</span> ·
      <span style="color:#3b82f6">B : ${escHtml(capB)}</span>
    </div>`;
}

// ── STEP 2 — Draft ────────────────────────────────────────────────────────────
function _fwwStep2Draft(s, players) {
  const playerOpts = _fwwPlayerOptions(players, s);
  // Captain dropdown: prefer the two current captains, fall back to all players.
  const fa = (s && s.factions && s.factions.A) || {};
  const fb = (s && s.factions && s.factions.B) || {};
  const caps = [fa.captain, fb.captain].filter(Boolean);
  const capOpts = caps.length
    ? caps.map((c) => `<option value="${escHtml(c)}">${escHtml(c)}</option>`).join('')
    : playerOpts;

  return `<div style="display:flex;flex-direction:column;gap:1rem">

    <div class="section-card" style="border-left:3px solid var(--amber)">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.6rem">
        <div style="font-weight:600">A. Pick de draft</div>
        <div id="fww-draft-turn" style="font-size:var(--type-sm);color:var(--muted)">${_fwwRenderDraftTurnInner(s)}</div>
        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <label class="field-label" style="min-width:120px">Capitaine</label>
          <select id="fww-draft-captain" class="select select-sm select-bordered" style="min-width:170px">${capOpts}</select>
          <span style="color:var(--muted)">choisit</span>
          <select id="fww-draft-player" class="select select-sm select-bordered" style="min-width:170px">${playerOpts}</select>
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.draftPick()">Drafter</button>
        </div>
      </div>
    </div>

    <div class="section-card" style="border-left:3px solid var(--bg-border)">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.6rem">
        <div style="font-weight:600">B. Affectation directe (override)</div>
        <div style="font-size:var(--type-sm);color:var(--muted)">Place un joueur dans une faction sans respecter l'ordre du draft.</div>
        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <label class="field-label" style="min-width:120px">Joueur</label>
          <select id="fww-assign-player" class="select select-sm select-bordered" style="min-width:180px">${playerOpts}</select>
          <span style="color:var(--muted)">→</span>
          ${_fwwFactionSelect('fww-assign-faction')}
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.assign()">Affecter</button>
        </div>
      </div>
    </div>

    <div id="fww-rosters">${_fwwRenderRostersInner(s)}</div>
  </div>`;
}

// Draft turn indicator (live). Tolerant of various /state shapes.
function _fwwRenderDraftTurnInner(s) {
  const draft = (s && (s.draft || (s.phaseState && s.phaseState.draft))) || null;
  if (!draft) return 'Le tour de draft s\'affichera ici une fois la phase de draft active.';
  const turn = draft.turn || draft.currentCaptain || draft.onTheClock || null;
  const round = draft.round != null ? draft.round : null;
  const pick = draft.pick != null ? draft.pick : null;
  let txt = '';
  if (turn) txt += `<strong>Au tour de : <span style="color:var(--amber)">${escHtml(turn)}</span></strong>`;
  if (round != null) txt += `${txt ? ' · ' : ''}Tour ${round}`;
  if (pick != null) txt += `${txt ? ' · ' : ''}Pick ${pick}`;
  if (draft.done || draft.complete) txt = '✓ Draft terminé.';
  return txt || 'Phase de draft.';
}

// Live A/B rosters.
function _fwwRenderRostersInner(s) {
  s = s || {};
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const block = (faction, fdat, accent) => {
    const name = fdat.name || ('Faction ' + faction);
    const members = Array.isArray(fdat.members) ? fdat.members : [];
    const captain = fdat.captain || '—';
    const list = members.length
      ? members.map((m) => {
          const isCap = m === fdat.captain;
          return `<span class="id-badge" style="margin:0 .25rem .25rem 0${isCap ? ';border-color:var(--amber)' : ''}">${escHtml(m)}${isCap ? ' ★' : ''}</span>`;
        }).join('')
      : '<span style="color:var(--muted-light)">Aucun membre encore.</span>';
    return `<div class="section-card" style="border-left:3px solid ${accent}">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.4rem">
        <div style="font-weight:600;color:${accent}">${escHtml(name)} <span style="color:var(--muted);font-weight:400">(Faction ${faction})</span></div>
        <div style="font-size:var(--type-xs);color:var(--muted)">Capitaine : <span style="color:var(--text-primary)">${escHtml(captain)}</span> · ${members.length} membre(s)</div>
        <div style="display:flex;flex-wrap:wrap">${list}</div>
      </div>
    </div>`;
  };
  return `<div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
    ${block('A', fa, 'var(--red,#ef4444)')}
    ${block('B', fb, '#3b82f6')}
  </div>`;
}

// ── STEP 3 — Nom des factions ─────────────────────────────────────────────────
function _fwwStep3Names(s) {
  s = s || {};
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const row = (faction, fdat, accent) => {
    const name = fdat.name || '';
    return `<div class="section-card" style="border-left:3px solid ${accent}">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.5rem">
        <div style="font-weight:600;color:${accent}">Faction ${faction}</div>
        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <input id="fww-name-${faction}" type="text" class="input input-sm input-bordered" style="min-width:200px"
                 placeholder="Nom de la faction ${faction}" value="${escHtml(name)}">
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.setName('${faction}')">Enregistrer</button>
        </div>
      </div>
    </div>`;
  };
  return `<div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
    ${row('A', fa, 'var(--red,#ef4444)')}
    ${row('B', fb, '#3b82f6')}
  </div>`;
}

// ── STEP 4 — Couleur des factions ─────────────────────────────────────────────
function _fwwStep4Colors(s) {
  s = s || {};
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const cfg = (window._fww && window._fww.config) || {};
  const row = (faction, fdat, accent) => {
    const name = fdat.name || ('Faction ' + faction);
    // Current colour: prefer faction.color from /state, then config team colour.
    const cur = (fdat.color || (faction === 'A' ? cfg.teamColorA : cfg.teamColorB) || '').toLowerCase();
    const swatch = _FWW_COLOR_HEX[cur] ? `<span style="display:inline-block;width:1rem;height:1rem;border-radius:3px;background:${_FWW_COLOR_HEX[cur]};border:1px solid var(--bg-border)"></span>` : '';
    return `<div class="section-card" style="border-left:3px solid ${accent}">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.5rem">
        <div style="font-weight:600;color:${accent}">${escHtml(name)} <span style="color:var(--muted);font-weight:400">(Faction ${faction})</span></div>
        <div style="font-size:var(--type-xs);color:var(--muted);display:flex;align-items:center;gap:0.4rem">Couleur actuelle : ${swatch} <span style="color:var(--text-primary)">${escHtml(cur || '—')}</span></div>
        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <select id="fww-color-${faction}" class="select select-sm select-bordered" style="min-width:160px">${_fwwColorOptions(cur)}</select>
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.setColor('${faction}')">Enregistrer</button>
        </div>
      </div>
    </div>`;
  };
  return `<div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
    ${row('A', fa, 'var(--red,#ef4444)')}
    ${row('B', fb, '#3b82f6')}
  </div>`;
}

// ── STEP 5 — Origine de faction (cards with effects) ──────────────────────────
// Origin shape (FactionOriginManager.buildStateJson):
//   { available:[{ id, name, description?, effects?:[{label,kind?}]|[strings],
//                  powers?, badge? }], default:id|null, A:id|null, B:id|null }
// Tolerant of legacy {current:{A,B}} and effects as strings or objects.
function _fwwRenderOriginsInner(o, s) {
  if (!o) {
    return `<div class="empty-state-text">Origines de faction indisponibles (endpoint absent ou serveur de jeu hors ligne).</div>`;
  }
  const available = Array.isArray(o.available) ? o.available : [];
  if (!available.length) {
    return `<div class="empty-state-text">Aucune origine de faction disponible.</div>`;
  }
  const current = o.current || { A: o.A, B: o.B };
  const byId = {};
  available.forEach((or) => { if (or && or.id != null) byId[or.id] = or; });

  const curName = (faction) => {
    const id = current[faction];
    if (id == null) return 'Aucune';
    const or = byId[id];
    return or ? (or.name || id) : id;
  };

  // One faction column: header (current origin) + a grid of origin cards.
  const factionColumn = (faction, accent) => {
    const curId = current[faction];
    const cards = available.map((or) => _fwwOriginCard(faction, or, accent, String(or.id) === String(curId == null ? ' ' : curId))).join('');
    return `<div style="display:flex;flex-direction:column;gap:0.6rem">
      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
        <span style="font-weight:700;color:${accent}">Faction ${faction}</span>
        <span style="font-size:var(--type-sm);color:var(--muted)">Origine actuelle : <span style="color:var(--text-primary)">${escHtml(curName(faction))}</span></span>
        ${curId != null ? `<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fww.clearOrigin('${faction}')">Retirer</button>` : ''}
      </div>
      <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:0.6rem">${cards}</div>
    </div>`;
  };

  return `<div style="display:flex;flex-direction:column;gap:1.25rem">
    <div style="font-size:var(--type-sm);color:var(--muted)">
      Chaque origine confère des bonus/malus d'équipe. Choisissez-en une par faction (override admin) —
      ou laissez le <strong>capitaine</strong> la choisir lui-même en jeu (touche <kbd>O</kbd> / commande d'origine).
    </div>
    ${factionColumn('A', 'var(--red,#ef4444)')}
    ${factionColumn('B', '#3b82f6')}
  </div>`;
}

// A single selectable origin card showing its effects.
function _fwwOriginCard(faction, or, accent, isCurrent) {
  const id = or.id;
  const name = or.name || id;
  const desc = or.description || '';
  const icon = or.icon || or.badge || '🎖';
  const effects = _fwwOriginEffects(or);
  const effHtml = effects.length
    ? `<ul style="margin:0;padding-left:1.1rem;display:flex;flex-direction:column;gap:0.15rem">
        ${effects.map((e) => {
          const color = e.kind === 'bonus' ? 'var(--green,#22c55e)'
                      : e.kind === 'malus' ? 'var(--red,#ef4444)'
                      : 'var(--muted)';
          const sign = e.kind === 'bonus' ? '+ ' : e.kind === 'malus' ? '− ' : '';
          return `<li style="font-size:var(--type-xs);color:${color}">${sign}${escHtml(e.label)}</li>`;
        }).join('')}
      </ul>`
    : `<div style="font-size:var(--type-xs);color:var(--muted-light)">Aucun effet listé.</div>`;
  const border = isCurrent ? accent : 'var(--bg-border)';
  const ribbon = isCurrent
    ? `<span style="position:absolute;top:0.4rem;right:0.4rem;font-size:var(--type-xs);color:${accent};font-weight:700">✓ choisie</span>`
    : '';
  return `<div style="position:relative;border:1px solid ${border};border-radius:var(--radius,8px);
                       padding:0.6rem;background:var(--bg-elevated, rgba(255,255,255,0.02));display:flex;flex-direction:column;gap:0.4rem;
                       ${isCurrent ? 'box-shadow:0 0 0 1px ' + accent + ' inset;' : ''}">
    ${ribbon}
    <div style="display:flex;align-items:center;gap:0.4rem">
      <span style="font-size:var(--type-lg)">${escHtml(String(icon))}</span>
      <span style="font-weight:600">${escHtml(name)}</span>
    </div>
    ${desc ? `<div style="font-size:var(--type-xs);color:var(--muted)">${escHtml(desc)}</div>` : ''}
    ${effHtml}
    <button class="btn-cg ${isCurrent ? 'btn-cg-ghost' : 'btn-cg-primary'} btn-cg-sm" style="margin-top:auto"
            onclick="window._fww.pickOrigin('${faction}', '${escHtml(String(id))}')">
      ${isCurrent ? 'Choisie' : 'Choisir pour ' + faction}
    </button>
  </div>`;
}

// Normalise an origin's effects into [{label, kind}] where kind ∈ bonus|malus|neutral.
// Tolerant of: effects:[strings], effects:[{label,kind|type|positive}], powers:[...].
function _fwwOriginEffects(or) {
  const raw = (Array.isArray(or.effects) && or.effects)
           || (Array.isArray(or.powers) && or.powers)
           || (Array.isArray(or.bonuses) && or.bonuses)
           || [];
  return raw.map((e) => {
    if (e == null) return null;
    if (typeof e === 'string') {
      // Infer sign from a leading +/-.
      const t = e.trim();
      if (t.startsWith('+')) return { label: t.replace(/^\+\s*/, ''), kind: 'bonus' };
      if (t.startsWith('-') || t.startsWith('−')) return { label: t.replace(/^[−-]\s*/, ''), kind: 'malus' };
      return { label: t, kind: 'neutral' };
    }
    const label = e.label || e.name || e.text || e.description || String(e);
    let kind = 'neutral';
    const k = (e.kind || e.type || '').toString().toLowerCase();
    if (k === 'bonus' || k === 'positive' || k === 'buff' || e.positive === true) kind = 'bonus';
    else if (k === 'malus' || k === 'negative' || k === 'debuff' || e.positive === false || e.negative === true) kind = 'malus';
    return { label, kind };
  }).filter(Boolean);
}

// ── STEP 6 — Récap & lancement ────────────────────────────────────────────────
function _fwwStep6Recap(s, origins) {
  const day = (s && s.phaseState && s.phaseState.day != null) ? s.phaseState.day : '';
  return `<div style="display:flex;flex-direction:column;gap:1rem">
    <div id="fww-recap">${_fwwRenderRecapInner(s, origins)}</div>

    <div class="section-card" style="border-left:3px solid var(--amber)">
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.75rem">
        <div style="font-weight:600">Lancement</div>

        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <label class="field-label" style="min-width:160px">Phase de jeu</label>
          <input id="fww-launch-day" type="number" min="1" class="input input-sm input-bordered" style="width:90px" placeholder="jour" value="${day !== '' ? day : ''}">
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fww.phaseSetDay()">Définir le jour</button>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fww.phaseNext()">Jour suivant →</button>
        </div>

        <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap">
          <label class="field-label" style="min-width:160px">Fenêtre PvP (la guerre)</label>
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="window._fww.pvpOn()">⚔️ Ouvrir la guerre (PvP ON)</button>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="window._fww.pvpOff()">Trêve (PvP OFF)</button>
        </div>

        <div style="font-size:var(--type-xs);color:var(--muted)">
          Astuce : pour le contrôle fin pendant la partie (scores, pools, zones de capture, objectifs,
          modération, broadcast…), utilisez la vue <strong>Guerre de Factions</strong> dédiée.
        </div>
      </div>
    </div>
  </div>`;
}

// Recap summary (live).
function _fwwRenderRecapInner(s, origins) {
  s = s || {};
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const cfg = (window._fww && window._fww.config) || {};
  const o = origins || _fwwExtractOrigins(s) || {};
  const byId = {};
  (Array.isArray(o.available) ? o.available : []).forEach((or) => { if (or && or.id != null) byId[or.id] = or; });
  const cur = o.current || { A: o.A, B: o.B };
  const originName = (f) => {
    const id = cur[f];
    if (id == null) return '—';
    const or = byId[id];
    return or ? (or.name || id) : id;
  };

  const cell = (label, valHtml) =>
    `<div style="display:flex;justify-content:space-between;gap:1rem;padding:0.3rem 0;border-bottom:1px solid var(--bg-border)">
      <span style="color:var(--muted);font-size:var(--type-sm)">${label}</span>
      <span style="text-align:right">${valHtml}</span>
    </div>`;

  const col = (faction, fdat, accent) => {
    const colorVal = (fdat.color || (faction === 'A' ? cfg.teamColorA : cfg.teamColorB) || '—');
    const members = Array.isArray(fdat.members) ? fdat.members.length : (fdat.count != null ? fdat.count : 0);
    return `<div class="section-card" style="border-left:3px solid ${accent}">
      <div class="section-card-body">
        <div style="font-weight:700;color:${accent};margin-bottom:0.4rem">${escHtml(fdat.name || ('Faction ' + faction))}</div>
        ${cell('Capitaine', `<strong>${escHtml(fdat.captain || '—')}</strong>`)}
        ${cell('Membres', `<strong>${members}</strong>`)}
        ${cell('Couleur', escHtml(String(colorVal)))}
        ${cell('Origine', escHtml(String(originName(faction))))}
      </div>
    </div>`;
  };

  return `<div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
    ${col('A', fa, 'var(--red,#ef4444)')}
    ${col('B', fb, '#3b82f6')}
  </div>`;
}

// ── Shared helpers ────────────────────────────────────────────────────────────

// <select> of A | B factions, labelled with the live faction names when known.
function _fwwFactionSelect(id) {
  const s = (window._fww && window._fww.state) || {};
  const fa = (s.factions && s.factions.A) || {};
  const fb = (s.factions && s.factions.B) || {};
  const la = fa.name ? `A — ${fa.name}` : 'Faction A';
  const lb = fb.name ? `B — ${fb.name}` : 'Faction B';
  return `<select id="${id}" class="select select-sm select-bordered" style="min-width:150px">
    <option value="A">${escHtml(la)}</option>
    <option value="B">${escHtml(lb)}</option>
  </select>`;
}

// Build <option>s for a player picker. Source order: players.online, players.known,
// then roster members (de-duped). Online players get a • marker. Falls back to a
// single disabled hint when nothing is known.
function _fwwPlayerOptions(players, s) {
  const online = (players && Array.isArray(players.online)) ? players.online : [];
  const known  = (players && Array.isArray(players.known))  ? players.known  : [];
  // Roster fallback (covers builds without /players).
  const roster = [];
  if (s && s.factions) {
    ['A', 'B'].forEach((f) => {
      const fd = s.factions[f] || {};
      if (Array.isArray(fd.members)) roster.push(...fd.members);
      if (fd.captain) roster.push(fd.captain);
    });
  }
  const onlineSet = new Set(online);
  const seen = new Set();
  const opts = [];
  online.forEach((n) => { if (n && !seen.has(n)) { seen.add(n); opts.push({ n, online: true }); } });
  known.concat(roster).forEach((n) => { if (n && !seen.has(n)) { seen.add(n); opts.push({ n, online: onlineSet.has(n) }); } });

  if (!opts.length) {
    return `<option value="" disabled selected>Aucun joueur connu</option>`;
  }
  return opts.map((o) =>
    `<option value="${escHtml(o.n)}">${o.online ? '● ' : ''}${escHtml(o.n)}</option>`
  ).join('');
}

// Pull factionOrigins out of a /state snapshot, else null.
function _fwwExtractOrigins(s) {
  if (!s) return null;
  const fo = s.factionOrigins;
  if (fo && Array.isArray(fo.available)) return fo;
  return null;
}

// Minecraft colour formattings (matches the mod's Formatting.byName validation).
const _FWW_COLORS = [
  'black','dark_blue','dark_green','dark_aqua','dark_red','dark_purple','gold',
  'gray','dark_gray','blue','green','aqua','red','light_purple','yellow','white',
];
// Approx hex for the swatch preview (vanilla chat colours).
const _FWW_COLOR_HEX = {
  black:'#000000', dark_blue:'#0000AA', dark_green:'#00AA00', dark_aqua:'#00AAAA',
  dark_red:'#AA0000', dark_purple:'#AA00AA', gold:'#FFAA00', gray:'#AAAAAA',
  dark_gray:'#555555', blue:'#5555FF', green:'#55FF55', aqua:'#55FFFF',
  red:'#FF5555', light_purple:'#FF55FF', yellow:'#FFFF55', white:'#FFFFFF',
};
function _fwwColorOptions(selected) {
  const sel = (selected || '').toLowerCase();
  return _FWW_COLORS.map((c) => `<option value="${c}"${c === sel ? ' selected' : ''}>${c}</option>`).join('');
}
