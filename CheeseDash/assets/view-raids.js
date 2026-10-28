// view-raids.js — CheeseRaids dashboard (modular server raids: Hoopa invasion, ...)

window._raids = {
  async refresh() {
    const el = document.getElementById('raids-body');
    if (el) el.innerHTML = await _raids.body();
  },
  async start(id) {
    try { await API.raids.start(id); _toast(`Raid « ${id} » démarré`, 'success'); }
    catch (e) { _toast(e.message, 'error'); }
    await _raids.refresh();
  },
  async stop(id) {
    if (!(await confirmAction(`Stopper le raid « ${id} » et nettoyer l'arène ?`))) return;
    try { await API.raids.stop(id); _toast(`Raid « ${id} » stoppé`, 'success'); }
    catch (e) { _toast(e.message, 'error'); }
    await _raids.refresh();
  },
  async reload() {
    try { await API.raids.reload(); _toast('Définitions rechargées', 'success'); }
    catch (e) { _toast(e.message, 'error'); }
    await _raids.refresh();
  },

  async body() {
    let data;
    try { data = await API.raids.overview(); }
    catch (e) { return `<div class="empty-state"><span class="empty-state-icon">\u{1F47B}</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`; }

    const defs = data.definitions || [];
    const instances = data.instances || [];
    const instById = {};
    instances.forEach(i => instById[i.raidId] = i);

    if (!defs.length) {
      return `<div class="empty-state"><span class="empty-state-icon">\u{1F47B}</span><span class="empty-state-text">Aucun raid configuré. Un <span class="mono">hoopa.json</span> par défaut est créé dans <span class="mono">config/cheeseraids/raids/</span>.</span></div>`;
    }

    const cards = defs.map(d => {
      const inst = instById[d.id];
      const phase = inst ? inst.phase : 'IDLE';
      const dotClass = inst ? 's-online' : 's-idle';
      const sched = d.autoTrigger
        ? `auto ${String(d.hour).padStart(2,'0')}:${String(d.minute).padStart(2,'0')} · tous les ${d.everyNDays}j`
        : 'déclenchement manuel';
      const actions = inst
        ? `<button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_raids.stop('${escHtml(d.id)}')">Stopper</button>`
        : `<button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_raids.start('${escHtml(d.id)}')" ${d.enabled ? '' : 'disabled'}>Démarrer</button>`;
      return `
        <div class="section-card">
          <div class="section-card-header">
            <span class="section-card-title">${escHtml(d.icon || '')} ${escHtml(d.displayName || d.id)}</span>
            <span class="id-badge">${escHtml(d.id)}</span>
          </div>
          <div class="section-card-body">
            <div class="flex items-center gap-2 mb-2">
              <span class="status-dot ${dotClass}"></span>
              <span class="mono">${escHtml(phase)}</span>
              <span class="text-muted">·</span>
              <span class="text-muted">${escHtml(sched)}</span>
            </div>
            ${inst ? _raids.instanceHtml(inst) : '<div class="text-muted">Inactif</div>'}
            <div class="mt-3">${actions}</div>
          </div>
        </div>`;
    }).join('');

    return `
      <div class="filter-bar mb-3">
        <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_raids.refresh()">\u{21BB} Rafraîchir</button>
        <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_raids.reload()">Recharger les définitions</button>
      </div>
      <div class="grid gap-4" style="grid-template-columns:repeat(auto-fill,minmax(340px,1fr))">${cards}</div>`;
  },

  instanceHtml(inst) {
    const players = inst.players || [];
    const rows = players.map(p => {
      const state = p.finished ? 'Terminé' : p.arrived ? 'Au centre' : p.entered ? 'Parkour' : 'En attente';
      return `<tr><td class="td-mono">#${p.slot}</td><td>${escHtml(p.name || '')}</td><td>${escHtml(state)}</td></tr>`;
    }).join('');
    return `
      <div class="bg-elevated-box p-2 rounded">
        <div class="mb-1"><span class="text-muted">Participants :</span> <b>${players.length}</b>
          ${inst.bossSpawned ? '<span class="text-muted">· boss actif</span>' : ''}</div>
        ${players.length ? `<table class="cg-table"><thead><tr><th>Slot</th><th>Joueur</th><th>Progression</th></tr></thead><tbody>${rows}</tbody></table>` : '<div class="text-muted">Aucun joueur dans la faille</div>'}
      </div>`;
  },
};

async function buildRaids(app) {
  // Live updates via the "raids" WebSocket channel (best-effort).
  try { app.subscribe && app.subscribe(['raids']); } catch (e) {}
  return `<div class="max-w-6xl mx-auto slide-in">
    <div id="raids-body">${await _raids.body()}</div>
  </div>`;
}
