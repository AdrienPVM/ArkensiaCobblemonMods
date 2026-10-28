// ── Events & Templates ───────────────────────────────────────────────────────

async function buildEvents(app) {
  let events;
  try {
    events = await API.events.list();
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const _e = window._ev = {
    events, app,
    search: '', stateFilter: '',

    getFiltered() {
      return this.events.filter(e => {
        if (this.search) {
          const q = this.search.toLowerCase();
          if (!e.name.toLowerCase().includes(q) && !e.id.toLowerCase().includes(q)) return false;
        }
        if (this.stateFilter && e.state !== this.stateFilter) return false;
        return true;
      });
    },

    renderTable() {
      const filtered = this.getFiltered();
      const tbody = document.getElementById('events-tbody');
      if (tbody) tbody.innerHTML = filtered.map(e => eventRowHtml(e)).join('');
      const cnt = document.getElementById('events-count');
      if (cnt) cnt.textContent = filtered.length;
    },

    async refresh() {
      try {
        this.events = await API.events.list();
        this.renderTable();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async create() {
      const inp = document.getElementById('event-name-input');
      if (!inp) return;
      const name = inp.value.trim();
      if (!name) return;
      try {
        const created = await API.events.create({ name });
        app.toast(t('events.created'), 'success');
        inp.value = '';
        if (created && created.id) app.navigate('events:' + created.id);
        else await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async doAction(id, action) {
      try {
        if (action === 'start') await API.events.start(id);
        else if (action === 'stop') await API.events.stop(id);
        else if (action === 'announce') await API.events.announce(id);
        app.toast(t('events.actionDone'), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async loadTemplate(name) {
      try {
        const created = await API.events.loadTemplate(name);
        app.toast('' + t('events.created') + '', 'success');
        document.getElementById('event-templates-modal')?.close();
        if (created && created.id) app.navigate('events:' + created.id);
        else await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteTemplate(name) {
      if (!await confirmAction(t('common.deleteConfirm') + ' ' + name + '" ?')) return;
      try {
        await API.events.deleteTemplate(name);
        app.toast(t('events.deleted'), 'success');
        this.showTemplates();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    onWsEventUpdate(msg) {
      if (!msg.eventId) return;
      const idx = this.events.findIndex(e => e.id === msg.eventId);
      if (idx >= 0 && msg.state) this.events[idx].state = msg.state;
      this.renderTable();
    },
  };

  function eventStateBadge(state) {
    const colors = { IDLE: 'text-muted', ANNOUNCED: 'text-cyan', QUEUE: 'text-cyan', COUNTDOWN: 'text-amber',
      ACTIVE: 'text-green', ENDING: 'text-amber', CLEANUP: 'text-muted', ENDED: 'text-muted' };
    const cls = colors[state] || 'text-muted';
    return '<span class="text-xs font-mono ' + cls + '">' + state + '</span>';
  }

  function eventRowHtml(e) {
    const nav = "_nav('events:" + escHtml(e.id) + "')";
    const state = eventStateBadge(e.state);
    return '<tr class="cursor-pointer" onclick="' + nav + '">\
      <td><span class="id-badge">' + escHtml(e.id) + '</span> <button class="btn-cg-sm" style="padding:0 4px;font-size:10px;vertical-align:middle" title="/cg event join ' + escHtml(e.id) + '" onclick="event.stopPropagation();cgCopy(\'/cg event join ' + escHtml(e.id) + '\').then(()=>_ev.app.toast(t(\'common.copied\'),\'success\',1500))">📋</button></td>\
      <td style="color:var(--text-primary)" class="text-sm">' + escHtml(e.name) + '</td>\
      <td>' + state + '</td>\
      <td class="text-sm text-muted font-mono">' + e.mode + '</td>\
      <td><span class="text-sm">' + e.participantCount + ' <span class="text-muted">' + t('common.players') + '</span></span></td>\
      <td class="flex gap-1">' +
        (e.state === 'IDLE' ? '<button class="btn-cg btn-cg-primary btn-cg-sm" onclick="event.stopPropagation();_ev.doAction(\'' + escHtml(e.id) + '\',\'announce\')">' + t('events.announce') + '</button>' : '') +
        (e.state === 'IDLE' || e.state === 'ANNOUNCED' || e.state === 'QUEUE' ? '<button class="btn-cg btn-cg-success btn-cg-sm" onclick="event.stopPropagation();_ev.doAction(\'' + escHtml(e.id) + '\',\'start\')">' + t('events.startEvent') + '</button>' : '') +
        (e.state === 'ACTIVE' || e.state === 'COUNTDOWN' ? '<button class="btn-cg btn-cg-danger btn-cg-sm" onclick="event.stopPropagation();_ev.doAction(\'' + escHtml(e.id) + '\',\'stop\')">' + t('events.stopEvent') + '</button>' : '') +
      '<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="event.stopPropagation();' + nav + '">' + t('common.details') + '</button>\
      </td>\
    </tr>';
  }

  // Build main HTML
  const rows = events.map(e => eventRowHtml(e)).join('');

  return `
    <div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <div class="section-card">
        <div class="section-card-header">
          <span class="section-card-title">${t('events.title')} <span id="events-count" class="text-muted text-xs font-mono ml-2">${events.length}</span></span>
          <div class="flex gap-2">
            <button class="btn-cg btn-cg-ghost" onclick="_nav('events:templates')">${t('events.templates')}</button>
          </div>
        </div>
        <div class="filter-bar">
          <input type="text" placeholder="${t('common.search')}"
            class="input input-sm input-bordered text-sm max-w-xs"
            oninput="_ev.search=this.value;_ev.renderTable()">
          <select class="select select-sm select-bordered text-sm"
            onchange="_ev.stateFilter=this.value;_ev.renderTable()">
            <option value="">${t('common.all')}</option>
            <option value="IDLE">IDLE</option>
            <option value="ANNOUNCED">ANNOUNCED</option>
            <option value="QUEUE">QUEUE</option>
            <option value="COUNTDOWN">COUNTDOWN</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="ENDING">ENDING</option>
            <option value="CLEANUP">CLEANUP</option>
          </select>
          <div class="flex-1"></div>
          <input id="event-name-input" class="input input-sm input-bordered max-w-xs" placeholder="${t('events.newEvent')}">
          <button class="btn-cg btn-cg-primary" onclick="_ev.create()">${t('common.create')}</button>
        </div>
        ${events.length === 0 ? '<div class="empty-state py-8"><span class="empty-state-icon">\uD83C\uDFAF</span><span class="empty-state-text">' + t('common.noResults') + '</span></div>' : `
        <table class="cg-table">
          <thead><tr><th>${t('common.id')}</th><th>${t('common.name')}</th><th>${t('common.status')}</th><th>${t('events.mode')}</th><th>${t('events.participants')}</th><th>${t('common.actions')}</th></tr></thead>
          <tbody id="events-tbody">${rows}</tbody>
        </table>`}
      </div>
    </div>`;
}

function edSwitchTab(tabId, btn) {
  if (tabId === 'timeline') {
    const evtId = window._ed && window._ed.evt ? window._ed.evt.id : null;
    if (evtId) { _nav('timeline:' + evtId); return; }
  }
  switchDetailTab(tabId, btn, 'ed');
}

async function edSaveMiniGameConfig() {
  if (!window._ed || !window._ed.evt) return;
  const evt = window._ed.evt;
  const schema = MINIGAME_CONFIG_SCHEMA[evt.miniGameType];
  if (!schema) return;
  const cfg = {};
  schema.fields.forEach(function(f) {
    if (f.t === 'tpSection') {
      var prefix = f.k;
      var modeEl = document.querySelector('input[name="ed-mg-' + prefix + '-mode"]:checked');
      var mode = modeEl ? modeEl.value : 'coords';
      if (mode === 'zones') {
        cfg[prefix + 'ZoneIds'] = (window._ed._mgTpZones && window._ed._mgTpZones[prefix]) || [];
        cfg[prefix + 'World'] = ''; cfg[prefix + 'X'] = 0; cfg[prefix + 'Y'] = 0; cfg[prefix + 'Z'] = 0;
      } else {
        var wEl = document.getElementById('ed-mg-' + prefix + 'World');
        cfg[prefix + 'World'] = wEl ? wEl.value : '';
        ['X','Y','Z'].forEach(function(a) {
          var el = document.getElementById('ed-mg-' + prefix + a);
          cfg[prefix + a] = el ? Number(el.value) : 0;
        });
        cfg[prefix + 'ZoneIds'] = [];
      }
      return;
    }
    if (f.t === 'zonelist') {
      cfg[f.k] = (window._ed._mgZoneLists && window._ed._mgZoneLists[f.k]) || [];
      return;
    }
    if (f.t === 'cmdlist') {
      cfg[f.k] = (window._ed._mgCmdLists && window._ed._mgCmdLists[f.k]) || [];
      return;
    }
    if (f.t === 'poweruplist') {
      cfg[f.k] = window._ed._mgPowerUps || [];
      return;
    }
    if (f.t === 'specieslist') {
      cfg[f.k] = (window._ed._mgSpeciesList && window._ed._mgSpeciesList[f.k]) || [];
      return;
    }
    if (f.t === 'powerconfig') {
      var list = (window._ed._mgPowerConfigs && window._ed._mgPowerConfigs[f.k]) || [];
      var obj = {};
      list.forEach(function(p) { obj[p.id] = { enabled: p.enabled, cooldown: p.cooldown }; });
      cfg[f.k] = obj;
      return;
    }
    const el = document.getElementById('ed-mg-' + f.k);
    if (!el) return;
    if (f.t === 'bool') cfg[f.k] = el.checked;
    else if (f.t === 'text') cfg[f.k] = el.value;
    else if (f.t === 'select') cfg[f.k] = el.value;
    else cfg[f.k] = Number(el.value);
  });
  try {
    window._ed.evt = await API.events.update(evt.id, { miniGameConfig: cfg });
    window._ed.app.toast(t('common.success'), 'success');
  } catch (e) { window._ed.app.toast(e.message, 'error'); }
}

function edResetMiniGameConfig() {
  if (!window._ed || !window._ed.evt) return;
  const schema = MINIGAME_CONFIG_SCHEMA[window._ed.evt.miniGameType];
  if (!schema) return;
  schema.fields.forEach(function(f) {
    const el = document.getElementById('ed-mg-' + f.k);
    if (f.t === 'tpSection') {
      var prefix = f.k;
      if (!window._ed._mgTpZones) window._ed._mgTpZones = {};
      window._ed._mgTpZones[prefix] = [];
      edMgTpRenderZones(prefix);
      ['World','X','Y','Z'].forEach(function(a) {
        var el = document.getElementById('ed-mg-' + prefix + a);
        if (el) el.value = a === 'World' ? '' : '0';
      });
      edMgTpToggle(prefix, 'coords');
      var radio = document.querySelector('input[name="ed-mg-' + prefix + '-mode"][value="coords"]');
      if (radio) radio.checked = true;
      return;
    }
    if (f.t === 'zonelist') {
      if (!window._ed._mgZoneLists) window._ed._mgZoneLists = {};
      window._ed._mgZoneLists[f.k] = [];
      edMgRenderZoneList(f.k);
      return;
    }
    if (f.t === 'cmdlist') {
      if (!window._ed._mgCmdLists) window._ed._mgCmdLists = {};
      window._ed._mgCmdLists[f.k] = [];
      edMgRenderCmdList(f.k);
      return;
    }
    if (f.t === 'poweruplist') {
      if (!window._ed._mgPowerUps) window._ed._mgPowerUps = [];
      window._ed._mgPowerUps = [];
      edMgRenderPowerUps();
      return;
    }
    if (f.t === 'specieslist') {
      if (!window._ed._mgSpeciesList) window._ed._mgSpeciesList = {};
      window._ed._mgSpeciesList[f.k] = [];
      edMgRenderSpeciesList(f.k);
      return;
    }
    if (f.t === 'powerconfig') {
      if (!window._ed._mgPowerConfigs) window._ed._mgPowerConfigs = {};
      var powerList = [];
      (f.powers || []).forEach(function(p) {
        powerList.push({ id: p.id, icon: p.icon, enabled: p.def.enabled, cooldown: p.def.cooldown });
      });
      window._ed._mgPowerConfigs[f.k] = powerList;
      edMgRenderPowerConfig(f.k);
      return;
    }
    if (!el) return;
    if (f.t === 'bool') el.checked = f.def;
    else el.value = f.def !== undefined ? f.def : '';
  });
}

// ── Mini-game config helpers: zone list ──────────────────────────────────────
function edMgAddZone(fieldKey) {
  var inp = document.getElementById('ed-mg-' + fieldKey + '-input');
  if (!inp || !inp.value.trim()) return;
  if (!window._ed._mgZoneLists) window._ed._mgZoneLists = {};
  if (!window._ed._mgZoneLists[fieldKey]) window._ed._mgZoneLists[fieldKey] = [];
  window._ed._mgZoneLists[fieldKey].push(inp.value.trim());
  inp.value = '';
  edMgRenderZoneList(fieldKey);
}
function edMgRemoveZone(fieldKey, idx) {
  if (!window._ed._mgZoneLists || !window._ed._mgZoneLists[fieldKey]) return;
  window._ed._mgZoneLists[fieldKey].splice(idx, 1);
  edMgRenderZoneList(fieldKey);
}
function edMgRenderZoneList(fieldKey) {
  var container = document.getElementById('ed-mg-' + fieldKey + '-badges');
  if (!container) return;
  var zones = (window._ed._mgZoneLists && window._ed._mgZoneLists[fieldKey]) || [];
  container.innerHTML = zones.map(function(z, i) {
    return '<span class="id-badge" style="display:inline-flex;align-items:center;gap:4px;margin:2px">' +
      '<span style="color:var(--amber);font-weight:600">' + (i + 1) + '.</span> ' + escHtml(z) +
      ' <button class="btn-cg-sm" style="padding:0 4px;line-height:1;font-size:10px" onclick="edMgRemoveZone(\'' + escHtml(fieldKey) + '\',' + i + ')">\u00d7</button>' +
    '</span>';
  }).join('');
}

// ── Mini-game config helpers: TP section (coords/zones toggle + use my position) ──
function edMgTpToggle(prefix, mode) {
  var coordsEl = document.getElementById('ed-mg-' + prefix + '-coords');
  var zonesEl = document.getElementById('ed-mg-' + prefix + '-zones');
  if (coordsEl) coordsEl.style.display = mode === 'coords' ? '' : 'none';
  if (zonesEl) zonesEl.style.display = mode === 'zones' ? '' : 'none';
}
function edMgTpAddZone(prefix) {
  var inp = document.getElementById('ed-mg-' + prefix + '-zone-input');
  if (!inp || !inp.value.trim()) return;
  if (!window._ed._mgTpZones) window._ed._mgTpZones = {};
  if (!window._ed._mgTpZones[prefix]) window._ed._mgTpZones[prefix] = [];
  window._ed._mgTpZones[prefix].push(inp.value.trim());
  inp.value = '';
  edMgTpRenderZones(prefix);
}
function edMgTpRemoveZone(prefix, idx) {
  if (!window._ed._mgTpZones || !window._ed._mgTpZones[prefix]) return;
  window._ed._mgTpZones[prefix].splice(idx, 1);
  edMgTpRenderZones(prefix);
}
function edMgTpRenderZones(prefix) {
  var container = document.getElementById('ed-mg-' + prefix + '-zone-badges');
  if (!container) return;
  var zones = (window._ed._mgTpZones && window._ed._mgTpZones[prefix]) || [];
  container.innerHTML = zones.map(function(z, i) {
    return '<span class="id-badge" style="display:inline-flex;align-items:center;gap:4px;margin:2px">' + escHtml(z) +
      ' <button class="btn-cg-sm" style="padding:0 4px;line-height:1;font-size:10px" onclick="edMgTpRemoveZone(\'' + escHtml(prefix) + '\',' + i + ')">\u00d7</button></span>';
  }).join('');
}
async function edMgTpFillPos(prefix) {
  try {
    var pos = await API.admin.getPosition();
    if (!pos || !pos.online) { window._ed.app.toast(t('common.offline'), 'warn'); return; }
    var xEl = document.getElementById('ed-mg-' + prefix + 'X');
    var yEl = document.getElementById('ed-mg-' + prefix + 'Y');
    var zEl = document.getElementById('ed-mg-' + prefix + 'Z');
    var wEl = document.getElementById('ed-mg-' + prefix + 'World');
    if (xEl) xEl.value = Math.floor(pos.x);
    if (yEl) yEl.value = Math.floor(pos.y);
    if (zEl) zEl.value = Math.floor(pos.z);
    if (wEl && pos.world) wEl.value = pos.world;
    window._ed.app.toast(t('common.success'), 'success');
  } catch (e) { window._ed.app.toast(e.message, 'error'); }
}

// ── Mini-game config helpers: command list ────────────────────────────────────
function edMgAddCmd(fieldKey) {
  if (!window._ed._mgCmdLists) window._ed._mgCmdLists = {};
  if (!window._ed._mgCmdLists[fieldKey]) window._ed._mgCmdLists[fieldKey] = [];
  window._ed._mgCmdLists[fieldKey].push('');
  edMgRenderCmdList(fieldKey);
}
function edMgRemoveCmd(fieldKey, idx) {
  if (!window._ed._mgCmdLists || !window._ed._mgCmdLists[fieldKey]) return;
  window._ed._mgCmdLists[fieldKey].splice(idx, 1);
  edMgRenderCmdList(fieldKey);
}
function edMgUpdateCmd(fieldKey, idx, value) {
  if (!window._ed._mgCmdLists || !window._ed._mgCmdLists[fieldKey]) return;
  window._ed._mgCmdLists[fieldKey][idx] = value;
}
function edMgRenderCmdList(fieldKey) {
  var container = document.getElementById('ed-mg-' + fieldKey + '-rows');
  if (!container) return;
  var cmds = (window._ed._mgCmdLists && window._ed._mgCmdLists[fieldKey]) || [];
  container.innerHTML = cmds.map(function(c, i) {
    return '<div class="flex gap-1 items-center mb-1">' +
      '<span class="text-xs mono" style="color:var(--amber);width:20px">' + (i + 1) + '.</span>' +
      '<input type="text" class="input input-sm input-bordered flex-1" value="' + escHtml(c) + '" ' +
        'onchange="edMgUpdateCmd(\'' + escHtml(fieldKey) + '\',' + i + ',this.value)">' +
      '<button class="btn-cg-sm" style="padding:0 6px" onclick="edMgRemoveCmd(\'' + escHtml(fieldKey) + '\',' + i + ')">\u00d7</button>' +
    '</div>';
  }).join('');
}

// ── Mini-game config helpers: power-up list ──────────────────────────────────
var _mgPowerUpEffects = [
  {v:'speed',l:t('events.pu.speed')},{v:'jump',l:t('events.pu.jump')},{v:'slow',l:t('events.pu.slow')},
  {v:'levitation',l:t('events.pu.levitation')},{v:'resistance',l:t('events.pu.resistance')},
  {v:'invisibility',l:t('events.pu.invisibility')},{v:'blindness',l:t('events.pu.blindness')},{v:'glowing',l:t('events.pu.glowing')}
];
function edMgAddPowerUp() {
  if (!window._ed._mgPowerUps) window._ed._mgPowerUps = [];
  window._ed._mgPowerUps.push({zoneId:'',effectType:'speed',amplifier:0,durationTicks:100,cooldownSeconds:10,sound:'block.beacon.activate',particleType:'HAPPY_VILLAGER'});
  edMgRenderPowerUps();
}
function edMgRemovePowerUp(idx) {
  if (!window._ed._mgPowerUps) return;
  window._ed._mgPowerUps.splice(idx, 1);
  edMgRenderPowerUps();
}
function edMgUpdatePowerUp(idx, field, value) {
  if (!window._ed._mgPowerUps || !window._ed._mgPowerUps[idx]) return;
  window._ed._mgPowerUps[idx][field] = field === 'amplifier' || field === 'durationTicks' || field === 'cooldownSeconds' ? Number(value) : value;
}
function edMgRenderPowerUps() {
  var container = document.getElementById('ed-mg-powerUpZones-rows');
  if (!container) return;
  var pus = window._ed._mgPowerUps || [];
  container.innerHTML = pus.map(function(pu, i) {
    var effOpts = _mgPowerUpEffects.map(function(e) {
      return '<option value="' + e.v + '"' + (pu.effectType === e.v ? ' selected' : '') + '>' + e.l + '</option>';
    }).join('');
    return '<div class="p-2 rounded mb-2" style="border:1px solid var(--bg-border);background:var(--bg-elevated)">' +
      '<div class="flex gap-2 items-end flex-wrap">' +
        '<div class="flex-1" style="min-width:120px">' +
          '<label class="text-xs" style="color:var(--muted)">' + t('common.zone') + '</label>' +
          '<input type="text" class="input input-sm input-bordered w-full" list="global-zones-dl" value="' + escHtml(pu.zoneId) + '" ' +
            'onchange="edMgUpdatePowerUp(' + i + ',\'zoneId\',this.value)">' +
        '</div>' +
        '<div style="width:110px">' +
          '<label class="text-xs" style="color:var(--muted)">' + t('common.effect') + '</label>' +
          '<select class="select select-sm select-bordered w-full" onchange="edMgUpdatePowerUp(' + i + ',\'effectType\',this.value)">' + effOpts + '</select>' +
        '</div>' +
        '<div style="width:60px">' +
          '<label class="text-xs" style="color:var(--muted)">' + t('events.pu.level') + '</label>' +
          '<input type="number" class="input input-sm input-bordered w-full" min="0" max="4" value="' + pu.amplifier + '" ' +
            'onchange="edMgUpdatePowerUp(' + i + ',\'amplifier\',this.value)">' +
        '</div>' +
        '<div style="width:70px">' +
          '<label class="text-xs" style="color:var(--muted)">' + t('common.duration') + '</label>' +
          '<input type="number" class="input input-sm input-bordered w-full" min="1" max="600" value="' + Math.round(pu.durationTicks / 20) + '" ' +
            'onchange="edMgUpdatePowerUp(' + i + ',\'durationTicks\',this.value*20)">' +
        '</div>' +
        '<div style="width:70px">' +
          '<label class="text-xs" style="color:var(--muted)">' + t('events.pu.cooldown') + '</label>' +
          '<input type="number" class="input input-sm input-bordered w-full" min="0" max="300" value="' + pu.cooldownSeconds + '" ' +
            'onchange="edMgUpdatePowerUp(' + i + ',\'cooldownSeconds\',this.value)">' +
        '</div>' +
        '<button class="btn-cg-sm" style="padding:2px 8px" onclick="edMgRemovePowerUp(' + i + ')">\u00d7</button>' +
      '</div>' +
    '</div>';
  }).join('');
}

// ── Mini-game config helpers: species list (Prop Hunt) ──────────────────────

const _phIconCache = {};

function edMgAddSpecies(fieldKey) {
    if (!window._ed || !window._ed._mgSpeciesList) return;
    const list = window._ed._mgSpeciesList[fieldKey] || [];
    list.push({ species: '', itemId: '', hasCustomIcon: false });
    window._ed._mgSpeciesList[fieldKey] = list;
    edMgRenderSpeciesList(fieldKey);
}

function edMgRemoveSpecies(fieldKey, index) {
    if (!window._ed || !window._ed._mgSpeciesList) return;
    const list = window._ed._mgSpeciesList[fieldKey];
    if (!list) return;
    const entry = list[index];
    // Delete icon from server if custom
    if (entry && entry.hasCustomIcon) {
        const key = _phSanitizeKey(entry.species);
        if (key) {
            API.events.prophuntDeleteIcon(key).catch(() => {});
            _phInvalidateIconCache(key);
        }
    }
    list.splice(index, 1);
    edMgRenderSpeciesList(fieldKey);
}

function edMgUpdateSpeciesField(fieldKey, index, field, value) {
    if (!window._ed || !window._ed._mgSpeciesList) return;
    const list = window._ed._mgSpeciesList[fieldKey];
    if (!list || !list[index]) return;
    list[index][field] = value;
    // If species name changed, re-render to update icon preview
    if (field === 'species') {
        edMgRenderSpeciesList(fieldKey);
    }
}

async function edMgUploadSpeciesIcon(fieldKey, index, input) {
    if (!window._ed || !window._ed._mgSpeciesList) return;
    const list = window._ed._mgSpeciesList[fieldKey];
    if (!list || !list[index]) return;
    const entry = list[index];
    const file = input.files && input.files[0];
    if (!file) return;

    const species = entry.species.trim();
    if (!species) {
        window._ed.app.toast(t('mg.ph.speciesNameRequired'), 'error');
        return;
    }

    const key = _phSanitizeKey(species);
    if (!key) return;

    // Read file as base64
    const b64 = await new Promise((res, rej) => {
        const r = new FileReader();
        r.onload = () => res(r.result);
        r.onerror = rej;
        r.readAsDataURL(file);
    });

    try {
        await API.events.prophuntUploadIcon(key, b64);
        entry.hasCustomIcon = true;
        _phInvalidateIconCache(key);
        // Pre-cache from file
        _phIconCache[key] = URL.createObjectURL(file);
        window._ed.app.toast(t('common.success'), 'success');
        edMgRenderSpeciesList(fieldKey);
    } catch (e) {
        window._ed.app.toast(e.message, 'error');
    }
}

async function edMgDeleteSpeciesIcon(fieldKey, index) {
    if (!window._ed || !window._ed._mgSpeciesList) return;
    const list = window._ed._mgSpeciesList[fieldKey];
    if (!list || !list[index]) return;
    const entry = list[index];
    const key = _phSanitizeKey(entry.species);
    if (!key) return;

    try {
        await API.events.prophuntDeleteIcon(key);
        entry.hasCustomIcon = false;
        _phInvalidateIconCache(key);
        window._ed.app.toast(t('common.success'), 'success');
        edMgRenderSpeciesList(fieldKey);
    } catch (e) {
        window._ed.app.toast(e.message, 'error');
    }
}

function _phSanitizeKey(species) {
    return species.toLowerCase().replace(/[^a-z0-9]/g, '_').replace(/_+/g, '_').replace(/^_|_$/g, '');
}

function _phInvalidateIconCache(key) {
    if (_phIconCache[key]) {
        URL.revokeObjectURL(_phIconCache[key]);
        delete _phIconCache[key];
    }
}

function edMgRenderSpeciesList(fieldKey) {
    const container = document.getElementById('ed-mg-' + fieldKey + '-list');
    if (!container) return;
    const list = (window._ed && window._ed._mgSpeciesList && window._ed._mgSpeciesList[fieldKey]) || [];

    let html = '';
    list.forEach(function(entry, i) {
        const key = entry.species ? _phSanitizeKey(entry.species) : '';
        const fk = escHtml(fieldKey);

        // Icon preview
        let iconHtml;
        if (entry.hasCustomIcon && key) {
            iconHtml = '<img data-ph-icon="' + escHtml(key) + '" style="width:32px;height:32px;border-radius:4px;object-fit:contain;background:var(--bg)" alt="">';
        } else if (entry.itemId) {
            iconHtml = '<img src="' + escHtml(mkItemIconUrl(entry.itemId)) + '" style="width:32px;height:32px;border-radius:4px;object-fit:contain;background:var(--bg)" alt="" onerror="this.src=\'' + escHtml(mkItemIconUrl('minecraft:paper')) + '\'">';
        } else {
            iconHtml = '<img src="' + escHtml(mkItemIconUrl('minecraft:paper')) + '" style="width:32px;height:32px;border-radius:4px;object-fit:contain;background:var(--bg)" alt="">';
        }

        // Icon actions (upload or delete)
        let iconActionsHtml;
        if (entry.hasCustomIcon) {
            iconActionsHtml = '<button class="btn-cg btn-cg-danger btn-cg-sm" style="padding:2px 6px;font-size:10px" ' +
                'onclick="edMgDeleteSpeciesIcon(\'' + fk + '\',' + i + ')" title="' + escHtml(t('mg.ph.deleteIcon')) + '">\u2715</button>';
        } else {
            iconActionsHtml = '<label class="btn-cg btn-cg-sm" style="padding:2px 6px;font-size:10px;cursor:pointer" title="' + escHtml(t('mg.ph.uploadIcon')) + '">\uD83D\uDCC1' +
                '<input type="file" accept="image/png" style="display:none" ' +
                'onchange="edMgUploadSpeciesIcon(\'' + fk + '\',' + i + ',this)"></label>';
        }

        html += '<div class="flex items-center gap-2" style="background:var(--bg-elevated);padding:6px 8px;border-radius:6px;border:1px solid var(--bg-border)">' +
            '<span class="text-xs mono" style="color:var(--amber);min-width:20px">' + (i + 1) + '.</span>' +
            iconHtml +
            '<input type="text" class="input input-sm input-bordered flex-1" value="' + escHtml(entry.species) + '" ' +
                'placeholder="' + escHtml(t('mg.ph.speciesPlaceholder')) + '" ' +
                'onchange="edMgUpdateSpeciesField(\'' + fk + '\',' + i + ',\'species\',this.value)">' +
            '<input type="text" class="input input-sm input-bordered" style="width:140px" value="' + escHtml(entry.itemId || '') + '" ' +
                'placeholder="' + escHtml(t('mg.ph.itemIdPlaceholder')) + '" ' +
                'onchange="edMgUpdateSpeciesField(\'' + fk + '\',' + i + ',\'itemId\',this.value)">' +
            iconActionsHtml +
            '<button class="btn-cg btn-cg-danger btn-cg-sm" style="padding:2px 6px" onclick="edMgRemoveSpecies(\'' + fk + '\',' + i + ')">\u2715</button>' +
        '</div>';
    });

    container.innerHTML = html;

    // Load custom icon previews
    setTimeout(function() { _phLoadIcons(container); }, 50);
}

async function _phLoadIcons(root) {
    const imgs = (root || document).querySelectorAll('img[data-ph-icon]');
    for (const img of imgs) {
        const key = img.dataset.phIcon;
        if (!key) continue;
        if (_phIconCache[key]) { img.src = _phIconCache[key]; continue; }
        try {
            const url = await API.events.prophuntGetIcon(key);
            _phIconCache[key] = url;
            img.src = url;
        } catch (e) {
            // Leave blank or default
        }
    }
}

// ── Mini-game config helpers: power config table ──────────────────────────────
function edMgRenderPowerConfig(fieldKey) {
    var container = document.getElementById('ed-mg-' + fieldKey + '-table');
    if (!container) return;
    var list = (window._ed && window._ed._mgPowerConfigs && window._ed._mgPowerConfigs[fieldKey]) || [];

    var html = '<table class="cg-table"><thead><tr>' +
        '<th>' + t('mg.ph.powerName') + '</th>' +
        '<th style="width:60px">' + t('mg.ph.powerEnabled') + '</th>' +
        '<th style="width:100px">' + t('mg.ph.powerCooldown') + '</th>' +
    '</tr></thead><tbody>';

    list.forEach(function(p, i) {
        var fk = escHtml(fieldKey);
        html += '<tr>' +
            '<td class="text-sm">' + p.icon + ' ' + escHtml(t('cheeseguard.prophunt.power.' + p.id.toLowerCase())) + '</td>' +
            '<td><input type="checkbox" class="toggle toggle-sm" ' + (p.enabled ? 'checked' : '') +
                ' onchange="edMgUpdatePowerConfig(\'' + fk + '\',' + i + ',\'enabled\',this.checked)"></td>' +
            '<td><input type="number" class="input input-sm input-bordered w-full" value="' + p.cooldown + '" min="1" max="300"' +
                ' onchange="edMgUpdatePowerConfig(\'' + fk + '\',' + i + ',\'cooldown\',parseInt(this.value)||10)"></td>' +
        '</tr>';
    });

    html += '</tbody></table>';
    container.innerHTML = html;
}

function edMgUpdatePowerConfig(fieldKey, index, field, value) {
    if (!window._ed || !window._ed._mgPowerConfigs) return;
    var list = window._ed._mgPowerConfigs[fieldKey];
    if (!list || !list[index]) return;
    list[index][field] = value;
}

// ── Race Track Editor ──────────────────────────────────────────────────────────

const RACE_WOOL_BLOCKS = [
  {id:'minecraft:blue_wool',color:'#3b82f6'},{id:'minecraft:red_wool',color:'#ef4444'},
  {id:'minecraft:green_wool',color:'#22c55e'},{id:'minecraft:pink_wool',color:'#f472b6'},
  {id:'minecraft:yellow_wool',color:'#eab308'},{id:'minecraft:cyan_wool',color:'#22d3ee'},
  {id:'minecraft:purple_wool',color:'#a855f7'},{id:'minecraft:orange_wool',color:'#f97316'},
  {id:'minecraft:white_wool',color:'#f8fafc'},{id:'minecraft:black_wool',color:'#1e293b'},
  {id:'minecraft:lime_wool',color:'#84cc16'},{id:'minecraft:light_blue_wool',color:'#38bdf8'},
  {id:'minecraft:magenta_wool',color:'#d946ef'},{id:'minecraft:brown_wool',color:'#92400e'},
  {id:'minecraft:gray_wool',color:'#6b7280'},{id:'minecraft:light_gray_wool',color:'#9ca3af'},
];

const RACE_POTION_EFFECTS = [
  'minecraft:speed','minecraft:slowness','minecraft:haste','minecraft:strength',
  'minecraft:jump_boost','minecraft:regeneration','minecraft:resistance',
  'minecraft:fire_resistance','minecraft:invisibility','minecraft:blindness',
  'minecraft:night_vision','minecraft:weakness','minecraft:poison',
  'minecraft:wither','minecraft:absorption','minecraft:saturation',
  'minecraft:glowing','minecraft:levitation','minecraft:slow_falling',
  'minecraft:darkness','minecraft:wind_charged',
];

const RACE_PARTICLES = [
  'HAPPY_VILLAGER','FLAME','HEART','END_ROD','SOUL_FIRE_FLAME',
  'FIREWORK','COMPOSTER','GLOW','ELECTRIC_SPARK','DUST','ENCHANT',
  'BUBBLE','CLOUD','CRIT','DAMAGE_INDICATOR','NOTE',
];

const RACE_MOVEMENT_MODES = [
  {v:'any',l:'race.movement.any'},{v:'foot',l:'race.movement.foot'},
  {v:'shift',l:'race.movement.shift'},{v:'kart',l:'race.movement.kart'},
];

function edRaceBuildPanel(evt) {
  const woolDl = '<datalist id="race-wool-dl">' +
    RACE_WOOL_BLOCKS.map(b => '<option value="' + b.id + '">').join('') + '</datalist>';
  const effectDl = '<datalist id="race-effect-dl">' +
    RACE_POTION_EFFECTS.map(e => '<option value="' + e + '">').join('') + '</datalist>';
  const particleDl = '<datalist id="race-particle-dl">' +
    RACE_PARTICLES.map(p => '<option value="' + p + '">').join('') + '</datalist>';
  const movOpts = RACE_MOVEMENT_MODES.map(m =>
    '<option value="' + m.v + '">' + t(m.l) + '</option>'
  ).join('');

  return woolDl + effectDl + particleDl +
    '<div class="space-y-4 p-1">' +

    // Section 1: Zone & Block Mappings
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('race.zone') + '</div>' +
    '<div class="mb-3">' +
      '<label class="field-label">' + t('race.zoneId') + '</label>' +
      '<input type="text" id="race-zone-id" class="input input-sm input-bordered w-full" list="global-zones-dl" placeholder="race_arena">' +
    '</div>' +
    '<label class="field-label">' + t('race.blockMappings') + '</label>' +
    '<div id="race-mappings-list"></div>' +
    '<button class="btn-cg btn-cg-sm mt-1" onclick="edRaceAddMapping()">' + t('race.addMapping') + '</button>' +
    '<div class="grid grid-cols-2 gap-3 mt-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.cpReplaceBlock') + '</label>' +
        '<input type="text" id="race-cp-replace" class="input input-sm w-full input-bordered" value="minecraft:air"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.startReplaceBlock') + '</label>' +
        '<input type="text" id="race-start-replace" class="input input-sm w-full input-bordered" value="minecraft:air"></div>' +
    '</div>' +
    '<div class="mt-3">' +
      '<button class="btn-cg btn-cg-primary" id="race-scan-btn" onclick="edRaceScan()">' +
        '<span id="race-scan-label">' + t('race.scan') + '</span>' +
      '</button>' +
    '</div>' +

    // Section 2: 2D Map + Properties panel side by side
    '<div class="section-divider my-4"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('race.map') + '</div>' +
    '<div class="flex gap-3" style="min-height:420px">' +
      // Left: canvas + toolbar
      '<div class="flex-1" style="min-width:0">' +
        '<div style="background:var(--bg-darker);border-radius:8px;border:1px solid var(--bg-border);overflow:hidden;position:relative">' +
          '<canvas id="race-canvas" width="700" height="400" style="width:100%;cursor:crosshair"></canvas>' +
          '<div id="race-coords" class="mono" style="position:absolute;bottom:4px;right:8px;font-size:10px;color:var(--muted-light);pointer-events:none"></div>' +
        '</div>' +
        '<div class="flex items-center gap-2 mt-1">' +
          '<div id="race-map-status" class="text-xs flex-1" style="color:var(--muted)">' + t('race.noScan') + '</div>' +
          '<span id="race-zoom-level" class="text-xs mono" style="color:var(--muted)">100%</span>' +
          '<button class="btn-cg btn-cg-sm btn-cg-ghost" style="padding:2px 6px;font-size:11px" onclick="edRaceZoomFit()" title="' + t('race.fitView') + '">[ ]</button>' +
        '</div>' +
      '</div>' +
      // Right: properties panel (visible when something selected)
      '<div id="race-props-panel" style="width:240px;flex-shrink:0;display:none">' +
        '<div id="race-selected-detail" style="height:100%"></div>' +
      '</div>' +
    '</div>' +

    // Section 3: Race Settings
    '<div class="section-divider my-4"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('race.settings') + '</div>' +
    '<div class="grid grid-cols-2 gap-3">' +
      '<div class="flex items-center gap-2"><label class="cursor-pointer flex items-center gap-2">' +
        '<input type="checkbox" id="race-ordered" class="toggle toggle-sm">' +
        '<span class="text-sm" style="color:var(--text-muted)">' + t('race.orderedCp') + '</span>' +
      '</label></div>' +
      '<div class="flex items-center gap-2"><label class="cursor-pointer flex items-center gap-2">' +
        '<input type="checkbox" id="race-freeze" class="toggle toggle-sm" checked>' +
        '<span class="text-sm" style="color:var(--text-muted)">' + t('race.freeze') + '</span>' +
      '</label></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.laps') + '</label>' +
        '<input type="number" id="race-laps" class="input input-sm w-full input-bordered" value="1" min="1" max="20"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.countdown') + '</label>' +
        '<input type="number" id="race-countdown" class="input input-sm w-full input-bordered" value="5" min="0" max="30"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.endTimer') + '</label>' +
        '<input type="number" id="race-end-timer" class="input input-sm w-full input-bordered" value="30" min="5" max="300"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.margin') + '</label>' +
        '<input type="number" id="race-margin" class="input input-sm w-full input-bordered" value="1.5" min="0" max="10" step="0.5"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.movement') + '</label>' +
        '<select id="race-movement" class="select select-sm w-full select-bordered">' + movOpts + '</select></div>' +
      '<div class="flex items-center gap-2"><label class="cursor-pointer flex items-center gap-2">' +
        '<input type="checkbox" id="race-tp-oob" class="toggle toggle-sm" checked>' +
        '<span class="text-sm" style="color:var(--text-muted)">' + t('race.tpOob') + '</span>' +
      '</label></div>' +
    '</div>' +

    // Section 4: Checkpoint Visuals
    '<div class="section-divider my-4"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('race.cpVisuals') + '</div>' +
    '<div class="grid grid-cols-2 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.nextParticle') + '</label>' +
        '<input type="text" id="race-next-particle" class="input input-sm w-full input-bordered" list="race-particle-dl" value="HAPPY_VILLAGER"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.validatedParticle') + '</label>' +
        '<input type="text" id="race-validated-particle" class="input input-sm w-full input-bordered" list="race-particle-dl" value="FLAME"></div>' +
      '<div class="flex items-center gap-2"><label class="cursor-pointer flex items-center gap-2">' +
        '<input type="checkbox" id="race-hide-validated" class="toggle toggle-sm">' +
        '<span class="text-sm" style="color:var(--text-muted)">' + t('race.hideValidated') + '</span>' +
      '</label></div>' +
    '</div>' +

    // Section 5: Powerup Types
    '<div class="section-divider my-4"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('race.powerupTypes') + '</div>' +
    '<div id="race-pu-types"></div>' +
    '<button class="btn-cg btn-cg-sm mt-2" onclick="edRaceAddPowerupType()">' + t('race.addPowerupType') + '</button>' +

    // Section 6: Scoring
    '<div class="section-divider my-4"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('race.scoring') + '</div>' +
    '<div class="grid grid-cols-3 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.ppCheckpoint') + '</label>' +
        '<input type="number" id="race-pp-cp" class="input input-sm w-full input-bordered" value="10" min="0" max="100"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.finishBonus') + '</label>' +
        '<input type="number" id="race-finish-bonus" class="input input-sm w-full input-bordered" value="100" min="0" max="500"></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('race.positionBonus') + '</label>' +
        '<input type="number" id="race-pos-bonus" class="input input-sm w-full input-bordered" value="50" min="0" max="500"></div>' +
    '</div>' +

    // Save button
    '<div class="flex gap-2 mt-4">' +
      '<button class="btn-cg btn-cg-primary" onclick="edRaceSaveTrack()">' + t('common.save') + '</button>' +
    '</div>' +
  '</div>';
}

// ── Race: Init & Load ────────────────────────────────────────────────────────

function _edRaceSetField(id, value, type) {
  const el = document.getElementById(id);
  if (!el) return;
  if (type === 'bool') el.checked = !!value;
  else el.value = value;
}

function _edRaceTagCheckpoints(config) {
  if (config.checkpoints) {
    config.checkpoints.forEach(function(cp, i) { cp._origIdx = i; });
    if (config.checkpoints.some(function(cp) { return cp.order >= 0; })) {
      config.checkpoints.sort(function(a, b) { return a.order - b.order; });
    }
  }
}

async function edRaceInit(eventId) {
  if (!window._ed) return;
  window._ed._rt = {
    config: null, eventId: eventId,
    selectedType: null, selectedIdx: null,
    mappings: [{block:'minecraft:blue_wool',type:'checkpoint'},{block:'minecraft:red_wool',type:'start'}],
    powerupTypes: {}, scanning: false,
    heightmapImg: null,
    // Zoom & pan state
    zoom: 1.0, panX: 0, panZ: 0,
    _dragging: false, _dragStartX: 0, _dragStartY: 0, _dragPanX: 0, _dragPanZ: 0,
  };

  // Attach canvas event listeners (after DOM is ready)
  requestAnimationFrame(() => _edRaceBindCanvas());

  try {
    const config = await API.races.getTrack(eventId);
    _edRaceTagCheckpoints(config);
    window._ed._rt.config = config;

    if (config.zoneId) {
      _edRaceSetField('race-zone-id', config.zoneId);
      edRaceLoadHeightmap(config.zoneId);
    }

    if (config.blockMappings && Object.keys(config.blockMappings).length > 0) {
      window._ed._rt.mappings = Object.entries(config.blockMappings).map(([block, type]) => ({block, type}));
    }

    _edRaceSetField('race-ordered', config.orderedCheckpoints, 'bool');
    _edRaceSetField('race-freeze', config.freezeDuringCountdown !== false, 'bool');
    _edRaceSetField('race-laps', config.laps || 1);
    _edRaceSetField('race-countdown', config.countdownSeconds != null ? config.countdownSeconds : 5);
    _edRaceSetField('race-end-timer', config.endTimerSeconds || 30);
    _edRaceSetField('race-margin', config.checkpointMargin != null ? config.checkpointMargin : 1.5);
    _edRaceSetField('race-movement', config.movementMode || 'any');
    _edRaceSetField('race-tp-oob', config.teleportOnOutOfBounds !== false, 'bool');
    _edRaceSetField('race-cp-replace', config.checkpointReplacementBlock || 'minecraft:air');
    _edRaceSetField('race-start-replace', config.startLineReplacementBlock || 'minecraft:air');
    _edRaceSetField('race-next-particle', config.nextCheckpointParticle || 'HAPPY_VILLAGER');
    _edRaceSetField('race-validated-particle', config.validatedCheckpointParticle || 'FLAME');
    _edRaceSetField('race-hide-validated', !!config.hideValidatedCheckpoints, 'bool');
    _edRaceSetField('race-pp-cp', config.pointsPerCheckpoint || 10);
    _edRaceSetField('race-finish-bonus', config.finishBonusPoints || 100);
    _edRaceSetField('race-pos-bonus', config.positionBonusPoints || 50);

    window._ed._rt.powerupTypes = config.powerupTypes ? JSON.parse(JSON.stringify(config.powerupTypes)) : {};

    // Update status bar
    const status = document.getElementById('race-map-status');
    if (status && config.checkpoints) {
      const cpCount = (config.checkpoints || []).length;
      const puCount = (config.powerups || []).length;
      const hasStart = !!config.startLine;
      status.textContent = cpCount + ' ' + t('race.marker.checkpoint') + 's, ' + puCount + ' ' + t('race.marker.powerup') + 's, ' + t('race.marker.start') + ' ' + (hasStart ? '\u2713' : '\u2717');
    }
  } catch (e) {
    if (window._ed && window._ed.app) window._ed.app.toast(e.message, 'error');
  }

  edRaceRenderMappings();
  edRaceRenderPowerupTypes();
  edRaceRenderMap();
}

// ── Race: Heightmap (top-down block map) ─────────────────────────────────────

async function edRaceLoadHeightmap(zoneId) {
  if (!window._ed || !window._ed._rt) return;
  const rt = window._ed._rt;
  if (!zoneId) return;

  try {
    const data = await API.races.heightmap({ zoneId });
    if (data && data.image) {
      const img = new Image();
      img.onload = function() {
        rt.heightmapImg = img;
        edRaceRenderMap();
      };
      img.src = data.image;
    }
  } catch (e) {
    // Heightmap is optional — silent fail
    console.warn('Heightmap load failed:', e.message);
  }
}

// ── Race: Scan ───────────────────────────────────────────────────────────────

async function edRaceScan() {
  if (!window._ed || !window._ed._rt) return;
  const rt = window._ed._rt;
  const zoneEl = document.getElementById('race-zone-id');
  const zoneId = zoneEl ? zoneEl.value.trim() : '';
  if (!zoneId) { window._ed.app.toast(t('race.noZone'), 'error'); return; }

  const blockMappings = {};
  rt.mappings.forEach(m => { if (m.block && m.type) blockMappings[m.block] = m.type; });
  if (Object.keys(blockMappings).length === 0) {
    window._ed.app.toast(t('race.noMappings'), 'error'); return;
  }

  const btn = document.getElementById('race-scan-btn');
  const label = document.getElementById('race-scan-label');
  if (btn) btn.disabled = true;
  if (label) label.textContent = t('race.scanning');

  try {
    const result = await API.races.scan({ zoneId, blockMappings, eventId: rt.eventId });
    const config = await API.races.getTrack(rt.eventId);
    _edRaceTagCheckpoints(config);
    rt.config = config;
    edRaceRenderMap();
    edRaceLoadHeightmap(zoneId);

    const status = document.getElementById('race-map-status');
    if (status) {
      const cpCount = (config.checkpoints || []).length;
      const puCount = (config.powerups || []).length;
      const hasStart = !!config.startLine;
      status.textContent = t('race.scanDone', {
        checkpoints: cpCount, powerups: puCount,
        start: hasStart ? '\u2713' : '\u2717',
        duration: result.durationMs || 0,
        blocks: result.totalBlocksScanned || 0,
      });
    }
    window._ed.app.toast(t('race.scanResult'), 'success');
  } catch (e) {
    window._ed.app.toast(e.message, 'error');
  } finally {
    if (btn) btn.disabled = false;
    if (label) label.textContent = t('race.scan');
  }
}

// ── Race: Save Track ─────────────────────────────────────────────────────────

async function edRaceSaveTrack() {
  if (!window._ed || !window._ed._rt) return;
  const rt = window._ed._rt;

  const data = {
    zoneId: (document.getElementById('race-zone-id') || {}).value || '',
    orderedCheckpoints: !!(document.getElementById('race-ordered') || {}).checked,
    laps: Number((document.getElementById('race-laps') || {}).value) || 1,
    countdownSeconds: Number((document.getElementById('race-countdown') || {}).value) || 5,
    freezeDuringCountdown: (document.getElementById('race-freeze') || {}).checked !== false,
    endTimerSeconds: Number((document.getElementById('race-end-timer') || {}).value) || 30,
    checkpointMargin: Number((document.getElementById('race-margin') || {}).value) || 1.5,
    movementMode: (document.getElementById('race-movement') || {}).value || 'any',
    teleportOnOutOfBounds: (document.getElementById('race-tp-oob') || {}).checked !== false,
    checkpointReplacementBlock: (document.getElementById('race-cp-replace') || {}).value || 'minecraft:air',
    startLineReplacementBlock: (document.getElementById('race-start-replace') || {}).value || 'minecraft:air',
    nextCheckpointParticle: (document.getElementById('race-next-particle') || {}).value || 'HAPPY_VILLAGER',
    validatedCheckpointParticle: (document.getElementById('race-validated-particle') || {}).value || 'FLAME',
    hideValidatedCheckpoints: !!(document.getElementById('race-hide-validated') || {}).checked,
    pointsPerCheckpoint: Number((document.getElementById('race-pp-cp') || {}).value) || 10,
    finishBonusPoints: Number((document.getElementById('race-finish-bonus') || {}).value) || 100,
    positionBonusPoints: Number((document.getElementById('race-pos-bonus') || {}).value) || 50,
    powerupTypes: rt.powerupTypes || {},
    blockMappings: {},
  };
  rt.mappings.forEach(m => { if (m.block && m.type) data.blockMappings[m.block] = m.type; });

  // Send checkpoint order: map backend positions to new order values
  if (rt.config && rt.config.checkpoints && rt.config.checkpoints.length > 0) {
    const orders = new Array(rt.config.checkpoints.length).fill(0);
    rt.config.checkpoints.forEach(function(cp, newOrder) {
      if (cp._origIdx != null) orders[cp._origIdx] = newOrder;
    });
    data.checkpointOrders = orders;
  }

  try {
    const config = await API.races.putTrack(rt.eventId, data);
    _edRaceTagCheckpoints(config);
    rt.config = config;
    window._ed.app.toast(t('common.success'), 'success');
  } catch (e) {
    window._ed.app.toast(e.message, 'error');
  }
}

// ── Race: Canvas Events (zoom, pan, click, hover) ───────────────────────────

function _edRaceBindCanvas() {
  const canvas = document.getElementById('race-canvas');
  if (!canvas || canvas._rcBound) return;
  canvas._rcBound = true;

  // Click
  canvas.addEventListener('click', edRaceCanvasClick);

  // Ctrl+Wheel → zoom
  canvas.addEventListener('wheel', function(e) {
    const rt = window._ed && window._ed._rt;
    if (!rt) return;
    if (!e.ctrlKey) return;
    e.preventDefault();
    const delta = e.deltaY > 0 ? -0.15 : 0.15;
    const oldZoom = rt.zoom;
    rt.zoom = Math.min(10, Math.max(0.2, rt.zoom + delta * rt.zoom));
    // Zoom toward cursor position
    const rect = canvas.getBoundingClientRect();
    const cx = (e.clientX - rect.left) / rect.width;
    const cy = (e.clientY - rect.top) / rect.height;
    const factor = rt.zoom / oldZoom;
    rt.panX = cx - factor * (cx - rt.panX);
    rt.panZ = cy - factor * (cy - rt.panZ);
    _edRaceUpdateZoomLabel();
    edRaceRenderMap();
  }, {passive: false});

  // Middle-click or right-click drag → pan
  canvas.addEventListener('mousedown', function(e) {
    const rt = window._ed && window._ed._rt;
    if (!rt) return;
    if (e.button === 1 || e.button === 2) {
      e.preventDefault();
      rt._dragging = true;
      rt._dragStartX = e.clientX;
      rt._dragStartY = e.clientY;
      rt._dragPanX = rt.panX;
      rt._dragPanZ = rt.panZ;
      canvas.style.cursor = 'grabbing';
    }
  });
  canvas.addEventListener('contextmenu', function(e) { e.preventDefault(); });

  window.addEventListener('mousemove', function(e) {
    const rt = window._ed && window._ed._rt;
    if (!rt) return;
    if (rt._dragging) {
      const rect = canvas.getBoundingClientRect();
      rt.panX = rt._dragPanX + (e.clientX - rt._dragStartX) / rect.width;
      rt.panZ = rt._dragPanZ + (e.clientY - rt._dragStartY) / rect.height;
      edRaceRenderMap();
    }
    // Coordinate readout on hover
    _edRaceUpdateCoords(e);
  });

  window.addEventListener('mouseup', function(e) {
    const rt = window._ed && window._ed._rt;
    if (!rt || !rt._dragging) return;
    if (e.button === 1 || e.button === 2) {
      rt._dragging = false;
      canvas.style.cursor = 'crosshair';
    }
  });

  // Keyboard: Escape to deselect
  canvas.setAttribute('tabindex', '0');
  canvas.addEventListener('keydown', function(e) {
    const rt = window._ed && window._ed._rt;
    if (!rt) return;
    if (e.key === 'Escape' && rt.selectedType) {
      rt.selectedType = null; rt.selectedIdx = null;
      edRaceRenderMap(); edRaceRenderSelected();
    }
  });
}

function _edRaceUpdateZoomLabel() {
  const rt = window._ed && window._ed._rt;
  const el = document.getElementById('race-zoom-level');
  if (el && rt) el.textContent = Math.round(rt.zoom * 100) + '%';
}

function edRaceZoomFit() {
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  rt.zoom = 1.0;
  rt.panX = 0;
  rt.panZ = 0;
  _edRaceUpdateZoomLabel();
  edRaceRenderMap();
}

function _edRaceUpdateCoords(e) {
  const canvas = document.getElementById('race-canvas');
  const el = document.getElementById('race-coords');
  if (!canvas || !el) return;
  const rt = window._ed && window._ed._rt;
  const config = rt && rt.config;
  if (!config || !config.zoneBounds || !config.zoneBounds.min) { el.textContent = ''; return; }

  const rect = canvas.getBoundingClientRect();
  if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) {
    el.textContent = ''; return;
  }

  const mx = (e.clientX - rect.left) * (canvas.width / rect.width);
  const my = (e.clientY - rect.top) * (canvas.height / rect.height);
  const transform = _edRaceGetTransform(canvas, config, rt);
  if (!transform) return;
  // Canvas coords → world coords (inverse of w2c)
  const wx = (mx - transform.offX) / transform.scale + transform.bounds.minX;
  const wz = (my - transform.offZ) / transform.scale + transform.bounds.minZ;
  el.textContent = 'X:' + Math.round(wx) + ' Z:' + Math.round(wz);
}

