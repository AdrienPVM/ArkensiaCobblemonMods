/**
 * view-playcosmetics.js — Éditeur de cosmétiques JOUABLES (boutique premium).
 *
 * Cible : un joueur NON-technicien. Aucun id Minecraft n'est jamais saisi à la
 * main — tout passe par des menus déroulants peuplés par /store/playcosmetics/enums.
 * UX inspirée de view-morphos.js : table + drag-reorder + modale create/edit, avec
 * en plus un APERÇU 3D de l'item icône (window.PlayCosmeticViewer).
 *
 * Catégories & champs (params) :
 *   TRAIL         → particle, shape, density(1-6)
 *   KILL_EFFECT   → effect, sound?, particle?
 *   DEATH_EFFECT  → effect, particle?, sound?
 *   CHAT_TAG      → tag(texte), color
 *   NAME_COLOR    → color
 *   PET           → mob, petName?, baby
 *
 * Tout l'état vit dans window._pc. Ce fichier ne touche QUE lui-même + le viewer.
 */

window._pc = window._pc || {
  app: null,
  list: [],
  enums: null,
  filter: 'ALL',      // catégorie active du filtre, ou 'ALL'
  editing: null,
  dragSrc: null,
  viewer: null,       // handle du viewer 3D de la modale (pour dispose)
};

// Libellés FR lisibles pour les catégories (le joueur ne voit jamais l'id brut).
const PC_CAT_LABELS = {
  TRAIL:        '✨ Traînée',
  KILL_EFFECT:  '💀 Effet de kill',
  DEATH_EFFECT: '⚰️ Effet de mort',
  CHAT_TAG:     '🏷️ Tag de chat',
  NAME_COLOR:   '🎨 Couleur du pseudo',
  PET:          '🐾 Familier',
};
function pcCatLabel(cat) { return PC_CAT_LABELS[cat] || cat || '—'; }

// Petit fallback d'enums si le mod ne renvoie rien d'utile (dégradation douce).
const PC_ENUMS_FALLBACK = {
  categories: ['TRAIL', 'KILL_EFFECT', 'DEATH_EFFECT', 'CHAT_TAG', 'NAME_COLOR', 'PET'],
  particles: [], effects: [], mobs: [], colors: [], iconItems: [], shapes: [], sounds: [],
};

// ── Entrée principale (appelée par le routeur app.js) ────────────────────────
async function buildPlayCosmetics(app) {
  window._pc.app = app;
  let list, enums;
  try {
    [list, enums] = await Promise.all([
      API.store.playcosmetics.list().catch(() => ({ cosmetics: [] })),
      API.store.playcosmetics.enums().catch(() => null),
    ]);
  } catch (e) {
    return `<div class="empty-state"><span class="empty-state-icon">⚠️</span>
      <span class="empty-state-text">${escHtml(e.message || 'Erreur de chargement')}</span></div>`;
  }
  window._pc.list = (list && Array.isArray(list.cosmetics)) ? list.cosmetics : (Array.isArray(list) ? list : []);
  window._pc.enums = _pcNormalizeEnums(enums);

  return `
    <div class="max-w-6xl mx-auto slide-in">
      <div class="section-card">
        <div class="section-card-header" style="display:flex;align-items:center;justify-content:space-between;gap:1rem;flex-wrap:wrap">
          <div>
            <div class="section-card-title">✨ Cosmétiques jouables</div>
            <div class="text-xs" style="color:var(--muted)">Boutique premium — traînées, effets, tags, familiers. Achetables en crédits par les joueurs.</div>
          </div>
          <div style="display:flex;gap:.5rem">
            <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_pcReload()">${t('common.refresh')}</button>
            <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_pcOpenCreate()">+ Nouveau cosmétique</button>
          </div>
        </div>
        <div class="section-card-body">
          ${_pcFilterBar()}
          <div id="pc-table-wrap">${_pcRenderTable()}</div>
        </div>
      </div>
    </div>
    <dialog id="pc-editor" class="modal"></dialog>
    <dialog id="pc-test" class="modal"></dialog>
  `;
}

// Normalise la forme des enums + applique le fallback champ par champ.
function _pcNormalizeEnums(e) {
  const out = Object.assign({}, PC_ENUMS_FALLBACK);
  if (e && typeof e === 'object') {
    for (const k of Object.keys(PC_ENUMS_FALLBACK)) {
      if (Array.isArray(e[k]) && e[k].length) out[k] = e[k];
    }
  }
  return out;
}

