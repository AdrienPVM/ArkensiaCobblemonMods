// ── Dimensions ───────────────────────────────────────────────────────────────

async function buildDimensions(app) {
  let dims, genTypes;
  try {
    dims = await API.dimensions.list();
    try { genTypes = await API.dimensions.genTypes(); } catch (_) { genTypes = []; }
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const _d = window._dv = {
    dims, genTypes, app,
    search: '', typeFilter: '',

    getFiltered() {
      return this.dims.filter(d => {
        if (this.search) {
          const q = this.search.toLowerCase();
          if (!(d.name || '').toLowerCase().includes(q) && !(d.id || '').toLowerCase().includes(q)) return false;
        }
        if (this.typeFilter) {
          const effective = d.customGenTypeId || d.genType;
          if (effective !== this.typeFilter) return false;
        }
        return true;
      });
    },

    renderTable() {
      const filtered = this.getFiltered();
      const tbody = document.getElementById('dims-tbody');
      if (tbody) tbody.innerHTML = filtered.map(d => dimRowHtml(d)).join('');
      const cnt = document.getElementById('dims-count');
      if (cnt) cnt.textContent = filtered.length;
    },

    // ── WS update handler ──
    onWsDimUpdate(msg) {
      // Refresh the list when a dim update comes in
      API.dimensions.list().then(d => { this.dims = d; this.renderTable(); }).catch(() => {});
    },

    async confirmCreate() {
      const name = document.getElementById('dim-create-name').value.trim();
      const type = document.getElementById('dim-create-type').value;
      const seedStr = document.getElementById('dim-create-seed').value.trim();
      if (!name) { app.toast(t('dims.nameRequired'), 'error'); return; }
      const isPlugin = type.includes(':');
      const data = { name };
      if (isPlugin) data.customGenTypeId = type;
      else data.genType = type;
      if (seedStr) data.seed = parseInt(seedStr) || 0;
      const flatInp = document.getElementById('dim-create-flat');
      if (type === 'FLAT' && flatInp && flatInp.value.trim()) {
        data.flatLayers = flatInp.value.trim();
      }
      // Options from step 3
      data.pvp = !!document.getElementById('dcw-pvp')?.checked;
      data.mobSpawning = !!document.getElementById('dcw-mobs')?.checked;
      data.keepInventory = !!document.getElementById('dcw-keepinv')?.checked;
      data.generateStructures = !!document.getElementById('dcw-structures')?.checked;
      data.perPlayerMode = !!document.getElementById('dcw-perplayer')?.checked;
      data.constructionMode = !!document.getElementById('dcw-construction')?.checked;
      data.timeLocked = !!document.getElementById('dcw-timelock')?.checked;
      if (data.timeLocked) {
        data.lockedTime = parseInt(document.getElementById('dcw-lockedtime')?.value) || 6000;
      }
      data.weatherLocked = !!document.getElementById('dcw-weatherlock')?.checked;
      data.raidDensEnabled = !!document.getElementById('dcw-raiddens')?.checked;
      const diff = document.getElementById('dcw-difficulty')?.value;
      if (diff && diff !== 'default') data.difficulty = diff;
      try {
        const created = await API.dimensions.create(data);
        app.toast(t('dims.created'), 'success');
        // Navigate to the new dimension detail
        if (created && created.id) app.navigate('dimensions:' + created.id);
        else app.navigate('dimensions');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  const rows = dims.map(d => dimRowHtml(d)).join('');

  return '\
<div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">\
  <div class="section-card">\
    <div class="section-card-header">\
      <span class="text-sm">\uD83C\uDF00</span>\
      <span class="section-card-title">' + t('dims.title') + '</span>\
      <span class="ml-auto id-badge" id="dims-count">' + dims.length + '</span>\
    </div>\
    <div class="filter-bar">\
      <input type="text" placeholder="' + t('common.search') + '"\
        class="input input-sm input-bordered text-sm max-w-xs"\
        oninput="_dv.search=this.value;_dv.renderTable()">\
      <select class="select select-sm select-bordered text-sm"\
        onchange="_dv.typeFilter=this.value;_dv.renderTable()">\
        <option value="">' + t('common.all') + '</option>' + (genTypes || []).map(function(gt){ return '<option value="' + escHtml(gt.id) + '">' + escHtml(gt.label || gt.id) + '</option>'; }).join('') + '\
      </select>\
      <div class="flex-1"></div>\
      <button class="btn-cg btn-cg-primary" onclick="_nav(\'dimensions:new\')">' + t('dims.newDim') + '</button>\
    </div>\
    <div class="overflow-x-auto">\
      <table class="cg-table">\
        <thead><tr>\
          <th>' + t('common.id') + '</th><th>' + t('common.name') + '</th><th>' + t('common.type') + '</th><th>' + t('common.players') + '</th><th>' + t('dims.snapshot') + '</th><th>' + t('dims.perPlayer') + '</th><th>' + t('common.options') + '</th><th></th>\
        </tr></thead>\
        <tbody id="dims-tbody">' + rows + '</tbody>\
      </table>\
    </div>\
    ' + (dims.length === 0 ? '<div class="empty-state py-12"><span class="empty-state-icon">\uD83C\uDF00</span><span class="empty-state-text">' + t('dims.noDims') + '</span></div>' : '') + '\
  </div>\
</div>';
}

// ── Dimension helpers ────────────────────────────────────────────────────────

function dimRowHtml(d) {
  const typeBadgeCls = {
    'VOID': 'text-muted-light', 'FLAT': 'text-cyan', 'NORMAL': 'text-green',
    'NETHER': 'text-red', 'END': 'text-purple-400'
  };
  const tCls = d.customGenTypeId ? 'text-amber' : (typeBadgeCls[d.genType] || 'text-muted');
  const typeLabel = d.customGenTypeLabel || d.genType;
  const ppBadge = d.perPlayerMode
    ? '<span class="id-badge text-cyan">' + t('common.on') + '</span>'
    : '<span class="id-badge text-muted">' + t('common.off') + '</span>';
  const snapTxt = d.snapshotEnabled ? fmtSize(d.snapshotSizeKb) : '\u2014';
  const optIcons = [];
  if (d.pvp) optIcons.push('\u2694');
  if (d.mobSpawning) optIcons.push('\uD83D\uDC7E');
  if (d.keepInventory) optIcons.push('\uD83C\uDF92');
  if (d.constructionMode) optIcons.push('\uD83D\uDEA7');
  const nav = "_nav('dimensions:" + escHtml(d.id) + "')";
  return '<tr class="cursor-pointer" onclick="' + nav + '"' + (d.builtIn ? ' style="opacity:0.7"' : '') + '>\
    <td><span class="id-badge">' + escHtml(d.id) + '</span></td>\
    <td style="color:var(--text-primary)" class="text-sm">' + escHtml(d.displayName) + '</td>\
    <td><span class="id-badge ' + tCls + '">' + escHtml(typeLabel) + '</span></td>\
    <td class="td-mono">' + d.playerCount + '</td>\
    <td class="td-mono">' + snapTxt + '</td>\
    <td>' + ppBadge + '</td>\
    <td class="text-sm">' + (optIcons.length ? optIcons.join(' ') : '<span class="text-muted">\u2014</span>') + '</td>\
    <td class="text-right flex gap-1 justify-end">\
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="event.stopPropagation();_tpAdmin({dimension:\'' + escHtml(d.worldId) + '\'})" title="' + t('common.teleport') + '">TP</button>\
      <button class="btn-cg btn-cg-ghost" onclick="event.stopPropagation();' + nav + '">' + t('common.details') + '</button>\
    </td>\
  </tr>';
}

// ── Dimension creation wizard (multi-step) ──────────────────────────────────
var _dcwStep = 1;

function dcwRandomSeed() {
  const input = document.getElementById('dim-create-seed');
  if (!input) return;
  const seed = Math.floor(Math.random() * Number.MAX_SAFE_INTEGER);
  input.value = seed;
}

function dcwSelectType(btn, type) {
  document.querySelectorAll('.dcw-type-btn').forEach(b => {
    b.style.borderColor = 'var(--bg-border)';
    b.style.background = 'var(--bg-elevated)';
  });
  btn.style.borderColor = 'var(--amber)';
  btn.style.background = 'rgba(245,166,35,0.1)';
  document.getElementById('dim-create-type').value = type;
}

function dcwGoTo(step) {
  _dcwStep = step;
  for (let i = 1; i <= 4; i++) {
    const page = document.getElementById('dcw-' + i);
    if (page) page.style.display = i === step ? '' : 'none';
  }
  // Update step indicators
  document.querySelectorAll('.dcw-step').forEach(el => {
    const s = parseInt(el.dataset.step);
    el.classList.toggle('active', s === step);
    el.style.color = s <= step ? 'var(--amber)' : 'var(--text-muted)';
  });
  // Prev button
  const prev = document.getElementById('dcw-prev');
  if (prev) prev.style.display = step > 1 ? '' : 'none';
  // Next button text
  const next = document.getElementById('dcw-next');
  if (next) next.textContent = step === 4 ? t('common.create') : t('common.confirm');

  // Show/hide world step fields based on type
  if (step === 2) {
    const type = document.getElementById('dim-create-type').value;
    const seedRow = document.getElementById('dcw-seed-row');
    const flatRow = document.getElementById('dcw-flat-row');
    const voidHint = document.getElementById('dcw-void-hint');
    if (seedRow) seedRow.style.display = (type !== 'VOID' && type !== 'FLAT') ? '' : 'none';
    if (flatRow) flatRow.style.display = type === 'FLAT' ? '' : 'none';
    if (voidHint) voidHint.style.display = type === 'VOID' ? '' : 'none';
  }

  // Show/hide timelock-row based on checkbox
  if (step === 3) {
    const tlCb = document.getElementById('dcw-timelock');
    const tlRow = document.getElementById('dcw-timelock-row');
    if (tlCb && tlRow) {
      tlRow.style.display = tlCb.checked ? '' : 'none';
      tlCb.onchange = () => { tlRow.style.display = tlCb.checked ? '' : 'none'; };
    }
  }

  // Build summary on step 4
  if (step === 4) {
    const name = document.getElementById('dim-create-name').value.trim() || t('common.unknown');
    const type = document.getElementById('dim-create-type').value;
    const seed = document.getElementById('dim-create-seed').value.trim();
    const flat = document.getElementById('dim-create-flat')?.value.trim() || '';
    const diff = document.getElementById('dcw-difficulty').value;
    let html = `<div><strong>${t('common.name')} :</strong> ${escHtml(name)}</div>`;
    html += `<div><strong>${t('common.type')} :</strong> ${escHtml(type)}</div>`;
    if (seed) html += `<div><strong>${t('dims.seed')} :</strong> ${escHtml(seed)}</div>`;
    if (flat) html += `<div><strong>${t('dims.flatLayers')} :</strong> ${escHtml(flat)}</div>`;
    html += `<div><strong>${t('dims.difficulty')} :</strong> ${escHtml(diff)}</div>`;
    const opts = [];
    if (document.getElementById('dcw-pvp')?.checked) opts.push(t('dims.toggle.pvp'));
    if (document.getElementById('dcw-mobs')?.checked) opts.push(t('dims.toggle.mobs'));
    if (document.getElementById('dcw-keepinv')?.checked) opts.push(t('dims.toggle.keepInventory'));
    if (document.getElementById('dcw-structures')?.checked) opts.push(t('dims.toggle.structures'));
    if (document.getElementById('dcw-perplayer')?.checked) opts.push(t('dims.toggle.perPlayer'));
    if (document.getElementById('dcw-construction')?.checked) opts.push(t('dims.toggle.construction'));
    if (document.getElementById('dcw-timelock')?.checked) {
      const lt = document.getElementById('dcw-lockedtime')?.value || '6000';
      opts.push(t('dims.timeLocked') + ' (' + lt + ')');
    }
    if (document.getElementById('dcw-weatherlock')?.checked) opts.push(t('dims.weatherLocked'));
    if (!document.getElementById('dcw-raiddens')?.checked) opts.push(t('dims.toggle.raidDens') + ' OFF');
    html += `<div><strong>${t('common.options')} :</strong> ${opts.length ? opts.join(', ') : t('common.none')}</div>`;
    document.getElementById('dcw-summary').innerHTML = html;
  }
}

function dcwNext() {
  if (_dcwStep === 1) {
    const name = document.getElementById('dim-create-name').value.trim();
    if (!name) { window._dv?.app.toast(t('dims.nameRequired'), 'error'); return; }
  }
  if (_dcwStep === 4) {
    // Final: create
    window._dv?.confirmCreate();
    return;
  }
  dcwGoTo(_dcwStep + 1);
}

function dcwPrev() {
  if (_dcwStep > 1) dcwGoTo(_dcwStep - 1);
}

// ── Dimension Create — full page wizard ──────────────────────────────────────
async function buildDimensionCreate(app) {
  // Store app ref for wizard helpers
  window._dcwApp = app;

  const builtinDescs = {VOID:t('common.none'),FLAT:t('dims.flatLayers'),NORMAL:t('common.general'),NETHER:t('dims.typeNether'),END:t('dims.typeEnd')};
  const builtinIcons = {VOID:'\u2B1B',FLAT:'\u2B1C',NORMAL:'\uD83C\uDF0D',NETHER:'\uD83D\uDD25',END:'\uD83C\uDF0C'};
  let genTypesList = (window._dv && window._dv.genTypes) || null;
  if (!genTypesList) {
    try { genTypesList = await API.dimensions.genTypes(); }
    catch (_) { genTypesList = []; }
  }
  const typeCards = genTypesList.map(gt => {
    const isPlugin = gt.kind === 'plugin';
    const icon = (!isPlugin && builtinIcons[gt.id]) || gt.icon || '\u2728';
    const label = isPlugin ? (gt.label || gt.id) : gt.id;
    const desc = isPlugin ? (gt.id) : (builtinDescs[gt.id] || '');
    const selected = gt.id === 'VOID';
    return `<button class="p-3 rounded border text-center text-xs font-mono transition-colors dcw-type-btn"
      style="border-color:${selected ? 'var(--amber)' : 'var(--bg-border)'};background:${selected ? 'rgba(245,166,35,0.1)' : 'var(--bg-elevated)'};color:var(--text-secondary)"
      onclick="dcwSelectType(this,'${escHtml(gt.id)}')" data-type="${escHtml(gt.id)}">
      <div class="text-lg mb-1">${escHtml(icon)}</div>
      <div>${escHtml(label)}</div>
      <div class="text-sm" style="color:var(--text-muted)">${escHtml(desc)}</div>
    </button>`;
  }).join('');

  setTimeout(() => dcwGoTo(1), 10);

  return `
    <div class="max-w-2xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <div class="section-card">
        <div class="detail-header">
          ${detailBackBtn('dimensions', t('dims.title'))}
          <span class="section-card-title">${t('dims.newDim')}</span>
        </div>

        <div class="section-card-body">
          <div id="dcw-steps" class="flex gap-1 mb-6">
            <span class="dcw-step active" data-step="1">1. ${t('common.type')}</span>
            <span class="dcw-step" data-step="2">2. ${t('common.world')}</span>
            <span class="dcw-step" data-step="3">3. ${t('common.options')}</span>
            <span class="dcw-step" data-step="4">4. ${t('common.create')}</span>
          </div>

          <div id="dcw-1" class="dcw-page">
            <div class="space-y-4">
              <div class="field-group">
                <label class="field-label">${t('common.name')}</label>
                <input type="text" id="dim-create-name" placeholder="${t('dims.dimNamePlaceholder')}"
                  class="input input-sm w-full input-bordered">
              </div>
              <div class="field-group">
                <label class="field-label">${t('dims.genType')} ${tip('tip.dims.genType')}</label>
                <div class="grid grid-cols-3 gap-2 mt-1">
                  ${typeCards}
                </div>
                <input type="hidden" id="dim-create-type" value="VOID">
              </div>
            </div>
          </div>

          <div id="dcw-2" class="dcw-page" style="display:none">
            <div class="space-y-4">
              <div class="field-group" id="dcw-seed-row">
                <label class="field-label">${t('dims.seed')} ${tip('tip.dims.seed')}</label>
                <div class="flex gap-2">
                  <input type="number" id="dim-create-seed" placeholder="${t('dims.seedPlaceholder')}"
                    class="input input-sm input-bordered flex-1">
                  <button type="button" class="btn-cg btn-cg-ghost btn-cg-sm" onclick="dcwRandomSeed()" title="${t('dims.seedRandom')}">\uD83C\uDFB2 ${t('dims.seedRandom')}</button>
                </div>
                <span class="field-help">${t('dims.seedPlaceholder')}</span>
              </div>
              <div class="field-group" id="dcw-flat-row" style="display:none">
                <label class="field-label">${t('dims.flatLayers')} ${tip('tip.dims.flatLayers')}</label>
                <input type="text" id="dim-create-flat" placeholder="${t('dims.flatPlaceholder')}"
                  class="input input-sm w-full input-bordered">
                <span class="field-help">${t('dims.flatPlaceholder')}</span>
              </div>
              <div class="field-group" id="dcw-void-hint" style="display:none">
                <div class="p-3 rounded" style="background:var(--bg-elevated)">
                  <span class="text-sm" style="color:var(--text-muted)">${t('tip.dims.genType')}</span>
                </div>
              </div>
            </div>
          </div>

          <div id="dcw-3" class="dcw-page" style="display:none">
            <div class="space-y-4">
              <p class="text-sm" style="color:var(--text-muted)">${t('common.options')}</p>
              <div class="grid grid-cols-2 gap-2" id="dcw-opts">
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-pvp"> \u2694 ${t('dims.pvp')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-mobs" checked> \uD83D\uDC7E ${t('dims.mobSpawning')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-keepinv"> \uD83C\uDF92 ${t('dims.keepInventory')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-structures"> \uD83C\uDFDB ${t('dims.structures')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-perplayer"> \uD83D\uDC64 ${t('dims.perPlayerMode')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-construction"> \uD83D\uDEA7 ${t('dims.constructionMode')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-timelock"> \u23F0 ${t('dims.timeLocked')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-weatherlock"> \u26C5 ${t('dims.weatherLocked')}
                </label>
                <label class="flex items-center gap-2 p-2 rounded border text-sm font-mono" style="border-color:var(--bg-border);background:var(--bg-elevated);color:var(--text-secondary)">
                  <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" id="dcw-raiddens" checked> \uD83D\uDC32 ${t('dims.toggle.raidDens')}
                </label>
              </div>
              <div id="dcw-timelock-row" class="field-group" style="display:none">
                <label class="field-label">${t('dims.lockedTime')} ${tip('tip.dims.lockedTime')}</label>
                <input type="number" id="dcw-lockedtime" value="6000" min="0" max="23999"
                  class="input input-sm w-full input-bordered">
                <span class="field-help">${t('tip.dims.timeValues')}</span>
              </div>
              <div class="field-group">
                <label class="field-label">${t('dims.difficulty')} ${tip('tip.dims.difficulty')}</label>
                <select id="dcw-difficulty" class="select select-sm w-full select-bordered">
                  <option value="default">${t('dims.diff.default')}</option>
                  <option value="peaceful">${t('dims.diff.peaceful')}</option>
                  <option value="easy">${t('dims.diff.easy')}</option>
                  <option value="normal" selected>${t('dims.diff.normal')}</option>
                  <option value="hard">${t('dims.diff.hard')}</option>
                </select>
              </div>
            </div>
          </div>

          <div id="dcw-4" class="dcw-page" style="display:none">
            <div class="p-4 rounded border" style="border-color:var(--bg-border);background:var(--bg-elevated)">
              <div class="text-sm font-mono tracking-wider uppercase mb-3" style="color:var(--text-muted)">${t('common.details')}</div>
              <div id="dcw-summary" class="space-y-2 text-sm" style="color:var(--text-primary)"></div>
            </div>
          </div>

          <div class="flex justify-between mt-6 pt-4" style="border-top:1px solid var(--bg-border)">
            <button class="btn-cg btn-cg-ghost" id="dcw-prev" onclick="dcwPrev()" style="display:none">${t('common.back')}</button>
            <div class="flex-1"></div>
            <div class="flex gap-2">
              <button class="btn-cg btn-cg-ghost" onclick="_nav('dimensions')">${t('common.cancel')}</button>
              <button class="btn-cg btn-cg-primary" id="dcw-next" onclick="dcwNext()">${t('common.confirm')}</button>
            </div>
          </div>
        </div>
      </div>
    </div>`;
}

// ── Dimension Detail ─────────────────────────────────────────────────────────

function ddSwitchTab(tabId, btn) { switchDetailTab(tabId, btn, 'dd'); if (tabId === 'gestion' && window._dd) window._dd.loadPlayerDims(); }

async function buildDimensionDetail(app, dimId) {
  let dim;
  try {
    dim = await API.dimensions.get(dimId);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  if (app.breadcrumbs.length >= 2) app.breadcrumbs[1].label = dim.displayName || dimId;

  const _dd = window._dd = {
    dim, app, dirty: false,
    battleLossCmds: [...(dim.commandsOnBattleLoss || [])],
    leaveCmds: [...(dim.commandsOnLeave || [])],
    edit: {
      displayName: dim.displayName, spawnX: dim.spawnX, spawnY: dim.spawnY, spawnZ: dim.spawnZ,
      difficulty: dim.difficulty, lockedTime: dim.lockedTime, lockedWeather: dim.lockedWeather || '',
      enabledMessage: dim.enabledMessage || '',
    },

    markDirty() { this.dirty = true; const el = document.getElementById('dd-dirty'); if (el) el.style.display = ''; this._debouncedSave(); },

    _debouncedSave: debounce(function() { if (window._dd) window._dd.save(); }, 1500),

    async save() {
      try {
        await API.dimensions.update(dimId, this.edit);
        app.toast(t('common.success'), 'success');
        this.dirty = false;
        const el = document.getElementById('dd-dirty'); if (el) el.style.display = 'none';
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async grabPlayerPos() {
      try {
        const pos = await API.admin.getPosition();
        if (!pos.online) { app.toast(t('error.network'), 'warn'); return; }
        this.edit.spawnX = pos.x; this.edit.spawnY = pos.y; this.edit.spawnZ = pos.z;
        const ix = document.getElementById('dd-spawn-x'); if (ix) ix.value = pos.x;
        const iy = document.getElementById('dd-spawn-y'); if (iy) iy.value = pos.y;
        const iz = document.getElementById('dd-spawn-z'); if (iz) iz.value = pos.z;
        this.markDirty();
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(t('common.error'), 'error'); }
    },

    async toggle(key) {
      try {
        const updated = await API.dimensions.toggle(dimId, key);
        app.toast(t('dims.toggled', {key}), 'success');
        this.dim = updated;
        ddRenderToggles(updated);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async toggleBorder() {
      try {
        const updated = await API.dimensions.toggle(dimId, 'borderEnabled');
        app.toast(t('common.success'), 'success');
        this.dim = updated;
        ddRenderBorderTab(updated);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async toggleFlag(flagId) {
      try {
        const updated = await API.dimensions.toggle(dimId, 'flag.' + flagId);
        app.toast(t('common.success'), 'success');
        this.dim = updated;
        ddRenderFlagsTab(updated);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    _debouncedBorderSave: debounce(function() { if (window._dd) window._dd.saveBorder(); }, 800),

    async saveBorder() {
      const cx = parseFloat(document.getElementById('dd-bd-cx')?.value) || 0;
      const cz = parseFloat(document.getElementById('dd-bd-cz')?.value) || 0;
      const size = Math.max(1, parseFloat(document.getElementById('dd-bd-size')?.value) || 1000);
      const damage = Math.max(0, parseFloat(document.getElementById('dd-bd-damage')?.value) || 0);
      const warn = Math.max(0, parseInt(document.getElementById('dd-bd-warn')?.value) || 0);
      const warnTime = Math.max(0, parseInt(document.getElementById('dd-bd-warntime')?.value) || 0);
      try {
        const updated = await API.dimensions.update(dimId, {
          borderCenterX: cx, borderCenterZ: cz,
          borderSize: size, borderDamage: damage,
          borderWarningBlocks: warn, borderWarningTime: warnTime,
        });
        app.toast(t('common.success'), 'success');
        if (updated) this.dim = updated;
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async grabBorderCenter() {
      try {
        const pos = await API.admin.getPosition();
        if (!pos.online) { app.toast(t('error.network'), 'warn'); return; }
        const ix = document.getElementById('dd-bd-cx'); if (ix) ix.value = Math.round(pos.x);
        const iz = document.getElementById('dd-bd-cz'); if (iz) iz.value = Math.round(pos.z);
        this.saveBorder();
      } catch (e) { app.toast(t('common.error'), 'error'); }
    },

    async createSnapshot() {
      try {
        const res = await API.dimensions.snapshot(dimId);
        app.toast(t('common.success'), 'success');
        this.dim.snapshotEnabled = true;
        this.dim.snapshotSizeKb = res.sizeKb;
        ddRenderSnapshotTab(this.dim);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async resetFromSnapshot() {
      if (!await confirmAction('' + t('common.deleteConfirm') + '')) return;
      try {
        const res = await API.dimensions.reset(dimId);
        if (res.status === 'ok') app.toast(t('common.success'), 'success');
        else if (res.status === 'pending') app.toast(t('common.success'), 'warn');
        else if (res.status === 'no_snapshot') app.toast(t('common.error'), 'error');
        else app.toast(t('error.prefix') + res.status, 'error');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    addBattleLossCmd() {
      const inp = document.getElementById('dd-inp-cmdBattleLoss');
      if (!inp || !inp.value.trim()) return;
      this.battleLossCmds.push(inp.value.trim());
      inp.value = '';
      this._saveBattleLossCmds();
      this._renderBattleLossCmds();
    },
    removeBattleLossCmd(idx) {
      this.battleLossCmds.splice(idx, 1);
      this._saveBattleLossCmds();
      this._renderBattleLossCmds();
    },
    async _saveBattleLossCmds() {
      try {
        await API.dimensions.update(dimId, { commandsOnBattleLoss: this.battleLossCmds });
      } catch (e) { app.toast(e.message, 'error'); }
    },
    _renderBattleLossCmds() {
      const el = document.getElementById('dd-list-commandsOnBattleLoss');
      if (!el) return;
      if (this.battleLossCmds.length === 0) { el.innerHTML = ''; return; }
      el.innerHTML = this.battleLossCmds.map((c, i) =>
        `<div class="flex items-center gap-2 mb-1"><code class="text-xs flex-1 mono" style="color:var(--muted-light)">${escHtml(c)}</code>` +
        `<button class="btn-cg-sm" style="padding:0 4px" onclick="_dd.removeBattleLossCmd(${i})">\u00d7</button></div>`
      ).join('');
    },

    addLeaveCmd() {
      const inp = document.getElementById('dd-inp-cmdLeave');
      if (!inp || !inp.value.trim()) return;
      this.leaveCmds.push(inp.value.trim());
      inp.value = '';
      this._saveLeaveCmds();
      this._renderLeaveCmds();
    },
    removeLeaveCmd(idx) {
      this.leaveCmds.splice(idx, 1);
      this._saveLeaveCmds();
      this._renderLeaveCmds();
    },
    async _saveLeaveCmds() {
      try {
        await API.dimensions.update(dimId, { commandsOnLeave: this.leaveCmds });
      } catch (e) { app.toast(e.message, 'error'); }
    },
    _renderLeaveCmds() {
      const el = document.getElementById('dd-list-commandsOnLeave');
      if (!el) return;
      if (this.leaveCmds.length === 0) { el.innerHTML = ''; return; }
      el.innerHTML = this.leaveCmds.map((c, i) =>
        `<div class="flex items-center gap-2 mb-1"><code class="text-xs flex-1 mono" style="color:var(--muted-light)">${escHtml(c)}</code>` +
        `<button class="btn-cg-sm" style="padding:0 4px" onclick="_dd.removeLeaveCmd(${i})">\u00d7</button></div>`
      ).join('');
    },

    _debouncedSnapSave: debounce(function() { if (window._dd) window._dd.saveSnapshotConfig(); }, 1500),

    async saveSnapshotConfig() {
      const sel = document.getElementById('dd-snap-trigger');
      const hourInp = document.getElementById('dd-snap-hour');
      if (!sel) return;
      try {
        const data = { trigger: sel.value };
        if (sel.value === 'SCHEDULED' && hourInp) data.hour = parseInt(hourInp.value) || 4;
        await API.dimensions.snapshotConfig(dimId, data);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async loadPlayerDims() {
      if (!this.dim.perPlayerMode) {
        const el = document.querySelector('[data-dd-section="perplayer"]');
        if (el) el.innerHTML = '<div class="empty-state py-8"><span class="empty-state-icon">\uD83D\uDC64</span><span class="empty-state-text">' + t('common.disabled') + '</span></div>';
        return;
      }
      try {
        const players = await API.dimensions.playerDims(dimId);
        ddRenderPlayerTab(players);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deletePlayerDim(uuid8) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.dimensions.deletePlayer(dimId, uuid8);
        app.toast(t('dims.deleted'), 'success');
        this.loadPlayerDims();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async resetPlayerDim(uuid8) {
      try {
        await API.dimensions.resetPlayer(dimId, uuid8);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async addWhitelist() {
      const inp = document.getElementById('dd-wl-input');
      if (!inp || !inp.value.trim()) return;
      try {
        const res = await API.dimensions.addWhitelist(dimId, { playerName: inp.value.trim() });
        app.toast(t('common.success'), 'success');
        inp.value = '';
        const d = await API.dimensions.get(dimId);
        this.dim = d;
        ddRenderAccessTab(d);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async removeWhitelist(uuid) {
      try {
        await API.dimensions.removeWhitelist(dimId, uuid);
        app.toast(t('common.success'), 'success');
        const d = await API.dimensions.get(dimId);
        this.dim = d;
        ddRenderAccessTab(d);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async addBuilder() {
      const inp = document.getElementById('dd-builder-input');
      if (!inp || !inp.value.trim()) return;
      try {
        const res = await API.dimensions.addBuilder(dimId, { playerName: inp.value.trim() });
        app.toast(t('common.success'), 'success');
        inp.value = '';
        const d = await API.dimensions.get(dimId);
        this.dim = d;
        ddRenderAccessTab(d);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async removeBuilder(uuid) {
      try {
        await API.dimensions.removeBuilder(dimId, uuid);
        app.toast(t('common.success'), 'success');
        const d = await API.dimensions.get(dimId);
        this.dim = d;
        ddRenderAccessTab(d);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteDimension() {
      const inp = document.getElementById('dd-del-input');
      if (!inp || inp.value !== dim.displayName) { app.toast(t('dims.nameNotMatch'), 'error'); return; }
      try {
        await API.dimensions.delete(dimId);
        app.toast(t('dims.deleted'), 'success');
        app.navigate('dimensions');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  // ── Type badge color
  const typeCls = { 'VOID': 'text-muted-light', 'FLAT': 'text-cyan', 'NORMAL': 'text-green', 'NETHER': 'text-red', 'END': 'text-purple-400' };
  const tCls = dim.customGenTypeId ? 'text-amber' : (typeCls[dim.genType] || 'text-muted');
  const dimTypeLabel = dim.customGenTypeLabel || dim.genType;

  // ── Difficulty options
  const diffLabels = {default:t('dims.diff.default'),peaceful:t('dims.diff.peaceful'),easy:t('dims.diff.easy'),normal:t('dims.diff.normal'),hard:t('dims.diff.hard')};
  const diffOpts = ['default','peaceful','easy','normal','hard'].map(d =>
    `<option value="${d}"${dim.difficulty === d ? ' selected' : ''}>${diffLabels[d]}</option>`
  ).join('');

  // ── Weather options
  const weatherOpts = [['',t('common.none')],['clear',t('dims.weather.clear')],['rain',t('dims.weather.rain')],['thunder',t('dims.weather.thunder')]].map(([v,l]) =>
    `<option value="${v}"${(dim.lockedWeather||'') === v ? ' selected' : ''}>${l}</option>`
  ).join('');

  // ── Pre-build toggles HTML
  const togglesDefs = [
    { key: 'enabled',           label: t('dims.toggle.enabled'),         icon: '\uD83D\uDD13' },
    { key: 'pvp',               label: t('dims.toggle.pvp'),             icon: '\u2694' },
    { key: 'mobSpawning',       label: t('dims.toggle.mobs'),            icon: '\uD83D\uDC7E' },
    { key: 'keepInventory',     label: t('dims.toggle.keepInventory'),   icon: '\uD83C\uDF92' },
    { key: 'clearInventory',    label: t('dims.toggle.clearInventory'),  icon: '\uD83E\uDDF9' },
    { key: 'commandBlocks',     label: t('dims.toggle.commandBlocks'),   icon: '\u2699' },
    { key: 'enderChest',        label: t('dims.toggle.enderChest'),      icon: '\uD83D\uDCE6' },
    { key: 'generateStructures',label: t('dims.toggle.structures'),      icon: '\uD83C\uDFDB' },
    { key: 'cobblemonBlocked',  label: t('dims.cobblemonBlocked'),       icon: '\uD83D\uDEAB' },
    { key: 'raidDensEnabled',   label: t('dims.toggle.raidDens'),        icon: '\uD83D\uDC32' },
    { key: 'allowHome',         label: t('dims.toggle.allowHome'),       icon: '\uD83C\uDFE0' },
    { key: 'constructionMode',  label: t('dims.toggle.construction'),    icon: '\uD83D\uDEA7' },
    { key: 'perPlayerMode',     label: t('dims.toggle.perPlayer'),       icon: '\uD83D\uDC64' },
    { key: 'timeLocked',        label: t('dims.timeLocked'),             icon: '\u23F0' },
    { key: 'weatherLocked',     label: t('dims.weatherLocked'),          icon: '\u2601' },
  ];
  const togglesHtml = ddBuildTogglesHtml(dim, togglesDefs);
  const snapshotHtml = ddBuildSnapshotHtml(dim);
  const accessHtml = ddBuildAccessHtml(dim);
  const ppHtml = dim.perPlayerMode
    ? `<div class="text-sm text-center py-4" style="color:var(--text-muted)">${t('common.loading')}</div>`
    : `<div class="text-sm py-4" style="color:var(--text-muted)">' + t('common.disabled') + '</div>`;

  // Tabs: Configuration | Border | Flags | Gestion
  const tabs = [
    { id: 'config', label: t('dims.tabs.config') },
    { id: 'border', label: t('dims.tabs.border') },
    { id: 'flags', label: t('dims.tabs.flags') },
    { id: 'gestion', label: t('dims.tabs.gestion') },
  ];

  const borderHtml = ddBuildBorderHtml(dim);
  const flagsHtml = ddBuildFlagsHtml(dim);

  setTimeout(() => { if (window._dd) { window._dd._renderBattleLossCmds(); window._dd._renderLeaveCmds(); } }, 80);

  return `<div style="animation:slide-in-right 0.2s ease-out">
    <div class="section-card">
      <div class="detail-header">
        ${detailBackBtn('dimensions', t('dims.title'))}
        <div class="flex items-center gap-3 flex-1">
          <span class="text-sm font-semibold" style="color:var(--text-heading)">${escHtml(dim.displayName)}</span>
          <span class="id-badge ${tCls}">${escHtml(dimTypeLabel)}</span>
          ${dim.builtIn ? '<span class="id-badge text-muted">' + t('dims.builtIn') + '</span>' : ''}
          <span class="id-badge">${escHtml(dim.id)}</span>
        </div>
        <span id="dd-dirty" class="text-xs" style="color:var(--amber);display:none">${t('common.save')}...</span>
        <button class="btn-cg btn-cg-ghost" onclick="_tpAdmin({dimension:'${escHtml(dim.worldId)}'})" title="${t('common.teleport')}">TP</button>
      </div>

      ${detailTabBar(tabs, 'config', 'ddSwitchTab')}

      <!-- Tab: Configuration — 2 column (fields left, toggles right) -->
      <div data-tab-panel-dd="config" class="section-card-body">
        <div class="detail-split detail-split-2">
          <div class="space-y-4">
            <div class="grid grid-cols-2 gap-4">
              <div class="field-group">
                <label class="field-label">${t('common.name')}</label>
                <input type="text" value="${escHtml(dim.displayName)}" class="input input-sm w-full input-bordered"
                  oninput="_dd.edit.displayName=this.value;_dd.markDirty()">
              </div>
              <div class="field-group">
                <label class="field-label">${t('common.type')}</label>
                <div class="mt-1"><span class="id-badge ${tCls}">${escHtml(dimTypeLabel)}</span></div>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div class="field-group">
                <label class="field-label">${t('dims.seed')}</label>
                <div class="text-sm font-mono mt-1" style="color:var(--text-secondary)">${dim.seed}</div>
              </div>
              <div class="field-group">
                <label class="field-label">${t('dims.worldId')}</label>
                <div class="text-sm font-mono mt-1 truncate" style="color:var(--text-secondary)">${escHtml(dim.worldId)}</div>
              </div>
            </div>
            ${dim.flatLayers ? `<div class="field-group"><label class="field-label">${t('dims.flatLayers')} ${tip('tip.dims.flatLayers')}</label><div class="text-sm font-mono mt-1" style="color:var(--text-secondary)">${escHtml(dim.flatLayers)}</div></div>` : ''}
            <div class="flex items-end gap-3">
              <div class="grid grid-cols-3 gap-3 flex-1">
                <div class="field-group">
                  <label class="field-label">${t('dims.spawnX')}</label>
                  <input id="dd-spawn-x" type="number" step="0.5" value="${dim.spawnX}" class="input input-sm w-full input-bordered"
                    oninput="_dd.edit.spawnX=parseFloat(this.value)||0;_dd.markDirty()">
                </div>
                <div class="field-group">
                  <label class="field-label">${t('dims.spawnY')}</label>
                  <input id="dd-spawn-y" type="number" step="0.5" value="${dim.spawnY}" class="input input-sm w-full input-bordered"
                    oninput="_dd.edit.spawnY=parseFloat(this.value)||0;_dd.markDirty()">
                </div>
                <div class="field-group">
                  <label class="field-label">${t('dims.spawnZ')}</label>
                  <input id="dd-spawn-z" type="number" step="0.5" value="${dim.spawnZ}" class="input input-sm w-full input-bordered"
                    oninput="_dd.edit.spawnZ=parseFloat(this.value)||0;_dd.markDirty()">
                </div>
              </div>
              <button class="btn-cg btn-cg-ghost shrink-0 mb-1" onclick="_dd.grabPlayerPos()" title="${t('dims.useMyPosition')}">📍 ${t('dims.useMyPosition')}</button>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div class="field-group">
                <label class="field-label">${t('dims.difficulty')} ${tip('tip.dims.difficulty')}</label>
                <select class="select select-sm w-full select-bordered"
                  onchange="_dd.edit.difficulty=this.value;_dd.markDirty()">${diffOpts}</select>
              </div>
              <div class="field-group">
                <label class="field-label">${t('dims.weatherLocked')}</label>
                <select class="select select-sm w-full select-bordered"
                  onchange="_dd.edit.lockedWeather=this.value;_dd.markDirty()">${weatherOpts}</select>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div class="field-group">
                <label class="field-label">${t('dims.lockedTime')}</label>
                <input type="number" min="0" max="23999" value="${dim.lockedTime}" class="input input-sm w-full input-bordered"
                  oninput="_dd.edit.lockedTime=parseInt(this.value)||0;_dd.markDirty()">
                <span class="field-help">${t('tip.dims.timeValues')}</span>
              </div>
              <div class="field-group">
                <label class="field-label">${t('dims.enabledMessage')}</label>
                <input type="text" value="${escHtml(dim.enabledMessage || '')}" class="input input-sm w-full input-bordered"
                  oninput="_dd.edit.enabledMessage=this.value;_dd.markDirty()">
              </div>
              <div class="field-group mt-3">
                <label class="field-label">${t('dims.cmdBattleLoss')} ${tip('tip.dims.cmdBattleLoss')}</label>
                <div id="dd-list-commandsOnBattleLoss" class="mt-1 mb-2"></div>
                <div class="flex gap-2">
                  <input id="dd-inp-cmdBattleLoss" type="text" class="input input-sm flex-1 input-bordered" placeholder="say {player} a perdu un combat">
                  <button class="btn-cg btn-cg-ghost" onclick="_dd.addBattleLossCmd()">+</button>
                </div>
              </div>
              <div class="field-group mt-3">
                <label class="field-label">${t('dims.cmdLeave')} ${tip('tip.dims.cmdLeave')}</label>
                <div id="dd-list-commandsOnLeave" class="mt-1 mb-2"></div>
                <div class="flex gap-2">
                  <input id="dd-inp-cmdLeave" type="text" class="input input-sm flex-1 input-bordered" placeholder="say {player} a quitte la dimension">
                  <button class="btn-cg btn-cg-ghost" onclick="_dd.addLeaveCmd()">+</button>
                </div>
              </div>
            </div>
          </div>
          <div>
            <p class="text-sm mb-3" style="color:var(--text-muted)">${t('common.apply')}</p>
            <div id="dd-toggles">${togglesHtml}</div>
          </div>
        </div>
      </div>

      <!-- Tab: Border — World Border configuration -->
      <div data-tab-panel-dd="border" class="section-card-body" style="display:none">
        ${borderHtml}
      </div>

      <!-- Tab: Flags — Dimension-level denied flags -->
      <div data-tab-panel-dd="flags" class="section-card-body" style="display:none">
        ${flagsHtml}
      </div>

      <!-- Tab: Gestion — Snapshot + Per-Player + Access + Danger -->
      <div data-tab-panel-dd="gestion" class="section-card-body" style="display:none">
        <div class="space-y-6">
          <div>
            <div class="text-sm font-semibold mb-3" style="color:var(--text-heading)">${t('dims.snapshot')}</div>
            <div id="dd-snapshot-panel">${snapshotHtml}</div>
          </div>

          <div class="section-divider"></div>

          <div>
            <div class="text-sm font-semibold mb-3" style="color:var(--text-heading)">${t('dims.perPlayerDims')}</div>
            <div data-dd-section="perplayer">${ppHtml}</div>
          </div>

          <div class="section-divider"></div>

          <div>
            <div class="text-sm font-semibold mb-3" style="color:var(--text-heading)">${t('zones.tabs.access')}</div>
            <div id="dd-access-panel">${accessHtml}</div>
          </div>

          ${!dim.builtIn ? `<div class="danger-zone">
            <div class="danger-zone-title">${t('common.delete')}</div>
            <p class="text-sm mb-3" style="color:var(--text-muted)">${t('common.deleteConfirm')}</p>
            <p class="text-sm mb-2" style="color:var(--text-secondary)">${t('dims.typeToConfirm', {name: '<strong style="color:var(--text-primary)">' + escHtml(dim.displayName) + '</strong>'})}</p>
            <div class="flex gap-2">
              <input type="text" id="dd-del-input" class="input input-sm flex-1 input-bordered"
                oninput="document.getElementById('dd-del-btn').disabled=this.value!=='${escHtml(dim.displayName).replace(/'/g,"\\'")}'"
                placeholder="${escHtml(dim.displayName)}">
              <button id="dd-del-btn" class="btn-cg btn-cg-danger" disabled onclick="_dd.deleteDimension()">${t('common.delete')}</button>
            </div>
          </div>` : ''}
        </div>
      </div>
    </div>
  </div>`;
}

function ddBuildTogglesHtml(dim, toggles) {
  let html = '<div class="grid grid-cols-2 md:grid-cols-3 gap-2">';
  toggles.forEach(tg => {
    const val = !!dim[tg.key];
    const cls = val
      ? 'border-green/30 text-green' : 'border-bg-border text-muted';
    const bg = val
      ? 'background:rgba(34,197,94,0.1)' : 'background:var(--bg-elevated)';
    const tipKey = 'tip.dims.' + tg.key;
    const tipText = t(tipKey);
    const hasTip = tipText && tipText !== tipKey;
    html += `<button class="flex items-center gap-2 px-3 py-2 rounded border text-sm font-mono transition-colors ${cls}" style="${bg}"
      onclick="_dd.toggle('${tg.key}')" id="ddtg-${tg.key}"${hasTip ? ` title="${escHtml(tipText)}"` : ''}>
      <span>${tg.icon}</span>
      <span class="flex-1 text-left">${tg.label}</span>
      <span class="font-bold">${val ? t('common.on') : t('common.off')}</span>
    </button>`;
  });
  html += '</div>';
  return html;
}

function ddBuildBorderHtml(dim) {
  const enabled = !!dim.borderEnabled;
  const cx = (dim.borderCenterX != null) ? dim.borderCenterX : 0;
  const cz = (dim.borderCenterZ != null) ? dim.borderCenterZ : 0;
  const size = (dim.borderSize != null) ? dim.borderSize : 1000;
  const damage = (dim.borderDamage != null) ? dim.borderDamage : 0.2;
  const warn = (dim.borderWarningBlocks != null) ? dim.borderWarningBlocks : 5;
  const warnTime = (dim.borderWarningTime != null) ? dim.borderWarningTime : 15;
  const cls = enabled ? 'border-green/30 text-green' : 'border-bg-border text-muted';
  const bg = enabled ? 'background:rgba(34,197,94,0.1)' : 'background:var(--bg-elevated)';
  return `
    <div class="space-y-4">
      <div>
        <button class="flex items-center gap-2 px-3 py-2 rounded border text-sm font-mono transition-colors ${cls}" style="${bg}"
          onclick="_dd.toggleBorder()" id="ddtg-borderEnabled">
          <span>🌐</span>
          <span class="flex-1 text-left">${t('dims.border.enabled')}</span>
          <span class="font-bold">${enabled ? t('common.on') : t('common.off')}</span>
        </button>
        <p class="text-xs mt-2" style="color:var(--text-muted)">${t('dims.border.desc')}</p>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div class="field-group">
          <label class="field-label">${t('dims.border.centerX')} ${tip('tip.dims.border.center')}</label>
          <input id="dd-bd-cx" type="number" step="1" value="${cx}" class="input input-sm w-full input-bordered"
            oninput="_dd._debouncedBorderSave()">
        </div>
        <div class="field-group">
          <label class="field-label">${t('dims.border.centerZ')} ${tip('tip.dims.border.center')}</label>
          <input id="dd-bd-cz" type="number" step="1" value="${cz}" class="input input-sm w-full input-bordered"
            oninput="_dd._debouncedBorderSave()">
        </div>
      </div>

      <div>
        <button class="btn-cg btn-cg-ghost" onclick="_dd.grabBorderCenter()" title="${t('dims.useMyPosition')}">📍 ${t('dims.useMyPosition')}</button>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div class="field-group">
          <label class="field-label">${t('dims.border.size')} ${tip('tip.dims.border.size')}</label>
          <input id="dd-bd-size" type="number" min="1" step="1" value="${size}" class="input input-sm w-full input-bordered"
            oninput="_dd._debouncedBorderSave()">
        </div>
        <div class="field-group">
          <label class="field-label">${t('dims.border.damage')} ${tip('tip.dims.border.damage')}</label>
          <input id="dd-bd-damage" type="number" min="0" step="0.1" value="${damage}" class="input input-sm w-full input-bordered"
            oninput="_dd._debouncedBorderSave()">
        </div>
      </div>
      <div class="grid grid-cols-2 gap-4">
        <div class="field-group">
          <label class="field-label">${t('dims.border.warningBlocks')} ${tip('tip.dims.border.warning')}</label>
          <input id="dd-bd-warn" type="number" min="0" step="1" value="${warn}" class="input input-sm w-full input-bordered"
            oninput="_dd._debouncedBorderSave()">
        </div>
        <div class="field-group">
          <label class="field-label">${t('dims.border.warningTime')} ${tip('tip.dims.border.warningTime')}</label>
          <input id="dd-bd-warntime" type="number" min="0" step="1" value="${warnTime}" class="input input-sm w-full input-bordered"
            oninput="_dd._debouncedBorderSave()">
        </div>
      </div>
    </div>`;
}

function ddBuildFlagsHtml(dim) {
  const flags = dim.availableFlags || [];
  const denied = new Set(dim.deniedFlags || []);
  if (flags.length === 0) {
    return `<div class="empty-state py-8"><span class="empty-state-text">${t('common.empty')}</span></div>`;
  }
  let html = `
    <p class="text-sm mb-4" style="color:var(--text-muted)">${t('dims.flags.desc')}</p>
    <div class="grid grid-cols-2 md:grid-cols-3 gap-2">`;
  flags.forEach(f => {
    const isDenied = denied.has(f.id);
    const cls = isDenied ? 'border-red/30 text-red' : 'border-bg-border text-muted';
    const bg = isDenied ? 'background:rgba(239,68,68,0.1)' : 'background:var(--bg-elevated)';
    const tipKey = 'tip.flags.' + f.id;
    const tipText = t(tipKey);
    const hasTip = tipText && tipText !== tipKey;
    html += `<button class="flex items-center gap-2 px-3 py-2 rounded border text-sm font-mono transition-colors ${cls}" style="${bg}"
      onclick="_dd.toggleFlag('${escHtml(f.id)}')" id="ddfg-${escHtml(f.id)}"${hasTip ? ` title="${escHtml(tipText)}"` : ''}>
      <span>${f.icon || '🚫'}</span>
      <span class="flex-1 text-left">${escHtml(f.name || f.id)}</span>
      <span class="font-bold">${isDenied ? t('dims.flags.denied') : t('dims.flags.allowed')}</span>
    </button>`;
  });
  html += '</div>';
  return html;
}

function ddRenderBorderTab(dim) {
  const el = document.querySelector('[data-tab-panel-dd="border"]');
  if (!el) return;
  el.innerHTML = ddBuildBorderHtml(dim);
}

function ddRenderFlagsTab(dim) {
  const el = document.querySelector('[data-tab-panel-dd="flags"]');
  if (!el) return;
  el.innerHTML = ddBuildFlagsHtml(dim);
}

function ddRenderToggles(dim) {
  const container = document.getElementById('dd-toggles');
  if (!container) return;
  const toggles = [
    { key: 'enabled',           label: t('dims.toggle.enabled'),         icon: '\uD83D\uDD13' },
    { key: 'pvp',               label: t('dims.toggle.pvp'),             icon: '\u2694' },
    { key: 'mobSpawning',       label: t('dims.toggle.mobs'),            icon: '\uD83D\uDC7E' },
    { key: 'keepInventory',     label: t('dims.toggle.keepInventory'),   icon: '\uD83C\uDF92' },
    { key: 'clearInventory',    label: t('dims.toggle.clearInventory'),  icon: '\uD83E\uDDF9' },
    { key: 'commandBlocks',     label: t('dims.toggle.commandBlocks'),   icon: '\u2699' },
    { key: 'enderChest',        label: t('dims.toggle.enderChest'),      icon: '\uD83D\uDCE6' },
    { key: 'generateStructures',label: t('dims.toggle.structures'),      icon: '\uD83C\uDFDB' },
    { key: 'cobblemonBlocked',  label: t('dims.cobblemonBlocked'),       icon: '\uD83D\uDEAB' },
    { key: 'raidDensEnabled',   label: t('dims.toggle.raidDens'),        icon: '\uD83D\uDC32' },
    { key: 'allowHome',         label: t('dims.toggle.allowHome'),       icon: '\uD83C\uDFE0' },
    { key: 'constructionMode',  label: t('dims.toggle.construction'),    icon: '\uD83D\uDEA7' },
    { key: 'perPlayerMode',     label: t('dims.toggle.perPlayer'),       icon: '\uD83D\uDC64' },
    { key: 'timeLocked',        label: t('dims.timeLocked'),             icon: '\u23F0' },
    { key: 'weatherLocked',     label: t('dims.weatherLocked'),          icon: '\u2601' },
  ];
  container.innerHTML = ddBuildTogglesHtml(dim, toggles);
}

function ddBuildSnapshotHtml(dim) {
  const triggerOpts = ['MANUAL', 'ON_EMPTY', 'SCHEDULED'].map(tr =>
    `<option value="${tr}"${dim.snapshotResetTrigger === tr ? ' selected' : ''}>${tr}</option>`
  ).join('');

  return `
    <div class="space-y-4">
      <div class="grid grid-cols-2 gap-4">
        <div class="p-3 rounded border" style="border-color:var(--bg-border);background:var(--bg-elevated)">
          <div class="text-sm font-mono mb-1" style="color:var(--text-muted)">${t('dims.snapshot')}</div>
          <div class="text-sm" style="color:var(--text-primary)">${dim.snapshotEnabled ? '\u2705 ' + t('dims.snapshotActive') + ' \u2014 ' + fmtSize(dim.snapshotSizeKb) : t('common.none')}</div>
        </div>
        <div class="p-3 rounded border" style="border-color:var(--bg-border);background:var(--bg-elevated)">
          <div class="text-sm font-mono mb-1" style="color:var(--text-muted)">${t('dims.lastReset')}</div>
          <div class="text-sm" style="color:var(--text-primary)">${I18N.fmtDate(dim.lastResetAt)}</div>
        </div>
      </div>
      <div class="flex gap-2">
        <button class="btn-cg btn-cg-primary" onclick="_dd.createSnapshot()">${t('dims.takeSnapshot')}</button>
        <button class="btn-cg btn-cg-danger" onclick="_dd.resetFromSnapshot()"${!dim.snapshotEnabled ? ' disabled style="opacity:0.5"' : ''}>${t('dims.restoreSnapshot')}</button>
      </div>
      ${dim.hasPendingReset ? `<div class="text-sm font-mono" style="color:var(--amber)">\u26A0 ${t('common.loading')}</div>` : ''}
      <div class="pt-4" style="border-top:1px solid var(--bg-border)">
        <div class="text-sm font-mono tracking-wider uppercase mb-2" style="color:var(--text-muted)">${t('dims.autoResetTrigger')}</div>
        <div class="flex gap-2 items-end">
          <div class="flex-1">
            <select id="dd-snap-trigger" class="select select-sm w-full select-bordered"
              onchange="document.getElementById('dd-snap-hour-row').style.display=this.value==='SCHEDULED'?'block':'none';_dd._debouncedSnapSave()">
              ${triggerOpts}
            </select>
          </div>
          <div id="dd-snap-hour-row" style="${dim.snapshotResetTrigger === 'SCHEDULED' ? '' : 'display:none'}">
            <input type="number" id="dd-snap-hour" min="0" max="23" value="${dim.snapshotResetHour}"
              class="input input-sm w-20 input-bordered" placeholder="${t('dims.hour')}"
              oninput="_dd._debouncedSnapSave()">
          </div>
        </div>
        <span class="field-help">${t('tip.dims.snapshotTrigger')}</span>
      </div>
    </div>`;
}

function ddRenderSnapshotTab(dim) {
  const el = document.getElementById('dd-snapshot-panel');
  if (!el) return;
  el.innerHTML = ddBuildSnapshotHtml(dim);
}

function ddRenderPlayerTab(players) {
  const el = document.querySelector('[data-dd-section="perplayer"]');
  if (!el) return;
  if (players.length === 0) {
    el.innerHTML = `<div class="empty-state py-8"><span class="empty-state-icon">\uD83D\uDC64</span><span class="empty-state-text">${t('dims.noDims')}</span></div>`;
    return;
  }
  let html = `<table class="cg-table"><thead><tr><th>${t('common.player')}</th><th>${t('dims.uuid8')}</th><th>${t('common.status')}</th><th></th></tr></thead><tbody>`;
  players.forEach(p => {
    html += `<tr>
      <td class="text-sm" style="color:var(--text-primary)">${escHtml(p.playerName)}</td>
      <td><span class="id-badge">${escHtml(p.uuid8)}</span></td>
      <td><span class="status-dot ${p.online ? 'status-online' : 'status-offline'}"></span> ${p.online ? t('common.online') : t('common.offline')}</td>
      <td class="text-right">
        <button class="btn-cg btn-cg-ghost" onclick="_dd.resetPlayerDim('${escHtml(p.uuid8)}')">${t('common.reset')}</button>
        <button class="btn-cg btn-cg-danger" onclick="_dd.deletePlayerDim('${escHtml(p.uuid8)}')">${t('common.delete')}</button>
      </td>
    </tr>`;
  });
  html += '</tbody></table>';
  html += `<div class="text-sm mt-2" style="color:var(--text-muted)">${players.length} ${t('dims.personalDims')}</div>`;
  el.innerHTML = html;
}

function ddBuildAccessHtml(dim) {
  // Whitelist
  let wlHtml = `<div class="text-sm font-mono tracking-wider uppercase mb-2" style="color:var(--text-muted)">${t('dims.whitelist')}</div>`;
  wlHtml += `<div class="flex gap-2 mb-3">
    <input id="dd-wl-input" class="input input-sm flex-1 input-bordered" placeholder="${t('common.playerName')}" readonly
      onclick="openPlayerPicker({title:t('dims.whitelist'),onSelect:n=>{document.getElementById('dd-wl-input').value=n}})">
    <button class="btn-cg btn-cg-primary" style="height:2rem;font-size:0.75rem" onclick="_dd.addWhitelist()">${t('common.add')}</button>
  </div>`;
  if (dim.whitelist && dim.whitelist.length > 0) {
    wlHtml += '<div class="space-y-1 mb-3">';
    dim.whitelist.forEach(uuid => {
      wlHtml += `<div class="flex items-center justify-between py-1 px-2 rounded" style="background:var(--bg-elevated)">
        <span class="text-xs font-mono" style="color:var(--text-secondary)">${escHtml(uuid)}</span>
        <button class="btn-cg btn-cg-danger" style="height:1.3rem;font-size:0.6rem;padding:0 0.4rem" onclick="_dd.removeWhitelist('${escHtml(uuid)}')">\u2715</button>
      </div>`;
    });
    wlHtml += '</div>';
  } else {
    wlHtml += `<div class="text-sm mb-3" style="color:var(--text-muted)">${t('common.none')}</div>`;
  }

  // Builders
  let bHtml = `<div class="text-sm font-mono tracking-wider uppercase mt-6 mb-2" style="color:var(--text-muted)">${t('dims.builders')}</div>`;
  bHtml += `<div class="flex gap-2 mb-3">
    <input id="dd-builder-input" class="input input-sm flex-1 input-bordered" placeholder="${t('common.playerName')}" readonly
      onclick="openPlayerPicker({title:t('dims.builders'),onSelect:n=>{document.getElementById('dd-builder-input').value=n}})">
    <button class="btn-cg btn-cg-primary" style="height:2rem;font-size:0.75rem" onclick="_dd.addBuilder()">${t('common.add')}</button>
  </div>`;
  if (dim.constructionBuilders && dim.constructionBuilders.length > 0) {
    bHtml += '<div class="space-y-1">';
    dim.constructionBuilders.forEach(uuid => {
      bHtml += `<div class="flex items-center justify-between py-1 px-2 rounded" style="background:var(--bg-elevated)">
        <span class="text-xs font-mono" style="color:var(--text-secondary)">${escHtml(uuid)}</span>
        <button class="btn-cg btn-cg-danger" style="height:1.3rem;font-size:0.6rem;padding:0 0.4rem" onclick="_dd.removeBuilder('${escHtml(uuid)}')">\u2715</button>
      </div>`;
    });
    bHtml += '</div>';
  } else {
    bHtml += `<div class="text-sm" style="color:var(--text-muted)">${t('common.none')}</div>`;
  }

  return wlHtml + bHtml;
}

function ddRenderAccessTab(dim) {
  const el = document.getElementById('dd-access-panel');
  if (!el) return;
  el.innerHTML = ddBuildAccessHtml(dim);
}