/** Compute transform params used by both render and click handlers */
function _edRaceGetTransform(canvas, config, rt) {
  if (!config.zoneBounds || !config.zoneBounds.min || !config.zoneBounds.max) return null;
  const bounds = {
    minX: config.zoneBounds.min[0], minZ: config.zoneBounds.min[2],
    maxX: config.zoneBounds.max[0], maxZ: config.zoneBounds.max[2],
  };
  const zoom = rt.zoom || 1;
  const padding = 30;
  const drawW = canvas.width - 2 * padding;
  const drawH = canvas.height - 2 * padding;
  const zoneW = bounds.maxX - bounds.minX + 1;
  const zoneH = bounds.maxZ - bounds.minZ + 1;
  const baseScale = Math.min(drawW / zoneW, drawH / zoneH);
  const scale = baseScale * zoom;
  const centerX = padding + drawW / 2 + (rt.panX || 0) * drawW;
  const centerZ = padding + drawH / 2 + (rt.panZ || 0) * drawH;
  const offX = centerX - zoneW * scale / 2;
  const offZ = centerZ - zoneH * scale / 2;
  return { scale, offX, offZ, bounds, zoneW, zoneH };
}

// ── Race: Canvas 2D Map ──────────────────────────────────────────────────────

function edRaceRenderMap() {
  const canvas = document.getElementById('race-canvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  const rt = window._ed && window._ed._rt;
  const config = rt && rt.config;

  ctx.fillStyle = '#0a0c12';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  if (!config || !config.zoneBounds || !config.zoneBounds.min || !config.zoneBounds.max) {
    ctx.fillStyle = '#6b7280';
    ctx.font = '14px Geist, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(t('race.noScan'), canvas.width / 2, canvas.height / 2);
    return;
  }

  const tf = _edRaceGetTransform(canvas, config, rt);
  if (!tf) return;
  const { scale, offX, offZ, bounds, zoneW, zoneH } = tf;

  function w2c(wx, wz) {
    return { x: offX + (wx - bounds.minX) * scale, y: offZ + (wz - bounds.minZ) * scale };
  }

  // Heightmap background (top-down block view)
  if (rt && rt.heightmapImg) {
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(rt.heightmapImg, offX, offZ, zoneW * scale, zoneH * scale);
    ctx.imageSmoothingEnabled = true;
  }

  // Zone boundary (dashed gray)
  ctx.strokeStyle = '#374151';
  ctx.lineWidth = 1;
  ctx.setLineDash([6, 4]);
  ctx.strokeRect(offX, offZ, zoneW * scale, zoneH * scale);
  ctx.setLineDash([]);

  // Start line (green rect)
  if (config.startLine) {
    const sl = config.startLine;
    const p1 = w2c(sl.min[0], sl.min[2]);
    const p2 = w2c(sl.max[0] + 1, sl.max[2] + 1);
    ctx.fillStyle = 'rgba(34, 197, 94, 0.3)';
    ctx.fillRect(p1.x, p1.y, p2.x - p1.x, p2.y - p1.y);
    ctx.strokeStyle = '#22c55e';
    ctx.lineWidth = 2;
    ctx.strokeRect(p1.x, p1.y, p2.x - p1.x, p2.y - p1.y);
    ctx.fillStyle = '#22c55e';
    ctx.font = 'bold 10px Geist, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(t('race.marker.start').toUpperCase(), (p1.x + p2.x) / 2, (p1.y + p2.y) / 2 + 4);
    if (rt.selectedType === 'start') {
      ctx.strokeStyle = '#f5a623'; ctx.lineWidth = 2;
      ctx.strokeRect(p1.x - 2, p1.y - 2, p2.x - p1.x + 4, p2.y - p1.y + 4);
    }
  }

  // Powerups (diamonds)
  const powerups = config.powerups || [];
  const puColorMap = {};
  const puColors = ['#22d3ee','#a855f7','#f97316','#eab308','#f472b6','#84cc16'];
  const puTypeSet = new Set();
  powerups.forEach(pu => puTypeSet.add(pu.markerType));
  const puTypeArr = Array.from(puTypeSet);
  puTypeArr.forEach((typ, idx) => { puColorMap[typ] = puColors[idx % puColors.length]; });

  powerups.forEach((pu, i) => {
    const c = w2c(pu.center[0], pu.center[2]);
    const sz = Math.max(6, scale * 0.8);
    const col = puColorMap[pu.markerType] || '#22d3ee';
    ctx.fillStyle = col;
    ctx.beginPath();
    ctx.moveTo(c.x, c.y - sz); ctx.lineTo(c.x + sz, c.y);
    ctx.lineTo(c.x, c.y + sz); ctx.lineTo(c.x - sz, c.y);
    ctx.closePath(); ctx.fill();
    if (rt.selectedType === 'powerup' && rt.selectedIdx === i) {
      ctx.strokeStyle = '#f5a623'; ctx.lineWidth = 2; ctx.stroke();
    }
  });

  // Checkpoints (circles with numbers)
  const checkpoints = config.checkpoints || [];
  checkpoints.forEach((cp, i) => {
    const c = w2c(cp.center[0], cp.center[2]);
    const r = Math.max(8, scale * 1.2);
    ctx.fillStyle = '#3b82f6';
    ctx.beginPath(); ctx.arc(c.x, c.y, r, 0, Math.PI * 2); ctx.fill();
    if (rt.selectedType === 'checkpoint' && rt.selectedIdx === i) {
      ctx.strokeStyle = '#f5a623'; ctx.lineWidth = 3; ctx.stroke();
    } else {
      ctx.strokeStyle = '#1e40af'; ctx.lineWidth = 1; ctx.stroke();
    }
    ctx.fillStyle = '#fff';
    ctx.font = 'bold ' + Math.max(10, r * 0.9) + 'px Geist, sans-serif';
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(String(i + 1), c.x, c.y);
  });

  ctx.textBaseline = 'alphabetic';

  // Legend
  ctx.font = '10px Geist, sans-serif';
  ctx.textAlign = 'left';
  const ly = canvas.height - 10;
  ctx.fillStyle = '#3b82f6'; ctx.fillRect(8, ly - 6, 8, 8);
  ctx.fillStyle = '#9ca3af'; ctx.fillText(t('race.marker.checkpoint'), 20, ly);
  ctx.fillStyle = '#22c55e'; ctx.fillRect(100, ly - 6, 8, 8);
  ctx.fillStyle = '#9ca3af'; ctx.fillText(t('race.marker.start'), 112, ly);
  if (puTypeArr.length > 0) {
    ctx.fillStyle = puColors[0]; ctx.fillRect(170, ly - 6, 8, 8);
    ctx.fillStyle = '#9ca3af'; ctx.fillText(t('race.marker.powerup'), 182, ly);
  }
}

// ── Race: Canvas Click ───────────────────────────────────────────────────────

function edRaceCanvasClick(e) {
  const canvas = document.getElementById('race-canvas');
  if (!canvas) return;
  const rt = window._ed && window._ed._rt;
  const config = rt && rt.config;
  if (!config || !config.zoneBounds || !config.zoneBounds.min) return;
  // Ignore if we were dragging (pan)
  if (rt._dragging) return;

  const rect = canvas.getBoundingClientRect();
  const mx = (e.clientX - rect.left) * (canvas.width / rect.width);
  const my = (e.clientY - rect.top) * (canvas.height / rect.height);

  const tf = _edRaceGetTransform(canvas, config, rt);
  if (!tf) return;
  const { scale: sc, offX: oX, offZ: oZ, bounds } = tf;
  function w2c(wx, wz) { return { x: oX + (wx - bounds.minX) * sc, y: oZ + (wz - bounds.minZ) * sc }; }

  // Check checkpoints first (highest priority)
  const cps = config.checkpoints || [];
  for (let i = 0; i < cps.length; i++) {
    const c = w2c(cps[i].center[0], cps[i].center[2]);
    const r = Math.max(8, sc * 1.2);
    const dx = mx - c.x, dy = my - c.y;
    if (dx * dx + dy * dy <= (r + 4) * (r + 4)) {
      rt.selectedType = 'checkpoint'; rt.selectedIdx = i;
      edRaceRenderMap(); edRaceRenderSelected(); return;
    }
  }

  // Check powerups
  const pus = config.powerups || [];
  for (let i = 0; i < pus.length; i++) {
    const c = w2c(pus[i].center[0], pus[i].center[2]);
    const sz = Math.max(6, sc * 0.8);
    if (Math.abs(mx - c.x) + Math.abs(my - c.y) <= sz + 4) {
      rt.selectedType = 'powerup'; rt.selectedIdx = i;
      edRaceRenderMap(); edRaceRenderSelected(); return;
    }
  }

  // Check start line
  if (config.startLine) {
    const sl = config.startLine;
    const p1 = w2c(sl.min[0], sl.min[2]);
    const p2 = w2c(sl.max[0] + 1, sl.max[2] + 1);
    if (mx >= p1.x && mx <= p2.x && my >= p1.y && my <= p2.y) {
      rt.selectedType = 'start'; rt.selectedIdx = 0;
      edRaceRenderMap(); edRaceRenderSelected(); return;
    }
  }

  // Deselect
  rt.selectedType = null; rt.selectedIdx = null;
  edRaceRenderMap(); edRaceRenderSelected();
}

// ── Race: Selected Detail ────────────────────────────────────────────────────

function edRaceRenderSelected() {
  const el = document.getElementById('race-selected-detail');
  const panel = document.getElementById('race-props-panel');
  if (!el) return;
  const rt = window._ed && window._ed._rt;
  if (!rt || !rt.selectedType || !rt.config) {
    el.innerHTML = '';
    if (panel) panel.style.display = 'none';
    return;
  }
  if (panel) panel.style.display = '';

  const config = rt.config;
  let html = '<div class="p-3 rounded" style="border:1px solid var(--amber);background:var(--bg-elevated);height:100%">';

  if (rt.selectedType === 'checkpoint') {
    const cp = (config.checkpoints || [])[rt.selectedIdx];
    if (!cp) { el.innerHTML = ''; if (panel) panel.style.display = 'none'; return; }
    html += '<div class="flex items-center justify-between mb-3">' +
        '<div class="text-sm font-semibold" style="color:var(--amber)">' +
          t('race.marker.checkpoint') + ' #' + (rt.selectedIdx + 1) + '</div>' +
        '<button class="btn-cg btn-cg-sm btn-cg-ghost" style="padding:0 4px" onclick="edRaceDeselect()">\u2715</button>' +
      '</div>' +
      '<div class="space-y-2 text-xs" style="color:var(--muted)">' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.center') + '</span>' +
          '<div class="mono" style="color:var(--muted-light)">' + cp.center.map(function(c){return c.toFixed(1)}).join(', ') + '</div></div>' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.blocks') + '</span>' +
          '<div style="color:var(--muted-light)">' + (cp.blockCount || 0) + '</div></div>' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.order') + '</span>' +
          '<div class="flex items-center gap-1">' +
            '<button class="btn-cg btn-cg-sm btn-cg-ghost" style="padding:0 4px" onclick="edRaceReorderCheckpoint(' + rt.selectedIdx + ',' + (rt.selectedIdx - 1) + ')"' +
              (rt.selectedIdx <= 0 ? ' disabled' : '') + '>\u25C0</button>' +
            '<input type="number" class="input input-sm input-bordered" style="width:50px;text-align:center" ' +
              'value="' + (rt.selectedIdx + 1) + '" min="1" max="' + (config.checkpoints || []).length + '" ' +
              'onchange="edRaceReorderCheckpoint(' + rt.selectedIdx + ',Number(this.value)-1)">' +
            '<button class="btn-cg btn-cg-sm btn-cg-ghost" style="padding:0 4px" onclick="edRaceReorderCheckpoint(' + rt.selectedIdx + ',' + (rt.selectedIdx + 1) + ')"' +
              (rt.selectedIdx >= (config.checkpoints || []).length - 1 ? ' disabled' : '') + '>\u25B6</button>' +
          '</div>' +
        '</div>' +
      '</div>';
  } else if (rt.selectedType === 'powerup') {
    const pu = (config.powerups || [])[rt.selectedIdx];
    if (!pu) { el.innerHTML = ''; if (panel) panel.style.display = 'none'; return; }
    const puKey = pu.markerType;
    const puCfg = (rt.powerupTypes || {})[puKey];
    const ek = escHtml(puKey);
    html += '<div class="flex items-center justify-between mb-3">' +
        '<div class="text-sm font-semibold" style="color:var(--cyan)">' + ek + '</div>' +
        '<button class="btn-cg btn-cg-sm btn-cg-ghost" style="padding:0 4px" onclick="edRaceDeselect()">\u2715</button>' +
      '</div>' +
      '<div class="space-y-2 text-xs" style="color:var(--muted)">' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.center') + '</span>' +
          '<div class="mono" style="color:var(--muted-light)">' + pu.center.map(function(c){return c.toFixed(1)}).join(', ') + '</div></div>' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.blocks') + '</span>' +
          '<div style="color:var(--muted-light)">' + (pu.blockCount || 0) + '</div></div>' +
        (puCfg ? '<div class="section-divider my-2"></div>' +
          '<div><label class="text-xs" style="color:var(--muted)">' + t('race.effect') + '</label>' +
            '<input type="text" class="input input-sm input-bordered w-full" list="race-effect-dl" value="' + escHtml(puCfg.effectId || '') + '" ' +
              'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'effectId\',this.value)"></div>' +
          '<div class="grid grid-cols-2 gap-1 mt-1">' +
            '<div><label style="font-size:9px;color:var(--muted)">' + t('race.amplifier') + '</label>' +
              '<input type="number" class="input input-sm input-bordered w-full" min="0" max="9" value="' + (puCfg.amplifier || 0) + '" ' +
                'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'amplifier\',this.value)"></div>' +
            '<div><label style="font-size:9px;color:var(--muted)">' + t('race.duration') + ' (s)</label>' +
              '<input type="number" class="input input-sm input-bordered w-full" min="1" max="600" value="' + Math.round((puCfg.durationTicks || 200) / 20) + '" ' +
                'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'durationTicks\',this.value*20)"></div>' +
            '<div><label style="font-size:9px;color:var(--muted)">' + t('race.cooldown') + '</label>' +
              '<input type="number" class="input input-sm input-bordered w-full" min="0" max="300" value="' + (puCfg.cooldownSeconds || 0) + '" ' +
                'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'cooldownSeconds\',this.value)"></div>' +
            '<div><label style="font-size:9px;color:var(--muted)">' + t('race.targetRadius') + '</label>' +
              '<input type="number" class="input input-sm input-bordered w-full" min="0" max="50" value="' + (puCfg.targetRadius || 0) + '" ' +
                'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'targetRadius\',this.value)"></div>' +
          '</div>' +
          '<div class="mt-1"><label style="font-size:9px;color:var(--muted)">' + t('race.sound') + '</label>' +
            '<input type="text" class="input input-sm input-bordered w-full" value="' + escHtml(puCfg.sound || 'block.beacon.activate') + '" ' +
              'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'sound\',this.value)"></div>' +
          '<div class="flex gap-2 mt-1">' +
            '<label class="cursor-pointer flex items-center gap-1"><input type="checkbox" class="toggle toggle-sm"' + (puCfg.singleUse ? ' checked' : '') + ' ' +
              'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'singleUse\',this.checked)"><span style="font-size:9px;color:var(--muted)">' + t('race.singleUse') + '</span></label>' +
            '<label class="cursor-pointer flex items-center gap-1"><input type="checkbox" class="toggle toggle-sm"' + (puCfg.targetSelf !== false ? ' checked' : '') + ' ' +
              'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'targetSelf\',this.checked)"><span style="font-size:9px;color:var(--muted)">' + t('race.targetSelf') + '</span></label>' +
          '</div>' : '') +
      '</div>';
  } else if (rt.selectedType === 'start') {
    const sl = config.startLine;
    if (!sl) { el.innerHTML = ''; if (panel) panel.style.display = 'none'; return; }
    html += '<div class="flex items-center justify-between mb-3">' +
        '<div class="text-sm font-semibold" style="color:var(--green)">' + t('race.marker.start') + '</div>' +
        '<button class="btn-cg btn-cg-sm btn-cg-ghost" style="padding:0 4px" onclick="edRaceDeselect()">\u2715</button>' +
      '</div>' +
      '<div class="space-y-2 text-xs" style="color:var(--muted)">' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.center') + '</span>' +
          '<div class="mono" style="color:var(--muted-light)">' + sl.center.map(function(c){return c.toFixed(1)}).join(', ') + '</div></div>' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.blocks') + '</span>' +
          '<div style="color:var(--muted-light)">' + (sl.blockCount || 0) + '</div></div>' +
        '<div><span class="field-label" style="font-size:10px">' + t('race.size') + '</span>' +
          '<div class="mono" style="color:var(--muted-light)">' +
            (sl.max[0] - sl.min[0] + 1) + ' \u00d7 ' + (sl.max[2] - sl.min[2] + 1) + '</div></div>' +
      '</div>';
  }

  html += '</div>';
  el.innerHTML = html;
}

function edRaceDeselect() {
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  rt.selectedType = null; rt.selectedIdx = null;
  edRaceRenderMap(); edRaceRenderSelected();
}

function edRaceReorderCheckpoint(fromIdx, toIdx) {
  const rt = window._ed && window._ed._rt;
  if (!rt || !rt.config || !rt.config.checkpoints) return;
  const cps = rt.config.checkpoints;
  if (toIdx < 0 || toIdx >= cps.length) return;
  const item = cps.splice(fromIdx, 1)[0];
  cps.splice(toIdx, 0, item);
  rt.selectedIdx = toIdx;
  edRaceRenderMap();
  edRaceRenderSelected();
}

// ── Race: Block Mappings ─────────────────────────────────────────────────────

function edRaceAddMapping() {
  if (!window._ed || !window._ed._rt) return;
  window._ed._rt.mappings.push({block:'', type:'checkpoint'});
  edRaceRenderMappings();
}

function edRaceRemoveMapping(idx) {
  if (!window._ed || !window._ed._rt) return;
  window._ed._rt.mappings.splice(idx, 1);
  edRaceRenderMappings();
}

function edRaceUpdateMapping(idx, field, value) {
  if (!window._ed || !window._ed._rt) return;
  window._ed._rt.mappings[idx][field] = value;
}

function edRaceRenderMappings() {
  const el = document.getElementById('race-mappings-list');
  if (!el) return;
  const rt = window._ed && window._ed._rt;
  if (!rt) return;

  const puTypes = Object.keys(rt.powerupTypes || {});
  const typeOptions = ['checkpoint', 'start'].concat(puTypes);

  el.innerHTML = rt.mappings.map(function(m, i) {
    const typeOpts = typeOptions.map(function(typ) {
      const label = typ === 'checkpoint' ? t('race.marker.checkpoint') : typ === 'start' ? t('race.marker.start') : typ;
      return '<option value="' + escHtml(typ) + '"' + (m.type === typ ? ' selected' : '') + '>' + escHtml(label) + '</option>';
    }).join('');

    return '<div class="flex gap-2 items-end mb-2">' +
      '<div class="flex-1" style="min-width:150px">' +
        '<label class="text-xs" style="color:var(--muted)">' + t('race.block') + '</label>' +
        '<input type="text" class="input input-sm input-bordered w-full" list="race-wool-dl" value="' + escHtml(m.block) + '" ' +
          'onchange="edRaceUpdateMapping(' + i + ',\'block\',this.value)">' +
      '</div>' +
      '<div style="width:130px">' +
        '<label class="text-xs" style="color:var(--muted)">' + t('race.markerType') + '</label>' +
        '<select class="select select-sm select-bordered w-full" onchange="edRaceUpdateMapping(' + i + ',\'type\',this.value)">' + typeOpts + '</select>' +
      '</div>' +
      '<button class="btn-cg-sm" style="padding:2px 8px" onclick="edRaceRemoveMapping(' + i + ')">\u00d7</button>' +
    '</div>';
  }).join('');
}

// ── Race: Powerup Type Config ────────────────────────────────────────────────

function edRaceAddPowerupType() {
  const key = prompt(t('race.typeKey'));
  if (!key || !key.trim()) return;
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  if (rt.powerupTypes[key]) return;
  rt.powerupTypes[key] = {
    effectId: 'minecraft:speed', amplifier: 0, durationTicks: 200,
    targetSelf: true, targetRadius: 0, cooldownSeconds: 10,
    singleUse: false, replacementBlock: 'minecraft:air', sound: 'block.beacon.activate',
  };
  edRaceRenderPowerupTypes();
  edRaceRenderMappings();
}

function edRaceAssignPuBlock(typeKey, blockId) {
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  rt.mappings = rt.mappings.filter(function(m) { return m.type !== typeKey; });
  if (blockId.trim()) {
    rt.mappings.push({ block: blockId.trim(), type: typeKey });
  }
  edRaceRenderMappings();
}

function edRaceRemovePowerupType(key) {
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  delete rt.powerupTypes[key];
  edRaceRenderPowerupTypes();
  edRaceRenderMappings();
}

function edRaceUpdatePowerupType(key, field, value) {
  const rt = window._ed && window._ed._rt;
  if (!rt || !rt.powerupTypes[key]) return;
  if (['amplifier','durationTicks','targetRadius','cooldownSeconds'].includes(field)) {
    rt.powerupTypes[key][field] = Number(value);
  } else if (field === 'singleUse' || field === 'targetSelf') {
    rt.powerupTypes[key][field] = !!value;
  } else {
    rt.powerupTypes[key][field] = value;
  }
}

function edRaceRenderPowerupTypes() {
  const el = document.getElementById('race-pu-types');
  if (!el) return;
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  const entries = Object.entries(rt.powerupTypes || {});
  if (entries.length === 0) {
    el.innerHTML = '<p class="text-xs" style="color:var(--muted)">' + t('race.noPowerupTypes') + '</p>';
    return;
  }
  el.innerHTML = entries.map(function(entry) {
    const key = entry[0], cfg = entry[1];
    const ek = escHtml(key);
    return '<div class="p-3 rounded mb-2" style="border:1px solid var(--bg-border);background:var(--bg-elevated)">' +
      '<div class="flex justify-between items-center mb-2">' +
        '<span class="text-sm font-semibold" style="color:var(--cyan)">' + ek + '</span>' +
        '<button class="btn-cg-sm" style="padding:0 6px" onclick="edRaceRemovePowerupType(\'' + ek + '\')">\u00d7</button>' +
      '</div>' +
      '<div class="grid grid-cols-3 gap-2">' +
        '<div><label class="text-xs" style="color:var(--muted)">' + t('race.effect') + '</label>' +
          '<input type="text" class="input input-sm input-bordered w-full" list="race-effect-dl" value="' + escHtml(cfg.effectId || '') + '" ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'effectId\',this.value)"></div>' +
        '<div><label class="text-xs" style="color:var(--muted)">' + t('race.amplifier') + '</label>' +
          '<input type="number" class="input input-sm input-bordered w-full" min="0" max="9" value="' + (cfg.amplifier || 0) + '" ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'amplifier\',this.value)"></div>' +
        '<div><label class="text-xs" style="color:var(--muted)">' + t('race.duration') + ' (s)</label>' +
          '<input type="number" class="input input-sm input-bordered w-full" min="1" max="600" value="' + Math.round((cfg.durationTicks || 200) / 20) + '" ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'durationTicks\',this.value*20)"></div>' +
        '<div><label class="text-xs" style="color:var(--muted)">' + t('race.cooldown') + '</label>' +
          '<input type="number" class="input input-sm input-bordered w-full" min="0" max="300" value="' + (cfg.cooldownSeconds || 0) + '" ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'cooldownSeconds\',this.value)"></div>' +
        '<div><label class="text-xs" style="color:var(--muted)">' + t('race.targetRadius') + '</label>' +
          '<input type="number" class="input input-sm input-bordered w-full" min="0" max="50" value="' + (cfg.targetRadius || 0) + '" ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'targetRadius\',this.value)"></div>' +
        '<div><label class="text-xs" style="color:var(--muted)">' + t('race.replacementBlock') + '</label>' +
          '<input type="text" class="input input-sm input-bordered w-full" value="' + escHtml(cfg.replacementBlock || 'minecraft:air') + '" ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'replacementBlock\',this.value)"></div>' +
        '<div><label class="text-xs" style="color:var(--muted)">' + t('race.sound') + '</label>' +
          '<input type="text" class="input input-sm input-bordered w-full" value="' + escHtml(cfg.sound || 'block.beacon.activate') + '" ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'sound\',this.value)"></div>' +
      '</div>' +
      '<div class="flex gap-4 mt-2">' +
        '<label class="cursor-pointer flex items-center gap-1">' +
          '<input type="checkbox" class="toggle toggle-sm" ' + (cfg.targetSelf !== false ? 'checked' : '') + ' ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'targetSelf\',this.checked)">' +
          '<span class="text-xs" style="color:var(--muted)">' + t('race.targetSelf') + '</span></label>' +
        '<label class="cursor-pointer flex items-center gap-1">' +
          '<input type="checkbox" class="toggle toggle-sm" ' + (cfg.singleUse ? 'checked' : '') + ' ' +
            'onchange="edRaceUpdatePowerupType(\'' + ek + '\',\'singleUse\',this.checked)">' +
          '<span class="text-xs" style="color:var(--muted)">' + t('race.singleUse') + '</span></label>' +
      '</div>' +
    '</div>';
  }).join('');
}

// ── Race: Transform Commands ─────────────────────────────────────────────────

function edRaceAddTransformCmd() {
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  rt.transformCmds.push('');
  edRaceRenderTransformCmds();
}

function edRaceRemoveTransformCmd(idx) {
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  rt.transformCmds.splice(idx, 1);
  edRaceRenderTransformCmds();
}

function edRaceUpdateTransformCmd(idx, val) {
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  rt.transformCmds[idx] = val;
}

function edRaceRenderTransformCmds() {
  const el = document.getElementById('race-transform-cmds');
  if (!el) return;
  const rt = window._ed && window._ed._rt;
  if (!rt) return;
  el.innerHTML = rt.transformCmds.map(function(c, i) {
    return '<div class="flex gap-1 items-center mb-1">' +
      '<span class="text-xs mono" style="color:var(--amber);width:20px">' + (i + 1) + '.</span>' +
      '<input type="text" class="input input-sm input-bordered flex-1" value="' + escHtml(c) + '" ' +
        'onchange="edRaceUpdateTransformCmd(' + i + ',this.value)">' +
      '<button class="btn-cg-sm" style="padding:0 6px" onclick="edRaceRemoveTransformCmd(' + i + ')">\u00d7</button>' +
    '</div>';
  }).join('');
}

// ── Event Templates — full page ──────────────────────────────────────────────
async function buildEventTemplates(app) {
  let templates;
  try {
    templates = await API.events.templates();
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  window._et = {
    templates, app,

    async loadTemplate(name) {
      try {
        const created = await API.events.loadTemplate(name);
        app.toast(t('events.created'), 'success');
        if (created && created.id) app.navigate('events:' + created.id);
        else app.navigate('events');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteTemplate(name) {
      if (!await confirmAction(t('common.deleteConfirm') + ' ' + name + '" ?')) return;
      try {
        await API.events.deleteTemplate(name);
        app.toast(t('events.deleted'), 'success');
        app.navigate('events:templates');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  const rows = templates.length === 0
    ? '<tr><td colspan="3" class="text-center text-muted text-sm py-8">' + t('common.noResults') + '</td></tr>'
    : templates.map(tpl => `
        <tr>
          <td><span class="id-badge">${escHtml(tpl.name)}</span></td>
          <td class="text-sm text-muted">${escHtml(tpl.description || '\u2014')}</td>
          <td class="flex gap-1">
            <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_et.loadTemplate('${escHtml(tpl.name)}')">${t('common.apply')}</button>
            <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_et.deleteTemplate('${escHtml(tpl.name)}')">${t('common.delete')}</button>
          </td>
        </tr>`).join('');

  return `
    <div class="max-w-4xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <div class="section-card">
        <div class="detail-header">
          <button class="detail-back" onclick="_nav('events')">\u25C0 ' + t('events.title') + '</button>
          <span class="section-card-title">${t('events.templates')}</span>
          <span class="ml-auto id-badge">${templates.length}</span>
        </div>
        <div class="overflow-x-auto">
          <table class="cg-table">
            <thead><tr><th>${t('common.name')}</th><th>${t('common.description')}</th><th>${t('common.actions')}</th></tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
      </div>
    </div>`;
}

// ── Launch Config Modal ─────────────────────────────────────────────────────
async function edOpenLaunchConfig() {
  let cfg;
  try { cfg = await API.events.getLaunchConfig(); } catch (e) {
    if (window._ed) window._ed.app.toast(e.message, 'error');
    return;
  }
  // Remove existing modal if any
  let old = document.getElementById('launch-config-modal');
  if (old) old.remove();

  const periods = [
    { v: 'HOUR', l: t('events.launchPeriodHour') },
    { v: 'DAY',  l: t('events.launchPeriodDay') },
    { v: 'WEEK', l: t('events.launchPeriodWeek') },
  ];
  function periodSel(id, val) {
    return '<select id="' + id + '" class="select select-sm select-bordered">' +
      periods.map(function(p) {
        return '<option value="' + p.v + '"' + (p.v === val ? ' selected' : '') + '>' + escHtml(p.l) + '</option>';
      }).join('') + '</select>';
  }

  const html = '<dialog id="launch-config-modal" class="modal">' +
    '<div class="modal-box" style="background:var(--bg-card);border:1px solid var(--bg-border);max-width:32rem">' +
      '<button class="modal-close" onclick="document.getElementById(\'launch-config-modal\').close()">✕</button>' +
      '<h3 class="text-sm font-bold mb-4" style="color:var(--amber)">' + t('events.launchConfig') + '</h3>' +
      '<div class="space-y-4">' +
        // Per-player limit
        '<div>' +
          '<label class="field-label">' + t('events.launchPlayerLimit') + '</label>' +
          '<div class="flex gap-2 items-center">' +
            '<input id="lc-playerLimitCount" type="number" min="0" class="input input-sm input-bordered w-24" value="' + (cfg.playerLimitCount || 0) + '">' +
            periodSel('lc-playerLimitPeriod', cfg.playerLimitPeriod || 'DAY') +
            '<span class="text-xs" style="color:var(--text-muted)">(' + t('events.launchUnlimited') + ' = 0)</span>' +
          '</div>' +
        '</div>' +
        // Global limit
        '<div>' +
          '<label class="field-label">' + t('events.launchGlobalLimit') + '</label>' +
          '<div class="flex gap-2 items-center">' +
            '<input id="lc-globalLimitCount" type="number" min="0" class="input input-sm input-bordered w-24" value="' + (cfg.globalLimitCount || 0) + '">' +
            periodSel('lc-globalLimitPeriod', cfg.globalLimitPeriod || 'DAY') +
            '<span class="text-xs" style="color:var(--text-muted)">(' + t('events.launchUnlimited') + ' = 0)</span>' +
          '</div>' +
        '</div>' +
        // Time window
        '<div class="section-divider"></div>' +
        '<div>' +
          '<label class="flex items-center gap-2 cursor-pointer">' +
            '<input id="lc-timeWindowEnabled" type="checkbox" class="toggle toggle-sm"' + (cfg.timeWindowEnabled ? ' checked' : '') + ' onchange="document.getElementById(\'lc-time-fields\').style.display=this.checked?\'\':\'none\'">' +
            '<span class="text-sm" style="color:var(--text-secondary)">' + t('events.launchTimeWindow') + '</span>' +
          '</label>' +
          '<div id="lc-time-fields" class="flex gap-3 items-center mt-2"' + (cfg.timeWindowEnabled ? '' : ' style="display:none"') + '>' +
            '<div><label class="text-xs" style="color:var(--text-muted)">' + t('events.launchTimeStart') + '</label>' +
              '<input id="lc-timeWindowStartHour" type="number" min="0" max="23" class="input input-sm input-bordered w-20" value="' + (cfg.timeWindowStartHour || 0) + '"></div>' +
            '<div><label class="text-xs" style="color:var(--text-muted)">' + t('events.launchTimeEnd') + '</label>' +
              '<input id="lc-timeWindowEndHour" type="number" min="0" max="23" class="input input-sm input-bordered w-20" value="' + (cfg.timeWindowEndHour || 23) + '"></div>' +
          '</div>' +
        '</div>' +
      '</div>' +
      '<div class="flex justify-end gap-2 mt-4">' +
        '<button class="btn-cg btn-cg-ghost" onclick="document.getElementById(\'launch-config-modal\').close()">' + t('common.cancel') + '</button>' +
        '<button class="btn-cg btn-cg-primary" onclick="edSaveLaunchConfig()">' + t('common.save') + '</button>' +
      '</div>' +
    '</div>' +
    '<form method="dialog" class="modal-backdrop"><button>close</button></form>' +
  '</dialog>';
  document.body.insertAdjacentHTML('beforeend', html);
  document.getElementById('launch-config-modal').showModal();
}

async function edSaveLaunchConfig() {
  const data = {
    playerLimitCount: Number(document.getElementById('lc-playerLimitCount').value) || 0,
    playerLimitPeriod: document.getElementById('lc-playerLimitPeriod').value,
    globalLimitCount: Number(document.getElementById('lc-globalLimitCount').value) || 0,
    globalLimitPeriod: document.getElementById('lc-globalLimitPeriod').value,
    timeWindowEnabled: document.getElementById('lc-timeWindowEnabled').checked,
    timeWindowStartHour: Number(document.getElementById('lc-timeWindowStartHour').value) || 0,
    timeWindowEndHour: Number(document.getElementById('lc-timeWindowEndHour').value) || 23,
  };
  try {
    await API.events.updateLaunchConfig(data);
    if (window._ed) window._ed.app.toast(t('common.success'), 'success');
    document.getElementById('launch-config-modal').close();
  } catch (e) {
    if (window._ed) window._ed.app.toast(e.message, 'error');
  }
}

// ── Event Detail (Round 4) ──────────────────────────────────────────────────
async function buildEventDetail(app, eventId) {
  let evt;
  try {
    evt = await API.events.get(eventId);
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  if (app.breadcrumbs.length >= 2) app.breadcrumbs[1].label = evt.name || eventId;

  // Fetch mini-game types for the dropdown
  let mgTypes = [];
  try { mgTypes = await API.events.minigameTypes(); } catch (e) { /* non-critical */ }

  const _ed = window._ed = {
    evt, app,
    _tpZoneIds: { tpParticipant: [...(evt.tpParticipantZoneIds || [])], tpSpectator: [...(evt.tpSpectatorZoneIds || [])] },

    _debouncedSave: debounce(function() { if (window._ed) window._ed.save(); }, 1500),

    async save() {
      const data = {};
      ['name','description','displayColor','bossbarColor','announceMessage','startMessage','endMessage'].forEach(k => {
        const el = document.getElementById('ed-' + k);
        if (el) data[k] = el.value;
      });
      ['announceDurationSeconds','countdownSeconds','cooldownMinutes','minParticipants','maxParticipants',
       'teamSize','victoryTargetScore'].forEach(k => {
        const el = document.getElementById('ed-' + k);
        if (el) data[k] = Number(el.value);
      });
      ['mode','trigger','teamMode','deathBehavior','victoryCondition','miniGameType','exclusionGroup'].forEach(k => {
        const el = document.getElementById('ed-' + k);
        if (el) data[k] = el.value;
      });
      ['saveInventory','forceEmptyInventory','bossbarEnabled','discordNotify','useActSystem',
       'scoreboardVisible','lightningMobsOnEnd','disablePokemonSummon','voiceChatGroup','playerLaunchable'].forEach(k => {
        const el = document.getElementById('ed-' + k);
        if (el) data[k] = el.checked;
      });
      // Scope zone IDs (comma-separated)
      const szEl = document.getElementById('ed-scopeZoneIds');
      if (szEl) data.scopeZoneIds = szEl.value.split(',').map(s => s.trim()).filter(Boolean);
      // Kit commands (newline-separated)
      const keEl = document.getElementById('ed-kitEntryCommands');
      if (keEl) data.kitEntryCommands = keEl.value.split('\n').map(s => s.trim()).filter(Boolean);
      const kxEl = document.getElementById('ed-kitExitCommands');
      if (kxEl) data.kitExitCommands = kxEl.value.split('\n').map(s => s.trim()).filter(Boolean);
      // Blacklist items (comma-separated)
      const biEl = document.getElementById('ed-blacklistItems');
      if (biEl) data.blacklistItems = biEl.value.split(',').map(s => s.trim()).filter(Boolean);
      // Collect location fields — respect mode toggle (coords vs zones)
      ['tpParticipant', 'tpSpectator'].forEach(prefix => {
        const modeEl = document.querySelector('input[name="ed-' + prefix + '-mode"]:checked');
        const mode = modeEl ? modeEl.value : 'coords';
        if (mode === 'zones') {
          // Zone mode: send zone IDs, clear fixed coords
          data[prefix + 'ZoneIds'] = this._tpZoneIds[prefix] || [];
          data[prefix + 'X'] = 0; data[prefix + 'Y'] = 0; data[prefix + 'Z'] = 0;
          data[prefix + 'World'] = '';
        } else {
          // Coords mode: send fixed coords, clear zone IDs
          ['X','Y','Z'].forEach(axis => {
            const el = document.getElementById('ed-' + prefix + axis);
            if (el) data[prefix + axis] = Number(el.value);
          });
          const wEl = document.getElementById('ed-' + prefix + 'World');
          if (wEl) data[prefix + 'World'] = wEl.value;
          data[prefix + 'ZoneIds'] = [];
        }
      });
      try {
        this.evt = await API.events.update(eventId, data);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async fillPosition(prefix) {
      try {
        const pos = await API.admin.getPosition();
        if (!pos.online) { app.toast(t('common.offline'), 'warn'); return; }
        const xEl = document.getElementById('ed-' + prefix + 'X');
        const yEl = document.getElementById('ed-' + prefix + 'Y');
        const zEl = document.getElementById('ed-' + prefix + 'Z');
        const wEl = document.getElementById('ed-' + prefix + 'World');
        if (xEl) xEl.value = Math.floor(pos.x);
        if (yEl) yEl.value = Math.floor(pos.y);
        if (zEl) zEl.value = Math.floor(pos.z);
        if (wEl && pos.world) wEl.value = pos.world;
        // Trigger save and wait for it
        await this.save();
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // Zone TP mode helpers
    toggleTpMode(prefix, mode) {
      const coordsEl = document.getElementById('ed-' + prefix + '-coords');
      const zonesEl = document.getElementById('ed-' + prefix + '-zones');
      if (coordsEl) coordsEl.style.display = mode === 'coords' ? '' : 'none';
      if (zonesEl) zonesEl.style.display = mode === 'zones' ? '' : 'none';
    },

    addZone(prefix) {
      const input = document.getElementById('ed-' + prefix + '-zone-input');
      if (!input) return;
      const zid = input.value.trim();
      if (!zid) return;
      if (!this._tpZoneIds[prefix]) this._tpZoneIds[prefix] = [];
      if (this._tpZoneIds[prefix].includes(zid)) { app.toast(t('common.error'), 'warn'); return; }
      this._tpZoneIds[prefix].push(zid);
      input.value = '';
      this._renderZoneBadges(prefix);
      this.save();
    },

    removeZone(prefix, idx) {
      if (!this._tpZoneIds[prefix]) return;
      this._tpZoneIds[prefix].splice(idx, 1);
      this._renderZoneBadges(prefix);
      this.save();
    },

    _renderZoneBadges(prefix) {
      const container = document.getElementById('ed-' + prefix + '-zone-list');
      if (!container) return;
      container.innerHTML = this._tpZoneIds[prefix].map(function(zid, i) {
        return '<span class="id-badge flex items-center gap-1">' + escHtml(zid) +
          ' <button class="text-xs" style="color:var(--red);cursor:pointer" onclick="_ed.removeZone(\'' + prefix + '\',' + i + ')">&times;</button></span>';
      }).join(' ');
    },

    async doAction(action) {
      try {
        if (action === 'start') this.evt = await API.events.start(eventId);
        else if (action === 'stop') this.evt = await API.events.stop(eventId);
        else if (action === 'announce') this.evt = await API.events.announce(eventId);
        app.toast(t('events.actionDone'), 'success');
        edRenderHeader(this.evt);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async addParticipant() {
      const inp = document.getElementById('ed-add-participant');
      if (!inp) return;
      const name = inp.value.trim();
      if (!name) return;
      const roleEl = document.getElementById('ed-add-role');
      const role = roleEl ? roleEl.value : 'PARTICIPANT';
      try {
        await API.events.addParticipant(eventId, { playerName: name, role });
        app.toast(t('common.success'), 'success');
        inp.value = '';
        this.evt = await API.events.get(eventId);
        edRenderParticipants(this.evt);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async removeParticipant(uuid) {
      try {
        await API.events.removeParticipant(eventId, uuid);
        app.toast(t('common.success'), 'success');
        this.evt = await API.events.get(eventId);
        edRenderParticipants(this.evt);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async setScore() {
      const nameEl = document.getElementById('ed-score-name');
      const amountEl = document.getElementById('ed-score-amount');
      const opEl = document.getElementById('ed-score-op');
      if (!nameEl || !amountEl) return;
      try {
        await API.events.score(eventId, { playerName: nameEl.value.trim(), amount: Number(amountEl.value), operation: opEl ? opEl.value : 'add' });
        app.toast(t('common.success'), 'success');
        nameEl.value = ''; amountEl.value = '0';
        this.evt = await API.events.get(eventId);
        edRenderParticipants(this.evt);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async saveTemplate() {
      const inp = document.getElementById('ed-template-name');
      if (!inp) return;
      const name = inp.value.trim();
      if (!name) return;
      try {
        await API.events.saveTemplate(eventId, { name });
        app.toast(t('common.success'), 'success');
        inp.value = '';
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteEvent() {
      const inp = document.getElementById('ed-delete-confirm');
      if (!inp || inp.value !== this.evt.name) { app.toast(t('common.error'), 'warn'); return; }
      try {
        await API.events.delete(eventId);
        app.toast(t('events.deleted'), 'success');
        app.navigate('events');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  // State badge colors
  const stateColors = { IDLE: '--text-muted', ANNOUNCED: '--cyan', QUEUE: '--cyan', COUNTDOWN: '--amber',
    ACTIVE: '--green', ENDING: '--amber', CLEANUP: '--text-muted' };
  const stateColor = stateColors[evt.state] || '--text-muted';

  // Select builder helper
  function sel(id, options, current) {
    return '<select id="ed-' + id + '" class="select select-sm w-full select-bordered">' +
      options.map(o => '<option value="' + o + '"' + (current === o ? ' selected' : '') + '>' + o + '</option>').join('') + '</select>';
  }
  function inp(id, val, type, dl) {
    type = type || 'text';
    return '<input id="ed-' + id + '" type="' + type + '"' + (dl ? ' list="' + dl + '"' : '') + ' class="input input-sm w-full input-bordered" value="' + escHtml(String(val != null ? val : '')) + '">';
  }
  function chk(id, checked, label) {
    return '<label class="flex items-center gap-2 text-sm" style="color:var(--text-secondary)"><input id="ed-' + id + '" type="checkbox" class="checkbox checkbox-sm checkbox-primary"' + (checked ? ' checked' : '') + '>' + escHtml(label) + '</label>';
  }

  // ── Config tab ──
  const configHtml = '<div class="space-y-4 p-1">' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.sections.basic') + '</div>' +
    '<div class="grid grid-cols-2 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('common.name') + '</label>' + inp('name', evt.name) + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('common.description') + '</label>' + inp('description', evt.description || '') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('chunks.resetMode') + '</label>' + sel('mode', ['SERVER_EVENT','MINIGAME'], evt.mode) + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('common.color') + '</label>' + inp('displayColor', evt.displayColor || '') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.exclusionGroup') + '</label>' + inp('exclusionGroup', evt.exclusionGroup || '') + '</div>' +
    '</div>' +
    // Mini-game type section (visible only when mode=MINIGAME)
    '<div id="ed-minigame-section" style="' + (evt.mode === 'MINIGAME' ? '' : 'display:none') + '">' +
      '<div class="section-divider my-3"></div>' +
      '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.sections.minigame') + '</div>' +
      '<div class="grid grid-cols-2 gap-3">' +
        '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.miniGameType') + '</label>' +
          '<select id="ed-miniGameType" class="select select-sm w-full select-bordered">' +
            '<option value=""' + (!evt.miniGameType ? ' selected' : '') + '>-- ' + t('common.none') + ' --</option>' +
            mgTypes.map(function(mg) {
              const dis = mg.enabled === false;
              return '<option value="' + mg.id + '"' + (evt.miniGameType === mg.id ? ' selected' : '') + (dis ? ' disabled style="color:var(--muted)"' : '') + '>' + mg.icon + ' ' + escHtml(mg.displayName) + ' — ' + escHtml(mg.description) + '</option>';
            }).join('') +
          '</select>' +
        '</div>' +
        '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.scopeZones') + '</label>' +
          inp('scopeZoneIds', (evt.scopeZoneIds || []).join(', '), 'text', 'global-zones-dl') +
        '</div>' +
      '</div>' +
    '</div>' +
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.trigger') + '</div>' +
    '<div class="grid grid-cols-2 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.trigger') + '</label>' + sel('trigger', ['MANUAL','SCHEDULED','RECURRING','LUNAR','ZONE_ENTRY'], evt.trigger) + '</div>' +
    '</div>' +
    // ── Player launch section ──
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.playerLaunchable') + '</div>' +
    '<div class="space-y-3">' +
      '<label class="flex items-center gap-2 text-sm" style="color:var(--text-secondary)">' +
        '<input id="ed-playerLaunchable" type="checkbox" class="checkbox checkbox-sm checkbox-primary"' + (evt.playerLaunchable ? ' checked' : '') + '>' +
        t('events.playerLaunchable') + ' ' + tip('tip.events.playerLaunchable') +
      '</label>' +
      '<div class="flex items-center gap-2">' +
        '<code class="text-xs px-3 py-1.5 rounded" style="background:var(--bg-elevated);color:var(--cyan);font-family:var(--font-mono);user-select:all">' + escHtml(evt.launchCommand || '/cg event launch ' + evt.id) + '</code>' +
        '<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="cgCopy(\'' + escHtml(evt.launchCommand || '/cg event launch ' + evt.id) + '\').then(()=>_ed.app.toast(t(\'events.launchCopied\'),\'success\',1500))">📋</button>' +
      '</div>' +
      '<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="edOpenLaunchConfig()">' + t('events.launchConfig') + ' ' + tip('tip.events.launchConfig') + '</button>' +
    '</div>' +
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.sections.timing') + '</div>' +
    '<div class="grid grid-cols-3 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.announceDuration') + '</label>' + inp('announceDurationSeconds', evt.announceDurationSeconds || 30, 'number') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.countdown') + '</label>' + inp('countdownSeconds', evt.countdownSeconds || 10, 'number') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.cooldownMin') + '</label>' + inp('cooldownMinutes', evt.cooldownMinutes || 0, 'number') + '</div>' +
    '</div>' +
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.teamMode') + '</div>' +
    '<div class="grid grid-cols-2 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.teamMode') + '</label>' + sel('teamMode', ['SOLO','DUOS','TEAMS','COOP'], evt.teamMode) + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.teamSize') + '</label>' + inp('teamSize', evt.teamSize || 2, 'number') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.minParticipants') + '</label>' + inp('minParticipants', evt.minParticipants || 0, 'number') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.maxParticipants') + '</label>' + inp('maxParticipants', evt.maxParticipants || 0, 'number') + '</div>' +
    '</div>' +
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.sections.victoryDeath') + '</div>' +
    '<div class="grid grid-cols-2 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.victoryCondition') + '</label>' + sel('victoryCondition', ['TIMER','SCORE_MAX','FIRST_TO_X','SURVIVAL','COOP_GOAL'], evt.victoryCondition) + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.targetScore') + '</label>' + inp('victoryTargetScore', evt.victoryTargetScore || 0, 'number') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.deathBehavior') + '</label>' + sel('deathBehavior', ['RESPAWN_EVENT','BECOME_SPECTATOR'], evt.deathBehavior) + '</div>' +
    '</div>' +
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('common.options') + '</div>' +
    '<div class="grid grid-cols-3 gap-3">' +
      chk('saveInventory', evt.saveInventory, t('events.opt.saveInventory')) +
      chk('forceEmptyInventory', evt.forceEmptyInventory, t('events.opt.forceEmptyInv')) +
      chk('bossbarEnabled', evt.bossbarEnabled, t('events.opt.bossbar')) +
      chk('scoreboardVisible', evt.scoreboardVisible !== false, t('events.opt.scoreboardVisible')) +
      chk('discordNotify', evt.discordNotify, t('events.opt.discordNotify')) +
      chk('useActSystem', evt.useActSystem, t('events.acts')) +
      chk('lightningMobsOnEnd', evt.lightningMobsOnEnd, t('events.opt.lightningOnEnd')) +
      chk('disablePokemonSummon', evt.disablePokemonSummon, t('events.opt.noPokemon')) +
      chk('voiceChatGroup', evt.voiceChatGroup, t('events.opt.voiceChat')) +
    '</div>' +
    '<div class="grid grid-cols-2 gap-3 mt-2">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.bossbarColor') + '</label>' + inp('bossbarColor', evt.bossbarColor || '') + '</div>' +
    '</div>' +
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.kitCommands') + '</div>' +
    '<div class="grid grid-cols-2 gap-3">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.entryCmds') + '</label>' +
        '<textarea id="ed-kitEntryCommands" class="textarea textarea-sm textarea-bordered w-full" rows="3" style="font-family:var(--font-mono);font-size:var(--type-xs)">' + escHtml((evt.kitEntryCommands || []).join('\n')) + '</textarea></div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.exitCmds') + '</label>' +
        '<textarea id="ed-kitExitCommands" class="textarea textarea-sm textarea-bordered w-full" rows="3" style="font-family:var(--font-mono);font-size:var(--type-xs)">' + escHtml((evt.kitExitCommands || []).join('\n')) + '</textarea></div>' +
    '</div>' +
    '<div class="mt-2"><label class="text-sm" style="color:var(--text-muted)">' + t('events.blacklistItems') + '</label>' +
      inp('blacklistItems', (evt.blacklistItems || []).join(', ')) +
    '</div>' +
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.sections.messages') + '</div>' +
    '<div class="space-y-2">' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.announce') + '</label>' + inp('announceMessage', evt.announceMessage || '') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.startSounds') + '</label>' + inp('startMessage', evt.startMessage || '') + '</div>' +
      '<div><label class="text-sm" style="color:var(--text-muted)">' + t('events.end') + '</label>' + inp('endMessage', evt.endMessage || '') + '</div>' +
    '</div>' +
  '</div>';

  // Locations merged into Configuration tab (see configLocMerged below)

  // ── Participants tab (pre-built, refreshed via DOM) ──
  const participantsHtml = '<div id="ed-participants-panel">' + edBuildParticipantsHtml(evt) + '</div>';

  // ── Timeline tab ──
  const acts = evt.acts || [];
  const totalBlocks = acts.reduce((s, a) => s + (a.blocks ? a.blocks.length : 0), 0);
  let timelineHtml = '<div class="p-1">' +
    '<div class="flex items-center gap-3 mb-3">' +
      '<button class="btn-cg btn-cg-primary" onclick="_nav(\'timeline:' + escHtml(eventId) + '\')">\uD83C\uDFAC ' + t('events.tabs.timeline') + '</button>' +
      '<span class="text-sm font-mono" style="color:var(--text-muted)">' + acts.length + ' ' + t('events.actCount') + ', ' + totalBlocks + ' ' + t('events.blockCount') + '</span>' +
    '</div>';
  if (acts.length > 0) {
    timelineHtml += '<table class="cg-table"><thead><tr><th>#</th><th>' + t('common.name') + '</th><th>' + t('common.duration') + '</th><th>' + t('events.blocks') + '</th></tr></thead><tbody>';
    acts.forEach(function(act, i) {
      var dur = act.duration || 0;
      var m = Math.floor(dur / 60), s = dur % 60;
      timelineHtml += '<tr><td class="td-mono">' + (i + 1) + '</td>' +
        '<td class="text-sm">' + escHtml(act.name || t('events.actDefault', {n: i + 1})) + '</td>' +
        '<td class="td-mono">' + m + 'm' + (s > 0 ? s + 's' : '') + '</td>' +
        '<td class="td-mono">' + (act.blocks || []).length + '</td></tr>';
    });
    timelineHtml += '</tbody></table>';
  } else {
    timelineHtml += '<div class="text-sm text-center py-4" style="color:var(--text-muted)">' + t('events.timeline') + '</div>';
  }
  timelineHtml += '</div>';

  // Rewards + Templates merged into "Templates & Rewards" tab (see templatesRewardsHtml below)

  // ── Danger zone (inline at bottom of Configuration tab) ──
  const dangerHtml = '<div class="danger-zone p-4 rounded-lg">' +
      '<div class="danger-zone-title text-sm font-semibold mb-2">' + t('common.delete') + '</div>' +
      '<p class="text-sm mb-3" style="color:var(--text-muted)">' + t('events.typeToConfirm') + ' <strong style="color:var(--text-primary)">' + escHtml(evt.name) + '</strong></p>' +
      '<div class="flex gap-2">' +
        '<input id="ed-delete-confirm" class="input input-sm input-bordered flex-1" placeholder="' + escHtml(evt.name) + '">' +
        '<button class="btn-cg btn-cg-danger" onclick="_ed.deleteEvent()">' + t('common.delete') + '</button>' +
      '</div>' +
  '</div>';

  // ── Action buttons based on state ──
  let actionBtns = '';
  if (evt.state === 'IDLE') {
    actionBtns = '<button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_ed.doAction(\'announce\')">' + t('events.announce') + '</button>' +
                 '<button class="btn-cg btn-cg-success btn-cg-sm" onclick="_ed.doAction(\'start\')">' + t('events.startEvent') + '</button>';
  } else if (evt.state === 'ANNOUNCED' || evt.state === 'QUEUE') {
    actionBtns = '<button class="btn-cg btn-cg-success btn-cg-sm" onclick="_ed.doAction(\'start\')">' + t('events.startEvent') + '</button>';
  } else if (evt.state === 'ACTIVE' || evt.state === 'COUNTDOWN') {
    actionBtns = '<button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_ed.doAction(\'stop\')">' + t('events.stopEvent') + '</button>';
  }

  const stBadge = '<span class="text-xs font-mono" style="color:var(' + stateColor + ')">' + evt.state + '</span>';

  // ── Templates & Rewards merged tab ──
  const templatesRewardsHtml = '<div class="space-y-6 p-1">' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.tabs.rewards') + '</div>' +
    (evt.rewards && evt.rewards.length > 0
      ? '<table class="cg-table"><thead><tr><th>' + t('common.rank') + '</th><th>' + t('events.commands') + '</th></tr></thead><tbody>' +
        evt.rewards.map(function(r) {
          var cmds = r.commands ? r.commands.map(function(c) { return '<code class="text-xs" style="color:var(--cyan)">' + escHtml(c) + '</code>'; }).join('<br>') : '\u2014';
          return '<tr><td class="td-mono font-bold">' + (r.rank || r.position || '\u2014') + '</td><td class="text-sm">' + cmds + '</td></tr>';
        }).join('') +
        '</tbody></table>'
      : '<div class="text-sm text-center py-4" style="color:var(--text-muted)">' + t('common.noResults') + '</div>'
    ) +
    '<div class="section-divider my-4"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.saveAsTemplate') + '</div>' +
    '<div class="flex gap-2 items-end">' +
      '<div class="flex-1"><input id="ed-template-name" class="input input-sm w-full input-bordered" placeholder="ex: pvp_arena"></div>' +
      '<button class="btn-cg btn-cg-primary" onclick="_ed.saveTemplate()">' + t('events.createTemplate') + '</button>' +
    '</div>' +
  '</div>';

  // ── Configuration + Locations merged tab ──
  // Determine initial mode: zones if zoneIds are non-empty, else coords
  const pZoneIds = evt.tpParticipantZoneIds || [];
  const sZoneIds = evt.tpSpectatorZoneIds || [];
  const pMode = pZoneIds.length > 0 ? 'zones' : 'coords';
  const sMode = sZoneIds.length > 0 ? 'zones' : 'coords';

  function _tpSectionHtml(prefix, label, mode, zoneIds, world, x, y, z) {
    const coordsVis = mode === 'coords' ? '' : 'display:none';
    const zonesVis = mode === 'zones' ? '' : 'display:none';
    const zoneBadges = zoneIds.map(function(zid, i) {
      return '<span class="id-badge flex items-center gap-1">' + escHtml(zid) +
        ' <button class="text-xs" style="color:var(--red);cursor:pointer" onclick="_ed.removeZone(\'' + prefix + '\',' + i + ')">&times;</button></span>';
    }).join(' ');
    return '<div class="text-sm font-semibold mb-1" style="color:var(--text-secondary)">' + label + '</div>' +
      '<div class="flex gap-2 mb-2">' +
        '<label class="flex items-center gap-1 cursor-pointer text-xs" style="color:var(--text-muted)">' +
          '<input type="radio" name="ed-' + prefix + '-mode" value="coords" class="radio radio-xs radio-warning"' + (mode === 'coords' ? ' checked' : '') +
          ' onchange="_ed.toggleTpMode(\'' + prefix + '\',\'coords\')"> ' + t('common.position') + '</label>' +
        '<label class="flex items-center gap-1 cursor-pointer text-xs" style="color:var(--text-muted)">' +
          '<input type="radio" name="ed-' + prefix + '-mode" value="zones" class="radio radio-xs radio-warning"' + (mode === 'zones' ? ' checked' : '') +
          ' onchange="_ed.toggleTpMode(\'' + prefix + '\',\'zones\')"> ' + t('nav.zones') + '</label>' +
      '</div>' +
      '<div id="ed-' + prefix + '-coords" style="' + coordsVis + '">' +
        '<div class="grid grid-cols-4 gap-2 mb-2">' +
          '<div><label class="text-sm" style="color:var(--text-muted)">' + t('common.world') + '</label>' + inp(prefix + 'World', world || '', 'text', 'global-dims-dl') + '</div>' +
          '<div><label class="text-sm" style="color:var(--text-muted)">X</label>' + inp(prefix + 'X', x || 0, 'number') + '</div>' +
          '<div><label class="text-sm" style="color:var(--text-muted)">Y</label>' + inp(prefix + 'Y', y || 0, 'number') + '</div>' +
          '<div><label class="text-sm" style="color:var(--text-muted)">Z</label>' + inp(prefix + 'Z', z || 0, 'number') + '</div>' +
        '</div>' +
        '<button class="btn-cg btn-cg-ghost mb-2" onclick="_ed.fillPosition(\'' + prefix + '\')">\uD83D\uDCCD ' + t('events.useMyPosition') + '</button>' +
      '</div>' +
      '<div id="ed-' + prefix + '-zones" style="' + zonesVis + '">' +
        '<div id="ed-' + prefix + '-zone-list" class="flex flex-wrap gap-1 mb-2">' + zoneBadges + '</div>' +
        '<div class="flex gap-2 items-center mb-2">' +
          '<input id="ed-' + prefix + '-zone-input" class="input input-sm input-bordered flex-1" list="global-zones-dl" placeholder="Zone ID...">' +
          '<button class="btn-cg btn-cg-ghost" onclick="_ed.addZone(\'' + prefix + '\')">' + t('common.add') + '</button>' +
        '</div>' +
        '<p class="text-xs mb-2" style="color:var(--text-muted)">' + t('tip.events.scopeZones') + '</p>' +
      '</div>';
  }

  const locationsHtml =
    '<div class="section-divider my-3"></div>' +
    '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + t('events.sections.locations') + '</div>' +
    _tpSectionHtml('tpParticipant', t('events.tpParticipants'), pMode, pZoneIds, evt.tpParticipantWorld, evt.tpParticipantX, evt.tpParticipantY, evt.tpParticipantZ) +
    '<div class="my-2"></div>' +
    _tpSectionHtml('tpSpectator', t('events.tpSpectators'), sMode, sZoneIds, evt.tpSpectatorWorld, evt.tpSpectatorX, evt.tpSpectatorY, evt.tpSpectatorZ);
  // Insert locations + danger zone before the last </div> of configHtml
  const configLocMerged = configHtml.slice(0, -'</div>'.length) +
    locationsHtml +
    '<div class="section-divider my-4"></div>' +
    dangerHtml +
    '</div>';

  const tabs = [
    { id: 'config', label: t('events.tabs.config') },
    { id: 'participants', label: t('events.tabs.participants') },
    { id: 'timeline', label: t('events.tabs.timeline') },
    { id: 'templates', label: t('events.tabs.rewards') },
  ];
  // Conditional mini-game config tab
  const isRace = evt.mode === 'MINIGAME' && evt.miniGameType === 'RACE';
  const mgSchema = (evt.mode === 'MINIGAME' && evt.miniGameType && !isRace) ? MINIGAME_CONFIG_SCHEMA[evt.miniGameType] : null;
  if (isRace) {
    tabs.push({ id: 'minigame', label: '\uD83C\uDFC1 ' + t('race.title') });
  } else if (mgSchema && mgSchema.fields.length > 0) {
    tabs.push({ id: 'minigame', label: '\uD83C\uDFAE ' + t('events.miniGameType') });
  }

  // ── Build mini-game config tab (conditional) ──
  let minigameHtml = '';
  if (isRace) {
    minigameHtml = edRaceBuildPanel(evt);
  } else if (mgSchema && mgSchema.fields.length > 0) {
    const cfg = evt.miniGameConfig || {};
    minigameHtml = '<div class="space-y-4 p-1">' +
      '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">\uD83C\uDFAE ' + escHtml(t(mgSchema.label)) + '</div>' +
      '<p class="text-xs mb-3" style="color:var(--text-muted)">' + t('events.miniGameType') + '</p>' +
      '<div class="grid grid-cols-2 gap-3">' +
      mgSchema.fields.map(function(f) {
        var val = cfg[f.k] !== undefined ? cfg[f.k] : f.def;
        var tipHtml = f.tip ? ' <span class="tl-tip" data-tip="' + escHtml(t(f.tip)) + '">(?)</span>' : '';
        if (f.t === 'bool') {
          return '<div class="flex items-center gap-2">' +
            '<label class="cursor-pointer flex items-center gap-2">' +
            '<input type="checkbox" id="ed-mg-' + f.k + '" class="toggle toggle-sm" ' + (val ? 'checked' : '') + '>' +
            '<span class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + '</span>' + tipHtml +
            '</label></div>';
        }
        if (f.t === 'text') {
          return '<div>' +
            '<label class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
            '<input type="text" id="ed-mg-' + f.k + '" class="input input-sm w-full input-bordered" value="' + escHtml(String(val)) + '"' +
            (f.ph ? ' placeholder="' + escHtml(f.ph) + '"' : '') +
            (f.dl ? ' list="' + escHtml(f.dl) + '"' : '') + '>' +
            '</div>';
        }
        if (f.t === 'tpSection') {
          // TP picker: toggle between coords mode (world,X,Y,Z + "use my position") and zones mode (zone list)
          var prefix = f.k; // e.g. 'suddenDeathTp'
          var zoneIds = Array.isArray(cfg[prefix + 'ZoneIds']) ? cfg[prefix + 'ZoneIds'] :
            (typeof cfg[prefix + 'ZoneIds'] === 'string' && cfg[prefix + 'ZoneIds'] ? cfg[prefix + 'ZoneIds'].split(',').map(function(s){return s.trim()}).filter(Boolean) : []);
          var tpWorld = cfg[prefix + 'World'] || '';
          var tpX = cfg[prefix + 'X'] || 0;
          var tpY = cfg[prefix + 'Y'] || 0;
          var tpZ = cfg[prefix + 'Z'] || 0;
          var tpMode = zoneIds.length > 0 ? 'zones' : 'coords';
          // Init local zone state
          if (!window._ed._mgTpZones) window._ed._mgTpZones = {};
          window._ed._mgTpZones[prefix] = zoneIds.slice();
          var zBadgesHtml = zoneIds.map(function(z, i) {
            return '<span class="id-badge" style="display:inline-flex;align-items:center;gap:4px;margin:2px">' + escHtml(z) +
              ' <button class="btn-cg-sm" style="padding:0 4px;line-height:1;font-size:10px" onclick="edMgTpRemoveZone(\'' + escHtml(prefix) + '\',' + i + ')">\u00d7</button></span>';
          }).join('');
          return '<div class="col-span-2">' +
            '<label class="text-sm font-semibold" style="color:var(--text-secondary)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
            '<div class="flex gap-2 mb-2 mt-1">' +
              '<label class="flex items-center gap-1 cursor-pointer text-xs" style="color:var(--text-muted)">' +
                '<input type="radio" name="ed-mg-' + prefix + '-mode" value="coords" class="radio radio-xs radio-warning"' + (tpMode === 'coords' ? ' checked' : '') +
                ' onchange="edMgTpToggle(\'' + prefix + '\',\'coords\')">' + t('common.position') +
              '</label>' +
              '<label class="flex items-center gap-1 cursor-pointer text-xs" style="color:var(--text-muted)">' +
                '<input type="radio" name="ed-mg-' + prefix + '-mode" value="zones" class="radio radio-xs radio-warning"' + (tpMode === 'zones' ? ' checked' : '') +
                ' onchange="edMgTpToggle(\'' + prefix + '\',\'zones\')">' + t('nav.zones') +
              '</label>' +
            '</div>' +
            // Coords mode
            '<div id="ed-mg-' + prefix + '-coords" style="' + (tpMode === 'coords' ? '' : 'display:none') + '">' +
              '<div class="grid grid-cols-4 gap-2 mb-2">' +
                '<div><label class="text-xs" style="color:var(--text-muted)">' + t('common.world') + '</label>' +
                  '<input id="ed-mg-' + prefix + 'World" type="text" list="global-dims-dl" class="input input-sm w-full input-bordered" value="' + escHtml(tpWorld) + '"></div>' +
                '<div><label class="text-xs" style="color:var(--text-muted)">X</label>' +
                  '<input id="ed-mg-' + prefix + 'X" type="number" class="input input-sm w-full input-bordered" value="' + tpX + '"></div>' +
                '<div><label class="text-xs" style="color:var(--text-muted)">Y</label>' +
                  '<input id="ed-mg-' + prefix + 'Y" type="number" class="input input-sm w-full input-bordered" value="' + tpY + '"></div>' +
                '<div><label class="text-xs" style="color:var(--text-muted)">Z</label>' +
                  '<input id="ed-mg-' + prefix + 'Z" type="number" class="input input-sm w-full input-bordered" value="' + tpZ + '"></div>' +
              '</div>' +
              '<button class="btn-cg btn-cg-ghost btn-cg-sm mb-2" onclick="edMgTpFillPos(\'' + prefix + '\')">📍 ' + t('events.useMyPosition') + '</button>' +
            '</div>' +
            // Zones mode
            '<div id="ed-mg-' + prefix + '-zones" style="' + (tpMode === 'zones' ? '' : 'display:none') + '">' +
              '<div id="ed-mg-' + prefix + '-zone-badges" class="flex flex-wrap gap-1 mb-2">' + zBadgesHtml + '</div>' +
              '<div class="flex gap-1">' +
                '<input type="text" id="ed-mg-' + prefix + '-zone-input" class="input input-sm input-bordered flex-1" list="global-zones-dl" placeholder="Zone ID...">' +
                '<button class="btn-cg btn-cg-sm" onclick="edMgTpAddZone(\'' + prefix + '\')">' + t('common.add') + '</button>' +
              '</div>' +
            '</div>' +
          '</div>';
        }
        if (f.t === 'select') {
          var selVal = val || f.def || '';
          var optHtml = (f.opts || []).map(function(o) {
            return '<option value="' + escHtml(o.v) + '"' + (o.v === selVal ? ' selected' : '') + '>' + escHtml(t(o.l)) + '</option>';
          }).join('');
          return '<div>' +
            '<label class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
            '<select id="ed-mg-' + f.k + '" class="select select-sm w-full select-bordered">' + optHtml + '</select>' +
            '</div>';
        }
        if (f.t === 'zonelist') {
          // Initialize local state
          var zones = Array.isArray(val) ? val.slice() : [];
          if (!window._ed._mgZoneLists) window._ed._mgZoneLists = {};
          window._ed._mgZoneLists[f.k] = zones;
          var badgesHtml = zones.map(function(z, i) {
            return '<span class="id-badge" style="display:inline-flex;align-items:center;gap:4px;margin:2px">' +
              '<span style="color:var(--amber);font-weight:600">' + (i + 1) + '.</span> ' + escHtml(z) +
              ' <button class="btn-cg-sm" style="padding:0 4px;line-height:1;font-size:10px" onclick="edMgRemoveZone(\'' + escHtml(f.k) + '\',' + i + ')">\u00d7</button></span>';
          }).join('');
          return '<div class="col-span-2">' +
            '<label class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
            '<div id="ed-mg-' + f.k + '-badges" class="flex flex-wrap gap-1 mb-2">' + badgesHtml + '</div>' +
            '<div class="flex gap-1">' +
              '<input type="text" id="ed-mg-' + f.k + '-input" class="input input-sm input-bordered flex-1" list="global-zones-dl" placeholder="' + escHtml(t('events.zoneIdPlaceholder')) + '">' +
              '<button class="btn-cg btn-cg-sm" onclick="edMgAddZone(\'' + escHtml(f.k) + '\')">' + t('common.add') + '</button>' +
            '</div>' +
          '</div>';
        }
        if (f.t === 'cmdlist') {
          var cmds = Array.isArray(val) ? val.slice() : [];
          if (!window._ed._mgCmdLists) window._ed._mgCmdLists = {};
          window._ed._mgCmdLists[f.k] = cmds;
          var rowsHtml = cmds.map(function(c, i) {
            return '<div class="flex gap-1 items-center mb-1">' +
              '<span class="text-xs mono" style="color:var(--amber);width:20px">' + (i + 1) + '.</span>' +
              '<input type="text" class="input input-sm input-bordered flex-1" value="' + escHtml(c) + '" ' +
                'onchange="edMgUpdateCmd(\'' + escHtml(f.k) + '\',' + i + ',this.value)">' +
              '<button class="btn-cg-sm" style="padding:0 6px" onclick="edMgRemoveCmd(\'' + escHtml(f.k) + '\',' + i + ')">\u00d7</button>' +
            '</div>';
          }).join('');
          return '<div class="col-span-2">' +
            '<label class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
            '<div id="ed-mg-' + f.k + '-rows">' + rowsHtml + '</div>' +
            '<button class="btn-cg btn-cg-sm mt-1" onclick="edMgAddCmd(\'' + escHtml(f.k) + '\')">' + t('common.add') + '</button>' +
          '</div>';
        }
        if (f.t === 'poweruplist') {
          var pus = Array.isArray(val) ? val.slice() : [];
          window._ed._mgPowerUps = pus;
          // Render will be done after DOM injection via setTimeout
          return '<div class="col-span-2">' +
            '<label class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
            '<div id="ed-mg-powerUpZones-rows"></div>' +
            '<button class="btn-cg btn-cg-sm mt-1" onclick="edMgAddPowerUp()">' + t('common.add') + '</button>' +
          '</div>';
        }
        if (f.t === 'specieslist') {
          var list = Array.isArray(val) ? val.slice() : [];
          if (!window._ed._mgSpeciesList) window._ed._mgSpeciesList = {};
          window._ed._mgSpeciesList[f.k] = list;
          return '<div class="col-span-2">' +
            '<label class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
            '<div id="ed-mg-' + f.k + '-list" class="space-y-2 mt-2"></div>' +
            '<button class="btn-cg btn-cg-sm mt-2" onclick="edMgAddSpecies(\'' + escHtml(f.k) + '\')">' +
                t('common.add') +
            '</button>' +
          '</div>';
        }
        if (f.t === 'powerconfig') {
          var config = (typeof val === 'object' && val !== null) ? val : {};
          if (!window._ed._mgPowerConfigs) window._ed._mgPowerConfigs = {};
          var powerList = [];
          (f.powers || []).forEach(function(p) {
            var saved = config[p.id] || {};
            powerList.push({
              id: p.id,
              icon: p.icon,
              enabled: saved.enabled !== undefined ? saved.enabled : p.def.enabled,
              cooldown: saved.cooldown !== undefined ? saved.cooldown : p.def.cooldown
            });
          });
          window._ed._mgPowerConfigs[f.k] = powerList;
          var roleLabel = f.role === 'hunter' ? t('mg.ph.hunterPowersTitle') : t('mg.ph.hiderPowersTitle');
          return '<div class="col-span-2 mt-4">' +
            '<div class="text-sm font-semibold mb-2" style="color:var(--amber)">' + escHtml(roleLabel) + '</div>' +
            '<div id="ed-mg-' + f.k + '-table"></div>' +
          '</div>';
        }
        return '<div>' +
          '<label class="text-sm" style="color:var(--text-muted)">' + escHtml(t(f.l)) + tipHtml + '</label>' +
          '<input type="number" id="ed-mg-' + f.k + '" class="input input-sm w-full input-bordered" value="' + val + '"' +
            (f.min !== undefined ? ' min="' + f.min + '"' : '') +
            (f.max !== undefined ? ' max="' + f.max + '"' : '') + '>' +
          '</div>';
      }).join('') +
      '</div>' +
    '</div>';
  }

  // Auto-save event config on any input/change in the config tab
  // Retry until the panel exists in the DOM (Alpine x-html injection is async)
  function _edAttachAutoSave() {
    const configPanel = document.querySelector('[data-tab-panel-ed="config"]');
    if (!configPanel) { setTimeout(_edAttachAutoSave, 150); return; }
    const autoSave = debounce(() => { if (window._ed) window._ed.save(); }, 1500);
    configPanel.addEventListener('input', autoSave);
    configPanel.addEventListener('change', autoSave);
    // Auto-save minigame config tab too
    const mgPanel = document.querySelector('[data-tab-panel-ed="minigame"]');
    if (mgPanel) {
      const mgAutoSave = debounce(() => { edSaveMiniGameConfig(); }, 1500);
      mgPanel.addEventListener('input', mgAutoSave);
      mgPanel.addEventListener('change', mgAutoSave);
    }
    // Toggle mini-game section visibility when mode changes
    const modeEl = document.getElementById('ed-mode');
    const mgSection = document.getElementById('ed-minigame-section');
    if (modeEl && mgSection) {
      modeEl.addEventListener('change', () => {
        mgSection.style.display = modeEl.value === 'MINIGAME' ? '' : 'none';
      });
    }
    // Deferred render for power-ups (DOM must exist first)
    if (window._ed && window._ed._mgPowerUps && window._ed._mgPowerUps.length > 0) {
      setTimeout(edMgRenderPowerUps, 50);
    }
    // Deferred render for species list (DOM must exist first)
    if (window._ed && window._ed._mgSpeciesList) {
      setTimeout(function() {
        for (const fk of Object.keys(window._ed._mgSpeciesList)) {
          edMgRenderSpeciesList(fk);
        }
      }, 50);
    }
    // Deferred render for power config tables (DOM must exist first)
    if (window._ed && window._ed._mgPowerConfigs) {
      setTimeout(function() {
        for (var fk of Object.keys(window._ed._mgPowerConfigs)) {
          edMgRenderPowerConfig(fk);
        }
      }, 50);
    }
    // Init race track editor (deferred — DOM must exist)
    if (isRace) {
      setTimeout(function() { edRaceInit(evt.id); }, 50);
    }
  }
  setTimeout(_edAttachAutoSave, 100);

  return '<div style="animation:slide-in-right 0.2s ease-out">' +
    '<div class="section-card">' +
      '<div class="detail-header">' +
        detailBackBtn('events', t('events.title')) +
        '<div class="flex items-center gap-3 flex-1">' +
          '<span class="text-sm font-semibold" style="color:var(--text-heading)">' + escHtml(evt.name) + '</span>' +
          '<span id="ed-state-badge">' + stBadge + '</span>' +
          '<span class="id-badge">' + escHtml(evt.id) + '</span>' +
          '<button class="btn-cg btn-cg-ghost btn-cg-sm" style="padding:2px 6px;font-size:11px" title="/cg event announce ' + escHtml(evt.id) + '" onclick="cgCopy(\'/cg event announce ' + escHtml(evt.id) + '\').then(()=>_ed.app.toast(t(\'common.copied\'),\'success\',1500))">📋 /cg event announce</button>' +
          '<button class="btn-cg btn-cg-ghost btn-cg-sm" style="padding:2px 6px;font-size:11px" title="/cg event join ' + escHtml(evt.id) + '" onclick="cgCopy(\'/cg event join ' + escHtml(evt.id) + '\').then(()=>_ed.app.toast(t(\'common.copied\'),\'success\',1500))">📋 /cg event join</button>' +
        '</div>' +
        '<div id="ed-action-btns" class="flex gap-2">' + actionBtns + '</div>' +
      '</div>' +
      detailTabBar(tabs, 'config', 'edSwitchTab') +
      '<div class="section-card-body">' +
        '<div data-tab-panel-ed="config">' + configLocMerged + '</div>' +
        '<div data-tab-panel-ed="participants" style="display:none">' + participantsHtml + '</div>' +
        '<div data-tab-panel-ed="timeline" style="display:none">' + timelineHtml + '</div>' +
        '<div data-tab-panel-ed="templates" style="display:none">' + templatesRewardsHtml + '</div>' +
        (minigameHtml ? '<div data-tab-panel-ed="minigame" style="display:none">' + minigameHtml + '</div>' : '') +
      '</div>' +
    '</div>' +
  '</div>';
}

function edBuildParticipantsHtml(evt) {
  var eid = escHtml(evt.id);
  var html = '<div class="flex gap-2 items-end mb-3">' +
    '<input id="ed-add-participant" class="input input-sm input-bordered flex-1" placeholder="' + escHtml(t('common.playerName')) + '" readonly ' +
      'onclick="openPlayerPicker({title:t(\'events.addParticipant\'),onSelect:function(n){document.getElementById(\'ed-add-participant\').value=n}})">' +
    '<select id="ed-add-role" class="select select-sm select-bordered">' +
      '<option>PARTICIPANT</option><option>SPECTATOR</option>' +
    '</select>' +
    '<button class="btn-cg btn-cg-primary" style="height:2rem;font-size:0.75rem" onclick="_ed.addParticipant()">' + t('common.add') + '</button>' +
  '</div>' +
  '<div class="section-divider mb-3"></div>' +
  '<div class="flex gap-2 items-end mb-3">' +
    '<input id="ed-score-name" class="input input-sm input-bordered" placeholder="' + escHtml(t('common.player')) + '">' +
    '<input id="ed-score-amount" type="number" class="input input-sm input-bordered w-20" value="0">' +
    '<select id="ed-score-op" class="select select-sm select-bordered">' +
      '<option value="add">' + t('events.score.add') + '</option><option value="set">' + t('events.score.set') + '</option><option value="subtract">' + t('events.score.sub') + '</option>' +
    '</select>' +
    '<button class="btn-cg btn-cg-primary" style="height:2rem;font-size:0.75rem" onclick="_ed.setScore()">' + t('events.scoreBtn') + '</button>' +
  '</div>';

  if (evt.participants && evt.participants.length > 0) {
    html += '<table class="cg-table"><thead><tr><th>' + t('common.player') + '</th><th>' + t('events.effectType') + '</th><th>' + t('events.score') + '</th><th></th></tr></thead><tbody>';
    evt.participants.forEach(function(p) {
      html += '<tr>' +
        '<td class="text-sm" style="color:var(--text-primary)">' + escHtml(p.name) + '</td>' +
        '<td><span class="text-xs font-mono" style="color:var(' + (p.role === 'SPECTATOR' ? '--text-muted' : '--cyan') + ')">' + p.role + '</span></td>' +
        '<td class="td-mono">' + p.score + '</td>' +
        '<td><button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_ed.removeParticipant(\'' + escHtml(p.uuid) + '\')">\u2715</button></td>' +
      '</tr>';
    });
    html += '</tbody></table>';
  } else {
    html += '<div class="text-sm text-center py-4" style="color:var(--text-muted)">' + t('common.noResults') + '</div>';
  }
  return html;
}

function edRenderParticipants(evt) {
  var el = document.getElementById('ed-participants-panel');
  if (el) el.innerHTML = edBuildParticipantsHtml(evt);
}

function edRenderHeader(evt) {
  var stateColors = { IDLE: '--text-muted', ANNOUNCED: '--cyan', QUEUE: '--cyan', COUNTDOWN: '--amber',
    ACTIVE: '--green', ENDING: '--amber', CLEANUP: '--text-muted' };
  var c = stateColors[evt.state] || '--text-muted';
  var badge = document.getElementById('ed-state-badge');
  if (badge) badge.innerHTML = '<span class="text-xs font-mono" style="color:var(' + c + ')">' + evt.state + '</span>';
  var btns = document.getElementById('ed-action-btns');
  if (btns) {
    var h = '';
    if (evt.state === 'IDLE') {
      h = '<button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_ed.doAction(\'announce\')">' + t('events.announce') + '</button>' +
          '<button class="btn-cg btn-cg-success btn-cg-sm" onclick="_ed.doAction(\'start\')">' + t('events.startEvent') + '</button>';
    } else if (evt.state === 'ANNOUNCED' || evt.state === 'QUEUE') {
      h = '<button class="btn-cg btn-cg-success btn-cg-sm" onclick="_ed.doAction(\'start\')">' + t('events.startEvent') + '</button>';
    } else if (evt.state === 'ACTIVE' || evt.state === 'COUNTDOWN') {
      h = '<button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_ed.doAction(\'stop\')">' + t('events.stopEvent') + '</button>';
    }
    btns.innerHTML = h;
  }
}
