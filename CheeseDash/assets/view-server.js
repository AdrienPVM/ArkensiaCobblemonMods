// ── Server Admin ─────────────────────────────────────────────────────────────
async function buildServer(app) {
  let stats, mods;
  try {
    [stats, mods] = await Promise.all([API.server.stats(), API.server.mods()]);
  } catch (e) {
    app.toast(e.message, 'error');
    return '<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">' + escHtml(e.message) + '</span></div>';
  }

  const _s = window._sv = {
    stats, mods, app,
    consoleLogs: [],
    consoleLoaded: false,
    cmdHistory: [],
    cmdHistoryIdx: -1,

    async refreshStats() {
      try {
        this.stats = await API.server.stats();
        renderServerStats(this.stats);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async loadLogs() {
      try {
        const data = await API.server.logs(200);
        this.consoleLogs = data.lines || [];
        this.consoleLoaded = true;
        renderConsoleLogs(this.consoleLogs);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async refreshLogs() {
      try {
        const data = await API.server.logs(200);
        this.consoleLogs = data.lines || [];
        renderConsoleLogs(this.consoleLogs);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async sendCommand() {
      const inp = document.getElementById('server-cmd-input');
      if (!inp) return;
      const cmd = inp.value.trim();
      if (!cmd) return;
      this.cmdHistory.unshift(cmd);
      this.cmdHistoryIdx = -1;
      inp.value = '';
      try {
        await API.server.command(cmd);
        app.toast(t('common.success'), 'success');
        // Refresh logs after short delay to see output
        setTimeout(() => this.refreshLogs(), 500);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    handleCmdKey(e) {
      if (e.key === 'Enter') {
        this.sendCommand();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (this.cmdHistory.length > 0 && this.cmdHistoryIdx < this.cmdHistory.length - 1) {
          this.cmdHistoryIdx++;
          document.getElementById('server-cmd-input').value = this.cmdHistory[this.cmdHistoryIdx];
        }
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (this.cmdHistoryIdx > 0) {
          this.cmdHistoryIdx--;
          document.getElementById('server-cmd-input').value = this.cmdHistory[this.cmdHistoryIdx];
        } else {
          this.cmdHistoryIdx = -1;
          document.getElementById('server-cmd-input').value = '';
        }
      }
    },

    onWsLog(line) {
      this.consoleLogs.push(line);
      if (this.consoleLogs.length > 500) this.consoleLogs.shift();
      if (this.consoleLoaded) renderConsoleLogs(this.consoleLogs);
    },

    // ── Scheduling ────────────────────────────────────────────────────
    scheduleData: null,
    _countdownInterval: null,

    _startCountdownTimer() {
      this._stopCountdownTimer();
      this._countdownInterval = setInterval(() => {
        const active = this.scheduleData?.active;
        if (!active) { this._stopCountdownTimer(); return; }
        const now = Date.now();
        const rem = Math.max(0, Math.floor((active.scheduledAt - now) / 1000));
        const el = document.getElementById('sched-countdown');
        if (!el) { this._stopCountdownTimer(); return; }
        const min = Math.floor(rem / 60);
        const sec = rem % 60;
        el.textContent = `${min}:${String(sec).padStart(2, '0')}`;
        const bar = document.getElementById('sched-progress');
        if (bar) {
          const total = (active.scheduledAt - active.createdAt) / 1000;
          const pct = total > 0 ? Math.max(0, Math.min(100, ((total - rem) / total) * 100)) : 0;
          bar.style.width = pct + '%';
        }
        if (rem <= 0) this._stopCountdownTimer();
      }, 1000);
    },

    _stopCountdownTimer() {
      if (this._countdownInterval) {
        clearInterval(this._countdownInterval);
        this._countdownInterval = null;
      }
    },

    async loadSchedule() {
      try {
        this.scheduleData = await API.schedule.get();
        renderScheduleSection(this.scheduleData);
        if (this.scheduleData?.active) {
          this._startCountdownTimer();
        } else {
          this._stopCountdownTimer();
        }
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async scheduleAction(type) {
      const modal = document.getElementById('schedule-modal');
      if (!modal) return;
      document.getElementById('sched-type-label').textContent =
        type;
      document.getElementById('sched-type').value = type;
      document.getElementById('sched-delay').value = '300';
      document.getElementById('sched-msg').value = '';
      modal.showModal();
    },

    async confirmSchedule() {
      const type = document.getElementById('sched-type').value;
      const delay = parseInt(document.getElementById('sched-delay').value);
      const message = document.getElementById('sched-msg').value.trim();
      if (isNaN(delay) || delay < 5) { app.toast(t('common.error'), 'error'); return; }
      try {
        await API.schedule.create({ type, delaySeconds: delay, message });
        document.getElementById('schedule-modal').close();
        app.toast(t('common.success'), 'success');
        this.loadSchedule();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async cancelSchedule() {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.schedule.cancelActive();
        this._stopCountdownTimer();
        app.toast(t('common.success'), 'success');
        this.loadSchedule();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async addRecurring() {
      const modal = document.getElementById('recurring-modal');
      if (!modal) return;
      document.getElementById('rec-type').value = 'RESTART';
      document.getElementById('rec-hour').value = '4';
      document.getElementById('rec-minute').value = '0';
      document.getElementById('rec-msg').value = '';
      document.querySelectorAll('.day-btn').forEach(b => b.classList.add('active'));
      modal.showModal();
    },

    async confirmRecurring() {
      const type = document.getElementById('rec-type').value;
      const hour = parseInt(document.getElementById('rec-hour').value);
      const minute = parseInt(document.getElementById('rec-minute').value);
      const message = document.getElementById('rec-msg').value.trim();
      const days = [];
      document.querySelectorAll('.day-btn.active').forEach(b => days.push(parseInt(b.dataset.day)));
      if (days.length === 0) { app.toast(t('common.error'), 'error'); return; }
      try {
        await API.schedule.addRecurring({ type, hour, minute, daysOfWeek: days, message });
        document.getElementById('recurring-modal').close();
        app.toast(t('common.success'), 'success');
        this.loadSchedule();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async toggleRecurring(id) {
      try {
        const rec = this.scheduleData?.recurring?.find(r => r.id === id);
        if (!rec) return;
        await API.schedule.updateRecurring(id, { enabled: !rec.enabled });
        this.loadSchedule();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async deleteRecurring(id) {
      if (!await confirmAction(t('events.deleteSchedule'))) return;
      try {
        await API.schedule.removeRecurring(id);
        app.toast(t('common.success'), 'success');
        this.loadSchedule();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    onScheduleUpdate(msg) {
      this.loadSchedule();
    },

    onScheduleTick(msg) {
      // Sync server-authoritative remaining time into local scheduleData
      if (this.scheduleData?.active) {
        const serverRemaining = msg.remaining;
        // Recalculate scheduledAt from server's remaining to correct any drift
        this.scheduleData.active.scheduledAt = Date.now() + serverRemaining * 1000;
      }
      // Ensure countdown timer is running (handles reconnection cases)
      if (!this._countdownInterval && this.scheduleData?.active) {
        this._startCountdownTimer();
      }
    },

    async regenerateToken() {
      const modal = document.getElementById('token-regen-modal');
      if (!modal) return;
      document.getElementById('tregen-btn').onclick = async () => {
        try {
          const data = await API.server.regenerateToken();
          modal.close();
          // Show new token in a result modal
          const resultModal = document.getElementById('token-result-modal');
          if (resultModal) {
            document.getElementById('tregen-token').textContent = data.token;
            resultModal.showModal();
          }
        } catch (e) { app.toast(e.message, 'error'); }
      };
      modal.showModal();
    },

    filterMods(q) {
      const tbl = document.getElementById('sv-mods-table');
      if (!tbl) return;
      const rows = tbl.querySelectorAll('tbody tr');
      const lower = q.toLowerCase();
      rows.forEach(r => {
        r.style.display = r.textContent.toLowerCase().includes(lower) ? '' : 'none';
      });
    },
  };

  function renderServerStats(s) {
    const el = document.getElementById('server-stats-grid');
    if (!el) return;
    const heapPct = s.heapMaxMb > 0 ? Math.round((s.heapUsedMb / s.heapMaxMb) * 100) : 0;
    const uptimeMs = s.uptime || 0;
    const uptimeH = Math.floor(uptimeMs / 3600000);
    const uptimeM = Math.floor((uptimeMs % 3600000) / 60000);
    const tpsColor = s.tps >= 19 ? 'text-green' : s.tps >= 15 ? 'text-amber' : 'text-red';
    const heapColor = heapPct < 70 ? 'text-green' : heapPct < 90 ? 'text-amber' : 'text-red';

    el.innerHTML = `
      <div class="stat-card"><span class="stat-icon ${tpsColor}">⚡</span><span class="stat-value ${tpsColor}">${s.tps}</span><span class="stat-label">TPS</span></div>
      <div class="stat-card"><span class="stat-icon ${heapColor}">💾</span><span class="stat-value ${heapColor}">${s.heapUsedMb} / ${s.heapMaxMb} MB</span><span class="stat-label">${t('server.heap')} (${heapPct}%)</span></div>
      <div class="stat-card"><span class="stat-icon text-cyan">👥</span><span class="stat-value text-cyan">${s.playerCount} / ${s.maxPlayers}</span><span class="stat-label">${t('common.players')}</span></div>
      <div class="stat-card"><span class="stat-icon text-muted">⏱</span><span class="stat-value text-muted-light">${uptimeH}h ${uptimeM}m</span><span class="stat-label">${t('overview.uptime')}</span></div>
      <div class="stat-card"><span class="stat-icon text-muted">📦</span><span class="stat-value text-muted-light">${s.version || '—'}</span><span class="stat-label">${t('server.version')}</span></div>`;
  }

  function renderConsoleLogs(lines) {
    const el = document.getElementById('server-console-output');
    if (!el) return;
    el.innerHTML = lines.map(line => {
      let cls = 'text-muted-light';
      if (line.includes('[WARN]') || line.includes('/WARN')) cls = 'text-amber';
      else if (line.includes('[ERROR]') || line.includes('/ERROR') || line.includes('Exception')) cls = 'text-red';
      else if (line.includes('[INFO]') || line.includes('/INFO')) cls = 'text-muted-light';
      return '<div class="' + cls + '" style="font-size:0.75rem;line-height:1.4;white-space:pre-wrap;word-break:break-all">' + escHtml(line) + '</div>';
    }).join('');
    el.scrollTop = el.scrollHeight;
  }

  function renderScheduleSection(data) {
    const container = document.getElementById('schedule-section');
    if (!container) return;

    // Active countdown
    let activeHtml = '';
    if (data.active) {
      const rem = data.active.remainingSeconds;
      const total = (data.active.scheduledAt - data.active.createdAt) / 1000;
      const initPct = total > 0 ? Math.max(0, Math.min(100, ((total - rem) / total) * 100)) : 0;
      activeHtml = `<div class="p-4 rounded-lg" style="background:linear-gradient(135deg, rgba(245,166,35,0.1), rgba(239,68,68,0.1)); border:1px solid var(--amber)">
           <div class="flex items-center justify-between mb-3">
             <div class="flex items-center gap-3">
               <span class="text-2xl">${data.active.type === 'RESTART' ? '\u{1F504}' : data.active.type === 'STOP' ? '\u{1F6D1}' : '\u{1F527}'}</span>
               <div>
                 <div class="text-sm font-semibold text-amber">${escHtml(data.active.type)} ${t('server.scheduleInProgress')}</div>
                 ${data.active.message ? '<div class="text-xs text-muted mt-0.5">' + escHtml(data.active.message) + '</div>' : ''}
               </div>
             </div>
             <button class="btn-cg btn-cg-danger" style="font-size:0.7rem" onclick="_sv.cancelSchedule()">${t('common.cancel')}</button>
           </div>
           <div class="flex items-center gap-3">
             <div class="countdown-bar flex-1"><div id="sched-progress" class="countdown-bar-fill" style="width:${initPct.toFixed(1)}%"></div></div>
             <span id="sched-countdown" class="font-mono text-lg text-amber font-bold">${Math.floor(rem / 60)}:${String(rem % 60).padStart(2, '0')}</span>
           </div>
           <div class="text-xs text-muted mt-2">${t('server.scheduledBy')} ${escHtml(data.active.createdBy)} \u2022 ${I18N.fmtDate(data.active.createdAt)}</div>
         </div>`;
    }

    // Recurring schedules
    const dayNames = ['', t('server.dayMon'), t('server.dayTue'), t('server.dayWed'), t('server.dayThu'), t('server.dayFri'), t('server.daySat'), t('server.daySun')];
    const recRows = (data.recurring || []).map(r => {
      const days = (r.daysOfWeek || []).sort().map(d => dayNames[d] || d).join(', ');
      const typeLabel = r.type;
      const typeBadge = r.type === 'RESTART' ? 'bg-amber/20 text-amber' : r.type === 'STOP' ? 'bg-red/20 text-red' : 'bg-cyan/20 text-cyan';
      return `<tr>
        <td><span class="inline-block px-2 py-0.5 rounded text-xs font-medium ${typeBadge}">${typeLabel}</span></td>
        <td class="font-mono text-sm">${String(r.hour).padStart(2, '0')}:${String(r.minute).padStart(2, '0')}</td>
        <td class="text-sm text-muted">${days}</td>
        <td class="text-sm text-muted">${escHtml(r.message || '\u2014')}</td>
        <td>
          <label class="swap swap-rotate">
            <input type="checkbox" ${r.enabled ? 'checked' : ''} onchange="_sv.toggleRecurring('${r.id}')">
            <span class="swap-on text-green text-xs">\u2705</span>
            <span class="swap-off text-red text-xs">\u274C</span>
          </label>
        </td>
        <td>
          <button class="btn-cg btn-cg-ghost" style="font-size:0.65rem;padding:2px 6px" onclick="_sv.deleteRecurring('${r.id}')">\u2715</button>
        </td>
      </tr>`;
    }).join('');

    container.innerHTML = `
      ${activeHtml}
      <div class="flex gap-2 mt-4 mb-3">
        <button class="btn-cg btn-cg-primary" style="font-size:0.75rem" onclick="_sv.scheduleAction('RESTART')">${t('server.restartNow')}</button>
        <button class="btn-cg btn-cg-danger" style="font-size:0.75rem" onclick="_sv.scheduleAction('STOP')">${t('common.stop')}</button>
        <button class="btn-cg" style="font-size:0.75rem;background:var(--cyan);color:var(--bg)" onclick="_sv.scheduleAction('MAINTENANCE')">${t('server.maintenance')}</button>
      </div>
      ${(data.recurring || []).length > 0 ? `
        <div class="text-xs text-muted uppercase tracking-wider mb-2 mt-4">${t('server.scheduling')}</div>
        <table class="cg-table">
          <thead><tr><th>${t('common.type')}</th><th>${t('server.scheduleHour')}</th><th>${t('server.scheduleDays')}</th><th>${t('events.scheduleMessage')}</th><th>${t('common.active')}</th><th></th></tr></thead>
          <tbody>${recRows}</tbody>
        </table>
      ` : '<div class="text-sm text-muted mt-3">' + t('common.noResults') + '</div>'}
      <button class="btn-cg btn-cg-ghost mt-3" style="font-size:0.75rem" onclick="_sv.addRecurring()">${t('common.add')}</button>
    `;
  }

  // Load schedule data after DOM is ready
  setTimeout(() => { _s.loadSchedule(); }, 100);

  const modRows = mods.sort((a, b) => a.name.localeCompare(b.name)).map(m => `
    <tr>
      <td><span class="id-badge">${escHtml(m.id)}</span></td>
      <td class="text-sm">${escHtml(m.name)}</td>
      <td class="text-sm text-muted font-mono">${escHtml(m.version)}</td>
    </tr>
  `).join('');

  // Auto-refresh stats every 5s
  const statsInterval = setInterval(() => {
    if (document.getElementById('server-stats-grid')) {
      _s.refreshStats();
    } else {
      clearInterval(statsInterval);
    }
  }, 5000);

  // Render stats after DOM is ready
  setTimeout(() => { renderServerStats(stats); }, 50);

  return `
    <div class="max-w-6xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <!-- Stats -->
      <div class="section-card">
        <div class="section-card-header">
          <span class="section-card-title">${t('server.title')}</span>
          <button class="btn-cg btn-cg-ghost" onclick="_sv.refreshStats()">${t('common.refresh')}</button>
        </div>
        <div id="server-stats-grid" class="grid grid-cols-5 gap-3 p-4"></div>
      </div>

      <!-- Scheduling -->
      <div class="section-card mt-4">
        <div class="section-card-header">
          <span class="section-card-title">${t('server.scheduling')}</span>
          <button class="btn-cg btn-cg-ghost" onclick="_sv.loadSchedule()">${t('common.refresh')}</button>
        </div>
        <div id="schedule-section" class="p-4">
          <div class="skeleton-line" style="width:60%"></div>
          <div class="skeleton-line mt-2" style="width:40%"></div>
        </div>
      </div>

      <!-- Schedule Action Modal -->
      <dialog id="schedule-modal" class="modal">
        <div class="modal-box max-w-sm">
          <h3 class="font-bold text-sm"><span id="sched-type-label">${t('common.action')}</span> ${t('server.scheduling')}</h3>
          <input type="hidden" id="sched-type">
          <div class="mt-3">
            <label class="text-xs text-muted block mb-1">${t('server.delayLabel')} ${tip('tip.server.delay')}</label>
            <div class="flex gap-2 flex-wrap mb-2">
              <button class="btn-cg btn-cg-ghost" style="font-size:0.7rem" onclick="document.getElementById('sched-delay').value='300'">5 min</button>
              <button class="btn-cg btn-cg-ghost" style="font-size:0.7rem" onclick="document.getElementById('sched-delay').value='900'">15 min</button>
              <button class="btn-cg btn-cg-ghost" style="font-size:0.7rem" onclick="document.getElementById('sched-delay').value='1800'">30 min</button>
              <button class="btn-cg btn-cg-ghost" style="font-size:0.7rem" onclick="document.getElementById('sched-delay').value='3600'">1h</button>
              <button class="btn-cg btn-cg-ghost" style="font-size:0.7rem" onclick="document.getElementById('sched-delay').value='7200'">2h</button>
            </div>
            <input id="sched-delay" type="number" min="5" max="86400" value="300" class="input input-sm input-bordered w-full font-mono" placeholder="${t('server.delayPlaceholder')}">
          </div>
          <div class="mt-3">
            <label class="text-xs text-muted block mb-1">${t('server.messageOptional')}</label>
            <input id="sched-msg" type="text" class="input input-sm input-bordered w-full" placeholder="${t('server.messagePlaceholder')}">
          </div>
          <div class="flex justify-end gap-2 mt-4">
            <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
            <button class="btn-cg btn-cg-primary" onclick="_sv.confirmSchedule()">${t('common.confirm')}</button>
          </div>
        </div>
        <form method="dialog" class="modal-backdrop"><button>close</button></form>
      </dialog>

      <!-- Recurring Schedule Modal -->
      <dialog id="recurring-modal" class="modal">
        <div class="modal-box max-w-sm">
          <h3 class="font-bold text-sm">${t('server.scheduling')}</h3>
          <div class="mt-3">
            <label class="text-xs text-muted block mb-1">${t('common.type')}</label>
            <select id="rec-type" class="select select-sm select-bordered w-full">
              <option value="RESTART">RESTART</option>
              <option value="STOP">STOP</option>
              <option value="MAINTENANCE">${t('server.maintenance')}</option>
            </select>
          </div>
          <div class="mt-3 flex gap-3">
            <div class="flex-1">
              <label class="text-xs text-muted block mb-1">${t('chunks.resetHour')}</label>
              <input id="rec-hour" type="number" min="0" max="23" value="4" class="input input-sm input-bordered w-full font-mono">
            </div>
            <div class="flex-1">
              <label class="text-xs text-muted block mb-1">${t('server.scheduleMinute')}</label>
              <input id="rec-minute" type="number" min="0" max="59" value="0" class="input input-sm input-bordered w-full font-mono">
            </div>
          </div>
          <div class="mt-3">
            <label class="text-xs text-muted block mb-1">${t('chunks.resetDays')}</label>
            <div class="day-picker flex gap-1">
              <button type="button" class="day-btn active" data-day="1" onclick="this.classList.toggle('active')">${t('server.dayMon')}</button>
              <button type="button" class="day-btn active" data-day="2" onclick="this.classList.toggle('active')">${t('server.dayTue')}</button>
              <button type="button" class="day-btn active" data-day="3" onclick="this.classList.toggle('active')">${t('server.dayWed')}</button>
              <button type="button" class="day-btn active" data-day="4" onclick="this.classList.toggle('active')">${t('server.dayThu')}</button>
              <button type="button" class="day-btn active" data-day="5" onclick="this.classList.toggle('active')">${t('server.dayFri')}</button>
              <button type="button" class="day-btn active" data-day="6" onclick="this.classList.toggle('active')">${t('server.daySat')}</button>
              <button type="button" class="day-btn active" data-day="7" onclick="this.classList.toggle('active')">${t('server.daySun')}</button>
            </div>
          </div>
          <div class="mt-3">
            <label class="text-xs text-muted block mb-1">${t('server.messageOptional')}</label>
            <input id="rec-msg" type="text" class="input input-sm input-bordered w-full" placeholder="${t('server.scheduleMessagePlaceholder')}">
          </div>
          <div class="flex justify-end gap-2 mt-4">
            <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
            <button class="btn-cg btn-cg-primary" onclick="_sv.confirmRecurring()">${t('common.add')}</button>
          </div>
        </div>
        <form method="dialog" class="modal-backdrop"><button>close</button></form>
      </dialog>

      <!-- Console -->
      <div class="section-card mt-4">
        <div class="section-card-header">
          <span class="section-card-title">${t('server.console')}</span>
          <div class="flex gap-2">
            <button class="btn-cg btn-cg-ghost" onclick="_sv.loadLogs()">${t('common.reload')}</button>
            <button class="btn-cg btn-cg-ghost" onclick="_sv.refreshLogs()">${t('common.refresh')}</button>
          </div>
        </div>
        <div id="server-console-output" class="bg-bg p-3 font-mono overflow-y-auto border-t border-bg-border" style="height:300px;min-height:200px">
          <div class="text-muted text-sm text-center py-8">${t('common.loading')}</div>
        </div>
        <div class="flex gap-2 p-3 border-t border-bg-border">
          <span class="text-amber text-xs font-mono self-center">&gt;</span>
          <input id="server-cmd-input" class="input input-sm input-bordered font-mono flex-1" placeholder="${t('server.commandPlaceholder')}" onkeydown="_sv.handleCmdKey(event)">
          <button class="btn-cg btn-cg-primary" style="height:2rem;font-size:0.75rem" onclick="_sv.sendCommand()">${t('common.send')}</button>
        </div>
      </div>

      <!-- Mods -->
      <div class="section-card mt-4">
        <div class="section-card-header">
          <span class="section-card-title">${t('server.installedMods')} <span class="text-muted text-xs font-mono ml-2">${mods.length}</span></span>
          <input id="sv-mod-search" class="input input-sm w-48 input-bordered ml-auto" placeholder="${t('server.filterMods')}" oninput="_sv.filterMods(this.value)">
        </div>
        <table class="cg-table" id="sv-mods-table">
          <thead><tr><th>${t('server.modId')}</th><th>${t('server.modName')}</th><th>${t('server.modVersion')}</th></tr></thead>
          <tbody>${modRows}</tbody>
        </table>
      </div>

      <!-- Danger Zone -->
      <div class="section-card mt-4" style="border-color:var(--red)">
        <div class="section-card-header">
          <span class="section-card-title text-red">${t('server.dangerZone')}</span>
        </div>
        <div class="p-4">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-sm text-tx">${t('accounts.token')}</div>
              <div class="text-sm text-muted">${t('accounts.token')}</div>
            </div>
            <button class="btn-cg btn-cg-danger" onclick="_sv.regenerateToken()">${t('common.reset')}</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Token regen confirm modal -->
    <dialog id="token-regen-modal" class="modal">
      <div class="modal-box max-w-sm" style="border-color:var(--red)">
        <h3 class="font-bold text-red text-sm">${t('accounts.token')}</h3>
        <p class="text-sm text-muted mt-2">${t('common.deleteConfirm')}</p>
        <p class="text-sm text-red mt-2 font-semibold">${t('common.deleteConfirm')}</p>
        <div class="flex justify-end gap-2 mt-4">
          <form method="dialog"><button class="btn-cg btn-cg-ghost">${t('common.cancel')}</button></form>
          <button id="tregen-btn" class="btn-cg btn-cg-danger">${t('common.confirm')}</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button>close</button></form>
    </dialog>

    <!-- Token result modal -->
    <dialog id="token-result-modal" class="modal">
      <div class="modal-box max-w-md">
        <h3 class="font-bold text-green text-sm">\u2705 ${t('accounts.token')}</h3>
        <p class="text-sm text-muted mt-2">${t('accounts.token')}</p>
        <div class="mt-3 p-3 rounded bg-elevated-box">
          <code id="tregen-token" class="text-xs font-mono text-amber break-all select-all"></code>
        </div>
        <div class="flex justify-end mt-4">
          <button class="btn-cg btn-cg-primary" onclick="cgCopy(document.getElementById('tregen-token').textContent);document.getElementById('token-result-modal').close();API.clearToken();location.reload()">${t('common.close')}</button>
        </div>
      </div>
    </dialog>`;
}
