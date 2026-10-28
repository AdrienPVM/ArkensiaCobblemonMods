// ── Zones & Groups ───────────────────────────────────────────────────────────

// ── Tag helpers ──────────────────────────────────────────────────────────────────
function _zvHashColor(tag) {
  if (!tag) return '#6b7280';
  let hash = 0;
  for (let i = 0; i < tag.length; i++) hash = ((hash << 5) - hash + tag.charCodeAt(i)) | 0;
  hash = hash & 0x7FFFFFFF;
  const hue = hash % 360;
  return `hsl(${hue}, 65%, 55%)`;
}

function _zvTagBadge(tag, tagConfigs, removable, zoneId) {
  const tc = tagConfigs && tagConfigs.find(c => c.id === tag);
  const color = tc && tc.displayColor ? tc.displayColor : _zvHashColor(tag);
  const label = tc && tc.label && tc.label !== tc.id ? escHtml(tc.label) : escHtml(tag);
  let removeBtn = '';
  if (removable && zoneId) {
    removeBtn = `<span class="tag-badge-remove" onclick="event.stopPropagation();_zvRemoveTag('${escHtml(zoneId)}','${escHtml(tag)}')">&times;</span>`;
  }
  return `<span class="tag-badge" style="background:${escHtml(color)}22;border-color:${escHtml(color)}44;color:${escHtml(color)}">` +
         `<span class="tag-badge-dot" style="background:${escHtml(color)}"></span>${label}${removeBtn}</span>`;
}

