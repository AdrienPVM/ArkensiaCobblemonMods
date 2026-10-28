// ── Admin (Accounts, Webhooks, Audit) ────────────────────────────────────────

async function buildAccounts(app) {
  if (!API.canAdmin()) {
    return `<div class="empty-state"><span class="empty-state-icon">🔒</span>
      <span class="empty-state-text">${t('error.forbidden')}</span></div>`;
  }

  let accounts, sessions;
  try {
    [accounts, sessions] = await Promise.all([
      API.admin.accounts(),
      API.admin.sessions(),
    ]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span>
      <span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const ROLE_BADGES = {
    SUPER_ADMIN: '<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-mono" style="background:rgba(239,68,68,0.15);color:var(--red)">SUPER_ADMIN</span>',
    OPERATOR:    '<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-mono" style="background:rgba(245,166,35,0.15);color:var(--amber)">OPERATOR</span>',
    READ_ONLY:   '<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-mono" style="background:rgba(107,114,128,0.15);color:var(--muted-light)">READ_ONLY</span>',
  };

  window._acc = {
    async createAccount() {
      const username = document.getElementById('acc-new-username')?.value?.trim();
      const role = document.getElementById('acc-new-role')?.value;
      if (!username) { app.toast(t('common.required'), 'warn'); return; }
      try {
        const result = await API.admin.createAccount(username, role);
        app.toast(t('accounts.token') + ': ' + result.token, 'success', 15000);
        // Show token in a prominent way
        const tokenEl = document.getElementById('acc-created-token');
        if (tokenEl) {
          tokenEl.textContent = result.token;
          tokenEl.closest('.hidden')?.classList.remove('hidden');
          document.getElementById('acc-created-section')?.classList.remove('hidden');
        }
        app.navigate('accounts');
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async changeRole(username) {
      const sel = document.getElementById('acc-role-' + username);
      if (!sel) return;
      try {
        await API.admin.updateAccount(username, sel.value);
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async regenToken(username) {
      if (!await confirmAction(t('admin.regenTokenWarning', {username: username}))) return;
      try {
        const result = await API.admin.regenAccountToken(username);
        app.toast(t('accounts.token') + ': ' + result.token, 'success', 15000);
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async deleteAccount(username) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.admin.deleteAccount(username);
        app.toast(t('common.success'), 'success');
        app.navigate('accounts');
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async revokeSession(sessionId) {
      try {
        await API.admin.revokeSession(sessionId);
        app.toast(t('common.success'), 'success');
        app.navigate('accounts');
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  const accRows = accounts.map(a => `
    <tr>
      <td><span class="font-mono font-semibold text-sm">${escHtml(a.username)}</span></td>
      <td>${ROLE_BADGES[a.role] || a.role}</td>
      <td class="text-sm text-muted">${I18N.fmtDate(a.createdAt)}</td>
      <td class="flex gap-1">
        <select id="acc-role-${escHtml(a.username)}" class="select select-xs select-bordered font-mono text-xs"
          onchange="_acc.changeRole('${escHtml(a.username)}')">
          <option value="READ_ONLY" ${a.role === 'READ_ONLY' ? 'selected' : ''}>READ_ONLY</option>
          <option value="OPERATOR" ${a.role === 'OPERATOR' ? 'selected' : ''}>OPERATOR</option>
          <option value="SUPER_ADMIN" ${a.role === 'SUPER_ADMIN' ? 'selected' : ''}>SUPER_ADMIN</option>
        </select>
        <button class="btn-cg btn-cg-ghost" style="font-size:0.75rem;height:1.75rem;padding:0 0.5rem"
          onclick="_acc.regenToken('${escHtml(a.username)}')" title="${t('accounts.token')}">🔑</button>
        <button class="btn-cg btn-cg-danger" style="font-size:0.75rem;height:1.75rem;padding:0 0.5rem"
          onclick="_acc.deleteAccount('${escHtml(a.username)}')" title="${t('common.delete')}">✕</button>
      </td>
    </tr>
  `).join('');

  const sessionRows = sessions.map(s => `
    <tr>
      <td><span class="font-mono text-sm">${escHtml(s.username)}</span></td>
      <td>${ROLE_BADGES[s.role] || s.role}</td>
      <td class="text-sm text-muted">${escHtml(s.ip)}</td>
      <td class="text-sm text-muted">${I18N.fmtDate(s.lastActivity)}</td>
      <td class="text-sm text-muted">${Math.round(s.expiresIn / 3600000)}h</td>
      <td>
        <button class="btn-cg btn-cg-ghost" style="font-size:0.75rem;height:1.75rem"
          onclick="_acc.revokeSession('${escHtml(s.sessionId)}')" title="${t('accounts.revoke')}">${t('accounts.revoke')}</button>
      </td>
    </tr>
  `).join('');

  return `
  <div class="max-w-5xl mx-auto slide-in">
    <!-- Current session info -->
    <div class="mb-4 p-3 rounded-lg section-card">
      <div class="flex items-center gap-3">
        <span class="text-lg">👤</span>
        <div>
          <div class="text-sm font-semibold">${t('common.player')} <span class="text-amber font-mono">${escHtml(API.getUsername())}</span></div>
          <div class="text-sm text-muted">${t('accounts.role')} : ${ROLE_BADGES[API.getRole()] || API.getRole()}</div>
        </div>
      </div>
    </div>

    <!-- Accounts -->
    <div class="section-card mb-4">
      <div class="section-card-header">
        <span class="section-card-title">${t('accounts.title')}</span>
        <span class="text-muted text-xs font-mono ml-2">${t('accounts.countPlusMaster', {n: accounts.length})}</span>
      </div>
      <div class="section-card-body">
        <div class="mb-3 p-3 rounded border flex items-center gap-3" style="background:var(--bg-elevated);border-color:var(--bg-border)">
          <span class="font-mono font-semibold text-sm text-amber">admin</span>
          ${ROLE_BADGES.SUPER_ADMIN}
          <span class="text-xs text-muted ml-auto">${t('accounts.masterTokenLabel')}</span>
        </div>

        ${accounts.length > 0 ? `
        <table class="cg-table mb-3">
          <thead><tr>
            <th>${t('accounts.username')}</th><th>${t('accounts.role')}</th><th>${t('accounts.createdAt')}</th><th>${t('common.actions')}</th>
          </tr></thead>
          <tbody>${accRows}</tbody>
        </table>
        ` : '<div class="text-sm text-muted mb-3">' + t('common.noResults') + '</div>'}

        <!-- Create new account -->
        <div class="p-3 rounded border" style="background:var(--bg-elevated);border-color:var(--bg-border)">
          <div class="text-sm font-semibold mb-2">${t('accounts.newAccount')}</div>
          <div class="flex gap-2 items-end flex-wrap">
            <div>
              <label class="text-sm text-muted block mb-1">${t('accounts.username')}</label>
              <input id="acc-new-username" type="text" class="input input-sm input-bordered font-mono text-sm" placeholder="${t('accounts.usernamePlaceholder')}" />
            </div>
            <div>
              <label class="text-sm text-muted block mb-1">${t('accounts.role')} ${tip('tip.accounts.role')}</label>
              <select id="acc-new-role" class="select select-sm select-bordered font-mono text-sm">
                <option value="READ_ONLY">READ_ONLY</option>
                <option value="OPERATOR" selected>OPERATOR</option>
                <option value="SUPER_ADMIN">SUPER_ADMIN</option>
              </select>
            </div>
            <button class="btn-cg btn-cg-primary" onclick="_acc.createAccount()">${t('common.create')}</button>
          </div>
          <div id="acc-created-section" class="hidden mt-3 p-2 rounded border" style="background:rgba(34,197,94,0.1);border-color:rgba(34,197,94,0.3)">
            <div class="text-xs text-muted mb-1">${t('accounts.token')}</div>
            <code id="acc-created-token" class="text-sm font-mono text-green break-all select-all"></code>
          </div>
        </div>
      </div>
    </div>

    <!-- Active sessions -->
    <div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('accounts.activeSessions')}</span>
        <span class="text-muted text-xs font-mono ml-2">${t('accounts.sessionCount', {n: sessions.length})}</span>
      </div>
      <div class="section-card-body">
        ${sessions.length > 0 ? `
        <table class="cg-table">
          <thead><tr>
            <th>${t('accounts.username')}</th><th>${t('accounts.role')}</th><th>${t('accounts.ip')}</th><th>${t('accounts.lastActivity')}</th><th>${t('accounts.expiresIn')}</th><th></th>
          </tr></thead>
          <tbody>${sessionRows}</tbody>
        </table>
        ` : '<div class="empty-state"><span class="empty-state-icon">🔑</span><span class="empty-state-text">' + t('common.noResults') + '</span></div>'}
      </div>
    </div>

    <!-- Role descriptions -->
    <div class="mt-4 p-3 rounded-lg section-card">
      <div class="text-sm font-semibold mb-2">${t('accounts.role')}</div>
      <div class="grid gap-2">
        <div class="flex items-start gap-2">
          ${ROLE_BADGES.READ_ONLY}
          <span class="text-sm text-muted">${t('accounts.roleReadOnly')}</span>
        </div>
        <div class="flex items-start gap-2">
          ${ROLE_BADGES.OPERATOR}
          <span class="text-sm text-muted">${t('accounts.roleOperator')}</span>
        </div>
        <div class="flex items-start gap-2">
          ${ROLE_BADGES.SUPER_ADMIN}
          <span class="text-sm text-muted">${t('accounts.roleSuperAdmin')}</span>
        </div>
      </div>
    </div>
  </div>`;
}


// ══════════════════════════════════════════════════════════════════════════════
//  WEBHOOKS VIEW
// ══════════════════════════════════════════════════════════════════════════════

async function buildWebhooks(app) {
  let webhooks, eventTypes;
  try {
    [webhooks, eventTypes] = await Promise.all([API.webhooks.list(), API.webhooks.eventTypes()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const categories = {};
  eventTypes.forEach(et => {
    if (!categories[et.category]) categories[et.category] = [];
    categories[et.category].push(et);
  });

  const _w = window._wh = {
    webhooks, eventTypes, categories, app,
    selectedWebhook: null,

    async refresh() {
      try { this.webhooks = await API.webhooks.list(); renderWebhookList(this.webhooks); }
      catch (e) { app.toast(e.message, 'error'); }
    },

    async addWebhook() {
      const modal = document.getElementById('webhook-add-modal');
      if (!modal) return;
      document.getElementById('wh-name').value = '';
      document.getElementById('wh-url').value = '';
      modal.showModal();
    },

    async confirmAdd() {
      const name = document.getElementById('wh-name').value.trim();
      const url = document.getElementById('wh-url').value.trim();
      if (!name || !url) { app.toast(t('common.required'), 'error'); return; }
      try {
        await API.webhooks.create({ name, url });
        document.getElementById('webhook-add-modal').close();
        app.toast(t('common.success'), 'success');
        this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async selectWebhook(id) {
      try {
        this.selectedWebhook = await API.webhooks.get(id);
        renderWebhookDetail(this.selectedWebhook, this.categories);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async saveWebhook() {
      if (!this.selectedWebhook) return;
      const events = [];
      document.querySelectorAll('.wh-event-cb:checked').forEach(cb => events.push(cb.value));
      const scoreboardFilter = [];
      document.querySelectorAll('.wh-sb-cb:checked').forEach(cb => scoreboardFilter.push(cb.value));
      const name = document.getElementById('wh-edit-name')?.value?.trim() || this.selectedWebhook.name;
      const url = document.getElementById('wh-edit-url')?.value?.trim() || this.selectedWebhook.url;
      const avatarUrl = document.getElementById('wh-edit-avatar')?.value?.trim() || '';
      const enabled = document.getElementById('wh-edit-enabled')?.checked ?? true;
      try {
        await API.webhooks.update(this.selectedWebhook.id, { name, url, avatarUrl, enabled, events, scoreboardFilter });
        app.toast(t('common.success'), 'success');
        this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async testWebhook(id) {
      const testId = id || this.selectedWebhook?.id;
      if (!testId) return;
      try {
        const result = await API.webhooks.test(testId);
        if (result.success) app.toast(t('common.success'), 'success');
        else app.toast(result.error || t('common.error'), 'error');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteWebhook(id) {
      if (!await confirmAction(t('webhooks.deleteConfirm'))) return;
      try {
        await API.webhooks.remove(id);
        app.toast(t('common.success'), 'success');
        this.selectedWebhook = null;
        renderWebhookDetail(null, this.categories);
        this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async toggleWebhook(id) {
      const wh = this.webhooks.find(w => w.id === id);
      if (!wh) return;
      try { await API.webhooks.update(id, { enabled: !wh.enabled }); this.refresh(); }
      catch (e) { app.toast(e.message, 'error'); }
    },

    closeDetail() {
      this.selectedWebhook = null;
      renderWebhookDetail(null, this.categories);
    },
  };

  function renderWebhookList(list) {
    const el = document.getElementById('webhook-list');
    if (!el) return;
    if (list.length === 0) {
      el.innerHTML = '<div class="text-sm text-muted text-center py-6">' + t('common.noResults') + '</div>';
      return;
    }
    el.innerHTML = list.map(w => {
      const evtCount = w.eventCount || 0;
      return `<div class="flex items-center justify-between p-3 rounded-lg bg-elevated-box mb-2 hover:border-amber/30 cursor-pointer transition-colors" onclick="_wh.selectWebhook('${w.id}')">
        <div class="flex items-center gap-3 min-w-0 flex-1">
          <span class="text-lg">\uD83D\uDD14</span>
          <div class="min-w-0">
            <div class="text-sm font-medium truncate">${escHtml(w.name)}</div>
            <div class="text-xs text-muted font-mono truncate">${escHtml(w.url)}</div>
          </div>
        </div>
        <div class="flex items-center gap-3 ml-3">
          <span class="text-xs text-muted">${t('webhooks.eventCount', {n: evtCount})}</span>
          <span class="status-dot ${w.enabled ? 's-online' : 's-offline'}"></span>
          <button class="btn-cg btn-cg-ghost" style="font-size:0.65rem;padding:2px 6px" onclick="event.stopPropagation();_wh.testWebhook('${w.id}')" title="${t('common.test')}">${t('common.test')}</button>
          <button class="btn-cg btn-cg-ghost" style="font-size:0.65rem;padding:2px 6px" onclick="event.stopPropagation();_wh.toggleWebhook('${w.id}')">${w.enabled ? '\u23F8' : '\u25B6'}</button>
        </div>
      </div>`;
    }).join('');
  }

  function renderWebhookDetail(wh, cats) {
    const el = document.getElementById('webhook-detail');
    if (!el) return;
    if (!wh) {
      el.innerHTML = '<div class="text-sm text-muted text-center py-8">' + t('common.select') + '</div>';
      return;
    }
    let eventsHtml = '';
    for (const [cat, types] of Object.entries(cats)) {
      const cbs = types.map(et => {
        const checked = (wh.events || []).includes(et.id) ? 'checked' : '';
        return `<label class="flex items-center gap-2 text-xs cursor-pointer py-0.5">
          <input type="checkbox" class="wh-event-cb checkbox checkbox-sm checkbox-primary" value="${et.id}" ${checked}>
          <span style="color:${et.color}">\u25CF</span> ${escHtml(et.label)}
        </label>`;
      }).join('');
      eventsHtml += `<div class="mb-3"><div class="text-xs text-muted uppercase tracking-wider mb-1">${escHtml(cat)}</div><div class="grid grid-cols-2 gap-x-4">${cbs}</div></div>`;
    }
    el.innerHTML = `
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-sm font-semibold">\uD83D\uDD14 ${escHtml(wh.name)}</h3>
        <div class="flex gap-2">
          <button class="btn-cg btn-cg-ghost" style="font-size:0.7rem" onclick="_wh.closeDetail()">${t('common.close')}</button>
          <button class="btn-cg btn-cg-danger" style="font-size:0.7rem" onclick="_wh.deleteWebhook('${wh.id}')">${t('common.delete')}</button>
        </div>
      </div>
      <div class="space-y-3">
        <div><label class="text-xs text-muted block mb-1">${t('common.name')}</label><input id="wh-edit-name" type="text" value="${escHtml(wh.name)}" class="input input-sm input-bordered w-full"></div>
        <div><label class="text-xs text-muted block mb-1">${t('webhooks.webhookUrl')}</label><input id="wh-edit-url" type="text" value="${escHtml(wh.url)}" class="input input-sm input-bordered w-full font-mono text-xs"></div>
        <div><label class="text-xs text-muted block mb-1">${t('webhooks.avatarUrl')}</label><input id="wh-edit-avatar" type="text" value="${escHtml(wh.avatarUrl || '')}" class="input input-sm input-bordered w-full" placeholder="https://..."></div>
        <div class="flex items-center gap-2"><input id="wh-edit-enabled" type="checkbox" class="checkbox checkbox-sm checkbox-primary" ${wh.enabled ? 'checked' : ''}><label class="text-sm">${t('common.enabled')}</label></div>
        <div class="border-t border-bg-border pt-3 mt-3">
          <div class="text-xs text-muted uppercase tracking-wider mb-2">${t('events.title')}</div>
          <div class="event-grid">${eventsHtml}</div>
        </div>
      </div>
      <div class="flex justify-end gap-2 mt-4 pt-3 border-t border-bg-border">
        <button class="btn-cg btn-cg-ghost" onclick="_wh.testWebhook()">${t('common.test')}</button>
      </div>`;
    // Auto-save event delegation (bind once)
    if (!el._whAutoSaveBound) {
      const autoSave = debounce(() => { if (window._wh) window._wh.saveWebhook(); }, 1500);
      el.addEventListener('input', autoSave);
      el.addEventListener('change', autoSave);
      el._whAutoSaveBound = true;
    }
  }

  setTimeout(() => { renderWebhookList(webhooks); }, 50);

  return `
    <div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div class="section-card">
          <div class="section-card-header">
            <span class="section-card-title">${t('webhooks.title')}</span>
            <button class="btn-cg btn-cg-primary" style="font-size:0.75rem" onclick="_wh.addWebhook()">${t('common.add')}</button>
          </div>
          <div id="webhook-list" class="p-4"><div class="skeleton-line" style="width:60%"></div><div class="skeleton-line mt-2" style="width:40%"></div></div>
        </div>
        <div class="section-card">
          <div class="section-card-header"><span class="section-card-title">${t('common.configuration')}</span></div>
          <div id="webhook-detail" class="p-4"><div class="text-sm text-muted text-center py-8">${t('common.select')}</div></div>
        </div>
      </div>
    </div>
    <dialog id="webhook-add-modal" class="modal">
      <div class="modal-box max-w-sm">
        <h3 class="font-bold text-sm">${t('webhooks.title')}</h3>
        <div class="mt-3"><label class="text-xs text-muted block mb-1">${t('common.name')}</label><input id="wh-name" type="text" class="input input-sm input-bordered w-full" placeholder="${t('webhooks.webhookNamePlaceholder')}"></div>
        <div class="mt-3"><label class="text-xs text-muted block mb-1">${t('webhooks.webhookUrl')}</label><input id="wh-url" type="text" class="input input-sm input-bordered w-full font-mono text-xs" placeholder="${t('webhooks.webhookUrlPlaceholder')}"><div class="text-xs text-muted mt-1">${t('webhooks.discordHelper')}</div></div>
        <div class="flex justify-end gap-2 mt-4"><form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form><button class="btn-cg btn-cg-primary" onclick="_wh.confirmAdd()">${t('common.create')}</button></div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>`;
}

// ══════════════════════════════════════════════════════════════════════════════
//  AUDIT VIEW — Action log viewer
// ══════════════════════════════════════════════════════════════════════════════

async function buildAudit(app) {
  let data;
  try {
    data = await API.admin.audit(200, 0);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span>
      <span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const entries = data.entries || [];

  const ACTION_COLORS = {
    'auth.login': 'var(--green)',
    'auth.login_failed': 'var(--red)',
    'auth.logout': 'var(--muted)',
    'server.command': 'var(--amber)',
    'admin.token_regenerate': 'var(--red)',
    'admin.account_create': 'var(--cyan)',
    'admin.account_delete': 'var(--red)',
    'admin.account_update': 'var(--amber)',
  };

  const ROLE_DOTS = {
    SUPER_ADMIN: 'var(--red)',
    OPERATOR: 'var(--amber)',
    READ_ONLY: 'var(--muted-light)',
  };

  window._au = {
    onWsAudit(msg) {
      const container = document.getElementById('audit-entries');
      if (!container) return;
      const row = document.createElement('tr');
      row.className = 'fade-in';
      const ts = msg.timestamp ? I18N.fmtDate(msg.timestamp) : '\u2014';
      const color = ACTION_COLORS[msg.action] || 'var(--muted)';
      const dot = ROLE_DOTS[msg.role] || 'var(--muted)';
      row.innerHTML = `
        <td class="text-xs text-muted font-mono">${ts}</td>
        <td class="flex items-center gap-1.5"><span class="w-1.5 h-1.5 rounded-full inline-block" style="background:${dot}"></span><span class="text-sm font-mono">${escHtml(msg.username)}</span></td>
        <td><span class="text-xs font-mono px-1.5 py-0.5 rounded" style="background:${color}20;color:${color}">${escHtml(msg.action)}</span></td>
        <td class="text-sm text-muted" style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${escHtml(msg.detail || '')}">${escHtml(msg.detail || '\u2014')}</td>
      `;
      container.prepend(row);
      // Trim
      while (container.children.length > 200) container.removeChild(container.lastChild);
    },
    async loadMore() {
      const container = document.getElementById('audit-entries');
      if (!container) return;
      const current = container.children.length;
      try {
        const more = await API.admin.audit(100, current);
        for (const e of (more.entries || [])) {
          const row = document.createElement('tr');
          const ts = e.timestamp ? I18N.fmtDate(e.timestamp) : '\u2014';
          const color = ACTION_COLORS[e.action] || 'var(--muted)';
          const dot = ROLE_DOTS[e.role] || 'var(--muted)';
          row.innerHTML = `
            <td class="text-xs text-muted font-mono">${ts}</td>
            <td class="flex items-center gap-1.5"><span class="w-1.5 h-1.5 rounded-full inline-block" style="background:${dot}"></span><span class="text-sm font-mono">${escHtml(e.username)}</span></td>
            <td><span class="text-xs font-mono px-1.5 py-0.5 rounded" style="background:${color}20;color:${color}">${escHtml(e.action)}</span></td>
            <td class="text-sm text-muted" style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${escHtml(e.detail || '')}">${escHtml(e.detail || '\u2014')}</td>
          `;
          container.appendChild(row);
        }
        if ((more.entries || []).length < 100) {
          document.getElementById('audit-load-more')?.remove();
        }
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  const rows = entries.map(e => {
    const ts = e.timestamp ? I18N.fmtDate(e.timestamp) : '\u2014';
    const color = ACTION_COLORS[e.action] || 'var(--muted)';
    const dot = ROLE_DOTS[e.role] || 'var(--muted)';
    return `<tr>
      <td class="text-xs text-muted font-mono">${ts}</td>
      <td class="flex items-center gap-1.5"><span class="w-1.5 h-1.5 rounded-full inline-block" style="background:${dot}"></span><span class="text-sm font-mono">${escHtml(e.username)}</span></td>
      <td><span class="text-xs font-mono px-1.5 py-0.5 rounded" style="background:${color}20;color:${color}">${escHtml(e.action)}</span></td>
      <td class="text-sm text-muted" style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${escHtml(e.detail || '')}">${escHtml(e.detail || '\u2014')}</td>
    </tr>`;
  }).join('');

  return `
  <div class="max-w-5xl mx-auto slide-in">
    <div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('audit.title')}</span>
        <span class="text-muted text-xs font-mono ml-2">${data.total}</span>
      </div>
      <div class="section-card-body">
        ${entries.length > 0 ? `
        <table class="cg-table">
          <thead><tr>
            <th style="width:130px">${t('audit.date')}</th>
            <th style="width:120px">${t('audit.user')}</th>
            <th style="width:180px">${t('audit.action')}</th>
            <th>${t('audit.details')}</th>
          </tr></thead>
          <tbody id="audit-entries">${rows}</tbody>
        </table>
        ${data.total > entries.length ? `
        <div id="audit-load-more" class="text-center mt-3">
          <button class="btn-cg btn-cg-ghost" onclick="_au.loadMore()">${t('common.reload')}</button>
        </div>` : ''}
        ` : '<div class="empty-state"><span class="empty-state-icon">📋</span><span class="empty-state-text">' + t('audit.noEntries') + '</span></div>'}
      </div>
    </div>

    <div class="mt-3 text-xs text-muted font-mono text-center">
      ${t('audit.title')}
    </div>
  </div>`;
}
