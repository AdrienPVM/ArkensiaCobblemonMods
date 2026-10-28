// ── Prison ──────────────────────────────────────────────────────────────────
// Active prisoners + history + zone/welcome config.
async function buildPrison(app) {
  let bundle, zones;
  try {
    [bundle, zones] = await Promise.all([API.prison.all(), API.zones.list()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">⚠</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const _p = window._pr = {
    app,
    bundle,
    zones: zones || [],
    tab: 'active',
    _searchFilter: '',
    _tickHandle: null,

    async refresh() {
      try {
        this.bundle = await API.prison.all();
        this.renderActive();
        this.renderHistory();
        this.renderConfig();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    switchTab(tab) {
      this.tab = tab;
      ['active', 'history', 'config'].forEach(k => {
        const sec = document.getElementById('prison-' + k + '-section');
        const btn = document.getElementById('prison-tab-' + k);
        if (sec) sec.classList.toggle('hidden', k !== tab);
        if (btn) btn.classList.toggle('active', k === tab);
      });
    },

    renderActive() {
      const tbody = document.getElementById('prison-active-tbody');
      if (!tbody) return;
      const list = (this.bundle.active || []).slice().sort((a, b) => a.endsAt - b.endsAt);
      const filter = this._searchFilter.trim().toLowerCase();
      const filtered = filter ? list.filter(e => (e.playerName || '').toLowerCase().includes(filter)) : list;
      if (filtered.length === 0) {
        tbody.innerHTML = `<tr><td colspan="6" class="text-center text-muted text-sm py-4">${t('prison.noActive')}</td></tr>`;
      } else {
        const now = Date.now();
        tbody.innerHTML = filtered.map(e => {
          const remaining = Math.max(0, (e.endsAt || 0) - now);
          return `
            <tr>
              <td class="font-mono">${escHtml(e.playerName || '?')}</td>
              <td>${escHtml(e.reason || '')}</td>
              <td class="td-mono" data-prison-remaining="${escHtml(e.playerUuid)}" data-ends-at="${e.endsAt}">${prisonFmtRemaining(remaining)}</td>
              <td>${escHtml(e.issuedByName || '?')}</td>
              <td class="td-mono text-xs text-muted">${prisonFmtDate(e.startedAt)}</td>
              <td>
                <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="_pr.extend('${escHtml(e.playerUuid)}','${escHtml(e.playerName || '')}')">${t('prison.extend')}</button>
                <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="_pr.release('${escHtml(e.playerUuid)}','${escHtml(e.playerName || '')}')">${t('prison.release')}</button>
              </td>
            </tr>`;
        }).join('');
      }
      const cnt = document.getElementById('prison-active-count');
      if (cnt) cnt.textContent = filtered.length + (filter ? '/' + list.length : '');
    },

    renderHistory() {
      const tbody = document.getElementById('prison-history-tbody');
      if (!tbody) return;
      const list = (this.bundle.history || []);
      const filter = this._searchFilter.trim().toLowerCase();
      const filtered = filter ? list.filter(e => (e.playerName || '').toLowerCase().includes(filter)) : list;
      if (filtered.length === 0) {
        tbody.innerHTML = `<tr><td colspan="6" class="text-center text-muted text-sm py-4">${t('prison.noHistory')}</td></tr>`;
      } else {
        tbody.innerHTML = filtered.map(e => {
          const reasonKey = e.releaseReason === 'EXPIRED' ? 'prison.releaseExpired' : 'prison.releasePardoned';
          const dur = (e.releasedAt || 0) - (e.startedAt || 0);
          return `
            <tr>
              <td class="font-mono">${escHtml(e.playerName || '?')}</td>
              <td>${escHtml(e.reason || '')}</td>
              <td class="td-mono">${prisonFmtMs(dur)}</td>
              <td>${escHtml(e.issuedByName || '?')}</td>
              <td><span class="id-badge">${escHtml(t(reasonKey))}</span> ${e.releasedByName ? escHtml(e.releasedByName) : ''}</td>
              <td class="td-mono text-xs text-muted">${prisonFmtDate(e.releasedAt)}</td>
            </tr>`;
        }).join('');
      }
    },

    renderConfig() {
      const cfg = this.bundle.config || {};
      const sel = document.getElementById('prison-zone-select');
      const msg = document.getElementById('prison-welcome-msg');
      if (sel) {
        sel.innerHTML = `<option value="">${t('prison.zoneNone')}</option>` +
          this.zones.map(z => `<option value="${escHtml(z.id)}" ${cfg.prisonZoneId === z.id ? 'selected' : ''}>${escHtml(z.name)} (${escHtml(z.world.replace('minecraft:', ''))})</option>`).join('');
      }
      if (msg) msg.value = cfg.welcomeMessage || '';
      const rpEl = document.getElementById('prison-release-readout');
      if (rpEl) {
        const rp = cfg.releasePoint;
        if (rp && rp.world) {
          const w = rp.world.replace('minecraft:', '');
          rpEl.innerHTML = `<span class="id-badge">${escHtml(w)}</span> <span class="td-mono">${rp.x.toFixed(1)}, ${rp.y.toFixed(1)}, ${rp.z.toFixed(1)}</span>`;
        } else {
          rpEl.innerHTML = `<span class="text-muted text-sm">${t('prison.releaseFallback')}</span>`;
        }
      }
    },

    async clearReleasePoint() {
      try {
        await API.prison.saveConfig({ releasePoint: null });
        app.toast(t('prison.releaseCleared'), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async saveConfig() {
      const sel = document.getElementById('prison-zone-select');
      const msg = document.getElementById('prison-welcome-msg');
      try {
        await API.prison.saveConfig({
          prisonZoneId: sel.value || null,
          welcomeMessage: msg.value || ''
        });
        app.toast(t('prison.configSaved'), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async openImprison() {
      const cfg = this.bundle.config || {};
      if (!cfg.prisonZoneId) {
        app.toast(t('prison.zoneRequired'), 'error');
        return;
      }
      const modal = document.getElementById('prison-imprison-modal');
      if (!modal) return;
      document.getElementById('prison-im-target').value = '';
      document.getElementById('prison-im-target-uuid').value = '';
      document.getElementById('prison-im-duration').value = '1h';
      document.getElementById('prison-im-reason').value = '';
      modal.showModal();
    },

    pickPlayer() {
      openPlayerPicker({
        title: t('prison.pickPlayer'),
        onSelect: async (name) => {
          document.getElementById('prison-im-target').value = name;
          // Best-effort UUID lookup so offline imprison works.
          try {
            const detail = await API.players.get(name);
            document.getElementById('prison-im-target-uuid').value = detail.uuid || '';
          } catch (e) { /* allow name-only fallback */ }
        }
      });
    },

    async submitImprison() {
      const name = document.getElementById('prison-im-target').value.trim();
      const uuid = document.getElementById('prison-im-target-uuid').value.trim();
      const duration = document.getElementById('prison-im-duration').value.trim();
      const reason = document.getElementById('prison-im-reason').value.trim();
      if (!name && !uuid) { app.toast(t('prison.pickPlayerFirst'), 'error'); return; }
      if (!duration) { app.toast(t('prison.durationRequired'), 'error'); return; }
      if (!reason) { app.toast(t('prison.reasonRequired'), 'error'); return; }
      try {
        const data = { duration, reason };
        if (uuid) data.uuid = uuid; else data.name = name;
        await API.prison.imprison(data);
        document.getElementById('prison-imprison-modal').close();
        app.toast(t('prison.imprisoned', {name: name || uuid}), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async release(uuid, name) {
      const ok = await confirmAction(t('prison.releaseConfirmMsg', {name}));
      if (!ok) return;
      try {
        await API.prison.release(uuid);
        app.toast(t('prison.released', {name}), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async extend(uuid, name) {
      const modal = document.getElementById('prison-extend-modal');
      if (!modal) return;
      document.getElementById('prison-ext-name').textContent = name;
      document.getElementById('prison-ext-uuid').value = uuid;
      document.getElementById('prison-ext-duration').value = '1h';
      modal.showModal();
    },

    async submitExtend() {
      const uuid = document.getElementById('prison-ext-uuid').value;
      const dur = document.getElementById('prison-ext-duration').value.trim();
      if (!dur) { app.toast(t('prison.durationRequired'), 'error'); return; }
      try {
        await API.prison.update(uuid, { duration: dur });
        document.getElementById('prison-extend-modal').close();
        app.toast(t('prison.updated'), 'success');
        await this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    setSearch(v) {
      this._searchFilter = v;
      this.renderActive();
      this.renderHistory();
    },

    startTicker() {
      if (this._tickHandle) clearInterval(this._tickHandle);
      this._tickHandle = setInterval(() => {
        // Auto-stop if user navigated away (prevents leak across view changes).
        if (!app.currentView || app.currentView !== 'prison') {
          clearInterval(this._tickHandle); this._tickHandle = null; return;
        }
        if (this.tab !== 'active') return;
        document.querySelectorAll('[data-prison-remaining]').forEach(el => {
          const ends = parseInt(el.getAttribute('data-ends-at') || '0', 10);
          const ms = Math.max(0, ends - Date.now());
          el.textContent = prisonFmtRemaining(ms);
        });
      }, 1000);
    }
  };

  // Render bodies + start ticker after Alpine inserts the HTML.
  setTimeout(() => {
    _p.renderActive();
    _p.renderHistory();
    _p.renderConfig();
    _p.startTicker();
  }, 50);

  return `
    <div class="max-w-6xl mx-auto slide-in">
      <div class="section-card">
        <div class="section-card-header flex items-center justify-between flex-wrap gap-2">
          <div class="flex items-center gap-3">
            <div class="section-card-title">⛓ ${t('prison.title')}</div>
            ${guideButton('prison')}
          </div>
          <div class="flex items-center gap-2">
            <input class="input input-sm input-bordered w-48"
                   placeholder="${t('common.search')}"
                   oninput="_pr.setSearch(this.value)" />
            <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_pr.openImprison()">⛓ ${t('prison.imprisonAction')}</button>
          </div>
        </div>

        <div class="section-card-body">
          <div class="detail-tabs">
            <button id="prison-tab-active" class="detail-tab active" onclick="_pr.switchTab('active')">${t('prison.tab.active')} <span id="prison-active-count" class="text-muted text-xs ml-1">${(bundle.active || []).length}</span></button>
            <button id="prison-tab-history" class="detail-tab" onclick="_pr.switchTab('history')">${t('prison.tab.history')}</button>
            <button id="prison-tab-config" class="detail-tab" onclick="_pr.switchTab('config')">${t('prison.tab.config')}</button>
          </div>

          <div id="prison-active-section" class="mt-4">
            <table class="cg-table">
              <thead><tr>
                <th>${t('prison.col.player')}</th>
                <th>${t('prison.col.reason')}</th>
                <th>${t('prison.col.remaining')}</th>
                <th>${t('prison.col.issuedBy')}</th>
                <th>${t('prison.col.startedAt')}</th>
                <th>${t('common.actions')}</th>
              </tr></thead>
              <tbody id="prison-active-tbody"></tbody>
            </table>
          </div>

          <div id="prison-history-section" class="mt-4 hidden">
            <table class="cg-table">
              <thead><tr>
                <th>${t('prison.col.player')}</th>
                <th>${t('prison.col.reason')}</th>
                <th>${t('prison.col.duration')}</th>
                <th>${t('prison.col.issuedBy')}</th>
                <th>${t('prison.col.releaseReason')}</th>
                <th>${t('prison.col.releasedAt')}</th>
              </tr></thead>
              <tbody id="prison-history-tbody"></tbody>
            </table>
          </div>

          <div id="prison-config-section" class="mt-4 hidden">
            <div class="flex flex-col gap-3 max-w-2xl">
              <div>
                <label class="field-label">${t('prison.zoneLabel')} ${tip('tip.prison.zone')}</label>
                <select id="prison-zone-select" class="select select-sm select-bordered w-full"></select>
                <div class="text-xs text-muted mt-1">${t('prison.zoneHint')}</div>
              </div>
              <div>
                <label class="field-label">${t('prison.welcomeMsgLabel')} ${tip('tip.prison.welcome')}</label>
                <textarea id="prison-welcome-msg" rows="4"
                          class="input input-sm input-bordered w-full font-mono"
                          style="height:auto;padding:.5rem"></textarea>
                <div class="text-xs text-muted mt-1">${t('prison.welcomeMsgHint')}</div>
              </div>
              <div>
                <label class="field-label">${t('prison.releaseLabel')} ${tip('tip.prison.release')}</label>
                <div class="flex items-center gap-2 flex-wrap">
                  <span id="prison-release-readout"></span>
                  <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_pr.clearReleasePoint()">${t('prison.releaseClear')}</button>
                </div>
                <div class="text-xs text-muted mt-1">${t('prison.releaseHint')}</div>
              </div>
              <div class="flex justify-end">
                <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_pr.saveConfig()">${t('common.save')}</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Imprison modal -->
    <dialog id="prison-imprison-modal" class="modal">
      <div class="modal-box max-w-md">
        <button class="modal-close" onclick="this.closest('dialog').close()">✕</button>
        <h3 class="text-lg font-bold mb-3">⛓ ${t('prison.imprisonTitle')}</h3>
        <div class="flex flex-col gap-3">
          <div>
            <label class="field-label">${t('prison.col.player')}</label>
            <div class="flex gap-2">
              <input id="prison-im-target" class="input input-sm input-bordered w-full" readonly />
              <input id="prison-im-target-uuid" type="hidden" />
              <button class="btn-cg btn-cg-sm" onclick="_pr.pickPlayer()">${t('common.pick')}</button>
            </div>
          </div>
          <div>
            <label class="field-label">${t('prison.duration')} ${tip('tip.prison.duration')}</label>
            <input id="prison-im-duration" class="input input-sm input-bordered w-full" placeholder="30m, 2h, 7d" />
          </div>
          <div>
            <label class="field-label">${t('prison.reason')}</label>
            <input id="prison-im-reason" class="input input-sm input-bordered w-full" />
          </div>
          <div class="flex justify-end gap-2 mt-2">
            <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="this.closest('dialog').close()">${t('common.cancel')}</button>
            <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_pr.submitImprison()">${t('prison.imprisonAction')}</button>
          </div>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- Extend modal -->
    <dialog id="prison-extend-modal" class="modal">
      <div class="modal-box max-w-sm">
        <button class="modal-close" onclick="this.closest('dialog').close()">✕</button>
        <h3 class="text-lg font-bold mb-3">${t('prison.extendTitle')} — <span id="prison-ext-name"></span></h3>
        <input id="prison-ext-uuid" type="hidden" />
        <div class="flex flex-col gap-3">
          <div>
            <label class="field-label">${t('prison.newDuration')} ${tip('tip.prison.newDuration')}</label>
            <input id="prison-ext-duration" class="input input-sm input-bordered w-full" placeholder="1h" />
            <div class="text-xs text-muted mt-1">${t('prison.newDurationHint')}</div>
          </div>
          <div class="flex justify-end gap-2 mt-2">
            <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="this.closest('dialog').close()">${t('common.cancel')}</button>
            <button class="btn-cg btn-cg-primary btn-cg-sm" onclick="_pr.submitExtend()">${t('common.save')}</button>
          </div>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>
  `;
}

// Helpers -------------------------------------------------------------------

function prisonFmtMs(ms) {
  if (!ms || ms <= 0) return '0s';
  let s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600);  s -= h * 3600;
  const m = Math.floor(s / 60);    s -= m * 60;
  let out = '';
  if (d) out += d + 'd ';
  if (h) out += h + 'h ';
  if (m) out += m + 'm ';
  if (!out) out = s + 's';
  return out.trim();
}
function prisonFmtRemaining(ms) {
  if (ms <= 0) return t('prison.expired');
  return prisonFmtMs(ms);
}
function prisonFmtDate(ts) {
  if (!ts) return '—';
  return I18N.fmtDate(new Date(ts).toISOString());
}

