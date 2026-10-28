// ── CheesePatrol ─────────────────────────────────────────────────────────────

async function buildPatrol(app) {
  const _pt = window._pt || {};
  _pt._app = app;
  _pt.patrols = _pt.patrols || [];

  _pt.load = async function() {
    try { _pt.patrols = await API.patrol.list(); }
    catch (e) { app.toast(e.message, 'error'); _pt.patrols = []; }
  };

  _pt.tpToNext = async function(npcUuid) {
    const pn = localStorage.getItem('cg_admin_player');
    if (!pn) { app.toast(t('patrol.noAdminPlayer'), 'error'); return; }
    try {
      const res = await API.patrol.tp({ npcUuid, playerName: pn });
      app.toast(t('patrol.tpSuccess', { x: res.x, y: res.y, z: res.z }), 'success');
    } catch (e) { app.toast(e.message, 'error'); }
  };

  window._pt = _pt;
  await _pt.load();

  // Sort: active first, then by name
  const sorted = [..._pt.patrols].sort((a, b) => {
    if (a.active !== b.active) return a.active ? -1 : 1;
    return (a.npcName || '').localeCompare(b.npcName || '');
  });

  const rows = sorted.map(p => {
    const statusCls = p.pausedBySystem ? 's-warn' : (p.active ? 's-online' : 's-offline');
    const statusLabel = p.pausedBySystem ? t('patrol.paused') : (p.active ? t('common.on') : t('common.off'));
    const dimShort = p.dimension.includes(':') ? p.dimension.split(':')[1] : p.dimension;
    return `<tr>
      <td style="font-weight:500">${escHtml(p.npcName)}</td>
      <td><span class="id-badge">${escHtml(dimShort)}</span></td>
      <td class="td-mono">${p.currentWaypointIndex + 1} / ${p.waypointCount}</td>
      <td><span class="status-dot ${statusCls}"></span> ${statusLabel}</td>
      <td class="td-mono">${p.speed}</td>
      <td>
        <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="window._pt.tpToNext('${escHtml(p.npcUuid)}')"
          title="${t('patrol.tpNextTitle')}">${t('patrol.tp')}</button>
      </td>
    </tr>`;
  }).join('');

  const activeCount = sorted.filter(p => p.active).length;

  return `<div class="max-w-6xl mx-auto slide-in">
    <div class="flex items-center gap-3 mb-6">
      <span style="font-size:var(--type-2xl)">\uD83D\uDEB6</span>
      <div>
        <h1 style="font-size:var(--type-xl);font-weight:700">${t('patrol.title')}</h1>
        <p style="color:var(--muted);font-size:var(--type-sm)">${t('patrol.subtitle')}</p>
      </div>
      <div class="ml-auto">${guideButton('patrol')}</div>
    </div>
    <div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('patrol.patrols')} (${activeCount}/${sorted.length})</span>
      </div>
      ${sorted.length === 0 ? `<div class="empty-state"><span class="empty-state-icon">\uD83D\uDEB6</span><span class="empty-state-text">${t('patrol.noPatrols')}</span></div>` : `
      <table class="cg-table"><thead><tr>
        <th>${t('patrol.npcName')}</th>
        <th>${t('patrol.dimension')}</th>
        <th>${t('patrol.waypoints')}</th>
        <th>${t('common.status')}</th>
        <th>${t('patrol.speed')}</th>
        <th></th>
      </tr></thead><tbody>${rows}</tbody></table>`}
    </div>
  </div>`;
}