// ── Zones view ──────────────────────────────────────────────────────────────────
async function buildZones(app) {
  let zones, groups, folders, tagConfigs;
  try {
    [zones, groups, folders, tagConfigs] = await Promise.all([API.zones.list(), API.groups.list(), API.zoneFolders.list(), API.zones.tagConfigs().catch(() => [])]);
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const groupMap = {};
  groups.forEach(g => { groupMap[g.id] = g; });

  const _z = window._zv = {
    zones, groups, groupMap, folders, tagConfigs, app,
    search: '', worldFilter: '', sortBy: 'name',
    expandedFolders: {},
    selectedIds: new Set(),
    _edit: null,

    // ── Batch selection ────────────────────────────────────────────────
    toggleSelect(zoneId, checked) {
      if (checked) this.selectedIds.add(zoneId);
      else this.selectedIds.delete(zoneId);
      this._renderBatchBar();
      this._syncHeaderCheckbox();
    },

    toggleSelectAll() {
      const filtered = this.getFiltered();
      if (this.selectedIds.size === filtered.length && filtered.length > 0) {
        this.selectedIds.clear();
      } else {
        filtered.forEach(z => this.selectedIds.add(z.id));
      }
      this.renderFolderView();
    },

    clearSelection() {
      this.selectedIds.clear();
      this._renderBatchBar();
      document.querySelectorAll('.zf-zone-cb').forEach(cb => { cb.checked = false; });
      this._syncHeaderCheckbox();
    },

    _syncHeaderCheckbox() {
      const cb = document.getElementById('zf-select-all-cb');
      if (!cb) return;
      const filtered = this.getFiltered();
      cb.checked = filtered.length > 0 && this.selectedIds.size === filtered.length;
      cb.indeterminate = this.selectedIds.size > 0 && this.selectedIds.size < filtered.length;
    },

    _renderBatchBar() {
      const bar = document.getElementById('zf-batch-bar');
      if (!bar) return;
      const count = this.selectedIds.size;
      if (count === 0) {
        bar.style.display = 'none';
        return;
      }
      bar.style.display = 'flex';
      const countEl = bar.querySelector('.zf-batch-count');
      if (countEl) countEl.textContent = t('zones.batch.selected', {count});
      // Update checkboxes visual state
      document.querySelectorAll('.zf-zone-cb').forEach(cb => {
        cb.checked = this.selectedIds.has(cb.dataset.zoneId);
      });
      this._syncHeaderCheckbox();
    },

    async batchDelete() {
      const ids = [...this.selectedIds];
      if (!ids.length) return;
      if (!await confirmAction(t('zones.batch.confirmDelete', {count: ids.length}))) return;
      let deleted = 0;
      for (const id of ids) {
        try {
          await API.zones.delete(id);
          deleted++;
        } catch (e) { app.toast(e.message, 'error'); }
      }
      if (deleted > 0) {
        app.toast(t('zones.batch.deleted', {count: deleted}), 'success');
        this.selectedIds.clear();
        try {
          this.zones = await API.zones.list();
          this.renderFolderView();
        } catch (e) { app.toast(e.message, 'error'); }
      }
    },

    async batchAssignGroup(groupId) {
      if (!groupId) { app.toast(t('zones.batch.noGroup'), 'error'); return; }
      const ids = [...this.selectedIds];
      if (!ids.length) return;
      let assigned = 0;
      for (const zoneId of ids) {
        try {
          await API.groups.assignZone(groupId, zoneId);
          assigned++;
        } catch (e) { app.toast(e.message, 'error'); }
      }
      const groupName = this.groupMap[groupId] ? this.groupMap[groupId].name : groupId;
      if (assigned > 0) {
        app.toast(t('zones.batch.assigned', {count: assigned, group: groupName}), 'success');
        this.selectedIds.clear();
        try {
          this.zones = await API.zones.list();
          this.renderFolderView();
        } catch (e) { app.toast(e.message, 'error'); }
      }
    },

    async batchRemoveGroup() {
      const ids = [...this.selectedIds];
      if (!ids.length) return;
      let removed = 0;
      for (const zoneId of ids) {
        const zone = this.zones.find(z => z.id === zoneId);
        if (!zone || !zone.groupId) continue;
        try {
          await API.groups.unassignZone(zone.groupId, zoneId);
          removed++;
        } catch (e) { app.toast(e.message, 'error'); }
      }
      if (removed > 0) {
        app.toast(t('zones.batch.removed', {count: removed}), 'success');
        this.selectedIds.clear();
        try {
          this.zones = await API.zones.list();
          this.renderFolderView();
        } catch (e) { app.toast(e.message, 'error'); }
      }
    },

    async batchMoveFolder(folderId) {
      const ids = [...this.selectedIds];
      if (!ids.length) return;
      // Remove from all folders first
      for (const f of this.folders) {
        f.zoneIds = (f.zoneIds || []).filter(id => !ids.includes(id));
      }
      // Add to target folder if specified
      if (folderId) {
        const target = this.folders.find(f => f.id === folderId);
        if (target) {
          if (!target.zoneIds) target.zoneIds = [];
          ids.forEach(id => { if (!target.zoneIds.includes(id)) target.zoneIds.push(id); });
          this.expandedFolders[folderId] = true;
        }
      }
      try {
        await API.zoneFolders.save(this.folders);
        app.toast(t('zones.batch.moved', {count: ids.length}), 'success');
        this.selectedIds.clear();
        this.renderFolderView();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async createZone() {
      const inp = document.getElementById('zone-name-input');
      if (!inp) return;
      const name = inp.value.trim();
      if (!name) { app.toast(t('zones.nameRequired'), 'error'); return; }
      try {
        const zone = await API.zones.create({ name });
        app.toast(t('zones.created', {name}), 'success');
        inp.value = '';
        app.navigate('zones:' + zone.id);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    getFiltered() {
      let f = this.zones.filter(z => {
        if (this.search) {
          const q = this.search.toLowerCase();
          if (!z.name.toLowerCase().includes(q) && !z.id.toLowerCase().includes(q)) return false;
        }
        if (this.worldFilter && z.world !== this.worldFilter) return false;
        return true;
      });
      f.sort((a, b) => this.sortBy === 'priority' ? b.priority - a.priority : a.name.localeCompare(b.name));
      return f;
    },

    // Build a map of zoneId → zone object for quick lookup
    _zoneMap() {
      const m = {};
      this.getFiltered().forEach(z => { m[z.id] = z; });
      return m;
    },

    renderFolderView() {
      const container = document.getElementById('zones-folder-view');
      if (!container) return;
      const zm = this._zoneMap();
      const allFilteredIds = new Set(Object.keys(zm));
      const assignedIds = new Set();

      // Sort folders by order
      const sorted = [...this.folders].sort((a, b) => (a.order || 0) - (b.order || 0));

      let html = '';

      // Batch action bar (always in DOM, hidden when no selection)
      html += _zfBatchBarHtml(this.selectedIds.size, this.groups, this.folders);

      for (const f of sorted) {
        const fZones = (f.zoneIds || []).filter(id => allFilteredIds.has(id)).map(id => zm[id]);
        // Also track which zones are in folders (even if filtered out)
        (f.zoneIds || []).forEach(id => assignedIds.add(id));
        const expanded = this.expandedFolders[f.id] !== false; // default open
        html += _zfFolderHtml(f, fZones, expanded, this.folders, this.groupMap, this.selectedIds);
      }

      // Uncategorized zones
      const uncat = this.getFiltered().filter(z => !assignedIds.has(z.id));
      if (uncat.length > 0) {
        html += '<div class="zf-uncat">';
        html += '<div class="zf-uncat-title">' + t('zones.uncategorized', {count: uncat.length}) + '</div>';
        for (const z of uncat) {
          html += _zfZoneHtml(z, this.folders, this.groupMap, this.selectedIds);
        }
        html += '</div>';
      }

      // Show empty state when no zones match
      if (!sorted.length && !uncat.length) {
        html += '<div class="empty-state py-12"><span class="empty-state-icon">\u2B1C</span><span class="empty-state-text">' + t('zones.noZones') + '</span></div>';
      }

      container.innerHTML = html;
      const cnt = document.getElementById('zones-count');
      if (cnt) cnt.textContent = this.zones.length;
      this._syncHeaderCheckbox();
    },

    toggleFolder(folderId) {
      this.expandedFolders[folderId] = this.expandedFolders[folderId] === false ? true : false;
      this.renderFolderView();
    },

    async createFolder() {
      const inp = document.getElementById('zf-new-folder-input');
      if (!inp) return;
      const name = inp.value.trim();
      if (!name) { app.toast(t('zones.folderNameRequired'), 'error'); return; }
      try {
        const folder = await API.zoneFolders.create({ name });
        this.folders.push(folder);
        this.expandedFolders[folder.id] = true;
        inp.value = '';
        app.toast(t('zones.folderCreated'), 'success');
        this.renderFolderView();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteFolder(folderId, name) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.zoneFolders.delete(folderId);
        this.folders = this.folders.filter(f => f.id !== folderId);
        app.toast(t('zones.folderDeleted'), 'success');
        this.renderFolderView();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    startRenameFolder(folderId) {
      const nameEl = document.getElementById('zf-name-' + folderId);
      if (!nameEl) return;
      const folder = this.folders.find(f => f.id === folderId);
      if (!folder) return;
      nameEl.innerHTML = '<input class="zf-inline-input" value="' + escHtml(folder.name) + '" '
        + 'onblur="_zv.finishRenameFolder(\'' + escHtml(folderId) + '\', this.value)" '
        + 'onkeydown="if(event.key===\'Enter\')this.blur();if(event.key===\'Escape\'){this.dataset.cancel=\'1\';this.blur()}" '
        + 'autofocus>';
      nameEl.querySelector('input').select();
    },

    async finishRenameFolder(folderId, newName) {
      const inp = document.querySelector('#zf-name-' + folderId + ' input');
      if (inp && inp.dataset.cancel === '1') {
        this.renderFolderView();
        return;
      }
      newName = (newName || '').trim();
      const folder = this.folders.find(f => f.id === folderId);
      if (!folder || !newName || newName === folder.name) {
        this.renderFolderView();
        return;
      }
      folder.name = newName;
      try {
        await API.zoneFolders.save(this.folders);
        this.renderFolderView();
      } catch (e) {
        app.toast(e.message, 'error');
        this.renderFolderView();
      }
    },

    async moveZone(zoneId, targetFolderId) {
      // Remove zone from any existing folder
      for (const f of this.folders) {
        const idx = (f.zoneIds || []).indexOf(zoneId);
        if (idx !== -1) f.zoneIds.splice(idx, 1);
      }
      // Add to target folder if specified
      if (targetFolderId) {
        const target = this.folders.find(f => f.id === targetFolderId);
        if (target) {
          if (!target.zoneIds) target.zoneIds = [];
          target.zoneIds.push(zoneId);
          this.expandedFolders[targetFolderId] = true;
        }
      }
      try {
        await API.zoneFolders.save(this.folders);
        this.renderFolderView();
      } catch (e) { app.toast(e.message, 'error'); }
    },

  };

  // Build filter options
  const worlds = [...new Set(zones.map(z => z.world))].sort();
  const worldOpts = worlds.map(w => '<option value="' + escHtml(w) + '">' + escHtml(fmtWorld(w)) + '</option>').join('');

  // Also expose groups controller for the Groupes tab
  window._gv = {
    groups, zones, app,
    search: '',

    getFiltered() {
      if (!this.search) return this.groups;
      const q = this.search.toLowerCase();
      return this.groups.filter(g => g.name.toLowerCase().includes(q) || g.id.toLowerCase().includes(q));
    },

    renderTable() {
      const filtered = this.getFiltered();
      const tbody = document.getElementById('groups-tbody');
      if (tbody) tbody.innerHTML = filtered.map(g => groupRowHtml(g)).join('');
      const cnt = document.getElementById('groups-count');
      if (cnt) cnt.textContent = filtered.length;
    },

    async createGroup() {
      const inp = document.getElementById('group-name-input');
      if (!inp) return;
      const name = inp.value.trim();
      if (!name) return;
      try {
        await API.groups.create({ name });
        app.toast(t('zones.groupCreated'), 'success');
        inp.value = '';
        this.groups = await API.groups.list();
        this.renderTable();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    openDetail(id) {
      app.navigate('groups:' + id);
    },

    async deleteGroup(id, name) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.groups.delete(id);
        app.toast(t('zones.groupDeleted'), 'success');
        this.groups = this.groups.filter(g => g.id !== id);
        this.renderTable();
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  // Initial rows
  const groupRows = groups.map(g => groupRowHtml(g)).join('');

  // Tab bar for Zones / Groupes / Tags
  const zvTabs = [
    { id: 'zones', label: t('zones.tabZones', {count: zones.length}) },
    { id: 'groups', label: t('zones.tabGroups', {count: groups.length}) },
    { id: 'tagconfigs', label: t('zones.tagConfigs') },
  ];

  // Initialize expanded state (all open by default)
  folders.forEach(f => { _z.expandedFolders[f.id] = true; });

  const html = `
<div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
  <div class="section-card">
    <div class="section-card-header">
      <span class="text-sm">\u2B1C</span>
      <span class="section-card-title">${t('zones.title')}</span>
    </div>

    ${detailTabBar(zvTabs, 'zones', 'zvSwitchTab')}

    <!-- Tab: Zones -->
    <div data-tab-panel-zv="zones">
      <div class="filter-bar" style="flex-wrap:wrap;row-gap:0.5rem">
        <input type="checkbox" id="zf-select-all-cb" class="zf-zone-cb zf-header-cb"
          onchange="_zv.toggleSelectAll()" title="${t('zones.batch.selectAll')}">
        <input type="text" placeholder="${t('common.search')}"
          class="input input-sm input-bordered text-sm flex-1 max-w-xs" style="min-width:8rem"
          oninput="_zv.search=this.value;_zv.selectedIds.clear();_zv.renderFolderView()">
        <select class="select select-sm select-bordered text-sm"
          onchange="_zv.worldFilter=this.value;_zv.selectedIds.clear();_zv.renderFolderView()">
          <option value="">${t('common.all')}</option>${worldOpts}
        </select>
        <select class="select select-sm select-bordered text-sm"
          onchange="_zv.sortBy=this.value;_zv.renderFolderView()">
          <option value="name">${t('common.name')}</option>
          <option value="priority">${t('zones.priority')}</option>
        </select>
        <div class="flex-1" style="min-width:1rem"></div>
        <input type="text" id="zf-new-folder-input" placeholder="${t('zones.newFolder')}"
          class="input input-sm input-bordered text-sm w-40"
          onkeydown="if(event.key==='Enter')_zv.createFolder()">
        <button class="btn-cg btn-cg-ghost" onclick="_zv.createFolder()" title="${t('zones.newFolderTooltip')}">${t('zones.newFolderBtn')}</button>
        <input type="text" id="zone-name-input" placeholder="${t('zones.newZone')}"
          class="input input-sm input-bordered text-sm w-40"
          onkeydown="if(event.key==='Enter')_zv.createZone()">
        <button class="btn-cg btn-cg-primary" onclick="_zv.createZone()" title="${t('zones.newZoneTooltip')}">${t('zones.newZoneBtn')}</button>
      </div>
      <div id="zones-folder-view" style="padding:0.5rem 0.75rem"></div>
    </div>

    <!-- Tab: Groupes -->
    <div data-tab-panel-zv="groups" style="display:none">
      <div class="filter-bar">
        <input type="text" placeholder="${t('common.search')}"
          class="input input-sm input-bordered text-sm max-w-xs"
          oninput="_gv.search=this.value;_gv.renderTable()">
        <div class="flex-1"></div>
        <input type="text" id="group-name-input" placeholder="${t('zones.newGroup')}"
          class="input input-sm input-bordered text-sm max-w-xs"
          onkeydown="if(event.key==='Enter')_gv.createGroup()">
        <button class="btn-cg btn-cg-primary" onclick="_gv.createGroup()">${t('common.create')}</button>
      </div>
      <div class="overflow-x-auto">
        <table class="cg-table">
          <thead><tr>
            <th>ID</th><th>${t('common.name')}</th><th>${t('nav.zones')}</th><th>${t('zones.activeFlags')}</th><th></th>
          </tr></thead>
          <tbody id="groups-tbody">${groupRows}</tbody>
        </table>
      </div>
      ${groups.length === 0 ? '<div class="empty-state py-12"><span class="empty-state-icon">\u{1F5C2}</span><span class="empty-state-text">' + t('zones.noGroups') + '</span></div>' : ''}
    </div>

    <!-- Tab: Tag Configs -->
    <div data-tab-panel-zv="tagconfigs" style="display:none">
      <div id="zv-tag-configs" class="section-card-body"></div>
    </div>
  </div>
</div>`;

  // Render folder view after DOM is ready
  setTimeout(() => _z.renderFolderView(), 0);

  return html;
}

function zvSwitchTab(tabId, btn) {
  switchDetailTab(tabId, btn, 'zv');
  if (tabId === 'tagconfigs') _zvRenderTagConfigs();
}

// ── Tag config panel ──────────────────────────────────────────────────────
async function _zvRenderTagConfigs() {
  const _z = window._zv;
  if (!_z) return;
  let configs;
  try {
    configs = await API.zones.tagConfigs();
    _z.tagConfigs = configs;
  } catch (e) { _z.app.toast(e.message, 'error'); return; }

  const container = document.getElementById('zv-tag-configs');
  if (!container) return;

  let html = `<div class="section-card slide-in">
    <div class="section-card-header">
      <h3 class="section-card-title">${t('zones.tagConfigs.title')}</h3>
      <div style="display:flex;gap:8px;align-items:center">
        <input id="zv-new-tag-id" class="input input-sm input-bordered" style="width:160px" placeholder="${t('zones.tagConfigs.new')}"
               onkeydown="if(event.key==='Enter')_zvCreateTagConfig()">
        <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_zvCreateTagConfig()">${t('zones.tagConfigs.newBtn')}</button>
      </div>
    </div>
    <div class="section-card-body">`;

  if (configs.length === 0) {
    html += `<div class="empty-state"><span class="empty-state-icon">\uD83C\uDFF7\uFE0F</span><span class="empty-state-text">${t('zones.tagConfigs.empty')}</span></div>`;
  } else {
    html += `<table class="cg-table"><thead><tr>
      <th>ID</th>
      <th>${t('zones.tagConfigs.label')}</th>
      <th>${t('zones.tagConfigs.color')}</th>
      <th>${t('zones.tagConfigs.ownershipLimit')} ${tip('tip.zones.tagLimit')}</th>
      <th></th>
    </tr></thead><tbody>`;
    for (const tc of configs) {
      const color = tc.displayColor || _zvHashColor(tc.id);
      html += `<tr>
        <td><span class="id-badge">${escHtml(tc.id)}</span></td>
        <td><input class="input input-sm input-bordered" style="width:120px" value="${escHtml(tc.label || '')}"
                   onchange="_zvUpdateTagConfig('${escHtml(tc.id)}',{label:this.value})"></td>
        <td><div style="display:flex;align-items:center;gap:8px">
          <input type="color" value="${escHtml(tc.displayColor || '#6b7280')}" style="width:32px;height:32px;border:none;cursor:pointer;background:transparent"
                 onchange="_zvUpdateTagConfig('${escHtml(tc.id)}',{color:this.value})">
          <span class="tag-badge" style="background:${escHtml(color)}22;border-color:${escHtml(color)}44;color:${escHtml(color)}">
            <span class="tag-badge-dot" style="background:${escHtml(color)}"></span>${escHtml(tc.label || tc.id)}</span>
        </div></td>
        <td><input type="number" class="input input-sm input-bordered" style="width:80px" min="0" value="${tc.ownershipLimit || 0}"
                   onchange="_zvUpdateTagConfig('${escHtml(tc.id)}',{ownershipLimit:parseInt(this.value)||0})"></td>
        <td><button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_zvDeleteTagConfig('${escHtml(tc.id)}')">\u2715</button></td>
      </tr>`;
    }
    html += `</tbody></table>`;
  }
  html += `</div></div>`;
  container.innerHTML = html;
}

async function _zvCreateTagConfig() {
  const input = document.getElementById('zv-new-tag-id');
  if (!input) return;
  const id = input.value.trim().toLowerCase().replace(/[^a-z0-9-]/g, '');
  if (!id) { window._zv.app.toast(t('zones.tagConfigs.invalidId'), 'error'); return; }
  try {
    await API.zones.tagConfigPut(id, { label: id, color: null, ownershipLimit: 0 });
    window._zv.app.toast(t('zones.tagConfigs.saved', { id }), 'success');
    input.value = '';
    _zvRenderTagConfigs();
  } catch (e) { window._zv.app.toast(e.message, 'error'); }
}

async function _zvUpdateTagConfig(tagId, updates) {
  const _z = window._zv;
  try {
    const current = (_z.tagConfigs || []).find(tc => tc.id === tagId) || {};
    const data = {
      label: updates.label !== undefined ? updates.label : (current.label || tagId),
      color: updates.color !== undefined ? updates.color : (current.color || null),
      ownershipLimit: updates.ownershipLimit !== undefined ? updates.ownershipLimit : (current.ownershipLimit || 0),
    };
    await API.zones.tagConfigPut(tagId, data);
    _z.app.toast(t('zones.tagConfigs.saved', { id: tagId }), 'success');
    _zvRenderTagConfigs();
  } catch (e) { _z.app.toast(e.message, 'error'); }
}

async function _zvDeleteTagConfig(tagId) {
  if (!await confirmAction(t('zones.tagConfigs.confirmDelete', { id: tagId }))) return;
  try {
    await API.zones.tagConfigDelete(tagId);
    window._zv.app.toast(t('zones.tagConfigs.deleted', { id: tagId }), 'success');
    _zvRenderTagConfigs();
  } catch (e) { window._zv.app.toast(e.message, 'error'); }
}

// ── Tag input (zone detail) ──────────────────────────────────────────────
function _zvTagInputHtml(zoneId, currentTags, tagConfigs) {
  const inputId = 'ztag-' + zoneId.replace(/[^a-zA-Z0-9]/g, '');
  const badges = (currentTags || []).map(tag => _zvTagBadge(tag, tagConfigs, true, zoneId)).join('');

  return `<div style="position:relative">
    <div class="tag-input-container" onclick="document.getElementById('${inputId}').focus()">
      ${badges}
      <input id="${inputId}" class="tag-input-field" type="text" placeholder="${t('zones.tagsPlaceholder')}"
             oninput="_zvTagAutocomplete(this,'${escHtml(zoneId)}')"
             onkeydown="if(event.key==='Enter'){event.preventDefault();_zvAddTag('${escHtml(zoneId)}',this.value);this.value='';_zvHideAutocomplete()}"
             autocomplete="off">
    </div>
    <div id="${inputId}-ac" class="tag-autocomplete" style="display:none"></div>
  </div>`;
}

async function _zvAddTag(zoneId, tag) {
  tag = tag.trim().toLowerCase().replace(/[^a-z0-9-]/g, '');
  if (!tag) return;
  const _z = window._zv;
  const zone = _z ? _z.zones.find(z => z.id === zoneId) : null;
  // Also check _zd (zone detail state)
  const _zd = window._zd;
  const zoneObj = _zd && _zd.zone && _zd.zone.id === zoneId ? _zd.zone : zone;
  if (!zoneObj) return;
  const tags = zoneObj.tags ? [...zoneObj.tags] : [];
  if (tags.includes(tag)) return;
  tags.push(tag);
  try {
    await API.zones.update(zoneId, { tags });
    zoneObj.tags = tags;
    if (_zd && _zd.zone && _zd.zone.id === zoneId) _zd.zone.tags = tags;
    if (zone) zone.tags = tags;
    const app = _z ? _z.app : (_zd ? _zd.app : null);
    if (app) app.toast(t('zones.tagAdded'), 'success');
    _zvRefreshTagInput(zoneId);
  } catch (e) {
    const app = _z ? _z.app : (_zd ? _zd.app : null);
    if (app) app.toast(e.message, 'error');
  }
}

async function _zvRemoveTag(zoneId, tag) {
  const _z = window._zv;
  const zone = _z ? _z.zones.find(z => z.id === zoneId) : null;
  const _zd = window._zd;
  const zoneObj = _zd && _zd.zone && _zd.zone.id === zoneId ? _zd.zone : zone;
  if (!zoneObj) return;
  const tags = (zoneObj.tags || []).filter(t => t !== tag);
  try {
    await API.zones.update(zoneId, { tags });
    zoneObj.tags = tags;
    if (_zd && _zd.zone && _zd.zone.id === zoneId) _zd.zone.tags = tags;
    if (zone) zone.tags = tags;
    const app = _z ? _z.app : (_zd ? _zd.app : null);
    if (app) app.toast(t('zones.tagRemoved'), 'success');
    _zvRefreshTagInput(zoneId);
  } catch (e) {
    const app = _z ? _z.app : (_zd ? _zd.app : null);
    if (app) app.toast(e.message, 'error');
  }
}

function _zvRefreshTagInput(zoneId) {
  const el = document.getElementById('zd-tag-section');
  if (!el) return;
  const _zd = window._zd;
  const _z = window._zv;
  const zoneObj = _zd && _zd.zone && _zd.zone.id === zoneId ? _zd.zone : null;
  if (!zoneObj) return;
  const tc = _z ? _z.tagConfigs : [];
  el.innerHTML = `<label class="field-label">${t('zones.tags')} ${tip('tip.zones.tags')}</label>` +
    _zvTagInputHtml(zoneId, zoneObj.tags, tc);
}

function _zvTagAutocomplete(input, zoneId) {
  const val = input.value.trim().toLowerCase();
  const acEl = document.getElementById(input.id + '-ac');
  if (!acEl) return;
  if (!val) { acEl.style.display = 'none'; return; }
  const _z = window._zv;
  const _zd = window._zd;
  const zoneObj = _zd && _zd.zone && _zd.zone.id === zoneId ? _zd.zone : (_z ? _z.zones.find(z => z.id === zoneId) : null);
  const currentTags = zoneObj && zoneObj.tags ? zoneObj.tags : [];

  // Merge unique tags from all zones + configured tags
  const allTagSet = new Set();
  if (_z) {
    _z.zones.forEach(z => (z.tags || []).forEach(tg => allTagSet.add(tg)));
    (_z.tagConfigs || []).forEach(tc => allTagSet.add(tc.id));
  }

  const suggestions = [...allTagSet]
    .filter(tg => tg.includes(val) && !currentTags.includes(tg))
    .sort()
    .slice(0, 8);

  if (suggestions.length === 0) { acEl.style.display = 'none'; return; }

  const tc = _z ? _z.tagConfigs || [] : [];
  acEl.innerHTML = suggestions.map(tg => {
    const conf = tc.find(c => c.id === tg);
    const color = conf && conf.displayColor ? conf.displayColor : _zvHashColor(tg);
    return `<div class="tag-autocomplete-item" onclick="_zvAddTag('${escHtml(zoneId)}','${escHtml(tg)}');document.getElementById('${input.id}').value='';_zvHideAutocomplete()">` +
           `<span class="tag-badge-dot" style="background:${escHtml(color)}"></span>${escHtml(tg)}</div>`;
  }).join('');
  acEl.style.display = 'block';
}

function _zvHideAutocomplete() {
  document.querySelectorAll('.tag-autocomplete').forEach(el => { el.style.display = 'none'; });
}

// Close autocomplete on click outside
document.addEventListener('click', (e) => {
  if (!e.target.closest('.tag-input-container') && !e.target.closest('.tag-autocomplete')) {
    _zvHideAutocomplete();
  }
});

function zoneRowHtml(z, groupMap) {
  const gName = z.groupId && groupMap[z.groupId] ? escHtml(groupMap[z.groupId].name) : '';
  const cx = Math.round(((z.minX || 0) + (z.maxX || 0)) / 2);
  const cy = z.maxY || 64;
  const cz = Math.round(((z.minZ || 0) + (z.maxZ || 0)) / 2);
  return '<tr class="cursor-pointer" onclick="_nav(\'zones:' + escHtml(z.id) + '\')">\
    <td><span class="id-badge">' + escHtml(z.id) + '</span></td>\
    <td style="color:var(--text-heading)" class="text-sm font-medium">' + escHtml(z.name) + '</td>\
    <td>' + worldBadgeHtml(z.world) + '</td>\
    <td class="td-mono">' + z.priority + '</td>\
    <td class="text-sm" style="color:var(--text-muted)">' + (gName || '<span style="opacity:0.5">\u2014</span>') + '</td>\
    <td class="td-mono">' + z.members + '</td>\
    <td class="text-right" onclick="event.stopPropagation()">\
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_tpAdmin({x:' + cx + ',y:' + cy + ',z:' + cz + '})" title="${t(\'zones.tpTooltip\')}">TP</button>\
    </td>\
  </tr>';
}

// ── Batch action bar ──────────────────────────────────────────────────────
function _zfBatchBarHtml(count, groups, folders) {
  const groupOpts = groups.map(g =>
    '<option value="' + escHtml(g.id) + '">' + escHtml(g.name) + '</option>'
  ).join('');
  const folderOpts = folders.map(f =>
    '<option value="' + escHtml(f.id) + '">' + escHtml(f.name) + '</option>'
  ).join('');

  let html = '<div id="zf-batch-bar" class="zf-batch-bar"' + (count === 0 ? ' style="display:none"' : '') + '>';
  html += '<span class="zf-batch-count">' + t('zones.batch.selected', {count}) + '</span>';
  html += '<div class="zf-batch-actions">';

  // Assign to group
  html += '<select class="select select-sm select-bordered text-sm" style="max-width:12rem" onchange="if(this.value){_zv.batchAssignGroup(this.value);this.value=\'\'}">';
  html += '<option value="">' + t('zones.batch.assignGroup') + '</option>';
  html += groupOpts;
  html += '</select>';

  // Remove from group
  html += '<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_zv.batchRemoveGroup()">' + t('zones.batch.removeGroup') + '</button>';

  html += '<span class="zf-batch-sep"></span>';

  // Move to folder
  html += '<select class="select select-sm select-bordered text-sm" style="max-width:12rem" onchange="if(this.value){_zv.batchMoveFolder(this.value===\'__none__\'?\'\':this.value);this.value=\'\'}">';
  html += '<option value="">' + t('zones.batch.moveFolder') + '</option>';
  html += '<option value="__none__">' + t('zones.batch.noFolder') + '</option>';
  html += folderOpts;
  html += '</select>';

  html += '<span class="zf-batch-sep"></span>';

  // Delete
  html += '<button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_zv.batchDelete()">' + t('zones.batch.delete') + '</button>';

  html += '</div>';
  html += '<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_zv.clearSelection()" title="' + t('zones.batch.deselectAll') + '">\u2715</button>';
  html += '</div>';
  return html;
}

// ── Zone folder helpers ───────────────────────────────────────────────────
function _zfFolderHtml(folder, zonesInFolder, expanded, allFolders, groupMap, selectedIds) {
  const chevronCls = expanded ? 'zf-chevron open' : 'zf-chevron';
  const fid = escHtml(folder.id);
  const fname = escHtml(folder.name);
  let html = '<div class="zf-folder">';
  html += '<div class="zf-header" onclick="_zv.toggleFolder(\'' + fid + '\')">';
  html += '<span class="' + chevronCls + '">\u25B6</span>';
  html += '<span style="font-size:0.9rem">\uD83D\uDCC1</span>';
  html += '<span class="zf-name" id="zf-name-' + fid + '">' + fname + '</span>';
  html += '<span class="zf-count">' + zonesInFolder.length + '</span>';
  html += '<span class="zf-actions" onclick="event.stopPropagation()">';
  html += '<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_zv.startRenameFolder(\'' + fid + '\')" title="${t(\'common.rename\')}">\u270E</button>';
  html += '<button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_zv.deleteFolder(\'' + fid + '\',\'' + fname.replace(/'/g, "\\'") + '\')" title="${t(\'common.delete\')}">\u2715</button>';
  html += '</span>';
  html += '</div>';
  if (expanded) {
    for (const z of zonesInFolder) {
      html += _zfZoneHtml(z, allFolders, groupMap, selectedIds);
    }
    if (zonesInFolder.length === 0) {
      html += '<div style="padding:0.375rem 0.75rem 0.375rem 2.25rem;color:var(--muted);font-size:var(--type-xs);font-style:italic">${t(\'common.none\')}</div>';
    }
  }
  html += '</div>';
  return html;
}

function _zfZoneHtml(zone, allFolders, groupMap, selectedIds) {
  const z = zone;
  const gName = z.groupId && groupMap[z.groupId] ? escHtml(groupMap[z.groupId].name) : '';
  const cx = Math.round(((z.minX || 0) + (z.maxX || 0)) / 2);
  const cy = z.maxY || 64;
  const cz = Math.round(((z.minZ || 0) + (z.maxZ || 0)) / 2);
  // Find which folder this zone is in
  let currentFolderId = '';
  for (const f of allFolders) {
    if ((f.zoneIds || []).includes(z.id)) { currentFolderId = f.id; break; }
  }
  const folderOpts = allFolders.map(f =>
    '<option value="' + escHtml(f.id) + '"' + (f.id === currentFolderId ? ' selected' : '') + '>' + escHtml(f.name) + '</option>'
  ).join('');

  const isChecked = selectedIds && selectedIds.has(z.id);
  let html = '<div class="zf-zone" onclick="_nav(\'zones:' + escHtml(z.id) + '\')">';
  html += '<input type="checkbox" class="zf-zone-cb" data-zone-id="' + escHtml(z.id) + '"' + (isChecked ? ' checked' : '') + ' onclick="event.stopPropagation();_zv.toggleSelect(\'' + escHtml(z.id) + '\',this.checked)">';
  html += '<span class="id-badge">' + escHtml(z.id) + '</span>';
  html += '<div class="zf-zone-info">';
  html += '<span class="zf-zone-name">' + escHtml(z.name) + '</span>';
  if (z.tags && z.tags.length) {
    html += '<span style="display:inline-flex;flex-wrap:wrap;gap:3px">';
    const _ztc = window._zv ? window._zv.tagConfigs : [];
    z.tags.forEach(tg => { html += _zvTagBadge(tg, _ztc, false, null); });
    html += '</span>';
  }
  if (z.forSale) {
    html += '<span class="tag-badge" style="background:var(--green);color:var(--text-heading);font-size:0.65rem"><span class="tag-badge-dot" style="background:var(--text-heading)"></span>' + t('zones.sale.forSale') + '</span>';
  }
  if (z.sold) {
    html += '<span class="tag-badge" style="background:var(--red);color:var(--text-heading);font-size:0.65rem"><span class="tag-badge-dot" style="background:var(--text-heading)"></span>' + t('zones.sale.sold') + '</span>';
  }
  html += worldBadgeHtml(z.world);
  html += '<span class="zf-zone-meta">p:' + z.priority + '</span>';
  if (gName) html += '<span class="zf-zone-meta">' + gName + '</span>';
  html += '</div>';
  html += '<span onclick="event.stopPropagation()">';
  html += '<select class="zf-move-select" onchange="_zv.moveZone(\'' + escHtml(z.id) + '\',this.value)" title="${t(\'common.select\')}">';
  html += '<option value=""' + (!currentFolderId ? ' selected' : '') + '>\u2014</option>';
  html += folderOpts;
  html += '</select>';
  html += '</span>';
  html += '<button class="btn-cg btn-cg-ghost btn-cg-sm" style="opacity:0;transition:opacity 0.15s" onmouseenter="this.style.opacity=1" onmouseleave="this.style.opacity=0" onclick="event.stopPropagation();_tpAdmin({x:' + cx + ',y:' + cy + ',z:' + cz + '})" title="${t(\'zones.tpTooltip\')}">TP</button>';
  html += '</div>';
  return html;
}

// Groups view — now merged into Zones view, redirect for backwards compat
async function buildGroups(app) { return buildZones(app); }

function groupRowHtml(g) {
  const nav = "_nav('groups:" + escHtml(g.id) + "')";
  return '<tr class="cursor-pointer" style="transition:background .15s" onmouseenter="this.style.background=\'var(--bg-elevated)\'" onmouseleave="this.style.background=\'\'">\
    <td onclick="' + nav + '"><span class="id-badge">' + escHtml(g.id) + '</span></td>\
    <td onclick="' + nav + '" style="color:var(--text-primary)" class="text-sm">' + escHtml(g.name) + '</td>\
    <td class="td-mono" onclick="' + nav + '">' + g.zoneCount + '</td>\
    <td class="td-mono" onclick="' + nav + '">' + g.flagCount + '</td>\
    <td class="text-right">\
      <button class="btn-cg btn-cg-ghost" onclick="_gv.openDetail(\'' + escHtml(g.id) + '\')">${t(\'common.details\')}</button>\
      <button class="btn-cg btn-cg-danger" onclick="event.stopPropagation();_gv.deleteGroup(\'' + escHtml(g.id) + '\',\'' + escHtml(g.name).replace(/'/g, "\\'") + '\')">${t(\'common.delete\')}</button>\
    </td>\
  </tr>';
}

// Group flag callback for group detail
window.gdFlag = function(flagId, state) {
  if (window._gd) {
    window._gd.edit.flags[flagId] = state;
    updateFlagBtnVisuals('gd', flagId, state);
    window._gd.markDirty();
  }
};

// ── Zone Detail (Round 2) ───────────────────────────────────────────────────
// Global flag callback for zone detail (called by buildFlagsGrid onclick)
function zdFlag(flagId, state) { if (window._zd) window._zd.setFlag(flagId, state); }
function zdSwitchTab(tabId, btn) {
  switchDetailTab(tabId, btn, 'zd');
  if (tabId === 'states' && window._zd) _zdLoadStates();
}

async function buildZoneDetail(app, zoneId) {
  let zone, groups, tagConfigs;
  try {
    [zone, groups, tagConfigs] = await Promise.all([API.zones.get(zoneId), API.groups.list(), API.zones.tagConfigs().catch(() => [])]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  // Update breadcrumb with zone name
  if (app.breadcrumbs.length >= 2) app.breadcrumbs[1].label = zone.name || zoneId;

  // Local state
  // Store tagConfigs on _zv if it exists for autocomplete access
  if (window._zv) window._zv.tagConfigs = tagConfigs;

  const _zd = window._zd = {
    zone, groups, tagConfigs, app, dirty: false,
    edit: {
      name: zone.name, priority: zone.priority, groupId: zone.groupId || '', parentId: zone.parentId || '',
      flags: { ...zone.flags },
      entryMessageEnabled: zone.entryMessageEnabled, entryMessage: zone.entryMessage || '',
      exitMessageEnabled: zone.exitMessageEnabled, exitMessage: zone.exitMessage || '',
      protectionMessage: zone.protectionMessage || '', particleColor: zone.particleColor || 'green',
      advancementDeniedMessage: zone.advancementDeniedMessage || '',
      requiredAdvancementsEntry: [...(zone.requiredAdvancementsEntry||[])],
      requiredAdvancementsExit: [...(zone.requiredAdvancementsExit||[])],
      requiredQuestsEntry: [...(zone.requiredQuestsEntry||[])],
      requiredQuestsExit: [...(zone.requiredQuestsExit||[])],
      commandsOnEnter: [...(zone.commandsOnEnter||[])],
      commandsOnExit: [...(zone.commandsOnExit||[])],
      commandsOnBattleLoss: [...(zone.commandsOnBattleLoss||[])],
    },
    owner: zone.owner || null,
    members: [...(zone.memberList || [])],

    markDirty() { this.dirty = true; const el = document.getElementById('zd-dirty'); if (el) el.style.display = ''; this._debouncedSave(); },

    _debouncedSave: debounce(function() { if (window._zd) window._zd.save(); }, 1500),

    async save() {
      try {
        await API.zones.update(zoneId, this.edit);
        app.toast(t('common.success'), 'success');
        this.dirty = false;
        const el = document.getElementById('zd-dirty'); if (el) el.style.display = 'none';
      } catch (e) { app.toast(e.message, 'error'); }
    },

    setFlag(flagId, state) {
      this.edit.flags[flagId] = state;
      updateFlagBtnVisuals('zd', flagId, state);
      this.markDirty();
    },

    async addMember() {
      openPlayerPicker({ title: t('zones.members'), onSelect: async (name) => {
        try {
          const res = await API.zones.addMember(zoneId, { playerName: name });
          app.toast(t('common.success'), 'success');
          _zd.members.push({ uuid: res.uuid, name: res.name, role: 'MEMBER' });
          _zd._renderMembers();
        } catch (e) { app.toast(e.message, 'error'); }
      }});
    },

    async removeMember(uuid) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.zones.removeMember(zoneId, uuid);
        _zd.members = _zd.members.filter(m => m.uuid !== uuid);
        app.toast(t('common.success'), 'success');
        _zd._renderMembers();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async transferOwner() {
      openPlayerPicker({ title: t('zones.transferOwner'), onSelect: async (name) => {
        try {
          const res = await API.zones.transferOwner(zoneId, { playerName: name });
          app.toast(t('zones.ownerTransferred', {name: res.name}), 'success');
          // Refresh full zone to get updated owner/members
          const updated = await API.zones.get(zoneId);
          _zd.zone = updated;
          _zd.owner = updated.owner || null;
          _zd.members = [...(updated.memberList || [])];
          _zd._renderOwner();
          _zd._renderMembers();
        } catch (e) { app.toast(e.message, 'error'); }
      }});
    },

    async removeOwner() {
      if (!await confirmAction(t('zones.confirmRemoveOwner'))) return;
      try {
        await API.zones.removeOwner(zoneId);
        app.toast(t('zones.ownerRemoved'), 'success');
        _zd.owner = null;
        _zd.zone.owner = null;
        _zd._renderOwner();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    _renderOwner() {
      const el = document.getElementById('zd-owner-section');
      if (!el) return;
      if (this.owner) {
        el.innerHTML = `
          <div class="flex items-center gap-3 py-2">
            <span class="text-xs font-bold px-2 py-0.5 rounded" style="background:var(--amber);color:var(--bg)">${t('zones.role.owner')}</span>
            <span class="text-sm font-mono" style="color:var(--text-primary)">${escHtml(this.owner.name)}</span>
            <span class="text-sm font-mono" style="color:var(--text-muted)">${this.owner.uuid.substring(0,8)}</span>
            <div class="ml-auto flex gap-1">
              <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_zd.transferOwner()" title="${t('zones.transferOwner')}">\u21C4</button>
              <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_zd.removeOwner()" title="${t('zones.removeOwner')}">\u2715</button>
            </div>
          </div>`;
      } else {
        el.innerHTML = `
          <div class="flex items-center gap-3 py-2">
            <span class="text-sm" style="color:var(--muted);font-style:italic">${t('zones.noOwner')}</span>
            <button class="btn-cg btn-cg-primary btn-cg-sm ml-auto" onclick="_zd.transferOwner()">${t('zones.setOwner')}</button>
          </div>`;
      }
    },

    _renderMembers() {
      const el = document.getElementById('zd-members-list');
      if (!el) return;
      if (!this.members.length) { el.innerHTML = '<div class="empty-state py-6"><span class="empty-state-text">' + t('common.none') + '</span></div>'; return; }
      el.innerHTML = this.members.map(m => `
        <div class="flex items-center gap-3 py-2 border-b" style="border-color:var(--bg-border)">
          <span class="text-xs font-medium px-2 py-0.5 rounded" style="background:var(--bg-elevated);color:var(--muted-light)">${t('zones.role.member')}</span>
          <span class="text-sm font-mono" style="color:var(--text-primary)">${escHtml(m.name)}</span>
          <span class="text-sm font-mono" style="color:var(--text-muted)">${m.uuid.substring(0,8)}</span>
          <button class="btn-cg btn-cg-danger btn-cg-sm ml-auto" onclick="_zd.removeMember('${m.uuid}')">\u2715</button>
        </div>`).join('');
    },

    addToList(field, inputId) {
      const inp = document.getElementById(inputId);
      if (!inp || !inp.value.trim()) return;
      this.edit[field].push(inp.value.trim());
      inp.value = '';
      this._renderList(field);
      this.markDirty();
    },

    removeFromList(field, idx) {
      this.edit[field].splice(idx, 1);
      this._renderList(field);
      this.markDirty();
    },

    _renderList(field) {
      const el = document.getElementById('zd-list-' + field);
      if (!el) return;
      const items = this.edit[field];
      if (!items.length) { el.innerHTML = '<span class="text-sm" style="color:var(--text-muted)">' + t('common.none') + '</span>'; return; }
      el.innerHTML = items.map((v, i) => `
        <div class="flex items-center gap-2 py-1">
          <span class="id-badge flex-1">${escHtml(v)}</span>
          <button class="btn-cg btn-cg-danger" style="height:1.25rem;font-size:0.6rem;padding:0 0.4rem" onclick="_zd.removeFromList('${field}',${i})">\u2715</button>
        </div>`).join('');
    },

    async replaceFromSelection() {
      try {
        const res = await API.zones.replaceSelection(zoneId, {});
        app.toast(t('common.success'), 'success');
        app.navigate('zones:' + zoneId);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteZone() {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.zones.delete(zoneId);
        app.toast(t('zones.deleted'), 'success');
        app.navigate('zones');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  // Group options
  const groupOpts = groups.map(g => `<option value="${escHtml(g.id)}" ${_zd.edit.groupId === g.id ? 'selected' : ''}>${escHtml(g.name)}</option>`).join('');

  // Particle colors
  const PARTICLE_COLORS = ['red','green','blue','yellow','purple','cyan','white','orange'];
  const colorGrid = PARTICLE_COLORS.map(c => {
    const sel = _zd.edit.particleColor === c;
    const hex = {red:'#ef4444',green:'#22c55e',blue:'#3b82f6',yellow:'#eab308',purple:'#a855f7',cyan:'#22d3ee',white:'#e5e7eb',orange:'#f97316'}[c];
    return `<button class="w-8 h-8 rounded border-2 ${sel ? 'border-white' : 'border-transparent'}" style="background:${hex}" onclick="_zd.edit.particleColor='${c}';_zd.markDirty();document.querySelectorAll('#zd-color-grid button').forEach(b=>b.style.borderColor='transparent');this.style.borderColor='white'"></button>`;
  }).join('');

  // Flags HTML
  // buildFlagsGrid uses prefix for both element IDs and onclick function names
  // onclick generates: zdFlag('pvp','ALLOW'), element IDs: flag-zd-pvp
  const flagsHtml = buildFlagsGrid(zone.flags, 'zd');

  // Coords display
  const coordStr = `${zone.minX}, ${zone.minY}, ${zone.minZ} \u2192 ${zone.maxX}, ${zone.maxY}, ${zone.maxZ}`;
  const sizeX = zone.maxX - zone.minX + 1, sizeY = zone.maxY - zone.minY + 1, sizeZ = zone.maxZ - zone.minZ + 1;

  // Tab bar
  const zdTabs = [
    { id: 'general', label: t('zones.tabs.general') },
    { id: 'messages', label: t('zones.tabs.messages') },
    { id: 'access', label: t('zones.tabs.access') },
    { id: 'states', label: t('states.title') },
    { id: 'sale', label: t('zones.sale.title') },
  ];

  // Schedule post-render initialization (must fire after Alpine inserts x-html)
  setTimeout(() => {
    _zd._renderOwner();
    _zd._renderMembers();
    ['requiredAdvancementsEntry','requiredAdvancementsExit','requiredQuestsEntry','requiredQuestsExit','commandsOnEnter','commandsOnExit','commandsOnBattleLoss'].forEach(f => _zd._renderList(f));
  }, 80);

  return `
  <div style="animation:slide-in-right 0.2s ease-out">
    <div class="section-card">
      <!-- Header -->
      <div class="detail-header">
        ${detailBackBtn('zones', t('nav.zones'))}
        <div class="flex-1">
          <div class="text-sm font-semibold" style="color:var(--text-heading)">${escHtml(zone.name)}</div>
          <div class="text-sm font-mono" style="color:var(--text-muted)">${escHtml(zone.id)} \u2014 ${escHtml(fmtWorld(zone.world))}</div>
        </div>
        <span id="zd-dirty" class="text-xs font-mono" style="color:var(--amber);display:none">${t('common.save')}...</span>
      </div>

      ${detailTabBar(zdTabs, 'general', 'zdSwitchTab')}

      <div class="section-card-body">
        <!-- Tab: General -->
        <div data-tab-panel-zd="general">
          <div class="detail-split detail-split-1-2">
            <!-- Left column: General, Coords, Appearance, Members -->
            <div class="space-y-5">
              <div class="field-group">
                <label class="field-label">${t('common.name')}</label>
                <input type="text" class="input input-sm w-full input-bordered"
                  value="${escHtml(zone.name)}" oninput="_zd.edit.name=this.value;_zd.markDirty()">
              </div>
              <div class="grid grid-cols-2 gap-4">
                <div class="field-group">
                  <label class="field-label">${t('zones.priority')} ${tip('tip.zones.priority')}</label>
                  <input type="number" class="input input-sm w-full input-bordered"
                    value="${zone.priority}" oninput="_zd.edit.priority=parseInt(this.value)||0;_zd.markDirty()">
                </div>
                <div class="field-group">
                  <label class="field-label">${t('zones.group')} ${tip('tip.zones.group')}</label>
                  <select class="select select-sm w-full select-bordered"
                    onchange="_zd.edit.groupId=this.value;_zd.markDirty()">
                    <option value="">${t('common.none')}</option>${groupOpts}
                  </select>
                </div>
              </div>
              <div class="field-group">
                <label class="field-label">${t('zones.parentZone')} ${tip('tip.zones.parent')}</label>
                <input type="text" class="input input-sm w-full input-bordered"
                  value="${escHtml(zone.parentId || '')}" placeholder="${t('common.none')}" oninput="_zd.edit.parentId=this.value;_zd.markDirty()">
                <span class="field-help">${t('tip.zones.parent')}</span>
              </div>

              <div class="section-divider"></div>

              <div>
                <div class="field-label mb-1">${t('common.world')}</div>
                <div class="id-badge">${escHtml(fmtWorld(zone.world))}</div>
                <span class="text-xs font-mono ml-2" style="color:var(--text-muted)">${escHtml(zone.world)}</span>
              </div>
              <div>
                <div class="flex items-center gap-3 mb-1">
                  <span class="field-label" style="margin:0">${t('common.position')}</span>
                  <button class="btn-cg btn-cg-ghost" style="height:1.5rem;font-size:0.7rem" onclick="_zd.replaceFromSelection()" title="${t('zones.newZoneTooltip')}">${t('common.apply')}</button>
                </div>
                <span class="id-badge">${coordStr}</span>
                <span class="text-xs font-mono ml-2" style="color:var(--text-muted)">${sizeX}\u00d7${sizeY}\u00d7${sizeZ} ${t('common.blocks')}</span>
              </div>
              <div>
                <div class="field-label mb-1">${t('zones.owner')}</div>
                <div id="zd-owner-section"></div>
              </div>

              <div class="section-divider"></div>

              <div>
                <span class="field-label">${t('events.color')}</span>
                <div id="zd-color-grid" class="flex gap-2 mt-2 flex-wrap">${colorGrid}</div>
              </div>

              <div class="section-divider"></div>

              <div id="zd-tag-section">
                <label class="field-label">${t('zones.tags')} ${tip('tip.zones.tags')}</label>
                ${_zvTagInputHtml(zone.id, zone.tags, tagConfigs)}
              </div>

              <div class="section-divider"></div>

              <div>
                <div class="flex items-center justify-between mb-3">
                  <span class="field-label" style="margin:0">${t('zones.members')} (${zone.memberList?.length || 0})</span>
                  <button class="btn-cg btn-cg-primary" style="height:1.75rem;font-size:var(--type-xs)" onclick="_zd.addMember()">${t('common.add')}</button>
                </div>
                <div id="zd-members-list"></div>
              </div>
            </div>

            <!-- Right column: Flags -->
            <div class="space-y-5">
              <div>
                <div class="text-sm font-semibold mb-3" style="color:var(--text-heading)">${t('zones.flags')}</div>
                ${flagsHtml}
              </div>
            </div>
          </div>

          <!-- Danger zone -->
          <div class="danger-zone mt-6">
            <div class="danger-zone-title">${t('common.delete')}</div>
            <p class="text-sm mb-3" style="color:var(--text-muted)">${t('common.deleteConfirm')}</p>
            <button class="btn-cg btn-cg-danger" onclick="_zd.deleteZone()">${t('common.delete')}</button>
          </div>
        </div>

        <!-- Tab: Messages -->
        <div data-tab-panel-zd="messages" style="display:none">
          <div class="space-y-5" style="max-width:36rem">
            <div>
              <div class="flex items-center gap-3 mb-2">
                <input type="checkbox" class="toggle toggle-sm" id="zd-entry-toggle"
                  ${_zd.edit.entryMessageEnabled ? 'checked' : ''}
                  onchange="_zd.edit.entryMessageEnabled=this.checked;_zd.markDirty()">
                <span class="field-label" style="margin:0">${t('zones.entryMessage')} ${tip('tip.zones.entryMessage')}</span>
              </div>
              <input type="text" class="input input-sm w-full input-bordered"
                value="${escHtml(_zd.edit.entryMessage)}" oninput="_zd.edit.entryMessage=this.value;_zd.markDirty()" placeholder="${t('zones.entryPlaceholder')}">
            </div>
            <div>
              <div class="flex items-center gap-3 mb-2">
                <input type="checkbox" class="toggle toggle-sm" id="zd-exit-toggle"
                  ${_zd.edit.exitMessageEnabled ? 'checked' : ''}
                  onchange="_zd.edit.exitMessageEnabled=this.checked;_zd.markDirty()">
                <span class="field-label" style="margin:0">${t('zones.exitMessage')} ${tip('tip.zones.exitMessage')}</span>
              </div>
              <input type="text" class="input input-sm w-full input-bordered"
                value="${escHtml(_zd.edit.exitMessage)}" oninput="_zd.edit.exitMessage=this.value;_zd.markDirty()" placeholder="${t('zones.exitPlaceholder')}">
            </div>
            <div>
              <span class="field-label">${t('zones.protectionMessage')} ${tip('tip.zones.protectionMessage')}</span>
              <input type="text" class="input input-sm w-full mt-1 input-bordered"
                value="${escHtml(_zd.edit.protectionMessage)}" oninput="_zd.edit.protectionMessage=this.value;_zd.markDirty()" placeholder="${t('zones.protectionPlaceholder')}">
            </div>
          </div>
        </div>

        <!-- Tab: Access & Conditions -->
        <div data-tab-panel-zd="access" style="display:none">
          <div class="space-y-5" style="max-width:36rem">
            <div>
              <span class="field-label">${t('zones.advEntry')} ${tip('tip.zones.advEntry')}</span>
              <div id="zd-list-requiredAdvancementsEntry" class="mt-1 mb-2"></div>
              <div class="flex gap-2">
                <input id="zd-inp-advEntry" type="text" class="input input-sm flex-1 input-bordered" placeholder="minecraft:story/root">
                <button class="btn-cg btn-cg-ghost" onclick="_zd.addToList('requiredAdvancementsEntry','zd-inp-advEntry')">+</button>
              </div>
            </div>
            <div>
              <span class="field-label">${t('zones.advExit')} ${tip('tip.zones.advExit')}</span>
              <div id="zd-list-requiredAdvancementsExit" class="mt-1 mb-2"></div>
              <div class="flex gap-2">
                <input id="zd-inp-advExit" type="text" class="input input-sm flex-1 input-bordered" placeholder="minecraft:story/root">
                <button class="btn-cg btn-cg-ghost" onclick="_zd.addToList('requiredAdvancementsExit','zd-inp-advExit')">+</button>
              </div>
            </div>
            <div>
              <span class="field-label">${t('zones.protectionMessage')}</span>
              <input type="text" class="input input-sm w-full mt-1 input-bordered"
                value="${escHtml(_zd.edit.advancementDeniedMessage)}" oninput="_zd.edit.advancementDeniedMessage=this.value;_zd.markDirty()">
            </div>
            <div class="section-divider"></div>
            <div>
              <span class="field-label">${t('zones.questEntry')} ${tip('tip.zones.questEntry')}</span>
              <div id="zd-list-requiredQuestsEntry" class="mt-1 mb-2"></div>
              <div class="flex gap-2">
                <input id="zd-inp-questEntry" type="text" class="input input-sm flex-1 input-bordered" placeholder="quest_id">
                <button class="btn-cg btn-cg-ghost" onclick="_zd.addToList('requiredQuestsEntry','zd-inp-questEntry')">+</button>
              </div>
            </div>
            <div>
              <span class="field-label">${t('zones.questExit')} ${tip('tip.zones.questExit')}</span>
              <div id="zd-list-requiredQuestsExit" class="mt-1 mb-2"></div>
              <div class="flex gap-2">
                <input id="zd-inp-questExit" type="text" class="input input-sm flex-1 input-bordered" placeholder="quest_id">
                <button class="btn-cg btn-cg-ghost" onclick="_zd.addToList('requiredQuestsExit','zd-inp-questExit')">+</button>
              </div>
            </div>
            <div class="section-divider"></div>
            <div>
              <span class="field-label">${t('zones.cmdEntry')} ${tip('tip.zones.cmdEntry')}</span>
              <span class="field-help">${t('tip.zones.cmdEntry')}</span>
              <div id="zd-list-commandsOnEnter" class="mt-1 mb-2"></div>
              <div class="flex gap-2">
                <input id="zd-inp-cmdEnter" type="text" class="input input-sm flex-1 input-bordered" placeholder="${t('zones.cmdPlaceholder')}">
                <button class="btn-cg btn-cg-ghost" onclick="_zd.addToList('commandsOnEnter','zd-inp-cmdEnter')">+</button>
              </div>
            </div>
            <div>
              <span class="field-label">${t('zones.cmdExit')} ${tip('tip.zones.cmdExit')}</span>
              <div id="zd-list-commandsOnExit" class="mt-1 mb-2"></div>
              <div class="flex gap-2">
                <input id="zd-inp-cmdExit" type="text" class="input input-sm flex-1 input-bordered" placeholder="${t('zones.cmdPlaceholder')}">
                <button class="btn-cg btn-cg-ghost" onclick="_zd.addToList('commandsOnExit','zd-inp-cmdExit')">+</button>
              </div>
            </div>
            <div>
              <span class="field-label">${t('zones.cmdBattleLoss')} ${tip('tip.zones.cmdBattleLoss')}</span>
              <div id="zd-list-commandsOnBattleLoss" class="mt-1 mb-2"></div>
              <div class="flex gap-2">
                <input id="zd-inp-cmdBattleLoss" type="text" class="input input-sm flex-1 input-bordered" placeholder="${t('zones.cmdPlaceholder')}">
                <button class="btn-cg btn-cg-ghost" onclick="_zd.addToList('commandsOnBattleLoss','zd-inp-cmdBattleLoss')">+</button>
              </div>
            </div>
          </div>
        </div>

        <!-- Tab: States -->
        <div data-tab-panel-zd="states" style="display:none">
          <div id="zd-states-content"><div class="flex items-center justify-center py-8"><span class="skeleton-line" style="width:200px;height:20px"></span></div></div>
        </div>

        <!-- Tab: Sale -->
        <div data-tab-panel-zd="sale" style="display:none">
          <div id="zv-sale-tab-content">${_zvBuildSaleTab(zone)}</div>
        </div>
      </div>
    </div>
  </div>`;
}

// ── Sale tab helpers ────────────────────────────────────────────────────────

function _zvBuildSaleTab(zone) {
    const sc = zone.saleConfig || {};
    const isSold = sc.sold || false;
    const hasHologram = sc.hasHologram || false;
    const signLines = sc.signText || ['', '', '', ''];
    while (signLines.length < 4) signLines.push('');
    const signLine1 = sc.signLine1 || '';
    const signLine2 = sc.signLine2 || '';
    const signType = sc.signType || 'oak';

    return `
    <div class="section-card">
        <div class="section-card-header">
            <h3 class="section-card-title">${t('zones.sale.title')}</h3>
        </div>
        <div class="section-card-body">
            ${isSold ? `<div class="bg-elevated-box" style="border-left:3px solid var(--red);padding:0.75rem;margin-bottom:1rem">
                <span style="color:var(--red);font-weight:600">${t('zones.sale.sold')}</span> —
                ${t('zones.sale.soldInfo', {buyer: escHtml(sc.buyerName || '?')})}
                <div style="margin-top:0.5rem">
                    <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="_zvRelistSale('${escHtml(zone.id)}')">${t('zones.sale.relist')}</button>
                </div>
            </div>` : ''}

            <div style="display:grid;grid-template-columns:1fr 1fr;gap:1rem">
                <label class="flex items-center gap-2" style="grid-column:span 2">
                    <input type="checkbox" class="toggle" id="zvSaleEnabled" ${sc.enabled ? 'checked' : ''}>
                    <span class="field-label" style="margin:0">${t('zones.sale.enabled')}</span>
                </label>

                <div>
                    <label class="field-label">${t('zones.sale.price')} ${tip('tip.zones.sale.price')}</label>
                    <input type="number" class="input input-sm w-full input-bordered" id="zvSalePrice" value="${sc.price || 0}" min="0">
                </div>

                <div>
                    <label class="field-label">${t('zones.sale.currency')} ${tip('tip.zones.sale.currency')}</label>
                    <select class="select select-sm w-full select-bordered" id="zvSaleCurrency" onchange="_zvToggleItemFields()">
                        <option value="NUMISMATIC" ${sc.currency === 'NUMISMATIC' ? 'selected' : ''}>${t('zones.sale.numismatic')}</option>
                        <option value="ITEMS" ${sc.currency === 'ITEMS' ? 'selected' : ''}>${t('zones.sale.items')}</option>
                        <option value="FROMAGE" ${sc.currency === 'FROMAGE' ? 'selected' : ''}>${t('zones.sale.fromage')}</option>
                    </select>
                </div>

                <div id="zvItemFields" style="grid-column:span 2;display:${sc.currency === 'ITEMS' ? 'grid' : 'none'};grid-template-columns:1fr 1fr;gap:1rem">
                    <div>
                        <label class="field-label">${t('zones.sale.itemId')} ${tip('tip.zones.sale.itemId')}</label>
                        <input type="text" class="input input-sm w-full input-bordered" id="zvSaleItemId" value="${escHtml(sc.itemId || '')}" placeholder="minecraft:diamond">
                    </div>
                    <div>
                        <label class="field-label">${t('zones.sale.itemCount')}</label>
                        <input type="number" class="input input-sm w-full input-bordered" id="zvSaleItemCount" value="${sc.itemCount || 1}" min="1">
                    </div>
                </div>
            </div>

            <div class="section-divider"></div>

            <div>
                <label class="field-label">${t('zones.sale.signText')} ${tip('tip.zones.sale.signText')}</label>
                <div style="display:grid;gap:0.5rem;margin-top:0.5rem">
                    ${signLines.map((line, i) => `
                        <input type="text" class="input input-sm w-full input-bordered" id="zvSignLine${i}" value="${escHtml(line)}" placeholder="${t('zones.sale.signLine', {n: i+1})}">
                    `).join('')}
                </div>
            </div>

            <div class="section-divider"></div>

            <div>
                <label class="field-label">${t('zones.sale.signSection')} ${tip('tip.zones.sale.signSection')}</label>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem;margin-top:0.5rem">
                    <div>
                        <label class="field-label" style="font-size:0.75rem">${t('zones.sale.signLine1')}</label>
                        <input type="text" class="input input-sm w-full input-bordered" id="zvSignCustom1" value="${escHtml(signLine1)}" maxlength="15" placeholder="${escHtml(zone.name)}">
                    </div>
                    <div>
                        <label class="field-label" style="font-size:0.75rem">${t('zones.sale.signLine2')}</label>
                        <input type="text" class="input input-sm w-full input-bordered" id="zvSignCustom2" value="${escHtml(signLine2)}" maxlength="15" placeholder="${t('zones.sale.signLine2Placeholder')}">
                    </div>
                    <div>
                        <label class="field-label" style="font-size:0.75rem">${t('zones.sale.signType')}</label>
                        <select class="select select-sm w-full select-bordered" id="zvSignType">
                            ${['oak','spruce','birch','jungle','acacia','dark_oak','mangrove','cherry','bamboo','crimson','warped'].map(w =>
                                `<option value="${w}" ${signType === w ? 'selected' : ''}>${t('zones.sale.wood.' + w)}</option>`
                            ).join('')}
                        </select>
                    </div>
                </div>
            </div>

            <div class="section-divider"></div>

            <div style="display:flex;gap:0.5rem;flex-wrap:wrap">
                <button class="btn-cg btn-cg-primary" onclick="_zvSaveSale('${escHtml(zone.id)}')">${t('zones.sale.save')}</button>
                ${hasHologram
                    ? `<button class="btn-cg btn-cg-danger" onclick="_zvRemoveHologram('${escHtml(zone.id)}')">${t('zones.sale.removeHologram')}</button>`
                    : `<button class="btn-cg btn-cg-ghost" onclick="_zvSpawnHologram('${escHtml(zone.id)}')">${t('zones.sale.spawnHologram')}</button>`}
                <button class="btn-cg btn-cg-ghost" onclick="_zvGiveSign('${escHtml(zone.id)}')">${t('zones.sale.giveSign')}</button>
                <button class="btn-cg btn-cg-danger" onclick="_zvRemoveSale('${escHtml(zone.id)}')">${t('zones.sale.remove')}</button>
            </div>
        </div>
    </div>`;
}

function _zvToggleItemFields() {
    const sel = document.getElementById('zvSaleCurrency');
    const fields = document.getElementById('zvItemFields');
    if (sel && fields) fields.style.display = sel.value === 'ITEMS' ? 'grid' : 'none';
}

async function _zvSaveSale(id) {
    const config = {
        enabled: document.getElementById('zvSaleEnabled')?.checked || false,
        price: parseInt(document.getElementById('zvSalePrice')?.value || '0'),
        currency: document.getElementById('zvSaleCurrency')?.value || 'NUMISMATIC',
        itemId: document.getElementById('zvSaleItemId')?.value || null,
        itemCount: parseInt(document.getElementById('zvSaleItemCount')?.value || '1'),
        signText: [0,1,2,3].map(i => document.getElementById('zvSignLine'+i)?.value || ''),
        signLine1: document.getElementById('zvSignCustom1')?.value || '',
        signLine2: document.getElementById('zvSignCustom2')?.value || '',
        signType: document.getElementById('zvSignType')?.value || 'oak'
    };
    try {
        await API.zones.setSale(id, config);
        _toast(t('zones.sale.saved'), 'success');
        await _zvRefreshSaleTab(id);
    } catch (e) { _toast(e.message, 'error'); }
}

async function _zvSpawnHologram(id) {
    try {
        await API.zones.spawnHologram(id);
        _toast(t('zones.sale.hologramSpawned'), 'success');
        await _zvRefreshSaleTab(id);
    } catch (e) { _toast(e.message, 'error'); }
}

async function _zvRemoveHologram(id) {
    try {
        await API.zones.removeHologram(id);
        _toast(t('zones.sale.hologramRemoved'), 'success');
        await _zvRefreshSaleTab(id);
    } catch (e) { _toast(e.message, 'error'); }
}

async function _zvRefreshSaleTab(id) {
    try {
        const zone = await API.zones.getZone(id);
        if (window._zd) window._zd.zone = zone;
        const el = document.getElementById('zv-sale-tab-content');
        if (el) el.innerHTML = _zvBuildSaleTab(zone);
    } catch (e) { /* ignore */ }
}

async function _zvGiveSign(id) {
    const playerName = localStorage.getItem('cg_admin_player');
    if (!playerName) { _toast(t('error.noAdminPlayer'), 'error'); return; }
    try {
        await API.zones.giveSign(id, playerName);
        _toast(t('zones.sale.signGiven', {player: playerName}), 'success');
    } catch (e) { _toast(e.message, 'error'); }
}

async function _zvRemoveSale(id) {
    if (!await confirmAction(t('zones.sale.remove') + '?')) return;
    try {
        await API.zones.removeSale(id);
        _toast(t('zones.sale.removed'), 'success');
        const app = Alpine.store('app') || window._zvApp;
        if (app && app.loadView) app.loadView();
    } catch (e) { _toast(e.message, 'error'); }
}

async function _zvRelistSale(id) {
    try {
        await API.zones.relistSale(id);
        _toast(t('zones.sale.relisted'), 'success');
        const app = Alpine.store('app') || window._zvApp;
        if (app && app.loadView) app.loadView();
    } catch (e) { _toast(e.message, 'error'); }
}

// ── Group Detail (Round 8) ──────────────────────────────────────────────────
function gdSwitchTab(tabId, btn) { switchDetailTab(tabId, btn, 'gd'); }

function gdRenderZones(zones, allZones, groupId) {
  const el = document.getElementById('gd-zones-body');
  if (!el) return;
  const assignedIds = new Set((zones || []).map(z => z.id));
  const available = allZones.filter(z => !assignedIds.has(z.id));
  const availOpts = available.map(z => `<option value="${escHtml(z.id)}">${escHtml(z.name)} (${escHtml(fmtWorld(z.world))})</option>`).join('');

  let html = `<div class="flex gap-2 mb-3">
    <select id="gd-zone-select" class="select select-sm flex-1 select-bordered">
      <option value="">${t('common.select')}...</option>${availOpts}
    </select>
    <button class="btn-cg btn-cg-primary" onclick="_gd.assignZone()">${t('common.add')}</button>
  </div>`;

  if (!zones || zones.length === 0) {
    html += `<div class="text-center py-4 text-sm" style="color:var(--text-muted)">' + t('zones.noZones') + '</div>`;
  } else {
    html += '<div class="space-y-1">';
    zones.forEach(z => {
      const nav = `_nav('zones:${escHtml(z.id)}')`;
      html += `<div class="flex items-center justify-between py-1.5 px-2 rounded" style="background:var(--bg-elevated)">
        <div class="cursor-pointer" onclick="${nav}">
          <span class="text-sm" style="color:var(--text-primary)">${escHtml(z.name)}</span>
          <span class="id-badge ml-2">${escHtml(z.id)}</span>
          ${worldBadgeHtml(z.world)}
        </div>
        <button class="btn-cg btn-cg-danger btn-cg-sm"
          onclick="_gd.unassignZone('${escHtml(z.id)}')">\u2715</button>
      </div>`;
    });
    html += '</div>';
  }
  el.innerHTML = html;
}

async function buildGroupDetail(app, groupId) {
  let group, allZones;
  try {
    [group, allZones] = await Promise.all([API.groups.get(groupId), API.zones.list()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const inp = (id, val, extra) => `<input id="${id}" class="input input-sm w-full input-bordered" value="${escHtml(val || '')}" ${extra || ''}>`;

  if (app.breadcrumbs.length >= 2) app.breadcrumbs[1].label = group.name || groupId;

  window._gd = {
    group, allZones, app, dirty: false,
    edit: { name: group.name, flags: { ...group.flags } },

    markDirty() { this.dirty = true; const el = document.getElementById('gd-dirty'); if (el) el.style.display = ''; this._debouncedSave(); },

    _debouncedSave: debounce(function() { if (window._gd) window._gd.save(); }, 1500),

    async save() {
      try {
        await API.groups.update(groupId, { name: this.edit.name, flags: this.edit.flags });
        app.toast(t('common.success'), 'success');
        this.dirty = false;
        const el = document.getElementById('gd-dirty'); if (el) el.style.display = 'none';
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async assignZone() {
      const sel = document.getElementById('gd-zone-select');
      if (!sel || !sel.value) return;
      try {
        await API.groups.assignZone(groupId, sel.value);
        app.toast(t('common.success'), 'success');
        this.group = await API.groups.get(groupId);
        gdRenderZones(this.group.zones, this.allZones, groupId);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async unassignZone(zoneId) {
      try {
        await API.groups.unassignZone(groupId, zoneId);
        app.toast(t('common.success'), 'success');
        this.group = await API.groups.get(groupId);
        gdRenderZones(this.group.zones, this.allZones, groupId);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteGroup() {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.groups.delete(groupId);
        app.toast(t('zones.groupDeleted'), 'success');
        app.navigate('zones');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  // Schedule post-render initialization
  setTimeout(() => gdRenderZones(group.zones || [], allZones, groupId), 80);

  return `<div style="animation:slide-in-right 0.2s ease-out">
    <div class="section-card">
      <div class="detail-header">
        ${detailBackBtn('zones', t('nav.zones'))}
        <div class="flex-1">
          <span class="text-sm font-semibold" style="color:var(--text-heading)">\u{1F5C2} ${escHtml(group.name)}</span>
          <span class="id-badge ml-2">${escHtml(group.id)}</span>
        </div>
        <span id="gd-dirty" class="text-xs" style="color:var(--amber);display:none">${t('common.save')}...</span>
      </div>

      <div class="section-card-body space-y-6">
        <!-- Name -->
        <div style="max-width:24rem">
          <label class="field-label">${t('zones.groupName')}</label>
          ${inp('gd-name', group.name, 'oninput="_gd.edit.name=this.value;_gd.markDirty()"')}
        </div>

        <!-- Flags -->
        <div>
          <div class="text-sm font-semibold mb-3" style="color:var(--text-heading)">${t('zones.flags')}</div>
          ${buildFlagsGrid(group.flags, 'gd')}
        </div>

        <!-- Zones list -->
        <div>
          <div class="text-sm font-semibold mb-3" style="color:var(--text-heading)">${t('zones.tabZones', {count: (group.zones || []).length})})</div>
          <div id="gd-zones-body"></div>
        </div>

        <!-- Danger zone -->
        <div class="danger-zone">
          <div class="danger-zone-title">${t('common.delete')}</div>
          <p class="text-sm mb-3" style="color:var(--text-muted)">${t('common.deleteConfirm')}</p>
          <button class="btn-cg btn-cg-danger" onclick="_gd.deleteGroup()">${t('common.delete')}</button>
        </div>
      </div>
    </div>
  </div>`;
}

// ── Zone States tab ────────────────────────────────────────────────────────

async function _zdLoadStates() {
  const zd = window._zd;
  if (!zd) return;
  const container = document.getElementById('zd-states-content');
  if (!container) return;

  try {
    const resp = await API.zones.states(zd.zone.id);
    const states = resp.states || resp;
    const animating = resp.animating || false;
    zd._states = states;
    zd._animating = animating;
    container.innerHTML = _zdRenderStates(zd.zone.id, states, animating);
  } catch (e) {
    container.innerHTML = `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }
}

function _zdRenderStates(zoneId, states, animating) {
  const particleOptions = ['none','cloud','poof','smoke','flame','soul','sparkle','enchant','totem','heart','drip','cherry','sculk','witch','snowflake','note','electric','ash'];
  const particleOpts = particleOptions.map(p =>
    `<option value="${p}"${p === 'cloud' ? ' selected' : ''}>${t('states.particle.' + p)}</option>`
  ).join('');

  let rows = '';
  if (states.length === 0) {
    rows = `<div class="empty-state" style="padding:2rem"><span class="empty-state-icon">\u{1F4E6}</span><span class="empty-state-text">${t('states.empty')}</span></div>`;
  } else {
    rows = `<table class="cg-table"><thead><tr>
      <th>ID</th><th>${t('common.name')}</th>
      <th>${t('states.blocks')}</th><th>${t('states.entities')}</th>
      <th>${t('states.created')}</th><th>${t('common.actions')}</th>
    </tr></thead><tbody>`;
    for (const s of states) {
      rows += `<tr>
        <td class="td-mono">${escHtml(s.id)}</td>
        <td>
          <input type="text" class="input input-sm input-bordered" style="width:180px"
            value="${escHtml(s.name)}" id="zds-name-${escHtml(s.id)}"
            onchange="_zdRenameState('${escHtml(zoneId)}','${escHtml(s.id)}',this.value)">
        </td>
        <td class="td-mono">${s.blockCount.toLocaleString()}</td>
        <td class="td-mono">${s.entityCount}</td>
        <td class="text-sm" style="color:var(--muted)">${I18N.fmtDate(new Date(s.createdAt).toISOString())}</td>
        <td>
          <div class="flex gap-1 flex-wrap">
            <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="_zdApplyState('${escHtml(zoneId)}','${escHtml(s.id)}',false)"
              title="${t('states.apply')}">${t('states.apply')}</button>
            <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_zdShowAnimateModal('${escHtml(zoneId)}','${escHtml(s.id)}','${escHtml(s.name)}')"
              title="${t('states.applyAnimated')}">&#9654; ${t('states.applyAnimated')}</button>
            <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_zdDeleteState('${escHtml(zoneId)}','${escHtml(s.id)}','${escHtml(s.name)}')"
              title="${t('common.delete')}">&times;</button>
          </div>
        </td>
      </tr>`;
    }
    rows += `</tbody></table>`;
  }

  const cancelBar = animating ? `
    <div class="bg-elevated-box flex items-center justify-between" style="border-left:3px solid var(--cyan);padding:0.75rem;margin-bottom:1rem">
      <span style="color:var(--cyan);font-weight:600">${t('states.animating')}</span>
      <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_zdCancelAnimation('${escHtml(zoneId)}')">${t('states.cancel')}</button>
    </div>` : '';

  return `
  <div class="section-card">
    <div class="section-card-header">
      <h3 class="section-card-title">${t('states.title')}</h3>
      <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="_zdCaptureState('${escHtml(zoneId)}')">${t('states.capture')}</button>
    </div>
    <div class="section-card-body">
      ${cancelBar}
      ${rows}
    </div>
  </div>

  <!-- Animate modal -->
  <dialog id="zds-animate-modal" class="modal">
    <div class="modal-box" style="max-width:420px">
      <button class="modal-close" onclick="document.getElementById('zds-animate-modal').close()">&times;</button>
      <h3 class="text-lg font-bold mb-4" style="color:var(--text-heading)">${t('states.applyAnimated')}</h3>
      <div class="space-y-4">
        <div>
          <label class="field-label">${t('states.direction')}</label>
          <select class="select select-sm w-full select-bordered" id="zds-direction" onchange="_zdUpdateSlideUI()">
            <optgroup label="${t('states.direction.group.layers')}">
              <option value="bottom_up">${t('states.direction.bottom_up')}</option>
              <option value="top_down">${t('states.direction.top_down')}</option>
            </optgroup>
            <optgroup label="${t('states.direction.group.slide')}">
              <option value="slide_from_bottom">${t('states.direction.slide_from_bottom')}</option>
              <option value="slide_from_top">${t('states.direction.slide_from_top')}</option>
              <option value="slide_from_north">${t('states.direction.slide_from_north')}</option>
              <option value="slide_from_south">${t('states.direction.slide_from_south')}</option>
              <option value="slide_from_east">${t('states.direction.slide_from_east')}</option>
              <option value="slide_from_west">${t('states.direction.slide_from_west')}</option>
            </optgroup>
            <optgroup label="${t('states.direction.group.cascade')}">
              <option value="cascade_from_bottom">${t('states.direction.cascade_from_bottom')}</option>
              <option value="cascade_from_top">${t('states.direction.cascade_from_top')}</option>
              <option value="cascade_from_north">${t('states.direction.cascade_from_north')}</option>
              <option value="cascade_from_south">${t('states.direction.cascade_from_south')}</option>
              <option value="cascade_from_east">${t('states.direction.cascade_from_east')}</option>
              <option value="cascade_from_west">${t('states.direction.cascade_from_west')}</option>
            </optgroup>
          </select>
        </div>
        <div>
          <label class="field-label" id="zds-speed-label">${t('states.speedUnit.layers')}</label>
          <div class="flex items-center gap-3">
            <input type="range" class="w-full" id="zds-speed" value="5" min="1" max="50" step="1"
              style="accent-color:var(--amber)" oninput="document.getElementById('zds-speed-val').textContent=this.value">
            <span id="zds-speed-val" class="td-mono text-sm" style="min-width:28px;text-align:right;color:var(--amber)">5</span>
          </div>
        </div>
        <label class="flex items-center gap-2">
          <input type="checkbox" class="toggle" id="zds-blockbyblock" onchange="_zdUpdateSpeedLabel()">
          <span class="field-label" style="margin:0">${t('states.blockByBlock')}</span>
        </label>
        <div>
          <label class="field-label">${t('states.particle')}</label>
          <select class="select select-sm w-full select-bordered" id="zds-particle">
            ${particleOpts}
          </select>
        </div>
        <div class="flex gap-2 justify-end mt-4">
          <button class="btn-cg btn-cg-ghost" onclick="document.getElementById('zds-animate-modal').close()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-ghost" id="zds-copy-cmd-btn" title="${t('states.copyCmd')}">&#128203; ${t('states.copyCmd')}</button>
          <button class="btn-cg btn-cg-primary" id="zds-animate-btn">${t('states.apply')}</button>
        </div>
      </div>
    </div>
    <form method="dialog" class="modal-backdrop"><button></button></form>
  </dialog>`;
}

async function _zdCaptureState(zoneId) {
  const name = prompt(t('states.captureName'), 'State ' + ((window._zd?._states?.length || 0) + 1));
  if (!name) return;
  try {
    await API.zones.stateCapture(zoneId, { name });
    _toast(t('states.captureSuccess'), 'success');
    _zdLoadStates();
  } catch (e) { _toast(e.message, 'error'); }
}

async function _zdRenameState(zoneId, stateId, newName) {
  try {
    await API.zones.stateRename(zoneId, stateId, { name: newName });
  } catch (e) { _toast(e.message, 'error'); }
}

async function _zdDeleteState(zoneId, stateId, name) {
  if (!confirm(t('states.confirmDelete', { name }))) return;
  try {
    await API.zones.stateDelete(zoneId, stateId);
    _toast(t('states.deleteSuccess'), 'success');
    _zdLoadStates();
  } catch (e) { _toast(e.message, 'error'); }
}

async function _zdApplyState(zoneId, stateId, animated, direction, speed, particle, blockByBlock) {
  try {
    await API.zones.stateApply(zoneId, stateId, {
      animated: !!animated,
      direction: direction || 'bottom_up',
      ticksPerLayer: speed || 2,
      particle: particle || 'cloud',
      blockByBlock: !!blockByBlock
    });
    _toast(animated ? t('states.animating') : t('states.applySuccess'), 'success');
  } catch (e) { _toast(e.message, 'error'); }
}

function _zdCopyToClipboard(text) {
  console.log('[CheeseDash] Copying to clipboard:', text);
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      console.log('[CheeseDash] Clipboard write success (navigator.clipboard)');
      _toast(t('states.cmdCopied'), 'success');
    }).catch(err => {
      console.warn('[CheeseDash] navigator.clipboard failed, trying fallback:', err);
      _zdCopyFallback(text);
    });
  } else {
    console.log('[CheeseDash] navigator.clipboard not available, using fallback');
    _zdCopyFallback(text);
  }
}

function _zdCopyFallback(text) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try {
    const ok = document.execCommand('copy');
    console.log('[CheeseDash] execCommand copy result:', ok);
    if (ok) _toast(t('states.cmdCopied'), 'success');
    else _toast('Copy failed (execCommand returned false)', 'error');
  } catch (e) {
    console.error('[CheeseDash] execCommand copy error:', e);
    _toast('Copy failed: ' + e.message, 'error');
  }
  document.body.removeChild(ta);
}

function _zdAnimStorageKey(zoneId, stateId) { return `cg_anim_${zoneId}_${stateId}`; }

function _zdUpdateSpeedLabel() {
  const label = document.getElementById('zds-speed-label');
  const bbb = document.getElementById('zds-blockbyblock')?.checked;
  const dir = document.getElementById('zds-direction')?.value || '';
  const isDisplacement = dir.startsWith('slide_') || dir.startsWith('cascade_');
  if (label) label.textContent = t(bbb ? 'states.speedUnit.blocks' : isDisplacement ? 'states.speedUnit.slices' : 'states.speedUnit.layers');
}

function _zdUpdateSlideUI() {
  const dir = document.getElementById('zds-direction')?.value || '';
  const isDisplacement = dir.startsWith('slide_') || dir.startsWith('cascade_');
  const bbbEl = document.getElementById('zds-blockbyblock');
  if (bbbEl) {
    bbbEl.disabled = isDisplacement;
    if (isDisplacement) bbbEl.checked = false;
  }
  _zdUpdateSpeedLabel();
}

function _zdShowAnimateModal(zoneId, stateId, name) {
  const modal = document.getElementById('zds-animate-modal');
  if (!modal) return;

  // Restore saved settings for this specific state
  const saved = JSON.parse(localStorage.getItem(_zdAnimStorageKey(zoneId, stateId)) || '{}');
  const dirEl = document.getElementById('zds-direction');
  const speedEl = document.getElementById('zds-speed');
  const speedVal = document.getElementById('zds-speed-val');
  const particleEl = document.getElementById('zds-particle');
  const bbbEl = document.getElementById('zds-blockbyblock');
  if (dirEl) dirEl.value = saved.direction || 'bottom_up';
  if (speedEl) { speedEl.value = saved.speed || 5; if (speedVal) speedVal.textContent = speedEl.value; }
  if (particleEl) particleEl.value = saved.particle || 'cloud';
  if (bbbEl) bbbEl.checked = !!saved.blockByBlock;
  _zdUpdateSpeedLabel();

  function _getModalSettings() {
    const dir = dirEl?.value || 'bottom_up';
    const speed = parseInt(speedEl?.value) || 5;
    const particle = particleEl?.value || 'cloud';
    const bbb = bbbEl?.checked || false;
    const ticks = Math.max(1, Math.round(20 / speed));
    return { dir, speed, particle, bbb, ticks };
  }

  const btn = document.getElementById('zds-animate-btn');
  if (btn) {
    btn.onclick = () => {
      const s = _getModalSettings();
      localStorage.setItem(_zdAnimStorageKey(zoneId, stateId), JSON.stringify({ direction: s.dir, speed: s.speed, particle: s.particle, blockByBlock: s.bbb }));
      _zdApplyState(zoneId, stateId, true, s.dir, s.ticks, s.particle, s.bbb);
      modal.close();
    };
  }

  const copyBtn = document.getElementById('zds-copy-cmd-btn');
  if (copyBtn) {
    copyBtn.onclick = () => {
      const s = _getModalSettings();
      let cmd = `/cg state apply ${zoneId} ${stateId} true ${s.dir} ${s.ticks} ${s.particle}`;
      if (s.bbb) cmd += ' true';
      console.log('[CheeseDash] State animation command:', cmd);
      _zdCopyToClipboard(cmd);
    };
  }

  modal.showModal();
}

async function _zdCancelAnimation(zoneId) {
  try {
    await API.zones.stateCancel(zoneId);
    _toast(t('states.cancelSuccess'), 'success');
    _zdLoadStates();
  } catch (e) { _toast(e.message, 'error'); }
}