// Les enums peuvent être des strings ("flame") ou des objets ({id,name,icon}).
// Ces deux helpers uniformisent l'accès pour les <option>.
function _pcOptId(o)    { return (o && typeof o === 'object') ? (o.id ?? o.value ?? o.name ?? '') : o; }
function _pcOptLabel(o) {
  if (o && typeof o === 'object') return o.name ?? o.label ?? o.displayName ?? o.id ?? o.value ?? '';
  return o;
}

// ── Barre de filtres par catégorie ───────────────────────────────────────────
function _pcFilterBar() {
  const cats = window._pc.enums.categories || [];
  const counts = {};
  window._pc.list.forEach(c => { counts[c.category] = (counts[c.category] || 0) + 1; });
  const chip = (id, label, n) => `
    <button class="btn-cg btn-cg-sm ${window._pc.filter === id ? 'btn-cg-primary' : 'btn-cg-ghost'}"
      onclick="_pcSetFilter('${id}')">${escHtml(label)}${n != null ? ` <span style="opacity:.7">(${n})</span>` : ''}</button>`;
  return `
    <div style="display:flex;gap:.4rem;flex-wrap:wrap;margin-bottom:.85rem">
      ${chip('ALL', 'Tout', window._pc.list.length)}
      ${cats.map(c => chip(c, pcCatLabel(c), counts[c] || 0)).join('')}
    </div>`;
}

function _pcSetFilter(cat) {
  window._pc.filter = cat;
  const wrap = document.getElementById('pc-table-wrap');
  if (wrap) wrap.innerHTML = _pcRenderTable();
  // Re-rendre la barre pour l'état actif des chips.
  const bar = document.querySelector('#pc-table-wrap')?.previousElementSibling;
  if (bar) bar.outerHTML = _pcFilterBar();
}

// ── Table ─────────────────────────────────────────────────────────────────────
function _pcVisibleList() {
  const f = window._pc.filter;
  return (f && f !== 'ALL') ? window._pc.list.filter(c => c.category === f) : window._pc.list;
}

function _pcRenderTable() {
  const list = _pcVisibleList();
  if (!list.length) {
    return `<div class="empty-state"><span class="empty-state-icon">✨</span>
      <span class="empty-state-text">Aucun cosmétique. Cliquez sur « Nouveau cosmétique » pour commencer.</span></div>`;
  }
  // L'index de drag porte sur la liste COMPLÈTE pour un reorder cohérent —
  // on ne permet le drag que sans filtre actif (sinon l'ordre serait ambigu).
  const dragOk = (window._pc.filter === 'ALL');
  const rows = list.map((c) => _pcRowHtml(c, dragOk)).join('');
  return `
    <table class="cg-table" style="width:100%">
      <thead>
        <tr>
          ${dragOk ? '<th style="width:28px"></th>' : ''}
          <th style="width:52px">Icône</th>
          <th>Titre</th>
          <th style="width:150px">Catégorie</th>
          <th class="td-mono" style="width:90px">Prix</th>
          <th style="width:64px;text-align:center">Actif</th>
          <th style="width:56px;text-align:center">NEW</th>
          <th style="width:230px">${t('common.actions')}</th>
        </tr>
      </thead>
      <tbody id="pc-tbody">${rows}</tbody>
    </table>`;
}

