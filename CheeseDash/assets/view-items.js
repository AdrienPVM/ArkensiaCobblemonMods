// ── CheeseItems ──────────────────────────────────────────────────────────────

async function buildItems(app) {
  let items, enums;
  try {
    [items, enums] = await Promise.all([API.items.list(), API.items.enums()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const POTIONS = enums.potionEffects || [];

  // ── State ────────────────────────────────────────────────────────────────
  window._ci = {
    items, enums, app,
    filter: '', filterEnabled: '',
    editing: null,
    editTab: 'identity',
    _saveTimer: null,

    async refresh() {
      try { this.items = await API.items.list(); _ciRenderList(); }
      catch (e) { app.toast(e.message, 'error'); }
    },

    async reload() {
      try { await API.items.reload(); app.toast(t('items.reloaded'), 'success'); this.refresh(); }
      catch (e) { app.toast(e.message, 'error'); }
    },

    openCreate() {
      window._ciCreateIconMode = 'mc';
      window._ciCreateImageB64 = null;
      document.getElementById('ci-create-dlg')?.showModal();
      // Reset UI after dialog opens
      setTimeout(() => {
        _ciUpdateCreateIconUI();
        const prev = document.getElementById('ci-create-custom-preview');
        if (prev) prev.innerHTML = `<span style="color:var(--muted);font-size:var(--type-xs)">${t('common.none')}</span>`;
        const radios = document.querySelectorAll('input[name="ci-create-icon-mode"]');
        radios.forEach(r => r.checked = r.value === 'mc');
      }, 10);
    },

    async doCreate() {
      const id = (document.getElementById('ci-new-id')?.value || '').trim();
      const name = (document.getElementById('ci-new-name')?.value || '').trim() || t('items.newItem');
      const isCustom = window._ciCreateIconMode === 'custom';
      const base = isCustom ? 'minecraft:paper' : (document.getElementById('ci-new-base')?.value || 'minecraft:paper');
      try {
        const created = await API.items.create({ id: id || undefined, displayName: name, baseItem: base });
        if (isCustom && window._ciCreateImageB64) {
          try { await API.items.uploadImage(created.id, window._ciCreateImageB64); created.hasCustomIcon = true; } catch(e) {}
        }
        window._ciCreateImageB64 = null;
        app.toast(t('items.created'), 'success');
        document.getElementById('ci-create-dlg')?.close();
        this.items.push(created);
        _ciRenderList();
        _ciOpenEditor(created.id);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteItem(id) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.items.delete(id);
        app.toast(t('items.deleted'), 'success');
        this.items = this.items.filter(i => i.id !== id);
        _ciRenderList();
        document.getElementById('ci-editor-dlg')?.close();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async toggleEnabled(id) {
      const it = this.items.find(i => i.id === id);
      if (!it) return;
      try { await API.items.update(id, { enabled: !it.enabled }); it.enabled = !it.enabled; _ciRenderList(); }
      catch (e) { app.toast(e.message, 'error'); }
    },

    async giveItem(id) {
      const playerName = document.getElementById('ci-give-player')?.value?.trim();
      const quantity = parseInt(document.getElementById('ci-give-qty')?.value) || 1;
      if (!playerName) { app.toast(t('items.playerRequired'), 'error'); return; }
      try {
        await API.items.give(id, { playerName, quantity });
        app.toast(t('items.given'), 'success');
        document.getElementById('ci-give-dlg')?.close();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    scheduleSave() {
      clearTimeout(this._saveTimer);
      const dirty = document.getElementById('ci-dirty');
      if (dirty) dirty.style.display = '';
      this._saveTimer = setTimeout(() => this.saveEditing(), 1500);
    },

    async saveEditing() {
      if (!this.editing) return;
      const data = _ciCollectEditorData();
      try {
        const updated = await API.items.update(this.editing.id, data);
        Object.assign(this.editing, updated);
        const dirty = document.getElementById('ci-dirty');
        if (dirty) dirty.style.display = 'none';
        const idx = this.items.findIndex(i => i.id === this.editing.id);
        if (idx >= 0) Object.assign(this.items[idx], updated);
        _ciRenderList();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    toggleFlag(field) {
      if (!this.editing) return;
      this.editing[field] = !this.editing[field];
      this.scheduleSave();
      // Toggle the CSS class on the button
      const btn = document.getElementById('ci-tg-' + field);
      if (btn) btn.classList.toggle('on');
    },

    addEffect(field) {
      if (!this.editing) return;
      if (!this.editing[field]) this.editing[field] = [];
      this.editing[field].push({ effectId: 'minecraft:speed', amplifier: 0, durationTicks: 600 });
      this.scheduleSave();
      _ciRenderEditor();
    },

    removeEffect(field, idx) {
      if (!this.editing || !this.editing[field]) return;
      this.editing[field].splice(idx, 1);
      this.scheduleSave();
      _ciRenderEditor();
    },

    addAction() {
      if (!this.editing) return;
      if (!this.editing.useActions) this.editing.useActions = [];
      this.editing.useActions.push({ command: '', count: 1, feedback: '', delayCommand: '', delaySeconds: 0, showTimer: false, timerLabel: '' });
      this.scheduleSave();
      _ciRenderEditor();
    },

    removeAction(idx) {
      if (!this.editing || !this.editing.useActions) return;
      this.editing.useActions.splice(idx, 1);
      this.scheduleSave();
      _ciRenderEditor();
    },
  };

  // Icon cache & loader: now in app.js (_ciIconCache, _ciLoadIcons, _ciInvalidateIconCache)

  // ── Render list ──────────────────────────────────────────────────────────
  function _ciRenderList() {
    const el = document.getElementById('ci-list');
    if (!el) return;
    const ci = window._ci;
    let filtered = ci.items.slice();
    if (ci.filter) {
      const q = ci.filter.toLowerCase();
      filtered = filtered.filter(i => i.id.toLowerCase().includes(q) || i.displayName.toLowerCase().includes(q));
    }
    if (ci.filterEnabled === 'on') filtered = filtered.filter(i => i.enabled);
    if (ci.filterEnabled === 'off') filtered = filtered.filter(i => !i.enabled);

    if (!filtered.length) {
      el.innerHTML = '<tr><td colspan="7" class="text-center" style="color:var(--muted);padding:2rem">' + t('common.noResults') + '</td></tr>';
      return;
    }
    el.innerHTML = filtered.map(i => {
      const fl = [];
      if (i.glowing) fl.push(t('items.flagGlint'));
      if (i.soulbound) fl.push(t('items.flagSoulbound'));
      if (i.consumable) fl.push(t('items.flagConsumable'));
      if (i.unbreakable) fl.push(t('items.flagUnbreakable'));
      if (!i.droppable) fl.push(t('items.flagNoDrop'));
      if (i.disappearOnDeath) fl.push(t('items.flagDeath'));
      if (i.maxUses > 0) fl.push(t('items.flagUses', {n: i.maxUses}));
      if (i.cooldownTicks > 0) fl.push(t('items.flagCooldown', {n: (i.cooldownTicks/20).toFixed(0)}));
      const flags = fl.join(', ') || '—';
      const badges = [];
      if (i.effectCount > 0) badges.push(`<span style="background:var(--cyan);color:var(--bg);border-radius:4px;padding:0 5px;font-size:var(--type-2xs);font-weight:600" title="${t('items.effectCount', {n: i.effectCount})}">🧪 ${i.effectCount}</span>`);
      if (i.actionCount > 0) badges.push(`<span style="background:var(--amber);color:var(--bg);border-radius:4px;padding:0 5px;font-size:var(--type-2xs);font-weight:600" title="${t('items.actionCount', {n: i.actionCount})}">⚡ ${i.actionCount}</span>`);
      const badgeHtml = badges.length ? ' ' + badges.join(' ') : '';
      const iconHtml = i.hasCustomIcon
        ? `<img data-ci-icon="${escHtml(i.id)}" width="20" height="20" style="image-rendering:pixelated;vertical-align:middle;border-radius:2px">`
        : mkItemImg(i.baseItem, 20);
      return `<tr class="hover-elevated" style="cursor:pointer" onclick="_ciOpenEditor('${escHtml(i.id)}')">
        <td>${iconHtml} <span class="id-badge" style="cursor:copy" onclick="event.stopPropagation();cgCopy('cheeseitems:${escHtml(i.id)}');window._ci.app.toast(t('common.copied'),'success',1500)" title="${t('items.clickToCopy')}">cheeseitems:${escHtml(i.id)}</span></td>
        <td style="color:${escHtml(i.nameColor || 'var(--text-primary)')}">${escHtml(i.displayName)}</td>
        <td class="td-mono" style="font-size:var(--type-2xs)">${escHtml(i.baseItem)}</td>
        <td style="font-size:var(--type-2xs);color:var(--muted-light)">${escHtml(flags)}${badgeHtml}</td>
        <td>
          <input type="checkbox" class="toggle toggle-sm" ${i.enabled ? 'checked' : ''} onclick="event.stopPropagation();window._ci.toggleEnabled('${escHtml(i.id)}')">
        </td>
        <td>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="event.stopPropagation();_ciDuplicate('${escHtml(i.id)}')" title="${t('common.duplicate')}">📋</button>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="event.stopPropagation();_ciOpenGive('${escHtml(i.id)}')" title="${t('common.give')}">🎁</button>
          <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="event.stopPropagation();window._ci.deleteItem('${escHtml(i.id)}')" title="${t('common.delete')}">✕</button>
        </td>
      </tr>`;
    }).join('');
    _ciLoadIcons(el);
  }

  // ── Open editor ──────────────────────────────────────────────────────────
  window._ciOpenEditor = async function(id) {
    try { window._ci.editing = await API.items.get(id); }
    catch (e) { app.toast(e.message, 'error'); return; }
    window._ci.editTab = 'identity';
    _ciRenderEditor();
    document.getElementById('ci-editor-dlg')?.showModal();
  };

  window._ciOpenGive = function(id) {
    document.getElementById('ci-give-item-id').value = id;
    document.getElementById('ci-give-player').value = '';
    document.getElementById('ci-give-qty').value = '1';
    // Populate online players datalist
    let dl = document.getElementById('ci-online-players-dl');
    if (!dl) { dl = document.createElement('datalist'); dl.id = 'ci-online-players-dl'; document.body.appendChild(dl); }
    const players = (window._ci.app.livePlayerList || []);
    dl.innerHTML = players.map(p => `<option value="${escHtml(p.name || p)}">`).join('');
    document.getElementById('ci-give-dlg')?.showModal();
  };

  window._ciSwitchTab = function(tabId, btn) {
    window._ci.editTab = tabId;
    _ciRenderEditor();
    document.querySelectorAll('#ci-editor-tabs .detail-tab').forEach(b => b.classList.remove('active'));
    if (btn) btn.classList.add('active');
  };

  window._ciSetCond = function(field, value) {
    if (!window._ci.editing) return;
    if (!window._ci.editing.useConditions) window._ci.editing.useConditions = {};
    window._ci.editing.useConditions[field] = value;
    window._ci.scheduleSave();
  };

  window._ciToggleTimer = function(idx) {
    const a = window._ci.editing?.useActions?.[idx];
    if (!a) return;
    a.showTimer = !a.showTimer;
    window._ci.scheduleSave();
    _ciRenderEditor();
  };

  // ── Editor render ────────────────────────────────────────────────────────
  function _ciRenderEditor() {
    const d = window._ci.editing;
    if (!d) return;
    const body = document.getElementById('ci-editor-body');
    if (!body) return;

    const hdr = document.getElementById('ci-editor-title');
    const edIconHtml = d.hasCustomIcon
      ? `<img data-ci-icon="${escHtml(d.id)}" width="20" height="20" style="image-rendering:pixelated;vertical-align:middle;border-radius:2px">`
      : mkItemImg(d.baseItem, 20);
    if (hdr) hdr.innerHTML = edIconHtml + ' ' + escHtml(d.displayName || d.id);

    let content = '';
    switch (window._ci.editTab) {
      case 'identity': content = _ciTabIdentity(d); break;
      case 'flags':    content = _ciTabFlags(d); break;
      case 'effects':  content = _ciTabEffects(d); break;
      case 'actions':    content = _ciTabActions(d); break;
      case 'endurance':  content = _ciTabEndurance(d); break;
    }
    body.innerHTML = content;

    // Sync active tab button
    const tabs = document.querySelectorAll('#ci-editor-tabs .detail-tab');
    const tabOrder = ['identity','flags','effects','actions','endurance'];
    tabs.forEach((btn, idx) => {
      btn.classList.toggle('active', tabOrder[idx] === window._ci.editTab);
    });

    // Update base item preview on input
    const baseInp = document.getElementById('ci-ed-baseItem');
    if (baseInp) {
      baseInp.addEventListener('input', function() {
        const prev = document.getElementById('ci-base-preview');
        if (prev) prev.innerHTML = mkItemImg(this.value, 24);
      });
    }
    // Load custom icon images with auth
    const dlg = document.getElementById('ci-editor-dlg');
    if (dlg) _ciLoadIcons(dlg);
  }

  // ── Tab: Identity ────────────────────────────────────────────────────────
  function _ciTabIdentity(d) {
    const nameHex = (d.nameColor || '#FFFFFF').replace(/^#/, '');
    const loreHex = (d.loreColor || '#AAAAAA').replace(/^#/, '');
    return `
      <div class="grid gap-3" style="grid-template-columns:1fr 1fr">
        <div>
          <label class="field-label">${t('items.displayName')}</label>
          <input type="text" value="${escHtml(d.displayName)}" class="input input-sm w-full input-bordered"
            oninput="window._ci.editing.displayName=this.value;window._ci.scheduleSave()">
        </div>
        <div>
          <label class="field-label">${t('items.nameColor')}</label>
          <div class="flex items-center gap-1.5">
            <input type="color" value="#${escHtml(nameHex)}" class="w-8 h-8 rounded cursor-pointer border" style="border-color:var(--bg-border);background:transparent;padding:1px"
              onchange="document.getElementById('ci-nameHex').value=this.value.substring(1).toUpperCase();window._ci.editing.nameColor=this.value;window._ci.scheduleSave()">
            <input id="ci-nameHex" class="input input-sm flex-1 font-mono text-xs input-bordered" value="${escHtml(nameHex.toUpperCase())}"
              oninput="try{this.previousElementSibling.value='#'+this.value.replace(/^#/,'')}catch(e){};window._ci.editing.nameColor='#'+this.value.replace(/^#/,'');window._ci.scheduleSave()">
          </div>
        </div>
        <div class="col-span-2">
          <label class="field-label">${t('common.type')}</label>
          <div class="flex gap-3 mb-2">
            <label class="flex items-center gap-1.5 cursor-pointer text-sm" style="color:var(--muted-light)">
              <input type="radio" name="ci-ed-icon-mode" value="mc" ${!d.hasCustomIcon ? 'checked' : ''} class="radio radio-sm"
                onchange="_ciSwitchIconMode('mc')"> ${t('items.itemMC')}
            </label>
            <label class="flex items-center gap-1.5 cursor-pointer text-sm" style="color:var(--muted-light)">
              <input type="radio" name="ci-ed-icon-mode" value="custom" ${d.hasCustomIcon ? 'checked' : ''} class="radio radio-sm"
                onchange="_ciSwitchIconMode('custom')"> ${t('items.customImage')}
            </label>
          </div>
          <div id="ci-ed-mode-mc" ${d.hasCustomIcon ? 'style="display:none"' : ''}>
            <div class="flex items-center gap-2">
              <span id="ci-base-preview">${mkItemImg(d.baseItem, 24)}</span>
              <input id="ci-ed-baseItem" type="text" value="${escHtml(d.baseItem)}" list="global-items-dl" class="input input-sm flex-1 font-mono text-sm input-bordered"
                oninput="window._ci.editing.baseItem=this.value;window._ci.scheduleSave()">
            </div>
          </div>
          <div id="ci-ed-mode-custom" ${!d.hasCustomIcon ? 'style="display:none"' : ''}>
            <div class="flex items-center gap-3">
              <div id="ci-ed-icon-preview" class="w-12 h-12 rounded flex items-center justify-center" style="background:var(--bg-elevated);border:1px solid var(--bg-border)">
                ${d.hasCustomIcon ? `<img data-ci-icon="${escHtml(d.id)}" class="w-12 h-12 rounded" style="image-rendering:pixelated;object-fit:contain">` : `<span style="color:var(--muted);font-size:var(--type-xs)">${t('common.none')}</span>`}
              </div>
              <label class="btn-cg btn-cg-sm btn-cg-ghost cursor-pointer">
                ${d.hasCustomIcon ? t('items.changeImage') : t('items.uploadPng')}
                <input type="file" accept="image/png" style="display:none" onchange="_ciHandleEditorImageUpload(this)">
              </label>
              ${d.hasCustomIcon ? `<button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_ciDeleteEditorImage()">${t('common.delete')}</button>` : ''}
            </div>
          </div>
        </div>
      </div>
      <div class="mt-3">
        <label class="field-label">${t('items.lore')}</label>
        <textarea rows="3" class="textarea textarea-sm textarea-bordered w-full font-mono text-sm" style="resize:vertical"
          oninput="window._ci.editing.lore=this.value.split('\\n');window._ci.scheduleSave()">${escHtml((d.lore||[]).join('\n'))}</textarea>
      </div>
      <div class="grid gap-3 mt-3" style="grid-template-columns:1fr 1fr">
        <div>
          <label class="field-label">${t('items.loreColor')}</label>
          <div class="flex items-center gap-1.5">
            <input type="color" value="#${escHtml(loreHex)}" class="w-8 h-8 rounded cursor-pointer border" style="border-color:var(--bg-border);background:transparent;padding:1px"
              onchange="document.getElementById('ci-loreHex').value=this.value.substring(1).toUpperCase();window._ci.editing.loreColor=this.value;window._ci.scheduleSave()">
            <input id="ci-loreHex" class="input input-sm flex-1 font-mono text-xs input-bordered" value="${escHtml(loreHex.toUpperCase())}"
              oninput="try{this.previousElementSibling.value='#'+this.value.replace(/^#/,'')}catch(e){};window._ci.editing.loreColor='#'+this.value.replace(/^#/,'');window._ci.scheduleSave()">
          </div>
        </div>
        <div>
          <label class="field-label">${t('items.dyeColor')}</label>
          <input type="number" value="${d.dyeColor}" min="-1" max="16777215" class="input input-sm w-full input-bordered"
            oninput="window._ci.editing.dyeColor=+this.value;window._ci.scheduleSave()">
        </div>
      </div>`;
  }

  // ── Tab: Flags ───────────────────────────────────────────────────────────
  function _ciTabFlags(d) {
    const tg = (label, field, icon, tip) => {
      const val = !!d[field];
      return `<div class="flex items-center gap-3 py-2 px-3 rounded" style="background:var(--bg-elevated)" title="${escHtml(tip)}">
        <input type="checkbox" id="ci-tg-${field}" class="toggle toggle-sm" ${val?'checked':''} onclick="window._ci.toggleFlag('${field}')">
        <span style="font-size:var(--type-sm);color:var(--text-primary)">${icon} ${escHtml(label)}</span>
      </div>`;
    };
    const tSec = (t) => t > 0 ? ` (${(t/20).toFixed(1)}s)` : '';
    return `
      <div class="grid gap-2" style="grid-template-columns:1fr 1fr">
        ${tg(t('common.enabled'),'enabled','⚡',t('tip.items.conditions'))}
        ${tg(t('items.glint'),'glowing','✨',t('tip.items.glowing'))}
        ${tg(t('items.droppable'),'droppable','📤',t('tip.items.droppable'))}
        ${tg(t('items.tradeable'),'tradeable','🤝',t('tip.items.conditions'))}
        ${tg(t('items.placeable'),'placeable','🧱',t('tip.items.conditions'))}
        ${tg(t('items.consumable'),'consumable','🍖',t('tip.items.consumable'))}
        ${tg(t('items.soulbound'),'soulbound','🔗',t('tip.items.soulbound'))}
        ${tg(t('items.disappearOnDeath'),'disappearOnDeath','💀',t('tip.items.conditions'))}
        ${tg(t('items.unbreakable'),'unbreakable','🛡',t('tip.items.unbreakable'))}
      </div>
      <div class="section-divider my-4"></div>
      <div class="grid gap-3" style="grid-template-columns:1fr 1fr 1fr">
        <div>
          <label class="field-label" title="${t('tip.items.conditions')}">${t('items.maxStackSize')}</label>
          <input type="number" value="${d.maxStackSize}" min="1" max="64" class="input input-sm w-full input-bordered"
            oninput="window._ci.editing.maxStackSize=+this.value;window._ci.scheduleSave()">
        </div>
        <div>
          <label class="field-label" title="${t('tip.items.maxUses')}">${t('items.maxUses')}</label>
          <input type="number" value="${d.maxUses}" min="0" class="input input-sm w-full input-bordered"
            oninput="window._ci.editing.maxUses=+this.value;window._ci.scheduleSave()">
        </div>
        <div>
          <label class="field-label" title="${t('tip.items.cooldown')}">${t('items.cooldownTicks')}${tSec(d.cooldownTicks)}</label>
          <input type="number" value="${d.cooldownTicks||0}" min="0" class="input input-sm w-full input-bordered"
            oninput="window._ci.editing.cooldownTicks=+this.value;window._ci.scheduleSave();this.parentElement.querySelector('.field-label').textContent=t('items.cooldownTicks')+(+this.value>0?' ('+(+this.value/20).toFixed(1)+'s)':'')">
        </div>
      </div>`;
  }

  // ── Tab: Effects ─────────────────────────────────────────────────────────
  function _ciTabEffects(d) {
    function effectSection(title, field, list) {
      const rows = (list||[]).map((e,i) => `
        <div class="flex gap-2 items-center mb-2 p-2 rounded" style="background:var(--bg-elevated)">
          <input type="text" value="${escHtml(e.effectId)}" list="ci-potions-dl" placeholder="minecraft:speed"
            oninput="window._ci.editing.${field}[${i}].effectId=this.value;window._ci.scheduleSave()"
            class="input input-sm input-bordered font-mono text-xs" style="flex:2">
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('items.amplifier')}</label>
            <input type="number" value="${e.amplifier}" min="0" max="9"
              oninput="window._ci.editing.${field}[${i}].amplifier=+this.value;window._ci.scheduleSave()"
              class="input input-sm input-bordered" style="width:52px">
          </div>
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('items.ticks')}</label>
            <input type="number" value="${e.durationTicks}" min="0"
              oninput="window._ci.editing.${field}[${i}].durationTicks=+this.value;window._ci.scheduleSave()"
              class="input input-sm input-bordered" style="width:72px">
          </div>
          <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._ci.removeEffect('${field}',${i})">✕</button>
        </div>`).join('');
      return `<div class="mb-5">
        <div class="flex items-center justify-between mb-2">
          <span class="font-semibold text-sm" style="color:var(--amber)">${escHtml(title)}</span>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._ci.addEffect('${field}')">${t('common.add')}</button>
        </div>
        ${rows || '<div class="text-xs py-2" style="color:var(--muted)">' + t('common.noResults') + '</div>'}
      </div>`;
    }
    return `
      ${effectSection(t('items.onUse'),'onUseEffects',d.onUseEffects)}
      <div class="section-divider my-3"></div>
      ${effectSection(t('items.passive'),'passiveEffects',d.passiveEffects)}
      <div class="section-divider my-3"></div>
      ${effectSection(t('items.onPickup'),'onPickupEffects',d.onPickupEffects)}
      <datalist id="ci-potions-dl">${POTIONS.map(p=>`<option value="${escHtml(p)}">`).join('')}</datalist>`;
  }

  // ── Tab: Actions & Conditions ────────────────────────────────────────────
  function _ciTabActions(d) {
    const actRows = (d.useActions||[]).map((a,i) => {
      const hasDelay = a.delayCommand || a.delaySeconds > 0;
      return `
      <div class="p-3 rounded mb-2" style="background:var(--bg-elevated);border:1px solid var(--bg-border)">
        <div class="flex gap-2 items-center mb-2">
          <input type="text" value="${escHtml(a.command)}" placeholder="give {player} diamond 1"
            oninput="window._ci.editing.useActions[${i}].command=this.value;window._ci.scheduleSave()"
            class="input input-sm input-bordered font-mono text-xs" style="flex:1" title="${t('tip.items.serverCommand')}">
          <div>
            <label class="text-xs" style="color:var(--muted)">×</label>
            <input type="number" value="${a.count}" min="1" max="100"
              oninput="window._ci.editing.useActions[${i}].count=+this.value;window._ci.scheduleSave()"
              class="input input-sm input-bordered" style="width:52px" title="${t('tip.items.conditions')}">
          </div>
          <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._ci.removeAction(${i})">✕</button>
        </div>
        <input type="text" value="${escHtml(a.feedback||'')}" placeholder="${t('items.feedbackPlaceholder')}"
          oninput="window._ci.editing.useActions[${i}].feedback=this.value;window._ci.scheduleSave()"
          class="input input-sm w-full input-bordered text-xs mb-2">
        <div class="flex gap-2 items-end">
          <div style="flex:1">
            <label class="text-xs" style="color:var(--muted)">⏱ ${t('items.delayCommand')}</label>
            <input type="text" value="${escHtml(a.delayCommand||'')}" placeholder="${t('items.delayPlaceholder')}"
              oninput="window._ci.editing.useActions[${i}].delayCommand=this.value;window._ci.scheduleSave()"
              class="input input-sm w-full input-bordered font-mono text-xs" title="${t('tip.items.conditions')}">
          </div>
          <div style="width:90px">
            <label class="text-xs" style="color:var(--muted)">${t('common.seconds')}</label>
            <input type="number" value="${a.delaySeconds||0}" min="0" max="3600"
              oninput="window._ci.editing.useActions[${i}].delaySeconds=+this.value;window._ci.scheduleSave()"
              class="input input-sm w-full input-bordered" title="${t('tip.items.conditions')}">
          </div>
        </div>
        <div class="flex gap-2 items-center mt-2" style="padding-top:4px;border-top:1px solid var(--bg-border)">
          <input type="checkbox" class="toggle toggle-sm" ${a.showTimer?'checked':''} onclick="_ciToggleTimer(${i})"
            title="${t('tip.items.conditions')}">
          <label class="text-xs" style="color:var(--muted)">${t('items.showTimerToPlayer')}</label>
          ${a.showTimer ? `<input type="text" value="${escHtml(a.timerLabel||'')}" placeholder="${t('items.timerPlaceholder')}"
            oninput="window._ci.editing.useActions[${i}].timerLabel=this.value;window._ci.scheduleSave()"
            class="input input-sm input-bordered text-xs" style="width:140px" title="${t('tip.items.timerLabel')}">` : ''}
        </div>
      </div>`;
    }).join('');

    const c = d.useConditions || {};
    const globalCdSec = Math.round((c.globalCooldownTicks||0) / 20);
    return `
      <div class="mb-5">
        <div class="flex items-center justify-between mb-2">
          <span class="font-semibold text-sm" style="color:var(--amber)">${t('items.serverCommands')}</span>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._ci.addAction()">${t('common.add')}</button>
        </div>
        ${actRows || '<div class="text-xs py-2" style="color:var(--muted)">' + t('common.noResults') + '</div>'}
      </div>
      <div class="section-divider my-4"></div>
      <span class="font-semibold text-sm" style="color:var(--amber)">${t('items.conditions')}</span>
      <div class="grid gap-3 mt-3" style="grid-template-columns:1fr 1fr">
        <div>
          <label class="field-label">${t('items.condDimension')}</label>
          <input type="text" value="${escHtml(c.dimension||'')}" list="global-dims-dl" placeholder="${t('items.condDimPlaceholder')}" class="input input-sm w-full input-bordered font-mono text-xs"
            oninput="_ciSetCond('dimension',this.value||null)">
        </div>
        <div>
          <label class="field-label">${t('items.condPermission')}</label>
          <input type="text" value="${escHtml(c.permission||'')}" placeholder="${t('items.condPermPlaceholder')}" class="input input-sm w-full input-bordered text-xs"
            oninput="_ciSetCond('permission',this.value||null)">
        </div>
        <div>
          <label class="field-label">${t('items.condZoneId')}</label>
          <input type="text" value="${escHtml(c.zoneId||'')}" list="global-zones-dl" placeholder="${t('items.condZonePlaceholder')}" class="input input-sm w-full input-bordered text-xs"
            oninput="_ciSetCond('zoneId',this.value||null)">
        </div>
        <div>
          <label class="field-label">${t('items.condMinLevel')}</label>
          <input type="number" value="${c.minLevel||0}" min="0" class="input input-sm w-full input-bordered"
            oninput="_ciSetCond('minLevel',+this.value)">
        </div>
        <div>
          <label class="field-label">${t('items.condRequiredItem')}</label>
          <input type="text" value="${escHtml(c.requiredHeldItem||'')}" list="global-items-dl" placeholder="${t('items.condItemPlaceholder')}" class="input input-sm w-full input-bordered font-mono text-xs"
            oninput="_ciSetCond('requiredHeldItem',this.value||null)">
        </div>
        <div>
          <label class="field-label">${t('items.condGlobalCooldown')}</label>
          <input type="number" value="${globalCdSec}" min="0" class="input input-sm w-full input-bordered"
            oninput="_ciSetCond('globalCooldownTicks',+this.value*20)" title="${t('tip.items.cooldown')}">
        </div>
      </div>`;
  }

  // ── Tab: Endurance ──────────────────────────────────────────────────────
  window._ciToggleEndurance = function() {
    const d = window._ci.editing;
    if (!d) return;
    if (d.endurance) {
      d.endurance = null;
    } else {
      d.endurance = { tag: '', displayName: 'Endurance', capacity: 100, drainPerSecond: 2.0, regenPerSecond: 1.0, onDepletedCommands: [] };
    }
    window._ci.scheduleSave();
    _ciRenderEditor();
  };

  window._ciAddDepletedCmd = function() {
    const d = window._ci.editing;
    if (!d || !d.endurance) return;
    d.endurance.onDepletedCommands.push({ command: '', count: 1, feedback: '' });
    window._ci.scheduleSave();
    _ciRenderEditor();
  };

  window._ciRemoveDepletedCmd = function(idx) {
    const d = window._ci.editing;
    if (!d || !d.endurance) return;
    d.endurance.onDepletedCommands.splice(idx, 1);
    window._ci.scheduleSave();
    _ciRenderEditor();
  };

  function _ciTabEndurance(d) {
    const ec = d.endurance;
    const enabled = !!ec;

    const cmdRows = enabled ? (ec.onDepletedCommands||[]).map((a,i) => `
      <div class="p-3 rounded mb-2" style="background:var(--bg-elevated);border:1px solid var(--bg-border)">
        <div class="flex gap-2 items-center mb-2">
          <input type="text" value="${escHtml(a.command)}" placeholder="cshift revert {player}"
            oninput="window._ci.editing.endurance.onDepletedCommands[${i}].command=this.value;window._ci.scheduleSave()"
            class="input input-sm input-bordered font-mono text-xs" style="flex:1" title="${t('tip.items.serverCommand')}">
          <div>
            <label class="text-xs" style="color:var(--muted)">×</label>
            <input type="number" value="${a.count}" min="1" max="100"
              oninput="window._ci.editing.endurance.onDepletedCommands[${i}].count=+this.value;window._ci.scheduleSave()"
              class="input input-sm input-bordered" style="width:52px">
          </div>
          <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_ciRemoveDepletedCmd(${i})">✕</button>
        </div>
        <input type="text" value="${escHtml(a.feedback||'')}" placeholder="${t('items.feedbackPlaceholder')}"
          oninput="window._ci.editing.endurance.onDepletedCommands[${i}].feedback=this.value;window._ci.scheduleSave()"
          class="input input-sm w-full input-bordered text-xs">
      </div>`).join('') : '';

    return `
      <div class="mb-4">
        <div class="flex items-center gap-2 mb-4">
          <input type="checkbox" class="toggle toggle-sm" ${enabled?'checked':''} onclick="_ciToggleEndurance()"
            title="${t('tip.items.endurance')}">
          <label class="font-semibold text-sm" style="color:var(--amber)">${t('tip.items.endurance')}</label>
        </div>
        ${enabled ? `
          <div class="grid gap-3 mb-4" style="grid-template-columns:1fr 1fr">
            <div>
              <label class="field-label">${t('items.enduranceTag')}</label>
              <input type="text" value="${escHtml(ec.tag||'')}" placeholder="pokemon"
                oninput="window._ci.editing.endurance.tag=this.value;window._ci.scheduleSave()"
                class="input input-sm w-full input-bordered text-xs"
                title="${t('tip.items.endurance')}">
            </div>
            <div>
              <label class="field-label">${t('items.actionbarLabel')}</label>
              <input type="text" value="${escHtml(ec.displayName||'')}" placeholder="Endurance"
                oninput="window._ci.editing.endurance.displayName=this.value;window._ci.scheduleSave()"
                class="input input-sm w-full input-bordered text-xs"
                title="${t('tip.items.endurance')}">
            </div>
            <div>
              <label class="field-label">${t("items.maxUses")}</label>
              <input type="number" value="${ec.capacity||100}" min="1" max="10000"
                oninput="window._ci.editing.endurance.capacity=+this.value;window._ci.scheduleSave()"
                class="input input-sm w-full input-bordered">
            </div>
            <div>
              <label class="field-label">${t('items.drainPerSec')}</label>
              <input type="number" value="${ec.drainPerSecond||2}" min="0.1" max="1000" step="0.1"
                oninput="window._ci.editing.endurance.drainPerSecond=+this.value;window._ci.scheduleSave()"
                class="input input-sm w-full input-bordered"
                title="${t('tip.items.endurance')}">
            </div>
            <div>
              <label class="field-label">${t('items.regenPerSec')}</label>
              <input type="number" value="${ec.regenPerSecond||1}" min="0.1" max="1000" step="0.1"
                oninput="window._ci.editing.endurance.regenPerSecond=+this.value;window._ci.scheduleSave()"
                class="input input-sm w-full input-bordered"
                title="${t('tip.items.endurance')}">
            </div>
          </div>
          <div class="section-divider my-4"></div>
          <div class="flex items-center justify-between mb-2">
            <span class="font-semibold text-sm" style="color:var(--amber)">${t('items.command')}</span>
            <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_ciAddDepletedCmd()">${t('common.add')}</button>
          </div>
          <div class="text-xs mb-3" style="color:var(--muted)">${t('tip.items.endurance')}</div>
          ${cmdRows || '<div class="text-xs py-2" style="color:var(--muted)">' + t('common.noResults') + '</div>'}
        ` : `<div class="text-xs py-2" style="color:var(--muted)">${t('common.disabled')}</div>`}
      </div>`;
  }

  // ── Collect editor data ──────────────────────────────────────────────────
  function _ciCollectEditorData() {
    const d = window._ci.editing;
    return {
      displayName: d.displayName, nameColor: d.nameColor, baseItem: d.baseItem,
      lore: d.lore, loreColor: d.loreColor,
      glowing: d.glowing, dyeColor: d.dyeColor, enabled: d.enabled,
      maxStackSize: d.maxStackSize, droppable: d.droppable, tradeable: d.tradeable,
      placeable: d.placeable, consumable: d.consumable, soulbound: d.soulbound,
      disappearOnDeath: d.disappearOnDeath, unbreakable: d.unbreakable,
      maxUses: d.maxUses, cooldownTicks: d.cooldownTicks,
      onUseEffects: d.onUseEffects, passiveEffects: d.passiveEffects,
      onPickupEffects: d.onPickupEffects, useActions: d.useActions,
      useConditions: d.useConditions,
      endurance: d.endurance || null,
    };
  }

  // ── Filter handlers ──────────────────────────────────────────────────────
  window._ciFilter = debounce(v => { window._ci.filter = v; _ciRenderList(); }, 300);
  window._ciFilterEnabled = v => { window._ci.filterEnabled = v; _ciRenderList(); };

  // ── Duplicate item ─────────────────────────────────────────────────────
  window._ciDuplicate = async function(id) {
    try {
      const src = await API.items.get(id);
      const data = {
        displayName: src.displayName + ' (' + t('items.copy') + ')',
        nameColor: src.nameColor, baseItem: src.baseItem,
        lore: src.lore, loreColor: src.loreColor,
        glowing: src.glowing, dyeColor: src.dyeColor, enabled: false,
        maxStackSize: src.maxStackSize, droppable: src.droppable,
        tradeable: src.tradeable, placeable: src.placeable,
        consumable: src.consumable, soulbound: src.soulbound,
        disappearOnDeath: src.disappearOnDeath, unbreakable: src.unbreakable,
        maxUses: src.maxUses, cooldownTicks: src.cooldownTicks,
        onUseEffects: src.onUseEffects, passiveEffects: src.passiveEffects,
        onPickupEffects: src.onPickupEffects, useActions: src.useActions,
        useConditions: src.useConditions,
        endurance: src.endurance || null,
      };
      const created = await API.items.create(data);
      window._ci.items.push(created);
      app.toast(t('common.success'), 'success');
      _ciRenderList();
      _ciOpenEditor(created.id);
    } catch (e) { app.toast(e.message, 'error'); }
  };

  // ── Create modal icon mode ───────────────────────────────────────────────
  window._ciCreateIconMode = 'mc';
  window._ciCreateImageB64 = null;

  window._ciUpdateCreateIconUI = function() {
    const mc = document.getElementById('ci-create-icon-mc');
    const custom = document.getElementById('ci-create-icon-custom');
    if (mc) mc.style.display = window._ciCreateIconMode === 'mc' ? '' : 'none';
    if (custom) custom.style.display = window._ciCreateIconMode === 'custom' ? '' : 'none';
  };

  window._ciUpdateCreatePreview = function() {
    const prev = document.getElementById('ci-create-preview');
    const val = document.getElementById('ci-new-base')?.value || '';
    if (prev) prev.innerHTML = mkItemImg(val, 32);
  };

  window._ciHandleCreateImageSelect = function(input) {
    const file = input.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      window._ciCreateImageB64 = reader.result;
      const prev = document.getElementById('ci-create-custom-preview');
      if (prev) prev.innerHTML = `<img src="${reader.result}" class="w-12 h-12 rounded" style="image-rendering:pixelated;object-fit:contain">`;
    };
    reader.readAsDataURL(file);
  };

  // ── Editor icon mode switch ──────────────────────────────────────────────
  window._ciSwitchIconMode = async function(mode) {
    const d = window._ci.editing;
    if (!d) return;
    const mcDiv = document.getElementById('ci-ed-mode-mc');
    const customDiv = document.getElementById('ci-ed-mode-custom');
    if (mcDiv) mcDiv.style.display = mode === 'mc' ? '' : 'none';
    if (customDiv) customDiv.style.display = mode === 'custom' ? '' : 'none';
    if (mode === 'mc' && d.hasCustomIcon) {
      try {
        await API.items.deleteImage(d.id);
        d.hasCustomIcon = false;
        _ciInvalidateIconCache(d.id);
        const listItem = window._ci.items.find(i => i.id === d.id);
        if (listItem) listItem.hasCustomIcon = false;
        _ciRenderList();
      } catch(e) { window._ci.app.toast(e.message, 'error'); }
    }
  };

  // ── Editor image upload/delete ─────────────────────────────────────────
  window._ciHandleEditorImageUpload = async function(input) {
    const file = input.files?.[0];
    if (!file || !window._ci.editing) return;
    const id = window._ci.editing.id;
    try {
      // Read as base64 for API upload
      const b64 = await new Promise((res, rej) => {
        const r = new FileReader();
        r.onload = () => res(r.result);
        r.onerror = rej;
        r.readAsDataURL(file);
      });
      await API.items.uploadImage(id, b64);
      window._ci.editing.hasCustomIcon = true;
      _ciInvalidateIconCache(id);
      // Pre-fill cache from the raw file (no fetch('data:') needed)
      _ciIconCache[id] = URL.createObjectURL(file);
      const listItem = window._ci.items.find(i => i.id === id);
      if (listItem) listItem.hasCustomIcon = true;
      window._ci.app.toast(t('common.success'), 'success');
      _ciRenderEditor();
      _ciRenderList();
    } catch(e) { window._ci.app.toast(e.message, 'error'); }
  };

  window._ciDeleteEditorImage = async function() {
    if (!window._ci.editing) return;
    if (!await confirmAction(t('common.deleteConfirm'))) return;
    const id = window._ci.editing.id;
    try {
      await API.items.deleteImage(id);
      window._ci.editing.hasCustomIcon = false;
      _ciInvalidateIconCache(id);
      const listItem = window._ci.items.find(i => i.id === id);
      if (listItem) listItem.hasCustomIcon = false;
      window._ci.app.toast(t('common.success'), 'success');
      _ciRenderEditor();
      _ciRenderList();
    } catch(e) { window._ci.app.toast(e.message, 'error'); }
  };

  // ── Build HTML ────────────────────────────────────────────────────────────
  setTimeout(() => _ciRenderList(), 50);

  return `<div class="max-w-6xl mx-auto" style="animation:slide-in-right 0.2s ease-out">
    <!-- Header -->
    <div class="flex items-center justify-between mb-4">
      <span class="font-medium" style="color:var(--text-heading);font-size:var(--type-lg)">${t('items.title')}</span>
      <div class="flex gap-2">
        <button class="btn-cg btn-cg-ghost" onclick="window._ci.reload()">${t('items.reload')}</button>
        <button class="btn-cg btn-cg-primary" onclick="window._ci.openCreate()">+ ${t('items.newItem')}</button>
      </div>
    </div>

    <!-- Filters -->
    <div class="filter-bar mb-4 flex gap-2 items-center flex-wrap">
      <input type="text" placeholder="${t('common.search')}" oninput="_ciFilter(this.value)" class="input input-sm input-bordered" style="width:200px">
      <select onchange="_ciFilterEnabled(this.value)" class="select select-sm select-bordered" style="width:130px">
        <option value="">${t('common.all')}</option>
        <option value="on">${t('common.enabled')}</option>
        <option value="off">${t('common.disabled')}</option>
      </select>
    </div>

    <!-- Table -->
    <div class="section-card">
      <table class="cg-table">
        <thead><tr>
          <th>${t('common.id')}</th><th>${t('common.name')}</th><th>${t('items.baseItem')}</th><th>${t('items.flags')}</th><th>${t('common.status')}</th><th>${t('common.actions')}</th>
        </tr></thead>
        <tbody id="ci-list"></tbody>
      </table>
    </div>

    <!-- Create modal -->
    <dialog id="ci-create-dlg" class="modal">
      <div class="modal-box" style="max-width:28rem">
        <h3 style="color:var(--amber)" class="font-semibold mb-4">${t('items.newItemCustom')}</h3>
        <div class="grid gap-3">
          <div>
            <label class="field-label" title="${t('tip.items.baseItem')}">${t("items.itemId")} (${t("common.optional")})</label>
            <input id="ci-new-id" type="text" placeholder="quest_key_01" class="input input-sm w-full font-mono input-bordered">
          </div>
          <div>
            <label class="field-label" title="${t('tip.items.baseItem')}">${t("items.displayName")}</label>
            <input id="ci-new-name" type="text" placeholder="${t('items.displayNamePlaceholder')}" class="input input-sm w-full input-bordered">
          </div>
          <div>
            <label class="field-label">${t("common.type")}</label>
            <div class="flex gap-3 mb-2">
              <label class="flex items-center gap-1.5 cursor-pointer text-sm" style="color:var(--muted-light)">
                <input type="radio" name="ci-create-icon-mode" value="mc" checked class="radio radio-sm" onchange="_ciCreateIconMode='mc';_ciUpdateCreateIconUI()"> ${t('items.itemMC')}
              </label>
              <label class="flex items-center gap-1.5 cursor-pointer text-sm" style="color:var(--muted-light)">
                <input type="radio" name="ci-create-icon-mode" value="custom" class="radio radio-sm" onchange="_ciCreateIconMode='custom';_ciUpdateCreateIconUI()"> ${t('items.customImage')}
              </label>
            </div>
            <div id="ci-create-icon-mc">
              <div class="flex items-center gap-2">
                <span id="ci-create-preview">${mkItemImg('minecraft:paper', 32)}</span>
                <input id="ci-new-base" type="text" value="minecraft:paper" list="global-items-dl" class="input input-sm flex-1 font-mono text-sm input-bordered"
                  oninput="_ciUpdateCreatePreview()">
              </div>
            </div>
            <div id="ci-create-icon-custom" style="display:none">
              <div class="flex items-center gap-3">
                <div id="ci-create-custom-preview" class="w-12 h-12 rounded flex items-center justify-center" style="background:var(--bg-elevated);border:1px dashed var(--bg-border)">
                  <span style="color:var(--muted);font-size:var(--type-xs)">${t('common.none')}</span>
                </div>
                <label class="btn-cg btn-cg-sm btn-cg-ghost cursor-pointer">
                  ${t('items.choosePng')}
                  <input type="file" accept="image/png" style="display:none" onchange="_ciHandleCreateImageSelect(this)">
                </label>
              </div>
            </div>
          </div>
        </div>
        <div class="mt-4 flex justify-end gap-2">
          <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
          <button class="btn-cg btn-cg-primary" onclick="window._ci.doCreate()">${t('common.create')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- Editor modal -->
    <dialog id="ci-editor-dlg" class="modal">
      <div class="modal-box" style="max-width:42rem;width:90%;padding:0;max-height:85vh;display:flex;flex-direction:column">
        <div class="flex items-center justify-between px-4 py-3" style="border-bottom:1px solid var(--bg-border)">
          <div class="flex items-center gap-2">
            <span id="ci-editor-title" class="font-medium" style="color:var(--text-primary)"></span>
            <span id="ci-dirty" class="text-xs" style="color:var(--amber);display:none">${t('common.save')}...</span>
          </div>
          <div class="flex gap-2">
            <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._ci.deleteItem(window._ci.editing?.id)">${t('common.delete')}</button>
            <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="document.getElementById('ci-editor-dlg').close()">✕</button>
          </div>
        </div>
        <div id="ci-editor-tabs" class="detail-tabs px-4 pt-2">
          <button class="detail-tab active" onclick="_ciSwitchTab('identity',this)">${t('items.tabs.identity')}</button>
          <button class="detail-tab" onclick="_ciSwitchTab('flags',this)">${t('items.tabs.flags')}</button>
          <button class="detail-tab" onclick="_ciSwitchTab('effects',this)">${t('items.tabs.effects')}</button>
          <button class="detail-tab" onclick="_ciSwitchTab('actions',this)">${t('items.tabs.actions')}</button>
          <button class="detail-tab" onclick="_ciSwitchTab('endurance',this)">${t('items.tabs.endurance')}</button>
        </div>
        <div id="ci-editor-body" class="p-4 overflow-y-auto" style="flex:1"></div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- Give modal -->
    <dialog id="ci-give-dlg" class="modal">
      <div class="modal-box" style="max-width:24rem">
        <h3 style="color:var(--amber)" class="font-semibold mb-3">${t('items.giveToPlayer')}</h3>
        <input type="hidden" id="ci-give-item-id">
        <div class="grid gap-3">
          <div>
            <label class="field-label">${t('common.player')}</label>
            <input id="ci-give-player" type="text" placeholder="${t('items.playerPlaceholder')}" class="input input-sm w-full input-bordered" list="ci-online-players-dl">
          </div>
          <div>
            <label class="field-label">${t('common.count')}</label>
            <input id="ci-give-qty" type="number" value="1" min="1" max="64" class="input input-sm w-full input-bordered">
          </div>
        </div>
        <div class="mt-4 flex justify-end gap-2">
          <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
          <button class="btn-cg btn-cg-primary" onclick="window._ci.giveItem(document.getElementById('ci-give-item-id').value)">${t('common.give')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>
  </div>`;
}
