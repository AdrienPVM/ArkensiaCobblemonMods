// ── CobbleCap ─────────────────────────────────────────────────────────────────

async function buildCobblecap(app) {
  let players, champions, config, stats;
  try {
    [players, champions, config, stats] = await Promise.all([
      API.cobblecap.players(), API.cobblecap.champions(),
      API.cobblecap.config(), API.cobblecap.stats()
    ]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  // Sort players by capLevel desc
  players.sort((a, b) => b.capLevel - a.capLevel);

  window._cc = {
    players, champions, config, stats, app,
    filter: '',
    tab: 'players',
    _saveTimer: null,
    _filterTimer: null,
  };

  const tabs = ['players', 'explock', 'champions', 'config', 'stats'];

  function tabBar() {
    return `<div class="detail-tabs">
      ${tabs.map(t2 => `<button class="detail-tab ${window._cc.tab === t2 ? 'active' : ''}" onclick="_ccSwitchTab('${t2}')">${t('cobblecap.' + t2)}</button>`).join('')}
    </div>`;
  }

  return `<div class="max-w-6xl mx-auto slide-in">
    <div class="section-card">
      <div class="section-card-header">
        <h2 class="section-card-title">${t('cobblecap.title')}</h2>
        <div style="display:flex;gap:8px;align-items:center">
          ${guideButton('cobblecap')}
        </div>
      </div>
      <div class="section-card-body" style="padding-top:0">
        ${tabBar()}
        <div id="cc-tab-body">${_ccRenderTab()}</div>
      </div>
    </div>
  </div>`;
}

// ── Tab switching ─────────────────────────────────────────────────────────────

function _ccSwitchTab(tab) {
  window._cc.tab = tab;
  // Update tab buttons
  document.querySelectorAll('.detail-tab').forEach(btn => {
    btn.classList.toggle('active', btn.textContent.trim() === t('cobblecap.' + tab));
  });
  const body = document.getElementById('cc-tab-body');
  if (body) body.innerHTML = _ccRenderTab();
}

function _ccRenderTab() {
  switch (window._cc.tab) {
    case 'players':   return _ccRenderPlayers();
    case 'explock':   return _ccRenderExpLock();
    case 'champions': return _ccRenderChampions();
    case 'config':    return _ccRenderConfig();
    case 'stats':     return _ccRenderStats();
    default:          return '';
  }
}

// ── ExpLock tab ──────────────────────────────────────────────────────────────

function _ccRenderExpLock() {
  const cc = window._cc;
  const locked = cc.players.filter(p => p.expLocked);
  const unlocked = cc.players.filter(p => !p.expLocked);

  return `<div style="margin-bottom:12px">
    <p class="text-muted" style="margin-bottom:8px">${t('cobblecap.explock.desc')}</p>
  </div>
  <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px">
    <div class="section-card" style="margin:0">
      <div class="section-card-header"><h3 class="section-card-title" style="font-size:0.9rem">${t('cobblecap.explock.locked')} (${locked.length})</h3></div>
      <div class="section-card-body" style="max-height:400px;overflow-y:auto;padding:8px">
        ${locked.length ? locked.map(p => `<div class="hover-elevated" style="display:flex;align-items:center;justify-content:space-between;padding:6px 10px;border-radius:6px;margin-bottom:4px">
          <span class="mono" style="font-size:0.85rem">${escHtml(p.playerName)}</span>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_ccToggleExpLock('${escHtml(p.uuid)}')" title="${t('cobblecap.explock.unlock')}">${t('cobblecap.explock.unlock')}</button>
        </div>`).join('') : `<span class="text-muted">${t('cobblecap.explock.none')}</span>`}
      </div>
    </div>
    <div class="section-card" style="margin:0">
      <div class="section-card-header"><h3 class="section-card-title" style="font-size:0.9rem">${t('cobblecap.explock.unlocked')} (${unlocked.length})</h3></div>
      <div class="section-card-body" style="max-height:400px;overflow-y:auto;padding:8px">
        ${unlocked.length ? unlocked.map(p => `<div class="hover-elevated" style="display:flex;align-items:center;justify-content:space-between;padding:6px 10px;border-radius:6px;margin-bottom:4px">
          <span class="mono" style="font-size:0.85rem">${escHtml(p.playerName)}</span>
          <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_ccToggleExpLock('${escHtml(p.uuid)}')" title="${t('cobblecap.explock.lock')}">${t('cobblecap.explock.lock')}</button>
        </div>`).join('') : `<span class="text-muted">${t('cobblecap.explock.none')}</span>`}
      </div>
    </div>
  </div>`;
}

async function _ccToggleExpLock(uuid) {
  try {
    const result = await API.cobblecap.toggleExpLock(uuid);
    // Update local data
    const p = window._cc.players.find(x => x.uuid === uuid);
    if (p) p.expLocked = result.expLocked;
    // Re-render
    const body = document.getElementById('cc-tab-body');
    if (body) body.innerHTML = _ccRenderExpLock();
    window._cc.app.toast(t('common.success'), 'success');
  } catch (e) {
    window._cc.app.toast(e.message, 'error');
  }
}

// ── Players tab ───────────────────────────────────────────────────────────────

function _ccRenderPlayers() {
  const cc = window._cc;
  const filtered = cc.filter
    ? cc.players.filter(p => p.playerName.toLowerCase().includes(cc.filter.toLowerCase()))
    : cc.players;

  if (!cc.players.length) {
    return `<div class="empty-state"><span class="empty-state-icon">👥</span><span class="empty-state-text">${t('cobblecap.noPlayers')}</span></div>`;
  }

  return `<div class="filter-bar" style="margin-bottom:12px">
    <input type="text" class="input input-sm input-bordered" placeholder="${escHtml(t('cobblecap.search'))}"
      value="${escHtml(cc.filter)}" oninput="_ccFilterPlayers(this.value)" style="max-width:280px">
  </div>
  <div style="overflow-x:auto">
    <table class="cg-table">
      <thead><tr>
        <th>${t('cobblecap.playerName')}</th>
        <th>${t('cobblecap.capLevel')} ${tip('tip.cobblecap.capLevel')}</th>
        <th>${t('cobblecap.championsBeaten')}</th>
        <th>${t('cobblecap.lastCapChange')}</th>
        <th></th>
      </tr></thead>
      <tbody>${filtered.map(p => `<tr>
        <td>
          <div style="display:flex;align-items:center;gap:8px">
            <span>${escHtml(p.playerName)}</span>
            <span class="id-badge">${escHtml(p.uuid.substring(0, 8))}</span>
          </div>
        </td>
        <td>
          <input type="number" min="1" max="100" value="${p.capLevel}"
            class="input input-sm input-bordered" style="width:80px"
            id="cc-cap-${escHtml(p.uuid)}">
        </td>
        <td class="td-mono">${p.championsBeatenCount}</td>
        <td>${p.lastCapChange ? I18N.fmtDate(p.lastCapChange) : '—'}</td>
        <td>
          <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="_ccSetCap('${escHtml(p.uuid)}')">${t('cobblecap.setCap')}</button>
        </td>
      </tr>`).join('')}</tbody>
    </table>
  </div>`;
}

function _ccFilterPlayers(val) {
  window._cc.filter = val;
  clearTimeout(window._cc._filterTimer);
  window._cc._filterTimer = setTimeout(() => {
    const body = document.getElementById('cc-tab-body');
    if (body && window._cc.tab === 'players') body.innerHTML = _ccRenderPlayers();
  }, 300);
}

async function _ccSetCap(uuid) {
  const input = document.getElementById('cc-cap-' + uuid);
  if (!input) return;
  const val = parseInt(input.value, 10);
  if (isNaN(val) || val < 1 || val > 100) {
    window._cc.app.toast(t('cobblecap.invalidCap'), 'error');
    return;
  }
  try {
    await API.cobblecap.setCap(uuid, { capLevel: val });
    window._cc.app.toast(t('cobblecap.capUpdated', { level: val }), 'success');
    // Refresh player list
    window._cc.players = await API.cobblecap.players();
    window._cc.players.sort((a, b) => b.capLevel - a.capLevel);
    const body = document.getElementById('cc-tab-body');
    if (body && window._cc.tab === 'players') body.innerHTML = _ccRenderPlayers();
  } catch (e) {
    window._cc.app.toast(e.message, 'error');
  }
}

// ── Champions tab ─────────────────────────────────────────────────────────────

function _ccRenderChampions() {
  const cc = window._cc;

  const addBtn = `<button class="btn-cg btn-cg-sm btn-cg-primary" onclick="_ccAddChampion()" style="margin-bottom:12px">${t('cobblecap.addChampion')}</button>`;

  if (!cc.champions.length) {
    return `<div style="margin-bottom:12px">${addBtn}</div>
      <div class="empty-state"><span class="empty-state-icon">🏅</span><span class="empty-state-text">${t('cobblecap.noChampions')}</span></div>`;
  }

  return `<div style="margin-bottom:12px">${addBtn}</div>
  <div style="overflow-x:auto">
    <table class="cg-table" id="cc-champ-table">
      <thead><tr>
        <th>#</th>
        <th>${t('cobblecap.championId')} ${tip('tip.cobblecap.trainerId', 'field-tip-down')}</th>
        <th>${t('cobblecap.displayName')}</th>
        <th>${t('cobblecap.capGranted')} ${tip('tip.cobblecap.capGranted', 'field-tip-down')}</th>
        <th>${t('cobblecap.requiredPokedex')} ${tip('tip.cobblecap.requiredPokedex', 'field-tip-down')}</th>
        <th>${t('cobblecap.requiredQuest')} ${tip('tip.cobblecap.requiredQuest', 'field-tip-down')}</th>
        <th>${t('cobblecap.victoryQuest')} ${tip('tip.cobblecap.victoryQuest', 'field-tip-down')}</th>
        <th>${t('cobblecap.challengeId')} ${tip('tip.cobblecap.challengeId', 'field-tip-down')}</th>
        <th>${t('cobblecap.challengeQuest')} ${tip('tip.cobblecap.challengeQuest', 'field-tip-down')}</th>
        <th>${t('cobblecap.challengeCooldown')} ${tip('tip.cobblecap.challengeCooldown', 'field-tip-down')}</th>
        <th></th>
      </tr></thead>
      <tbody>${cc.champions.map((c, i) => `<tr data-idx="${i}">
        <td class="td-mono">${i + 1}</td>
        <td><input type="text" class="input input-sm input-bordered" style="min-width:160px"
          value="${escHtml(c.id)}" data-field="id" oninput="_ccChampionChanged()"></td>
        <td><input type="text" class="input input-sm input-bordered" style="min-width:100px"
          value="${escHtml(c.displayName)}" data-field="displayName" oninput="_ccChampionChanged()"></td>
        <td><input type="number" min="0" class="input input-sm input-bordered" style="width:80px"
          value="${c.capGranted}" data-field="capGranted" oninput="_ccChampionChanged()"></td>
        <td><input type="number" min="0" class="input input-sm input-bordered" style="width:80px"
          value="${c.requiredPokedex}" data-field="requiredPokedex" oninput="_ccChampionChanged()"></td>
        <td><input type="text" class="input input-sm input-bordered" style="min-width:100px"
          value="${escHtml(c.requiredQuestId || '')}" data-field="requiredQuestId" oninput="_ccChampionChanged()"></td>
        <td><input type="text" class="input input-sm input-bordered" style="min-width:100px"
          value="${escHtml(c.victoryQuestId || '')}" data-field="victoryQuestId" oninput="_ccChampionChanged()"></td>
        <td><input type="text" class="input input-sm input-bordered" style="min-width:100px"
          value="${escHtml(c.challengeId || '')}" data-field="challengeId" oninput="_ccChampionChanged()"></td>
        <td><input type="text" class="input input-sm input-bordered" style="min-width:100px"
          value="${escHtml(c.challengeQuestId || '')}" data-field="challengeQuestId" oninput="_ccChampionChanged()"></td>
        <td><input type="number" min="0" class="input input-sm input-bordered" style="width:80px"
          value="${c.challengeCooldownHours || 0}" data-field="challengeCooldownHours" oninput="_ccChampionChanged()"></td>
        <td><button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_ccRemoveChampion(${i})">${t('cobblecap.removeChampion')}</button></td>
      </tr>`).join('')}</tbody>
    </table>
  </div>`;
}

function _ccChampionChanged() {
  clearTimeout(window._cc._saveTimer);
  window._cc._saveTimer = setTimeout(() => _ccSaveChampions(), 500);
}

async function _ccSaveChampions() {
  const table = document.getElementById('cc-champ-table');
  if (!table) return;
  const rows = table.querySelectorAll('tbody tr');
  const list = [];
  rows.forEach(row => {
    const obj = {};
    row.querySelectorAll('input[data-field]').forEach(inp => {
      const field = inp.dataset.field;
      if (field === 'capGranted' || field === 'requiredPokedex' || field === 'challengeCooldownHours') {
        obj[field] = parseInt(inp.value, 10) || 0;
      } else {
        obj[field] = inp.value;
      }
    });
    list.push(obj);
  });
  try {
    const res = await API.cobblecap.saveChamps(list);
    window._cc.champions = list;
    window._cc.app.toast(t('cobblecap.championsSaved', { count: res.count }), 'success');
  } catch (e) {
    window._cc.app.toast(e.message, 'error');
  }
}

function _ccAddChampion() {
  window._cc.champions.push({
    id: '', displayName: '', capGranted: 20, requiredPokedex: 0,
    requiredQuestId: '', victoryQuestId: '', challengeId: '',
    challengeQuestId: '', challengeCooldownHours: 0
  });
  const body = document.getElementById('cc-tab-body');
  if (body) body.innerHTML = _ccRenderChampions();
}

function _ccRemoveChampion(index) {
  window._cc.champions.splice(index, 1);
  const body = document.getElementById('cc-tab-body');
  if (body) body.innerHTML = _ccRenderChampions();
  _ccSaveChampions();
}

// ── Config tab ────────────────────────────────────────────────────────────────

function _ccRenderConfig() {
  const cfg = window._cc.config;
  const banned = cfg.bannedSpecies || [];
  const zones = cfg.availableZones || [];
  const fwEnabled = !!cfg.fireworksEnabled;
  const fwZone = cfg.fireworksZoneId || '';
  const fwCount = cfg.fireworksCount ?? 12;
  const fwInterval = cfg.fireworksIntervalMs ?? 800;
  const fwDuration = cfg.fireworksDurationSec ?? 12;

  // Zone dropdown — if CheeseGuard is absent or no zones, falls back to a free-text input.
  const zoneSelect = zones.length
    ? `<select id="cc-fw-zone" class="select select-sm select-bordered" style="width:260px">
        <option value="">— ${escHtml(t('cobblecap.fireworksZoneNone') || 'aucune')} —</option>
        ${zones.map(z => `<option value="${escHtml(z)}" ${z === fwZone ? 'selected' : ''}>${escHtml(z)}</option>`).join('')}
      </select>`
    : `<input type="text" class="input input-sm input-bordered" id="cc-fw-zone"
         value="${escHtml(fwZone)}" placeholder="cheeseguard zone id"
         style="width:260px">
       <div style="color:var(--muted);font-size:12px;margin-top:4px">
         ${escHtml(t('cobblecap.fireworksNoCheeseGuard') || 'CheeseGuard absent — saisir l\'id manuellement')}
       </div>`;

  return `<div style="max-width:600px;display:flex;flex-direction:column;gap:16px;padding-top:12px">
    <div>
      <label class="field-label">${t('cobblecap.enabled')} ${tip('tip.cobblecap.enabled')}</label>
      <input type="checkbox" class="toggle" id="cc-cfg-enabled" ${cfg.enabled ? 'checked' : ''}>
    </div>
    <div>
      <label class="field-label">${t('cobblecap.startingCap')}</label>
      <span class="td-mono">${cfg.startingCap}</span>
    </div>
    <div class="section-divider"></div>
    <div>
      <label class="field-label">${t('cobblecap.bannedSpecies')} ${tip('tip.cobblecap.bannedSpecies')}</label>
      <div id="cc-banned-list" style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px">
        ${banned.map((s, i) => `<span class="id-badge" style="display:inline-flex;align-items:center;gap:4px">
          ${escHtml(s)}
          <button onclick="_ccRemoveBanned(${i})" style="background:none;border:none;color:var(--red);cursor:pointer;padding:0;font-size:14px;line-height:1">&times;</button>
        </span>`).join('')}
        ${!banned.length ? `<span style="color:var(--muted)">${t('cobblecap.noBanned')}</span>` : ''}
      </div>
      <div style="display:flex;gap:6px;align-items:center">
        <input type="text" class="input input-sm input-bordered" id="cc-banned-input"
          placeholder="${escHtml(t('cobblecap.bannedPlaceholder'))}"
          style="width:200px" onkeydown="if(event.key==='Enter')_ccAddBanned()">
        <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="_ccAddBanned()">+</button>
      </div>
    </div>
    <div class="section-divider"></div>
    <div class="section-card">
      <div class="section-card-header">
        <div class="section-card-title">🎆 ${escHtml(t('cobblecap.fireworksTitle') || 'Feux d\'artifice — Nouveau Maître Compétitif')}</div>
      </div>
      <div class="section-card-body" style="display:flex;flex-direction:column;gap:12px">
        <div style="color:var(--muted-light);font-size:13px">
          ${escHtml(t('cobblecap.fireworksDesc') || 'Lance une salve de fusées dans la zone choisie chaque fois qu\'un joueur devient Maître de la Ligue en mode Compétitif.')}
        </div>
        <div>
          <label class="field-label">${escHtml(t('cobblecap.fireworksEnabled') || 'Activé')}</label>
          <input type="checkbox" class="toggle" id="cc-fw-enabled" ${fwEnabled ? 'checked' : ''}>
        </div>
        <div>
          <label class="field-label">${escHtml(t('cobblecap.fireworksZone') || 'Zone CheeseGuard')}</label>
          ${zoneSelect}
        </div>
        <div style="display:flex;gap:16px;flex-wrap:wrap">
          <div>
            <label class="field-label">${escHtml(t('cobblecap.fireworksCount') || 'Nombre de fusées')}</label>
            <input type="number" class="input input-sm input-bordered" id="cc-fw-count"
              value="${fwCount}" min="1" max="500" style="width:110px">
          </div>
          <div>
            <label class="field-label">${escHtml(t('cobblecap.fireworksInterval') || 'Intervalle (ms)')}</label>
            <input type="number" class="input input-sm input-bordered" id="cc-fw-interval"
              value="${fwInterval}" min="50" max="10000" step="50" style="width:130px">
          </div>
          <div>
            <label class="field-label">${escHtml(t('cobblecap.fireworksDuration') || 'Durée max (s)')}</label>
            <input type="number" class="input input-sm input-bordered" id="cc-fw-duration"
              value="${fwDuration}" min="1" max="300" style="width:110px">
          </div>
        </div>
        <div style="display:flex;align-items:center;gap:10px">
          <button class="btn-cg btn-cg-ghost" onclick="_ccTestFireworks()" title="${escHtml(t('cobblecap.fireworksTestTip') || 'Lance immédiatement une salve dans la zone configurée — ignore l\'état Activé')}">
            🎆 ${escHtml(t('cobblecap.fireworksTest') || 'Tester les feux')}
          </button>
          <span style="color:var(--muted);font-size:12px">
            ${escHtml(t('cobblecap.fireworksTestHint') || 'Sauvegarde d\'abord la config si tu viens de changer la zone.')}
          </span>
        </div>
      </div>
    </div>
    <div>
      <button class="btn-cg btn-cg-primary" onclick="_ccSaveConfig()">${t('cobblecap.save')}</button>
    </div>
  </div>`;
}

function _ccAddBanned() {
  const input = document.getElementById('cc-banned-input');
  if (!input) return;
  const val = input.value.trim().toLowerCase();
  if (!val) return;
  const banned = window._cc.config.bannedSpecies || [];
  if (banned.includes(val)) {
    window._cc.app.toast(t('cobblecap.bannedAlreadyExists', { name: val }), 'error');
    return;
  }
  banned.push(val);
  window._cc.config.bannedSpecies = banned;
  input.value = '';
  const body = document.getElementById('cc-tab-body');
  if (body) body.innerHTML = _ccRenderConfig();
}

function _ccRemoveBanned(index) {
  const banned = window._cc.config.bannedSpecies || [];
  banned.splice(index, 1);
  window._cc.config.bannedSpecies = banned;
  const body = document.getElementById('cc-tab-body');
  if (body) body.innerHTML = _ccRenderConfig();
}

async function _ccTestFireworks() {
  // Use the currently-saved config — the user must save first if they tweaked
  // the zone. Inform them via the hint text next to the button.
  try {
    const res = await API.cobblecap.testFireworks();
    window._cc.app.toast(
      t('cobblecap.fireworksTestStarted', { zone: res.zoneId, count: res.count }) ||
      `Salve lancée dans ${res.zoneId} (${res.count} fusées)`,
      'success'
    );
  } catch (e) {
    window._cc.app.toast(e.message, 'error');
  }
}

async function _ccSaveConfig() {
  const enabled = document.getElementById('cc-cfg-enabled')?.checked ?? true;
  const bannedSpecies = window._cc.config.bannedSpecies || [];
  const fireworksEnabled = document.getElementById('cc-fw-enabled')?.checked ?? false;
  const fireworksZoneId = document.getElementById('cc-fw-zone')?.value ?? '';
  const fireworksCount = parseInt(document.getElementById('cc-fw-count')?.value || '12', 10);
  const fireworksIntervalMs = parseInt(document.getElementById('cc-fw-interval')?.value || '800', 10);
  const fireworksDurationSec = parseInt(document.getElementById('cc-fw-duration')?.value || '12', 10);
  try {
    await API.cobblecap.saveConfig({
      enabled, bannedSpecies,
      fireworksEnabled, fireworksZoneId,
      fireworksCount, fireworksIntervalMs, fireworksDurationSec
    });
    Object.assign(window._cc.config, {
      enabled, fireworksEnabled, fireworksZoneId,
      fireworksCount, fireworksIntervalMs, fireworksDurationSec
    });
    window._cc.app.toast(t('cobblecap.configSaved'), 'success');
  } catch (e) {
    window._cc.app.toast(e.message, 'error');
  }
}

// ── Stats tab ─────────────────────────────────────────────────────────────────

function _ccRenderStats() {
  const st = window._cc.stats;
  if (!st || !st.tiers || !st.tiers.length) {
    return `<div class="empty-state"><span class="empty-state-icon">📊</span><span class="empty-state-text">${t('cobblecap.noPlayers')}</span></div>`;
  }

  const totalCard = `<div class="stat-card stat-card-glow" style="margin-bottom:16px">
    <span class="stat-icon">👥</span>
    <span class="stat-value">${st.totalPlayers}</span>
    <span class="stat-label">${t('cobblecap.totalPlayers')}</span>
  </div>`;

  const tierCards = st.tiers.map(tier => `<div class="stat-card">
    <span class="stat-value">${tier.count}</span>
    <span class="stat-label">${t('cobblecap.playersAtCap', { cap: tier.cap })}</span>
  </div>`).join('');

  return `<div style="padding-top:12px">
    ${totalCard}
    <h3 style="margin-bottom:12px;color:var(--muted-light)">${t('cobblecap.tierDistribution')}</h3>
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      ${tierCards}
    </div>
  </div>`;
}
