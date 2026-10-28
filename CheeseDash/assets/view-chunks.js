// -- Chunks -----------------------------------------------------------------------

async function buildChunks(app) {
  let sets, suggestions;
  try {
    [sets, suggestions] = await Promise.all([API.chunks.sets(), API.chunks.suggestions()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const _c = window._cv = {
    sets, suggestions, app,
    search: '',

    getFilteredSets() {
      if (!this.search) return this.sets;
      const q = this.search.toLowerCase();
      return this.sets.filter(s => (s.name || '').toLowerCase().includes(q));
    },

    renderSets() {
      const filtered = this.getFilteredSets();
      const tbody = document.getElementById('chunks-sets-tbody');
      if (tbody) tbody.innerHTML = filtered.map(s => chunkSetRowHtml(s)).join('');
      const cnt = document.getElementById('chunks-sets-count');
      if (cnt) cnt.textContent = filtered.length;
    },

    renderSuggestions() {
      const tbody = document.getElementById('chunks-sugg-tbody');
      const cnt = document.getElementById('chunks-sugg-count');
      if (cnt) cnt.textContent = this.suggestions.length;
      if (tbody) {
        if (this.suggestions.length === 0) {
          tbody.innerHTML = '<tr><td colspan="6" class="text-center text-muted text-sm py-4">' + t('chunks.noSuggestions') + '</td></tr>';
        } else {
          tbody.innerHTML = this.suggestions.map(s => suggestionRowHtml(s)).join('');
        }
      }
    },

    async refresh() {
      try {
        [this.sets, this.suggestions] = await Promise.all([API.chunks.sets(), API.chunks.suggestions()]);
        this.renderSets();
        this.renderSuggestions();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async createSet() {
      const inp = document.getElementById('chunk-set-name-input');
      if (!inp) return;
      const name = inp.value.trim();
      if (!name) return;
      try {
        await API.chunks.createSet({ name });
        app.toast(t('chunks.created'), 'success');
        inp.value = '';
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    openSetDetail(name) {
      _nav('chunks:' + encodeURIComponent(name));
    },

    async deleteSet(name) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.chunks.deleteSet(name);
        app.toast(t('chunks.deleted'), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async dismissSuggestion(id) {
      try {
        await API.chunks.dismiss(id);
        app.toast(t('common.success'), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async acceptSuggestion(id) {
      if (!this.sets || this.sets.length === 0) {
        app.toast(t('chunks.noChunks'), 'error');
        return;
      }
      // Show modal to pick target set
      const opts = this.sets.map(s => `<option value="${escHtml(s.name)}">${escHtml(s.name)} (${s.chunkCount} chunks)</option>`).join('');
      const dlg = document.getElementById('chunk-accept-dlg');
      if (dlg) dlg.remove();
      document.body.insertAdjacentHTML('beforeend', `
        <dialog id="chunk-accept-dlg" class="modal">
          <div class="modal-box max-w-md">
            <h3 class="font-bold text-lg mb-3">${t('common.confirm')}</h3>
            <label class="text-sm text-muted mb-1 block">${t('chunks.addChunk')}</label>
            <select id="chunk-accept-set" class="select select-sm select-bordered w-full mb-3">${opts}</select>
            <label class="text-sm text-muted mb-1 block">${t('chunks.radiusLabel')}</label>
            <input id="chunk-accept-radius" type="number" class="input input-sm input-bordered w-full mb-4" value="1" min="0" max="16">
            <div class="flex gap-2 justify-end">
              <button class="btn-cg btn-cg-ghost" onclick="document.getElementById('chunk-accept-dlg').close()">${t('common.cancel')}</button>
              <button class="btn-cg btn-cg-primary" id="chunk-accept-confirm">${t('common.confirm')}</button>
            </div>
          </div>
          <form method="dialog" class="modal-backdrop"><button>close</button></form>
        </dialog>
      `);
      const modal = document.getElementById('chunk-accept-dlg');
      modal.showModal();
      document.getElementById('chunk-accept-confirm').onclick = async () => {
        const setName = document.getElementById('chunk-accept-set').value;
        const radius = parseInt(document.getElementById('chunk-accept-radius').value) || 1;
        modal.close();
        try {
          await API.chunks.acceptSuggestion(id, setName, radius);
          app.toast(t('chunks.suggestionAccepted'), 'success');
          await this.refresh();
        } catch (e) { app.toast(e.message, 'error'); }
      };
    },

    async removeEntry(setName, x, z, radius) {
      try {
        await API.chunks.removeEntry(setName, x, z, radius);
        app.toast(t('common.success'), 'success');
        app.navigate('chunks:' + encodeURIComponent(setName));
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async resetChunks(setName) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.chunks.resetSet(setName);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  function chunkSetRowHtml(s) {
    const schedBadge = s.resetTrigger === 'SCHEDULED'
      ? `<span class="text-xs px-1.5 py-0.5 rounded" style="background:var(--amber);color:var(--bg)">${String(s.resetHour).padStart(2,'0')}:${String(s.resetMinute||0).padStart(2,'0')}</span>`
      : `<span class="text-xs text-muted">${t('common.general')}</span>`;
    const dimShort = (s.dimension || 'minecraft:overworld').replace('minecraft:', '');
    return `<tr class="cursor-pointer hover-elevated" onclick="_nav('chunks:${escHtml(s.name)}')">
      <td><span class="id-badge">${escHtml(s.name)}</span></td>
      <td class="text-xs"><span class="id-badge">${escHtml(dimShort)}</span></td>
      <td class="td-mono">${s.chunkCount}</td>
      <td class="td-mono">${s.totalAffectedChunks || s.chunkCount}</td>
      <td>${schedBadge}</td>
      <td onclick="event.stopPropagation()">
        <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_cv.deleteSet('${escHtml(s.name)}')">\u2715</button>
      </td>
    </tr>`;
  }

  function suggestionRowHtml(s) {
    const sx = Math.round(s.x || 0), sy = Math.round(s.y || 0), sz = Math.round(s.z || 0);
    return `<tr>
      <td><span class="id-badge">${escHtml(s.id ? s.id.substring(0,8) : '—')}</span></td>
      <td class="text-sm">${escHtml(s.playerName || '—')}</td>
      <td>${worldBadgeHtml(s.world || '')}</td>
      <td class="td-mono">${sx}, ${sy}, ${sz}</td>
      <td class="text-sm text-muted">${s.createdAt ? I18N.fmtDate(s.createdAt) : '—'}</td>
      <td class="flex gap-1">
        <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_tpAdmin({x:${sx},y:${sy},z:${sz}})" title="${t('common.teleport')}">TP</button>
        <button class="btn-cg btn-cg-success btn-cg-sm" onclick="_cv.acceptSuggestion('${escHtml(s.id)}')">${t('common.confirm')}</button>
        <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_cv.dismissSuggestion('${escHtml(s.id)}')">${t('common.delete')}</button>
      </td>
    </tr>`;
  }

  const setRows = sets.map(s => chunkSetRowHtml(s)).join('');
  const suggRows = suggestions.map(s => suggestionRowHtml(s)).join('');

  return `
    <div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <!-- Chunk Sets -->
      <div class="section-card">
        <div class="section-card-header">
          <span class="section-card-title">\uD83D\uDDFA ${t('chunks.title')} <span id="chunks-sets-count" class="text-muted text-xs font-mono ml-2">${sets.length}</span></span>
        </div>
        <div class="filter-bar">
          <input type="text" placeholder="${t('common.search')}"
            class="input input-sm input-bordered text-sm max-w-xs"
            oninput="_cv.search=this.value;_cv.renderSets()">
          <div class="flex-1"></div>
          <input id="chunk-set-name-input" class="input input-sm input-bordered max-w-xs" placeholder="${t('chunks.newChunkSet')}">
          <button class="btn-cg btn-cg-primary" onclick="_cv.createSet()">${t('common.create')}</button>
        </div>
        ${sets.length === 0 ? `<div class="empty-state py-6"><span class="empty-state-text text-sm text-muted">${t('chunks.noChunks')}</span></div>` : `
        <table class="cg-table">
          <thead><tr><th>${t('common.name')}</th><th>${t('common.dimension')}</th><th>${t('chunks.chunks')}</th><th>${t('chunks.affectedChunks')}</th><th>${t('common.reset')}</th><th></th></tr></thead>
          <tbody id="chunks-sets-tbody">${setRows}</tbody>
        </table>`}
      </div>

      <!-- Suggestions -->
      <div class="section-card mt-4">
        <div class="section-card-header">
          <span class="section-card-title">\uD83D\uDCA1 ${t('chunks.suggestions')} <span id="chunks-sugg-count" class="text-muted text-xs font-mono ml-2">${suggestions.length}</span></span>
          <button class="btn-cg btn-cg-ghost" onclick="_cv.refresh()">${t('common.refresh')}</button>
        </div>
        <table class="cg-table">
          <thead><tr><th>${t('common.id')}</th><th>${t('common.player')}</th><th>${t('common.world')}</th><th>${t('common.position')}</th><th>${t('common.date')}</th><th>${t('common.actions')}</th></tr></thead>
          <tbody id="chunks-sugg-tbody">${suggestions.length === 0 ? '<tr><td colspan="6" class="text-center text-muted text-sm py-4">' + t('chunks.noSuggestions') + '</td></tr>' : suggRows}</tbody>
        </table>
      </div>
    </div>`;
}

async function buildChunkSetDetail(app, setName) {
  let detail;
  try {
    detail = await API.chunks.getSet(setName);
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const _cd = window._cdv = {
    detail, app,
    async removeEntry(x, z, radius) {
      try {
        await API.chunks.removeEntry(detail.name, x, z, radius);
        app.toast(t('common.success'), 'success');
        app.navigate('chunks:' + encodeURIComponent(detail.name));
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async resetChunks() {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.chunks.resetSet(detail.name);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async deleteSet() {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.chunks.deleteSet(detail.name);
        app.toast(t('chunks.deleted'), 'success');
        app.navigate('chunks');
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async saveSchedule() {
      const trigger = document.getElementById('cs-trigger')?.value || 'MANUAL';
      const dimension = document.getElementById('cs-dimension')?.value || detail.dimension || 'minecraft:overworld';
      const hour = parseInt(document.getElementById('cs-hour')?.value) || 0;
      const minute = parseInt(document.getElementById('cs-minute')?.value) || 0;
      const dayCheckboxes = document.querySelectorAll('.cs-day:checked');
      const days = Array.from(dayCheckboxes).map(cb => cb.value);
      try {
        await API.chunks.setSchedule(detail.name, { trigger, dimension, hour, minute, days });
        detail.resetTrigger = trigger;
        detail.dimension = dimension;
        detail.resetHour = hour;
        detail.resetMinute = minute;
        detail.resetDays = days;
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  const entryCount = detail.chunks ? detail.chunks.length : 0;
  const affectedCount = detail.totalAffectedChunks || entryCount;
  const sn = escHtml(detail.name);

  let chunkRows = '';
  if (detail.chunks && detail.chunks.length > 0) {
    chunkRows = detail.chunks.map(c => {
      const cx = c.centerX != null ? c.centerX : (c.x * 16 + 8);
      const cz = c.centerZ != null ? c.centerZ : (c.z * 16 + 8);
      return `<tr>
        <td class="td-mono">${c.x}</td>
        <td class="td-mono">${c.z}</td>
        <td class="td-mono">${c.radius}</td>
        <td class="text-sm">${escHtml(c.label || '')}</td>
        <td class="td-mono">${cx}, ${cz}</td>
        <td class="flex gap-1">
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_tpAdmin({x:${cx},y:64,z:${cz}})" title="${t('common.tp')}">TP</button>
          <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_cdv.removeEntry(${c.x},${c.z},${c.radius})" title="${t('common.remove')}">\u2715</button>
        </td>
      </tr>`;
    }).join('');
  }

  // Build dimension options from known dimensions
  const dimVal = detail.dimension || 'minecraft:overworld';

  return `
    <div class="max-w-5xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      ${detailBackBtn('chunks', t('chunks.title'))}
      <div class="detail-header">
        <span class="text-lg font-semibold text-amber">${sn}</span>
        <span class="id-badge ml-2">${escHtml(dimVal)}</span>
      </div>

      <!-- Stats -->
      <div class="flex gap-4 mb-4">
        <div class="stat-card" style="flex:1">
          <span class="stat-value">${entryCount}</span>
          <span class="stat-label">${t('common.count')}</span>
        </div>
        <div class="stat-card" style="flex:1">
          <span class="stat-value">${affectedCount}</span>
          <span class="stat-label">${t('chunks.affectedChunks')}</span>
        </div>
      </div>

      <!-- Auto-reset schedule (before chunk list) -->
      <div class="section-card">
        <div class="section-card-header">
          <span class="section-card-title">${t('chunks.autoReset')}</span>
        </div>
        <div class="section-card-body">
          <div class="flex items-center gap-4 mb-3">
            <label class="field-label" style="min-width:80px">${t('common.dimension')} ${tip('tip.chunks.dimension')}</label>
            <input id="cs-dimension" class="input input-sm input-bordered w-64" value="${escHtml(dimVal)}" placeholder="minecraft:overworld">
          </div>
          <div class="section-divider my-3"></div>
          <div class="flex items-center gap-4 mb-3">
            <label class="field-label" style="min-width:80px">${t('chunks.resetMode')}</label>
            <select id="cs-trigger" class="select select-sm select-bordered w-40" onchange="document.getElementById('cs-schedule-opts').style.display=this.value==='SCHEDULED'?'':'none'">
              <option value="MANUAL" ${detail.resetTrigger !== 'SCHEDULED' ? 'selected' : ''}>${t('common.general')}</option>
              <option value="SCHEDULED" ${detail.resetTrigger === 'SCHEDULED' ? 'selected' : ''}>${t('server.scheduled')}</option>
            </select>
          </div>
          <div id="cs-schedule-opts" style="display:${detail.resetTrigger === 'SCHEDULED' ? '' : 'none'}">
            <div class="flex items-center gap-4 mb-3">
              <label class="field-label" style="min-width:80px">${t('chunks.resetHour')}</label>
              <input id="cs-hour" type="number" class="input input-sm input-bordered w-20" min="0" max="23" value="${detail.resetHour || 0}">
              <span class="text-muted">h</span>
              <input id="cs-minute" type="number" class="input input-sm input-bordered w-20" min="0" max="59" value="${detail.resetMinute || 0}">
              <span class="text-muted">min</span>
            </div>
            <div class="flex items-center gap-4 mb-3">
              <label class="field-label" style="min-width:80px">${t('chunks.resetDays')}</label>
              <div class="flex flex-wrap gap-2">
                ${['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'].map(d => {
                  const labels = {MONDAY:t('chunks.day.mon'),TUESDAY:t('chunks.day.tue'),WEDNESDAY:t('chunks.day.wed'),THURSDAY:t('chunks.day.thu'),FRIDAY:t('chunks.day.fri'),SATURDAY:t('chunks.day.sat'),SUNDAY:t('chunks.day.sun')};
                  const checked = (detail.resetDays || []).includes(d) ? 'checked' : '';
                  return '<label class="flex items-center gap-1 text-xs"><input type="checkbox" class="cs-day checkbox checkbox-xs" value="'+d+'" '+checked+'> '+labels[d]+'</label>';
                }).join('')}
              </div>
            </div>
            <p class="text-xs text-muted mb-3">${t('chunks.daysEmpty')}</p>
            ${detail.lastAutoReset ? '<p class="text-xs text-muted mb-3">' + t('chunks.lastAutoReset') + ' ' + I18N.fmtDate(detail.lastAutoReset) + '</p>' : ''}
          </div>
          <button class="btn-cg btn-cg-primary" onclick="_cdv.saveSchedule()">${t('common.save')}</button>
        </div>
      </div>

      <!-- Chunk entries table -->
      <div class="section-card mt-4">
        <div class="section-card-header">
          <span class="section-card-title">${t('chunks.chunks')}</span>
          <button class="btn-cg btn-cg-danger" style="font-size:0.75rem" onclick="_cdv.resetChunks()">${t('common.reset')}</button>
        </div>
        ${entryCount === 0 ? `<div class="empty-state py-6"><span class="empty-state-text text-sm text-muted">${t('chunks.noChunks')}</span></div>` : `
        <table class="cg-table">
          <thead><tr><th>${t('chunks.chunkX')}</th><th>${t('chunks.chunkZ')}</th><th>${t('chunks.radius')}</th><th>${t('chunks.label')}</th><th>${t('chunks.centerBlocks')}</th><th>${t('common.actions')}</th></tr></thead>
          <tbody>${chunkRows}</tbody>
        </table>`}
      </div>

      <!-- Danger zone -->
      <div class="danger-zone mt-4">
        <span class="text-sm font-semibold" style="color:var(--red)">${t('server.dangerZone')}</span>
        <p class="text-sm text-muted mt-1">${t('common.deleteConfirm')}</p>
        <button class="btn-cg btn-cg-danger mt-2" onclick="_cdv.deleteSet()">${t('common.delete')}</button>
      </div>
    </div>`;
}
