// ── CheeseKart ───────────────────────────────────────────────────────────────

async function buildKart(app) {
  const _kart = window._kart || {};
  _kart.tab = _kart.tab || 'presets';
  _kart.presets = _kart.presets || [];
  _kart.active = _kart.active || [];
  _kart.editing = _kart.editing || null;
  _kart._saveTimer = _kart._saveTimer || null;
  _kart._app = app;

  function _kartRefresh() { app.viewHtml = ''; app.navigate('kart'); }

  _kart.load = async function() {
    try {
      const [presets, active] = await Promise.all([API.kart.presets(), API.kart.active()]);
      _kart.presets = presets || [];
      _kart.active = active || [];
    } catch(e) { app.toast(e.message,'error'); }
  };
  _kart.deletePreset = async function(id) {
    if (!await confirmAction(t('common.deleteConfirm') + ' '+escHtml(id)+'" ?')) return;
    try { await API.kart.deletePreset(id); app.toast(t('kart.presetDeleted'),'success'); await _kart.load(); _kartRefresh(); } catch(e) { app.toast(e.message,'error'); }
  };
  _kart._buildData = function() {
    const f = _kart.editing; if (!f) return null;
    return {
      name: f.name, slipperiness: parseFloat(f.slipperiness), stepHeight: parseFloat(f.stepHeight),
      gravity: parseFloat(f.gravity), forwardAccel: parseFloat(f.forwardAccel), backwardAccel: parseFloat(f.backwardAccel),
      yawAccel: parseFloat(f.yawAccel), turnForwardAccel: parseFloat(f.turnForwardAccel),
      accelStacking: !!f.accelStacking, airControl: !!f.airControl, airStepping: !!f.airStepping,
      jumpForce: parseFloat(f.jumpForce), fallDamage: !!f.fallDamage, locked: !!f.locked, species: f.species || '',
    };
  };
  _kart.savePreset = async function() {
    const form = _kart.editing; if (!form) return;
    const data = _kart._buildData(); if (!data) return;
    try {
      if (form._isNew) {
        data.id = form.id;
        await API.kart.createPreset(data);
        app.toast(t('kart.presetCreated'),'success');
      } else {
        await API.kart.updatePreset(form.id, data);
        app.toast(t('common.success'),'success');
      }
      _kart.editing = null;
      await _kart.load(); _kartRefresh();
    } catch(e) { app.toast(e.message,'error'); }
  };
  // Auto-save with debounce (600ms) — updates are applied in real-time in-game
  _kart._autoSave = function() {
    if (!_kart.editing || _kart.editing._isNew) return;
    if (_kart._saveTimer) clearTimeout(_kart._saveTimer);
    _kart._saveTimer = setTimeout(async () => {
      const data = _kart._buildData(); if (!data) return;
      try {
        await API.kart.updatePreset(_kart.editing.id, data);
        const ind = document.getElementById('kart-autosave-ind');
        if (ind) { ind.textContent = t('kart.appliedInGame'); ind.style.color = 'var(--green)'; setTimeout(()=>{ if(ind) { ind.textContent = t('kart.autoSaveActive'); ind.style.color = 'var(--muted)'; }}, 1500); }
      } catch(e) { app.toast(e.message,'error'); }
    }, 600);
  };
  _kart._fieldChange = function(key, value) {
    if (!_kart.editing) return;
    _kart.editing[key] = value;
    _kart._autoSave();
  };
  _kart._toggleField = function(key) {
    if (!_kart.editing) return;
    _kart.editing[key] = !_kart.editing[key];
    _kart._autoSave();
  };
  _kart.openCreate = function() {
    _kart.editing = { _isNew:true, id:'', name:'', slipperiness:0.98, stepHeight:1.25, gravity:-0.04, forwardAccel:0.04, backwardAccel:0.005, yawAccel:1.0, turnForwardAccel:0.005, accelStacking:false, airControl:true, airStepping:true, jumpForce:0.0, fallDamage:false, locked:true, species:'' };
    _kartRefresh();
  };
  _kart.openEdit = function(p) {
    _kart.editing = { _isNew:false, ...p };
    _kartRefresh();
  };
  _kart.closeEdit = function() {
    if (_kart._saveTimer) clearTimeout(_kart._saveTimer);
    _kart.editing = null;
    _kart.load().then(()=>_kartRefresh());
  };
  _kart.startKart = async function() {
    const pn = document.getElementById('kart-start-player')?.value?.trim();
    const pr = document.getElementById('kart-start-preset')?.value;
    const sp = document.getElementById('kart-start-species')?.value?.trim() || '';
    if (!pn || !pr) { app.toast(t('kart.playerPresetRequired'),'error'); return; }
    try { await API.kart.start({playerName:pn,presetId:pr,species:sp}); app.toast(t('kart.kartStarted'),'success'); await _kart.load(); _kartRefresh(); } catch(e) { app.toast(e.message,'error'); }
  };
  _kart.stopKart = async function(name) {
    try { await API.kart.stop({playerName:name}); app.toast(t('kart.kartStopped'),'success'); await _kart.load(); _kartRefresh(); } catch(e) { app.toast(e.message,'error'); }
  };
  _kart.stopAll = async function() {
    if (!await confirmAction(t('kart.stopAllConfirm'))) return;
    try { await API.kart.stopAll(); app.toast(t('kart.allKartsStopped'),'success'); await _kart.load(); _kartRefresh(); } catch(e) { app.toast(e.message,'error'); }
  };
  window._kart = _kart;
  await _kart.load();

  // ── Tab bar ───────────────────────────────────────────────────────────
  const tabs = `<div class="flex gap-1 mb-4">
    <button class="btn-cg btn-cg-sm ${_kart.tab==='presets'?'btn-cg-primary':'btn-cg-ghost'}" onclick="window._kart.tab='presets';window._kart._app.viewHtml='';window._kart._app.navigate('kart')">${t('kart.presets')}</button>
    <button class="btn-cg btn-cg-sm ${_kart.tab==='sessions'?'btn-cg-primary':'btn-cg-ghost'}" onclick="window._kart.tab='sessions';window._kart._app.viewHtml='';window._kart._app.navigate('kart')">${t('kart.activeSessions')}</button>
  </div>`;

  let content = '';
  if (_kart.tab === 'presets') {
    // ── Presets table ─────────────────────────────────────────────────
    const rows = _kart.presets.map(p => `<tr class="hover-elevated" style="cursor:pointer" onclick="window._kart.openEdit(window._kart.presets.find(x=>x.id==='${escHtml(p.id)}'))">
      <td><span class="id-badge">${escHtml(p.id)}</span></td>
      <td>${escHtml(p.name)}</td>
      <td class="mono" style="font-size:var(--type-xs)">${p.slipperiness}</td>
      <td class="mono" style="font-size:var(--type-xs)">${p.stepHeight}</td>
      <td class="mono" style="font-size:var(--type-xs)">${p.forwardAccel}</td>
      <td class="mono" style="font-size:var(--type-xs)">${p.yawAccel}</td>
      <td class="mono" style="font-size:var(--type-xs)">${p.jumpForce}</td>
      <td>${p.locked ? '<span class="text-green-400">' + t('common.on') + '</span>' : '<span style="color:var(--muted)">' + t('common.off') + '</span>'}</td>
      <td>${p.species ? '<span class="id-badge">'+escHtml(p.species)+'</span>' : '<span style="color:var(--muted)">-</span>'}</td>
      <td class="flex gap-1" onclick="event.stopPropagation()">
        <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._kart.deletePreset('${escHtml(p.id)}')">${t('common.delete')}</button>
      </td>
    </tr>`).join('');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('kart.presets')}</span>
        <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="window._kart.openCreate()">${t('common.create')}</button>
      </div>
      ${_kart.presets.length === 0 ? `<div class="empty-state"><span class="empty-state-icon">🏎</span><span class="empty-state-text">${t('common.noResults')}</span></div>` : `
      <table class="cg-table"><thead><tr>
        <th>${t('common.id')}</th><th>${t('common.name')}</th><th>${t('kart.thSlip')}</th><th>${t('kart.thStep')}</th><th>${t('kart.thFwd')}</th><th>${t('kart.thYaw')}</th><th>${t('kart.thJump')}</th><th>${t('kart.lock')}</th><th>${t('kart.speciesOverride')}</th><th></th>
      </tr></thead><tbody>${rows}</tbody></table>`}
    </div>`;
  } else {
    // ── Active sessions ────────────────────────────────────────────────
    const rows = _kart.active.map(s => `<tr>
      <td>${escHtml(s.playerName)}</td>
      <td><span class="id-badge">${escHtml(s.presetId)}</span></td>
      <td>${s.species ? '<span class="id-badge">'+escHtml(s.species)+'</span>' : '<span style="color:var(--muted)">-</span>'}</td>
      <td>${s.locked ? '<span class="text-green-400">' + t('common.yes') + '</span>' : t('common.no')}</td>
      <td class="mono">${escHtml(s.durationFormatted)}</td>
      <td><button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._kart.stopKart('${escHtml(s.playerName)}')">${t('common.stop')}</button></td>
    </tr>`).join('');
    const presetOpts = _kart.presets.map(p => `<option value="${escHtml(p.id)}">${escHtml(p.name)}</option>`).join('');
    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('kart.activeSessions')} (${_kart.active.length})</span>
        ${_kart.active.length > 0 ? '<button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._kart.stopAll()">' + t('kart.stopAll') + '</button>' : ''}
      </div>
      ${_kart.active.length === 0 ? `<div class="empty-state"><span class="empty-state-icon">🏎</span><span class="empty-state-text">${t('common.noResults')}</span></div>` : `
      <table class="cg-table"><thead><tr>
        <th>${t('common.player')}</th><th>${t('kart.preset')}</th><th>${t('kart.speciesOverride')}</th><th>${t('kart.lock')}</th><th>${t('common.duration')}</th><th></th>
      </tr></thead><tbody>${rows}</tbody></table>`}
    </div>
    <div class="section-card mt-4">
      <div class="section-card-header"><span class="section-card-title">${t('kart.startKart')}</span></div>
      <div class="section-card-body">
        <div class="grid grid-cols-1 sm:grid-cols-4 gap-3">
          <div><label class="field-label">${t('common.player')}</label><input id="kart-start-player" type="text" class="input input-sm w-full input-bordered" placeholder="${t('kart.playerNamePh')}"></div>
          <div><label class="field-label">${t('kart.preset')}</label><select id="kart-start-preset" class="select select-sm w-full select-bordered">${presetOpts}</select></div>
          <div><label class="field-label">${t('kart.speciesOptional')}</label><input id="kart-start-species" type="text" class="input input-sm w-full input-bordered" list="global-species-dl" placeholder="${t('kart.speciesPh')}"></div>
          <div class="flex items-end"><button class="btn-cg btn-cg-primary w-full" onclick="window._kart.startKart()">${t('common.start')}</button></div>
        </div>
      </div>
    </div>`;
  }

  // ── Edit dialog ──────────────────────────────────────────────────────
  const ed = _kart.editing;
  const _kf = [
    {k:'id',    l:t('common.id'),                   t:'text',  g:'id',    tip:t('tip.kart.id')},
    {k:'name',  l:t('common.name'),                 t:'text',  g:'id',    tip:t('tip.kart.name')},
    {k:'slipperiness', l:t('kart.slipperiness'),    t:'number', s:0.01, g:'physics', tip:t('tip.kart.slipperiness')},
    {k:'stepHeight',   l:t('kart.stepHeight'),      t:'number', s:0.25, g:'physics', tip:t('tip.kart.stepHeight')},
    {k:'gravity',      l:t('kart.gravity'),         t:'number', s:0.005,g:'physics', tip:t('tip.kart.gravity')},
    {k:'forwardAccel', l:t('kart.forwardAccel'),    t:'number', s:0.005,g:'physics', tip:t('tip.kart.forwardAccel')},
    {k:'backwardAccel',l:t('kart.backwardAccel'),   t:'number', s:0.001,g:'physics', tip:t('tip.kart.backwardAccel')},
    {k:'yawAccel',     l:t('kart.yawAccel'),        t:'number', s:0.1,  g:'physics', tip:t('tip.kart.yawAccel')},
    {k:'turnForwardAccel',l:t('kart.turnAccel'),    t:'number', s:0.001,g:'physics', tip:t('tip.kart.turnAccel')},
    {k:'jumpForce',    l:t('kart.jumpForce'),       t:'number', s:0.1,  g:'physics', tip:t('tip.kart.jumpForce')},
    {k:'species',      l:t('kart.speciesCobbleShift'), t:'text',  g:'extra',dl:'global-species-dl', tip:t('tip.kart.species')},
    {k:'accelStacking',l:t('kart.accelStacking'),   t:'bool',  g:'flags', tip:t('tip.kart.accelStacking')},
    {k:'airControl',   l:t('kart.airControl'),      t:'bool',  g:'flags', tip:t('tip.kart.airControl')},
    {k:'airStepping',  l:t('kart.airStepping'),     t:'bool',  g:'flags', tip:t('tip.kart.airStepping')},
    {k:'fallDamage',   l:t('kart.fallDamage'),      t:'bool',  g:'flags', tip:t('tip.kart.fallDamage')},
    {k:'locked',       l:t('kart.lockedNoDismount'),t:'bool',  g:'flags', tip:t('tip.kart.locked')},
  ];
  let formHtml = '';
  if (ed) {
    const autoSaveNote = ed._isNew ? '' : `<div class="col-span-2 flex items-center gap-2 px-1" style="font-size:var(--type-xs)">
      <span class="status-dot s-online" style="width:6px;height:6px"></span>
      <span id="kart-autosave-ind" style="color:var(--muted)">${t('kart.autoSaveActive')}</span>
      <span style="color:var(--muted-light)">— ${t('kart.changesAppliedRealtime')}</span>
    </div>`;
    // Group: Identity
    formHtml += `<div class="col-span-2" style="font-size:var(--type-xs);font-weight:600;color:var(--amber);margin-top:4px">${t('kart.groupIdentity')}</div>`;
    for (const fi of _kf.filter(f=>f.g==='id')) {
      const ro = fi.k==='id' && !ed._isNew;
      formHtml += `<div><label class="field-label">${fi.l} <span style="color:var(--muted-light);font-weight:400;font-size:var(--type-2xs)" title="${escHtml(fi.tip)}">(?)</span></label>
        <input type="${fi.t}" class="input input-sm w-full input-bordered" value="${escHtml(String(ed[fi.k]??''))}"${ro?' readonly style="opacity:0.5"':''} onchange="window._kart._fieldChange('${fi.k}',this.value)"></div>`;
    }
    formHtml += autoSaveNote;
    // Group: Physics
    formHtml += `<div class="col-span-2" style="font-size:var(--type-xs);font-weight:600;color:var(--cyan);margin-top:8px">${t('kart.groupPhysics')}</div>`;
    for (const fi of _kf.filter(f=>f.g==='physics')) {
      formHtml += `<div><label class="field-label">${fi.l} <span style="color:var(--muted-light);font-weight:400;font-size:var(--type-2xs)" title="${escHtml(fi.tip)}">(?)</span></label>
        <input type="number" class="input input-sm w-full input-bordered" value="${ed[fi.k]??0}" step="${fi.s||0.01}" onchange="window._kart._fieldChange('${fi.k}',parseFloat(this.value))" oninput="window._kart._fieldChange('${fi.k}',parseFloat(this.value))"></div>`;
    }
    // Group: Flags
    formHtml += `<div class="col-span-2" style="font-size:var(--type-xs);font-weight:600;color:var(--green);margin-top:8px">${t('common.options')}</div>`;
    for (const fi of _kf.filter(f=>f.g==='flags')) {
      const tid = 'kart-tg-' + fi.k;
      const isOn = !!ed[fi.k];
      formHtml += `<div class="flex items-center justify-between gap-2 cursor-pointer" style="padding:6px 8px;border-radius:6px;background:var(--bg-elevated)">
        <span style="font-size:var(--type-sm);color:var(--text-primary)">${fi.l} <span style="color:var(--muted-light);font-weight:400;font-size:var(--type-2xs)" title="${escHtml(fi.tip)}">(?)</span></span>
        <input type="checkbox" id="${tid}" class="toggle toggle-sm" ${isOn?'checked':''} onclick="window._kart._toggleField('${fi.k}')">
      </div>`;
    }
    // Group: Extra
    formHtml += `<div class="col-span-2" style="font-size:var(--type-xs);font-weight:600;color:var(--muted-light);margin-top:8px">${t('kart.groupExtra')}</div>`;
    for (const fi of _kf.filter(f=>f.g==='extra')) {
      formHtml += `<div class="col-span-2"><label class="field-label">${fi.l} <span style="color:var(--muted-light);font-weight:400;font-size:var(--type-2xs)" title="${escHtml(fi.tip)}">(?)</span></label>
        <input type="${fi.t}" class="input input-sm w-full input-bordered" value="${escHtml(String(ed[fi.k]??''))}"${fi.dl?' list="'+fi.dl+'"':''} onchange="window._kart._fieldChange('${fi.k}',this.value)"></div>`;
    }
  }
  const dlg = ed ? `<dialog id="kart-edit-dlg" class="modal" open>
    <div class="modal-box bg-elevated-box" style="max-width:640px">
      <div class="flex items-center justify-between mb-4">
        <h3 class="font-bold text-lg">${ed._isNew ? t('common.create') : t('common.edit') + ': ' + escHtml(ed.name)}</h3>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._kart.closeEdit()" style="font-size:var(--type-lg)">&times;</button>
      </div>
      <div class="grid grid-cols-2 gap-x-4 gap-y-2">${formHtml}</div>
      <div class="mt-5 flex justify-end gap-2">
        <button class="btn-cg btn-cg-ghost" onclick="window._kart.closeEdit()">${t('common.close')}</button>
        ${ed._isNew ? `<button class="btn-cg btn-cg-primary" onclick="window._kart.savePreset()">${t('common.create')}</button>` : ''}
      </div>
    </div>
    <form method="dialog" class="modal-backdrop"><button onclick="window._kart.closeEdit()">close</button></form>
  </dialog>` : '';

  return `<div class="max-w-6xl mx-auto slide-in">
    <div class="flex items-center gap-3 mb-6">
      <span style="font-size:var(--type-2xl)">🏎</span>
      <div>
        <h1 style="font-size:var(--type-xl);font-weight:700">CheeseKart</h1>
        <p style="color:var(--muted);font-size:var(--type-sm)">${t('kart.subtitle')}</p>
      </div>
    </div>
    ${tabs}
    ${content}
    ${dlg}
  </div>`;
}
