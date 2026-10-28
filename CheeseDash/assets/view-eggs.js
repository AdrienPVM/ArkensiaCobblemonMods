// ── EggAndCheese ─────────────────────────────────────────────────────────────

// ╔═══════════════════════════════════════════════════════════════════════════╗
// ║  EGGS VIEW — EggAndCheese collectibles                                  ║
// ╚═══════════════════════════════════════════════════════════════════════════╝

async function buildEggs(app) {
  let entries, stats, enums;
  try {
    [entries, stats, enums] = await Promise.all([API.eggs.list(), API.eggs.stats(), API.eggs.enums()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  window._eg = {
    entries, stats, enums, app,
    filter: { search: '' },
    _debouncedConfig: debounce(function() { if (window._eg) window._eg.saveConfig(); }, 1500),

    async refresh() {
      try {
        [this.entries, this.stats] = await Promise.all([API.eggs.list(), API.eggs.stats()]);
        egRenderList(this.entries, this.filter);
        egRenderStats(this.stats);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    applyFilter() {
      this.filter.search = document.getElementById('eg-search')?.value?.toLowerCase() || '';
      egRenderList(this.entries, this.filter);
    },

    // ── Integrity check (duplicate-ID / orphan blocks) ──
    async checkIntegrity() {
      let r;
      try { r = await API.eggs.integrity(); }
      catch (e) { app.toast(e.message, 'error'); return; }
      const dups = r.duplicateCount || 0;
      const orph = r.orphanCount || 0;
      if (dups === 0 && orph === 0) {
        app.toast(t('eggs.integrityClean', { n: r.loadedBlocks || 0 }), 'success');
        return;
      }
      // Build a short detail of the issues found (loaded chunks only)
      const lines = [];
      (r.duplicates || []).forEach(d => {
        const ps = (d.positions || []).map(p => `${p.x},${p.y},${p.z}`).join(' / ');
        lines.push(t('eggs.integrityDup', { id: d.id, pos: ps }));
      });
      (r.orphans || []).forEach(o => lines.push(t('eggs.integrityOrphan', { id: o.id, pos: `${o.x},${o.y},${o.z}` })));
      const msg = t('eggs.integrityFound', { dups, orph }) + '  ' + lines.join('  •  ');
      if (!await confirmAction(msg)) return;
      try {
        const res = await API.eggs.reconcile();
        app.toast(t('eggs.integrityFixed', { d: res.duplicatesResolved || 0, o: res.orphansCleared || 0 }), 'success');
        this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async createEgg() {
      document.getElementById('eg-create-modal')?.showModal();
    },

    async doCreate() {
      const f = (id) => document.getElementById(id)?.value || '';
      const n = (id) => parseInt(document.getElementById(id)?.value) || 0;
      try {
        const result = await API.eggs.create({
          type: 'egg',
          dimension: f('eg-new-dim') || 'minecraft:overworld',
          x: n('eg-new-x'), y: n('eg-new-y') || 64, z: n('eg-new-z'),
          title: f('eg-new-title'),
        });
        app.toast(t('eggs.created'), 'success');
        document.getElementById('eg-create-modal')?.close();
        if (result?.id) app.navigate('eggs:' + result.id);
        else this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // ── Bulk operations ──
    selectedIds: new Set(),

    toggleSelect(id) {
      if (this.selectedIds.has(id)) this.selectedIds.delete(id);
      else this.selectedIds.add(id);
      this._updateBulkBar();
      const cb = document.querySelector(`.eg-select-cb[data-id="${id}"]`);
      if (cb) cb.checked = this.selectedIds.has(id);
    },

    selectAll() {
      const all = this.entries.map(e => e.id);
      if (this.selectedIds.size === all.length) { this.selectedIds.clear(); }
      else { all.forEach(id => this.selectedIds.add(id)); }
      this._updateBulkBar();
      document.querySelectorAll('.eg-select-cb').forEach(cb => cb.checked = this.selectedIds.has(cb.dataset.id));
    },

    _updateBulkBar() {
      const bar = document.getElementById('eg-bulk-bar');
      if (!bar) return;
      const n = this.selectedIds.size;
      bar.style.display = n > 0 ? 'flex' : 'none';
      const cnt = document.getElementById('eg-bulk-count');
      if (cnt) cnt.textContent = n;
    },

    async bulkDelete() {
      const ids = [...this.selectedIds];
      if (!ids.length) return;
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      let ok = 0;
      for (const id of ids) { try { await API.eggs.delete(id); ok++; } catch(e) {} }
      app.toast(t('common.success'), 'success');
      this.selectedIds.clear();
      this.refresh();
    },

    async bulkRandomize() {
      const ids = [...this.selectedIds];
      if (!ids.length) return;
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      let ok = 0;
      for (const id of ids) { try { await API.eggs.randomizeDna(id); ok++; } catch(e) {} }
      app.toast(t('common.success'), 'success');
      this.selectedIds.clear();
      this.refresh();
    },

    async openFtbConfig() {
      try {
        const cfg = await API.eggs.config();
        document.getElementById('eg-ftb-enabled').checked = cfg.ftbQuestsEnabled;
        document.getElementById('eg-ftb-chapter').value = cfg.ftbQuestsChapter || 'oeufs';
        const modal = document.getElementById('eg-ftb-modal');
        if (modal) {
          modal.showModal();
          if (!modal._autoSaveBound) {
            const autoSave = debounce(() => { if (window._eg) window._eg.saveFtbConfig(); }, 1500);
            modal.addEventListener('input', autoSave);
            modal.addEventListener('change', autoSave);
            modal._autoSaveBound = true;
          }
        }
      } catch (e2) { app.toast(e2.message, 'error'); }
    },

    async saveFtbConfig() {
      try {
        await API.eggs.updateConfig({
          ftbQuestsEnabled: document.getElementById('eg-ftb-enabled')?.checked || false,
          ftbQuestsChapter: document.getElementById('eg-ftb-chapter')?.value || 'oeufs',
        });
        app.toast(t('common.success'), 'success');
        document.getElementById('eg-ftb-modal')?.close();
      } catch (e2) { app.toast(e2.message, 'error'); }
    },

    async syncFtb() {
      try { await API.eggs.syncFtb(); app.toast(t('common.success'), 'success'); }
      catch (e2) { app.toast(e2.message, 'error'); }
    },

    // ── Config tab ──
    _cfgLoaded: false,
    cfg: {},

    async loadConfig() {
      try {
        this.cfg = await API.eggs.config();
        this._cfgLoaded = true;
        egRenderConfig(this.cfg);
      } catch (e2) { app.toast(e2.message, 'error'); }
    },

    async saveConfig() {
      try {
        const cfg = {
          ftbQuestsEnabled: document.getElementById('egc-ftb-enabled')?.checked || false,
          ftbQuestsChapter: document.getElementById('egc-ftb-chapter')?.value || 'oeufs',
          ftbAutoSync: document.getElementById('egc-ftb-autosync')?.checked || false,
          textureRenderDistance: parseInt(document.getElementById('egc-render-dist')?.value) || 10,
        };
        await API.eggs.updateConfig(cfg);
        this.cfg = cfg;
        app.toast(t('common.success'), 'success');
      } catch (e2) { app.toast(e2.message, 'error'); }
    },

    async syncFtbFromConfig() {
      try { await API.eggs.syncFtb(); app.toast(t('common.success'), 'success'); }
      catch (e2) { app.toast(e2.message, 'error'); }
    },

    switchMainTab(tabId) {
      document.querySelectorAll('[data-eg-main-tab]').forEach(el => el.style.display = 'none');
      const target = document.querySelector(`[data-eg-main-tab="${tabId}"]`);
      if (target) target.style.display = '';
      document.querySelectorAll('#eg-main-tabs .detail-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tabId));
      if (tabId === 'config' && !this._cfgLoaded) this.loadConfig();
    },
  };

  function egRenderStats(st) {
    const el = document.getElementById('eg-stats');
    if (!el) return;
    el.innerHTML = `
      <div class="stat-card"><span class="stat-icon" style="color:var(--amber)">🥚</span><span class="stat-value" style="color:var(--amber)">${st.totalEntries}</span><span class="stat-label">${t('eggs.title')}</span></div>
      <div class="stat-card"><span class="stat-icon" style="color:var(--green)">✨</span><span class="stat-value" style="color:var(--green)">${st.totalDiscoveries}</span><span class="stat-label">${t('eggs.discoveries')}</span></div>
      <div class="stat-card"><span class="stat-icon" style="color:var(--cyan)">👁</span><span class="stat-value" style="color:var(--cyan)">${st.uniqueDiscoverers || 0}</span><span class="stat-label">${t('common.players')}</span></div>`;
  }

  function egRenderList(entries, filter) {
    const el = document.getElementById('eg-list');
    if (!el) return;
    let filtered = entries;
    if (filter.search) filtered = filtered.filter(e => e.id.toLowerCase().includes(filter.search) || (e.title || '').toLowerCase().includes(filter.search));

    if (!filtered.length) {
      el.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-8">' + t('eggs.noCollectibles') + '</td></tr>';
      return;
    }
    const nav = (id) => `_nav('eggs:${id}')`;
    el.innerHTML = filtered.map(e => {
      const posStr = e.positionLost ? '<span style="color:var(--red)" class="text-xs">' + t('common.error') + '</span>' : `${e.x}, ${e.y}, ${e.z}`;
      const checked = window._eg?.selectedIds?.has(e.id) ? 'checked' : '';
      return `
      <tr class="cursor-pointer" style="transition:background .15s" onmouseenter="this.style.background='var(--bg-elevated)'" onmouseleave="this.style.background=''">
        <td class="text-center" onclick="event.stopPropagation()"><input type="checkbox" class="checkbox checkbox-sm checkbox-primary eg-select-cb" data-id="${escHtml(e.id)}" ${checked} onchange="_eg.toggleSelect('${escHtml(e.id)}')"></td>
        <td onclick="${nav(escHtml(e.id))}"><span class="id-badge">${escHtml(e.id)}</span></td>
        <td class="text-sm" onclick="${nav(escHtml(e.id))}">${escHtml(e.paletteDisplay)}</td>
        <td class="text-sm font-mono" onclick="${nav(escHtml(e.id))}">${escHtml(fmtWorld(e.dimension))}</td>
        <td class="text-sm font-mono" onclick="${nav(escHtml(e.id))}">${posStr}</td>
        <td class="text-center" onclick="${nav(escHtml(e.id))}">${e.discoveryCount}</td>
        <td class="text-sm" style="color:var(--text-secondary)" onclick="${nav(escHtml(e.id))}">${escHtml(e.title || '—')}</td>
        <td onclick="event.stopPropagation()"><button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_tpAdmin({dimension:'${escHtml(e.dimension)}',x:${e.x||0},y:${e.y||0},z:${e.z||0}})" title="${t('players.tpTo')}">TP</button></td>
      </tr>`;
    }).join('');
  }

  function egRenderConfig(cfg) {
    const el = document.getElementById('eg-config-panel');
    if (!el) return;
    el.innerHTML = `
      <div class="section-card mb-4">
        <div class="section-card-header"><span class="section-card-title">${t('eggs.ftbQuests')}</span></div>
        <div class="section-card-body" style="padding:1rem">
          <div class="space-y-3">
            <label class="flex items-center gap-2 cursor-pointer">
              <input id="egc-ftb-enabled" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${cfg.ftbQuestsEnabled ? 'checked' : ''}>
              <span class="text-sm" style="color:var(--text-secondary)">${t('eggs.ftbQuests')}</span>
            </label>
            <div><label class="text-xs font-mono" style="color:var(--muted)">${t('eggs.chapterName')}</label><input id="egc-ftb-chapter" class="input input-sm w-full mt-1 input-bordered" value="${escHtml(cfg.ftbQuestsChapter || 'oeufs')}"></div>
            <label class="flex items-center gap-2 cursor-pointer">
              <input id="egc-ftb-autosync" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${cfg.ftbAutoSync ? 'checked' : ''}>
              <span class="text-sm" style="color:var(--text-secondary)">${t('eggs.ftbQuests')}</span>
            </label>
            <div class="pt-2"><button class="btn-cg btn-cg-ghost" onclick="_eg.syncFtbFromConfig()">${t('common.reload')}</button></div>
          </div>
        </div>
      </div>
      <div class="section-card mb-4">
        <div class="section-card-header"><span class="section-card-title">${t('eggs.renderDistance')}</span></div>
        <div class="section-card-body" style="padding:1rem">
          <p class="text-xs mb-3" style="color:var(--muted)">${t('tip.eggs.renderDistance')}</p>
          <div class="flex items-center gap-3">
            <label class="text-sm font-mono" style="color:var(--muted-light)">${t('eggs.globalDistance')}</label>
            <input id="egc-render-dist" type="number" class="input input-sm input-bordered w-20" value="${cfg.textureRenderDistance || 10}" min="0" max="128">
            <span class="text-xs" style="color:var(--muted)">${t('eggs.blocks')}</span>
          </div>
          <p class="text-xs mt-2" style="color:var(--muted)">${t('tip.eggs.renderDistance')}</p>
        </div>
      </div>
      <div id="eg-cfg-dirty" class="text-right text-xs mt-2" style="color:var(--amber);display:none">${t('common.save')}...</div>`;
    // Auto-save event delegation
    setTimeout(() => {
      const autoSave = debounce(() => { if (window._eg) window._eg.saveConfig(); }, 1500);
      el.addEventListener('input', autoSave);
      el.addEventListener('change', autoSave);
    }, 50);
  }

  setTimeout(() => { egRenderStats(stats); egRenderList(entries, _eg.filter); }, 50);

  return `
    <div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <!-- Stats -->
      <div id="eg-stats" class="grid grid-cols-3 gap-3 mb-4"></div>

      <!-- Main tabs -->
      <div id="eg-main-tabs" class="detail-tabs mb-4">
        <button class="detail-tab active" data-tab="list" onclick="_eg.switchMainTab('list')">${t('eggs.title')}</button>
        <button class="detail-tab" data-tab="config" onclick="_eg.switchMainTab('config')">${t('eggs.configuration')}</button>
      </div>

      <!-- TAB: Collectibles List -->
      <div data-eg-main-tab="list">
        <!-- Filters -->
        <div class="filter-bar mb-4 flex gap-2 items-center flex-wrap">
          <input id="eg-search" class="input input-sm w-48 input-bordered" placeholder="${t('common.search')}" oninput="_eg.applyFilter()">
          <div class="flex-1"></div>
          <button class="btn-cg btn-cg-primary" onclick="_eg.createEgg()">${t('eggs.createNew')}</button>
          <button class="btn-cg btn-cg-ghost" onclick="_eg.checkIntegrity()">${t('eggs.integrity')}</button>
          <button class="btn-cg btn-cg-ghost" onclick="_eg.refresh()">${t('common.refresh')}</button>
        </div>

        <!-- Bulk action bar -->
        <div id="eg-bulk-bar" class="flex items-center gap-3 px-4 py-2 rounded mb-3" style="display:none;border:1px solid rgba(245,166,35,0.3);background:rgba(245,166,35,0.05)">
          <span class="text-xs font-semibold" style="color:var(--amber)"><span id="eg-bulk-count">0</span> ${t('common.selected')}</span>
          <button class="btn-cg btn-cg-danger" onclick="_eg.bulkDelete()">${t('common.delete')}</button>
          <button class="btn-cg btn-cg-ghost" onclick="_eg.bulkRandomize()">${t('eggs.bulkRandomize')}</button>
          <div class="flex-1"></div>
          <button class="text-xs" style="color:var(--text-muted)" onclick="_eg.selectedIds.clear(); _eg._updateBulkBar(); _eg.refresh()">${t('common.cancel')}</button>
        </div>

        <!-- Table -->
        <div class="section-card">
          <table class="cg-table">
            <thead><tr><th class="w-8"><input type="checkbox" class="checkbox checkbox-sm checkbox-primary" onchange="_eg.selectAll()"></th><th>${t('common.id')}</th><th>${t('eggs.palette')}</th><th>${t('common.world')}</th><th>${t('common.position')}</th><th>${t('eggs.discoveries')}</th><th>${t('eggs.titleField')}</th><th></th></tr></thead>
            <tbody id="eg-list"></tbody>
          </table>
        </div>
      </div>

      <!-- TAB: Configuration -->
      <div data-eg-main-tab="config" style="display:none">
        <div id="eg-config-panel">
          <div class="text-center text-muted py-8 font-mono">${t('common.loading')}</div>
        </div>
      </div>

      <!-- Create Modal -->
      <dialog id="eg-create-modal" class="modal">
        <div class="modal-box" style="max-width:28rem">
          <h3 style="color:var(--amber)" class="font-semibold mb-3">${t('eggs.newCollectible')}</h3>
          <div class="grid grid-cols-2 gap-3">
            <div class="col-span-2"><label class="text-sm" style="color:var(--text-muted)">${t('eggs.titleField')}</label><input id="eg-new-title" class="input input-sm w-full input-bordered"></div>
            <div class="col-span-2"><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dimension')}</label><input id="eg-new-dim" list="global-dims-dl" class="input input-sm w-full font-mono input-bordered" value="minecraft:overworld"></div>
            <div><label class="text-sm" style="color:var(--text-muted)">X</label><input id="eg-new-x" type="number" class="input input-sm w-full input-bordered" value="0"></div>
            <div><label class="text-sm" style="color:var(--text-muted)">Y</label><input id="eg-new-y" type="number" class="input input-sm w-full input-bordered" value="64"></div>
            <div><label class="text-sm" style="color:var(--text-muted)">Z</label><input id="eg-new-z" type="number" class="input input-sm w-full input-bordered" value="0"></div>
          </div>
          <div class="mt-4 flex justify-end gap-2">
            <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
            <button class="btn-cg btn-cg-primary" onclick="_eg.doCreate()">${t('common.create')}</button>
          </div>
        </div>
        <form method="dialog" class="modal-backdrop"><button>close</button></form>
      </dialog>

      <!-- FTB Config Modal (legacy, kept for backward compat) -->
      <dialog id="eg-ftb-modal" class="modal">
        <div class="modal-box" style="max-width:24rem">
          <h3 style="color:var(--amber)" class="font-semibold mb-3">${t('eggs.ftbQuests')}</h3>
          <div class="space-y-3">
            <label class="flex items-center gap-2 text-sm" style="color:var(--text-secondary)">
              <input id="eg-ftb-enabled" type="checkbox" class="checkbox checkbox-sm checkbox-primary"> ${t('eggs.ftbQuests')}
            </label>
            <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.chapter')}</label><input id="eg-ftb-chapter" class="input input-sm w-full input-bordered" value="oeufs"></div>
          </div>
          <div class="mt-4 flex justify-between">
            <button class="btn-cg btn-cg-ghost" onclick="_eg.syncFtb()">${t('common.reload')}</button>
            <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.close')}</button></form>
          </div>
        </div>
        <form method="dialog" class="modal-backdrop"><button>close</button></form>
      </dialog>
    </div>`;
}

// ── Egg Detail helpers & view ────────────────────────────────────────────────

function egdSwitchTab(tabId, btn) { switchDetailTab(tabId, btn, 'egd'); if (tabId === 'disc' && window._egd && !_egd._discLoaded) { _egd._discLoaded = true; _egd.loadDiscoveries(); } }

// ── Egg Detail (Round 7 — split-screen layout) ─────────────────────────────
// egdEnumSelect and egdRenderDiscoveries are shared helpers defined in app.js

async function buildEggDetail(app, eggId) {
  let entry, enums;
  try {
    [entry, enums] = await Promise.all([API.eggs.get(eggId), API.eggs.enums()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const inp = (id, val, extra) => `<input id="${id}" class="input input-sm w-full input-bordered" value="${escHtml(val || '')}" ${extra || ''}>`;

  // State
  window._egd = {
    entry, enums, app, _discLoaded: false,
    _eggPreview: null, _autoSpin: true, _dnaDebounce: null,
    _debouncedDna: debounce(function() { if (window._egd) window._egd.saveDna(); }, 1500),
    _debouncedLoc: debounce(function() { if (window._egd) window._egd.saveLocation(); }, 1500),
    _debouncedCfg: debounce(function() { if (window._egd) window._egd.saveConfig(); }, 1500),

    async reload() {
      try { this.entry = await API.eggs.get(this.entry.id); } catch(e) { app.toast(e.message,'error'); }
    },

    // ── DNA ──
    async saveDna() {
      const f = (id) => document.getElementById(id)?.value || '';
      const n = (id) => parseInt(document.getElementById(id)?.value) || 0;
      let flags = 0;
      if (document.getElementById('egd-flag-lighting')?.checked) flags |= 0x01;
      if (document.getElementById('egd-flag-specular')?.checked) flags |= 0x02;
      if (document.getElementById('egd-flag-emboss')?.checked)   flags |= 0x04;
      if (document.getElementById('egd-flag-varnish')?.checked)  flags |= 0x08;
      if (document.getElementById('egd-flag-rimglow')?.checked)  flags |= 0x10;
      try {
        await API.eggs.update(this.entry.id, { dna: {
          palette: f('egd-dna-palette'), pattern: f('egd-dna-pattern'), patternScale: n('egd-dna-patternScale'),
          secondaryPattern: f('egd-dna-secPattern'), patternBlend: n('egd-dna-blend'),
          emissive: f('egd-dna-emissive'), emissiveDensity: n('egd-dna-density'),
          stamp: f('egd-dna-stamp'), microTexture: f('egd-dna-micro'),
          visualFlags: flags, varnishLevel: n('egd-dna-varnish'),
          customPalette: f('egd-dna-customPalette'),
        }});
        app.toast(t('common.success'), 'success');
        await this.reload();
        this._update3dPreview();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async randomizeDna() {
      try {
        await API.eggs.randomizeDna(this.entry.id);
        await this.reload();
        this._refreshDnaForm();
        this._update3dPreview();
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    _refreshDnaForm() {
      const dna = this.entry.dna || {};
      const set = (id, val) => { const el = document.getElementById(id); if (el) el.value = val ?? ''; };
      const chk = (id, val) => { const el = document.getElementById(id); if (el) el.checked = !!val; };
      set('egd-dna-palette', dna.palette); set('egd-dna-pattern', dna.pattern);
      set('egd-dna-patternScale', dna.patternScale || 1); set('egd-dna-secPattern', dna.secondaryPattern);
      set('egd-dna-blend', dna.patternBlend || 0); set('egd-dna-emissive', dna.emissive);
      set('egd-dna-density', dna.emissiveDensity || 0);
      set('egd-dna-stamp', dna.stamp); set('egd-dna-micro', dna.microTexture);
      set('egd-dna-varnish', dna.varnishLevel || 0); set('egd-dna-customPalette', dna.customPalette || '');
      chk('egd-flag-lighting', dna.flagLighting); chk('egd-flag-specular', dna.flagSpecular);
      chk('egd-flag-emboss', dna.flagEmboss); chk('egd-flag-varnish', dna.flagVarnish);
      chk('egd-flag-rimglow', dna.flagRimGlow);
      // Update custom palette color pickers
      const cpRow = document.getElementById('egd-custom-palette-row');
      if (cpRow) cpRow.style.display = dna.palette === 'CUSTOM' ? 'block' : 'none';
      const cpColors = (dna.customPalette || '').split(',').map(s => s.trim()).filter(Boolean);
      for (let i = 0; i < 6; i++) {
        const cpEl = document.getElementById('egd-cp-' + i);
        if (cpEl && cpColors[i]) cpEl.value = '#' + cpColors[i].replace('#','').padStart(6,'0');
      }
      const codeEl = document.getElementById('egd-dna-code');
      if (codeEl) codeEl.textContent = dna.dnaCode || '—';
    },

    copyDna(el) {
      const text = el?.textContent || '';
      if (!text || text === '—') return;
      cgCopy(text).then(() => app.toast(t('common.copied'), 'success', 1500)).catch(() => {});
    },

    // Debounced 3D update when DNA form changes
    onDnaChange() {
      clearTimeout(this._dnaDebounce);
      this._dnaDebounce = setTimeout(() => this._update3dPreview(), 500);
      this._debouncedDna();
    },

    onPaletteChange() {
      const val = document.getElementById('egd-dna-palette')?.value || '';
      const row = document.getElementById('egd-custom-palette-row');
      if (row) row.style.display = val === 'CUSTOM' ? 'block' : 'none';
      this.onDnaChange();
    },

    onCustomPaletteChange() {
      const hex = [];
      for (let i = 0; i < 6; i++) {
        const el = document.getElementById('egd-cp-' + i);
        hex.push(el ? el.value.replace('#', '').toUpperCase() : 'FF0000');
      }
      const hidden = document.getElementById('egd-dna-customPalette');
      if (hidden) hidden.value = hex.join(',');
      this.onDnaChange();
    },

    _update3dPreview() {
      if (!this._eggPreview) return;
      // Read current form values for live preview (not server-side entry.dna)
      const f = (id) => document.getElementById(id)?.value || '';
      const n = (id) => parseInt(document.getElementById(id)?.value) || 0;
      let flags = 0;
      if (document.getElementById('egd-flag-lighting')?.checked) flags |= 0x01;
      if (document.getElementById('egd-flag-specular')?.checked) flags |= 0x02;
      if (document.getElementById('egd-flag-emboss')?.checked)   flags |= 0x04;
      if (document.getElementById('egd-flag-varnish')?.checked)  flags |= 0x08;
      if (document.getElementById('egd-flag-rimglow')?.checked)  flags |= 0x10;
      const formDna = {
        palette: f('egd-dna-palette'), pattern: f('egd-dna-pattern'),
        patternScale: n('egd-dna-patternScale') || 1,
        secondaryPattern: f('egd-dna-secPattern'), patternBlend: n('egd-dna-blend'),
        emissive: f('egd-dna-emissive'), emissiveDensity: n('egd-dna-density'),
        stamp: f('egd-dna-stamp'),
        microTexture: f('egd-dna-micro'), visualFlags: flags,
        varnishLevel: n('egd-dna-varnish'),
        customPalette: f('egd-dna-customPalette'),
        seed: this.entry?.dna?.seed || 0,
        flagLighting: !!(flags & 0x01), flagSpecular: !!(flags & 0x02),
        flagEmboss: !!(flags & 0x04), flagVarnish: !!(flags & 0x08),
        flagRimGlow: !!(flags & 0x10),
      };
      this._eggPreview.setDNA(formDna);
    },

    // ── 3D Preview ──
    init3dPreview() {
      if (typeof EggPreview === 'undefined') { app.toast(t('common.error'), 'error'); return; }
      this.dispose3dPreview();
      const container = document.getElementById('egd-3d-container');
      if (!container) return;
      try {
        this._eggPreview = new EggPreview('egd-3d-container');
        if (this.entry?.dna) this._eggPreview.setDNA(this.entry.dna);
        if (this._autoSpin) this._eggPreview.startAutoSpin();
      } catch (e) {
        console.error('3D preview error:', e);
        container.innerHTML = '<div class="text-center py-8 text-sm" style="color:var(--text-muted)">' + t('eggs.preview3dError') + '</div>';
      }
    },

    dispose3dPreview() {
      if (this._eggPreview) { this._eggPreview.dispose(); this._eggPreview = null; }
    },

    resetPreviewCamera() { if (this._eggPreview) this._eggPreview.resetCamera(); },

    toggleAutoSpin() {
      this._autoSpin = !this._autoSpin;
      if (this._eggPreview) {
        if (this._autoSpin) this._eggPreview.startAutoSpin();
        else this._eggPreview.stopAutoSpin();
      }
      const btn = document.getElementById('egd-autospin-btn');
      if (btn) btn.textContent = this._autoSpin ? t('eggs.autoRotOn') : t('eggs.autoRotOff');
    },

    // ── Location ──
    async saveLocation() {
      const f = (id) => document.getElementById(id)?.value || '';
      const n = (id) => parseInt(document.getElementById(id)?.value) || 0;
      try {
        await API.eggs.update(this.entry.id, {
          dimension: f('egd-loc-dim'),
          x: n('egd-loc-x'), y: n('egd-loc-y'), z: n('egd-loc-z'),
          scale: parseFloat(document.getElementById('egd-loc-scale')?.value) || 1.0,
          positionLost: document.getElementById('egd-loc-lost')?.checked || false,
        });
        app.toast(t('common.success'), 'success');
        await this.reload();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async useMyPosition() {
      try {
        const pos = await API.admin.getPosition();
        if (pos.x != null) {
          const set = (id, v) => { const el = document.getElementById(id); if (el) el.value = v; };
          set('egd-loc-dim', pos.dimension || 'minecraft:overworld');
          set('egd-loc-x', Math.floor(pos.x)); set('egd-loc-y', Math.floor(pos.y)); set('egd-loc-z', Math.floor(pos.z));
          app.toast(t('common.success'), 'success');
          this._debouncedLoc();
        } else { app.toast(t('eggs.positionUnavailable'), 'error'); }
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // ── Discoveries ──
    async loadDiscoveries() {
      try {
        const discs = await API.eggs.discoveries(this.entry.id);
        egdRenderDiscoveries(discs);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async addDiscovery() {
      const name = document.getElementById('egd-disc-name')?.value?.trim();
      if (!name) return;
      try {
        await API.eggs.addDiscovery(this.entry.id, name);
        document.getElementById('egd-disc-name').value = '';
        app.toast(t('common.success'), 'success');
        this.loadDiscoveries();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async removeDiscovery(uuid) {
      try {
        await API.eggs.removeDiscovery(this.entry.id, uuid);
        app.toast(t('common.success'), 'success');
        this.loadDiscoveries();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async clearDiscoveries() {
      if (!await confirmAction('' + t('eggs.clearDiscoveries') + '')) return;
      try {
        await API.eggs.clearDiscoveries(this.entry.id);
        app.toast(t('common.success'), 'success');
        this.loadDiscoveries();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // ── Config (title, subtitle, rename) ──
    async saveConfig() {
      const f = (id) => document.getElementById(id)?.value || '';
      const rdVal = parseInt(document.getElementById('egd-cfg-renderdist')?.value);
      try {
        await API.eggs.update(this.entry.id, {
          title: f('egd-cfg-title'), subtitle: f('egd-cfg-subtitle'),
          renderDistanceOverride: isNaN(rdVal) ? -1 : rdVal,
        });
        app.toast(t('common.success'), 'success');
        await this.reload();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async renameEntry() {
      const newId = document.getElementById('egd-cfg-newid')?.value?.trim();
      if (!newId) { app.toast(t('eggs.idRequired'), 'error'); return; }
      if (newId === this.entry.id) return;
      try {
        await API.eggs.rename(this.entry.id, newId);
        app.toast(t('common.success'), 'success');
        app.navigate('eggs:' + newId);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // ── Give items ──
    async giveAdmin() {
      const name = document.getElementById('egd-give-player')?.value?.trim();
      if (!name) { app.toast(t('eggs.playerNameRequired'), 'error'); return; }
      try {
        await API.eggs.giveAdmin(this.entry.id, name);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async giveDeco() {
      const name = document.getElementById('egd-give-player')?.value?.trim();
      if (!name) { app.toast(t('eggs.playerNameRequired'), 'error'); return; }
      try {
        await API.eggs.giveDeco(this.entry.id, name);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // ── Delete ──
    async deleteEntry() {
      if (!await confirmAction(t('eggs.deleteCollectible'))) return;
      try {
        await API.eggs.delete(this.entry.id);
        app.toast(t('eggs.deleted'), 'success');
        app.navigate('eggs');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  const dna = entry.dna || {};
  const dnaChangeAttr = 'onchange="_egd.onDnaChange()" oninput="_egd.onDnaChange()"';

  // DNA form content (right column)
  const dnaHtml = `
    <div class="grid grid-cols-2 gap-3">
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.palette')}</label>${egdEnumSelect('egd-dna-palette', enums.palettes, dna.palette).replace('<select ', '<select onchange="_egd.onPaletteChange()" ')}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.pattern')}</label>${egdEnumSelect('egd-dna-pattern', enums.patterns, dna.pattern).replace('<select ', '<select onchange="_egd.onDnaChange()" ')}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.scale')}</label>${inp('egd-dna-patternScale', dna.patternScale || 1, 'type="number" min="1" max="4" ' + dnaChangeAttr)}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.secondary')}</label>${egdEnumSelect('egd-dna-secPattern', enums.patterns, dna.secondaryPattern).replace('<select ', '<select onchange="_egd.onDnaChange()" ')}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.blend')}</label>${inp('egd-dna-blend', dna.patternBlend || 0, 'type="number" min="0" max="100" ' + dnaChangeAttr)}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.emissive')}</label>${egdEnumSelect('egd-dna-emissive', enums.emissives, dna.emissive).replace('<select ', '<select onchange="_egd.onDnaChange()" ')}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.density')}</label>${inp('egd-dna-density', dna.emissiveDensity || 0, 'type="number" min="0" max="100" ' + dnaChangeAttr)}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.stamp')}</label>${egdEnumSelect('egd-dna-stamp', enums.stamps, dna.stamp).replace('<select ', '<select onchange="_egd.onDnaChange()" ')}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.microTex')}</label>${egdEnumSelect('egd-dna-micro', enums.microTextures, dna.microTexture).replace('<select ', '<select onchange="_egd.onDnaChange()" ')}</div>
      <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.dna.varnish')}</label>${inp('egd-dna-varnish', dna.varnishLevel || 0, 'type="number" min="0" max="100" ' + dnaChangeAttr)}</div>
    </div>
    <div id="egd-custom-palette-row" class="mt-3" style="display:${dna.palette === 'CUSTOM' ? 'block' : 'none'}">
      <div class="text-sm mb-2" style="color:var(--text-muted)">${t('eggs.dna.customPalette')}</div>
      <div class="grid grid-cols-6 gap-2">${
        [t('eggs.dna.cpPrimary'),t('eggs.dna.cpSecondary'),t('eggs.dna.cpReflect'),t('eggs.dna.cpShadow'),t('eggs.dna.cpAccent'),t('eggs.dna.cpGlow')].map((label, i) => {
          const cpColors = (dna.customPalette || '').split(',').map(s => s.trim()).filter(Boolean);
          const val = cpColors[i] ? '#' + cpColors[i].replace('#','').padStart(6,'0') : ['#e07030','#ff9050','#ffd080','#bb4010','#ffb040','#ff6010'][i];
          return `<div class="text-center"><label class="text-xs" style="color:var(--text-muted)">${label}</label><input type="color" id="egd-cp-${i}" value="${val}" class="w-full h-7 rounded cursor-pointer" style="border:1px solid var(--bg-border);background:transparent" onchange="_egd.onCustomPaletteChange()" oninput="_egd.onCustomPaletteChange()"></div>`;
        }).join('')
      }</div>
      <input type="hidden" id="egd-dna-customPalette" value="${escHtml(dna.customPalette || '')}">
    </div>
    <div class="mt-3">
      <div class="text-sm mb-1" style="color:var(--text-muted)">${t('eggs.dna.visualFlags')}</div>
      <div class="flex flex-wrap gap-3">
        <label class="flex items-center gap-1 text-sm" style="color:var(--text-secondary)"><input id="egd-flag-lighting" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${dna.flagLighting ? 'checked' : ''} onchange="_egd.onDnaChange()"> ${t('eggs.dna.flagLighting')}</label>
        <label class="flex items-center gap-1 text-sm" style="color:var(--text-secondary)"><input id="egd-flag-specular" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${dna.flagSpecular ? 'checked' : ''} onchange="_egd.onDnaChange()"> ${t('eggs.dna.flagSpecular')}</label>
        <label class="flex items-center gap-1 text-sm" style="color:var(--text-secondary)"><input id="egd-flag-emboss" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${dna.flagEmboss ? 'checked' : ''} onchange="_egd.onDnaChange()"> ${t('eggs.dna.flagEmboss')}</label>
        <label class="flex items-center gap-1 text-sm" style="color:var(--text-secondary)"><input id="egd-flag-varnish" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${dna.flagVarnish ? 'checked' : ''} onchange="_egd.onDnaChange()"> ${t('eggs.dna.flagVarnish')}</label>
        <label class="flex items-center gap-1 text-sm" style="color:var(--text-secondary)"><input id="egd-flag-rimglow" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${dna.flagRimGlow ? 'checked' : ''} onchange="_egd.onDnaChange()"> ${t('eggs.dna.flagRimGlow')}</label>
      </div>
    </div>
    <div id="egd-dna-code" class="mt-3 text-xs font-mono p-2 rounded break-all cursor-pointer transition-colors" style="color:var(--text-muted);background:var(--bg);border:1px solid var(--bg-border)" title="${t('eggs.clickToCopy')}" onclick="_egd.copyDna(this)">${escHtml(dna.dnaCode || '\u2014')}</div>
    <div class="mt-3 flex gap-2 justify-end">
      <button class="btn-cg btn-cg-ghost" onclick="_egd.randomizeDna()">${t('common.refresh')}</button>
    </div>`;

  // Init 3D preview + auto-save event delegation
  setTimeout(() => {
    if (window._egd) _egd.init3dPreview();
    const locPanel = document.querySelector('[data-tab-panel-egd="loc"]');
    if (locPanel) {
      const autoLoc = debounce(() => { if (window._egd) window._egd.saveLocation(); }, 1500);
      locPanel.addEventListener('input', autoLoc);
      locPanel.addEventListener('change', autoLoc);
    }
    const cfgPanel = document.querySelector('[data-tab-panel-egd="cfg"]');
    if (cfgPanel) {
      const autoCfg = debounce(() => { if (window._egd) window._egd.saveConfig(); }, 1500);
      cfgPanel.addEventListener('input', autoCfg);
      cfgPanel.addEventListener('change', autoCfg);
    }
  }, 100);

  return `<div style="animation:slide-in-right 0.2s ease-out">
    <!-- Header -->
    <div class="section-card" style="border-radius:0.5rem 0.5rem 0 0">
      <div class="detail-header">
        ${detailBackBtn('eggs', t('eggs.collectibles'))}
        <span class="text-sm font-semibold" style="color:var(--text-heading)">\uD83E\uDD5A ${escHtml(entry.title || entry.id)}</span>
        <span class="id-badge ml-2">${escHtml(entry.id)}</span>
        <button class="btn-cg btn-cg-ghost ml-2" onclick="_tpAdmin({dimension:'${escHtml(entry.dimension)}',x:${entry.x||0},y:${entry.y||0},z:${entry.z||0}})" title="${t('common.teleport')}">TP</button>
      </div>
    </div>

    <!-- Split: 3D left | Controls right -->
    <div class="detail-split detail-split-3-2" style="gap:0">
      <!-- Left: 3D Preview (always visible) -->
      <div class="egg-preview-col" style="border:1px solid var(--bg-border);border-top:none;border-radius:0 0 0 0.5rem">
        <div id="egd-3d-container" style="flex:1;min-height:0;border-radius:0"></div>
        <div class="flex gap-2 justify-center py-2" style="background:var(--bg-card);border-top:1px solid var(--bg-border)">
          <button class="btn-cg btn-cg-ghost" onclick="_egd.resetPreviewCamera()">${t('common.reset')}</button>
          <button id="egd-autospin-btn" class="btn-cg btn-cg-ghost" onclick="_egd.toggleAutoSpin()">${t('eggs.autoRotOn')}</button>
        </div>
      </div>

      <!-- Right: Controls (scrollable) -->
      <div style="border:1px solid var(--bg-border);border-top:none;border-left:none;border-radius:0 0 0.5rem 0;overflow-y:auto;max-height:calc(100vh - 3.5rem);background:var(--bg-card)">
        <!-- DNA section -->
        <div style="padding:1rem;border-bottom:1px solid var(--bg-border)">
          <div class="text-xs font-mono tracking-wider uppercase mb-3" style="color:var(--amber)">${t('eggs.dnaAppearance')}</div>
          ${dnaHtml}
        </div>

        <!-- Tabs: Location | Config | Discoveries -->
        ${detailTabBar([
          {id:'loc', label:t('eggs.tabs.location')},
          {id:'cfg', label:t('eggs.tabs.config')},
          {id:'disc', label:t('eggs.tabs.discoveries')},
        ], 'loc', 'egdSwitchTab')}

        <!-- Tab: Location -->
        <div data-tab-panel-egd="loc" style="padding:1rem;border-bottom:1px solid var(--bg-border)">
          <div class="grid grid-cols-2 gap-3">
            <div><label class="text-sm" style="color:var(--text-muted)">${t('common.dimension')}</label>${inp('egd-loc-dim', entry.dimension || 'minecraft:overworld', 'class="input input-sm w-full font-mono" onchange="_egd._debouncedLoc()"')}</div>
            <div><label class="text-sm" style="color:var(--text-muted)">${t('common.scale')}</label>${inp('egd-loc-scale', entry.scale || 1, 'type="number" step="0.1" min="0.1" max="4" onchange="_egd._debouncedLoc()" oninput="_egd._debouncedLoc()"')}</div>
            <div><label class="text-sm" style="color:var(--text-muted)">X</label>${inp('egd-loc-x', entry.x || 0, 'type="number" onchange="_egd._debouncedLoc()"')}</div>
            <div><label class="text-sm" style="color:var(--text-muted)">Y</label>${inp('egd-loc-y', entry.y || 64, 'type="number" onchange="_egd._debouncedLoc()"')}</div>
            <div><label class="text-sm" style="color:var(--text-muted)">Z</label>${inp('egd-loc-z', entry.z || 0, 'type="number" onchange="_egd._debouncedLoc()"')}</div>
            <div class="flex items-end">
              <label class="flex items-center gap-2 text-sm" style="color:var(--text-secondary)"><input id="egd-loc-lost" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${entry.positionLost ? 'checked' : ''} onchange="_egd._debouncedLoc()"> ${t('eggs.positionLost')}</label>
            </div>
          </div>
          <div class="mt-3 flex gap-2 justify-end">
            <button class="btn-cg btn-cg-ghost" onclick="_egd.useMyPosition()">${t('eggs.useMyPosition')}</button>
          </div>
        </div>

        <!-- Tab: Configuration -->
        <div data-tab-panel-egd="cfg" style="padding:1rem;border-bottom:1px solid var(--bg-border);display:none">
          <div class="grid grid-cols-2 gap-3">
            <div><label class="text-sm" style="color:var(--text-muted)">ID</label>
              <div class="flex gap-1">
                ${inp('egd-cfg-newid', entry.id, 'class="input input-sm flex-1 font-mono"')}
                <button class="btn-cg btn-cg-ghost" onclick="_egd.renameEntry()">${t('common.rename')}</button>
              </div>
            </div>
            <div><label class="text-sm" style="color:var(--text-muted)">${t('common.type')}</label>
              <div class="text-sm mt-1" style="color:var(--text-secondary)">\uD83E\uDD5A ${t('eggs.typeEgg')}</div>
            </div>
            <div><label class="text-sm" style="color:var(--text-muted)">${t('common.title')}</label>${inp('egd-cfg-title', entry.title || '')}</div>
            <div><label class="text-sm" style="color:var(--text-muted)">${t('eggs.subtitle')}</label>${inp('egd-cfg-subtitle', entry.subtitle || '')}</div>
            <div class="col-span-2">
              <label class="text-sm" style="color:var(--text-muted)">${t('eggs.renderDistance')}</label>
              <div class="flex items-center gap-2 mt-1">
                ${inp('egd-cfg-renderdist', entry.renderDistanceOverride != null ? entry.renderDistanceOverride : -1, 'type="number" min="-1" max="128" class="input input-sm" style="width:5rem;background:var(--bg-elevated);border-color:var(--bg-border);color:var(--text-primary)"')}
                <span class="text-xs" style="color:var(--muted)">${t('eggs.renderDistanceHelp')}</span>
              </div>
            </div>
          </div>
          <div id="egd-cfg-dirty" class="mt-3 text-right text-xs" style="color:var(--amber);display:none">${t('common.save')}...</div>
        </div>

        <!-- Tab: Discoveries (lazy-loaded) -->
        <div data-tab-panel-egd="disc" style="padding:1rem;border-bottom:1px solid var(--bg-border);display:none">
          <div id="egd-disc-body" class="text-center py-4 text-sm" style="color:var(--text-muted)">${t('common.loading')}</div>
        </div>

        <!-- Give items section -->
        <div style="padding:1rem;border-bottom:1px solid var(--bg-border)">
          <div class="text-xs font-mono tracking-wider uppercase mb-3" style="color:var(--amber)">${t('eggs.giveItem')}</div>
          <div class="flex gap-2 items-end flex-wrap">
            <div class="flex-1" style="min-width:140px">
              <label class="text-sm" style="color:var(--text-muted)">${t('eggs.onlinePlayer')}</label>
              ${inp('egd-give-player', '', 'placeholder="' + t('eggs.playerPseudo') + '"')}
            </div>
            <button class="btn-cg btn-cg-primary" onclick="_egd.giveAdmin()" title="${t('common.give')}">${t('eggs.giveAdminEgg')}</button>
            <button class="btn-cg btn-cg-ghost" onclick="_egd.giveDeco()" title="${t('common.give')}">${t('eggs.giveDecoEgg')}</button>
          </div>
        </div>

        <!-- Danger zone (always visible at bottom) -->
        <div style="padding:1rem">
          <div class="danger-zone">
            <div class="danger-zone-title">${t('common.dangerZone')}</div>
            <p class="text-sm mb-3" style="color:var(--text-muted)">${t('common.deleteConfirm')}</p>
            <button class="btn-cg btn-cg-danger" onclick="_egd.deleteEntry()">${t('common.delete')}</button>
          </div>
        </div>
      </div>
    </div>
  </div>`;
}
