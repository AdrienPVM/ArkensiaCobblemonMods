// ── CobblemonDungeons ────────────────────────────────────────────────────────

async function buildDungeons(app) {
  let configs, activeData, stats;
  try {
    [configs, activeData, stats] = await Promise.all([
      API.dungeons.configs(),
      API.dungeons.active(),
      API.dungeons.stats(),
    ]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">🏰</span><span class="empty-state-text">${t('error.prefix')}${escHtml(e.message)}</span></div>`;
  }

  const instances = activeData.instances || [];
  const cw = API.canWrite();

  if (!window._dg) window._dg = {};
  window._dg.refresh = async () => {
    app.viewHtml = await buildDungeons(app);
  };

  // ── Stat cards
  const statCards = `
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
      <div class="stat-card">
        <div class="stat-icon">🏰</div>
        <div class="stat-value">${configs.length}</div>
        <div class="stat-label">${t('dungeons.configs')}</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">⚔️</div>
        <div class="stat-value">${instances.length}</div>
        <div class="stat-label">${t('dungeons.activeRuns')}</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">📊</div>
        <div class="stat-value">${stats.successRate || 0}%</div>
        <div class="stat-label">${t('dungeons.stats')}</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">🕐</div>
        <div class="stat-value">${stats.totalRuns || 0}</div>
        <div class="stat-label">${t('dungeons.totalRuns')}</div>
      </div>
    </div>`;

  // ── Active runs
  let activeHtml = '';
  if (instances.length > 0) {
    const activeRows = instances.map(inst => {
      const playerNames = (inst.players || []).map(p => escHtml(p.name)).join(', ');
      const timeLeft = inst.timeLeft > 0 ? Math.floor(inst.timeLeft / 20) + 's' : '—';
      const progressParts = [];
      if (inst.alphasRequired > 0) progressParts.push(`⚔${inst.alphasDefeated}/${inst.alphasRequired}`);
      if (inst.gemsRequired > 0) progressParts.push(`💎${inst.gemsFound}/${inst.gemsRequired}`);
      if (inst.guardiansRequired > 0) progressParts.push(`🛡${inst.guardiansDefeated}/${inst.guardiansRequired}`);
      if (inst.stakesRequired > 0) progressParts.push(`🗡${inst.stakesFound}/${inst.stakesRequired}`);
      const progress = progressParts.join(' ') || '—';
      const bossStatus = inst.bossDefeated ? '<span style="color:var(--green)">✓ ' + t('dungeons.defeated') + '</span>'
        : inst.bossSpawned ? `<span style="color:var(--red)">⚔ ${escHtml(inst.bossSpecies || 'Boss')}</span>`
        : '<span style="color:var(--muted)">—</span>';
      const firstPlayer = inst.players?.[0];
      const failBtn = cw && firstPlayer ? `<button class="btn-cg btn-cg-danger text-xs" onclick="(async()=>{if(await confirmAction(t('common.deleteConfirm'))){try{await API.dungeons.failRun('${firstPlayer.uuid}');window._dg.refresh();}catch(e){_toast(e.message,'error')}}})()">${t('dungeons.fail')}</button>` : '';
      return `<tr>
        <td><span class="font-medium">${escHtml(inst.dungeonName)}</span></td>
        <td><span class="id-badge">T${inst.tier}</span></td>
        <td>${playerNames}</td>
        <td class="td-mono">${timeLeft}</td>
        <td>${inst.lives}</td>
        <td class="text-xs">${progress}</td>
        <td>${bossStatus}</td>
        <td>${failBtn}</td>
      </tr>`;
    }).join('');

    activeHtml = `
      <div class="section-card mb-4">
        <div class="section-card-header">
          <span class="section-card-title">⚔️ ${t('dungeons.activeRuns')} (${instances.length})</span>
        </div>
        <div class="section-card-body p-0">
          <table class="cg-table"><thead><tr>
            <th>${t('dungeons.dungeon')}</th><th>${t('dungeons.tier')}</th><th>${t('common.players')}</th><th>${t('dungeons.time')}</th><th>${t('dungeons.lives')}</th><th>${t('dungeons.progression')}</th><th>${t('dungeons.boss')}</th><th></th>
          </tr></thead><tbody>${activeRows}</tbody></table>
        </div>
      </div>`;
  }

  // ── Dungeon configs table
  const configRows = configs.map(c => {
    const activeInst = c.activeInstances || 0;
    const activeBadge = activeInst > 0 ? `<span class="status-dot s-online"></span> ${activeInst}` : '<span style="color:var(--muted)">—</span>';
    return `<tr style="cursor:pointer" onclick="_nav('dungeons:${escHtml(c.id)}')">
      <td><span class="id-badge">${escHtml(c.id)}</span></td>
      <td class="font-medium">${escHtml(c.name)}</td>
      <td><span class="id-badge">T${c.tier}</span></td>
      <td>${c.slotsTotal}</td>
      <td>${activeBadge}</td>
      <td class="td-mono">${c.teleportMode === 'SAME_SERVER' ? t('dungeons.teleportLocal') : t('dungeons.teleportCross')}</td>
      <td>${Math.floor((c.timeLimitSeconds || 1800) / 60)}min</td>
      <td>
        ${cw ? `<button class="btn-cg btn-cg-danger text-xs" onclick="event.stopPropagation();(async()=>{if(await confirmAction(t('common.deleteConfirm'))){try{await API.dungeons.deleteConfig('${escHtml(c.id)}');window._dg.refresh();}catch(e){_toast(e.message,'error')}}})()">${t('common.delete')}</button>` : ''}
      </td>
    </tr>`;
  }).join('');

  const createBtn = cw ? `<button class="btn-cg btn-cg-primary text-xs" onclick="document.getElementById('dg-create-modal').showModal()">${t('dungeons.newDungeon')}</button>` : '';
  const reloadBtn = cw ? `<button class="btn-cg btn-cg-ghost text-xs ml-2" onclick="(async()=>{try{await API.dungeons.reload();_toast(t('common.success'),'success');window._dg.refresh();}catch(e){_toast(e.message,'error')}})()">↻ ${t('common.reload')}</button>` : '';

  const createModal = cw ? `
    <dialog id="dg-create-modal" class="modal">
      <div class="modal-box max-w-md">
        <h3 class="text-sm font-semibold mb-4" style="color:var(--text-heading)">${t('dungeons.newDungeon')}</h3>
        <div class="space-y-3">
          <div class="field-group">
            <label class="field-label">${t('dungeons.uniqueId')}</label>
            <input id="dg-new-id" type="text" placeholder="mon_donjon" class="input input-sm w-full input-bordered">
            <span class="field-help">${t('dungeons.idHelp')}</span>
          </div>
          <div class="field-group">
            <label class="field-label">${t('common.displayName')}</label>
            <input id="dg-new-name" type="text" placeholder="Mon Donjon" class="input input-sm w-full input-bordered">
          </div>
          <div class="field-group">
            <label class="field-label">${t('dungeons.mode')}</label>
            <select id="dg-new-mode" class="select select-sm w-full select-bordered">
              <option value="SAME_SERVER">SAME_SERVER (local)</option>
              <option value="CROSS_SERVER">CROSS_SERVER</option>
            </select>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <button class="btn-cg btn-cg-ghost text-xs" onclick="document.getElementById('dg-create-modal').close()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-primary text-xs" onclick="(async()=>{
            const id=document.getElementById('dg-new-id').value.trim();
            const name=document.getElementById('dg-new-name').value.trim();
            const mode=document.getElementById('dg-new-mode').value;
            if(!id){_toast(t('dungeons.idRequired'),'error');return}
            try{
              await API.dungeons.createConfig({id,name:name||id,teleportMode:mode});
              document.getElementById('dg-create-modal').close();
              _nav('dungeons:'+id);
            }catch(e){_toast(e.message,'error')}
          })()">${t('common.create')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>` : '';

  const configTable = `
    <div class="section-card mb-4">
      <div class="section-card-header">
        <span class="section-card-title">🏰 ${t('dungeons.configs')} (${configs.length})</span>
        <div>${createBtn}${reloadBtn}</div>
      </div>
      <div class="section-card-body p-0">
        ${configs.length === 0 ? `<div class="empty-state"><span class="empty-state-icon">🏰</span><span class="empty-state-text">${t('common.noResults')}</span></div>` : `
          <table class="cg-table"><thead><tr>
            <th>${t('common.id')}</th><th>${t('common.name')}</th><th>${t('dungeons.tier')}</th><th>${t('dungeons.slots')}</th><th>${t('common.active')}</th><th>${t('dungeons.mode')}</th><th>${t('common.duration')}</th><th></th>
          </tr></thead><tbody>${configRows}</tbody></table>`}
      </div>
    </div>
    ${createModal}`;

  // ── History section (last 10 runs)
  let historyHtml = '';
  try {
    const histData = await API.dungeons.history(10);
    const runs = histData.history || [];
    if (runs.length > 0) {
      const histRows = runs.map(r => {
        const dur = r.durationSeconds || (r.endTime - r.startTime);
        const durStr = dur > 60 ? Math.floor(dur / 60) + 'min ' + (dur % 60) + 's' : dur + 's';
        const statusBadge = r.success
          ? '<span style="color:var(--green)">✓ ' + t('dungeons.completed') + '</span>'
          : '<span style="color:var(--red)">✗ ' + t('dungeons.failed') + '</span>';
        const date = I18N.fmtDate(r.startTime * 1000);
        return `<tr>
          <td class="td-mono text-xs">${date}</td>
          <td>${escHtml(r.dungeonName || r.dungeonId)}</td>
          <td>T${r.tier}</td>
          <td>${(r.players || []).join(', ')}</td>
          <td>${statusBadge}</td>
          <td class="td-mono">${durStr}</td>
          <td>${r.deathCount || 0}</td>
        </tr>`;
      }).join('');
      historyHtml = `
        <div class="section-card">
          <div class="section-card-header">
            <span class="section-card-title">📜 ${t('dungeons.history')} (${histData.total})</span>
          </div>
          <div class="section-card-body p-0">
            <table class="cg-table"><thead><tr>
              <th>${t('common.date')}</th><th>${t('dungeons.title')}</th><th>${t('dungeons.tier')}</th><th>${t('common.players')}</th><th>${t('dungeons.result')}</th><th>${t('common.duration')}</th><th>${t('dungeons.deaths')}</th>
            </tr></thead><tbody>${histRows}</tbody></table>
          </div>
        </div>`;
    }
  } catch (e) { /* ignore history fetch errors */ }

  return `<div class="max-w-6xl mx-auto slide-in">
    ${statCards}
    ${activeHtml}
    ${configTable}
    ${historyHtml}
  </div>`;
}

// ── Dungeon Detail view ─────────────────────────────────────────────────────

// Input helper for dungeon detail — returns full attribute string, with optional extra styles
function _dgInp(extra) {
  return `class="input input-sm input-bordered w-full"` + (extra ? ` style="${extra}"` : '');
}
function _dgSel() {
  return `class="select select-sm w-full select-bordered"`;
}

function _dgSpeciesInput(val, oninput, w) {
  const low = (val || '').toLowerCase().replace(/\s+/g, '');
  const cw = API.canWrite();
  return `<div class="flex items-center gap-1" style="min-width:${w}px">
    <img src="https://img.pokemondb.net/sprites/home/normal/${encodeURIComponent(low)}.png" width="24" height="24"
      style="image-rendering:pixelated;flex-shrink:0" onerror="this.style.visibility='hidden'" onload="this.style.visibility='visible'">
    <input type="text" list="dg-species-dl" value="${escHtml(val || '')}" ${_dgInp('width:' + (w - 30) + 'px')}
      ${cw ? `oninput="${oninput};_dg.markDirty();var _i=this.parentElement.querySelector('img');if(_i){_i.src='https://img.pokemondb.net/sprites/home/normal/'+encodeURIComponent(this.value.toLowerCase().replace(/\\s+/g,''))+'.png';_i.style.visibility='visible'}"` : 'disabled'}>
  </div>`;
}

function _dgItemInput(val, oninput, w) {
  const cw = API.canWrite();
  return `<div class="flex items-center gap-1" style="min-width:${w}px">
    ${mkItemImg(val, 22)}
    <input type="text" list="dg-items-dl" value="${escHtml(val || '')}" ${_dgInp('width:' + (w - 30) + 'px')}
      ${cw ? `oninput="${oninput};_dg.markDirty();var _i=this.parentElement.querySelector('img');if(_i){_i.src=mkItemIconUrl(this.value);_i.style.display=''}"` : 'disabled'}>
  </div>`;
}

function _dgPosFields(prefix, pos, path, label) {
  const p = pos || { x: 0, y: 100, z: 0, dimension: 'minecraft:overworld' };
  const cw = API.canWrite();
  return `<div class="flex items-end gap-2">
    <div class="grid grid-cols-4 gap-2 flex-1">
      <div class="field-group"><label class="field-label">X</label>
        <input id="dg-pos-${prefix}-x" type="number" step="0.5" value="${p.x}" ${_dgInp()} ${cw ? `oninput="${path}.x=parseFloat(this.value)||0;_dg.markDirty()"` : 'disabled'}></div>
      <div class="field-group"><label class="field-label">Y</label>
        <input id="dg-pos-${prefix}-y" type="number" step="0.5" value="${p.y}" ${_dgInp()} ${cw ? `oninput="${path}.y=parseFloat(this.value)||0;_dg.markDirty()"` : 'disabled'}></div>
      <div class="field-group"><label class="field-label">Z</label>
        <input id="dg-pos-${prefix}-z" type="number" step="0.5" value="${p.z}" ${_dgInp()} ${cw ? `oninput="${path}.z=parseFloat(this.value)||0;_dg.markDirty()"` : 'disabled'}></div>
      <div class="field-group"><label class="field-label">${t('dungeons.dimension')}</label>
        <input type="text" list="dg-dims-dl" value="${escHtml(p.dimension || 'minecraft:overworld')}" ${_dgInp()} ${cw ? `oninput="${path}.dimension=this.value;_dg.markDirty()"` : 'disabled'}></div>
    </div>
    ${cw ? `<button class="btn-cg btn-cg-ghost shrink-0 mb-1" onclick="_dgGrabPos('${prefix}','${path}')" title="${t('dungeons.grabPosition')}">📍</button>` : ''}
  </div>`;
}

async function _dgGrabPos(prefix, path) {
  try {
    const pos = await API.admin.getPosition();
    if (!pos.online) { _toast(t('error.network'), 'warn'); return; }
    const setPath = new Function('v', path + '.x=v.x;' + path + '.y=v.y;' + path + '.z=v.z');
    setPath(pos);
    const ix = document.getElementById('dg-pos-' + prefix + '-x'); if (ix) ix.value = pos.x;
    const iy = document.getElementById('dg-pos-' + prefix + '-y'); if (iy) iy.value = pos.y;
    const iz = document.getElementById('dg-pos-' + prefix + '-z'); if (iz) iz.value = pos.z;
    _dg.markDirty();
    _toast(t('dungeons.positionGrabbed') + pos.x + ', ' + pos.y + ', ' + pos.z, 'success');
  } catch (e) { _toast('' + e.message, 'error'); }
}

function dgSwitchTab(tabId, btn) { switchDetailTab(tabId, btn, 'dg'); }

function _dgRenderLocations() {
  const el = document.getElementById('dg-addloc-list');
  if (!el) return;
  const locs = _dg.edit.additionalLocations || [];
  if (locs.length === 0) {
    el.innerHTML = '<div class="text-xs py-2" style="color:var(--muted)">' + t('common.none') + '</div>';
    return;
  }
  el.innerHTML = locs.map((al, i) => {
    const base = `_dg.edit.additionalLocations[${i}]`;
    return `<div class="p-3 mb-2" style="border:1px solid var(--bg-border);border-radius:8px">
      <div class="flex items-center justify-between mb-2">
        <span class="text-xs font-medium" style="color:var(--amber)">${t('dungeons.slot')} ${i + 2}</span>
        <button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.additionalLocations.splice(${i},1);_dg.markDirty();_dgRenderLocations()">${t('common.delete')}</button>
      </div>
      <div class="space-y-2">
        <div><span class="text-xs" style="color:var(--muted)">${t('dungeons.posSpawn')}</span>${_dgPosFields('al'+i+'s', al.spawnPos, base+'.spawnPos')}</div>
        <div><span class="text-xs" style="color:var(--muted)">${t('dungeons.posHub')}</span>${_dgPosFields('al'+i+'h', al.hubPos, base+'.hubPos')}</div>
        <div><span class="text-xs" style="color:var(--muted)">${t('dungeons.posBossRoom')}</span>${_dgPosFields('al'+i+'b', al.bossRoomPos, base+'.bossRoomPos')}</div>
        <div><span class="text-xs" style="color:var(--muted)">${t('dungeons.posReturn')}</span>${_dgPosFields('al'+i+'r', al.returnPos, base+'.returnPos')}</div>
      </div>
    </div>`;
  }).join('');
}

function _dgRenderTierLevels() {
  const el = document.getElementById('dg-tiers-list');
  if (!el) return;
  const tiers = _dg.edit.scaling.tierLevels || [];
  if (tiers.length === 0) { el.innerHTML = '<tr><td colspan="3" class="text-center text-muted">' + t('common.none') + '</td></tr>'; return; }
  el.innerHTML = tiers.map((t, i) => `<tr>
    <td><input type="number" value="${t.tier}" ${_dgInp('width:70px')}
      oninput="_dg.edit.scaling.tierLevels[${i}].tier=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${t.level}" ${_dgInp('width:70px')}
      oninput="_dg.edit.scaling.tierLevels[${i}].level=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.scaling.tierLevels.splice(${i},1);_dg.markDirty();_dgRenderTierLevels()">×</button></td>
  </tr>`).join('');
}

function _dgRenderAlphaPool() {
  const el = document.getElementById('dg-alphas-list');
  if (!el) return;
  const pool = _dg.edit.spawning.alphaPool || [];
  if (pool.length === 0) { el.innerHTML = '<tr><td colspan="6" class="text-center text-muted">' + t('common.none') + '</td></tr>'; return; }
  el.innerHTML = pool.map((a, i) => `<tr>
    <td>${_dgSpeciesInput(a.species, "_dg.edit.spawning.alphaPool[" + i + "].species=this.value", 160)}</td>
    <td><input type="number" value="${a.tier}" ${_dgInp('width:70px')}
      oninput="_dg.edit.spawning.alphaPool[${i}].tier=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${a.minLevel}" ${_dgInp('width:70px')}
      oninput="_dg.edit.spawning.alphaPool[${i}].minLevel=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${a.maxLevel}" ${_dgInp('width:70px')}
      oninput="_dg.edit.spawning.alphaPool[${i}].maxLevel=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${a.weight}" ${_dgInp('width:70px')}
      oninput="_dg.edit.spawning.alphaPool[${i}].weight=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.spawning.alphaPool.splice(${i},1);_dg.markDirty();_dgRenderAlphaPool()">×</button></td>
  </tr>`).join('');
}

function _dgRenderGuardianPool() {
  const el = document.getElementById('dg-guardians-list');
  if (!el) return;
  const pool = _dg.edit.spawning.guardianPool || [];
  if (pool.length === 0) { el.innerHTML = '<tr><td colspan="5" class="text-center text-muted">' + t('common.none') + '</td></tr>'; return; }
  el.innerHTML = pool.map((g, i) => `<tr>
    <td><input type="text" list="dg-trainers-dl" value="${escHtml(g.trainerId)}" ${_dgInp('width:160px')}
      oninput="_dg.edit.spawning.guardianPool[${i}].trainerId=this.value;_dg.markDirty()"></td>
    <td><input type="number" value="${g.tier}" ${_dgInp('width:70px')}
      oninput="_dg.edit.spawning.guardianPool[${i}].tier=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${g.weight}" ${_dgInp('width:70px')}
      oninput="_dg.edit.spawning.guardianPool[${i}].weight=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${g.level || 0}" ${_dgInp('width:70px')}
      oninput="_dg.edit.spawning.guardianPool[${i}].level=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.spawning.guardianPool.splice(${i},1);_dg.markDirty();_dgRenderGuardianPool()">×</button></td>
  </tr>`).join('');
}

function _dgRenderBossPool() {
  const el = document.getElementById('dg-boss-list');
  if (!el) return;
  const pool = _dg.edit.boss.pool || [];
  if (pool.length === 0) { el.innerHTML = '<tr><td colspan="9" class="text-center text-muted">' + t('common.none') + '</td></tr>'; return; }
  el.innerHTML = pool.map((b, i) => `<tr>
    <td>${_dgSpeciesInput(b.species, "_dg.edit.boss.pool[" + i + "].species=this.value", 160)}</td>
    <td><input type="number" value="${b.tier}" ${_dgInp('width:70px')}
      oninput="_dg.edit.boss.pool[${i}].tier=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${b.level}" ${_dgInp('width:70px')}
      oninput="_dg.edit.boss.pool[${i}].level=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${b.scale || 4.0}" step="0.1" ${_dgInp('width:75px')}
      oninput="_dg.edit.boss.pool[${i}].scale=parseFloat(this.value)||4.0;_dg.markDirty()"></td>
    <td><input type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${b.isShiny ? 'checked' : ''} onchange="_dg.edit.boss.pool[${i}].isShiny=this.checked;_dg.markDirty()"></td>
    <td><input type="number" value="${b.weight}" ${_dgInp('width:70px')}
      oninput="_dg.edit.boss.pool[${i}].weight=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="number" value="${b.rewardChance || 100}" step="1" ${_dgInp('width:70px')}
      oninput="_dg.edit.boss.pool[${i}].rewardChance=parseFloat(this.value)||100;_dg.markDirty()"></td>
    <td><input type="text" value="${escHtml(b.customName || '')}" ${_dgInp('width:140px')}
      oninput="_dg.edit.boss.pool[${i}].customName=this.value;_dg.markDirty()"></td>
    <td><button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.boss.pool.splice(${i},1);_dg.markDirty();_dgRenderBossPool()">×</button></td>
  </tr>`).join('');
}

function _dgRenderLootPools() {
  const el = document.getElementById('dg-loot-list');
  if (!el) return;
  const pools = _dg.edit.lootPools || [];
  if (pools.length === 0) { el.innerHTML = '<div class="empty-state py-4"><span class="empty-state-text">' + t('common.noResults') + '</span></div>'; return; }
  el.innerHTML = pools.map((pool, pi) => {
    const items = pool.items || [];
    const itemRows = items.map((item, ii) => `<tr>
      <td>${_dgItemInput(item.item, "_dg.edit.lootPools[" + pi + "].items[" + ii + "].item=this.value", 190)}</td>
      <td><input type="number" value="${item.min}" ${_dgInp('width:65px')}
        oninput="_dg.edit.lootPools[${pi}].items[${ii}].min=parseInt(this.value)||1;_dg.markDirty()"></td>
      <td><input type="number" value="${item.max}" ${_dgInp('width:65px')}
        oninput="_dg.edit.lootPools[${pi}].items[${ii}].max=parseInt(this.value)||1;_dg.markDirty()"></td>
      <td><input type="number" value="${item.weight}" ${_dgInp('width:65px')}
        oninput="_dg.edit.lootPools[${pi}].items[${ii}].weight=parseInt(this.value)||1;_dg.markDirty()"></td>
      <td><input type="text" value="${escHtml(item.customName || '')}" ${_dgInp('width:130px')}
        oninput="_dg.edit.lootPools[${pi}].items[${ii}].customName=this.value;_dg.markDirty()"></td>
      <td><button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.lootPools[${pi}].items.splice(${ii},1);_dg.markDirty();_dgRenderLootPools()">×</button></td>
    </tr>`).join('');
    return `<div class="section-card mb-3">
      <div class="section-card-header">
        <div class="flex items-center gap-2 flex-1">
          <span class="section-card-title">${t('dungeons.poolNumber')}</span>
          <input type="number" value="${pool.id || pi + 1}" ${_dgInp('width:65px')}
            oninput="_dg.edit.lootPools[${pi}].id=parseInt(this.value)||1;_dg.markDirty()">
          <span class="text-xs" style="color:var(--muted)">${t('dungeons.tier')}</span>
          <input type="number" value="${pool.tier}" ${_dgInp('width:65px')}
            oninput="_dg.edit.lootPools[${pi}].tier=parseInt(this.value)||0;_dg.markDirty()">
          <span class="text-xs" style="color:var(--muted)">${t('dungeons.rolls')}</span>
          <input type="number" value="${pool.rolls}" ${_dgInp('width:65px')}
            oninput="_dg.edit.lootPools[${pi}].rolls=parseInt(this.value)||1;_dg.markDirty()">
        </div>
        <button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.lootPools.splice(${pi},1);_dg.markDirty();_dgRenderLootPools()">${t('common.delete')}</button>
      </div>
      <div class="section-card-body p-0">
        <table class="cg-table"><thead><tr><th>${t('common.item')}</th><th>${t('common.min')}</th><th>${t('common.max')}</th><th>${t('dungeons.weight')}</th><th>${t('dungeons.customName')}</th><th></th></tr></thead>
        <tbody>${itemRows || '<tr><td colspan="6" class="text-center text-muted">' + t('common.empty') + '</td></tr>'}</tbody></table>
      </div>
      <div class="p-2"><button class="btn-cg btn-cg-ghost text-xs" onclick="_dg.edit.lootPools[${pi}].items.push({item:'minecraft:diamond',customName:'',min:1,max:1,weight:10,nbt:''});_dg.markDirty();_dgRenderLootPools()">${t('common.add')}</button></div>
    </div>`;
  }).join('');
}

function _dgRenderCompletionRewards() {
  const el = document.getElementById('dg-rewards-list');
  if (!el) return;
  const rewards = _dg.edit.completionRewards || [];
  if (rewards.length === 0) { el.innerHTML = '<tr><td colspan="7" class="text-center text-muted">' + t('common.none') + '</td></tr>'; return; }
  el.innerHTML = rewards.map((r, i) => `<tr>
    <td>${_dgItemInput(r.item, "_dg.edit.completionRewards[" + i + "].item=this.value", 190)}</td>
    <td><input type="number" value="${r.min}" ${_dgInp('width:65px')}
      oninput="_dg.edit.completionRewards[${i}].min=parseInt(this.value)||1;_dg.markDirty()"></td>
    <td><input type="number" value="${r.max}" ${_dgInp('width:65px')}
      oninput="_dg.edit.completionRewards[${i}].max=parseInt(this.value)||1;_dg.markDirty()"></td>
    <td><input type="number" value="${r.chance}" step="1" ${_dgInp('width:70px')}
      oninput="_dg.edit.completionRewards[${i}].chance=parseFloat(this.value)||100;_dg.markDirty()"></td>
    <td><input type="number" value="${r.tier}" ${_dgInp('width:65px')}
      oninput="_dg.edit.completionRewards[${i}].tier=parseInt(this.value)||0;_dg.markDirty()"></td>
    <td><input type="text" value="${escHtml(r.customName || '')}" ${_dgInp('width:130px')}
      oninput="_dg.edit.completionRewards[${i}].customName=this.value;_dg.markDirty()"></td>
    <td><button class="btn-cg btn-cg-danger text-xs" onclick="_dg.edit.completionRewards.splice(${i},1);_dg.markDirty();_dgRenderCompletionRewards()">×</button></td>
  </tr>`).join('');
}

async function buildDungeonDetail(app, configId) {
  let config, activeData, histData, trainersList, dimsList;
  try {
    [config, activeData, histData, trainersList, dimsList] = await Promise.all([
      API.dungeons.config(configId),
      API.dungeons.active().catch(() => null),
      API.dungeons.history(100).catch(() => null),
      API.dungeons.trainers().catch(() => []),
      API.dimensions.list().catch(() => []),
    ]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">🏰</span><span class="empty-state-text">${t('dungeons.notFound')}${escHtml(configId)}</span></div>`;
  }

  const cw = API.canWrite();

  const _dg = window._dg = {
    config, configId, app, dirty: false,
    edit: JSON.parse(JSON.stringify(config)),
    _trainers: trainersList || [],
    _dims: dimsList || [],

    markDirty() {
      this.dirty = true;
      const el = document.getElementById('dg-dirty');
      if (el) el.style.display = '';
      this._debouncedSave();
    },

    _debouncedSave: debounce(function() { if (window._dg) window._dg.save(); }, 1500),

    async save() {
      try {
        const snapshot = JSON.parse(JSON.stringify(this.edit));
        // Reset dirty BEFORE the async call so new edits during save set it again
        this.dirty = false;
        const dirtyEl = document.getElementById('dg-dirty');
        if (dirtyEl) dirtyEl.style.display = 'none';

        const updated = await API.dungeons.updateConfig(this.configId, snapshot);
        this.config = updated;
        // Only sync edit from server if no new edits happened during the async save
        if (!this.dirty) {
          this.edit = JSON.parse(JSON.stringify(updated));
        }
        app.toast(t('common.success'), 'success');
      } catch (e) {
        // On failure, mark dirty again so next debounced save retries
        this.dirty = true;
        const dirtyEl = document.getElementById('dg-dirty');
        if (dirtyEl) dirtyEl.style.display = '';
        app.toast(e.message, 'error');
      }
    },
  };

  // Ensure nested objects exist in edit
  const e = _dg.edit;
  if (!e.general) e.general = {};
  if (!e.networking) e.networking = {};
  if (!e.requirements) e.requirements = {};
  if (!e.locations) e.locations = {};
  const defPos = () => ({ x: 0, y: 100, z: 0, dimension: 'minecraft:overworld' });
  if (!e.locations.spawnPos) e.locations.spawnPos = defPos();
  if (!e.locations.hubPos) e.locations.hubPos = defPos();
  if (!e.locations.bossRoomPos) e.locations.bossRoomPos = defPos();
  if (!e.locations.returnPos) e.locations.returnPos = defPos();
  if (!e.additionalLocations) e.additionalLocations = [];
  if (!e.scaling) e.scaling = {};
  if (!e.scaling.tierLevels) e.scaling.tierLevels = [];
  if (!e.spawning) e.spawning = {};
  if (!e.spawning.alphaPool) e.spawning.alphaPool = [];
  if (!e.spawning.guardianPool) e.spawning.guardianPool = [];
  if (!e.spawning.alphaSpawnerIds) e.spawning.alphaSpawnerIds = [];
  if (!e.spawning.guardianSpawnerIds) e.spawning.guardianSpawnerIds = [];
  if (!e.spawning.stakeSpawnerIds) e.spawning.stakeSpawnerIds = [];
  if (!e.spawning.portalIds) e.spawning.portalIds = [];
  if (!e.spawning.chestIds) e.spawning.chestIds = [];
  if (!e.spawning.guardianTrainerIds) e.spawning.guardianTrainerIds = [];
  if (!e.boss) e.boss = {};
  if (!e.boss.pool) e.boss.pool = [];
  if (!e.boss.bossSpawnPos) e.boss.bossSpawnPos = { x: 10, y: 100, z: 10, dimension: 'minecraft:overworld' };
  if (!e.lootPools) e.lootPools = [];
  if (!e.completionRewards) e.completionRewards = [];

  const g = e.general, n = e.networking, r = e.requirements, sc = e.scaling, sp = e.spawning, boss = e.boss;

  const teleportOpts = ['SAME_SERVER','CROSS_SERVER'].map(v =>
    `<option value="${v}"${(n.teleportMode||'SAME_SERVER')===v?' selected':''}>${v}</option>`
  ).join('');

  // ── Icon sprite (reuse market pattern) ──────────────────────────────
  const iconUrl = mkItemIconUrl(g.guiIcon || 'minecraft:paper');

  // ── All tabs content generated upfront, shown/hidden via data-tab-panel ──

  const tabs = [
    { id: 'general', label: t('zones.tabs.general') },
    { id: 'scaling', label: t('dungeons.tabs.scaling') },
    { id: 'boss',    label: t('dungeons.tabs.boss') },
    { id: 'loot',    label: t('dungeons.tabs.loot') },
    { id: 'active',  label: t('dungeons.tabs.active') },
    { id: 'stats',   label: t('dungeons.tabs.stats') },
  ];

  // ── General tab ─────────────────────────────────────────────────────────
  const generalTab = `<div data-tab-panel-dg="general">
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('common.information')}</span></div>
        <div class="section-card-body space-y-3">
          <div class="field-group">
            <label class="field-label">${t('common.id')}</label>
            <span class="id-badge">${escHtml(g.id)}</span>
          </div>
          <div class="field-group">
            <label class="field-label">${t('common.name')}</label>
            <input type="text" value="${escHtml(g.name || '')}" ${_dgInp()}
              ${cw ? `oninput="_dg.edit.general.name=this.value;_dg.markDirty()"` : 'disabled'}>
          </div>
          <div class="field-group">
            <label class="field-label">${t('common.type')}</label>
            <div class="flex items-center gap-2">
              <img id="dg-icon-preview" src="${iconUrl}" width="32" height="32" style="image-rendering:pixelated;border-radius:4px;background:var(--bg-elevated)" onerror="mkItemIconFallback(this,'${escHtml((g.guiIcon||'minecraft:paper').replace('minecraft:',''))}')">
              <input type="text" list="dg-items-dl" value="${escHtml(g.guiIcon || 'minecraft:paper')}" ${_dgInp()}
                ${cw ? `oninput="_dg.edit.general.guiIcon=this.value;_dg.markDirty();var img=document.getElementById('dg-icon-preview');if(img){img.src=mkItemIconUrl(this.value);img.style.display=''}"` : 'disabled'}>
            </div>
            <span class="field-help">${t('dungeons.iconHelp')}</span>
          </div>
          <div class="field-group">
            <label class="field-label">${t('dungeons.mode')}</label>
            <select ${_dgSel()} ${cw ? `onchange="_dg.edit.networking.teleportMode=this.value;_dg.markDirty()"` : 'disabled'}>${teleportOpts}</select>
            <span class="field-help">${t('dungeons.modeHelp')}</span>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="field-group">
              <label class="field-label">${t('dungeons.targetServer')}</label>
              <input type="text" value="${escHtml(n.targetServer || '')}" ${_dgInp()}
                ${cw ? `oninput="_dg.edit.networking.targetServer=this.value;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.mode')}</span>
            </div>
            <div class="field-group">
              <label class="field-label">${t('dungeons.mainServer')}</label>
              <input type="text" value="${escHtml(n.mainServer || '')}" ${_dgInp()}
                ${cw ? `oninput="_dg.edit.networking.mainServer=this.value;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.mode')}</span>
            </div>
          </div>
          <div class="field-group">
            <label class="field-label flex items-center gap-2">
              <input type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${n.syncPlayerData ? 'checked' : ''} ${cw ? `onchange="_dg.edit.networking.syncPlayerData=this.checked;_dg.markDirty()"` : 'disabled'}>
              ${t('dungeons.mode')}
            </label>
          </div>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('dungeons.prerequisites')}</span></div>
        <div class="section-card-body space-y-3">
          <div class="grid grid-cols-2 gap-3">
            <div class="field-group">
              <label class="field-label">${t('dungeons.timeLimit')}</label>
              <input type="number" value="${r.timeLimitSeconds || 1800}" ${_dgInp()}
                ${cw ? `oninput="_dg.edit.requirements.timeLimitSeconds=parseInt(this.value)||1800;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${Math.floor((r.timeLimitSeconds || 1800) / 60)} min</span>
            </div>
            <div class="field-group">
              <label class="field-label">${t('dungeons.maxPartySize')}</label>
              <input type="number" value="${r.dungeonsPartySizeLimit || 5}" ${_dgInp()}
                ${cw ? `oninput="_dg.edit.requirements.dungeonsPartySizeLimit=parseInt(this.value)||5;_dg.markDirty()"` : 'disabled'}>
            </div>
          </div>
          <div class="grid grid-cols-3 gap-3">
            <div class="field-group">
              <label class="field-label">${t('dungeons.soloLives')}</label>
              <input type="number" value="${r.soloLives || 3}" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.soloLives=parseInt(this.value)||3;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.soloHelp')}</span>
            </div>
            <div class="field-group">
              <label class="field-label">${t('dungeons.smallPartyLives')}</label>
              <input type="number" value="${r.smallPartyLives || 6}" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.smallPartyLives=parseInt(this.value)||6;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.smallPartyHelp')}</span>
            </div>
            <div class="field-group">
              <label class="field-label">${t('dungeons.largePartyLives')}</label>
              <input type="number" value="${r.largePartyLives || 10}" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.largePartyLives=parseInt(this.value)||10;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.largePartyHelp')}</span>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="field-group">
              <label class="field-label">${t('dungeons.requiredAlphas')}</label>
              <input type="number" value="${r.requiredAlphas || 0}" min="0" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.requiredAlphas=parseInt(this.value)||0;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.alphaPool')}</span>
            </div>
            <div class="field-group">
              <label class="field-label">${t('dungeons.requiredGems')}</label>
              <input type="number" value="${r.requiredGems || 0}" min="0" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.requiredGems=parseInt(this.value)||0;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.progression')}</span>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="field-group">
              <label class="field-label">${t('dungeons.requiredGuardians')}</label>
              <input type="number" value="${r.requiredGuardians || 0}" min="0" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.requiredGuardians=parseInt(this.value)||0;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.stats')}</span>
            </div>
            <div class="field-group">
              <label class="field-label">${t('dungeons.requiredStakes')}</label>
              <input type="number" value="${r.requiredStakes || 0}" min="0" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.requiredStakes=parseInt(this.value)||0;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.progression')}</span>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="field-group">
              <label class="field-label">${t('dungeons.minLevel')}</label>
              <input type="number" value="${r.minLevel || 1}" min="1" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.minLevel=parseInt(this.value)||1;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.prerequisites')}</span>
            </div>
            <div class="field-group">
              <label class="field-label">${t('dungeons.cooldownSec')}</label>
              <input type="number" value="${r.cooldownSeconds || 0}" min="0" ${_dgInp()} ${cw ? `oninput="_dg.edit.requirements.cooldownSeconds=parseInt(this.value)||0;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.noCooldownHelp')}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="section-card lg:col-span-2">
        <div class="section-card-header">
          <span class="section-card-title">${t('dungeons.positions')}</span>
        </div>
        <div class="section-card-body space-y-3">
          <div>
            <span class="text-xs font-semibold" style="color:var(--text-heading)">${t('dungeons.posSpawn')}</span>
            <span class="field-help ml-2">${t('dungeons.posSpawnHelp')}</span>
            ${_dgPosFields('sp', e.locations.spawnPos, '_dg.edit.locations.spawnPos')}
          </div>
          <div>
            <span class="text-xs font-semibold" style="color:var(--text-heading)">${t('dungeons.posHub')}</span>
            <span class="field-help ml-2">${t('dungeons.posHubHelp')}</span>
            ${_dgPosFields('hu', e.locations.hubPos, '_dg.edit.locations.hubPos')}
          </div>
          <div>
            <span class="text-xs font-semibold" style="color:var(--text-heading)">${t('dungeons.posBossRoom')}</span>
            <span class="field-help ml-2">${t('dungeons.posBossRoomHelp')}</span>
            ${_dgPosFields('br', e.locations.bossRoomPos, '_dg.edit.locations.bossRoomPos')}
          </div>
          <div>
            <span class="text-xs font-semibold" style="color:var(--text-heading)">${t('dungeons.posReturn')}</span>
            <span class="field-help ml-2">${t('dungeons.posReturnHelp')}</span>
            ${_dgPosFields('rt', e.locations.returnPos, '_dg.edit.locations.returnPos')}
          </div>
          <div class="section-divider my-2"></div>
          <div class="flex items-center justify-between">
            <div>
              <span class="text-xs font-semibold" style="color:var(--amber)">${t('dungeons.additionalSlots')} (${e.additionalLocations.length})</span>
              <span class="field-help ml-2">${t('dungeons.positions')}</span>
            </div>
            ${cw ? `<button class="btn-cg btn-cg-ghost text-xs" onclick="_dg.edit.additionalLocations.push({spawnPos:{x:0,y:100,z:0,dimension:'minecraft:overworld'},hubPos:{x:0,y:100,z:0,dimension:'minecraft:overworld'},bossRoomPos:{x:0,y:100,z:0,dimension:'minecraft:overworld'},returnPos:{x:0,y:100,z:0,dimension:'minecraft:overworld'}});_dg.markDirty();_dgRenderLocations()">${t('common.add')}</button>` : ''}
          </div>
          <div id="dg-addloc-list"></div>
        </div>
      </div>
    </div>
  </div>`;

  // ── Scaling & Spawning tab ──────────────────────────────────────────────
  const scalingTab = `<div data-tab-panel-dg="scaling" style="display:none">
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('dungeons.tierAndLevels')}</span></div>
        <div class="section-card-body space-y-3">
          <div class="field-group">
            <label class="field-label">${t('dungeons.tier')}</label>
            <input type="number" value="${sc.tier || 1}" min="0" ${_dgInp()} ${cw ? `oninput="_dg.edit.scaling.tier=parseInt(this.value)||1;_dg.markDirty()"` : 'disabled'}>
            <span class="field-help">${t('tip.dungeons.tier')}</span>
          </div>
          <table class="cg-table"><thead><tr><th>${t('dungeons.tier')}</th><th>${t('dungeons.level')}</th><th></th></tr></thead>
          <tbody id="dg-tiers-list"></tbody></table>
          ${cw ? `<button class="btn-cg btn-cg-ghost text-xs mt-2" onclick="_dg.edit.scaling.tierLevels.push({tier:_dg.edit.scaling.tierLevels.length+1,level:15});_dg.markDirty();_dgRenderTierLevels()">${t('common.add')}</button>` : ''}
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('dungeons.spawnerIds')}</span></div>
        <div class="section-card-body space-y-3">
          <span class="field-help">${t('dungeons.spawnerIds')}</span>
          <div class="field-group">
            <label class="field-label">${t('dungeons.alphaSpawners')}</label>
            <input type="text" value="${(sp.alphaSpawnerIds||[]).join(', ')}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.alphaSpawnerIds=this.value.split(',').map(v=>parseInt(v.trim())).filter(v=>!isNaN(v));_dg.markDirty()"` : 'disabled'}>
          </div>
          <div class="field-group">
            <label class="field-label">${t('dungeons.guardianSpawners')}</label>
            <input type="text" value="${(sp.guardianSpawnerIds||[]).join(', ')}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.guardianSpawnerIds=this.value.split(',').map(v=>parseInt(v.trim())).filter(v=>!isNaN(v));_dg.markDirty()"` : 'disabled'}>
          </div>
          <div class="field-group">
            <label class="field-label">${t('dungeons.stakeSpawners')}</label>
            <input type="text" value="${(sp.stakeSpawnerIds||[]).join(', ')}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.stakeSpawnerIds=this.value.split(',').map(v=>parseInt(v.trim())).filter(v=>!isNaN(v));_dg.markDirty()"` : 'disabled'}>
          </div>
          <div class="field-group">
            <label class="field-label">${t('dungeons.portals')}</label>
            <input type="text" value="${(sp.portalIds||[]).join(', ')}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.portalIds=this.value.split(',').map(v=>parseInt(v.trim())).filter(v=>!isNaN(v));_dg.markDirty()"` : 'disabled'}>
          </div>
          <div class="field-group">
            <label class="field-label">${t('dungeons.chests')}</label>
            <input type="text" value="${(sp.chestIds||[]).join(', ')}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.chestIds=this.value.split(',').map(v=>parseInt(v.trim())).filter(v=>!isNaN(v));_dg.markDirty()"` : 'disabled'}>
          </div>
          <div class="field-group">
            <label class="field-label">${t('dungeons.guardianTrainerIds')}</label>
            <input type="text" value="${(sp.guardianTrainerIds||[]).join(', ')}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.guardianTrainerIds=this.value.split(',').map(v=>v.trim()).filter(v=>v);_dg.markDirty()"` : 'disabled'}>
            <span class="field-help">${t('dungeons.spawnerIds')}</span>
          </div>
        </div>
      </div>

      <div class="section-card lg:col-span-2">
        <div class="section-card-header"><span class="section-card-title">${t('dungeons.spawnRadii')}</span></div>
        <div class="section-card-body">
          <span class="field-help mb-2 block">${t('dungeons.spawnRadiiHelp')}</span>
          <div class="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <div class="field-group"><label class="field-label">${t('dungeons.alphaSpawnRadius')}</label>
              <input type="number" value="${sp.alphaSpawnRadius || 150}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.alphaSpawnRadius=parseInt(this.value)||150;_dg.markDirty()"` : 'disabled'}></div>
            <div class="field-group"><label class="field-label">${t('dungeons.alphaTriggerRadius')}</label>
              <input type="number" value="${sp.alphaTriggerRadius || 5}" step="0.5" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.alphaTriggerRadius=parseFloat(this.value)||5;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.alphaTriggerHelp')}</span></div>
            <div class="field-group"><label class="field-label">${t('dungeons.guardianSpawnRadius')}</label>
              <input type="number" value="${sp.guardianSpawnRadius || 150}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.guardianSpawnRadius=parseInt(this.value)||150;_dg.markDirty()"` : 'disabled'}></div>
            <div class="field-group"><label class="field-label">${t('dungeons.stakeSpawnRadius')}</label>
              <input type="number" value="${sp.stakeSpawnRadius || 150}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.stakeSpawnRadius=parseInt(this.value)||150;_dg.markDirty()"` : 'disabled'}></div>
            <div class="field-group"><label class="field-label">${t('dungeons.portalSpawnRadius')}</label>
              <input type="number" value="${sp.portalSpawnRadius || 150}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.portalSpawnRadius=parseInt(this.value)||150;_dg.markDirty()"` : 'disabled'}></div>
            <div class="field-group"><label class="field-label">${t('dungeons.chestSpawnRadius')}</label>
              <input type="number" value="${sp.chestSpawnRadius || 150}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.chestSpawnRadius=parseInt(this.value)||150;_dg.markDirty()"` : 'disabled'}></div>
            <div class="field-group"><label class="field-label">${t('dungeons.verticalScan')}</label>
              <input type="number" value="${sp.spawnerScanVerticalRange || 64}" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.spawnerScanVerticalRange=parseInt(this.value)||64;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.verticalScanHelp')}</span></div>
            <div class="field-group"><label class="field-label">${t('dungeons.protection')}</label>
              <input type="number" value="${sp.spawnProtectionRadius || 100}" step="0.5" ${_dgInp()} ${cw ? `oninput="_dg.edit.spawning.spawnProtectionRadius=parseFloat(this.value)||100;_dg.markDirty()"` : 'disabled'}>
              <span class="field-help">${t('dungeons.protectionHelp')}</span></div>
          </div>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('dungeons.alphaPool')} (${(sp.alphaPool||[]).length})</span></div>
        <div class="section-card-body p-0">
          <table class="cg-table"><thead><tr><th>${t('dungeons.species')}</th><th>${t('dungeons.tier')}</th><th>${t('dungeons.minLv')}</th><th>${t('dungeons.maxLv')}</th><th>${t('dungeons.weight')}</th><th></th></tr></thead>
          <tbody id="dg-alphas-list"></tbody></table>
        </div>
        ${cw ? `<div class="p-2"><button class="btn-cg btn-cg-ghost text-xs" onclick="_dg.edit.spawning.alphaPool.push({species:'',tier:0,minLevel:10,maxLevel:20,weight:10});_dg.markDirty();_dgRenderAlphaPool()">${t('common.add')}</button></div>` : ''}
      </div>

      <div class="section-card">
        <div class="section-card-header"><span class="section-card-title">${t('dungeons.guardianPool')} (${(sp.guardianPool||[]).length})</span></div>
        <div class="section-card-body p-0">
          <table class="cg-table"><thead><tr><th>${t('dungeons.trainerId')}</th><th>${t('dungeons.tier')}</th><th>${t('dungeons.weight')}</th><th>${t('dungeons.level')}</th><th></th></tr></thead>
          <tbody id="dg-guardians-list"></tbody></table>
        </div>
        ${cw ? `<div class="p-2"><button class="btn-cg btn-cg-ghost text-xs" onclick="_dg.edit.spawning.guardianPool.push({trainerId:'',tier:0,weight:10,level:0});_dg.markDirty();_dgRenderGuardianPool()">${t('common.add')}</button></div>` : ''}
      </div>
    </div>
  </div>`;

  // ── Boss tab ────────────────────────────────────────────────────────────
  const bossTab = `<div data-tab-panel-dg="boss" style="display:none">
    <div class="section-card mb-4">
      <div class="section-card-header"><span class="section-card-title">${t('dungeons.bossPosition')}</span></div>
      <div class="section-card-body">
        <span class="field-help mb-2 block">${t('dungeons.bossPosition')}</span>
        ${_dgPosFields('bs', boss.bossSpawnPos, '_dg.edit.boss.bossSpawnPos')}
      </div>
    </div>
    <div class="section-card">
      <div class="section-card-header"><span class="section-card-title">${t('dungeons.bossPool')} (${(boss.pool||[]).length})</span></div>
      <div class="section-card-body p-0" style="overflow-x:auto">
        <table class="cg-table"><thead><tr>
          <th>${t('dungeons.species')}</th><th>${t('dungeons.tier')}</th><th>${t('dungeons.level')}</th><th>${t('dungeons.scale')}</th><th>${t('dungeons.shiny')}</th><th>${t('dungeons.weight')}</th><th>${t('dungeons.rewardPercent')}</th><th>${t('dungeons.customName')}</th><th></th>
        </tr></thead>
        <tbody id="dg-boss-list"></tbody></table>
      </div>
      ${cw ? `<div class="p-2"><button class="btn-cg btn-cg-ghost text-xs" onclick="_dg.edit.boss.pool.push({species:'pikachu',tier:0,level:50,scale:4.0,isShiny:false,weight:10,rewardChance:100,customName:'Dungeon Boss',customRewards:[]});_dg.markDirty();_dgRenderBossPool()">${t('common.add')}</button></div>` : ''}
    </div>
  </div>`;

  // ── Loot tab ────────────────────────────────────────────────────────────
  const lootTab = `<div data-tab-panel-dg="loot" style="display:none">
    <div class="field-group mb-3">
      <label class="field-label">${t('dungeons.rewardCap')}</label>
      <input type="number" value="${e.rewardCap || 0}" min="0" ${_dgInp('max-width:120px')} ${cw ? `oninput="_dg.edit.rewardCap=parseInt(this.value)||0;_dg.markDirty()"` : 'disabled'}>
      <span class="field-help">${t('dungeons.completionRewards')}</span>
    </div>
    <div class="flex items-center justify-between mb-3">
      <span class="text-sm font-medium" style="color:var(--text-heading)">${t('dungeons.lootPools')} (${(e.lootPools||[]).length})</span>
      ${cw ? `<button class="btn-cg btn-cg-ghost text-xs" onclick="var ps=_dg.edit.lootPools;ps.push({id:ps.length+1,tier:0,rolls:1,items:[]});_dg.markDirty();_dgRenderLootPools()">${t('common.add')}</button>` : ''}
    </div>
    <div id="dg-loot-list"></div>
    <div class="section-card mt-4">
      <div class="section-card-header"><span class="section-card-title">${t('dungeons.completionRewards')} (${(e.completionRewards||[]).length})</span></div>
      <div class="section-card-body p-0" style="overflow-x:auto">
        <table class="cg-table"><thead><tr><th>${t('common.item')}</th><th>${t('common.min')}</th><th>${t('common.max')}</th><th>${t('dungeons.chancePercent')}</th><th>${t('dungeons.tier')}</th><th>${t('dungeons.customName')}</th><th></th></tr></thead>
        <tbody id="dg-rewards-list"></tbody></table>
      </div>
      ${cw ? `<div class="p-2"><button class="btn-cg btn-cg-ghost text-xs" onclick="_dg.edit.completionRewards.push({item:'minecraft:diamond',customName:'',min:1,max:1,chance:100,tier:0,nbt:''});_dg.markDirty();_dgRenderCompletionRewards()">${t('common.add')}</button></div>` : ''}
    </div>
  </div>`;

  // ── Active runs tab ─────────────────────────────────────────────────────
  let activeContent = '';
  const instances = activeData ? (activeData.instances || []).filter(i => i.dungeonId === configId) : [];
  if (instances.length === 0) {
    activeContent = `<div class="empty-state"><span class="empty-state-icon">⚔️</span><span class="empty-state-text">${t('common.noResults')}</span></div>`;
  } else {
    const aRows = instances.map(inst => {
      const pn = (inst.players || []).map(p => escHtml(p.name)).join(', ');
      const tl = inst.timeLeft > 0 ? Math.floor(inst.timeLeft / 20) + 's' : '—';
      const bs = inst.bossDefeated ? '✓ ' + t('dungeons.defeated') : inst.bossSpawned ? `⚔ ${escHtml(inst.bossSpecies||'')}` : '—';
      return `<tr><td>${pn}</td><td>T${inst.tier}</td><td class="td-mono">${tl}</td><td>${inst.lives}</td>
        <td>⚔${inst.alphasDefeated}/${inst.alphasRequired} 💎${inst.gemsFound}/${inst.gemsRequired} 🛡${inst.guardiansDefeated}/${inst.guardiansRequired} 🗡${inst.stakesFound}/${inst.stakesRequired}</td>
        <td>${bs}</td>
        <td>${cw && inst.players?.[0] ? `<button class="btn-cg btn-cg-danger text-xs" onclick="(async()=>{if(await confirmAction(t('dungeons.confirmFail'))){await API.dungeons.failRun('${inst.players[0].uuid}');_nav('dungeons:${configId}')}})()">` + t('dungeons.fail') + `</button>` : ''}</td></tr>`;
    }).join('');
    activeContent = `<div class="section-card"><div class="section-card-header"><span class="section-card-title">${t('dungeons.activeRuns')} (${instances.length})</span></div>
      <div class="section-card-body p-0"><table class="cg-table"><thead><tr><th>${t('common.players')}</th><th>${t('dungeons.tier')}</th><th>${t('dungeons.time')}</th><th>${t('dungeons.lives')}</th><th>${t('dungeons.progression')}</th><th>${t('dungeons.boss')}</th><th></th></tr></thead><tbody>${aRows}</tbody></table></div></div>`;
  }
  const activeTab2 = `<div data-tab-panel-dg="active" style="display:none">${activeContent}</div>`;

  // ── Stats tab ───────────────────────────────────────────────────────────
  let statsContent = '';
  const runs = histData ? (histData.history || []).filter(r2 => r2.dungeonId === configId) : [];
  const total = runs.length, success = runs.filter(r2 => r2.success).length;
  const rate = total > 0 ? Math.round(success * 100 / total) : 0;
  const avgDur = total > 0 ? Math.round(runs.reduce((s2, r2) => s2 + (r2.durationSeconds || 0), 0) / total) : 0;
  const sRows = runs.slice(0, 20).map(r2 => {
    const dur = r2.durationSeconds || 0;
    const ds = dur > 60 ? Math.floor(dur/60)+'min '+(dur%60)+'s' : dur+'s';
    return `<tr><td class="td-mono text-xs">${I18N.fmtDate(r2.startTime*1000)}</td><td>T${r2.tier}</td><td>${(r2.players||[]).join(', ')}</td>
      <td>${r2.success?'<span style="color:var(--green)">✓</span>':'<span style="color:var(--red)">✗</span>'}</td><td class="td-mono">${ds}</td><td>${r2.deathCount||0}</td></tr>`;
  }).join('');
  let lbHtml = '';
  try {
    const lb = await API.dungeons.leaderboard(configId);
    const tierMap = lb[configId] || {};
    const tierKeys = Object.keys(tierMap).sort((a,b) => parseInt(a)-parseInt(b));
    if (tierKeys.length > 0) {
      lbHtml = `<div class="section-card mt-3"><div class="section-card-header"><span class="section-card-title">${t('dungeons.leaderboardTop10')}</span></div><div class="section-card-body">${
        tierKeys.map(t2 => {
          const entries = tierMap[t2] || [];
          const lbR = entries.map((le,i) => {
            const dur=le.durationSeconds||0; const ds=dur>60?Math.floor(dur/60)+'min '+(dur%60)+'s':dur+'s';
            return `<tr><td class="td-mono">${i+1}</td><td>${escHtml(le.playerName)}</td><td class="td-mono">${ds}</td><td class="td-mono text-xs">${I18N.fmtDate(le.date*1000)}</td></tr>`;
          }).join('');
          return `<div class="mb-2"><div class="text-xs font-medium mb-1" style="color:var(--amber)">Tier ${t2}</div>
            <table class="cg-table"><thead><tr><th>#</th><th>${t('common.player')}</th><th>${t('dungeons.time')}</th><th>${t('common.date')}</th></tr></thead>
            <tbody>${lbR||'<tr><td colspan="4" class="text-center text-muted">' + t('common.noResults') + '</td></tr>'}</tbody></table></div>`;
        }).join('')
      }</div></div>`;
    }
  } catch (_) {}
  statsContent = `
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
      <div class="stat-card"><div class="stat-value">${total}</div><div class="stat-label">${t('dungeons.runs')}</div></div>
      <div class="stat-card"><div class="stat-value">${rate}%</div><div class="stat-label">${t('dungeons.result')}</div></div>
      <div class="stat-card"><div class="stat-value">${Math.floor(avgDur/60)}min</div><div class="stat-label">${t('common.duration')}</div></div>
      <div class="stat-card"><div class="stat-value">${success}</div><div class="stat-label">${t('dungeons.completed')}</div></div>
    </div>
    <div class="section-card"><div class="section-card-header"><span class="section-card-title">${t('dungeons.historyTitle')} (${total} ${t('dungeons.runs')})</span></div>
      <div class="section-card-body p-0"><table class="cg-table"><thead><tr><th>${t('common.date')}</th><th>${t('dungeons.tier')}</th><th>${t('common.players')}</th><th>${t('dungeons.result')}</th><th>${t('common.duration')}</th><th>${t('dungeons.deaths')}</th></tr></thead>
      <tbody>${sRows||'<tr><td colspan="6" class="text-center text-muted">' + t('common.noResults') + '</td></tr>'}</tbody></table></div></div>
    ${lbHtml}`;
  const statsTab = `<div data-tab-panel-dg="stats" style="display:none">${statsContent}</div>`;

  // Schedule dynamic renders after DOM insert
  setTimeout(() => {
    _dgRenderLocations();
    _dgRenderTierLevels();
    _dgRenderAlphaPool();
    _dgRenderGuardianPool();
    _dgRenderBossPool();
    _dgRenderLootPools();
    _dgRenderCompletionRewards();
  }, 0);

  // ── Datalists for autocomplete ───────────────────────────────────────────
  const mcDims = ['minecraft:overworld','minecraft:the_nether','minecraft:the_end'];
  const allDimIds = [...new Set([...mcDims, ...(dimsList || []).map(d => d.id)])];
  const datalistsHtml = `
    <datalist id="dg-items-dl">${MK_COMMON_ITEMS.map(i => `<option value="${i}">`).join('')}</datalist>
    <datalist id="dg-species-dl">${COBBLEMON_SPECIES.map(s => `<option value="${s}">`).join('')}</datalist>
    <datalist id="dg-trainers-dl">${(trainersList || []).map(t => `<option value="${escHtml(t.id)}">`).join('')}</datalist>
    <datalist id="dg-dims-dl">${allDimIds.map(d => `<option value="${escHtml(d)}">`).join('')}</datalist>`;

  return `<div class="max-w-6xl mx-auto slide-in">
    <div class="detail-header">
      ${detailBackBtn('dungeons', t('dungeons.title'))}
      <span class="font-medium" style="color:var(--text-primary);font-size:var(--type-lg)">${escHtml(config.general?.name || configId)}</span>
      <span class="id-badge ml-1">${escHtml(configId)}</span>
      <span id="dg-dirty" class="text-xs ml-2" style="color:var(--amber);display:none">${t('common.save')}...</span>
    </div>
    ${detailTabBar(tabs, 'general', 'dgSwitchTab')}
    <div class="p-4">
      ${generalTab}${scalingTab}${bossTab}${lootTab}${activeTab2}${statsTab}
    </div>
    ${datalistsHtml}
  </div>`;
}