function _pcRowHtml(c, dragOk) {
  const idx = window._pc.list.indexOf(c);
  const icon = (typeof mkItemImg === 'function' && c.icon)
    ? mkItemImg(c.icon, 32)
    : `<div style="width:32px;height:32px;border-radius:6px;background:var(--bg-elevated);display:flex;align-items:center;justify-content:center;color:var(--muted)">✨</div>`;
  const dragAttrs = dragOk
    ? `draggable="true" ondragstart="_pcDragStart(event, ${idx})" ondragover="event.preventDefault()" ondrop="_pcDrop(event, ${idx})"`
    : '';
  return `
    <tr ${dragAttrs}>
      ${dragOk ? '<td style="cursor:grab;color:var(--muted);text-align:center">⋮⋮</td>' : ''}
      <td>${icon}</td>
      <td>
        <div style="font-weight:600">${escHtml(c.title || '(sans titre)')}</div>
        <div class="text-xs" style="color:var(--muted)">${escHtml(c.id || '')}</div>
      </td>
      <td>${escHtml(pcCatLabel(c.category))}</td>
      <td class="td-mono">${(c.price != null ? c.price : 0)} cr.</td>
      <td style="text-align:center">
        <input type="checkbox" class="toggle toggle-sm" ${c.enabled ? 'checked' : ''}
          onchange="_pcToggle('${escHtml(c.id)}','enabled',this.checked)">
      </td>
      <td style="text-align:center">
        <input type="checkbox" class="toggle toggle-sm" ${c.isNew ? 'checked' : ''}
          onchange="_pcToggle('${escHtml(c.id)}','isNew',this.checked)">
      </td>
      <td>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_pcOpenEdit('${escHtml(c.id)}')">${t('common.edit')}</button>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_pcDuplicate('${escHtml(c.id)}')">Dupliquer</button>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_pcTest('${escHtml(c.id)}')">🎮 Tester</button>
        <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_pcDelete('${escHtml(c.id)}')">${t('common.delete')}</button>
      </td>
    </tr>`;
}

// ── Toggle inline (enabled / isNew) ──────────────────────────────────────────
async function _pcToggle(id, field, value) {
  const c = window._pc.list.find(x => x.id === id);
  const prev = c ? c[field] : undefined;
  if (c) c[field] = value;
  try {
    const patch = {}; patch[field] = value;
    await API.store.playcosmetics.update(id, patch);
  } catch (e) {
    if (c) c[field] = prev;
    _toast(e.message || t('error.unknown'), 'error');
    _pcRefreshTable();
  }
}

// ── Drag & drop reorder (sans filtre uniquement) ─────────────────────────────
function _pcDragStart(ev, idx) {
  window._pc.dragSrc = idx;
  try { ev.dataTransfer.effectAllowed = 'move'; } catch (e) {}
}
async function _pcDrop(ev, targetIdx) {
  ev.preventDefault();
  const src = window._pc.dragSrc;
  window._pc.dragSrc = null;
  if (src == null || src === targetIdx) return;
  const list = window._pc.list;
  const [moved] = list.splice(src, 1);
  list.splice(targetIdx, 0, moved);
  _pcRefreshTable();
  try {
    await API.store.playcosmetics.reorder(list.map(c => c.id));
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
    _pcReload();
  }
}

function _pcRefreshTable() {
  const wrap = document.getElementById('pc-table-wrap');
  if (wrap) wrap.innerHTML = _pcRenderTable();
}

