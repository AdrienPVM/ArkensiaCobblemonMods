// ── CheeseMarket (Market list + Market Detail + Layout Editor) ───────────

async function buildMarket(app) {
  // Cleanup layout editor state + listeners
  if (window._mkl) {
    document.removeEventListener('keydown', mkLayoutKeyHandler);
    document.removeEventListener('mousemove', mkLayoutMouseMove);
    document.removeEventListener('mouseup', mkLayoutMouseUp);
  }
  window._mkl = null;
  let shops, stats;
  try {
    [shops, stats] = await Promise.all([API.market.shops(), API.market.stats()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  // State for list view
  window._mk = {
    shops, stats, app,

    async refresh() {
      try {
        [this.shops, this.stats] = await Promise.all([API.market.shops(), API.market.stats()]);
        renderMarketList(this.shops);
        renderMarketStats(this.stats);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async createShop() {
      const idInp = document.getElementById('mk-new-id');
      const nameInp = document.getElementById('mk-new-name');
      const id = idInp ? idInp.value.trim() : '';
      const name = nameInp ? nameInp.value.trim() : '';
      try {
        const created = await API.market.createShop({ id: id || undefined, name: name || t('market.newShop') });
        app.toast(t('market.created'), 'success');
        if (idInp) idInp.value = '';
        if (nameInp) nameInp.value = '';
        app.navigate('market:' + created.id);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteShop(shopId) {
      if (!await confirmAction(t('market.deleteShop'))) return;
      try {
        await API.market.deleteShop(shopId);
        app.toast(t('market.deleted'), 'success');
        this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  function renderMarketStats(st) {
    const el = document.getElementById('mk-stats');
    if (!el) return;
    el.innerHTML = `
      <div class="stat-card"><span class="stat-icon" style="color:var(--amber)">🛒</span><span class="stat-value" style="color:var(--amber)">${st.shopCount}</span><span class="stat-label">${t('market.shops')}</span></div>
      <div class="stat-card"><span class="stat-icon" style="color:var(--cyan)">📦</span><span class="stat-value" style="color:var(--cyan)">${st.totalSets}</span><span class="stat-label">${t('market.sets')}</span></div>
      <div class="stat-card"><span class="stat-icon" style="color:var(--green)">🏷</span><span class="stat-value" style="color:var(--green)">${st.totalItems}</span><span class="stat-label">${t('market.items')}</span></div>
      <div class="stat-card"><span class="stat-icon" style="color:var(--muted)">✅</span><span class="stat-value" style="color:var(--muted-light)">${st.enabledItems}</span><span class="stat-label">${t('common.active')}</span></div>
      <div class="stat-card"><span class="stat-icon" style="color:var(--muted)">📊</span><span class="stat-value" style="color:var(--muted-light)">${st.transactionCount}</span><span class="stat-label">${t('market.transactions')}</span></div>`;
  }

  function renderMarketList(shops) {
    const el = document.getElementById('mk-shop-list');
    if (!el) return;
    if (!shops.length) {
      el.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-8">' + t('common.noResults') + '</td></tr>';
      return;
    }
    el.innerHTML = shops.map(s => {
      const nav = "_nav('market:" + escHtml(s.id) + "')";
      return `
      <tr class="cursor-pointer" onclick="${nav}">
        <td><span class="id-badge cursor-pointer hover-elevated" style="cursor:copy" title="${t('common.copy')}" onclick="event.stopPropagation();cgCopy('${escHtml(s.id)}').then(()=>_mk.app.toast(t('common.copied'),'success'))">${escHtml(s.id)}</span></td>
        <td>${escHtml(s.name || '—')}</td>
        <td><span class="px-1.5 py-0.5 rounded text-xs font-mono" style="background:var(--bg-elevated);color:var(--cyan)">${escHtml(s.displayMode)}</span></td>
        <td class="text-center">${s.setCount}</td>
        <td class="text-center">${s.itemCount}</td>
        <td class="text-right">
          <button class="btn-cg btn-cg-ghost" onclick="event.stopPropagation(); ${nav}">${t('common.details')}</button>
          <button class="btn-cg btn-cg-danger" onclick="event.stopPropagation(); _mk.deleteShop('${escHtml(s.id)}')">${t('common.delete')}</button>
        </td>
      </tr>`;
    }).join('');
  }

  setTimeout(() => { renderMarketStats(stats); renderMarketList(shops); }, 50);

  return `
    <div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <!-- Stats -->
      <div id="mk-stats" class="grid grid-cols-5 gap-3 mb-4"></div>

      <!-- Create shop -->
      <div class="section-card mb-4">
        <div class="section-card-header">
          <span class="section-card-title">${t('market.title')}</span>
          <div class="flex gap-2 items-center">
            <input id="mk-new-id" class="input input-sm input-bordered w-24" placeholder="${t('market.shopId')}">
            <input id="mk-new-name" class="input input-sm input-bordered w-40" placeholder="${t('common.name')}">
            <button class="btn-cg btn-cg-primary" onclick="_mk.createShop()">${t('common.create')}</button>
            <button class="btn-cg btn-cg-ghost" onclick="_mk.refresh()">${t('common.refresh')}</button>
          </div>
        </div>
        <table class="cg-table">
          <thead><tr><th>${t('common.id')}</th><th>${t('common.name')}</th><th>${t('events.mode')}</th><th>${t('market.sets')}</th><th>${t('market.items')}</th><th></th></tr></thead>
          <tbody id="mk-shop-list"></tbody>
        </table>
      </div>
    </div>`;
}

function mkSwitchTab(tabId, btn) {
  switchDetailTab(tabId, btn, 'mk');
  if (tabId === 'layout' && window._mkd && !window._mkd._layoutLoaded) {
    window._mkd._layoutLoaded = true;
    mkLayoutInit();
  }
}
// ── Layout Editor ─────────────────────────────────────────────────────────
// State: window._mkl (layout editor state)
const MK_LAYOUT_W = 350, MK_LAYOUT_H = 250;
const MK_ELEMENT_TYPES = [
  { type: 'BACKGROUND', get label() { return t('market.elBackground'); }, icon: '🖼' },
  { type: 'TEXT', get label() { return t('market.elText'); }, icon: '🔤' },
  { type: 'BUTTON', get label() { return t('market.elButton'); }, icon: '🔘' },
  { type: 'ITEM_GRID', get label() { return t('market.elItemGrid'); }, icon: '📦' },
  { type: 'IMAGE', get label() { return t('market.elImage'); }, icon: '📷' },
];

async function mkLayoutInit() {
  const root = document.getElementById('mkd-layout-root');
  if (!root || !window._mkd) return;
  const shopId = window._mkd.shop.id;
  let layout;
  try { layout = await API.market.getLayout(shopId); }
  catch (e) { root.innerHTML = `<div class="empty-state"><span class="empty-state-text">${escHtml(e.message)}</span></div>`; return; }

  window._mkl = {
    shopId,
    layout,
    selected: null, // element id
    snap: 5,
    zoom: 2,          // canvas zoom (1-4)
    itemCardOpen: false,
    confirmOpen: false,
    itemCardZoom: 4,   // item card editor zoom (2-8)
    itemCardDragging: null, // { target:'icon'|'cost'|'costIcon', startX, startY, ... }
    assetCache: {},    // { name: blobUrl } — cached background images
    history: [JSON.stringify(layout)],
    historyIdx: 0,
    dragging: null, // { elId, startX, startY, elStartX, elStartY }
    resizing: null, // { elId, handle, startX, startY, elStartX, elStartY, elStartW, elStartH }
    _saveDebounce: debounce(async () => { if (window._mkl) await mkLayoutSave(); }, 2000),

    pushHistory() {
      const json = JSON.stringify(this.layout);
      // Trim future states
      this.history = this.history.slice(0, this.historyIdx + 1);
      this.history.push(json);
      if (this.history.length > 50) this.history.shift();
      this.historyIdx = this.history.length - 1;
      this._saveDebounce();
    },
    undo() {
      if (this.historyIdx > 0) {
        this.historyIdx--;
        this.layout = JSON.parse(this.history[this.historyIdx]);
        mkLayoutRender();
        this._saveDebounce();
      }
    },
    redo() {
      if (this.historyIdx < this.history.length - 1) {
        this.historyIdx++;
        this.layout = JSON.parse(this.history[this.historyIdx]);
        mkLayoutRender();
        this._saveDebounce();
      }
    },
  };

  mkLayoutRender();

  // Keyboard shortcuts + global drag events (cleanup old ones first)
  document.removeEventListener('keydown', mkLayoutKeyHandler);
  document.removeEventListener('mousemove', mkLayoutMouseMove);
  document.removeEventListener('mouseup', mkLayoutMouseUp);
  document.addEventListener('keydown', mkLayoutKeyHandler);
  document.addEventListener('mousemove', mkLayoutMouseMove);
  document.addEventListener('mouseup', mkLayoutMouseUp);
}

function mkLayoutKeyHandler(e) {
  if (!window._mkl) return;
  const t = e.target?.tagName;
  if (t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT') return;
  if (e.ctrlKey && e.key === 'z') { e.preventDefault(); _mkl.undo(); }
  else if (e.ctrlKey && e.key === 'y') { e.preventDefault(); _mkl.redo(); }
  else if (e.key === 'Delete' && _mkl.selected) { mkLayoutDeleteEl(_mkl.selected); }
  else if (e.ctrlKey && e.key === 'd' && _mkl.selected) { e.preventDefault(); mkLayoutDuplicateEl(_mkl.selected); }
  else if (_mkl.selected && ['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(e.key)) {
    e.preventDefault();
    const el = _mkl.layout.elements.find(x => x.id === _mkl.selected);
    if (!el) return;
    const step = e.shiftKey ? _mkl.snap : 1;
    if (e.key === 'ArrowUp') el.y = Math.max(0, el.y - step);
    else if (e.key === 'ArrowDown') el.y = Math.min(MK_LAYOUT_H - el.height, el.y + step);
    else if (e.key === 'ArrowLeft') el.x = Math.max(0, el.x - step);
    else if (e.key === 'ArrowRight') el.x = Math.min(MK_LAYOUT_W - el.width, el.x + step);
    _mkl.pushHistory();
    mkLayoutRender();
  }
}

function mkLayoutRender() {
  const root = document.getElementById('mkd-layout-root');
  if (!root || !window._mkl) return;
  const L = _mkl.layout;
  const els = [...(L.elements || [])].sort((a,b) => a.zOrder - b.zOrder);
  const ic = L.itemCard || {};

  // Toolbar
  let toolbar = `<div class="flex flex-wrap items-center gap-2 mb-3">
    <label class="text-xs" style="color:var(--text-muted)">${t('market.snap')}:</label>
    <select class="select select-xs select-bordered" style="width:70px" onchange="_mkl.snap=+this.value">
      ${[1,2,5,10,15,20].map(v => `<option value="${v}" ${_mkl.snap === v ? 'selected' : ''}>${v}px</option>`).join('')}
    </select>
    <label class="text-xs" style="color:var(--text-muted)">${t('market.zoom')}:</label>
    <input type="range" min="1" max="4" step="0.5" value="${_mkl.zoom}" style="width:80px;accent-color:var(--amber)"
      oninput="_mkl.zoom=+this.value;mkLayoutRender()">
    <span class="text-xs mono" style="color:var(--muted);min-width:28px">${_mkl.zoom}×</span>
    <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_mkl.undo()" title="Ctrl+Z">${t('common.cancel')}</button>
    <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_mkl.redo()" title="Ctrl+Y">${t('market.redo')}</button>
    <div class="dropdown dropdown-end">
      <button tabindex="0" class="btn-cg btn-cg-primary btn-cg-sm">${t('common.add')}</button>
      <ul tabindex="0" class="dropdown-content z-50 menu p-2 shadow rounded-box w-52" style="background:var(--bg-card);border:1px solid var(--bg-border)">
        ${MK_ELEMENT_TYPES.map(t => `<li><a onclick="mkLayoutAddEl('${t.type}')" class="text-sm" style="color:var(--text-primary)">${t.icon} ${t.label}</a></li>`).join('')}
      </ul>
    </div>
    <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="mkLayoutUploadAsset()">${t('market.uploadImage')}</button>
    <div class="flex-1"></div>
    <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="mkLayoutTemplates()">${t('market.templates')}</button>
    <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="mkLayoutReset()">${t('common.reset')}</button>
  </div>`;

  // Canvas (350x250 preview)
  const shopBg = _mkl.shopId ? API.market.assetUrl(_mkl.shopId, 'shop-bg') : '';
  let canvasEls = '';
  for (const el of els) {
    const sel = _mkl.selected === el.id;
    const p = el.props || {};
    let inner = '';
    let style = `left:${el.x}px;top:${el.y}px;width:${el.width}px;height:${el.height}px;z-index:${el.zOrder};`;
    let cls = 'mkl-el';

    if (el.type === 'BACKGROUND') {
      const assetName = p.src || '';
      if (assetName && assetName !== 'default') {
        const cached = _mkl.assetCache[assetName];
        if (cached) {
          inner = `<img src="${cached}" style="width:100%;height:100%;object-fit:cover;image-rendering:pixelated" draggable="false">`;
        } else {
          inner = `<div style="width:100%;height:100%;background:var(--mk-gui-bg);display:flex;align-items:center;justify-content:center;font-size:10px;color:var(--muted)">${t('common.loading')}</div>`;
          // Fetch with auth and cache as blob URL
          (async () => {
            try {
              const sid = API.getToken();
              const resp = await fetch(API.market.assetUrl(_mkl.shopId, assetName), { headers: { 'Authorization': 'Bearer ' + sid } });
              if (resp.ok) {
                const blob = await resp.blob();
                _mkl.assetCache[assetName] = URL.createObjectURL(blob);
                mkLayoutRender();
              }
            } catch(e) {}
          })();
        }
      } else {
        inner = `<div style="width:100%;height:100%;background:var(--mk-gui-bg);display:flex;align-items:center;justify-content:center;font-size:10px;color:var(--muted)">${t('common.none')}</div>`;
      }
      style += 'pointer-events:none;';
      cls += ' mkl-bg';
    } else if (el.type === 'TEXT') {
      const txt = (p.text || t('market.defaultText')).replace('{shopName}', escHtml(_mkd.shop.name || 'Shop')).replace('{shopDesc}', escHtml(_mkd.shop.description || ''));
      const fontSize = p.fontSize || 9;
      const color = p.color === '{titleColor}' ? '#' + (_mkd.shop.titleColor || 'FFD700').replace('#','') : '#' + (p.color || 'FFFFFF').replace('#','');
      const align = p.align || 'center';
      const scale = p.scale || 1;
      inner = `<span style="font-size:${fontSize * scale}px;color:${color};text-align:${align};display:block;white-space:nowrap;overflow:hidden;font-family:'Minecraft',monospace;${p.bold ? 'font-weight:bold;' : ''}">${txt}</span>`;
    } else if (el.type === 'BUTTON') {
      const bgC = '#' + (p.color || '1E3A5F').replace('#','');
      const txtC = '#' + (p.textColor || 'CCCCCC').replace('#','');
      inner = `<div style="background:${bgC};color:${txtC};display:flex;align-items:center;justify-content:center;height:100%;border-radius:2px;font-size:8px;font-family:'Minecraft',monospace">${escHtml(p.text || (p.action === 'close' ? 'X' : t('market.defaultBtn')))}</div>`;
    } else if (el.type === 'ITEM_GRID') {
      inner = mkLayoutRenderGrid(el, ic);
    } else if (el.type === 'IMAGE') {
      const src = p.src ? API.market.assetUrl(_mkl.shopId, p.src) + '?t=' + Date.now() : '';
      inner = src ? `<img src="${src}" style="width:100%;height:100%;object-fit:contain;image-rendering:pixelated" draggable="false" onerror="this.style.display='none'">` : `<div style="border:1px dashed var(--bg-border);height:100%;display:flex;align-items:center;justify-content:center;font-size:10px;color:var(--muted)">${t('market.emptyImage')}</div>`;
    }

    if (sel) cls += ' mkl-selected';
    canvasEls += `<div class="${cls}" data-elid="${el.id}" style="${style}"
      onmousedown="mkLayoutElMouseDown(event,'${el.id}')"
      onclick="event.stopPropagation();mkLayoutSelect('${el.id}')">
      ${inner}
      ${sel && el.type !== 'BACKGROUND' ? mkLayoutResizeHandles() : ''}
    </div>`;
  }

  // Guides (center lines)
  const guides = `<div class="mkl-guide-h" style="top:${MK_LAYOUT_H/2}px"></div><div class="mkl-guide-v" style="left:${MK_LAYOUT_W/2}px"></div>`;

  const zm = _mkl.zoom;
  let canvas = `<div class="flex gap-4">
    <div class="mkl-canvas-wrap" style="width:${MK_LAYOUT_W * zm}px;height:${MK_LAYOUT_H * zm}px;overflow:hidden">
      <div id="mkl-canvas" class="mkl-canvas" style="width:${MK_LAYOUT_W}px;height:${MK_LAYOUT_H}px;position:relative;overflow:hidden;transform:scale(${zm});transform-origin:top left"
           onclick="mkLayoutSelect(null)" onmousemove="mkLayoutShowCoords(event)" onmouseleave="mkLayoutHideCoords()">
        ${guides}${canvasEls}
      </div>
    </div>
    <div>
      <div class="text-xs mb-2 flex gap-3" style="color:var(--muted)">
        <span>${MK_LAYOUT_W}×${MK_LAYOUT_H}px</span>
        <span>${els.length}</span>
        <span id="mkl-coords" class="mono"></span>
      </div>
      <div class="mkl-props" style="min-width:220px;max-width:300px">
        ${_mkl.selected ? mkLayoutPropsPanel() : `<div class="text-sm text-center py-8" style="color:var(--muted)">${t('common.select')}</div>`}
      </div>
    </div>
  </div>`;

  // Layers panel
  let layers = `<div class="mt-4"><div class="text-xs font-semibold mb-2" style="color:var(--text-muted)">${t('market.layers')}</div>
    <div class="mkl-layers">
      ${[...els].reverse().map(el => {
        const p = el.props || {};
        const typeLabel = MK_ELEMENT_TYPES.find(t => t.type === el.type)?.icon || '?';
        const name = el.type === 'TEXT' ? (p.text || t('market.elText')).substring(0, 20) : el.type === 'BUTTON' ? (p.action === 'close' ? t('common.close') : t('market.elButton')) : el.type;
        const sel = _mkl.selected === el.id;
        return `<div class="mkl-layer ${sel ? 'mkl-layer-active' : ''}" onclick="mkLayoutSelect('${el.id}')">
          <span class="mkl-layer-icon">${typeLabel}</span>
          <span class="mkl-layer-name">${escHtml(name)}</span>
          <span class="mkl-layer-z">z:${el.zOrder}</span>
          <button class="mkl-layer-btn" onclick="event.stopPropagation();mkLayoutMoveZ('${el.id}',1)" title="${t('market.moveUp')}">▲</button>
          <button class="mkl-layer-btn" onclick="event.stopPropagation();mkLayoutMoveZ('${el.id}',-1)" title="${t('market.moveDown')}">▼</button>
        </div>`;
      }).join('')}
    </div>
  </div>`;

  // Item card sub-editor
  let itemCardEditor = `<div class="mt-4">
    <div class="flex items-center gap-2 mb-2">
      <span class="text-xs font-semibold" style="color:var(--text-muted)">${t('market.itemCard')}</span>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="mkLayoutToggleItemCard()">
        ${_mkl.itemCardOpen ? '▼' : '▶'}
      </button>
      ${_mkl.itemCardOpen ? `<label class="text-xs ml-2" style="color:var(--muted)">${t('market.zoom')}:</label>
        <input type="range" min="2" max="8" step="1" value="${_mkl.itemCardZoom}" style="width:60px;accent-color:var(--amber)"
          oninput="_mkl.itemCardZoom=+this.value;mkLayoutRender()">
        <span class="text-xs mono" style="color:var(--muted)">${_mkl.itemCardZoom}×</span>` : ''}
    </div>
    <div id="mkl-itemcard-body" style="display:${_mkl.itemCardOpen ? '' : 'none'}">
      ${mkLayoutItemCardEditor(ic)}
    </div>
  </div>`;

  // Confirm layout sub-editor
  const cl = L.confirmLayout || {};
  let confirmEditor = `<div class="mt-4">
    <div class="flex items-center gap-2 mb-2">
      <span class="text-xs font-semibold" style="color:var(--text-muted)">${t('market.tabs.config')}</span>
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="mkLayoutToggleConfirm()">
        ${_mkl.confirmOpen ? '▼' : '▶'}
      </button>
    </div>
    <div id="mkl-confirm-body" style="display:${_mkl.confirmOpen ? '' : 'none'}">
      ${mkLayoutConfirmEditor(cl)}
    </div>
  </div>`;

  root.innerHTML = toolbar + canvas + layers + itemCardEditor + confirmEditor;
}

function mkLayoutRenderGrid(gridEl, ic) {
  const p = gridEl.props ?? {};
  const cols = p.cols ?? 6;
  const rows = p.rows ?? 3;
  const padX = p.padX ?? 4;
  const padY = p.padY ?? 3;
  const cw = ic.cardWidth ?? 41;
  const ch = ic.cardHeight ?? 36;
  const iconOx = ic.iconOffsetX ?? 13;
  const iconOy = ic.iconOffsetY ?? 7;
  const iconSz = ic.iconSize ?? 15;

  let html = '<div style="position:relative;width:100%;height:100%">';
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const x = c * (cw + padX);
      const y = r * (ch + padY);
      html += `<div style="position:absolute;left:${x}px;top:${y}px;width:${cw}px;height:${ch}px;background:rgba(30,48,80,0.6);border:1px solid rgba(42,90,138,0.5);border-radius:2px">
        <div style="position:absolute;left:${iconOx}px;top:${iconOy}px;width:${iconSz}px;height:${iconSz}px;background:rgba(255,204,68,0.3);border:1px dashed rgba(255,204,68,0.5)"></div>
      </div>`;
    }
  }
  html += '</div>';
  return html;
}

function mkLayoutResizeHandles() {
  return ['nw','ne','sw','se','n','s','e','w'].map(h =>
    `<div class="mkl-handle mkl-handle-${h}" onmousedown="event.stopPropagation();mkLayoutResizeStart(event,'${h}')"></div>`
  ).join('');
}

function mkLayoutPropsPanel() {
  const el = _mkl.layout.elements.find(x => x.id === _mkl.selected);
  if (!el) return '';
  const p = el.props || {};
  const typeLabel = MK_ELEMENT_TYPES.find(t => t.type === el.type)?.label || el.type;

  let fields = `
    <div class="text-xs font-semibold mb-3" style="color:var(--amber)">${typeLabel}</div>
    <div class="grid grid-cols-2 gap-2 mb-3">
      <div><label class="text-xs" style="color:var(--muted)">X</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${el.x}" onchange="mkLayoutSetProp('${el.id}','x',+this.value)"></div>
      <div><label class="text-xs" style="color:var(--muted)">Y</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${el.y}" onchange="mkLayoutSetProp('${el.id}','y',+this.value)"></div>
      <div><label class="text-xs" style="color:var(--muted)">${t('market.width')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${el.width}" onchange="mkLayoutSetProp('${el.id}','width',+this.value)"></div>
      <div><label class="text-xs" style="color:var(--muted)">${t('market.height')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${el.height}" onchange="mkLayoutSetProp('${el.id}','height',+this.value)"></div>
    </div>
    <div class="mb-3">
      <label class="text-xs" style="color:var(--muted)">Z-Order</label>
      <input type="number" class="input input-xs input-bordered w-full" value="${el.zOrder}" onchange="mkLayoutSetProp('${el.id}','zOrder',+this.value)">
    </div>`;

  if (el.type === 'TEXT') {
    fields += `
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.layout.text')}</label>
        <input class="input input-xs input-bordered w-full" value="${escHtml(p.text || '')}" onchange="mkLayoutSetElProp('${el.id}','text',this.value)">
        <div class="text-xs mt-1" style="color:var(--muted)">{shopName}, {shopDesc}, {titleColor}</div></div>
      <div class="grid grid-cols-2 gap-2 mb-2">
        <div><label class="text-xs" style="color:var(--muted)">${t('market.layout.sizePx')}</label>
          <input type="number" class="input input-xs input-bordered w-full" value="${p.fontSize || 9}" onchange="mkLayoutSetElProp('${el.id}','fontSize',+this.value)"></div>
        <div><label class="text-xs" style="color:var(--muted)">${t('market.layout.scale')}</label>
          <input type="number" step="0.05" class="input input-xs input-bordered w-full" value="${p.scale || 1}" onchange="mkLayoutSetElProp('${el.id}','scale',+this.value)"></div>
      </div>
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('common.color')}</label>
        <div class="flex gap-1"><input type="color" value="#${(p.color || 'FFFFFF').replace('{titleColor}','FFD700').replace('#','')}" class="w-7 h-7 rounded cursor-pointer" style="border:1px solid var(--bg-border)"
          onchange="mkLayoutSetElProp('${el.id}','color',this.value.substring(1))">
        <input class="input input-xs input-bordered flex-1 font-mono" value="${escHtml(p.color || 'FFFFFF')}" onchange="mkLayoutSetElProp('${el.id}','color',this.value)"></div></div>
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.layout.align')}</label>
        <select class="select select-xs select-bordered w-full" onchange="mkLayoutSetElProp('${el.id}','align',this.value)">
          ${['left','center','right'].map(a => `<option ${(p.align||'center')===a?'selected':''}>${a}</option>`).join('')}
        </select></div>
      <label class="flex items-center gap-2 text-xs mb-2" style="color:var(--muted)">
        <input type="checkbox" class="checkbox checkbox-xs" ${p.bold ? 'checked' : ''} onchange="mkLayoutSetElProp('${el.id}','bold',this.checked)"> ${t('market.layout.bold')}</label>`;
  } else if (el.type === 'BUTTON') {
    fields += `
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.layout.action')}</label>
        <select class="select select-xs select-bordered w-full" onchange="mkLayoutSetElProp('${el.id}','action',this.value)">
          <option value="close" ${(p.action||'close')==='close'?'selected':''}>${t('common.close')} (X)</option>
          <option value="custom" ${p.action==='custom'?'selected':''}>${t('market.layout.custom')}</option>
        </select></div>
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.layout.text')}</label>
        <input class="input input-xs input-bordered w-full" value="${escHtml(p.text || '')}" onchange="mkLayoutSetElProp('${el.id}','text',this.value)"></div>
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.layout.bgColor')}</label>
        <div class="flex gap-1"><input type="color" value="#${(p.color || '1E3A5F').replace('#','')}" class="w-7 h-7 rounded cursor-pointer" style="border:1px solid var(--bg-border)"
          onchange="mkLayoutSetElProp('${el.id}','color',this.value.substring(1))">
        <input class="input input-xs input-bordered flex-1 font-mono" value="${escHtml(p.color || '1E3A5F')}" onchange="mkLayoutSetElProp('${el.id}','color',this.value)"></div></div>
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.layout.textColor')}</label>
        <div class="flex gap-1"><input type="color" value="#${(p.textColor || 'CCCCCC').replace('#','')}" class="w-7 h-7 rounded cursor-pointer" style="border:1px solid var(--bg-border)"
          onchange="mkLayoutSetElProp('${el.id}','textColor',this.value.substring(1))">
        <input class="input input-xs input-bordered flex-1 font-mono" value="${escHtml(p.textColor || 'CCCCCC')}" onchange="mkLayoutSetElProp('${el.id}','textColor',this.value)"></div></div>`;
  } else if (el.type === 'BACKGROUND') {
    fields += `
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.layout.source')}</label>
        <select class="select select-xs select-bordered w-full" id="mkl-bg-src" onchange="mkLayoutSetElProp('${el.id}','src',this.value)">
          <option value="default" ${(p.src||'default')==='default'?'selected':''}>${t('market.layout.defaultBg')}</option>
        </select>
        <div class="text-xs mt-1" style="color:var(--muted)">${t('market.layout.uploadHelp')}</div></div>`;
    // Load assets list for background selector
    setTimeout(async () => {
      const sel = document.getElementById('mkl-bg-src');
      if (!sel) return;
      try {
        const assets = await API.market.listAssets(_mkl.shopId);
        for (const name of assets) {
          const opt = document.createElement('option');
          opt.value = name; opt.textContent = name;
          if (p.src === name) opt.selected = true;
          sel.appendChild(opt);
        }
      } catch(e) {}
    }, 50);
  } else if (el.type === 'ITEM_GRID') {
    fields += `
      <div class="grid grid-cols-2 gap-2 mb-2">
        <div><label class="text-xs" style="color:var(--muted)">${t('market.columns')}</label>
          <input type="number" class="input input-xs input-bordered w-full" value="${p.cols || 6}" min="1" max="10" onchange="mkLayoutSetElProp('${el.id}','cols',+this.value)"></div>
        <div><label class="text-xs" style="color:var(--muted)">${t('market.visibleRows')}</label>
          <input type="number" class="input input-xs input-bordered w-full" value="${p.rows || 3}" min="1" max="6" onchange="mkLayoutSetElProp('${el.id}','rows',+this.value)"></div>
        <div><label class="text-xs" style="color:var(--muted)">${t('market.spacingH')}</label>
          <input type="number" class="input input-xs input-bordered w-full" value="${p.padX || 4}" min="0" max="20" onchange="mkLayoutSetElProp('${el.id}','padX',+this.value)"></div>
        <div><label class="text-xs" style="color:var(--muted)">${t('market.spacingV')}</label>
          <input type="number" class="input input-xs input-bordered w-full" value="${p.padY || 3}" min="0" max="20" onchange="mkLayoutSetElProp('${el.id}','padY',+this.value)"></div>
      </div>`;
  } else if (el.type === 'IMAGE') {
    fields += `
      <div class="mb-2"><label class="text-xs" style="color:var(--muted)">${t('market.assetSource')}</label>
        <select class="select select-xs select-bordered w-full" id="mkl-img-src" onchange="mkLayoutSetElProp('${el.id}','src',this.value)">
          <option value="">-- ${t('market.choose')} --</option>
        </select></div>`;
    setTimeout(async () => {
      const sel = document.getElementById('mkl-img-src');
      if (!sel) return;
      try {
        const assets = await API.market.listAssets(_mkl.shopId);
        for (const name of assets) {
          const opt = document.createElement('option');
          opt.value = name; opt.textContent = name;
          if (p.src === name) opt.selected = true;
          sel.appendChild(opt);
        }
      } catch(e) {}
    }, 50);
  }

  fields += `<div class="section-divider my-3"></div>
    <div class="flex gap-2">
      <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="mkLayoutDuplicateEl('${el.id}')" title="Ctrl+D">${t('common.duplicate')}</button>
      <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="mkLayoutDeleteEl('${el.id}')" title="${t('market.layout.deleteKey')}">${t('common.delete')}</button>
    </div>`;

  return fields;
}

function mkLayoutItemCardEditor(ic) {
  const zm = _mkl.itemCardZoom ?? 4;
  const cw = ic.cardWidth ?? 41, ch = ic.cardHeight ?? 36;
  const iconOx = ic.iconOffsetX ?? 13, iconOy = ic.iconOffsetY ?? 7, iconSz = ic.iconSize ?? 15;
  const costOx = ic.costOffsetX ?? 0, costOy = ic.costOffsetY ?? 26, costIconSz = ic.costIconSize ?? 9;
  const costScale = ic.costTextScale ?? 0.85, costGap = ic.costGap ?? 2, costIconOY = ic.costIconOffsetY ?? 0;
  const costTextIconGap = ic.costTextIconGap ?? -1;
  const costColor = (ic.costTextColor || 'FFCC44').replace('#', '');

  // In-game: cost row is auto-centered within card width, then shifted by costOx
  // Preview: simulate this by centering the cost zone via left:50% + translateX(-50%) + margin-left for offset
  return `<div class="flex gap-4 items-start">
    <div class="mkl-itemcard-preview" style="width:${cw * zm + 16}px;height:${ch * zm + 16}px;position:relative;padding:8px;background:rgba(30,48,80,0.6);border:1px solid rgba(42,90,138,0.5);border-radius:4px"
      title="${t('tip.market.itemCardPreview')}">
      <!-- Draggable icon -->
      <div id="mkl-ic-icon" class="mkl-ic-draggable" style="position:absolute;left:${8 + iconOx * zm}px;top:${8 + iconOy * zm}px;width:${iconSz * zm}px;height:${iconSz * zm}px;background:rgba(255,204,68,0.3);border:2px dashed rgba(255,204,68,0.6);cursor:move;display:flex;align-items:center;justify-content:center;font-size:${zm * 5}px;z-index:2"
        onmousedown="mkLayoutIcMouseDown(event,'icon')"
        title="${t('tip.market.iconDrag')}">🎮
        <div class="mkl-ic-resize" style="position:absolute;right:-5px;bottom:-5px;width:10px;height:10px;background:var(--amber);border:1px solid var(--bg);border-radius:50%;cursor:se-resize;z-index:3"
          onmousedown="event.stopPropagation();mkLayoutIcMouseDown(event,'iconResize')"
          title="${t('tip.market.resizeIcon')}"></div>
      </div>
      <!-- Draggable cost zone — centered like in-game, offset by costOx -->
      <div id="mkl-ic-cost" class="mkl-ic-draggable" style="position:absolute;left:50%;transform:translateX(-50%);margin-left:${costOx * zm}px;top:${8 + costOy * zm}px;cursor:move;display:flex;align-items:center;gap:${costTextIconGap * zm}px;z-index:2;padding:2px"
        onmousedown="mkLayoutIcMouseDown(event,'cost')"
        title="${t('tip.market.costDrag')}">
        <span style="font-size:${costScale * 12 * zm}px;color:#${costColor};font-family:'Minecraft',monospace"
          title="${t('tip.market.costText')}">×2</span>
        <span id="mkl-ic-costicon" style="display:inline-block;width:${costIconSz * zm}px;height:${costIconSz * zm}px;background:rgba(255,170,0,0.4);border:2px dashed rgba(255,170,0,0.6);position:relative;top:${costIconOY * zm}px"
          title="${t('tip.market.costIconCorner')}">
          <div class="mkl-ic-resize" style="position:absolute;right:-5px;bottom:-5px;width:10px;height:10px;background:var(--amber);border:1px solid var(--bg);border-radius:50%;cursor:se-resize;z-index:3"
            onmousedown="event.stopPropagation();mkLayoutIcMouseDown(event,'costIconResize')"
            title="${t('tip.market.resizeCostIcon')}"></div>
        </span>
      </div>
    </div>
    <div class="flex-1 grid grid-cols-2 gap-2 text-xs">
      <div><label style="color:var(--muted)" title="${t('tip.market.cardWidth')}">${t('market.cardWidth')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${cw}" min="20" max="80" onchange="mkLayoutSetItemCard('cardWidth',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.cardHeight')}">${t('market.cardHeight')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${ch}" min="20" max="80" onchange="mkLayoutSetItemCard('cardHeight',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.iconOffsetX')}">${t('market.iconOffsetX')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${iconOx}" onchange="mkLayoutSetItemCard('iconOffsetX',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.iconOffsetY')}">${t('market.iconOffsetY')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${iconOy}" onchange="mkLayoutSetItemCard('iconOffsetY',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.iconSize')}">${t('market.iconSize')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${iconSz}" min="5" max="40" onchange="mkLayoutSetItemCard('iconSize',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.costOffsetX')}">${t('market.costOffsetX')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${costOx}" onchange="mkLayoutSetItemCard('costOffsetX',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.costOffsetY')}">${t('market.costOffsetY')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${costOy}" onchange="mkLayoutSetItemCard('costOffsetY',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.costTextScale')}">${t('market.costTextScale')}</label>
        <input type="number" step="0.05" class="input input-xs input-bordered w-full" value="${costScale}" min="0.3" max="2" onchange="mkLayoutSetItemCard('costTextScale',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.costIconSize')}">${t('market.costIconSize')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${costIconSz}" min="3" max="20" onchange="mkLayoutSetItemCard('costIconSize',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.costSpacing')}">${t('market.costSpacing')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${costGap}" min="0" max="10" onchange="mkLayoutSetItemCard('costGap',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.textIconGap')}">${t('market.textIconGap')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${costTextIconGap}" min="-5" max="10" onchange="mkLayoutSetItemCard('costTextIconGap',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.costIconYOffset')}">${t('market.costIconYOffset')}</label>
        <input type="number" class="input input-xs input-bordered w-full" value="${costIconOY}" min="-10" max="10" onchange="mkLayoutSetItemCard('costIconOffsetY',+this.value)"></div>
      <div><label style="color:var(--muted)" title="${t('tip.market.costTextColor')}">${t('market.costTextColor')}</label>
        <div class="flex gap-1"><input type="color" value="#${costColor}" class="w-6 h-6 rounded cursor-pointer" style="border:1px solid var(--bg-border)"
          onchange="mkLayoutSetItemCard('costTextColor',this.value.substring(1))">
        <input class="input input-xs input-bordered flex-1 font-mono" value="${escHtml(costColor)}" onchange="mkLayoutSetItemCard('costTextColor',this.value)"></div></div>
    </div>
  </div>`;
}

// ── Layout editor actions ──
function mkLayoutSelect(elId) {
  if (!window._mkl) return;
  _mkl.selected = elId;
  mkLayoutRender();
}

function mkLayoutAddEl(type) {
  if (!window._mkl) return;
  const el = { id: Math.random().toString(36).substring(2, 10), type, x: 10, y: 10, width: 60, height: 20, zOrder: (_mkl.layout.elements.length + 1) * 5, props: {} };
  if (type === 'BACKGROUND') { el.x = 0; el.y = 0; el.width = MK_LAYOUT_W; el.height = MK_LAYOUT_H; el.zOrder = 0; el.props = { src: 'default' }; }
  else if (type === 'TEXT') { el.props = { text: 'Text', fontSize: 9, color: 'FFFFFF', align: 'center', scale: 1 }; }
  else if (type === 'BUTTON') { el.width = 20; el.height = 20; el.props = { action: 'close', color: '1E3A5F', textColor: 'CCCCCC', text: 'X' }; }
  else if (type === 'ITEM_GRID') { el.x = 32; el.y = 95; el.width = 286; el.height = 121; el.zOrder = 5; el.props = { cols: 6, rows: 3, padX: 4, padY: 3 }; }
  else if (type === 'IMAGE') { el.width = 40; el.height = 40; el.props = { src: '' }; }
  _mkl.layout.elements.push(el);
  _mkl.selected = el.id;
  _mkl.pushHistory();
  mkLayoutRender();
}

function mkLayoutDeleteEl(elId) {
  if (!window._mkl) return;
  _mkl.layout.elements = _mkl.layout.elements.filter(e => e.id !== elId);
  if (_mkl.selected === elId) _mkl.selected = null;
  _mkl.pushHistory();
  mkLayoutRender();
}

function mkLayoutDuplicateEl(elId) {
  if (!window._mkl) return;
  const el = _mkl.layout.elements.find(e => e.id === elId);
  if (!el) return;
  const clone = JSON.parse(JSON.stringify(el));
  clone.id = Math.random().toString(36).substring(2, 10);
  clone.x += 10; clone.y += 10;
  _mkl.layout.elements.push(clone);
  _mkl.selected = clone.id;
  _mkl.pushHistory();
  mkLayoutRender();
}

function mkLayoutMoveZ(elId, dir) {
  if (!window._mkl) return;
  const el = _mkl.layout.elements.find(e => e.id === elId);
  if (!el) return;
  el.zOrder += dir * 5;
  _mkl.pushHistory();
  mkLayoutRender();
}

function mkLayoutSetProp(elId, key, val) {
  if (!window._mkl) return;
  const el = _mkl.layout.elements.find(e => e.id === elId);
  if (!el) return;
  el[key] = val;
  _mkl.pushHistory();
  mkLayoutRender();
}

function mkLayoutSetElProp(elId, key, val) {
  if (!window._mkl) return;
  const el = _mkl.layout.elements.find(e => e.id === elId);
  if (!el) return;
  if (!el.props) el.props = {};
  el.props[key] = val;
  _mkl.pushHistory();
  mkLayoutRender();
}

function mkLayoutSetItemCard(key, val) {
  if (!window._mkl) return;
  if (!_mkl.layout.itemCard) _mkl.layout.itemCard = {};
  _mkl.layout.itemCard[key] = val;
  _mkl.pushHistory();
  mkLayoutRender();
}

function mkLayoutToggleItemCard() {
  if (!window._mkl) return;
  _mkl.itemCardOpen = !_mkl.itemCardOpen;
  mkLayoutRender();
}

function mkLayoutToggleConfirm() {
  if (!window._mkl) return;
  _mkl.confirmOpen = !_mkl.confirmOpen;
  mkLayoutRender();
}

function mkLayoutSetConfirm(key, val) {
  if (!window._mkl) return;
  if (!_mkl.layout.confirmLayout) _mkl.layout.confirmLayout = {};
  _mkl.layout.confirmLayout[key] = val;
  _mkl.pushHistory();
  mkLayoutRender();
}

async function mkLayoutUploadConfirmBg() {
  if (!window._mkl || !window._mkd) return;
  const input = document.createElement('input');
  input.type = 'file'; input.accept = 'image/png';
  input.onchange = async () => {
    const file = input.files[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) { _mkd.app.toast(t('market.fileTooLarge'), 'error'); return; }
    const name = 'confirm-bg';
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        await API.market.uploadAsset(_mkl.shopId, name, reader.result);
        _mkd.app.toast(t('common.success'), 'success');
        _mkl.assetCache[name] = reader.result;
        mkLayoutSetConfirm('bgImage', name);
      } catch (e) { _mkd.app.toast(e.message, 'error'); }
    };
    reader.readAsDataURL(file);
  };
  input.click();
}

function mkLayoutConfirmEditor(cl) {
  const w = cl.width ?? 340;
  const bgImageName = cl.bgImage || '';
  const bg = (cl.bgColor || '1A1A2E').replace('#','');
  const border = (cl.borderColor || '2A5A8A').replace('#','');
  const accent = (cl.accentColor || '4AC8EA').replace('#','');
  const headerBg = (cl.headerBgColor || '0D2137').replace('#','');
  const titleC = (cl.titleColor || '4AC8EA').replace('#','');
  const labelC = (cl.labelColor || '7A8A9A').replace('#','');
  const costC = (cl.costColor || 'FFCC44').replace('#','');
  const arrowC = (cl.arrowColor || '4AC8EA').replace('#','');
  const sliderBg = (cl.sliderBgColor || '1A2A4A').replace('#','');
  const sliderFill = (cl.sliderFillColor || '357EA3').replace('#','');
  const sliderThumb = (cl.sliderThumbColor || '4AC8EA').replace('#','');
  const sep = (cl.sepColor || '1E3050').replace('#','');
  const btnConfirm = (cl.btnConfirmTextColor || '55FF55').replace('#','');
  const btnCancel = (cl.btnCancelTextColor || 'FF6666').replace('#','');
  const colBg = (cl.columnBgColor || '18FFFFFF').replace('#','');

  // Parse columnBg as AARRGGBB for preview
  let colBgCss = 'rgba(255,255,255,0.09)';
  if (colBg.length === 8) {
    const a = parseInt(colBg.substring(0,2), 16) / 255;
    const r = parseInt(colBg.substring(2,4), 16);
    const g = parseInt(colBg.substring(4,6), 16);
    const b = parseInt(colBg.substring(6,8), 16);
    colBgCss = 'rgba(' + r + ',' + g + ',' + b + ',' + a.toFixed(2) + ')';
  }

  // Zoom factor for confirm preview
  const zm = 1.5;
  const pw = w * zm;

  // Simulated layout
  const itemsH = 50 * zm;
  const headerH = 18 * zm;
  const labelsH = 12 * zm;
  const sliderZoneH = 28 * zm;
  const btnZoneH = 24 * zm;
  const sepH = 1 * zm;
  const gapH = 6 * zm;
  const totalH = headerH + labelsH + itemsH + gapH + sepH + sliderZoneH + sepH + btnZoneH + gapH * 2;

  const halfW = (pw - 30 * zm) / 2;

  return `<div class="flex gap-4 items-start flex-wrap">
    <div style="width:${pw + 4}px;position:relative;background:var(--bg);border:1px solid rgba(42,90,138,0.5);border-radius:4px;padding:2px"
      title="${t('tip.market.confirmPreview')}">
      <!-- Panel bg -->
      <div style="width:${pw}px;height:${totalH}px;position:relative;background:#${bg};border:1px solid #${border};border-radius:2px;overflow:hidden${bgImageName && _mkl.assetCache[bgImageName] ? `;background-image:url('${_mkl.assetCache[bgImageName]}');background-size:cover;background-position:center` : ''}">
        <!-- Accent top line -->
        <div style="position:absolute;top:0;left:0;right:0;height:${2 * zm}px;background:#${accent}"></div>
        <!-- Header -->
        <div style="height:${headerH}px;background:#${headerBg};border-bottom:1px solid #${border};display:flex;align-items:center;justify-content:center;position:relative">
          <span style="color:#${titleC};font-size:${8 * zm}px;font-family:'Minecraft',monospace" title="${t('market.confirm.titleTip')}">${t('common.confirm')}: Item</span>
          <div style="position:absolute;right:2px;top:50%;transform:translateY(-50%);width:${10 * zm}px;height:${10 * zm}px;background:rgba(255,255,255,0.1);display:flex;align-items:center;justify-content:center;font-size:${6 * zm}px;color:var(--muted-light)">✕</div>
        </div>
        <!-- Labels -->
        <div style="display:flex;padding:${2 * zm}px ${6 * zm}px 0">
          <div style="flex:1;padding-right:${15 * zm}px">
            <span style="color:#${labelC};font-size:${6 * zm}px;font-family:'Minecraft',monospace" title="${t('market.confirm.labelsTip')}">${t('market.confirm.youPay')}:</span>
          </div>
          <div style="flex:1;padding-left:${15 * zm}px">
            <span style="color:#${labelC};font-size:${6 * zm}px;font-family:'Minecraft',monospace">${t('market.confirm.youReceive')}:</span>
          </div>
        </div>
        <!-- Columns -->
        <div style="display:flex;padding:${2 * zm}px ${6 * zm}px;gap:0;align-items:stretch;height:${itemsH}px">
          <div style="flex:1;background:${colBgCss};border-radius:2px;display:flex;align-items:center;justify-content:center;position:relative" title="${t('market.confirm.columnBgTip')}">
            <span style="color:#${costC};font-size:${6 * zm}px;font-family:'Minecraft',monospace" title="${t('market.confirm.costColor')}">×2 Diamond</span>
          </div>
          <div style="width:${30 * zm}px;display:flex;align-items:center;justify-content:center">
            <span style="color:#${arrowC};font-size:${10 * zm}px" title="${t('market.confirm.arrowColor')}">→</span>
          </div>
          <div style="flex:1;background:${colBgCss};border-radius:2px;display:flex;align-items:center;justify-content:center">
            <span style="color:#${costC};font-size:${6 * zm}px;font-family:'Minecraft',monospace">×1 Sword</span>
          </div>
        </div>
        <!-- Separator -->
        <div style="margin:${2 * zm}px ${4 * zm}px;height:${sepH}px;background:#${sep}" title="${t('market.confirm.separatorColor')}"></div>
        <!-- Slider zone -->
        <div style="padding:${2 * zm}px ${8 * zm}px">
          <div style="display:flex;justify-content:space-between;margin-bottom:${2 * zm}px">
            <span style="color:#${labelC};font-size:${6 * zm}px;font-family:'Minecraft',monospace">${t('market.confirm.qty')}:</span>
            <span style="color:#${accent};font-size:${6 * zm}px;font-family:'Minecraft',monospace">3</span>
          </div>
          <div style="height:${6 * zm}px;background:#${sliderBg};border-radius:${2 * zm}px;position:relative;border-top:1px solid #${border};border-bottom:1px solid #${border}" title="${t('market.confirm.sliderTip')}">
            <div style="position:absolute;left:0;top:${1 * zm}px;bottom:${1 * zm}px;width:30%;background:#${sliderFill};border-radius:${2 * zm}px" title="${t('market.confirm.sliderFillTip')}"></div>
            <div style="position:absolute;left:30%;top:${-2 * zm}px;width:${6 * zm}px;height:${10 * zm}px;background:#${sliderThumb};border-radius:${1 * zm}px;transform:translateX(-50%)" title="${t('market.confirm.sliderThumb')}"></div>
          </div>
        </div>
        <!-- Separator -->
        <div style="margin:${2 * zm}px ${4 * zm}px;height:${sepH}px;background:#${sep}"></div>
        <!-- Buttons -->
        <div style="display:flex;justify-content:center;gap:${6 * zm}px;padding:${3 * zm}px">
          <div style="width:${75 * zm / 2}px;height:${14 * zm}px;background:rgba(30,60,100,0.6);border:1px solid rgba(42,90,138,0.4);border-radius:2px;display:flex;align-items:center;justify-content:center">
            <span style="color:#${btnConfirm};font-size:${6 * zm}px;font-family:'Minecraft',monospace" title="${t('market.confirm.btnConfirmTip')}">${t('common.confirm')} x3</span>
          </div>
          <div style="width:${75 * zm / 2}px;height:${14 * zm}px;background:rgba(30,60,100,0.6);border:1px solid rgba(42,90,138,0.4);border-radius:2px;display:flex;align-items:center;justify-content:center">
            <span style="color:#${btnCancel};font-size:${6 * zm}px;font-family:'Minecraft',monospace" title="${t('market.confirm.btnCancelTip')}">${t('common.cancel')}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="flex-1 grid grid-cols-2 gap-2 text-xs" style="min-width:220px">
      <div class="col-span-2">
        <label style="color:var(--muted)" title="${t('tip.market.confirmBg')}">${t('market.confirm.background')}</label>
        <div class="flex gap-2 items-center">
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="mkLayoutUploadConfirmBg()">${t('market.confirm.upload')}</button>
          <span class="text-xs mono" style="color:var(--muted)">${bgImageName ? escHtml(bgImageName) : t('common.none')}</span>
          ${bgImageName ? `<button class="text-xs hover:underline" style="color:var(--red)" onclick="mkLayoutSetConfirm('bgImage','');mkLayoutRender()" title="${t('common.delete')}">✕</button>` : ''}
        </div>
      </div>
      ${mkConfirmColorInput(t('market.confirm.width'), 'width', w, 'number', t('tip.market.confirmWidth'), 200, 500)}
      ${mkConfirmColorInput(t('market.confirm.bgColor'), 'bgColor', bg, 'color', t('tip.market.confirmBgColor'))}
      ${mkConfirmColorInput(t('market.confirm.border'), 'borderColor', border, 'color', t('tip.market.confirmBorder'))}
      ${mkConfirmColorInput(t('market.confirm.accent'), 'accentColor', accent, 'color', t('tip.market.confirmAccent'))}
      ${mkConfirmColorInput(t('market.confirm.headerBg'), 'headerBgColor', headerBg, 'color', t('market.confirm.headerBgTip'))}
      ${mkConfirmColorInput(t('market.confirm.title'), 'titleColor', titleC, 'color', t('market.confirm.titleTip'))}
      ${mkConfirmColorInput(t('market.confirm.labels'), 'labelColor', labelC, 'color', t('tip.market.confirmLabels'))}
      ${mkConfirmColorInput(t('market.confirm.cost'), 'costColor', costC, 'color', t('tip.market.confirmCost'))}
      ${mkConfirmColorInput(t('market.confirm.arrow'), 'arrowColor', arrowC, 'color', t('tip.market.confirmArrow'))}
      ${mkConfirmColorInput(t('market.confirm.sliderBg'), 'sliderBgColor', sliderBg, 'color', t('tip.market.confirmSliderBg'))}
      ${mkConfirmColorInput(t('market.confirm.sliderFill'), 'sliderFillColor', sliderFill, 'color', t('market.confirm.sliderFillTip'))}
      ${mkConfirmColorInput(t('market.confirm.sliderThumb'), 'sliderThumbColor', sliderThumb, 'color', t('tip.market.confirmSliderThumb'))}
      ${mkConfirmColorInput(t('market.confirm.separator'), 'sepColor', sep, 'color', t('tip.market.confirmSeparator'))}
      ${mkConfirmColorInput(t('market.confirm.btnConfirm'), 'btnConfirmTextColor', btnConfirm, 'color', t('market.confirm.btnConfirmTip'))}
      ${mkConfirmColorInput(t('market.confirm.btnCancel'), 'btnCancelTextColor', btnCancel, 'color', t('market.confirm.btnCancelTip'))}
      ${mkConfirmColorInput(t('market.confirm.columnBg'), 'columnBgColor', colBg, 'text', t('market.confirm.columnBgTip'))}
    </div>
  </div>`;
}

function mkConfirmColorInput(label, key, val, type, tooltip, min, max) {
  if (type === 'number') {
    return `<div><label style="color:var(--muted)" title="${escHtml(tooltip)}">${escHtml(label)}</label>
      <input type="number" class="input input-xs input-bordered w-full" value="${val}" ${min != null ? 'min="'+min+'"' : ''} ${max != null ? 'max="'+max+'"' : ''}
        onchange="mkLayoutSetConfirm('${key}',+this.value)"></div>`;
  }
  if (type === 'text') {
    return `<div><label style="color:var(--muted)" title="${escHtml(tooltip)}">${escHtml(label)}</label>
      <input class="input input-xs input-bordered w-full font-mono" value="${escHtml(val)}"
        onchange="mkLayoutSetConfirm('${key}',this.value)"></div>`;
  }
  // color
  return `<div><label style="color:var(--muted)" title="${escHtml(tooltip)}">${escHtml(label)}</label>
    <div class="flex gap-1"><input type="color" value="#${val}" class="w-6 h-6 rounded cursor-pointer" style="border:1px solid var(--bg-border)"
      onchange="mkLayoutSetConfirm('${key}',this.value.substring(1))">
    <input class="input input-xs input-bordered flex-1 font-mono" value="${escHtml(val)}" onchange="mkLayoutSetConfirm('${key}',this.value)"></div></div>`;
}

// ── Drag & drop ──
function mkLayoutElMouseDown(e, elId) {
  if (!window._mkl || e.button !== 0) return;
  const el = _mkl.layout.elements.find(x => x.id === elId);
  if (!el || el.type === 'BACKGROUND') return;
  e.stopPropagation();
  e.preventDefault();
  _mkl.selected = elId;
  // Prepare drag but don't activate yet — wait for mouse to move past deadzone
  _mkl._pendingDrag = { elId, startX: e.clientX, startY: e.clientY, elStartX: el.x, elStartY: el.y };
  _mkl.dragging = null;
  mkLayoutRender();
}

function mkLayoutResizeStart(e, handle) {
  if (!window._mkl || !_mkl.selected) return;
  e.stopPropagation();
  e.preventDefault();
  const el = _mkl.layout.elements.find(x => x.id === _mkl.selected);
  if (!el) return;
  _mkl.resizing = { elId: el.id, handle, startX: e.clientX, startY: e.clientY, elStartX: el.x, elStartY: el.y, elStartW: el.width, elStartH: el.height };
}

function mkLayoutMouseMove(e) {
  if (!window._mkl) return;
  const snap = _mkl.snap;
  const snapVal = v => Math.round(v / snap) * snap;

  const zm = _mkl.zoom || 1;

  // Activate drag only after moving past 3px deadzone
  if (_mkl._pendingDrag && !_mkl.dragging) {
    const pd = _mkl._pendingDrag;
    const dist = Math.abs(e.clientX - pd.startX) + Math.abs(e.clientY - pd.startY);
    if (dist >= 3) {
      _mkl.dragging = pd;
      _mkl._pendingDrag = null;
    }
  }

  if (_mkl.dragging) {
    const d = _mkl.dragging;
    const el = _mkl.layout.elements.find(x => x.id === d.elId);
    if (!el) return;
    let nx = d.elStartX + (e.clientX - d.startX) / zm;
    let ny = d.elStartY + (e.clientY - d.startY) / zm;
    nx = snapVal(Math.max(0, Math.min(MK_LAYOUT_W - el.width, nx)));
    ny = snapVal(Math.max(0, Math.min(MK_LAYOUT_H - el.height, ny)));
    el.x = nx; el.y = ny;
    // Update DOM directly for performance
    const dom = document.querySelector(`[data-elid="${d.elId}"]`);
    if (dom) { dom.style.left = nx + 'px'; dom.style.top = ny + 'px'; }
  }

  if (_mkl.resizing) {
    const r = _mkl.resizing;
    const el = _mkl.layout.elements.find(x => x.id === r.elId);
    if (!el) return;
    const dx = (e.clientX - r.startX) / zm;
    const dy = (e.clientY - r.startY) / zm;
    let nx = r.elStartX, ny = r.elStartY, nw = r.elStartW, nh = r.elStartH;
    if (r.handle.includes('e')) nw = Math.max(10, r.elStartW + dx);
    if (r.handle.includes('w')) { nw = Math.max(10, r.elStartW - dx); nx = r.elStartX + (r.elStartW - nw); }
    if (r.handle.includes('s')) nh = Math.max(10, r.elStartH + dy);
    if (r.handle.includes('n')) { nh = Math.max(10, r.elStartH - dy); ny = r.elStartY + (r.elStartH - nh); }
    el.x = snapVal(Math.max(0, nx)); el.y = snapVal(Math.max(0, ny));
    el.width = snapVal(nw); el.height = snapVal(nh);
    const dom = document.querySelector(`[data-elid="${r.elId}"]`);
    if (dom) { dom.style.left = el.x + 'px'; dom.style.top = el.y + 'px'; dom.style.width = el.width + 'px'; dom.style.height = el.height + 'px'; }
  }

  // Item card drag
  if (_mkl.itemCardDragging) {
    const d = _mkl.itemCardDragging;
    const icZm = _mkl.itemCardZoom ?? 4;
    const ic = _mkl.layout.itemCard ?? {};
    const dx = (e.clientX - d.startX) / icZm;
    const dy = (e.clientY - d.startY) / icZm;
    if (d.target === 'icon') {
      ic.iconOffsetX = Math.max(0, Math.round(d.startValX + dx));
      ic.iconOffsetY = Math.max(0, Math.round(d.startValY + dy));
      const dom = document.getElementById('mkl-ic-icon');
      if (dom) { dom.style.left = (8 + ic.iconOffsetX * icZm) + 'px'; dom.style.top = (8 + ic.iconOffsetY * icZm) + 'px'; }
    } else if (d.target === 'cost') {
      ic.costOffsetX = Math.round(d.startValX + dx);
      ic.costOffsetY = Math.max(0, Math.round(d.startValY + dy));
      const dom = document.getElementById('mkl-ic-cost');
      if (dom) { dom.style.marginLeft = (ic.costOffsetX * icZm) + 'px'; dom.style.top = (8 + ic.costOffsetY * icZm) + 'px'; }
    } else if (d.target === 'iconResize') {
      const newSz = Math.max(5, Math.min(40, Math.round(d.startValW + dx)));
      ic.iconSize = newSz;
      const dom = document.getElementById('mkl-ic-icon');
      if (dom) { dom.style.width = (newSz * icZm) + 'px'; dom.style.height = (newSz * icZm) + 'px'; }
    } else if (d.target === 'costIconResize') {
      const newSz = Math.max(3, Math.min(20, Math.round(d.startValW + dx)));
      ic.costIconSize = newSz;
      const dom = document.getElementById('mkl-ic-costicon');
      if (dom) { dom.style.width = (newSz * icZm) + 'px'; dom.style.height = (newSz * icZm) + 'px'; }
    }
  }
}

function mkLayoutMouseUp(e) {
  if (!window._mkl) return;
  _mkl._pendingDrag = null;
  if (_mkl.dragging || _mkl.resizing) {
    _mkl.dragging = null;
    _mkl.resizing = null;
    _mkl.pushHistory();
    mkLayoutRender();
  }
  if (_mkl.itemCardDragging) {
    _mkl.itemCardDragging = null;
    _mkl.pushHistory();
    mkLayoutRender();
  }
}

// ── Coordinate display ──
function mkLayoutShowCoords(e) {
  const canvas = document.getElementById('mkl-canvas');
  const coords = document.getElementById('mkl-coords');
  if (!canvas || !coords || !window._mkl) return;
  const rect = canvas.getBoundingClientRect();
  const zm = _mkl.zoom || 1;
  const x = Math.round((e.clientX - rect.left) / zm);
  const y = Math.round((e.clientY - rect.top) / zm);
  if (x >= 0 && x <= MK_LAYOUT_W && y >= 0 && y <= MK_LAYOUT_H) {
    coords.textContent = `${x}, ${y}`;
  } else {
    coords.textContent = '';
  }
}
function mkLayoutHideCoords() {
  const coords = document.getElementById('mkl-coords');
  if (coords) coords.textContent = '';
}

// ── Item card drag start ──
function mkLayoutIcMouseDown(e, target) {
  if (!window._mkl || e.button !== 0) return;
  e.stopPropagation(); e.preventDefault();
  const ic = _mkl.layout.itemCard ?? {};
  if (target === 'icon') {
    _mkl.itemCardDragging = { target: 'icon', startX: e.clientX, startY: e.clientY, startValX: ic.iconOffsetX ?? 13, startValY: ic.iconOffsetY ?? 7 };
  } else if (target === 'cost') {
    _mkl.itemCardDragging = { target: 'cost', startX: e.clientX, startY: e.clientY, startValX: ic.costOffsetX ?? 0, startValY: ic.costOffsetY ?? 26 };
  } else if (target === 'iconResize') {
    _mkl.itemCardDragging = { target: 'iconResize', startX: e.clientX, startY: e.clientY, startValW: ic.iconSize ?? 15 };
  } else if (target === 'costIconResize') {
    _mkl.itemCardDragging = { target: 'costIconResize', startX: e.clientX, startY: e.clientY, startValW: ic.costIconSize ?? 9 };
  }
}

// ── Save ──
async function mkLayoutSave() {
  if (!window._mkl || !window._mkd) return;
  try {
    await API.market.saveLayout(_mkl.shopId, _mkl.layout);
    _mkd.app.toast(t('common.success'), 'success');
  } catch (e) { _mkd.app.toast(e.message, 'error'); }
}

// ── Reset ──
async function mkLayoutReset() {
  if (!window._mkl || !window._mkd) return;
  if (!await confirmAction('' + t('common.deleteConfirm') + '')) return;
  try {
    // Send empty object to reset to default
    const layout = await API.market.saveLayout(_mkl.shopId, {});
    _mkl.layout = layout;
    _mkl.selected = null;
    _mkl.pushHistory();
    mkLayoutRender();
    _mkd.app.toast(t('common.success'), 'success');
  } catch (e) { _mkd.app.toast(e.message, 'error'); }
}

// ── Asset upload ──
async function mkLayoutUploadAsset() {
  if (!window._mkl || !window._mkd) return;
  const input = document.createElement('input');
  input.type = 'file'; input.accept = 'image/png';
  input.onchange = async () => {
    const file = input.files[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) { _mkd.app.toast(t('market.fileTooLarge'), 'error'); return; }
    const name = file.name.replace(/\.png$/i, '').replace(/[^a-zA-Z0-9_-]/g, '_');
    if (!name) { _mkd.app.toast(t('market.invalidFileName'), 'error'); return; }
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        await API.market.uploadAsset(_mkl.shopId, name, reader.result);
        _mkd.app.toast(t('common.success'), 'success');
        // Cache the data URL locally for immediate preview
        _mkl.assetCache[name] = reader.result;
        // Auto-apply as background if a BACKGROUND element exists
        const bgEl = _mkl.layout.elements.find(el => el.type === 'BACKGROUND');
        if (bgEl) {
          if (!bgEl.props) bgEl.props = {};
          bgEl.props.src = name;
          _mkl.pushHistory();
        }
        mkLayoutRender();
      } catch (e) { _mkd.app.toast(e.message, 'error'); }
    };
    reader.readAsDataURL(file);
  };
  input.click();
}

// ── Templates ──
async function mkLayoutTemplates() {
  if (!window._mkl || !window._mkd) return;
  if (document.getElementById('mkl-tpl-dialog')) return;
  let templates;
  try { templates = await API.market.listTemplates(); } catch (e) { _mkd.app.toast(e.message, 'error'); return; }

  const dlg = document.createElement('dialog');
  dlg.className = 'modal';
  dlg.id = 'mkl-tpl-dialog';
  dlg.innerHTML = `<div class="modal-box" style="background:var(--bg-card);border:1px solid var(--bg-border);max-width:400px">
    <button class="modal-close" onclick="this.closest('dialog').close()">✕</button>
    <h3 class="font-bold mb-4" style="color:var(--amber)">${t('market.layoutTemplates')}</h3>
    <div class="mb-4">
      <div class="text-xs font-semibold mb-2" style="color:var(--text-muted)">${t('market.loadTemplate')}</div>
      ${templates.length ? templates.map(t => `<div class="flex items-center gap-2 mb-1">
        <button class="btn-cg btn-cg-ghost btn-cg-sm flex-1 text-left" onclick="mkLayoutLoadTemplate('${escHtml(t)}');this.closest('dialog').close()">${escHtml(t)}</button>
        <button class="text-xs hover:underline" style="color:var(--red)" onclick="mkLayoutDeleteTemplate('${escHtml(t)}');this.closest('dialog').close()">×</button>
      </div>`).join('') : '<div class="text-sm" style="color:var(--muted)">' + t('common.noResults') + '</div>'}
    </div>
    <div class="section-divider my-3"></div>
    <div class="text-xs font-semibold mb-2" style="color:var(--text-muted)">${t('common.save')}</div>
    <div class="flex gap-2">
      <input id="mkl-tpl-name" class="input input-sm input-bordered flex-1" placeholder="${t('market.templateName')}">
      <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="mkLayoutSaveTemplate();this.closest('dialog').close()">${t('common.save')}</button>
    </div>
  </div><form method="dialog" class="modal-backdrop"><button>close</button></form>`;
  document.body.appendChild(dlg);
  dlg.showModal();
  dlg.addEventListener('close', () => dlg.remove());
}

async function mkLayoutLoadTemplate(name) {
  if (!window._mkl) return;
  try {
    const layout = await API.market.getTemplate(name);
    _mkl.layout = layout;
    _mkl.selected = null;
    _mkl.pushHistory();
    mkLayoutRender();
    _mkd.app.toast(t('common.success'), 'success');
  } catch (e) { _mkd.app.toast(e.message, 'error'); }
}

async function mkLayoutSaveTemplate() {
  if (!window._mkl) return;
  const name = document.getElementById('mkl-tpl-name')?.value?.trim();
  if (!name) { _mkd.app.toast(t('dims.nameRequired'), 'error'); return; }
  try {
    await API.market.saveTemplate(name, _mkl.layout);
    _mkd.app.toast(t('common.success'), 'success');
  } catch (e) { _mkd.app.toast(e.message, 'error'); }
}

async function mkLayoutDeleteTemplate(name) {
  if (!window._mkl) return;
  if (!await confirmAction(t('common.deleteConfirm'))) return;
  try {
    await API.market.deleteTemplate(name);
    _mkd.app.toast(t('common.success'), 'success');
  } catch (e) { _mkd.app.toast(e.message, 'error'); }
}

function mkColorField(key, label, val) {
  const hex = (val || '').replace(/^#/, '');
  const hex6 = hex.length === 6 ? '#' + hex : (hex === 'gold' ? '#FFD700' : '#' + hex);
  return `<div>
    <label class="field-label">${label}</label>
    <div class="flex items-center gap-1.5">
      <input type="color" id="mkd-cfg-${key}-picker" value="${hex6}" onchange="document.getElementById('mkd-cfg-${key}').value=this.value.substring(1).toUpperCase()" class="w-8 h-8 rounded cursor-pointer border" style="border-color:var(--bg-border);background:transparent;padding:1px">
      <input id="mkd-cfg-${key}" class="input input-sm flex-1 font-mono text-xs input-bordered" value="${escHtml(hex.toUpperCase() || val)}" oninput="try{document.getElementById('mkd-cfg-${key}-picker').value='#'+this.value.replace(/^#/,'')}catch(e){}">
    </div>
  </div>`;
}

async function buildMarketDetail(app, shopId) {
  if (window._mkl) {
    document.removeEventListener('keydown', mkLayoutKeyHandler);
    document.removeEventListener('mousemove', mkLayoutMouseMove);
    document.removeEventListener('mouseup', mkLayoutMouseUp);
  }
  window._mkl = null;
  let shop;
  try { shop = await API.market.getShop(shopId); }
  catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const inp = (id, val, extra) => `<input id="${id}" class="input input-sm w-full input-bordered" value="${escHtml(val || '')}" ${extra || ''}>`;
  const sel = (id, opts, cur) => `<select id="${id}" class="select select-sm w-full select-bordered">${opts.map(o => `<option value="${o.v}" ${cur === o.v ? 'selected' : ''}>${escHtml(o.l)}</option>`).join('')}</select>`;

  // State
  window._mkd = {
    shop, app, txLoaded: false, transactions: [],

    _debouncedConfig: debounce(function() { if (window._mkd) window._mkd.saveConfig(); }, 1500),

    async saveConfig() {
      const f = (id) => document.getElementById(id)?.value || '';
      try {
        await API.market.updateShop(shop.id, {
          name: f('mkd-cfg-name'), description: f('mkd-cfg-desc'),
          displayMode: f('mkd-cfg-mode'),
          showWelcomeMessage: document.getElementById('mkd-cfg-welcome-on')?.checked || false,
          welcomeMessage: f('mkd-cfg-welcome'),
          titleColor: f('mkd-cfg-titleColor'), bgColor: f('mkd-cfg-bgColor'),
          borderColor: f('mkd-cfg-borderColor'), accentColor: f('mkd-cfg-accentColor'),
          btnBuyColor: f('mkd-cfg-btnBuyColor'), btnBuyTextColor: f('mkd-cfg-btnBuyTextColor'),
          btnCloseColor: f('mkd-cfg-btnCloseColor'), btnCloseTextColor: f('mkd-cfg-btnCloseTextColor'),
          morphoCatalog: document.getElementById('mkd-cfg-morphoCatalog')?.checked || false,
        });
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async loadTransactions() {
      try {
        const data = await API.market.transactions({ shopId: shop.id, n: 100 });
        this.transactions = data.transactions || [];
        this.txLoaded = true;
        mkRenderTransactions(this.transactions);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async createSet() {
      try {
        await API.market.createSet(shop.id, { name: t('market.setName') });
        app.toast(t('market.created'), 'success');
        this.shop = await API.market.getShop(shop.id);
        mkRenderSets(this.shop);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteSet(setId) {
      if (!await confirmAction(t('market.deleteSet'))) return;
      try {
        await API.market.deleteSet(shop.id, setId);
        app.toast(t('market.deleted'), 'success');
        this.shop = await API.market.getShop(shop.id);
        mkRenderSets(this.shop);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    _debouncedSetSave: (function() { const timers = {}; return function(setId) { clearTimeout(timers[setId]); timers[setId] = setTimeout(() => { if (window._mkd) window._mkd.saveSet(setId); }, 1500); }; })(),

    async saveSet(setId) {
      const f = (id) => document.getElementById(id)?.value || '';
      try {
        await API.market.updateSet(shop.id, setId, {
          name: f('mkd-set-name-' + setId),
          rotation: {
            mode: f('mkd-set-rot-' + setId),
            scheduleStart: f('mkd-set-start-' + setId),
            scheduleEnd: f('mkd-set-end-' + setId),
            manualActive: document.getElementById('mkd-set-manual-' + setId)?.checked || false,
          }
        });
        app.toast(t('common.success'), 'success');
        this.shop = await API.market.getShop(shop.id);
        mkRenderSets(this.shop);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async createItem(setId) {
      this.openItemEditor(setId, null);
    },

    async toggleItem(setId, itemId) {
      try {
        await API.market.toggleItem(shop.id, setId, itemId);
        this.shop = await API.market.getShop(shop.id);
        mkRenderSets(this.shop);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteItem(setId, itemId) {
      if (!await confirmAction(t('market.deleteItem'))) return;
      try {
        await API.market.deleteItem(shop.id, setId, itemId);
        app.toast(t('items.deleted'), 'success');
        this.shop = await API.market.getShop(shop.id);
        mkRenderSets(this.shop);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // ── Item editor modal state ──
    _editSetId: null,
    _editItemId: null,
    _editCosts: [],
    _editOutputs: [],
    _editCommands: [],
    _editDisplayItem: '',
    _editDisplayName: '',
    _editEnabled: true,
    _editStock: { mode: 'UNLIMITED', globalStock: 0, perPlayerLimit: 0, resetType: 'NONE', resetIntervalSeconds: 0 },

    _debouncedItemSave: debounce(function() { if (window._mkd && window._mkd._editItemId) window._mkd.saveItemFromModal(true); }, 1500),

    openItemEditor(setId, item) {
      this._editSetId = setId;
      this._editItemId = item ? item.id : null;
      this._editCosts = item && item.costs ? item.costs.map(c => ({...c})) : [];
      this._editOutputs = item && item.outputs ? item.outputs.map(o => ({...o})) : [];
      this._editCommands = item && item.commands ? item.commands.map(c => ({...c})) : [];
      this._editDisplayItem = item ? (item.displayItem || '') : '';
      this._editDisplayName = item ? (item.displayName || '') : '';
      this._editEnabled = item ? !!item.enabled : true;
      this._editStock = item && item.stock ? {...item.stock} : { mode: 'UNLIMITED', globalStock: 0, perPlayerLimit: 0, resetType: 'NONE', resetIntervalSeconds: 0 };
      mkRenderItemEditor();
      const dlg = document.getElementById('mkd-item-editor-dialog');
      if (dlg) {
        dlg.showModal();
        // Auto-save for edit mode
        if (this._editItemId) {
          const content = document.getElementById('mkd-item-editor-content');
          if (content && !content._autoSaveBound) {
            content._autoSaveBound = true;
            content.addEventListener('input', () => { if (window._mkd && window._mkd._editItemId) window._mkd._debouncedItemSave(); });
            content.addEventListener('change', () => { if (window._mkd && window._mkd._editItemId) window._mkd._debouncedItemSave(); });
          }
        }
      }
    },

    closeItemEditor() {
      const dlg = document.getElementById('mkd-item-editor-dialog');
      if (dlg) dlg.close();
    },

    addCostEntry() {
      this._editCosts.push({ itemId: 'minecraft:diamond', count: 1 });
      mkRenderItemEditor();
    },

    removeCostEntry(idx) {
      this._editCosts.splice(idx, 1);
      mkRenderItemEditor();
    },

    addOutputEntry() {
      this._editOutputs.push({ itemId: 'minecraft:stone', count: 1 });
      mkRenderItemEditor();
    },

    removeOutputEntry(idx) {
      this._editOutputs.splice(idx, 1);
      mkRenderItemEditor();
    },

    addCommandEntry() {
      this._editCommands.push({ command: '', count: 1 });
      mkRenderItemEditor();
    },

    removeCommandEntry(idx) {
      this._editCommands.splice(idx, 1);
      mkRenderItemEditor();
    },

    syncEditorFields() {
      this._editDisplayItem = document.getElementById('mke-displayItem')?.value || '';
      this._editDisplayName = document.getElementById('mke-displayName')?.value || '';
      this._editEnabled = document.getElementById('mke-enabled')?.checked || false;
      const mode = document.getElementById('mke-stockMode')?.value || 'UNLIMITED';
      this._editStock.mode = mode;
      this._editStock.globalStock = parseInt(document.getElementById('mke-globalStock')?.value) || 0;
      this._editStock.perPlayerLimit = parseInt(document.getElementById('mke-perPlayerLimit')?.value) || 0;
      this._editStock.resetType = document.getElementById('mke-resetType')?.value || 'NONE';
      this._editStock.resetIntervalSeconds = parseInt(document.getElementById('mke-resetInterval')?.value) || 0;
      // Sync cost/output entries from DOM
      document.querySelectorAll('.mke-cost-row').forEach((row, i) => {
        if (this._editCosts[i]) {
          this._editCosts[i].itemId = row.querySelector('.mk-entry-item-input')?.value || '';
          this._editCosts[i].count = parseInt(row.querySelector('.mk-entry-count-input')?.value) || 1;
        }
      });
      document.querySelectorAll('.mke-output-row').forEach((row, i) => {
        if (this._editOutputs[i]) {
          this._editOutputs[i].itemId = row.querySelector('.mk-entry-item-input')?.value || '';
          this._editOutputs[i].count = parseInt(row.querySelector('.mk-entry-count-input')?.value) || 1;
        }
      });
      document.querySelectorAll('.mke-cmd-row').forEach((row, i) => {
        if (this._editCommands[i]) {
          this._editCommands[i].command = row.querySelector('.mk-cmd-input')?.value || '';
          this._editCommands[i].count = parseInt(row.querySelector('.mk-cmd-count-input')?.value) || 1;
        }
      });
    },

    async saveItemFromModal(keepOpen) {
      this.syncEditorFields();
      // Auto-set displayItem from first output if left empty
      let displayItem = this._editDisplayItem;
      if (!displayItem && this._editOutputs.length > 0 && this._editOutputs[0].itemId) {
        displayItem = this._editOutputs[0].itemId;
      }
      const data = {
        displayItem: displayItem,
        displayName: this._editDisplayName,
        enabled: this._editEnabled,
        costs: this._editCosts.filter(c => c.itemId),
        outputs: this._editOutputs.filter(o => o.itemId),
        commands: this._editCommands.filter(c => c.command),
        stock: this._editStock,
      };
      try {
        if (this._editItemId) {
          await API.market.updateItem(shop.id, this._editSetId, this._editItemId, data);
          app.toast(t('common.success'), 'success');
        } else {
          await API.market.createItem(shop.id, this._editSetId, data);
          app.toast(t('items.created'), 'success');
        }
        if (!keepOpen) {
          this.closeItemEditor();
          this.shop = await API.market.getShop(shop.id);
          mkRenderSets(this.shop);
        } else {
          // Auto-save: refresh data silently without re-rendering (modal stays open)
          this.shop = await API.market.getShop(shop.id);
        }
      } catch (e) { app.toast(e.message, 'error'); }
    },

    filterItems(query) {
      const q = (query || '').toLowerCase().trim();
      document.querySelectorAll('.mk-item-row').forEach(row => {
        if (!q) { row.style.display = ''; return; }
        const text = row.getAttribute('data-search') || '';
        row.style.display = text.includes(q) ? '' : 'none';
      });
    },

    /* ── Item drag & drop ── */
    _dragItemId: null,
    _dragItemSetId: null,
    onItemDragStart(e, setId) {
      e.stopPropagation();
      const row = e.target.closest('.mk-item-row');
      if (!row) { e.preventDefault(); return; }
      this._dragItemId = row.dataset.itemId;
      this._dragItemSetId = setId;
      row.style.opacity = '0.4';
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', 'item');
    },
    onItemDragOver(e) {
      e.preventDefault();
      e.stopPropagation();
      e.dataTransfer.dropEffect = 'move';
      const row = e.target.closest('.mk-item-row');
      document.querySelectorAll('.mk-item-row').forEach(r => r.classList.remove('item-drag-over'));
      if (row && row.dataset.itemId !== this._dragItemId) row.classList.add('item-drag-over');
    },
    onItemDragEnd(e) {
      e.stopPropagation();
      document.querySelectorAll('.mk-item-row').forEach(r => { r.style.opacity = ''; r.classList.remove('item-drag-over'); });
      this._dragItemId = null;
      this._dragItemSetId = null;
    },
    async onItemDrop(e) {
      e.preventDefault();
      e.stopPropagation();
      const target = e.target.closest('.mk-item-row');
      if (!target || !this._dragItemId || !this._dragItemSetId) return;
      const targetId = target.dataset.itemId;
      if (this._dragItemId === targetId) return;
      const s = this.shop;
      if (!s) return;
      const set = (s.sets || []).find(st => st.id === this._dragItemSetId);
      if (!set) return;
      const items = [...set.items];
      const fromIdx = items.findIndex(it => it.id === this._dragItemId);
      const toIdx = items.findIndex(it => it.id === targetId);
      if (fromIdx < 0 || toIdx < 0) return;
      const [moved] = items.splice(fromIdx, 1);
      items.splice(toIdx, 0, moved);
      for (let i = 0; i < items.length; i++) {
        try { await API.market.updateItem(s.id, set.id, items[i].id, { sortOrder: i }); } catch (e2) { /* ignore */ }
      }
      this.shop = await API.market.getShop(s.id);
      mkRenderSets(this.shop);
      app.toast(t('common.success'), 'success');
    },

    _dragSetId: null,
    onDragStart(e) {
      if (e.target.closest('.mk-item-row')) return; // item drag handled separately
      const block = e.target.closest('.mkd-set-block');
      if (!block) return;
      this._dragSetId = block.dataset.setId;
      block.style.opacity = '0.5';
      e.dataTransfer.effectAllowed = 'move';
    },
    onDragOver(e) {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      const block = e.target.closest('.mkd-set-block');
      document.querySelectorAll('.mkd-set-block').forEach(b => b.classList.remove('drag-over'));
      if (block) block.classList.add('drag-over');
    },
    onDragEnd(e) {
      document.querySelectorAll('.mkd-set-block').forEach(b => { b.style.opacity = ''; b.classList.remove('drag-over'); });
      this._dragSetId = null;
    },
    async onDrop(e) {
      e.preventDefault();
      if (this._dragItemId) return; // item drag in progress, don't handle as set drop
      const target = e.target.closest('.mkd-set-block');
      if (!target || !this._dragSetId) return;
      const targetId = target.dataset.setId;
      if (this._dragSetId === targetId) return;
      const s = this.shop;
      if (!s || !s.sets) return;
      const sets = [...s.sets];
      const fromIdx = sets.findIndex(st => st.id === this._dragSetId);
      const toIdx = sets.findIndex(st => st.id === targetId);
      if (fromIdx < 0 || toIdx < 0) return;
      const [moved] = sets.splice(fromIdx, 1);
      sets.splice(toIdx, 0, moved);
      for (let i = 0; i < sets.length; i++) {
        try { await API.market.updateSet(s.id, sets[i].id, { sortOrder: i }); } catch (e2) { /* ignore */ }
      }
      this.shop = await API.market.getShop(s.id);
      mkRenderSets(this.shop);
      app.toast(t('common.success'), 'success');
    },

    async deleteShop() {
      if (!await confirmAction(t('market.deleteShopFull'))) return;
      try {
        await API.market.deleteShop(shop.id);
        app.toast(t('market.deleted'), 'success');
        app.navigate('market');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    // ── CSV Export / Import ──

    exportSetCsv(setId) {
      const set = (this.shop.sets || []).find(s => s.id === setId);
      if (!set) return;
      const items = set.items || [];
      if (items.length === 0) { app.toast(t('market.csv.noItems'), 'error'); return; }

      const CSV_COLS = ['displayItem','displayName','enabled','sortOrder','outputs','costs','commands','stockMode','globalStock','perPlayerLimit','resetType','resetIntervalSeconds'];

      function csvEscape(val) {
        const s = String(val == null ? '' : val);
        if (s.includes(',') || s.includes('"') || s.includes('\n') || s.includes('\r')) {
          return '"' + s.replace(/"/g, '""') + '"';
        }
        return s;
      }

      const rows = [CSV_COLS.join(',')];
      for (const it of items) {
        const stock = it.stock || {};
        const row = [
          it.displayItem || '',
          it.displayName || '',
          it.enabled ? 'true' : 'false',
          it.sortOrder || 0,
          JSON.stringify((it.outputs || []).map(o => ({ itemId: o.itemId, count: o.count, ...(o.components ? { components: o.components } : {}) }))),
          JSON.stringify((it.costs || []).map(c => ({ itemId: c.itemId, count: c.count, ...(c.components ? { components: c.components } : {}) }))),
          JSON.stringify((it.commands || []).map(c => ({ command: c.command, count: c.count }))),
          stock.mode || 'UNLIMITED',
          stock.globalStock || 0,
          stock.perPlayerLimit || 0,
          stock.resetType || 'DAILY',
          stock.resetIntervalSeconds || 0,
        ];
        rows.push(row.map(csvEscape).join(','));
      }

      const blob = new Blob(['\uFEFF' + rows.join('\r\n')], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${(set.name || set.id).replace(/[^a-zA-Z0-9_\- ]/g, '')}_items.csv`;
      a.click();
      URL.revokeObjectURL(url);
      app.toast(t('market.csv.exported', { count: items.length }), 'success');
    },

    _csvImportSetId: null,
    _csvImportRows: [],

    importSetCsvPick(setId) {
      this._csvImportSetId = setId;
      const fileInput = document.getElementById('mkd-csv-file-input');
      if (fileInput) { fileInput.value = ''; fileInput.click(); }
    },

    handleCsvFile(input) {
      const file = input.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = (e) => {
        try {
          const parsed = mkParseCsv(e.target.result);
          if (parsed.error) { app.toast(parsed.error, 'error'); return; }
          this._csvImportRows = parsed.rows;
          const set = (this.shop.sets || []).find(s => s.id === this._csvImportSetId);
          const setName = set ? (set.name || set.id) : this._csvImportSetId;
          const existingCount = set ? (set.items || []).length : 0;

          const preview = document.getElementById('mkd-csv-import-preview');
          if (preview) {
            preview.innerHTML = `
              <div class="p-3 rounded mb-3" style="background:var(--bg-elevated);border:1px solid var(--bg-border)">
                <div class="text-sm mb-2" style="color:var(--text-primary)">
                  <strong>${t('market.csv.file')}:</strong> ${escHtml(file.name)}
                </div>
                <div class="text-sm mb-2" style="color:var(--text-primary)">
                  <strong>${t('market.csv.setTarget')}:</strong> ${escHtml(setName)}
                </div>
                <div class="text-sm mb-2" style="color:var(--green)">
                  <strong>${t('market.csv.itemsToImport')}:</strong> ${parsed.rows.length}
                </div>
                ${existingCount > 0 ? `<div class="text-sm" style="color:var(--red)">
                  <strong>${t('market.csv.itemsToDelete')}:</strong> ${existingCount}
                </div>` : ''}
              </div>
              <div class="p-3 rounded" style="background:rgba(239,68,68,0.1);border:1px solid var(--red)">
                <p class="text-sm" style="color:var(--red)">${t('market.csv.importWarning')}</p>
              </div>`;
          }
          document.getElementById('mkd-csv-import-dialog')?.showModal();
        } catch (err) {
          app.toast(t('market.csv.parseError') + ': ' + err.message, 'error');
        }
      };
      reader.readAsText(file, 'UTF-8');
    },

    async confirmCsvImport() {
      document.getElementById('mkd-csv-import-dialog')?.close();
      const setId = this._csvImportSetId;
      const rows = this._csvImportRows;
      if (!setId || !rows.length) return;

      const set = (this.shop.sets || []).find(s => s.id === setId);
      if (!set) { app.toast(t('common.error'), 'error'); return; }

      // Delete all existing items
      const existing = set.items || [];
      for (const it of existing) {
        try { await API.market.deleteItem(shop.id, setId, it.id); } catch (e) { /* continue */ }
      }

      // Create items from CSV rows
      let created = 0;
      for (let i = 0; i < rows.length; i++) {
        const r = rows[i];
        try {
          await API.market.createItem(shop.id, setId, {
            displayItem: r.displayItem || 'minecraft:paper',
            displayName: r.displayName || '',
            enabled: r.enabled,
            sortOrder: r.sortOrder,
            outputs: r.outputs,
            costs: r.costs,
            commands: r.commands,
            stock: {
              mode: r.stockMode,
              globalStock: r.globalStock,
              perPlayerLimit: r.perPlayerLimit,
              resetType: r.resetType,
              resetIntervalSeconds: r.resetIntervalSeconds,
            },
          });
          created++;
        } catch (e) {
          app.toast(t('market.csv.rowError', { row: i + 2, error: e.message }), 'error');
        }
      }

      this.shop = await API.market.getShop(shop.id);
      mkRenderSets(this.shop);
      app.toast(t('market.csv.imported', { count: created }), 'success');
      this._csvImportRows = [];
      this._csvImportSetId = null;
    },
  };

  function mkRenderSets(sh) {
    const el = document.getElementById('mkd-sets-body');
    if (!el) return;
    el.innerHTML = mkBuildSetsHtml(sh);
  }

  // ── CSV Parser (RFC 4180 compliant) ──

  const MK_CSV_COLS = ['displayItem','displayName','enabled','sortOrder','outputs','costs','commands','stockMode','globalStock','perPlayerLimit','resetType','resetIntervalSeconds'];
  const MK_STOCK_MODES = ['UNLIMITED','GLOBAL','PER_PLAYER','PER_PLAYER_RESET'];
  const MK_RESET_TYPES = ['DAILY','WEEKLY','MONTHLY','CUSTOM'];

  function mkParseCsv(text) {
    const lines = mkCsvSplitLines(text.replace(/^\uFEFF/, ''));
    if (lines.length < 2) return { error: t('market.csv.errorEmpty') };

    // Validate header
    const header = mkCsvParseLine(lines[0]);
    for (let i = 0; i < MK_CSV_COLS.length; i++) {
      if ((header[i] || '').trim() !== MK_CSV_COLS[i]) {
        return { error: t('market.csv.errorHeader', { expected: MK_CSV_COLS[i], got: (header[i] || '').trim(), col: i + 1 }) };
      }
    }

    const rows = [];
    for (let i = 1; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line) continue;
      const cells = mkCsvParseLine(line);
      if (cells.length < MK_CSV_COLS.length) {
        return { error: t('market.csv.errorCols', { row: i + 1, expected: MK_CSV_COLS.length, got: cells.length }) };
      }

      // Parse and validate each field
      const displayItem = cells[0].trim();
      const displayName = cells[1].trim();
      const enabled = cells[2].trim().toLowerCase() === 'true';
      const sortOrder = parseInt(cells[3]) || 0;

      let outputs, costs, commands;
      try { outputs = JSON.parse(cells[4] || '[]'); } catch { return { error: t('market.csv.errorJson', { row: i + 1, col: 'outputs' }) }; }
      try { costs = JSON.parse(cells[5] || '[]'); } catch { return { error: t('market.csv.errorJson', { row: i + 1, col: 'costs' }) }; }
      try { commands = JSON.parse(cells[6] || '[]'); } catch { return { error: t('market.csv.errorJson', { row: i + 1, col: 'commands' }) }; }

      if (!Array.isArray(outputs)) return { error: t('market.csv.errorArray', { row: i + 1, col: 'outputs' }) };
      if (!Array.isArray(costs)) return { error: t('market.csv.errorArray', { row: i + 1, col: 'costs' }) };
      if (!Array.isArray(commands)) return { error: t('market.csv.errorArray', { row: i + 1, col: 'commands' }) };

      // Validate entries
      for (const o of outputs) {
        if (!o.itemId || typeof o.itemId !== 'string') return { error: t('market.csv.errorEntry', { row: i + 1, col: 'outputs' }) };
        o.count = parseInt(o.count) || 1;
      }
      for (const c of costs) {
        if (!c.itemId || typeof c.itemId !== 'string') return { error: t('market.csv.errorEntry', { row: i + 1, col: 'costs' }) };
        c.count = parseInt(c.count) || 1;
      }
      for (const c of commands) {
        if (!c.command || typeof c.command !== 'string') return { error: t('market.csv.errorEntry', { row: i + 1, col: 'commands' }) };
        c.count = parseInt(c.count) || 1;
      }

      const stockMode = cells[7].trim();
      if (!MK_STOCK_MODES.includes(stockMode)) return { error: t('market.csv.errorEnum', { row: i + 1, col: 'stockMode', value: stockMode, valid: MK_STOCK_MODES.join(', ') }) };
      const resetType = cells[10].trim();
      if (!MK_RESET_TYPES.includes(resetType)) return { error: t('market.csv.errorEnum', { row: i + 1, col: 'resetType', value: resetType, valid: MK_RESET_TYPES.join(', ') }) };

      rows.push({
        displayItem,
        displayName,
        enabled,
        sortOrder,
        outputs,
        costs,
        commands,
        stockMode,
        globalStock: parseInt(cells[8]) || 0,
        perPlayerLimit: parseInt(cells[9]) || 0,
        resetType,
        resetIntervalSeconds: parseInt(cells[11]) || 0,
      });
    }

    if (rows.length === 0) return { error: t('market.csv.errorEmpty') };
    return { rows };
  }

  function mkCsvSplitLines(text) {
    const lines = [];
    let current = '';
    let inQuotes = false;
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (ch === '"') {
        inQuotes = !inQuotes;
        current += ch;
      } else if ((ch === '\n' || ch === '\r') && !inQuotes) {
        lines.push(current);
        current = '';
        if (ch === '\r' && text[i + 1] === '\n') i++;
      } else {
        current += ch;
      }
    }
    if (current) lines.push(current);
    return lines;
  }

  function mkCsvParseLine(line) {
    const cells = [];
    let current = '';
    let inQuotes = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (inQuotes) {
        if (ch === '"') {
          if (line[i + 1] === '"') { current += '"'; i++; }
          else inQuotes = false;
        } else {
          current += ch;
        }
      } else {
        if (ch === '"') { inQuotes = true; }
        else if (ch === ',') { cells.push(current); current = ''; }
        else { current += ch; }
      }
    }
    cells.push(current);
    return cells;
  }

  function mkTradeEntries(entries, prefix) {
    if (!entries || entries.length === 0) return `<span style="color:var(--muted);font-size:var(--type-xs)">\u2014</span>`;
    return entries.map((e, i) => {
      const isFromage = e.itemId === 'cheesestore:fromage';
      const clean = isFromage ? 'fromage' : (e.itemId || '').replace('minecraft:', '').replace(/[^a-z0-9_]/g, '');
      const label = isFromage ? t('market.fromage') : (clean || '?');
      const url = mkItemIconUrl(e.itemId);
      return (i > 0 ? '<span class="mk-trade-plus">+</span>' : '') +
        `<span class="mk-trade-entry"><img src="${url}" width="18" height="18" alt="${escHtml(label)}" onerror="mkItemIconFallback(this,'${escHtml(clean)}')">${escHtml(label)} x${e.count || 1}</span>`;
    }).join('');
  }

  function mkStockLabel(stock) {
    if (!stock || stock.mode === 'UNLIMITED') return t('common.none');
    if (stock.mode === 'GLOBAL') return `Global: ${stock.globalStock || 0}`;
    if (stock.mode === 'PER_PLAYER') return `${t('market.stockPerPlayer')}: ${stock.perPlayerLimit || 0}`;
    if (stock.mode === 'PER_PLAYER_RESET') return `${t('market.stockPerPlayerReset')}: ${stock.perPlayerLimit || 0}`;
    return stock.mode;
  }

  function mkBuildSetsHtml(sh) {
    const sets = sh.sets || [];
    const datalistHtml = `<datalist id="mk-items-datalist">${MK_COMMON_ITEMS.map(i => `<option value="${i}">`).join('')}</datalist>`;
    let html = datalistHtml + `<div class="flex justify-between items-center mb-3 gap-2">
      <span class="text-sm" style="color:var(--text-secondary)">${sets.length} ${t('market.setsCount')}</span>
      <div class="flex gap-2 items-center flex-1 justify-end">
        <input id="mkd-item-search" class="input input-sm input-bordered w-48" placeholder="${t('common.search')}" oninput="_mkd.filterItems(this.value)">
        <button class="btn-cg btn-cg-primary" onclick="_mkd.createSet()">${t('common.create')}</button>
      </div>
    </div>`;

    // Item editor modal (single instance, reused)
    html += `<dialog id="mkd-item-editor-dialog" class="modal">
      <div class="modal-box" style="max-width:540px">
        <div id="mkd-item-editor-content"></div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>`;

    // CSV import confirmation modal
    html += `<dialog id="mkd-csv-import-dialog" class="modal">
      <div class="modal-box" style="max-width:480px">
        <div class="flex items-center justify-between mb-4">
          <h3 style="font-size:var(--type-lg);font-weight:600;color:var(--text-heading)">${t('market.csv.import')}</h3>
          <button class="modal-close" onclick="document.getElementById('mkd-csv-import-dialog').close()">\u00D7</button>
        </div>
        <div id="mkd-csv-import-preview"></div>
        <div class="flex justify-end gap-2 mt-4">
          <button class="btn-cg btn-cg-ghost" onclick="document.getElementById('mkd-csv-import-dialog').close()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-danger" onclick="_mkd.confirmCsvImport()">${t('market.csv.confirmImport')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>`;

    // Hidden file input for CSV import
    html += `<input type="file" id="mkd-csv-file-input" accept=".csv" style="display:none" onchange="_mkd.handleCsvFile(this)">`;

    for (let si = 0; si < sets.length; si++) {
      const set = sets[si];
      const items = set.items || [];
      const rotMode = set.rotation?.mode || 'ALWAYS';
      html += `
      <div class="border rounded mb-3 mkd-set-block mk-set-block" draggable="true" data-set-id="${set.id}" style="border-color:var(--bg-border)"
        ondragstart="_mkd.onDragStart(event)" ondragover="_mkd.onDragOver(event)" ondrop="_mkd.onDrop(event)" ondragend="_mkd.onDragEnd(event)">
        <div class="flex items-center gap-2 p-2 cursor-pointer" style="background:var(--bg-elevated)" onclick="this.nextElementSibling.classList.toggle('hidden')">
          <span style="color:var(--muted);cursor:grab;font-size:10px" title="${t('market.dragToReorder')}">\u2807</span>
          <span class="status-dot ${set.active ? 'status-online' : 'status-offline'}"></span>
          <span class="text-sm font-medium flex-1" style="color:var(--text-primary)">${escHtml(set.name || set.id)}</span>
          <span class="text-sm" style="color:var(--text-muted)">${items.length} ${t('market.itemsCount')}</span>
          <span class="text-sm font-mono" style="color:var(--text-muted)">${escHtml(set.rotationLabel || rotMode)}</span>
        </div>
        <div class="hidden p-3" style="border-top:1px solid var(--bg-border)">
          <div class="grid grid-cols-3 gap-2 mb-3">
            <div><label class="field-label">${t('common.name')}</label><input id="mkd-set-name-${set.id}" class="input input-sm input-bordered w-full" value="${escHtml(set.name || '')}" oninput="_mkd._debouncedSetSave('${set.id}')"></div>
            <div><label class="field-label">${t('market.rotation')}</label>
              <select id="mkd-set-rot-${set.id}" class="select select-sm select-bordered w-full"
                onchange="_mkd._debouncedSetSave('${set.id}')">
                <option value="ALWAYS" ${rotMode==='ALWAYS'?'selected':''}>${t('market.rotAlways')}</option>
                <option value="SCHEDULE" ${rotMode==='SCHEDULE'?'selected':''}>${t('market.rotSchedule')}</option>
                <option value="MANUAL" ${rotMode==='MANUAL'?'selected':''}>${t('market.rotManual')}</option>
              </select>
            </div>
            <div class="flex items-end">
              <button class="btn-cg btn-cg-danger" onclick="_mkd.deleteSet('${set.id}')">${t('common.delete')}</button>
            </div>
          </div>
          <div class="flex justify-between items-center mb-2">
            <span class="text-sm" style="color:var(--text-muted)">${t('market.items')}</span>
            <div class="flex gap-1">
              <button class="btn-cg btn-cg-ghost" style="height:1.75rem;font-size:var(--type-xs)" onclick="_mkd.exportSetCsv('${set.id}')" title="${t('market.csv.export')}">CSV ↓</button>
              <button class="btn-cg btn-cg-ghost" style="height:1.75rem;font-size:var(--type-xs)" onclick="_mkd.importSetCsvPick('${set.id}')" title="${t('market.csv.import')}">CSV ↑</button>
              <button class="btn-cg btn-cg-primary" style="height:1.75rem;font-size:var(--type-xs)" onclick="_mkd.createItem('${set.id}')">${t('common.add')}</button>
            </div>
          </div>
          ${items.length === 0 ? '<div class="text-sm text-center py-4" style="color:var(--text-muted)">' + t('common.noResults') + '</div>' : `
          <table class="cg-table mk-item-table" style="width:100%">
            <thead><tr>
              <th style="width:28px"></th>
              <th style="width:36px"></th>
              <th>${t('common.name')}</th>
              <th>${t('market.pays')}</th>
              <th></th>
              <th>${t('market.receives')}</th>
              <th>${t('market.stock')}</th>
              <th style="width:32px"></th>
              <th style="width:90px"></th>
            </tr></thead>
            <tbody>
            ${items.map(it => {
              const costs = it.costs || [];
              const outputs = it.outputs || [];
              const itemJson = escHtml(JSON.stringify(it));
              return `
              <tr class="mk-item-row" draggable="true" data-item-id="${it.id}" data-search="${escHtml((it.displayName || '').toLowerCase() + ' ' + (it.displayItem || '').toLowerCase() + ' ' + (it.costsSummary || '').toLowerCase())}"
                ondragstart="_mkd.onItemDragStart(event,'${set.id}')" ondragover="_mkd.onItemDragOver(event)" ondrop="_mkd.onItemDrop(event)" ondragend="_mkd.onItemDragEnd(event)">
                <td style="color:var(--muted);cursor:grab;font-size:10px;text-align:center" title="${t('market.dragToReorder')}">\u2807</td>
                <td><div class="mk-item-card-icon">${mkItemImg(it.displayItem, 28)}</div></td>
                <td>
                  <div class="mk-item-card-name">${escHtml(it.effectiveName || it.displayName || it.displayItem || '—')}</div>
                  <div class="mk-item-card-id">${escHtml(it.displayItem || '—')}</div>
                </td>
                <td><div class="mk-trade-entries">${mkTradeEntries(costs)}</div></td>
                <td class="mk-trade-arrow" style="text-align:center">\u2192</td>
                <td><div class="mk-trade-entries">${mkTradeEntries(outputs)}</div></td>
                <td><span class="mk-stock-badge">${escHtml(mkStockLabel(it.stock))}</span></td>
                <td style="text-align:center"><input type="checkbox" class="toggle toggle-xs" ${it.enabled ? 'checked' : ''} onchange="_mkd.toggleItem('${set.id}','${it.id}')" title="${it.enabled ? t('common.active') : t('common.inactive')}"></td>
                <td>
                  <div class="flex gap-1">
                    <button class="btn-cg btn-cg-ghost" style="height:1.625rem;font-size:var(--type-xs)" onclick="_mkd.openItemEditor('${set.id}', ${itemJson})">${t('common.edit')}</button>
                    <button class="btn-cg btn-cg-danger" style="height:1.625rem;font-size:var(--type-xs)" onclick="_mkd.deleteItem('${set.id}','${it.id}')">${t('common.delete')}</button>
                  </div>
                </td>
              </tr>`;
            }).join('')}
            </tbody>
          </table>`}
        </div>
      </div>`;
    }
    return html;
  }

  function mkEntryIconUpdate(inputEl) {
    const img = inputEl.closest('.mk-entry-row')?.querySelector('img');
    if (img) {
      const url = mkItemIconUrl(inputEl.value);
      if (url) { img.style.display = ''; img.src = url; }
      else img.style.display = 'none';
    }
  }

  function mkRenderItemEditor() {
    const d = window._mkd;
    const el = document.getElementById('mkd-item-editor-content');
    if (!el) return;
    const isNew = !d._editItemId;
    const title = isNew ? t('market.newItem') : t('market.editItem');

    function entryRow(entry, idx, type) {
      const url = mkItemIconUrl(entry.itemId);
      const clean = (entry.itemId || '').replace('minecraft:', '').replace(/[^a-z0-9_]/g, '');
      const imgHtml = url ? `<img src="${url}" width="24" height="24" alt="${escHtml(clean)}" style="image-rendering:pixelated" onerror="mkItemIconFallback(this,'${escHtml(clean)}')">` : '<span style="width:24px;height:24px;display:inline-block"></span>';
      return `<div class="mk-entry-row mke-${type}-row">
        ${imgHtml}
        <input class="mk-entry-item-input" list="mk-items-datalist" value="${escHtml(entry.itemId || '')}" placeholder="minecraft:item_id" onchange="mkEntryIconUpdate(this)">
        <span style="color:var(--muted);font-size:var(--type-xs)">x</span>
        <input class="mk-entry-count-input" type="number" min="1" value="${entry.count || 1}">
        <button class="mk-entry-remove" onclick="_mkd.syncEditorFields();_mkd.remove${type === 'cost' ? 'Cost' : 'Output'}Entry(${idx})">\u00D7</button>
      </div>`;
    }

    const costsHtml = d._editCosts.map((c, i) => entryRow(c, i, 'cost')).join('');
    const outputsHtml = d._editOutputs.map((o, i) => entryRow(o, i, 'output')).join('');
    const commandsHtml = d._editCommands.map((c, i) => `<div class="mk-entry-row mke-cmd-row">
        <span style="color:var(--amber);font-size:var(--type-sm);width:24px;text-align:center;flex-shrink:0">\u26A1</span>
        <input class="mk-cmd-input" style="flex:1;min-width:0;font-size:var(--type-xs);padding:0.2rem 0.4rem;border-radius:0.25rem;border:1px solid var(--bg-border);background:var(--bg-elevated);color:var(--text-primary)" value="${escHtml(c.command || '')}" placeholder="give {player} diamond 1">
        <span style="color:var(--muted);font-size:var(--type-xs)">\u00D7</span>
        <input class="mk-cmd-count-input" type="number" min="1" value="${c.count || 1}" style="width:3rem;font-size:var(--type-xs);padding:0.2rem 0.3rem;border-radius:0.25rem;border:1px solid var(--bg-border);background:var(--bg-elevated);color:var(--text-primary);text-align:center">
        <button class="mk-entry-remove" onclick="_mkd.syncEditorFields();_mkd.removeCommandEntry(${i})">\u00D7</button>
      </div>`).join('');

    const displayUrl = mkItemIconUrl(d._editDisplayItem);

    const sm = d._editStock;
    const showGlobal = sm.mode === 'GLOBAL';
    const showPerPlayer = sm.mode === 'PER_PLAYER' || sm.mode === 'PER_PLAYER_RESET';
    const showReset = sm.mode === 'PER_PLAYER_RESET';
    const showCustomInterval = showReset && sm.resetType === 'CUSTOM';

    el.innerHTML = `
      <div class="flex items-center justify-between mb-4">
        <h3 style="font-size:var(--type-lg);font-weight:600;color:var(--text-heading)">${title}</h3>
        <button class="mk-entry-remove" style="font-size:1.25rem" onclick="_mkd.closeItemEditor()">\u00D7</button>
      </div>

      <div class="mk-section-label">${t('market.playerReceives')}</div>
      ${outputsHtml}
      <button class="mk-add-entry" onclick="_mkd.syncEditorFields();_mkd.addOutputEntry()">${t('common.add')}</button>

      <div class="mk-section-label">${t('market.playerPays')}</div>
      ${costsHtml}
      <button class="mk-add-entry" onclick="_mkd.syncEditorFields();_mkd.addCostEntry()">${t('common.add')}</button>

      <div class="mk-section-label">\u26A1 ${t('market.serverCommands')}</div>
      ${commandsHtml}
      <button class="mk-add-entry" onclick="_mkd.syncEditorFields();_mkd.addCommandEntry()">${t('common.add')}</button>

      <div class="mk-section-label">${t('market.shopDisplay')}</div>
      <div class="grid grid-cols-2 gap-3">
        <div class="field-group">
          <label class="field-label">${t('market.shopIcon')}</label>
          <div class="flex items-center gap-2">
            ${displayUrl ? `<img id="mke-display-icon" src="${displayUrl}" width="24" height="24" style="image-rendering:pixelated;flex-shrink:0" onerror="mkItemIconFallback(this,'${escHtml((d._editDisplayItem||'').replace('minecraft:',''))}')">` : '<span id="mke-display-icon" style="width:24px;height:24px;display:inline-block;flex-shrink:0"></span>'}
            <input id="mke-displayItem" list="mk-items-datalist" class="input input-sm w-full input-bordered" value="${escHtml(d._editDisplayItem)}" placeholder="auto (1\u00E8re sortie)" oninput="var u=mkItemIconUrl(this.value);var ic=document.getElementById('mke-display-icon');if(u&&ic){ic.style.display='';ic.src=u}">
          </div>
          <span class="field-help">${t('market.shopIconHelp')}</span>
        </div>
        <div class="field-group">
          <label class="field-label">${t('market.customName')}</label>
          <input id="mke-displayName" class="input input-sm w-full input-bordered" value="${escHtml(d._editDisplayName)}" placeholder="${t('market.customNamePlaceholder')}">
        </div>
      </div>

      <div class="mk-section-label">${t('market.stock')}</div>
      <div class="field-group mb-2">
        <select id="mke-stockMode" class="select select-sm w-full select-bordered" onchange="_mkd.syncEditorFields();mkRenderItemEditor()">
          <option value="UNLIMITED" ${sm.mode==='UNLIMITED'?'selected':''}>${t('market.stockUnlimited')}</option>
          <option value="GLOBAL" ${sm.mode==='GLOBAL'?'selected':''}>${t('market.stockGlobal')}</option>
          <option value="PER_PLAYER" ${sm.mode==='PER_PLAYER'?'selected':''}>${t('market.stockPerPlayer')}</option>
          <option value="PER_PLAYER_RESET" ${sm.mode==='PER_PLAYER_RESET'?'selected':''}>${t('market.stockPerPlayerReset')}</option>
        </select>
      </div>
      ${showGlobal ? `<div class="field-group mb-2"><label class="field-label">${t('market.stockGlobal')}</label><input id="mke-globalStock" type="number" min="0" class="input input-sm w-full input-bordered" value="${sm.globalStock || 0}"></div>` : ''}
      ${showPerPlayer ? `<div class="field-group mb-2"><label class="field-label">${t('market.playerLimit')}</label><input id="mke-perPlayerLimit" type="number" min="0" class="input input-sm w-full input-bordered" value="${sm.perPlayerLimit || 0}"></div>` : ''}
      ${showReset ? `<div class="field-group mb-2"><label class="field-label">${t('market.resetType')}</label>
        <select id="mke-resetType" class="select select-sm w-full select-bordered" onchange="_mkd.syncEditorFields();mkRenderItemEditor()">
          <option value="NONE" ${sm.resetType==='NONE'?'selected':''}>${t('common.none')}</option>
          <option value="DAILY" ${sm.resetType==='DAILY'?'selected':''}>${t('market.resetDaily')}</option>
          <option value="WEEKLY" ${sm.resetType==='WEEKLY'?'selected':''}>${t('market.resetWeekly')}</option>
          <option value="CUSTOM" ${sm.resetType==='CUSTOM'?'selected':''}>${t('market.resetCustom')}</option>
        </select>
      </div>` : ''}
      ${showCustomInterval ? `<div class="field-group mb-2"><label class="field-label">${t('market.intervalSeconds')}</label><input id="mke-resetInterval" type="number" min="0" class="input input-sm w-full input-bordered" value="${sm.resetIntervalSeconds || 0}"></div>` : ''}

      <div class="mk-section-label">${t('common.status')}</div>
      <label class="flex items-center gap-2 text-sm cursor-pointer" style="color:var(--text-secondary)">
        <input id="mke-enabled" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${d._editEnabled ? 'checked' : ''}>
        ${t('market.itemActive')}
      </label>

      <div class="flex justify-end gap-2 mt-6">
        <button class="btn-cg btn-cg-ghost" onclick="_mkd.closeItemEditor()">${t('common.close')}</button>
        ${d._editItemId ? '' : `<button class="btn-cg btn-cg-primary" onclick="_mkd.saveItemFromModal()">${t('common.create')}</button>`}
      </div>
    `;
  }

  function mkRenderTransactions(txs) {
    const el = document.getElementById('mkd-tx-body');
    if (!el) return;
    if (!txs.length) { el.innerHTML = '<div class="text-center py-4 text-sm" style="color:var(--text-muted)">' + t('common.noResults') + '</div>'; return; }
    el.innerHTML = `
      <table class="cg-table text-xs">
        <thead><tr><th>${t('market.transDate')}</th><th>${t('market.transPlayer')}</th><th>${t('market.transShop')}</th><th>${t('market.transItem')}</th><th>${t('market.transCost')}</th></tr></thead>
        <tbody>${txs.map(tx => `
          <tr>
            <td class="font-mono text-xs">${I18N.fmtDate(Date.parse(tx.timestamp))}</td>
            <td>${escHtml(tx.playerName || '?')}</td>
            <td><span class="id-badge text-xs">${escHtml(tx.shopId)}</span></td>
            <td>${escHtml(tx.itemName || tx.itemId)}</td>
            <td class="text-sm" style="color:var(--text-muted)">${(tx.costs || []).map(c => escHtml(c.itemId?.split(':')[1] || c.itemId) + ' x' + c.count).join(', ') || t('market.free')}</td>
          </tr>
        `).join('')}</tbody>
      </table>`;
  }

  const dm = shop.displayMode || 'TABS';
  const sets = shop.sets || [];
  let totalItems = 0, enabledItems = 0;
  sets.forEach(s => { (s.items || []).forEach(i => { totalItems++; if (i.enabled) enabledItems++; }); });

  // Load transactions when that tab is first shown + auto-save config
  setTimeout(() => {
    const txTab = document.querySelector('[data-tab-panel-mk="transactions"]');
    if (txTab) {
      const obs = new MutationObserver(() => {
        if (txTab.style.display !== 'none' && !window._mkd.txLoaded) window._mkd.loadTransactions();
      });
      obs.observe(txTab, { attributes: true, attributeFilter: ['style'] });
    }
    // Auto-save market config on input/change
    const cfgPanel = document.querySelector('[data-tab-panel-mk="config"]');
    if (cfgPanel) {
      const autoSaveCfg = debounce(() => { if (window._mkd) window._mkd.saveConfig(); }, 1500);
      cfgPanel.addEventListener('input', autoSaveCfg);
      cfgPanel.addEventListener('change', autoSaveCfg);
    }
  }, 100);

  return `
  <div style="animation:slide-in-right 0.2s ease-out">
    <div class="section-card">
      <!-- Header -->
      <div class="detail-header">
        ${detailBackBtn('market', t('market.shops'))}
        <div class="flex-1">
          <div class="text-sm font-semibold" style="color:var(--text-heading)">${escHtml(shop.name || shop.id)}</div>
          <div class="text-sm font-mono" style="color:var(--text-muted)">${escHtml(shop.id)} — ${escHtml(dm)}</div>
        </div>
      </div>

      ${detailTabBar([
        { id: 'config', label: t('market.tabs.config') },
        { id: 'layout', label: t('market.tabs.layout') },
        { id: 'sets', label: t('market.tabs.sets') },
        { id: 'transactions', label: t('market.tabs.transactions') },
      ], 'config', 'mkSwitchTab')}

      <!-- Tab: Config -->
      <div data-tab-panel-mk="config" class="section-card-body">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div class="field-group">
            <label class="field-label">${t('common.name')}</label>
            <textarea id="mkd-cfg-name" class="textarea textarea-sm textarea-bordered w-full" rows="2">${escHtml(shop.name || '')}</textarea>
          </div>
          <div class="field-group">
            <label class="field-label">${t('market.displayMode')}</label>
            ${sel('mkd-cfg-mode', [{v:'TABS',l:t('market.modeTabs')},{v:'FLAT',l:t('market.modeFlat')},{v:'FEATURED',l:t('market.modeFeatured')}], dm)}
          </div>
          <div class="field-group md:col-span-2">
            <label class="field-label">${t('common.description')}</label>
            <textarea id="mkd-cfg-desc" class="textarea textarea-sm textarea-bordered w-full" rows="2">${escHtml(shop.description || '')}</textarea>
          </div>
          <div class="field-group md:col-span-2">
            <label class="flex items-center gap-2 text-sm" style="color:var(--text-muted)">
              <input id="mkd-cfg-welcome-on" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${shop.showWelcomeMessage ? 'checked' : ''}>
              ${t('market.welcomeMessage')}
            </label>
            ${inp('mkd-cfg-welcome', shop.welcomeMessage)}
          </div>
          <div class="field-group md:col-span-2" style="border-top:1px dashed var(--bg-border);padding-top:.75rem;margin-top:.25rem">
            <label class="flex items-center gap-2 text-sm" style="color:var(--text-muted)">
              <input id="mkd-cfg-morphoCatalog" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${shop.morphoCatalog ? 'checked' : ''}>
              <span>Shop catalogue de morphos (auto-populé)</span>
            </label>
            <p class="text-xs mt-1" style="color:var(--muted)">
              Si activé, ce shop ignore ses sets/items et est rempli automatiquement depuis le catalogue Morphos. Les achats consomment du fromage et ajoutent la morpho au codex du joueur.
            </p>
          </div>
        </div>
        <hr class="section-divider">
        <div class="field-label mb-2">${t('market.colorsHex')}</div>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
          ${mkColorField('titleColor', t('market.confirm.title'), shop.titleColor || 'gold')}
          ${mkColorField('bgColor', t('market.cfgBg'), shop.bgColor || '1A1A2E')}
          ${mkColorField('borderColor', t('market.cfgBorder'), shop.borderColor || '2A5A8A')}
          ${mkColorField('accentColor', t('market.cfgAccent'), shop.accentColor || '4AC8EA')}
          ${mkColorField('btnBuyColor', t('market.cfgBtnBuy'), shop.btnBuyColor || '1A5C2A')}
          ${mkColorField('btnBuyTextColor', t('market.cfgBtnBuyText'), shop.btnBuyTextColor || 'FFFFFF')}
          ${mkColorField('btnCloseColor', t('market.cfgBtnClose'), shop.btnCloseColor || '1E3A5F')}
          ${mkColorField('btnCloseTextColor', t('market.cfgBtnCloseText'), shop.btnCloseTextColor || 'CCCCCC')}
        </div>
        <div class="section-divider my-4"></div>
        <div class="danger-zone p-4 rounded-lg">
          <div class="danger-zone-title text-sm font-semibold mb-2">${t('common.delete')}</div>
          <p class="text-sm mb-3" style="color:var(--text-muted)">${t('market.deleteShopFull')}</p>
          <button class="btn-cg btn-cg-danger" onclick="_mkd.deleteShop()">${t('common.delete')}</button>
        </div>
      </div>

      <!-- Tab: Layout Editor -->
      <div data-tab-panel-mk="layout" class="section-card-body" style="display:none">
        <div id="mkd-layout-root">
          <div class="text-center py-8 text-sm" style="color:var(--text-muted)">${t('common.loading')}</div>
        </div>
      </div>

      <!-- Tab: Sets & Items -->
      <div data-tab-panel-mk="sets" class="section-card-body" style="display:none">
        <div id="mkd-sets-body">${mkBuildSetsHtml(shop)}</div>
      </div>

      <!-- Tab: Transactions (Stats + Transactions merged) -->
      <div data-tab-panel-mk="transactions" class="section-card-body" style="display:none">
        <div class="grid grid-cols-3 gap-3 mb-4">
          <div class="stat-card"><span class="stat-value" style="color:var(--cyan)">${sets.length}</span><span class="stat-label">${t('market.sets')}</span></div>
          <div class="stat-card"><span class="stat-value" style="color:var(--green)">${totalItems}</span><span class="stat-label">${t('market.totalItems')}</span></div>
          <div class="stat-card"><span class="stat-value" style="color:var(--amber)">${enabledItems}</span><span class="stat-label">${t('market.activeItems')}</span></div>
        </div>
        <div class="section-divider mb-4"></div>
        <div id="mkd-tx-body" class="text-center py-8 text-sm" style="color:var(--text-muted)">${t('common.loading')}</div>
      </div>
    </div>
  </div>`;
}

// egdEnumSelect and egdRenderDiscoveries are shared helpers defined in app.js
