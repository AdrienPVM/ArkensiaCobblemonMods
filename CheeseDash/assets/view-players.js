// ── Players ──────────────────────────────────────────────────────────────────
async function buildPlayers(app) {
  let online, offline;
  try {
    [online, offline] = await Promise.all([API.players.list(), API.players.offline(20)]);
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const _p = window._pv = {
    online, offline, app,
    tab: 'online',
    _searchFilter: '',

    renderOnline() {
      const tbody = document.getElementById('players-online-tbody');
      const filtered = this._searchFilter
        ? this.online.filter(p => p.name.toLowerCase().includes(this._searchFilter.toLowerCase()))
        : this.online;
      if (tbody) tbody.innerHTML = filtered.length === 0
        ? '<tr><td colspan="7" class="text-center text-muted text-sm py-4">' + t('common.noResults') + '</td></tr>'
        : filtered.map(p => playerRowHtml(p)).join('');
      const cnt = document.getElementById('players-online-count');
      if (cnt) cnt.textContent = filtered.length + (this._searchFilter ? '/' + this.online.length : '');
    },

    renderOffline() {
      const tbody = document.getElementById('players-offline-tbody');
      if (tbody) {
        if (this.offline.length === 0) {
          tbody.innerHTML = '<tr><td colspan="3" class="text-center text-muted text-sm py-4">' + t('common.noResults') + '</td></tr>';
        } else {
          tbody.innerHTML = this.offline.map(p => `
            <tr class="cursor-pointer hover-elevated" onclick="_pv.openDetail('${escHtml(p.name)}')">
              <td>${playerAvatar(p.name)} <span class="text-sm ml-2">${escHtml(p.name)}</span></td>
              <td><span class="text-xs text-muted font-mono">${p.uuid ? p.uuid.substring(0,8) : '—'}</span></td>
              <td><span class="status-dot s-offline"></span><span class="text-xs text-muted">${t('common.offline')}</span></td>
            </tr>
          `).join('');
        }
      }
    },

    async refresh() {
      try {
        [this.online, this.offline] = await Promise.all([API.players.list(), API.players.offline(20)]);
        this.renderOnline();
        this.renderOffline();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    switchTab(t) {
      this.tab = t;
      document.getElementById('players-online-section').classList.toggle('hidden', t !== 'online');
      document.getElementById('players-offline-section').classList.toggle('hidden', t !== 'offline');
      document.getElementById('ptab-online').classList.toggle('text-amber', t === 'online');
      document.getElementById('ptab-offline').classList.toggle('text-amber', t === 'offline');
      document.getElementById('ptab-online').style.borderColor = t === 'online' ? 'var(--amber)' : '';
      document.getElementById('ptab-offline').style.borderColor = t === 'offline' ? 'var(--amber)' : '';
    },

    async openDetail(name) {
      try {
        const detail = await API.players.get(name);
        showPlayerDetailModal(detail);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async kick(name) {
      const modal = document.getElementById('player-kick-modal');
      if (!modal) return;
      document.getElementById('pkick-name').textContent = name;
      document.getElementById('pkick-msg').value = '';
      document.getElementById('pkick-btn').onclick = async () => {
        const msg = document.getElementById('pkick-msg').value.trim() || t('players.defaultKickMsg');
        try {
          await API.players.kick(name, msg);
          app.toast(t('players.actionDone'), 'success');
          modal.close();
          await this.refresh();
        } catch (e) { app.toast(e.message, 'error'); }
      };
      modal.showModal();
    },

    async teleport(name) {
      const modal = document.getElementById('player-tp-modal');
      if (!modal) return;
      document.getElementById('ptp-name').textContent = name;
      document.getElementById('ptp-x').value = '0';
      document.getElementById('ptp-y').value = '64';
      document.getElementById('ptp-z').value = '0';
      document.getElementById('ptp-dim').value = '';
      document.getElementById('ptp-btn').onclick = async () => {
        const dim = document.getElementById('ptp-dim').value.trim();
        const data = {};
        if (dim) data.dimension = dim;
        const x = document.getElementById('ptp-x').value.trim();
        const y = document.getElementById('ptp-y').value.trim();
        const z = document.getElementById('ptp-z').value.trim();
        if (x !== '' || y !== '' || z !== '') {
          data.x = Number(x) || 0;
          data.y = Number(y) || 64;
          data.z = Number(z) || 0;
        }
        try {
          await API.players.tp(name, data);
          app.toast(t('players.actionDone'), 'success');
          modal.close();
        } catch (e) { app.toast(e.message, 'error'); }
      };
      modal.showModal();
    },

    async gamemode(name) {
      const modal = document.getElementById('player-gm-modal');
      if (!modal) return;
      document.getElementById('pgm-name').textContent = name;
      document.getElementById('pgm-btn').onclick = async () => {
        const mode = document.getElementById('pgm-select').value;
        try {
          await API.players.gamemode(name, mode);
          app.toast(t('players.actionDone'), 'success');
          modal.close();
          await this.refresh();
        } catch (e) { app.toast(e.message, 'error'); }
      };
      modal.showModal();
    },

    async message(name) {
      const modal = document.getElementById('player-msg-modal');
      if (!modal) return;
      document.getElementById('pmsg-name').textContent = name;
      document.getElementById('pmsg-text').value = '';
      document.getElementById('pmsg-btn').onclick = async () => {
        const text = document.getElementById('pmsg-text').value.trim();
        const type = document.getElementById('pmsg-type').value;
        if (!text) return;
        try {
          await API.players.message(name, text, type);
          app.toast(t('players.actionDone'), 'success');
          modal.close();
        } catch (e) { app.toast(e.message, 'error'); }
      };
      modal.showModal();
    },
  };

  function playerAvatar(name) {
    const colors = ['#ef4444','#f59e0b','#22c55e','#3b82f6','#8b5cf6','#ec4899','#14b8a6','#f97316'];
    const idx = name.split('').reduce((a, c) => a + c.charCodeAt(0), 0) % colors.length;
    const initial = name.charAt(0).toUpperCase();
    return '<span style="display:inline-flex;align-items:center;justify-content:center;width:1.5rem;height:1.5rem;border-radius:4px;background:'+colors[idx]+';color:var(--text-heading);font-size:0.75rem;font-weight:bold">'+initial+'</span>';
  }

  function gmBadge(gm) {
    const c = { survival:'text-green', creative:'text-cyan', adventure:'text-amber', spectator:'text-muted' };
    return '<span class="text-xs font-mono ' + (c[gm] || 'text-muted') + '">' + (gm || '—') + '</span>';
  }

  function pingColor(ping) {
    if (ping < 80) return 'text-green';
    if (ping < 200) return 'text-amber';
    return 'text-red';
  }

  function playerRowHtml(p) {
    return `<tr class="cursor-pointer hover-elevated" onclick="_pv.openDetail('${escHtml(p.name)}')">
      <td>${playerAvatar(p.name)} <span class="text-sm ml-2">${escHtml(p.name)}</span></td>
      <td>${worldBadgeHtml(p.world)}</td>
      <td class="td-mono">${Math.round(p.x)}, ${Math.round(p.y)}, ${Math.round(p.z)}</td>
      <td>${gmBadge(p.gameMode)}</td>
      <td><span class="text-xs font-mono ${pingColor(p.ping)}">${p.ping}ms</span></td>
      <td>
        <span class="text-sm">${p.health != null ? Math.round(p.health*10)/10 : '—'}</span>
        <span class="text-muted text-sm">/ ${p.maxHealth != null ? Math.round(p.maxHealth*10)/10 : '—'}</span>
      </td>
      <td class="flex gap-1" onclick="event.stopPropagation()">
        <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_pv.teleport('${escHtml(p.name)}')" title="${t('players.tpTo')}">TP</button>
        <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_pv.gamemode('${escHtml(p.name)}')" title="${t('players.gameMode')}">GM</button>
        <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_pv.message('${escHtml(p.name)}')" title="${t('players.message')}">MSG</button>
        <button class="btn-cg btn-cg-danger btn-cg-sm" onclick="_pv.kick('${escHtml(p.name)}')" title="${t('players.kick')}">${t('players.kick')}</button>
      </td>
    </tr>`;
  }

  function showPlayerDetailModal(p) {
    const modal = document.getElementById('player-detail-modal');
    if (!modal) return;
    document.getElementById('pdm-title').textContent = p.name;
    const body = document.getElementById('pdm-body');
    if (body) {
      body.innerHTML = `
        <div class="grid grid-cols-2 gap-3">
          <div class="stat-card"><span class="stat-label">${t('players.uuid')}</span><span class="stat-value text-sm font-mono">${escHtml(p.uuid || '—')}</span></div>
          <div class="stat-card"><span class="stat-label">${t('common.status')}</span><span class="stat-value text-sm ${p.online ? 'text-green' : 'text-muted'}">${p.online ? t('common.online') : t('common.offline')}</span></div>
          ${p.online ? `
          <div class="stat-card"><span class="stat-label">${t('common.world')}</span><span class="stat-value text-sm">${fmtWorld(p.world)}</span></div>
          <div class="stat-card"><span class="stat-label">${t('common.position')}</span><span class="stat-value text-sm font-mono">${Math.round(p.x || 0)}, ${Math.round(p.y || 0)}, ${Math.round(p.z || 0)}</span></div>
          <div class="stat-card"><span class="stat-label">${t('players.health')}</span><span class="stat-value text-sm">${p.health != null ? Math.round(p.health*10)/10 + ' / ' + Math.round(p.maxHealth*10)/10 : '—'}</span></div>
          <div class="stat-card"><span class="stat-label">${t('players.food')}</span><span class="stat-value text-sm">${p.food != null ? p.food + ' / 20' : '—'}</span></div>
          <div class="stat-card"><span class="stat-label">${t('players.gameMode')}</span><span class="stat-value text-sm">${p.gameMode || '—'}</span></div>
          <div class="stat-card"><span class="stat-label">${t('players.xpLevel')}</span><span class="stat-value text-sm">${p.xpLevel != null ? p.xpLevel : '—'}</span></div>
          <div class="stat-card"><span class="stat-label">${t('players.fly')}</span><span class="stat-value text-sm">${p.fly ? t('common.enabled') : t('common.disabled')}</span></div>
          <div class="stat-card"><span class="stat-label">${t('players.ping')}</span><span class="stat-value text-sm font-mono">${p.ping || '—'}ms</span></div>
          <div class="stat-card"><span class="stat-label">${t('players.advancements')}</span><span class="stat-value text-sm">${p.advDone != null ? p.advDone + ' / ' + p.advTotal : '—'}</span></div>
          ` : ''}
        </div>`;
    }
    modal.showModal();
  }

  const onlineRows = online.map(p => playerRowHtml(p)).join('');

  // Render offline data after DOM is ready
  setTimeout(() => { _p.renderOffline(); }, 50);

  return `
    <div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <div class="section-card">
        <div class="section-card-header">
          <span class="section-card-title">${t('players.title')}</span>
          <button class="btn-cg btn-cg-ghost" onclick="_pv.refresh()">${t('common.refresh')}</button>
        </div>
        <div class="flex gap-2 px-4 pb-3 border-b border-bg-border items-center">
          <button id="ptab-online" class="btn-cg btn-cg-ghost text-amber border-b-2" style="border-color:var(--amber)" onclick="_pv.switchTab('online')">${t('common.online')} <span id="players-online-count" class="text-muted ml-1">${online.length}</span></button>
          <button id="ptab-offline" class="btn-cg btn-cg-ghost" onclick="_pv.switchTab('offline')">${t('common.offline')}</button>
          <div class="ml-auto">
            <input id="players-search" class="input input-sm input-bordered w-44" placeholder="${t('common.search')}"
              oninput="_pv._searchFilter=this.value;_pv.renderOnline()">
          </div>
        </div>

        <div id="players-online-section">
          ${online.length === 0 ? '<div class="empty-state py-8"><span class="empty-state-text text-sm text-muted">' + t('common.noResults') + '</span></div>' : `
          <table class="cg-table">
            <thead><tr><th>${t('common.player')}</th><th>${t('common.world')}</th><th>${t('common.position')}</th><th>${t('players.gameMode')}</th><th>${t('players.ping')}</th><th>${t('players.health')}</th><th>${t('common.actions')}</th></tr></thead>
            <tbody id="players-online-tbody">${onlineRows}</tbody>
          </table>`}
        </div>

        <div id="players-offline-section" class="hidden">
          <table class="cg-table">
            <thead><tr><th>${t('common.player')}</th><th>${t('players.uuid')}</th><th>${t('common.status')}</th></tr></thead>
            <tbody id="players-offline-tbody"></tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Player detail modal -->
    <dialog id="player-detail-modal" class="modal">
      <div class="modal-box max-w-lg">
        <form method="dialog"><button class="modal-close">\u2715</button></form>
        <h3 id="pdm-title" class="font-bold text-amber text-sm"></h3>
        <div id="pdm-body" class="mt-3"></div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- TP modal -->
    <dialog id="player-tp-modal" class="modal">
      <div class="modal-box max-w-sm">
        <h3 class="font-bold text-amber text-sm">${t('players.tpTo')} <span id="ptp-name"></span></h3>
        <div class="mt-3">
          <label class="text-sm text-muted">${t('eggs.dimension')}</label>
          <input id="ptp-dim" list="global-dims-dl" class="input input-sm w-full input-bordered mt-1" placeholder="${t('players.dimPlaceholder')}">
          <div class="grid grid-cols-3 gap-2 mt-2">
            <div><label class="text-sm text-muted">X</label><input id="ptp-x" type="number" class="input input-sm w-full input-bordered mt-1" value="0"></div>
            <div><label class="text-sm text-muted">Y</label><input id="ptp-y" type="number" class="input input-sm w-full input-bordered mt-1" value="64"></div>
            <div><label class="text-sm text-muted">Z</label><input id="ptp-z" type="number" class="input input-sm w-full input-bordered mt-1" value="0"></div>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
          <button id="ptp-btn" class="btn-cg btn-cg-primary">${t('players.tpTo')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- Gamemode modal -->
    <dialog id="player-gm-modal" class="modal">
      <div class="modal-box max-w-xs">
        <h3 class="font-bold text-amber text-sm">${t('players.gameMode')} — <span id="pgm-name"></span></h3>
        <select id="pgm-select" class="select select-sm w-full select-bordered mt-3">
          <option value="SURVIVAL">${t('players.gmSurvival')}</option>
          <option value="CREATIVE">${t('players.gmCreative')}</option>
          <option value="ADVENTURE">${t('players.gmAdventure')}</option>
          <option value="SPECTATOR">${t('players.gmSpectator')}</option>
        </select>
        <div class="flex justify-end gap-2 mt-4">
          <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
          <button id="pgm-btn" class="btn-cg btn-cg-primary">${t('common.apply')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- Message modal -->
    <dialog id="player-msg-modal" class="modal">
      <div class="modal-box max-w-sm">
        <h3 class="font-bold text-amber text-sm">${t('players.message')} — <span id="pmsg-name"></span></h3>
        <div class="mt-3">
          <select id="pmsg-type" class="select select-sm w-full select-bordered">
            <option value="chat">${t('players.msgChat')}</option>
            <option value="actionbar">${t('players.msgActionbar')}</option>
            <option value="title">${t('players.msgTitle')}</option>
            <option value="subtitle">${t('players.msgSubtitle')}</option>
          </select>
          <textarea id="pmsg-text" class="textarea textarea-sm textarea-bordered w-full mt-2" rows="3" placeholder="${t('players.messagePlaceholder')}"></textarea>
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
          <button id="pmsg-btn" class="btn-cg btn-cg-primary">${t('common.send')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- Kick modal -->
    <dialog id="player-kick-modal" class="modal">
      <div class="modal-box max-w-sm">
        <h3 class="font-bold text-red text-sm">${t('players.kick')} — <span id="pkick-name"></span></h3>
        <div class="mt-3">
          <label class="text-sm text-muted">${t('server.messageOptional')}</label>
          <input id="pkick-msg" class="input input-sm w-full input-bordered mt-1" placeholder="${t('players.kickPlaceholder')}">
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
          <button id="pkick-btn" class="btn-cg btn-cg-danger">${t('players.kick')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>`;
}
