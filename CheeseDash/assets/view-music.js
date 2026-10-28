// ── CheeseMusic ──────────────────────────────────────────────────────────────

async function buildMusic(app) {
  const _mu = window._mu || {};
  _mu.tab = _mu.tab || 'assignments';
  _mu.assignments = _mu.assignments || [];
  _mu.tracks = _mu.tracks || [];
  _mu.zones = _mu.zones || [];
  _mu.config = _mu.config || {};
  _mu.status = _mu.status || [];
  _mu.editing = _mu.editing || null;
  _mu.search = _mu.search || '';
  _mu._app = app;
  window._mu = _mu;

  function _muRefresh() { app.viewHtml = ''; app.navigate('music'); }

  _mu.switchTab = function(tab) {
    _mu.tab = tab;
    _mu.editing = null;
    _muRefresh();
  };

  _mu.load = async function() {
    try {
      const [assignments, tracks, zones, config, status] = await Promise.all([
        API.music.assignments(), API.music.tracks(), API.music.zones(),
        API.music.config(), API.music.status()
      ]);
      _mu.assignments = assignments || [];
      _mu.tracks = tracks || [];
      _mu.zones = zones || [];
      _mu.config = config || {};
      _mu.status = status || [];
    } catch(e) { app.toast(e.message, 'error'); }
  };

  await _mu.load();

  _mu.deleteAssignment = async function(zoneId) {
    if (!await confirmAction(t('music.deleteConfirm', {id: zoneId}))) return;
    try {
      await API.music.deleteAssignment(zoneId);
      app.toast(t('music.deleted'), 'success');
      await _mu.load(); _muRefresh();
    } catch(e) { app.toast(e.message, 'error'); }
  };

  _mu.openCreate = function() {
    _mu.editing = {
      _isNew: true, zoneId: '', zoneName: '', tracks: [],
      mode: 'RANDOM', volume: 0.7, fadeInTicks: 60, fadeOutTicks: 60
    };
    _muRefresh();
  };

  _mu.openEditById = function(zoneId) {
    const a = _mu.assignments.find(x => x.zoneId === zoneId);
    if (!a) return;
    _mu.editing = { _isNew: false, ...a, tracks: [...(a.tracks || [])] };
    _muRefresh();
  };

  _mu.cancelEdit = function() {
    _mu.editing = null;
    _muRefresh();
  };

  _mu.toggleTrack = function(track) {
    if (!_mu.editing) return;
    const idx = _mu.editing.tracks.indexOf(track);
    if (idx >= 0) _mu.editing.tracks.splice(idx, 1);
    else _mu.editing.tracks.push(track);
    _muRefresh();
  };

  _mu.saveAssignment = async function() {
    const f = _mu.editing; if (!f) return;
    if (!f.zoneId) { app.toast(t('music.errorNoZone'), 'error'); return; }
    if (!f.tracks.length) { app.toast(t('music.errorNoTracks'), 'error'); return; }

    const data = {
      zoneId: f.zoneId,
      zoneName: f.zoneName || f.zoneId,
      tracks: f.tracks,
      mode: f.mode,
      volume: parseFloat(f.volume),
      fadeInTicks: parseInt(f.fadeInTicks),
      fadeOutTicks: parseInt(f.fadeOutTicks),
    };

    try {
      if (f._isNew) {
        await API.music.createAssignment(data);
        app.toast(t('music.created'), 'success');
      } else {
        await API.music.updateAssignment(f.zoneId, data);
        app.toast(t('common.success'), 'success');
      }
      _mu.editing = null;
      await _mu.load(); _muRefresh();
    } catch(e) { app.toast(e.message, 'error'); }
  };

  _mu.saveConfig = async function() {
    try {
      await API.music.updateConfig(_mu.config);
      app.toast(t('common.success'), 'success');
    } catch(e) { app.toast(e.message, 'error'); }
  };

  _mu.selectZone = function(zoneId) {
    if (!_mu.editing) return;
    _mu.editing.zoneId = zoneId;
    const z = _mu.zones.find(z => z.id === zoneId);
    if (z) _mu.editing.zoneName = z.name;
    _muRefresh();
  };

  _mu.uploadTrack = async function() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.ogg';
    input.multiple = true;
    input.onchange = async () => {
      for (const file of input.files) {
        if (!file.name.endsWith('.ogg')) {
          app.toast(t('music.errorNotOgg', {name: file.name}), 'error');
          continue;
        }
        try {
          await API.music.uploadTrack(file);
          app.toast(t('music.trackUploaded', {name: file.name}), 'success');
        } catch(e) { app.toast(e.message, 'error'); }
      }
      await _mu.load(); _muRefresh();
    };
    input.click();
  };

  _mu.deleteTrack = async function(trackName) {
    if (!await confirmAction(t('music.deleteTrackConfirm', {name: trackName}))) return;
    try {
      await API.music.deleteTrack(trackName);
      app.toast(t('music.trackDeleted'), 'success');
      await _mu.load(); _muRefresh();
    } catch(e) { app.toast(e.message, 'error'); }
  };

  // ── Tab: Assignments ──────────────────────────────────────────────────────
  function renderAssignments() {
    const filtered = _mu.assignments.filter(a =>
      !_mu.search || a.zoneId.toLowerCase().includes(_mu.search.toLowerCase())
      || (a.zoneName && a.zoneName.toLowerCase().includes(_mu.search.toLowerCase()))
    );

    let rows = '';
    for (const a of filtered) {
      rows += `<tr>
        <td class="td-mono">${escHtml(a.zoneId)}</td>
        <td>${escHtml(a.zoneName || '')}</td>
        <td>${a.tracks ? a.tracks.length : 0}</td>
        <td><span class="id-badge">${escHtml(a.mode || 'RANDOM')}</span></td>
        <td>${a.volume != null ? Math.round(a.volume * 100) + '%' : '70%'}</td>
        <td>
          <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_mu.openEditById('${escHtml(a.zoneId)}')">${t('common.edit')}</button>
          <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_mu.deleteAssignment('${escHtml(a.zoneId)}')">${t('common.delete')}</button>
        </td>
      </tr>`;
    }

    if (!rows) {
      rows = `<tr><td colspan="6" class="empty-state-text" style="text-align:center;padding:2rem">${t('music.noAssignments')}</td></tr>`;
    }

    return `
      <div class="section-card slide-in">
        <div class="section-card-header">
          <h2 class="section-card-title">${t('music.assignments')}</h2>
          <div style="display:flex;gap:0.5rem;align-items:center">
            <input type="text" class="input input-sm input-bordered" placeholder="${t('common.search')}" value="${escHtml(_mu.search)}"
              oninput="_mu.search=this.value;setTimeout(()=>{_mu.switchTab('assignments')},300)">
            <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_mu.openCreate()">+ ${t('music.addAssignment')}</button>
          </div>
        </div>
        <div class="section-card-body">
          <table class="cg-table">
            <thead><tr>
              <th>${t('music.zoneId')}</th>
              <th>${t('music.zoneName')}</th>
              <th>${t('music.trackCount')}</th>
              <th>${t('music.mode')}</th>
              <th>${t('music.volume')}</th>
              <th>${t('common.actions')}</th>
            </tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
      </div>

      ${renderStatus()}
    `;
  }

  // ── Status card ────────────────────────────────────────────────────────────
  function renderStatus() {
    if (!_mu.status.length) return '';
    let rows = '';
    for (const s of _mu.status) {
      rows += `<tr>
        <td>${escHtml(s.playerName)}</td>
        <td class="td-mono">${escHtml(s.zoneId)}</td>
      </tr>`;
    }
    return `
      <div class="section-card slide-in" style="margin-top:1rem">
        <div class="section-card-header">
          <h2 class="section-card-title">${t('music.activeNow')}</h2>
        </div>
        <div class="section-card-body">
          <table class="cg-table">
            <thead><tr><th>${t('common.player')}</th><th>${t('music.zoneId')}</th></tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
      </div>`;
  }

  // ── Tab: Tracks ────────────────────────────────────────────────────────────
  function renderTracks() {
    let rows = '';
    for (const tk of _mu.tracks) {
      rows += `<tr>
        <td class="td-mono">${escHtml(tk)}</td>
        <td><code style="color:var(--muted);font-size:0.75rem">cheesemusic:${escHtml(tk)}</code></td>
        <td>
          <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_mu.deleteTrack('${escHtml(tk)}')">${t('common.delete')}</button>
        </td>
      </tr>`;
    }
    if (!rows) {
      rows = `<tr><td colspan="3" class="empty-state-text" style="text-align:center;padding:2rem">${t('music.noTracksFound')}</td></tr>`;
    }

    return `
      <div class="section-card slide-in">
        <div class="section-card-header">
          <h2 class="section-card-title">${t('music.tracks')} (${_mu.tracks.length})</h2>
          <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_mu.uploadTrack()">+ ${t('music.uploadTrack')}</button>
        </div>
        <div class="section-card-body">
          <table class="cg-table">
            <thead><tr>
              <th>${t('music.trackName')}</th>
              <th>${t('music.soundId')}</th>
              <th>${t('common.actions')}</th>
            </tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
      </div>`;
  }

  // ── Edit modal ─────────────────────────────────────────────────────────────
  function renderEditModal() {
    const f = _mu.editing;
    if (!f) return '';

    // Zone selector
    let zoneOptions = `<option value="">${t('music.selectZone')}</option>`;
    for (const z of _mu.zones) {
      const sel = z.id === f.zoneId ? 'selected' : '';
      zoneOptions += `<option value="${escHtml(z.id)}" ${sel}>${escHtml(z.name)} (${escHtml(z.id)})</option>`;
    }

    // Track checkboxes
    let trackList = '';
    if (!_mu.tracks.length) {
      trackList = `<p class="empty-state-text">${t('music.noTracksFound')}</p>`;
    } else {
      for (const tk of _mu.tracks) {
        const checked = f.tracks.includes(tk) ? 'checked' : '';
        trackList += `<label style="display:flex;align-items:center;gap:0.5rem;padding:0.25rem 0;cursor:pointer">
          <input type="checkbox" class="checkbox checkbox-sm" ${checked} onchange="_mu.toggleTrack('${escHtml(tk)}')">
          <span class="mono">${escHtml(tk)}</span>
        </label>`;
      }
    }

    return `
    <dialog class="modal" id="mu-edit-modal" open>
      <div class="modal-box" style="max-width:560px">
        <button class="modal-close" onclick="_mu.cancelEdit()">✕</button>
        <h3 style="font-size:1.1rem;font-weight:600;margin-bottom:1rem">${f._isNew ? t('music.addAssignment') : t('music.editAssignment')}</h3>

        <div style="display:flex;flex-direction:column;gap:0.75rem">
          ${f._isNew ? `
          <div>
            <label class="field-label">${t('music.zone')} ${tip('tip.music.zone')}</label>
            <select class="select select-sm select-bordered w-full" onchange="_mu.selectZone(this.value)">
              ${zoneOptions}
            </select>
          </div>` : `
          <div>
            <label class="field-label">${t('music.zone')}</label>
            <div class="id-badge">${escHtml(f.zoneId)} — ${escHtml(f.zoneName)}</div>
          </div>`}

          <div>
            <label class="field-label">${t('music.tracks')} ${tip('tip.music.tracks')}</label>
            <div style="max-height:200px;overflow-y:auto;background:var(--bg-elevated);border-radius:0.5rem;padding:0.5rem">
              ${trackList}
            </div>
            <div style="margin-top:0.25rem;color:var(--muted);font-size:0.75rem">${t('music.tracksHelp', {count: f.tracks.length})}</div>
          </div>

          <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
            <div>
              <label class="field-label">${t('music.mode')} ${tip('tip.music.mode')}</label>
              <select class="select select-sm select-bordered w-full" onchange="_mu.editing.mode=this.value">
                <option value="RANDOM" ${f.mode==='RANDOM'?'selected':''}>Random</option>
                <option value="SEQUENTIAL" ${f.mode==='SEQUENTIAL'?'selected':''}>Sequential</option>
                <option value="SHUFFLE" ${f.mode==='SHUFFLE'?'selected':''}>Shuffle</option>
              </select>
            </div>
            <div>
              <label class="field-label">${t('music.volume')} ${tip('tip.music.volume')}</label>
              <input type="range" min="0" max="1" step="0.05" value="${f.volume}"
                oninput="_mu.editing.volume=parseFloat(this.value);this.nextElementSibling.textContent=Math.round(this.value*100)+'%'"
                style="width:100%">
              <span style="color:var(--muted);font-size:0.75rem">${Math.round(f.volume*100)}%</span>
            </div>
          </div>

          <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.75rem">
            <div>
              <label class="field-label">${t('music.fadeIn')} ${tip('tip.music.fade')}</label>
              <input type="number" class="input input-sm input-bordered w-full" value="${f.fadeInTicks}" min="0" max="200"
                oninput="_mu.editing.fadeInTicks=parseInt(this.value)">
            </div>
            <div>
              <label class="field-label">${t('music.fadeOut')}</label>
              <input type="number" class="input input-sm input-bordered w-full" value="${f.fadeOutTicks}" min="0" max="200"
                oninput="_mu.editing.fadeOutTicks=parseInt(this.value)">
            </div>
          </div>
        </div>

        <div style="display:flex;justify-content:flex-end;gap:0.5rem;margin-top:1rem">
          <button class="btn-cg btn-cg-ghost" onclick="_mu.cancelEdit()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-primary" onclick="_mu.saveAssignment()">${t('common.save')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>`;
  }

  // ── Tab: Config ────────────────────────────────────────────────────────────
  function renderConfig() {
    const c = _mu.config;
    return `
      <div class="section-card slide-in">
        <div class="section-card-header">
          <h2 class="section-card-title">${t('music.config')}</h2>
        </div>
        <div class="section-card-body" style="display:flex;flex-direction:column;gap:0.75rem;max-width:500px">

          <div style="display:flex;align-items:center;gap:0.75rem">
            <label class="field-label" style="min-width:160px">${t('music.enabled')} ${tip('tip.music.enabled')}</label>
            <input type="checkbox" class="toggle" ${c.enabled?'checked':''}
              onchange="_mu.config.enabled=this.checked">
          </div>

          <div style="display:flex;align-items:center;gap:0.75rem">
            <label class="field-label" style="min-width:160px">${t('music.defaultVolume')}</label>
            <input type="range" min="0" max="1" step="0.05" value="${c.defaultVolume || 0.7}"
              oninput="_mu.config.defaultVolume=parseFloat(this.value);this.nextElementSibling.textContent=Math.round(this.value*100)+'%'"
              style="flex:1">
            <span style="color:var(--muted);font-size:0.75rem;min-width:35px">${Math.round((c.defaultVolume||0.7)*100)}%</span>
          </div>

          <div style="display:flex;align-items:center;gap:0.75rem">
            <label class="field-label" style="min-width:160px">${t('music.defaultFadeIn')}</label>
            <input type="number" class="input input-sm input-bordered" style="width:100px" value="${c.defaultFadeInTicks || 60}" min="0" max="200"
              oninput="_mu.config.defaultFadeInTicks=parseInt(this.value)">
            <span style="color:var(--muted);font-size:0.75rem">ticks</span>
          </div>

          <div style="display:flex;align-items:center;gap:0.75rem">
            <label class="field-label" style="min-width:160px">${t('music.defaultFadeOut')}</label>
            <input type="number" class="input input-sm input-bordered" style="width:100px" value="${c.defaultFadeOutTicks || 60}" min="0" max="200"
              oninput="_mu.config.defaultFadeOutTicks=parseInt(this.value)">
            <span style="color:var(--muted);font-size:0.75rem">ticks</span>
          </div>

          <div style="display:flex;align-items:center;gap:0.75rem">
            <label class="field-label" style="min-width:160px">${t('music.checkInterval')} ${tip('tip.music.checkInterval')}</label>
            <input type="number" class="input input-sm input-bordered" style="width:100px" value="${c.checkIntervalTicks || 10}" min="1" max="100"
              oninput="_mu.config.checkIntervalTicks=parseInt(this.value)">
            <span style="color:var(--muted);font-size:0.75rem">ticks</span>
          </div>

          <button class="btn-cg btn-cg-primary btn-cg-sm" style="align-self:flex-start;margin-top:0.5rem" onclick="_mu.saveConfig()">${t('common.save')}</button>
        </div>
      </div>`;
  }

  // ── Tab bar + header ──────────────────────────────────────────────────────
  const header = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:1rem">
      <div class="detail-tabs">
        <button class="detail-tab ${_mu.tab==='assignments'?'active':''}" onclick="_mu.switchTab('assignments')">${t('music.assignments')}</button>
        <button class="detail-tab ${_mu.tab==='tracks'?'active':''}" onclick="_mu.switchTab('tracks')">${t('music.tracks')}</button>
        <button class="detail-tab ${_mu.tab==='config'?'active':''}" onclick="_mu.switchTab('config')">${t('music.config')}</button>
      </div>
      ${guideButton('music')}
    </div>`;

  let content = '';
  switch (_mu.tab) {
    case 'tracks': content = renderTracks(); break;
    case 'config': content = renderConfig(); break;
    default: content = renderAssignments(); break;
  }

  return `<div class="max-w-6xl mx-auto slide-in">
    ${header}
    ${content}
    ${_mu.editing ? renderEditModal() : ''}
  </div>`;
}
