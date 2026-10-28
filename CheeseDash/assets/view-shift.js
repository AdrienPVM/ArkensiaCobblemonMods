// ── CobbleShift ──────────────────────────────────────────────────────────────

async function buildShift(app) {
  // Fetch data in parallel
  let transforms = [], abilities = [], config = {}, onlinePlayers = [], disabledPlayers = [];
  try {
    [transforms, abilities, config, onlinePlayers, disabledPlayers] = await Promise.all([
      API.shift.transforms().then(r => Array.isArray(r) ? r : (r && r.players ? r.players : [])).catch(() => []),
      API.shift.abilities().catch(() => []),
      API.shift.config().catch(() => ({})),
      API.players.list().catch(() => []),
      API.shift.abilityDisabled().catch(() => []),
    ]);
  } catch (e) {
    app.toast(e.message, 'error');
  }

  const canWrite = API.canWrite();
  const canAdmin = API.canAdmin();

  // Persist tab across rebuilds
  const savedTab = (window._sh && window._sh.tab) ? window._sh.tab : 'transforms';
  const savedSpecies = (window._sh && window._sh._speciesResult) ? window._sh._speciesResult : null;
  const savedSearch = (window._sh && window._sh.speciesSearch) ? window._sh.speciesSearch : '';

  // Local state
  window._sh = {
    app,
    tab: savedTab,
    transforms, abilities, config, onlinePlayers, disabledPlayers,
    speciesSearch: savedSearch,
    _speciesResult: savedSpecies,

    switchTab(t) {
      this.tab = t;
      document.querySelectorAll('.sh-tab-content').forEach(el => el.style.display = 'none');
      document.querySelectorAll('#sh-tabs .detail-tab').forEach(el => el.classList.toggle('active', el.dataset.tab === t));
      const panel = document.getElementById('sh-tab-' + t);
      if (panel) panel.style.display = 'block';
    },

    async doTransform() {
      const nameEl = document.getElementById('sh-tf-player');
      const specEl = document.getElementById('sh-tf-species');
      if (!nameEl || !specEl) return;
      const name = nameEl.value.trim();
      const species = specEl.value.trim();
      if (!name) { app.toast(t('common.error'), 'warn'); nameEl.focus(); return; }
      if (!species) { app.toast(t('common.error'), 'warn'); specEl.focus(); return; }
      try {
        await API.shift.transform({ playerName: name, species });
        app.toast(t('common.success'), 'success');
        nameEl.value = ''; specEl.value = '';
        app.viewHtml = ''; app.navigate('shift');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async doRevert(playerName) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.shift.revert({ playerName });
        app.toast(t('common.success'), 'success');
        app.viewHtml = ''; app.navigate('shift');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async doRevertAll() {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.shift.revertAll();
        app.toast(t('common.success'), 'success');
        app.viewHtml = ''; app.navigate('shift');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async doForceAbility() {
      const nameEl = document.getElementById('sh-ab-player');
      const specEl = document.getElementById('sh-ab-species');
      if (!nameEl || !specEl) return;
      const name = nameEl.value.trim();
      const species = specEl.value.trim();
      if (!name) { app.toast(t('common.selectPlayer'), 'warn'); nameEl.focus(); return; }
      try {
        await API.shift.ability({ playerName: name, species: species || '' });
        app.toast(t('common.success'), 'success');
        nameEl.value = ''; specEl.value = '';
        app.viewHtml = ''; app.navigate('shift');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async doToggleGlobal() {
      const newState = !this.config.abilityGlobalEnabled;
      try {
        await API.shift.abilityGlobal({ enabled: newState });
        this.config.abilityGlobalEnabled = newState;
        app.toast(t('common.success'), 'success');
        app.viewHtml = ''; app.navigate('shift');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async doTogglePlayer(playerName) {
      try {
        const res = await API.shift.abilityToggle({ playerName });
        app.toast(t('common.success'), 'success');
        app.viewHtml = ''; app.navigate('shift');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async doLookupSpecies() {
      const name = this.speciesSearch.trim();
      if (!name) return;
      try {
        const info = await API.shift.speciesInfo(name);
        this._speciesResult = info;
        const container = document.getElementById('sh-species-result');
        if (container) container.innerHTML = _shSpeciesResultHtml(info);
      } catch (e) { app.toast(t('common.error'), 'error'); }
    },

    async saveConfig() {
      try {
        await API.shift.updateConfig(this.config);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    fillPlayer(inputId, name) {
      const el = document.getElementById(inputId);
      if (el) el.value = name;
      document.querySelectorAll('.sh-player-dropdown').forEach(d => d.style.display = 'none');
    },
  };

  // Player picker HTML helper
  const playerOptions = onlinePlayers.map(p => escHtml(p.name || p));
  function playerPickerHtml(inputId, placeholder) {
    const optBtns = playerOptions.length > 0
      ? `<div class="sh-player-dropdown" id="${inputId}-dd" style="display:none;position:absolute;top:100%;left:0;right:0;z-index:50;max-height:160px;overflow-y:auto;border-radius:0.375rem;border:1px solid var(--bg-border);background:var(--bg-elevated);box-shadow:0 4px 12px rgba(0,0,0,.3)">
          ${playerOptions.map(n => `<button type="button" class="block w-full text-left px-3 py-1.5 text-sm hover:bg-bg-border" style="color:var(--text-primary)" onclick="_sh.fillPlayer('${inputId}','${n}')">${n}</button>`).join('')}
        </div>`
      : '';
    return `<div class="relative flex-1">
      <input type="text" id="${inputId}" class="input input-sm input-bordered text-sm w-full" placeholder="${placeholder}"
             onfocus="document.getElementById('${inputId}-dd')&&(document.getElementById('${inputId}-dd').style.display='block')"
             onblur="setTimeout(()=>{const d=document.getElementById('${inputId}-dd');if(d)d.style.display='none'},150)">
      ${optBtns}
    </div>`;
  }

  // Build disabled player set for quick lookup
  const disabledUuids = new Set(disabledPlayers.map(d => d.uuid));

  // ── Transforms tab content ──
  const tfRows = transforms.map(t => {
    const dur = Math.round((Date.now() - t.transformedAt) / 60000);
    return `<tr>
      <td class="font-medium" style="color:var(--text-primary)">${escHtml(t.playerName)}</td>
      <td><div class="flex items-center gap-1"><img src="https://img.pokemondb.net/sprites/home/normal/${encodeURIComponent((t.species||'').toLowerCase().replace(/\s+/g,''))}.png" width="20" height="20" style="image-rendering:pixelated" onerror="this.style.display='none'"><span class="id-badge">${escHtml(t.species)}</span></div></td>
      <td style="color:var(--text-secondary)">${escHtml(t.type1)}${t.type2 ? ' / ' + escHtml(t.type2) : ''}</td>
      <td class="td-mono">${t.bst || '?'}</td>
      <td class="td-mono">${dur}min</td>
      <td>
        ${t.locked ? '<span style="color:var(--amber)">🔒</span> ' : ''}
        ${canWrite ? `<button class="btn-cg btn-cg-danger" style="font-size:0.7rem;padding:0.15rem 0.5rem" onclick="_sh.doRevert('${escHtml(t.playerName)}')">${t('shift.revert')}</button>` : ''}
      </td>
    </tr>`;
  }).join('');

  const tfContent = `
    ${canWrite ? `
    <div class="section-card mb-4">
      <div class="section-card-body">
        <div class="text-sm font-semibold mb-2" style="color:var(--text-heading)">${t('shift.transform')}</div>
        <div class="flex items-end gap-3 flex-wrap">
          <div class="flex-1 min-w-[140px]">
            <label class="text-xs text-muted block mb-1">${t('common.player')}</label>
            ${playerPickerHtml('sh-tf-player', t('common.playerNamePlaceholder'))}
          </div>
          <div class="flex-1 min-w-[140px]">
            <label class="text-xs text-muted block mb-1">${t('shift.speciesName')}</label>
            ${speciesPickerHtml('', {id:'sh-tf-species', placeholder:'Ex: pikachu, charizard...', fullWidth:true})}
          </div>
          <button class="btn-cg btn-cg-primary shrink-0" onclick="_sh.doTransform()">${t('shift.transform')}</button>
        </div>
      </div>
    </div>` : ''}
    <div class="flex items-center justify-between mb-3">
      <span class="text-muted text-sm">${transforms.length} ${t('shift.activeTransforms')}</span>
      ${canWrite && transforms.length > 0 ? `<button class="btn-cg btn-cg-danger" onclick="_sh.doRevertAll()">${t('shift.revertAll')}</button>` : ''}
    </div>
    ${transforms.length > 0 ? `
    <table class="cg-table">
      <thead><tr><th>${t('common.player')}</th><th>${t('shift.speciesName')}</th><th>${t('shift.types')}</th><th>${t('shift.bst')}</th><th>${t('common.duration')}</th><th>${t('common.actions')}</th></tr></thead>
      <tbody>${tfRows}</tbody>
    </table>` : `<div class="empty-state"><span class="empty-state-icon">🔄</span><span class="empty-state-text">${t('common.noResults')}</span></div>`}`;

  // ── Abilities tab content ──
  const globalEnabled = config.abilityGlobalEnabled !== false;
  const abRows = abilities.map(a => `<tr>
    <td class="font-medium" style="color:var(--text-primary)">${escHtml(a.playerName)}</td>
    <td><div class="flex items-center gap-1"><img src="https://img.pokemondb.net/sprites/home/normal/${encodeURIComponent((a.species||'').toLowerCase().replace(/\s+/g,''))}.png" width="20" height="20" style="image-rendering:pixelated" onerror="this.style.display='none'"><span class="id-badge">${escHtml(a.species)}</span></div></td>
    <td style="color:var(--text-secondary)">${escHtml(a.abilityName)}</td>
    <td style="color:var(--text-secondary)">${escHtml(a.type1)}${a.type2 ? ' / ' + escHtml(a.type2) : ''}</td>
    <td>${a.forced ? '<span style="color:var(--amber);font-size:0.75rem;font-weight:600">' + t('common.forced') + '</span>' : '<span style="color:var(--green);font-size:0.75rem">' + t('common.auto') + '</span>'}</td>
  </tr>`).join('');

  // Per-player toggle rows
  const toggleRows = playerOptions.map(name => {
    const p = onlinePlayers.find(pl => (pl.name || pl) === name);
    const uuid = p && p.uuid ? p.uuid : '';
    const isDisabled = uuid && disabledUuids.has(uuid);
    return `<tr>
      <td class="font-medium" style="color:var(--text-primary)">${name}</td>
      <td>${isDisabled
        ? '<span style="color:var(--red);font-size:0.75rem;font-weight:600">OFF</span>'
        : '<span style="color:var(--green);font-size:0.75rem">ON</span>'}</td>
      <td>${canWrite ? `<button class="btn-cg ${isDisabled ? 'btn-cg-success' : 'btn-cg-danger'}" style="font-size:0.7rem;padding:0.15rem 0.5rem" onclick="_sh.doTogglePlayer('${name}')">${isDisabled ? t('common.enabled') : t('common.disabled')}</button>` : ''}</td>
    </tr>`;
  }).join('');

  const abContent = `
    ${canWrite ? `
    <div class="flex items-center gap-3 mb-4">
      <span class="text-sm font-medium" style="color:var(--text-primary)">${t('shift.globalAbilities')} :</span>
      <span class="font-semibold" style="color:var(${globalEnabled ? '--green' : '--red'})">${globalEnabled ? 'ON' : 'OFF'}</span>
      ${canAdmin ? `<button class="btn-cg ${globalEnabled ? 'btn-cg-danger' : 'btn-cg-success'}" style="font-size:0.7rem;padding:0.15rem 0.5rem" onclick="_sh.doToggleGlobal()">${globalEnabled ? t('common.disabled') : t('common.enabled')}</button>` : ''}
    </div>` : ''}
    ${canWrite ? `
    <div class="section-card mb-4">
      <div class="section-card-body">
        <div class="text-sm font-semibold mb-2" style="color:var(--text-heading)">${t('shift.forceAbility')}</div>
        <div class="flex items-end gap-3 flex-wrap">
          <div class="flex-1 min-w-[140px]">
            <label class="text-xs text-muted block mb-1">${t('common.player')}</label>
            ${playerPickerHtml('sh-ab-player', t('common.playerNamePlaceholder'))}
          </div>
          <div class="flex-1 min-w-[140px]">
            <label class="text-xs text-muted block mb-1">${t('shift.speciesName')}</label>
            ${speciesPickerHtml('', {id:'sh-ab-species', placeholder:'Ex: pikachu (vide = retirer)', fullWidth:true})}
          </div>
          <button class="btn-cg btn-cg-primary shrink-0" onclick="_sh.doForceAbility()">${t('common.apply')}</button>
        </div>
      </div>
    </div>` : ''}
    <div class="flex items-center justify-between mb-3">
      <span class="text-muted text-sm">${abilities.length} ${t('shift.activeAbilities')} — ${t('shift.mode')} : ${config.abilityAutoMode ? t('shift.modeAuto') : t('shift.modeManual')}</span>
    </div>
    ${abilities.length > 0 ? `
    <table class="cg-table">
      <thead><tr><th>${t('common.player')}</th><th>${t('shift.speciesName')}</th><th>${t('shift.abilityType')}</th><th>${t('shift.types')}</th><th>${t('shift.abilityMode')}</th></tr></thead>
      <tbody>${abRows}</tbody>
    </table>` : `<div class="empty-state"><span class="empty-state-icon">⚡</span><span class="empty-state-text">${t('common.noResults')}</span></div>`}
    ${playerOptions.length > 0 ? `
    <div class="section-card mt-4">
      <div class="section-card-header"><span class="section-card-title">${t('shift.abilities')}</span></div>
      <table class="cg-table">
        <thead><tr><th>${t('common.player')}</th><th>${t('common.status')}</th><th>${t('common.action')}</th></tr></thead>
        <tbody>${toggleRows}</tbody>
      </table>
    </div>` : ''}`;

  // ── Species browser tab content ──
  const spContent = `
    <div class="flex items-center gap-2 mb-3">
      ${speciesPickerHtml(savedSearch, {id:'sh-sp-search', placeholder:"${t('shift.speciesName')}", width:260, oninput:"_sh.speciesSearch=this.value", onkeydown:"if(event.key==='Enter')_sh.doLookupSpecies()"})}
      <button class="btn-cg btn-cg-primary" onclick="_sh.doLookupSpecies()">${t('common.search')}</button>
    </div>
    <p class="text-muted text-sm mb-2">${t('shift.species')}</p>
    <div id="sh-species-result">${savedSpecies ? _shSpeciesResultHtml(savedSpecies) : ''}</div>`;

  // ── Config tab content ──
  const c = config;
  const cfContent = `
    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('shift.transforms')}</span></div>
        <div class="section-card-body space-y-3">
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.autoRevert')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.autoRevertOnDeath ? 'checked' : ''}
                   onchange="_sh.config.autoRevertOnDeath=this.checked">
          </label>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.speedEnabled')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.transformSpeedEnabled ? 'checked' : ''}
                   onchange="_sh.config.transformSpeedEnabled=this.checked">
          </label>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.healthEnabled')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.transformHealthEnabled ? 'checked' : ''}
                   onchange="_sh.config.transformHealthEnabled=this.checked">
          </label>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.scaleEnabled')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.transformScaleEnabled ? 'checked' : ''}
                   onchange="_sh.config.transformScaleEnabled=this.checked">
          </label>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('shift.abilities')}</span></div>
        <div class="section-card-body space-y-3">
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.globalAbilities')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.abilityGlobalEnabled !== false ? 'checked' : ''}
                   onchange="_sh.config.abilityGlobalEnabled=this.checked">
          </label>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.abilityMode')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.abilityAutoMode ? 'checked' : ''}
                   onchange="_sh.config.abilityAutoMode=this.checked">
          </label>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.tickInterval')}</span>
            <input type="number" class="input input-sm input-bordered w-24 text-sm" value="${c.abilityTickInterval || 100}"
                   onchange="_sh.config.abilityTickInterval=+this.value" min="20" max="1200">
          </label>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.effectDuration')}</span>
            <input type="number" class="input input-sm input-bordered w-24 text-sm" value="${c.abilityEffectDuration || 160}"
                   onchange="_sh.config.abilityEffectDuration=+this.value" min="40" max="2400">
          </label>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('shift.attacks')}</span></div>
        <div class="section-card-body space-y-3">
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.attackMultiplier')}</span>
            <input type="number" class="input input-sm input-bordered w-24 text-sm" value="${c.attackDamageMultiplier || 1.0}"
                   onchange="_sh.config.attackDamageMultiplier=+this.value" min="0.1" max="5.0" step="0.1">
          </label>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.cooldownTime')}</span>
            <input type="number" class="input input-sm input-bordered w-24 text-sm" value="${c.attackCooldownMultiplier || 1.0}"
                   onchange="_sh.config.attackCooldownMultiplier=+this.value" min="0.1" max="5.0" step="0.1">
          </label>
          <p class="text-xs text-muted mt-1">${t('tip.shift.abilities')}</p>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('shift.limits')}</span></div>
        <div class="section-card-body space-y-3">
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.maxDuration')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.transformMaxDurationEnabled ? 'checked' : ''}
                   onchange="_sh.config.transformMaxDurationEnabled=this.checked">
          </label>
          <label class="flex items-center justify-between ${c.transformMaxDurationEnabled ? '' : 'opacity-50'}">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.maxDuration')} (s)</span>
            <input type="number" class="input input-sm input-bordered w-24 text-sm" value="${c.transformMaxDurationSeconds || 300}"
                   onchange="_sh.config.transformMaxDurationSeconds=+this.value" min="10" max="3600"
                   ${c.transformMaxDurationEnabled ? '' : 'disabled'}>
          </label>
          <div class="section-divider"></div>
          <label class="flex items-center justify-between">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.cooldownTime')}</span>
            <input type="checkbox" class="toggle toggle-sm" ${c.transformCooldownEnabled ? 'checked' : ''}
                   onchange="_sh.config.transformCooldownEnabled=this.checked">
          </label>
          <label class="flex items-center justify-between ${c.transformCooldownEnabled ? '' : 'opacity-50'}">
            <span class="text-sm" style="color:var(--text-primary)">${t('shift.cooldownTime')} (s)</span>
            <input type="number" class="input input-sm input-bordered w-24 text-sm" value="${c.transformCooldownSeconds || 60}"
                   onchange="_sh.config.transformCooldownSeconds=+this.value" min="5" max="3600"
                   ${c.transformCooldownEnabled ? '' : 'disabled'}>
          </label>
          <p class="text-xs text-muted mt-1">${t('tip.shift.cooldown')}</p>
        </div>
      </div>
    </div>

    ${canWrite ? `<div id="sh-cfg-dirty" class="mt-4 text-right text-xs" style="color:var(--amber);display:none">${t('common.save')}...</div>` : ''}`;

  // Tab bar
  const tabDefs = [
    { id: 'transforms', label: '🔄 ' + t('shift.tabTransforms'), count: transforms.length },
    { id: 'abilities', label: '⚡ ' + t('shift.tabAbilities'), count: abilities.length },
    { id: 'species', label: '📖 ' + t('shift.tabSpecies') },
    { id: 'config', label: '⚙ ' + t('common.config') },
  ];
  const tabBar = tabDefs.map(td =>
    `<button data-tab="${td.id}" class="detail-tab ${savedTab === td.id ? 'active' : ''}" onclick="_sh.switchTab('${td.id}')">
      ${td.label}${td.count !== undefined ? ` <span class="ml-1 opacity-60">(${td.count})</span>` : ''}
    </button>`
  ).join('');

  // Auto-save event delegation for config tab
  setTimeout(() => {
    const cfgTab = document.getElementById('sh-tab-config');
    if (cfgTab) {
      const autoSave = debounce(() => { if (window._sh) window._sh.saveConfig(); }, 1500);
      cfgTab.addEventListener('input', autoSave);
      cfgTab.addEventListener('change', autoSave);
    }
  }, 100);

  return `
  <div class="max-w-6xl mx-auto slide-in">
    <div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">CobbleShift</span>
      </div>
      <div id="sh-tabs" class="detail-tabs mb-4 px-4">${tabBar}</div>
      <div class="section-card-body">
        <div id="sh-tab-transforms" class="sh-tab-content" style="display:${savedTab === 'transforms' ? 'block' : 'none'}">${tfContent}</div>
        <div id="sh-tab-abilities" class="sh-tab-content" style="display:${savedTab === 'abilities' ? 'block' : 'none'}">${abContent}</div>
        <div id="sh-tab-species" class="sh-tab-content" style="display:${savedTab === 'species' ? 'block' : 'none'}">${spContent}</div>
        <div id="sh-tab-config" class="sh-tab-content" style="display:${savedTab === 'config' ? 'block' : 'none'}">${cfContent}</div>
      </div>
    </div>
  </div>`;
}

function _shSpeciesResultHtml(sr) {
  if (!sr) return '';
  const bstTier = sr.bst >= 450 ? t('shift.bstTier3') : sr.bst >= 350 ? t('shift.bstTier2') : t('shift.bstTier1');
  return `
    <div class="section-card mt-3">
      <div class="section-card-header"><span class="section-card-title flex items-center gap-2"><img src="https://img.pokemondb.net/sprites/home/normal/${encodeURIComponent((sr.name||'').toLowerCase().replace(/\s+/g,''))}.png" width="28" height="28" style="image-rendering:pixelated" onerror="this.style.display='none'">${escHtml(sr.name)}</span></div>
      <div class="section-card-body">
        <div class="grid grid-cols-2 md:grid-cols-3 gap-3 mb-3">
          <div class="stat-card"><span class="stat-label">${t('shift.statType1')}</span><span class="stat-value">${escHtml(sr.type1)}</span></div>
          ${sr.type2 ? `<div class="stat-card"><span class="stat-label">${t('shift.statType2')}</span><span class="stat-value">${escHtml(sr.type2)}</span></div>` : ''}
          <div class="stat-card"><span class="stat-label">${t('shift.statHp')}</span><span class="stat-value">${sr.hp}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statAttack')}</span><span class="stat-value">${sr.atk}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statDefense')}</span><span class="stat-value">${sr.def}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statSpAtk')}</span><span class="stat-value">${sr.spAtk}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statSpDef')}</span><span class="stat-value">${sr.spDef}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statSpeed')}</span><span class="stat-value">${sr.speed}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statHeight')}</span><span class="stat-value">${sr.height}m</span></div>
        </div>
        <div class="section-divider"></div>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3">
          <div class="stat-card"><span class="stat-label">${t('shift.statBst')}</span><span class="stat-value">${sr.bst || '?'}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statMcSpeed')}</span><span class="stat-value">${sr.mappedSpeed.toFixed(3)}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statMcHealth')}</span><span class="stat-value">${sr.mappedHealth.toFixed(1)}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statScale')}</span><span class="stat-value">${sr.mappedScale.toFixed(2)}x</span></div>
        </div>
        <div class="section-divider mt-3"></div>
        <div class="grid grid-cols-2 md:grid-cols-3 gap-3 mt-3">
          <div class="stat-card"><span class="stat-label">${t('shift.statAtkDamage')}</span><span class="stat-value">${sr.attackDamage != null ? sr.attackDamage.toFixed(1) : '?'}</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statAtkCooldown')}</span><span class="stat-value">${sr.attackCooldown || '?'} ticks</span></div>
          <div class="stat-card"><span class="stat-label">${t('shift.statProjectiles')}</span><span class="stat-value">${sr.projectileCount || '?'} <span class="text-xs text-muted">(${bstTier})</span></span></div>
        </div>
        <div class="mt-3">
          <span class="text-muted text-sm">${t('shift.abilityLabel')} : </span>
          <span class="font-medium" style="color:var(--text-primary)">${escHtml(sr.ability1Name)}</span>
          ${sr.ability2Name ? ` + <span class="font-medium" style="color:var(--text-primary)">${escHtml(sr.ability2Name)}</span>` : ''}
        </div>
      </div>
    </div>`;
}
