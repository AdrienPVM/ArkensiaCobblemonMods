/**
 * Morphos catalog view — central source of truth for morpho entries.
 * Each entry feeds three surfaces: in-game Codex, web shop, NPC shop.
 *
 * Top-level tabs:  "Catalogue"  |  "Endurance globale"
 * Editor sub-tabs: "Général" | "Commandes" | "Endurance"
 *
 * State lives in window._mp.
 */

window._mp = window._mp || {
  tab: 'catalog',     // 'catalog' | 'global'
  editorTab: 'general', // 'general' | 'commands' | 'endurance'
  list: [],
  globalEnd: null,    // { capacity, drainPerSecond, regenPerSecond }
  editing: null,
  saveTimer: null,
  dragSrc: null,
  app: null,
};

async function buildMorphos(app) {
  let list, globalEnd;
  try {
    [list, globalEnd] = await Promise.all([
      API.morphos.list(),
      API.morphos.global().catch(() => ({ capacity:100, drainPerSecond:2.0, regenPerSecond:1.0 })),
    ]);
  } catch (e) {
    app.toast(e.message || t('error.unknown'), 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠️</span><span class="empty-state-text">${escHtml(e.message || '')}</span></div>`;
  }
  window._mp.list = list || [];
  window._mp.globalEnd = globalEnd;
  window._mp.app = app;
  // Bumped every reload so freshly uploaded images bypass the browser cache.
  window._mp.imgVersion = Date.now();

  const activeTab = window._mp.tab || 'catalog';
  return `
    <div class="max-w-6xl mx-auto slide-in">
      <div class="section-card">
        <div class="section-card-header" style="display:flex;align-items:center;justify-content:space-between;gap:1rem;flex-wrap:wrap">
          <div>
            <div class="section-card-title">${t('morphos.title')}</div>
            <div class="text-xs" style="color:var(--muted)">${t('morphos.subtitle')}</div>
          </div>
          <div class="detail-tabs">
            <button class="detail-tab ${activeTab==='catalog'?'active':''}" onclick="_mpSwitchTopTab('catalog')">${t('morphos.tab.catalog')}</button>
            <button class="detail-tab ${activeTab==='global'?'active':''}" onclick="_mpSwitchTopTab('global')">${t('morphos.tab.global')}</button>
          </div>
        </div>
        <div class="section-card-body" id="mp-tab-body">
          ${activeTab === 'catalog' ? _mpRenderCatalogTab() : _mpRenderGlobalTab()}
        </div>
      </div>
    </div>
    <dialog id="mp-editor" class="modal"></dialog>
  `;
}

function _mpSwitchTopTab(tab) {
  window._mp.tab = tab;
  const body = document.getElementById('mp-tab-body');
  if (!body) return;
  body.innerHTML = tab === 'catalog' ? _mpRenderCatalogTab() : _mpRenderGlobalTab();
  document.querySelectorAll('.section-card-header .detail-tab').forEach((el, i) => {
    el.classList.toggle('active', (i === 0 && tab === 'catalog') || (i === 1 && tab === 'global'));
  });
}

// ── Catalog tab ──────────────────────────────────────────────────────

function _mpRenderCatalogTab() {
  const list = window._mp.list;
  const rows = list.map((m, i) => _mpRowHtml(m, i)).join('');
  return `
    <div style="display:flex;justify-content:flex-end;gap:.5rem;margin-bottom:.75rem">
      <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_mpReload()">${t('common.refresh')}</button>
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_mpOpenCreate()">+ ${t('morphos.new')}</button>
    </div>
    ${list && list.length ? `
      <table class="cg-table" style="width:100%">
        <thead>
          <tr>
            <th style="width:32px"></th>
            <th style="width:64px">${t('morphos.col.image')}</th>
            <th>${t('morphos.col.title')}</th>
            <th>${t('morphos.col.species')}</th>
            <th class="td-mono" style="width:120px">${t('morphos.col.priceCredits')}</th>
            <th class="td-mono" style="width:100px">${t('morphos.col.priceGold')}</th>
            <th style="width:80px;text-align:center">${t('morphos.col.web')}</th>
            <th style="width:80px;text-align:center">${t('morphos.col.npc')}</th>
            <th style="width:80px;text-align:center">${t('morphos.col.new')}</th>
            <th style="width:160px">${t('common.actions')}</th>
          </tr>
        </thead>
        <tbody id="mp-tbody">${rows}</tbody>
      </table>
    ` : `<div class="empty-state"><span class="empty-state-icon">🧬</span><span class="empty-state-text">${t('morphos.empty')}</span></div>`}
  `;
}

function _mpRowHtml(m, idx) {
  // Cache-bust per render so a freshly uploaded image is reloaded by the
  // browser instead of serving the old (cached) PNG at the same URL.
  const v = window._mp.imgVersion || 0;
  // 48px row cell — pull the ≤480px thumb (cached, ~100 KB) instead of the
  // full-resolution original (often 1-2 MB) to keep the admin table snappy.
  const imgSrc = m.hasImage ? `/morphos/api/thumb/${encodeURIComponent(m.id)}?v=${v}` : '';
  const imgCell = imgSrc
    ? `<img src="${imgSrc}" alt="" style="width:48px;height:48px;object-fit:cover;border-radius:6px;background:var(--bg-elevated)">`
    : `<div style="width:48px;height:48px;border-radius:6px;background:var(--bg-elevated);display:flex;align-items:center;justify-content:center;color:var(--muted);font-size:1.2rem">🧬</div>`;
  return `
    <tr draggable="true"
        ondragstart="_mpDragStart(event, ${idx})"
        ondragover="event.preventDefault()"
        ondrop="_mpDrop(event, ${idx})">
      <td style="cursor:grab;color:var(--muted);text-align:center">⋮⋮</td>
      <td>${imgCell}</td>
      <td>
        <div style="font-weight:600">${escHtml(m.title || '(' + t('common.untitled') + ')')}</div>
        <div class="text-xs" style="color:var(--muted)">${escHtml(m.id)}</div>
      </td>
      <td class="td-mono">${escHtml(m.species || '—')}${m.shiny?' ✨':''}</td>
      <td class="td-mono">${m.priceCredits||0}</td>
      <td class="td-mono">${m.priceGold||0}</td>
      <td style="text-align:center">
        <input type="checkbox" class="toggle toggle-sm" ${m.availableOnWebsite?'checked':''}
          onchange="_mpToggle('${m.id}','availableOnWebsite',this.checked)">
      </td>
      <td style="text-align:center">
        <input type="checkbox" class="toggle toggle-sm" ${m.availableInNpcShop?'checked':''}
          onchange="_mpToggle('${m.id}','availableInNpcShop',this.checked)">
      </td>
      <td style="text-align:center">
        <input type="checkbox" class="toggle toggle-sm" ${m.isNew?'checked':''}
          onchange="_mpToggle('${m.id}','isNew',this.checked)">
      </td>
      <td>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_mpOpenEdit('${m.id}')">${t('common.edit')}</button>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_mpDuplicate('${m.id}')">${t('morphos.duplicate')}</button>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_mpGrant('${m.id}')">${t('morphos.grant')}</button>
        <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_mpDelete('${m.id}')">${t('common.delete')}</button>
      </td>
    </tr>
  `;
}

// ── Global endurance tab ──────────────────────────────────────────────

function _mpRenderGlobalTab() {
  const g = window._mp.globalEnd || { capacity:100, drainPerSecond:2.0, regenPerSecond:1.0 };
  return `
    <div style="max-width:560px">
      <p class="text-sm mb-4" style="color:var(--muted)">
        ${t('morphos.global.intro')}
      </p>
      <div class="grid gap-3" style="grid-template-columns:1fr 1fr">
        <div>
          <label class="field-label">${t('morphos.global.capacity')}</label>
          <input type="number" id="mp-glb-cap" min="1" step="1" value="${g.capacity}"
            class="input input-sm input-bordered w-full">
          <p class="text-xs mt-1" style="color:var(--muted)">${t('morphos.global.capacityHint')}</p>
        </div>
        <div>
          <label class="field-label">${t('morphos.global.drain')}</label>
          <input type="number" id="mp-glb-drain" min="0" step="0.1" value="${g.drainPerSecond}"
            class="input input-sm input-bordered w-full">
          <p class="text-xs mt-1" style="color:var(--muted)">${t('morphos.global.drainHint')}</p>
        </div>
        <div>
          <label class="field-label">${t('morphos.global.regen')}</label>
          <input type="number" id="mp-glb-regen" min="0" step="0.1" value="${g.regenPerSecond}"
            class="input input-sm input-bordered w-full">
          <p class="text-xs mt-1" style="color:var(--muted)">${t('morphos.global.regenHint')}</p>
        </div>
      </div>
      <div style="display:flex;gap:.5rem;justify-content:flex-end;margin-top:1.25rem;padding-top:1rem;border-top:1px solid var(--bg-border)">
        <button class="btn-cg btn-cg-primary" onclick="_mpSaveGlobal()">${t('common.save')}</button>
      </div>
    </div>
  `;
}

async function _mpSaveGlobal() {
  const get = (id) => parseFloat(document.getElementById(id)?.value);
  const payload = {
    capacity: parseInt(get('mp-glb-cap')) || 100,
    drainPerSecond: get('mp-glb-drain') || 0,
    regenPerSecond: get('mp-glb-regen') || 0,
  };
  try {
    const saved = await API.morphos.saveGlobal(payload);
    window._mp.globalEnd = saved;
    _toast(t('common.success'), 'success');
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
  }
}

// ── Catalog actions ─────────────────────────────────────────────────

async function _mpReload() {
  const app = window._mp.app;
  if (app && app.navigate) {
    app.viewHtml = '';
    app.navigate('morphos');
  } else {
    location.reload();
  }
}

async function _mpToggle(id, field, value) {
  try {
    const patch = {}; patch[field] = value;
    await API.morphos.update(id, patch);
    const m = window._mp.list.find(x => x.id === id);
    if (m) m[field] = value;
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
  }
}

function _mpOpenCreate() {
  window._mp.editing = {
    id: '', title: '', description: '', species: '', form: '', shiny: false,
    priceCredits: 0, priceGold: 0,
    availableOnWebsite: false, availableInNpcShop: false, isNew: false,
    terrestrial: false, aquatic: false, aerial: false,
    legendary: false,
    commandsOnTransform: [], commandsOnRevert: [],
    useCustomEndurance: false,
    customCapacity: 100, customDrainPerSecond: 2.0, customRegenPerSecond: 1.0,
    _isNew: true,
  };
  window._mp.editorTab = 'general';
  _mpRenderEditor();
}

function _mpOpenEdit(id) {
  const m = window._mp.list.find(x => x.id === id);
  if (!m) return;
  window._mp.editing = {
    ...m,
    commandsOnTransform: Array.isArray(m.commandsOnTransform) ? [...m.commandsOnTransform] : [],
    commandsOnRevert:    Array.isArray(m.commandsOnRevert)    ? [...m.commandsOnRevert]    : [],
    useCustomEndurance:  !!m.useCustomEndurance,
    customCapacity:       m.customCapacity || 100,
    customDrainPerSecond: m.customDrainPerSecond || 2.0,
    customRegenPerSecond: m.customRegenPerSecond || 1.0,
    _isNew: false,
  };
  window._mp.editorTab = 'general';
  _mpRenderEditor();
}

function _mpRenderEditor() {
  const e = window._mp.editing;
  if (!e) return;
  const dlg = document.getElementById('mp-editor');
  if (!dlg) return;
  const tab = window._mp.editorTab || 'general';
  dlg.innerHTML = `
    <div class="modal-box" style="max-width:760px;background:var(--bg-card)">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:.75rem">
        <h3 style="font-weight:700;font-size:1.1rem;color:var(--amber)">
          ${e._isNew ? t('morphos.new') : t('morphos.edit')}
        </h3>
        <button class="modal-close" onclick="_mpCloseEditor()">✕</button>
      </div>
      <div class="detail-tabs" style="margin-bottom:1rem">
        <button class="detail-tab ${tab==='general'?'active':''}"   onclick="_mpEditorTab('general')">${t('morphos.editor.general')}</button>
        <button class="detail-tab ${tab==='commands'?'active':''}"  onclick="_mpEditorTab('commands')">${t('morphos.editor.commands')}</button>
        <button class="detail-tab ${tab==='endurance'?'active':''}" onclick="_mpEditorTab('endurance')">${t('morphos.editor.endurance')}</button>
      </div>
      <div id="mp-editor-body">${_mpEditorBody(tab, e)}</div>
      <div style="display:flex;gap:.5rem;justify-content:flex-end;margin-top:1.25rem;padding-top:1rem;border-top:1px solid var(--bg-border)">
        <button class="btn-cg btn-cg-ghost" onclick="_mpCloseEditor()">${t('common.cancel')}</button>
        <button class="btn-cg btn-cg-primary" onclick="_mpSave()">${t('common.save')}</button>
      </div>
    </div>
  `;
  if (typeof dlg.showModal === 'function' && !dlg.open) dlg.showModal();
}

function _mpEditorTab(tab) {
  window._mp.editorTab = tab;
  const body = document.getElementById('mp-editor-body');
  if (body) body.innerHTML = _mpEditorBody(tab, window._mp.editing);
  document.querySelectorAll('#mp-editor .detail-tabs .detail-tab').forEach((el, i) => {
    const order = ['general','commands','endurance'];
    el.classList.toggle('active', order[i] === tab);
  });
}

function _mpEditorBody(tab, e) {
  if (tab === 'commands')  return _mpEditorCommands(e);
  if (tab === 'endurance') return _mpEditorEndurance(e);
  return _mpEditorGeneral(e);
}

function _mpEditorGeneral(e) {
  // Editor preview is rendered at max 120×120 — the ≤480px thumb is more than
  // enough and avoids re-downloading the original on every editor open.
  const imgSrc = (!e._isNew && e.hasImage) ? `/morphos/api/thumb/${encodeURIComponent(e.id)}?t=${Date.now()}` : '';
  return `
    <div class="grid gap-3" style="grid-template-columns:1fr 1fr">
      <div>
        <label class="field-label">${t('morphos.field.title')}</label>
        <input type="text" class="input input-sm input-bordered w-full"
          value="${escHtml(e.title||'')}"
          oninput="window._mp.editing.title=this.value">
      </div>
      <div>
        <label class="field-label">${t('morphos.field.species')}</label>
        <input type="text" class="input input-sm input-bordered w-full"
          value="${escHtml(e.species||'')}" placeholder="ditto"
          oninput="window._mp.editing.species=this.value">
      </div>
      <div style="grid-column:1/-1">
        <label class="field-label">${t('morphos.field.description')}</label>
        <textarea class="input input-sm input-bordered w-full" rows="2"
          oninput="window._mp.editing.description=this.value">${escHtml(e.description||'')}</textarea>
      </div>
      <div style="grid-column:1/-1">
        <label class="field-label">${t('morphos.field.form')}</label>
        <input type="text" class="input input-sm input-bordered w-full"
          value="${escHtml(e.form||'')}" placeholder=""
          oninput="window._mp.editing.form=this.value">
      </div>
      <div>
        <label class="field-label">${t('morphos.field.priceCredits')}</label>
        <input type="number" min="0" class="input input-sm input-bordered w-full"
          value="${e.priceCredits||0}"
          oninput="window._mp.editing.priceCredits=parseInt(this.value)||0">
      </div>
      <div>
        <label class="field-label">${t('morphos.field.priceGold')}</label>
        <input type="number" min="0" class="input input-sm input-bordered w-full"
          value="${e.priceGold||0}"
          oninput="window._mp.editing.priceGold=parseInt(this.value)||0">
      </div>
      <div style="display:flex;align-items:center;gap:.5rem">
        <input type="checkbox" id="mp-web" class="toggle toggle-sm" ${e.availableOnWebsite?'checked':''}
          onchange="window._mp.editing.availableOnWebsite=this.checked">
        <label for="mp-web" class="text-xs">${t('morphos.field.web')}</label>
      </div>
      <div style="display:flex;align-items:center;gap:.5rem">
        <input type="checkbox" id="mp-npc" class="toggle toggle-sm" ${e.availableInNpcShop?'checked':''}
          onchange="window._mp.editing.availableInNpcShop=this.checked">
        <label for="mp-npc" class="text-xs">${t('morphos.field.npc')}</label>
      </div>
      <div style="display:flex;align-items:center;gap:.5rem">
        <input type="checkbox" id="mp-new" class="toggle toggle-sm" ${e.isNew?'checked':''}
          onchange="window._mp.editing.isNew=this.checked">
        <label for="mp-new" class="text-xs">${t('morphos.field.isNew')}</label>
      </div>
      <div style="grid-column:1/-1;border-top:1px solid var(--bg-border);padding-top:1rem;margin-top:.25rem">
        <div class="field-label" style="margin-bottom:.5rem">${t('morphos.field.habitat')}</div>
        <div style="display:flex;gap:1.25rem;flex-wrap:wrap">
          <label style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer">
            <input type="checkbox" class="toggle toggle-sm" ${e.terrestrial?'checked':''}
              onchange="window._mp.editing.terrestrial=this.checked">
            <span class="text-xs">🌳 ${t('morphos.field.terrestrial')}</span>
          </label>
          <label style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer">
            <input type="checkbox" class="toggle toggle-sm" ${e.aquatic?'checked':''}
              onchange="window._mp.editing.aquatic=this.checked">
            <span class="text-xs">🌊 ${t('morphos.field.aquatic')}</span>
          </label>
          <label style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer">
            <input type="checkbox" class="toggle toggle-sm" ${e.aerial?'checked':''}
              onchange="window._mp.editing.aerial=this.checked">
            <span class="text-xs">🪽 ${t('morphos.field.aerial')}</span>
          </label>
        </div>
      </div>
      <div style="grid-column:1/-1;border-top:1px solid var(--bg-border);padding-top:1rem;margin-top:.25rem">
        <div class="field-label" style="margin-bottom:.5rem">${t('morphos.field.rarity')}</div>
        <div style="display:flex;gap:1.25rem;flex-wrap:wrap">
          <label style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer">
            <input type="checkbox" class="toggle toggle-sm" ${e.legendary?'checked':''}
              onchange="window._mp.editing.legendary=this.checked">
            <span class="text-xs">★ ${t('morphos.field.legendary')}</span>
          </label>
          <label style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer">
            <input type="checkbox" class="toggle toggle-sm" ${e.shiny?'checked':''}
              onchange="window._mp.editing.shiny=this.checked">
            <span class="text-xs">✨ ${t('morphos.field.shiny')}</span>
          </label>
        </div>
      </div>
      <div style="grid-column:1/-1;border-top:1px solid var(--bg-border);padding-top:1rem;margin-top:.5rem">
        <label class="field-label">${t('morphos.field.image')}</label>
        ${imgSrc ? `<div style="margin-bottom:.5rem"><img src="${imgSrc}" alt="" style="max-width:120px;max-height:120px;border-radius:8px;background:var(--bg-elevated)"></div>` : ''}
        <input type="file" id="mp-img" accept="image/png,image/jpeg" class="input input-sm input-bordered w-full">
        <div class="text-xs" style="color:var(--muted);margin-top:.25rem">${t('morphos.field.imageHint')}</div>
      </div>
    </div>
  `;
}

function _mpEditorCommands(e) {
  const onTRows = (e.commandsOnTransform || []).map((c, i) => _mpCmdRow('transform', i, c)).join('');
  const onRRows = (e.commandsOnRevert || []).map((c, i) => _mpCmdRow('revert', i, c)).join('');
  return `
    <p class="text-sm mb-3" style="color:var(--muted)">${t('morphos.commands.intro')}</p>
    <div class="section-divider mb-3"></div>
    <div class="flex items-center justify-between mb-2">
      <span class="font-semibold text-sm" style="color:var(--amber)">${t('morphos.commands.onTransform')}</span>
      <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_mpCmdAdd('transform')">+ ${t('common.add')}</button>
    </div>
    <div id="mp-cmd-transform">${onTRows || `<p class="text-xs" style="color:var(--muted)">${t('common.noResults')}</p>`}</div>
    <div class="section-divider my-4"></div>
    <div class="flex items-center justify-between mb-2">
      <span class="font-semibold text-sm" style="color:var(--amber)">${t('morphos.commands.onRevert')}</span>
      <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_mpCmdAdd('revert')">+ ${t('common.add')}</button>
    </div>
    <div id="mp-cmd-revert">${onRRows || `<p class="text-xs" style="color:var(--muted)">${t('common.noResults')}</p>`}</div>
  `;
}

function _mpCmdRow(kind, i, cmd) {
  return `
    <div style="display:flex;gap:.5rem;align-items:center;margin-bottom:.4rem">
      <input type="text" class="input input-sm input-bordered w-full"
        value="${escHtml(cmd)}"
        placeholder="${t('morphos.commands.placeholder')}"
        oninput="_mpCmdSet('${kind}', ${i}, this.value)">
      <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_mpCmdRemove('${kind}', ${i})">✕</button>
    </div>
  `;
}

function _mpCmdAdd(kind) {
  const list = kind === 'transform' ? window._mp.editing.commandsOnTransform : window._mp.editing.commandsOnRevert;
  list.push('');
  _mpEditorTab('commands');
}
function _mpCmdSet(kind, i, value) {
  const list = kind === 'transform' ? window._mp.editing.commandsOnTransform : window._mp.editing.commandsOnRevert;
  list[i] = value;
}
function _mpCmdRemove(kind, i) {
  const list = kind === 'transform' ? window._mp.editing.commandsOnTransform : window._mp.editing.commandsOnRevert;
  list.splice(i, 1);
  _mpEditorTab('commands');
}

function _mpEditorEndurance(e) {
  const g = window._mp.globalEnd || { capacity:100, drainPerSecond:2.0, regenPerSecond:1.0 };
  return `
    <p class="text-sm mb-3" style="color:var(--muted)">${t('morphos.endurance.intro')}</p>
    <div style="display:flex;align-items:center;gap:.6rem;margin-bottom:1rem">
      <input type="checkbox" id="mp-end-toggle" class="toggle toggle-sm" ${e.useCustomEndurance?'checked':''}
        onchange="window._mp.editing.useCustomEndurance=this.checked;_mpEditorTab('endurance')">
      <label for="mp-end-toggle" class="text-sm">${t('morphos.endurance.useCustom')}</label>
    </div>
    <div class="grid gap-3" style="grid-template-columns:1fr 1fr 1fr;${e.useCustomEndurance?'':'opacity:.45;pointer-events:none'}">
      <div>
        <label class="field-label">${t('morphos.global.capacity')}</label>
        <input type="number" min="1" step="1" class="input input-sm input-bordered w-full"
          value="${e.customCapacity||100}"
          oninput="window._mp.editing.customCapacity=parseInt(this.value)||1">
      </div>
      <div>
        <label class="field-label">${t('morphos.global.drain')}</label>
        <input type="number" min="0" step="0.1" class="input input-sm input-bordered w-full"
          value="${e.customDrainPerSecond||2}"
          oninput="window._mp.editing.customDrainPerSecond=parseFloat(this.value)||0">
      </div>
      <div>
        <label class="field-label">${t('morphos.global.regen')}</label>
        <input type="number" min="0" step="0.1" class="input input-sm input-bordered w-full"
          value="${e.customRegenPerSecond||1}"
          oninput="window._mp.editing.customRegenPerSecond=parseFloat(this.value)||0">
      </div>
    </div>
    <p class="text-xs mt-3" style="color:var(--muted)">
      ${t('morphos.endurance.fallback', { cap: g.capacity, drain: g.drainPerSecond, regen: g.regenPerSecond })}
    </p>
  `;
}

function _mpCloseEditor() {
  const dlg = document.getElementById('mp-editor');
  if (dlg && typeof dlg.close === 'function') dlg.close();
  window._mp.editing = null;
}

async function _mpSave() {
  const e = window._mp.editing;
  if (!e) return;
  const payload = {
    title: e.title || '',
    description: e.description || '',
    species: e.species || '',
    form: e.form || '',
    shiny: !!e.shiny,
    priceCredits: parseInt(e.priceCredits) || 0,
    priceGold: parseInt(e.priceGold) || 0,
    availableOnWebsite: !!e.availableOnWebsite,
    availableInNpcShop: !!e.availableInNpcShop,
    isNew: !!e.isNew,
    terrestrial: !!e.terrestrial,
    aquatic: !!e.aquatic,
    aerial: !!e.aerial,
    legendary: !!e.legendary,
    commandsOnTransform: (e.commandsOnTransform || []).filter(s => s && s.trim().length),
    commandsOnRevert:    (e.commandsOnRevert    || []).filter(s => s && s.trim().length),
    useCustomEndurance: !!e.useCustomEndurance,
    customCapacity: parseInt(e.customCapacity) || 100,
    customDrainPerSecond: parseFloat(e.customDrainPerSecond) || 0,
    customRegenPerSecond: parseFloat(e.customRegenPerSecond) || 0,
  };
  let saved;
  try {
    if (e._isNew) saved = await API.morphos.create(payload);
    else          saved = await API.morphos.update(e.id, payload);
  } catch (err) {
    _toast(err.message || t('error.unknown'), 'error');
    return;
  }
  // Image upload if present — downscaled and recompressed in the browser before send.
  const fileInput = document.getElementById('mp-img');
  if (fileInput && fileInput.files && fileInput.files[0]) {
    try {
      const b64 = await optimizeImageFile(fileInput.files[0], { maxDim: 1024 });
      await API.morphos.uploadImage(saved.id, b64);
    } catch (err) {
      _toast(err.message || t('error.unknown'), 'error');
    }
  }
  _toast(t('morphos.saved'), 'success');
  _mpCloseEditor();
  _mpReload();
}

async function _mpDuplicate(id) {
  // Always fetch the source from the server — the cached list can be stale
  // (e.g. commands edited in another tab) and we want a true 1:1 copy.
  let src;
  try { src = await API.morphos.get(id); }
  catch (e) { _toast(e.message || t('error.unknown'), 'error'); return; }
  if (!src) return;

  // Spread every persisted field, then override the id-bearing/transient ones.
  // This guarantees that any field added later to MorphoEntry.toJson is copied
  // automatically without having to update this function.
  const payload = {
    ...src,
    title: (src.title || '') + ' (copie)',
    commandsOnTransform: Array.isArray(src.commandsOnTransform) ? [...src.commandsOnTransform] : [],
    commandsOnRevert:    Array.isArray(src.commandsOnRevert)    ? [...src.commandsOnRevert]    : [],
  };
  // Strip fields the server assigns or that don't belong on a fresh entry.
  delete payload.id;
  delete payload.hasImage;
  delete payload.sortOrder;
  delete payload.createdAt;
  let copy;
  try { copy = await API.morphos.create(payload); }
  catch (e) { _toast(e.message || t('error.unknown'), 'error'); return; }

  // If the source had a custom image, copy it over to the new entry.
  if (src.hasImage) {
    try {
      const resp = await fetch(`/morphos/api/image/${encodeURIComponent(src.id)}`);
      if (resp.ok) {
        const blob = await resp.blob();
        const b64 = await new Promise((res, rej) => {
          const r = new FileReader();
          r.onload = () => res(r.result);
          r.onerror = () => rej(new Error('read failed'));
          r.readAsDataURL(blob);
        });
        await API.morphos.uploadImage(copy.id, b64);
      }
    } catch (e) { /* image copy failed — non-fatal, the entry still exists */ }
  }
  _toast(t('morphos.duplicated', { name: copy.title || copy.id }), 'success');
  _mpReload();
}

async function _mpDelete(id) {
  if (!await confirmAction(t('morphos.deleteConfirm'))) return;
  try {
    await API.morphos.delete(id);
    _toast(t('morphos.deleted'), 'success');
    _mpReload();
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
  }
}

async function _mpGrant(morphoId) {
  if (typeof openPlayerPicker !== 'function') {
    _toast(t('error.unknown'), 'error');
    return;
  }
  openPlayerPicker({
    title: t('morphos.grant'),
    onSelect: async (playerName) => {
      try {
        await API.morphos.grant({ playerName, morphoId });
        _toast(t('morphos.granted', { name: playerName }), 'success');
      } catch (e) {
        _toast(e.message || t('error.unknown'), 'error');
      }
    },
  });
}

// ── Drag & drop reorder ────────────────────────────────────────────────

function _mpDragStart(ev, idx) {
  window._mp.dragSrc = idx;
  try { ev.dataTransfer.effectAllowed = 'move'; } catch (e) {}
}

async function _mpDrop(ev, targetIdx) {
  ev.preventDefault();
  const src = window._mp.dragSrc;
  window._mp.dragSrc = null;
  if (src == null || src === targetIdx) return;
  const list = window._mp.list;
  const [moved] = list.splice(src, 1);
  list.splice(targetIdx, 0, moved);
  try {
    await API.morphos.reorder(list.map(m => m.id));
    const tbody = document.getElementById('mp-tbody');
    if (tbody) tbody.innerHTML = list.map((m, i) => _mpRowHtml(m, i)).join('');
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
    _mpReload();
  }
}

function _mpReadFileAsBase64(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = () => reject(new Error('read failed'));
    r.readAsDataURL(file);
  });
}