async function _pcReload() {
  const app = window._pc.app;
  if (app && app.navigate) {
    app.viewHtml = '';
    app.navigate('playcosmetics');
  } else {
    location.reload();
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MODALE CREATE / EDIT
// ═══════════════════════════════════════════════════════════════════════════════

function _pcBlankEntry() {
  const cats = window._pc.enums.categories || [];
  return {
    id: '', title: '', description: '',
    category: cats[0] || 'TRAIL',
    icon: _pcOptId((window._pc.enums.iconItems || [])[0]) || 'minecraft:blaze_powder',
    price: 0, isNew: false, enabled: true,
    params: {},
    _isNew: true,
  };
}

function _pcOpenCreate() {
  window._pc.editing = _pcBlankEntry();
  _pcRenderEditor();
}

function _pcOpenEdit(id) {
  const c = window._pc.list.find(x => x.id === id);
  if (!c) return;
  window._pc.editing = {
    ...c,
    params: Object.assign({}, c.params || {}),
    _isNew: false,
  };
  _pcRenderEditor();
}

function _pcRenderEditor() {
  const e = window._pc.editing;
  if (!e) return;
  const dlg = document.getElementById('pc-editor');
  if (!dlg) return;
  dlg.innerHTML = `
    <div class="modal-box" style="max-width:780px;background:var(--bg-card)">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:.75rem">
        <h3 style="font-weight:700;font-size:1.1rem;color:var(--amber)">
          ${e._isNew ? 'Nouveau cosmétique' : 'Modifier le cosmétique'}
        </h3>
        <button class="modal-close" onclick="_pcCloseEditor()">✕</button>
      </div>
      <div id="pc-editor-body">${_pcEditorBody(e)}</div>
      <div style="display:flex;gap:.5rem;justify-content:space-between;align-items:center;margin-top:1.25rem;padding-top:1rem;border-top:1px solid var(--bg-border)">
        <div>
          ${!e._isNew ? `<button class="btn-cg btn-cg-danger" onclick="_pcDeleteFromEditor()">${t('common.delete')}</button>
                         <button class="btn-cg btn-cg-ghost" onclick="_pcDuplicate('${escHtml(e.id)}')">Dupliquer</button>` : ''}
        </div>
        <div style="display:flex;gap:.5rem">
          <button class="btn-cg btn-cg-ghost" onclick="_pcCloseEditor()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-primary" onclick="_pcSave()">${t('common.save')}</button>
        </div>
      </div>
    </div>`;
  if (typeof dlg.showModal === 'function' && !dlg.open) dlg.showModal();
  // Le viewer 3D se monte une fois le DOM de la modale présent.
  _pcMountIconViewer();
}

function _pcEditorBody(e) {
  const en = window._pc.enums;
  const catOpts = (en.categories || []).map(c =>
    `<option value="${escHtml(c)}" ${c === e.category ? 'selected' : ''}>${escHtml(pcCatLabel(c))}</option>`).join('');
  return `
    <div class="grid gap-4" style="grid-template-columns:200px 1fr">
      <!-- Colonne gauche : aperçu 3D de l'icône -->
      <div>
        <label class="field-label">Aperçu de l'icône</label>
        <div id="pc-icon-viewer" style="width:200px;height:200px;border-radius:10px;background:radial-gradient(circle at 50% 40%, rgba(255,180,80,.12), transparent 70%), var(--bg-elevated);display:flex;align-items:center;justify-content:center;overflow:hidden"></div>
        <div class="text-xs" style="color:var(--muted);margin-top:.4rem;text-align:center">Glissez pour tourner</div>
      </div>

      <!-- Colonne droite : champs -->
      <div class="grid gap-3" style="grid-template-columns:1fr 1fr;align-content:start">
        <div style="grid-column:1/-1">
          <label class="field-label">Titre</label>
          <input type="text" class="input input-sm input-bordered w-full"
            value="${escHtml(e.title || '')}" placeholder="ex : Traînée de flammes"
            oninput="window._pc.editing.title=this.value">
        </div>
        <div style="grid-column:1/-1">
          <label class="field-label">Description</label>
          <textarea class="input input-sm input-bordered w-full" rows="2"
            placeholder="Courte description visible dans la boutique"
            oninput="window._pc.editing.description=this.value">${escHtml(e.description || '')}</textarea>
        </div>
        <div>
          <label class="field-label">Catégorie</label>
          <select class="select select-sm select-bordered w-full" onchange="_pcSetCategory(this.value)">${catOpts}</select>
        </div>
        <div>
          <label class="field-label">Icône (item)</label>
          ${_pcIconSelect(e.icon)}
        </div>
        <div>
          <label class="field-label">Prix (crédits)</label>
          <input type="number" min="0" step="1" class="input input-sm input-bordered w-full"
            value="${e.price != null ? e.price : 0}"
            oninput="window._pc.editing.price=parseInt(this.value)||0">
        </div>
        <div style="display:flex;align-items:flex-end;gap:1.25rem;flex-wrap:wrap">
          <label style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer">
            <input type="checkbox" class="toggle toggle-sm" ${e.enabled ? 'checked' : ''}
              onchange="window._pc.editing.enabled=this.checked">
            <span class="text-xs">Actif (visible en boutique)</span>
          </label>
          <label style="display:inline-flex;align-items:center;gap:.4rem;cursor:pointer">
            <input type="checkbox" class="toggle toggle-sm" ${e.isNew ? 'checked' : ''}
              onchange="window._pc.editing.isNew=this.checked">
            <span class="text-xs">NEW</span>
          </label>
        </div>
      </div>
    </div>

    <div style="border-top:1px solid var(--bg-border);margin-top:1rem;padding-top:1rem">
      <div class="field-label" style="margin-bottom:.6rem">Réglages — ${escHtml(pcCatLabel(e.category))}</div>
      <div id="pc-params" class="grid gap-3" style="grid-template-columns:1fr 1fr">${_pcParamsFields(e)}</div>
    </div>`;
}

function _pcIconSelect(current) {
  const items = window._pc.enums.iconItems || [];
  const opts = items.map(o => {
    const id = _pcOptId(o);
    return `<option value="${escHtml(id)}" ${id === current ? 'selected' : ''}>${escHtml(_pcOptLabel(o))}</option>`;
  }).join('');
  // Si l'item courant n'est pas dans la liste (entrée existante), on l'ajoute.
  const has = items.some(o => _pcOptId(o) === current);
  const extra = (!has && current) ? `<option value="${escHtml(current)}" selected>${escHtml(current)}</option>` : '';
  return `<select id="pc-icon-select" class="select select-sm select-bordered w-full" onchange="_pcSetIcon(this.value)">${extra}${opts}</select>`;
}

// Construit un <select> générique à partir d'une liste d'enums.
function _pcEnumSelect(enumKey, value, onChange, opts) {
  const list = window._pc.enums[enumKey] || [];
  const allowEmpty = opts && opts.optional;
  const head = allowEmpty ? `<option value="" ${!value ? 'selected' : ''}>— Aucun —</option>` : '';
  const body = list.map(o => {
    const id = _pcOptId(o);
    return `<option value="${escHtml(id)}" ${id === value ? 'selected' : ''}>${escHtml(_pcOptLabel(o))}</option>`;
  }).join('');
  const has = list.some(o => _pcOptId(o) === value);
  const extra = (!has && value) ? `<option value="${escHtml(value)}" selected>${escHtml(value)}</option>` : '';
  return `<select class="select select-sm select-bordered w-full" onchange="${onChange}">${head}${extra}${body}</select>`;
}

// Champs spécifiques selon la catégorie. Tout passe par des dropdowns d'enums.
function _pcParamsFields(e) {
  const p = e.params || (e.params = {});
  const cat = e.category;
  const sel = (key, enumKey, opts) =>
    _pcEnumSelect(enumKey, p[key], `_pcSetParam('${key}',this.value)`, opts);

  if (cat === 'TRAIL') {
    return `
      <div><label class="field-label">Particule</label>${sel('particle', 'particles')}</div>
      <div><label class="field-label">Forme</label>${sel('shape', 'shapes')}</div>
      <div>
        <label class="field-label">Densité (1-6)</label>
        <input type="number" min="1" max="6" step="1" class="input input-sm input-bordered w-full"
          value="${p.density != null ? p.density : 3}"
          oninput="_pcSetParam('density', Math.max(1, Math.min(6, parseInt(this.value)||3)))">
      </div>`;
  }
  if (cat === 'KILL_EFFECT' || cat === 'DEATH_EFFECT') {
    return `
      <div><label class="field-label">Effet</label>${sel('effect', 'effects')}</div>
      <div><label class="field-label">Particule (optionnel)</label>${sel('particle', 'particles', { optional: true })}</div>
      <div style="grid-column:1/-1"><label class="field-label">Son (optionnel)</label>${sel('sound', 'sounds', { optional: true })}</div>`;
  }
  if (cat === 'CHAT_TAG') {
    return `
      <div>
        <label class="field-label">Tag (texte court)</label>
        <input type="text" maxlength="16" class="input input-sm input-bordered w-full"
          value="${escHtml(p.tag || '')}" placeholder="ex : LÉGENDE"
          oninput="_pcSetParam('tag', this.value)">
      </div>
      <div><label class="field-label">Couleur</label>${sel('color', 'colors')}</div>`;
  }
  if (cat === 'NAME_COLOR') {
    return `<div><label class="field-label">Couleur du pseudo</label>${sel('color', 'colors')}</div>`;
  }
  if (cat === 'PET') {
    return `
      <div><label class="field-label">Familier (mob)</label>${sel('mob', 'mobs')}</div>
      <div>
        <label class="field-label">Nom du familier (optionnel)</label>
        <input type="text" maxlength="24" class="input input-sm input-bordered w-full"
          value="${escHtml(p.petName || '')}" placeholder="ex : Braise"
          oninput="_pcSetParam('petName', this.value)">
      </div>
      <div style="display:flex;align-items:center;gap:.5rem;grid-column:1/-1">
        <input type="checkbox" class="toggle toggle-sm" ${p.baby ? 'checked' : ''}
          onchange="_pcSetParam('baby', this.checked)">
        <label class="text-xs">Bébé</label>
      </div>`;
  }
  return `<div class="text-xs" style="color:var(--muted)">Aucun réglage pour cette catégorie.</div>`;
}

function _pcSetParam(key, value) {
  if (!window._pc.editing.params) window._pc.editing.params = {};
  window._pc.editing.params[key] = value;
}

function _pcSetCategory(cat) {
  window._pc.editing.category = cat;
  // Changer de catégorie repart de params vierges (champs différents).
  window._pc.editing.params = {};
  // Re-rendre uniquement la section params + le titre.
  const body = document.getElementById('pc-editor-body');
  if (body) {
    body.innerHTML = _pcEditorBody(window._pc.editing);
    _pcMountIconViewer();
  }
}

function _pcSetIcon(itemId) {
  window._pc.editing.icon = itemId;
  _pcMountIconViewer();
}

// Monte (ou remonte) l'aperçu 3D de l'item icône. Fallback 2D si pas de 3D.
function _pcMountIconViewer() {
  const cont = document.getElementById('pc-icon-viewer');
  if (!cont) return;
  if (window._pc.viewer && window._pc.viewer.dispose) {
    try { window._pc.viewer.dispose(); } catch (e) {}
    window._pc.viewer = null;
  }
  const itemId = window._pc.editing ? window._pc.editing.icon : null;
  let handle = null;
  if (window.PlayCosmeticViewer && typeof window.PlayCosmeticViewer.mount === 'function') {
    // Fallback 2D demandé par le viewer si la texture/WebGL échoue.
    cont.__onNoTexture = () => _pcIconFallback2D(cont, itemId);
    try { handle = window.PlayCosmeticViewer.mount(cont, itemId); } catch (e) { handle = null; }
  }
  if (!handle || handle.noTexture) {
    _pcIconFallback2D(cont, itemId);
  }
  window._pc.viewer = handle;
}

function _pcIconFallback2D(cont, itemId) {
  cont.innerHTML = (typeof mkItemImg === 'function' && itemId)
    ? `<div style="transform:scale(3);image-rendering:pixelated">${mkItemImg(itemId, 48)}</div>`
    : `<span style="font-size:3rem">✨</span>`;
}

function _pcCloseEditor() {
  if (window._pc.viewer && window._pc.viewer.dispose) {
    try { window._pc.viewer.dispose(); } catch (e) {}
    window._pc.viewer = null;
  }
  const dlg = document.getElementById('pc-editor');
  if (dlg && typeof dlg.close === 'function') dlg.close();
  window._pc.editing = null;
}

// Construit le payload propre selon la catégorie (params filtrés).
function _pcBuildPayload(e) {
  const cat = e.category;
  const p = e.params || {};
  let params = {};
  if (cat === 'TRAIL') {
    params = { particle: p.particle || '', shape: p.shape || '', density: parseInt(p.density) || 3 };
  } else if (cat === 'KILL_EFFECT' || cat === 'DEATH_EFFECT') {
    params = { effect: p.effect || '' };
    if (p.particle) params.particle = p.particle;
    if (p.sound) params.sound = p.sound;
  } else if (cat === 'CHAT_TAG') {
    params = { tag: p.tag || '', color: p.color || '' };
  } else if (cat === 'NAME_COLOR') {
    params = { color: p.color || '' };
  } else if (cat === 'PET') {
    params = { mob: p.mob || '', baby: !!p.baby };
    if (p.petName) params.petName = p.petName;
  }
  const payload = {
    title: e.title || '',
    description: e.description || '',
    category: cat,
    icon: e.icon || '',
    price: parseInt(e.price) || 0,
    isNew: !!e.isNew,
    enabled: !!e.enabled,
    params,
  };
  if (e.id) payload.id = e.id;
  return payload;
}

async function _pcSave() {
  const e = window._pc.editing;
  if (!e) return;
  if (!e.title || !e.title.trim()) { _toast('Le titre est requis.', 'error'); return; }
  const payload = _pcBuildPayload(e);
  try {
    if (e._isNew) await API.store.playcosmetics.create(payload);
    else          await API.store.playcosmetics.update(e.id, payload);
  } catch (err) {
    _toast(err.message || t('error.unknown'), 'error');
    return;
  }
  _toast('Cosmétique enregistré.', 'success');
  _pcCloseEditor();
  _pcReload();
}

async function _pcDeleteFromEditor() {
  const e = window._pc.editing;
  if (!e || !e.id) { _pcCloseEditor(); return; }
  const id = e.id;
  _pcCloseEditor();
  _pcDelete(id);
}

async function _pcDelete(id) {
  if (!await confirmAction('Supprimer ce cosmétique ? Cette action est définitive.')) return;
  try {
    await API.store.playcosmetics.remove(id);
    _toast('Cosmétique supprimé.', 'success');
    _pcReload();
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
  }
}

async function _pcDuplicate(id) {
  const src = window._pc.list.find(x => x.id === id);
  if (!src) return;
  const payload = {
    title: (src.title || '') + ' (copie)',
    description: src.description || '',
    category: src.category,
    icon: src.icon || '',
    price: src.price || 0,
    isNew: false,
    enabled: false, // une copie démarre désactivée pour éviter un doublon en boutique
    params: Object.assign({}, src.params || {}),
  };
  try {
    await API.store.playcosmetics.create(payload);
    _toast('Cosmétique dupliqué (désactivé).', 'success');
    _pcCloseEditor();
    _pcReload();
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TESTER EN JEU (grant) — l'admin s'octroie le cosmétique pour l'équiper via /cosmetics
// ═══════════════════════════════════════════════════════════════════════════════

function _pcTest(id) {
  const c = window._pc.list.find(x => x.id === id);
  if (!c) return;
  const dlg = document.getElementById('pc-test');
  if (!dlg) return;
  dlg.innerHTML = `
    <div class="modal-box max-w-md" style="background:var(--bg-card)">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:.75rem">
        <h3 style="font-weight:700;font-size:1.05rem;color:var(--amber)">🎮 Tester « ${escHtml(c.title || c.id)} »</h3>
        <button class="modal-close" onclick="document.getElementById('pc-test').close()">✕</button>
      </div>
      <p class="text-sm" style="color:var(--muted);margin-bottom:.75rem">
        Octroyez ce cosmétique à un joueur (vous-même de préférence) pour l'essayer en jeu.
      </p>
      <label class="field-label">Pseudo du joueur</label>
      <div style="display:flex;gap:.5rem">
        <input id="pc-test-player" class="input input-sm input-bordered flex-1" placeholder="Pseudo Minecraft" autocomplete="off">
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_pcTestPick()">Choisir…</button>
      </div>
      <div class="text-xs" style="color:var(--muted);margin-top:.85rem;line-height:1.5">
        <div style="font-weight:600;color:var(--text-primary);margin-bottom:.25rem">Étapes de test :</div>
        1. Octroyez le cosmétique ci-dessous.<br>
        2. En jeu, tapez <code>/cosmetics</code> pour ouvrir le menu.<br>
        3. Équipez « ${escHtml(c.title || c.id)} » et vérifiez l'effet.
      </div>
      <div style="display:flex;gap:.5rem;justify-content:flex-end;margin-top:1rem;padding-top:.85rem;border-top:1px solid var(--bg-border)">
        <button class="btn-cg btn-cg-ghost" onclick="document.getElementById('pc-test').close()">${t('common.cancel')}</button>
        <button class="btn-cg btn-cg-primary" onclick="_pcTestGrant('${escHtml(id)}')">Octroyer</button>
      </div>
    </div>`;
  if (typeof dlg.showModal === 'function' && !dlg.open) dlg.showModal();
  // Pré-remplit avec l'admin player du dashboard si dispo.
  const app = window._pc.app;
  const inp = document.getElementById('pc-test-player');
  if (inp && app && app.adminPlayer) inp.value = app.adminPlayer;
}

function _pcTestPick() {
  if (typeof openPlayerPicker !== 'function') return;
  openPlayerPicker({
    title: 'Choisir un joueur',
    onSelect: (name) => { const inp = document.getElementById('pc-test-player'); if (inp) inp.value = name; },
  });
}

async function _pcTestGrant(id) {
  const inp = document.getElementById('pc-test-player');
  const player = inp ? inp.value.trim() : '';
  if (!player) { _toast('Indiquez un pseudo.', 'error'); return; }
  try {
    await API.store.playcosmetics.grant({ player, id });
    _toast(`Cosmétique octroyé à ${player}. Tapez /cosmetics en jeu pour l'équiper.`, 'success');
    const dlg = document.getElementById('pc-test');
    if (dlg) dlg.close();
  } catch (e) {
    _toast(e.message || t('error.unknown'), 'error');
  }
}
