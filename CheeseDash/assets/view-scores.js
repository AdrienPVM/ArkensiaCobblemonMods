// ── CobbleCheeseScore (Scores, Config, MiniGames) ───────────────────────────

async function buildScores(app) {
  let scoreboards, stats;
  try {
    [scoreboards, stats] = await Promise.all([
      API.scores.scoreboards(),
      API.scores.stats(),
    ]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">🏆</span><span class="empty-state-text">${t('common.error')}</span></div>`;
  }

  // Expose helpers for event handlers
  window._sc = {
    app,
    scoreboards,
    stats,
    selectedId: null,
    imageUrls: {},
    search: '',

    filterAndRender() {
      const grid = document.getElementById('scores-grid');
      if (!grid) return;
      const q = this.search.toLowerCase();
      const filtered = q ? this.scoreboards.filter(b =>
        (b.title || b.id).toLowerCase().includes(q) || b.id.toLowerCase().includes(q)
      ) : this.scoreboards;
      grid.innerHTML = filtered.length === 0
        ? '<div class="empty-state col-span-full"><span class="empty-state-icon">\uD83D\uDD0D</span><span class="empty-state-text">' + t('common.noResults') + '</span></div>'
        : filtered.map(b => _scCardHtml(b)).join('');
    },

    async refresh() {
      try {
        app.toast(t('common.refreshing'), 'info');
        await API.scores.refresh();
        app.toast(t('scores.refreshScoreboards'), 'success');
        app.navigate('scores');
      } catch (e) {
        app.toast(e.message, 'error');
      }
    },

    openDetail(id) {
      app.navigate('scores:' + id);
    },

  };

  // ── Stats bar ───────────────────────────────────────────────────────
  const statsHtml = `
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
      <div class="stat-card">
        <div class="stat-icon">📊</div>
        <div class="stat-value">${stats.totalScoreboards || 0}</div>
        <div class="stat-label">${t('scores.scoreboards')}</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">✅</div>
        <div class="stat-value">${stats.enabledScoreboards || 0}</div>
        <div class="stat-label">${t('common.active')}</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">👥</div>
        <div class="stat-value">${stats.totalPlayersTracked || 0}</div>
        <div class="stat-label">${t('common.players')}</div>
      </div>
      <div class="stat-card">
        <div class="stat-icon">🕐</div>
        <div class="stat-value">${stats.lastUpdate ? I18N.fmtDate(stats.lastUpdate) : '—'}</div>
        <div class="stat-label">${t('store.lastUpdate')}</div>
      </div>
    </div>`;

  // ── Scoreboard card HTML helper ─────────────────────────────────────
  function _scCardHtml(b) {
    const enabledBadge = b.enabled
      ? '<span class="status-dot s-online"></span>'
      : '<span class="status-dot s-offline"></span>';
    const topPlayerHtml = b.topPlayer
      ? `<div class="flex items-center gap-2 mt-2" style="color:var(--muted-light)">
          <span>\uD83E\uDD47</span>
          <span class="text-sm font-medium" style="color:var(--amber)">${escHtml(b.topPlayer.name)}</span>
          <span class="text-xs td-mono">${escHtml(b.topPlayer.score)}</span>
        </div>` : '';
    const imgLabel = encodeURIComponent((b.title || b.id).replace(/[^a-zA-Z0-9_\-\s]/g, '').trim().replace(/\s+/g, '-') || b.id);
    const imgUrl = location.origin + '/scores/api/images/' + imgLabel + '/' + encodeURIComponent(b.id) + '.png';
    return `
      <div class="section-card cursor-pointer hover:ring-1" style="--tw-ring-color:var(--amber)" onclick="_sc.openDetail('${escHtml(b.id)}')">
        <div class="section-card-body">
          <div class="flex items-start justify-between">
            <div class="flex-1 min-w-0">
              <h3 class="font-semibold text-base flex items-center gap-2">
                ${enabledBadge}
                ${escHtml(b.title || b.id)}
              </h3>
              <p class="text-sm mt-1" style="color:var(--muted)">${escHtml(b.description || '')}</p>
            </div>
            <div class="flex items-center gap-2 shrink-0">
              <button class="btn-cg btn-cg-ghost" style="padding:0.2rem 0.4rem;font-size:0.75rem" title="${t('scores.copyImageUrl')}"
                onclick="event.stopPropagation();cgCopy('${escHtml(imgUrl)}').then(()=>_sc.app.toast(t('common.copied'),'success'),()=>_sc.app.toast(t('scores.copyError'),'error'))">📋</button>
              <span class="text-xs px-2 py-0.5 rounded-full font-mono" style="background:var(--bg-elevated);color:var(--muted-light)">${b.playerCount} ${t('common.players').toLowerCase()}</span>
            </div>
          </div>
          ${topPlayerHtml}
        </div>
      </div>`;
  }
  // Make accessible for filterAndRender
  window._scCardHtml = _scCardHtml;

  // ── Scoreboard cards grid ───────────────────────────────────────────
  const cardsHtml = scoreboards.length === 0
    ? `<div class="empty-state"><span class="empty-state-icon">\uD83C\uDFC6</span><span class="empty-state-text">${t('common.noResults')}</span></div>`
    : `<div id="scores-grid" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        ${scoreboards.map(b => _scCardHtml(b)).join('')}
      </div>`;

  return `
    <div class="max-w-6xl mx-auto slide-in">
      <!-- Header -->
      <div class="flex items-center justify-between mb-6 flex-wrap gap-3">
        <div>
          <h1 class="text-xl font-bold">${t('scores.title')}</h1>
          <p class="text-sm" style="color:var(--muted)">CobbleCheeseScore — ${scoreboards.length} ${t('scores.scoreboards').toLowerCase()}</p>
        </div>
        <div class="flex gap-2 items-center flex-wrap">
          <input type="text" placeholder="${t('common.search')}"
            class="input input-sm input-bordered text-sm"
            style="max-width:180px"
            oninput="_sc.search=this.value;_sc.filterAndRender()">
          <button class="btn-cg btn-cg-ghost" onclick="_nav('scores:minigames')">🎮 ${t('scores.minigames')}</button>
          <button class="btn-cg btn-cg-ghost" onclick="_nav('scores:config')">${t('common.config')}</button>
          <button class="btn-cg btn-cg-primary" onclick="_sc.refresh()">${t('scores.refreshScoreboards')}</button>
        </div>
      </div>

      ${statsHtml}
      ${cardsHtml}
    </div>`;
}

// ── Score Config — full page ──────────────────────────────────────────────────
async function buildScoreConfig(app) {
  let config;
  try {
    config = await API.scores.config();
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  window._scfg = {
    app, config,
    _debouncedSave: debounce(function() { if (window._scfg) window._scfg.save(); }, 1500),

    async save() {
      try {
        const dirty = document.getElementById('scfg-dirty');
        if (dirty) dirty.style.display = '';
        const data = {
          updateIntervalMinutes: parseInt(document.getElementById('score-cfg-interval').value) || 10,
          defaultMaxPlayers: parseInt(document.getElementById('score-cfg-maxplayers').value) || 30,
          defaultRowsPerColumn: parseInt(document.getElementById('score-cfg-rows').value) || 10,
          enableHistory: document.getElementById('score-cfg-history').checked,
        };
        await API.scores.updateConfig(data);
        app.toast(t('common.success'), 'success');
        if (dirty) dirty.style.display = 'none';
      } catch (e) {
        app.toast(e.message, 'error');
      }
    },
  };

  // Auto-save event delegation
  setTimeout(() => {
    const panel = document.getElementById('scfg-panel');
    if (panel) {
      const autoSave = debounce(() => { if (window._scfg) window._scfg.save(); }, 1500);
      panel.addEventListener('input', autoSave);
      panel.addEventListener('change', autoSave);
    }
  }, 100);

  const boardToggles = (config.scoreboards || []).map(b => `
    <label class="flex items-center gap-3 py-2 px-3 rounded border cursor-pointer" style="border-color:var(--bg-border);background:var(--bg-elevated)">
      <input type="checkbox" class="toggle toggle-sm" data-board-id="${escHtml(b.id)}" ${b.enabled ? 'checked' : ''}>
      <span class="text-sm" style="color:var(--text-primary)">${escHtml(b.title || b.id)}</span>
    </label>
  `).join('') || '<p class="text-sm" style="color:var(--muted)">' + t('common.noResults') + '</p>';

  return `
    <div class="max-w-2xl mx-auto" style="animation: slide-in-right 0.2s ease-out">
      <div id="scfg-panel" class="section-card">
        <div class="detail-header">
          <button class="detail-back" onclick="_nav('scores')">\u25C0 ${t('scores.leaderboards')}</button>
          <span class="section-card-title">${t('common.config')} CobbleCheeseScore</span>
        </div>
        <div class="section-card-body">
          <div class="grid grid-cols-2 gap-4 mb-6">
            <div class="field-group">
              <label class="field-label">${t('scores.updateInterval')}</label>
              <input id="score-cfg-interval" type="number" min="1" max="60" value="${config.updateIntervalMinutes || 10}"
                class="input input-sm w-full input-bordered">
            </div>
            <div class="field-group">
              <label class="field-label">${t('scores.maxPlayersBoard')}</label>
              <input id="score-cfg-maxplayers" type="number" min="5" max="100" value="${config.defaultMaxPlayers || 30}"
                class="input input-sm w-full input-bordered">
            </div>
            <div class="field-group">
              <label class="field-label">${t('scores.rowsPerColumn')}</label>
              <input id="score-cfg-rows" type="number" min="5" max="30" value="${config.defaultRowsPerColumn || 10}"
                class="input input-sm w-full input-bordered">
            </div>
            <div class="field-group flex items-end">
              <label class="flex items-center gap-2 cursor-pointer p-2 rounded border" style="border-color:var(--bg-border);background:var(--bg-elevated)">
                <input id="score-cfg-history" type="checkbox" class="toggle toggle-sm" ${config.enableHistory ? 'checked' : ''}>
                <span class="text-sm" style="color:var(--text-primary)">${t('scores.history')}</span>
              </label>
            </div>
          </div>

          <div class="section-divider"></div>
          <h4 class="text-sm font-semibold mb-3">${t('scores.scoreboards')}</h4>
          <div class="grid gap-2 mb-6">${boardToggles}</div>

          <div class="flex justify-between items-center pt-4" style="border-top:1px solid var(--bg-border)">
            <button class="btn-cg btn-cg-ghost" onclick="_nav('scores')">${t('common.back')}</button>
            <span id="scfg-dirty" class="text-xs" style="color:var(--amber);display:none">${t('common.save')}...</span>
          </div>
        </div>
      </div>
    </div>`;
}

// ── Mini-Games — list view ───────────────────────────────────────────────────

async function buildMiniGames(app) {
  let games, globalRanking;
  try {
    [games, globalRanking] = await Promise.all([
      API.scores.minigames(),
      API.scores.globalRanking(),
    ]);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">🎮</span><span class="empty-state-text">${t('scores.loadMinigamesError')}</span></div>`;
  }

  window._mg = {
    app, games,
    async createGame() {
      const id = document.getElementById('mg-new-id')?.value?.trim();
      const name = document.getElementById('mg-new-name')?.value?.trim();
      if (!id || !name) { app.toast(t('scores.idAndNameRequired'), 'warn'); return; }
      try {
        await API.scores.createMinigame({ id, displayName: name });
        app.toast(t('common.success'), 'success');
        app.navigate('scores:minigames');
      } catch (e) { app.toast(e.message, 'error'); }
    },
    async deleteGame(id) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.scores.deleteMinigame(id);
        app.toast(t('common.success'), 'success');
        app.navigate('scores:minigames');
      } catch (e) { app.toast(e.message, 'error'); }
    },
    openDetail(id) {
      app.navigate('scores:mg:' + id);
    },
  };

  // Global ranking table
  const globalRows = (globalRanking.ranking || []).length === 0
    ? '<tr><td colspan="3" class="text-center py-4" style="color:var(--muted)">' + t('common.noResults') + '</td></tr>'
    : globalRanking.ranking.map(p => {
        const medal = p.rank === 1 ? '\uD83E\uDD47' : p.rank === 2 ? '\uD83E\uDD48' : p.rank === 3 ? '\uD83E\uDD49' : '';
        const color = p.rank === 1 ? 'var(--amber)' : p.rank <= 3 ? 'var(--muted-light)' : 'inherit';
        return `<tr>
          <td style="color:${color};font-weight:${p.rank<=3?'600':'400'}">${medal} #${p.rank}</td>
          <td>${escHtml(p.name)}</td>
          <td class="td-mono text-right">${escHtml(p.formattedScore)}</td>
        </tr>`;
      }).join('');

  // Game cards
  const gameCards = games.length === 0
    ? '<div class="empty-state"><span class="empty-state-icon">\uD83C\uDFAE</span><span class="empty-state-text">' + t('common.noResults') + '</span></div>'
    : games.map(g => `
        <div class="section-card cursor-pointer hover:ring-1" style="--tw-ring-color:var(--amber)" onclick="_mg.openDetail('${escHtml(g.id)}')">
          <div class="section-card-body">
            <div class="flex items-start justify-between">
              <div>
                <h3 class="font-semibold text-base">${escHtml(g.displayName)}</h3>
                <p class="text-xs mt-1" style="color:var(--muted)"><span class="id-badge">${escHtml(g.id)}</span> &mdash; ${g.sessionCount} ${t('scores.sessions').toLowerCase()}</p>
              </div>
              <button class="btn-cg btn-cg-danger" style="font-size:0.7rem;padding:0.15rem 0.4rem" onclick="event.stopPropagation();_mg.deleteGame('${escHtml(g.id)}')" title="${t('common.delete')}">\u2715</button>
            </div>
            ${g.topPlayer ? `<div class="mt-2 text-sm" style="color:var(--muted-light)">\uD83E\uDD47 <span style="color:var(--amber)">${escHtml(g.topPlayer.name)}</span> <span class="td-mono text-xs">${escHtml(g.topPlayer.score)}</span></div>` : ''}
          </div>
        </div>
      `).join('');

  return `
    <div class="max-w-6xl mx-auto slide-in">
      <div class="flex items-center justify-between mb-6 flex-wrap gap-3">
        <div>
          <button class="detail-back" onclick="_nav('scores')">\u25C0 ${t('scores.leaderboards')}</button>
          <h1 class="text-xl font-bold mt-1">\uD83C\uDFAE ${t('scores.minigames')}</h1>
          <p class="text-sm" style="color:var(--muted)">${t('scores.globalRankDesc')}</p>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <!-- Global Ranking -->
        <div class="section-card">
          <div class="section-card-header">
            <span class="section-card-title">\uD83C\uDFC6 ${t('scores.globalRanking')}</span>
          </div>
          <div class="section-card-body" style="max-height:400px;overflow-y:auto">
            <table class="cg-table">
              <thead><tr><th style="width:80px">${t('common.rank')}</th><th>${t('common.player')}</th><th class="text-right">${t('common.points')}</th></tr></thead>
              <tbody>${globalRows}</tbody>
            </table>
          </div>
        </div>

        <!-- Create game -->
        <div class="section-card">
          <div class="section-card-header">
            <span class="section-card-title">${t('scores.newMiniGame')}</span>
          </div>
          <div class="section-card-body">
            <div class="grid grid-cols-2 gap-3 mb-3">
              <div class="field-group">
                <label class="field-label">${t('scores.uniqueId')}</label>
                <input id="mg-new-id" type="text" placeholder="cobblehunt" class="input input-sm w-full input-bordered">
              </div>
              <div class="field-group">
                <label class="field-label">${t('items.displayName')}</label>
                <input id="mg-new-name" type="text" placeholder="CobbleHunt" class="input input-sm w-full input-bordered">
              </div>
            </div>
            <button class="btn-cg btn-cg-primary w-full" onclick="_mg.createGame()">${t('common.create')}</button>
          </div>
        </div>
      </div>

      <!-- Game cards grid -->
      <h3 class="text-sm font-semibold mb-3" style="color:var(--muted-light)">${t('scores.games')} (${games.length})</h3>
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        ${gameCards}
      </div>
    </div>`;
}

// ── Mini-Game Detail — sessions + per-game ranking ──────────────────────────

async function buildMiniGameDetail(app, gameId) {
  let game;
  try {
    game = await API.scores.minigame(gameId);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const _mgd = window._mgd = {
    app, game, gameId,

    async rename() {
      const name = document.getElementById('mgd-name')?.value?.trim();
      if (!name) return;
      try {
        await API.scores.updateMinigame(gameId, { displayName: name });
        app.toast(t('common.success'), 'success');
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async addSession() {
      // Read rows from DOM
      const rows = document.querySelectorAll('.mgd-session-row');
      const rankings = [];
      rows.forEach(row => {
        const name = row.querySelector('.mgd-p-name')?.value?.trim();
        const score = parseFloat(row.querySelector('.mgd-p-score')?.value) || 0;
        if (name) rankings.push({ uuid: '', name, score });
      });
      if (rankings.length === 0) { app.toast(t('scores.addOnePlayer'), 'warn'); return; }
      // Sort by score descending
      rankings.sort((a, b) => b.score - a.score);
      try {
        await API.scores.addSession(gameId, { rankings });
        app.toast(t('common.success'), 'success');
        app.navigate('scores:mg:' + gameId);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    addPlayerRow() {
      const container = document.getElementById('mgd-new-players');
      if (!container) return;
      const idx = container.querySelectorAll('.mgd-session-row').length;
      const row = document.createElement('div');
      row.className = 'mgd-session-row flex gap-2 items-center mb-2';
      row.innerHTML = `
        <span class="text-xs" style="color:var(--muted);width:2rem">#${idx + 1}</span>
        <input class="mgd-p-name input input-sm input-bordered flex-1" placeholder="${t('common.playerSearch')}">
        <input class="mgd-p-score input input-sm input-bordered w-20" type="number" placeholder="${t('common.score')}">
        <button class="btn-cg btn-cg-danger" style="font-size:0.7rem;padding:0.1rem 0.3rem" onclick="this.closest('.mgd-session-row').remove()">\u2715</button>
      `;
      container.appendChild(row);
    },

    async deleteSession(idx) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.scores.deleteSession(gameId, idx);
        app.toast(t('common.success'), 'success');
        app.navigate('scores:mg:' + gameId);
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  // Per-game aggregated ranking
  const ranking = game.ranking || [];
  const rankRows = ranking.length === 0
    ? '<tr><td colspan="3" class="text-center py-4" style="color:var(--muted)">' + t('common.noResults') + '</td></tr>'
    : ranking.map(p => {
        const medal = p.rank === 1 ? '\uD83E\uDD47' : p.rank === 2 ? '\uD83E\uDD48' : p.rank === 3 ? '\uD83E\uDD49' : '';
        const color = p.rank === 1 ? 'var(--amber)' : p.rank <= 3 ? 'var(--muted-light)' : 'inherit';
        return `<tr>
          <td style="color:${color};font-weight:${p.rank<=3?'600':'400'}">${medal} #${p.rank}</td>
          <td>${escHtml(p.name)}</td>
          <td class="td-mono text-right">${escHtml(p.formattedScore)}</td>
        </tr>`;
      }).join('');

  // Sessions list (most recent first)
  const sessions = game.sessions || [];
  const sessionCards = sessions.length === 0
    ? '<p class="text-sm py-4" style="color:var(--muted)">' + t('common.noResults') + '</p>'
    : sessions.map(s => {
        const topPlayers = (s.rankings || []).slice(0, 5).map((r, i) => {
          const m = i === 0 ? '\uD83E\uDD47' : i === 1 ? '\uD83E\uDD48' : i === 2 ? '\uD83E\uDD49' : `#${i+1}`;
          return `<span class="text-xs">${m} ${escHtml(r.name)} <span class="td-mono">(${r.score})</span></span>`;
        }).join(' &middot; ');
        return `<div class="flex items-center gap-3 py-2 px-3 rounded" style="background:var(--bg-elevated);border:1px solid var(--bg-border);margin-bottom:0.5rem">
          <span class="text-xs shrink-0" style="color:var(--muted)">${I18N.fmtDate(s.date)}</span>
          <div class="flex-1 min-w-0 truncate">${topPlayers}</div>
          <span class="text-xs shrink-0" style="color:var(--muted)">${(s.rankings||[]).length} ${t('common.players').toLowerCase()}</span>
          <button class="btn-cg btn-cg-danger" style="font-size:0.65rem;padding:0.1rem 0.3rem" onclick="_mgd.deleteSession(${s.index})" title="${t('common.delete')}">\u2715</button>
        </div>`;
      }).join('');

  return `
    <div class="max-w-5xl mx-auto slide-in">
      <div class="section-card">
        <div class="detail-header">
          ${detailBackBtn('scores:minigames', t('scores.minigames'))}
          <span class="text-sm font-semibold">\uD83C\uDFAE ${escHtml(game.displayName)}</span>
          <span class="ml-2 id-badge">${escHtml(gameId)}</span>
          <span class="text-xs ml-auto" style="color:var(--muted)">${sessions.length} ${t('scores.sessions').toLowerCase()}</span>
        </div>

        <div class="section-card-body">
          <!-- Rename -->
          <div class="flex gap-2 items-end mb-6">
            <div class="field-group flex-1">
              <label class="field-label">${t('scores.gameName')}</label>
              <input id="mgd-name" type="text" value="${escHtml(game.displayName)}" class="input input-sm w-full input-bordered">
            </div>
            <button class="btn-cg btn-cg-primary" onclick="_mgd.rename()">${t('common.rename')}</button>
          </div>

          <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <!-- Ranking -->
            <div>
              <h4 class="text-sm font-semibold mb-3">${t('scores.leaderboard')}</h4>
              <div style="max-height:400px;overflow-y:auto">
                <table class="cg-table">
                  <thead><tr><th style="width:70px">${t('common.rank')}</th><th>${t('common.player')}</th><th class="text-right">${t('common.points')}</th></tr></thead>
                  <tbody>${rankRows}</tbody>
                </table>
              </div>
            </div>

            <!-- Add session -->
            <div>
              <h4 class="text-sm font-semibold mb-3">${t('common.add')}</h4>
              <div id="mgd-new-players">
                <div class="mgd-session-row flex gap-2 items-center mb-2">
                  <span class="text-xs" style="color:var(--muted);width:2rem">#1</span>
                  <input class="mgd-p-name input input-sm input-bordered flex-1" placeholder="${t('common.playerName')}">
                  <input class="mgd-p-score input input-sm input-bordered w-20" type="number" placeholder="${t('common.score')}">
                  <button class="btn-cg btn-cg-danger" style="font-size:0.7rem;padding:0.1rem 0.3rem" onclick="this.closest('.mgd-session-row').remove()">\u2715</button>
                </div>
              </div>
              <div class="flex gap-2 mt-2">
                <button class="btn-cg btn-cg-ghost flex-1" onclick="_mgd.addPlayerRow()">${t('scores.addPlayer')}</button>
                <button class="btn-cg btn-cg-primary flex-1" onclick="_mgd.addSession()">${t('common.save')}</button>
              </div>
            </div>
          </div>

          <!-- Sessions history -->
          <div class="section-divider mt-6 mb-4"></div>
          <h4 class="text-sm font-semibold mb-3">${t('scores.sessionHistory')}</h4>
          <div style="max-height:500px;overflow-y:auto">
            ${sessionCards}
          </div>
        </div>
      </div>
    </div>`;
}

// ── Score Detail — individual scoreboard view ────────────────────────────────

async function buildScoreDetail(app, boardId) {
  let board;
  try {
    board = await API.scores.getScoreboard(boardId);
  } catch (e) {
    app.toast(e.message, 'error');
    return `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
  }

  const rankings = board.rankings || [];
  const rankingsRows = rankings.length === 0
    ? '<tr><td colspan="3" class="text-center py-6" style="color:var(--text-muted)">' + t('common.noResults') + '</td></tr>'
    : rankings.map(p => {
        const medal = p.rank === 1 ? '\uD83E\uDD47' : p.rank === 2 ? '\uD83E\uDD48' : p.rank === 3 ? '\uD83E\uDD49' : '';
        const bold = p.rank <= 3 ? 'font-semibold' : '';
        const color = p.rank === 1 ? 'var(--amber)' : p.rank === 2 ? 'var(--text-secondary)' : p.rank === 3 ? '#cd7f32' : 'var(--text-primary)';
        return `<tr>
          <td class="${bold}" style="color:${color}">${medal} #${p.rank}</td>
          <td>${escHtml(p.name)}</td>
          <td class="td-mono text-right">${escHtml(p.formattedScore)}</td>
        </tr>`;
      }).join('');

  const colors = board.colors || {};

  const _scdImgLabel = encodeURIComponent((board.title || boardId).replace(/[^a-zA-Z0-9_\-\s]/g, '').trim().replace(/\s+/g, '-') || boardId);
  const _scdPublicUrl = location.origin + '/scores/api/images/' + _scdImgLabel + '/' + encodeURIComponent(boardId) + '.png';

  window._scd = {
    app, board, boardId, imageUrl: null,
    publicUrl: _scdPublicUrl,

    async loadImage() {
      const imgEl = document.getElementById('scd-image');
      if (!imgEl || !board.hasImage) return;
      imgEl.innerHTML = '<div class="flex justify-center py-8"><span class="skeleton-line" style="width:200px;height:20px"></span></div>';
      try {
        this.imageUrl = await API.scores.getImage(boardId);
        imgEl.innerHTML = `<img src="${this.imageUrl}" alt="${escHtml(board.title)}" class="rounded-lg w-full max-h-96 object-contain" style="background:var(--bg)">`;
      } catch {
        imgEl.innerHTML = '<div class="text-center py-6 text-sm" style="color:var(--text-muted)">' + t('scores.imageUnavailable') + '</div>';
      }
    },

    copyUrl() {
      cgCopy(this.publicUrl)
        .then(() => this.app.toast(t('common.copied'), 'success'))
        .catch(() => this.app.toast(t('scores.copyError'), 'error'));
    },

    async refresh() {
      try {
        await API.scores.refresh();
        app.toast(t('common.success'), 'success');
        app.navigate('scores:' + boardId);
      } catch (e) { app.toast(e.message, 'error'); }
    },

    async saveConfig() {
      const f = (id) => document.getElementById(id)?.value || '';
      const data = {
        title: f('scd-title'),
        description: f('scd-desc'),
        enabled: document.getElementById('scd-enabled')?.checked ?? true,
        colors: {
          titleBackground: f('scd-color-bg'),
          titleText: f('scd-color-text'),
        },
      };
      try {
        await API.scores.updateScoreboard(boardId, data);
        app.toast(t('common.success'), 'success');
        // Reload to see new image
        app.navigate('scores:' + boardId);
      } catch (e) { app.toast(e.message, 'error'); }
    },
  };

  const enabledBadge = board.enabled
    ? '<span class="status-dot s-online"></span> <span class="text-xs" style="color:var(--green)">' + t('common.active') + '</span>'
    : '<span class="status-dot s-offline"></span> <span class="text-xs" style="color:var(--red)">' + t('common.inactive') + '</span>';

  setTimeout(() => { if (window._scd) _scd.loadImage(); }, 100);

  // Color preview
  const headerBg = colors.titleBackground || '#ffcb05';
  const headerText = colors.titleText || '';

  return `<div class="max-w-5xl mx-auto" style="animation:slide-in-right 0.2s ease-out">
    <div class="section-card">
      <div class="detail-header">
        ${detailBackBtn('scores', t('scores.leaderboards'))}
        <span class="text-sm font-semibold" style="color:var(--text-heading)">\uD83C\uDFC6 ${escHtml(board.title || boardId)}</span>
        <span class="ml-2">${enabledBadge}</span>
        <span class="text-xs ml-auto" style="color:var(--text-muted)">${rankings.length} ${t('common.players').toLowerCase()}</span>
      </div>

      <div class="section-card-body">
        <!-- Customization section -->
        <div class="mb-6 p-4 rounded" style="background:var(--bg-elevated);border:1px solid var(--bg-border)">
          <h4 class="text-sm font-semibold mb-3">${t('scores.customization')}</h4>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-3">
            <div class="field-group">
              <label class="field-label">${t('common.title')}</label>
              <input id="scd-title" type="text" value="${escHtml(board.title || '')}" class="input input-sm w-full input-bordered">
            </div>
            <div class="field-group">
              <label class="field-label">${t('common.subtitle')}</label>
              <input id="scd-desc" type="text" value="${escHtml(board.description || '')}" class="input input-sm w-full input-bordered">
            </div>
          </div>
          <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-3">
            <div class="field-group">
              <label class="field-label">${t('scores.headerBgColor')}</label>
              <div class="flex gap-2 items-center">
                <input id="scd-color-bg" type="color" value="${escHtml(headerBg)}" style="width:2.5rem;height:2rem;cursor:pointer;border:1px solid var(--bg-border);border-radius:4px;background:var(--bg-elevated)">
                <input type="text" value="${escHtml(headerBg)}" class="input input-sm input-bordered flex-1 font-mono text-xs"
                  oninput="document.getElementById('scd-color-bg').value=this.value"
                  onchange="document.getElementById('scd-color-bg').value=this.value">
              </div>
            </div>
            <div class="field-group">
              <label class="field-label">${t('scores.titleTextColor')}</label>
              <div class="flex gap-2 items-center">
                <input id="scd-color-text" type="color" value="${escHtml(headerText || '#ffffff')}" style="width:2.5rem;height:2rem;cursor:pointer;border:1px solid var(--bg-border);border-radius:4px;background:var(--bg-elevated)">
                <input type="text" value="${escHtml(headerText)}" placeholder="auto" class="input input-sm input-bordered flex-1 font-mono text-xs"
                  oninput="document.getElementById('scd-color-text').value=this.value||'#ffffff'"
                  onchange="document.getElementById('scd-color-text').value=this.value||'#ffffff'">
              </div>
            </div>
            <div class="field-group flex items-end">
              <label class="flex items-center gap-2 cursor-pointer p-2 rounded border" style="border-color:var(--bg-border);background:var(--bg)">
                <input id="scd-enabled" type="checkbox" class="toggle toggle-sm" ${board.enabled ? 'checked' : ''}>
                <span class="text-sm">${t('common.active')}</span>
              </label>
            </div>
            <div class="field-group flex items-end">
              <button class="btn-cg btn-cg-primary w-full" onclick="_scd.saveConfig()">${t('common.save')}</button>
            </div>
          </div>
        </div>

        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <!-- Rankings table -->
          <div>
            <h4 class="text-sm font-semibold mb-3" style="color:var(--text-heading)">${t('scores.ranking')}</h4>
            <div style="max-height:500px;overflow-y:auto">
              <table class="cg-table">
                <thead><tr><th style="width:80px">${t('common.rank')}</th><th>${t('common.player')}</th><th class="text-right">${t('common.score')}</th></tr></thead>
                <tbody>${rankingsRows}</tbody>
              </table>
            </div>
            <div class="mt-2 text-sm" style="color:var(--text-muted)">
              ${board.description ? escHtml(board.description) : ''}
              ${board.lastUpdate ? ' \u2014 ' + I18N.fmtDate(board.lastUpdate) : ''}
            </div>
          </div>

          <!-- Image -->
          <div>
            <div class="flex items-center justify-between mb-3">
              <h4 class="text-sm font-semibold" style="color:var(--text-heading)">${t('scores.pngImage')}</h4>
              <button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="_scd.copyUrl()">📋 ${t('scores.copyImageUrl')}</button>
            </div>
            <div id="scd-image" class="p-2 rounded" style="background:var(--bg);border:1px solid var(--bg-border)">
              ${board.hasImage
                ? '<div class="flex justify-center py-8"><span class="skeleton-line" style="width:200px;height:20px"></span></div>'
                : '<div class="text-center py-6 text-sm" style="color:var(--text-muted)">' + t('common.noResults') + '</div>'}
            </div>
            <div class="mt-2 text-xs font-mono truncate" style="color:var(--muted);max-width:100%;overflow:hidden">${escHtml(_scdPublicUrl)}</div>
          </div>
        </div>

        <div class="mt-4 flex justify-end">
          <button class="btn-cg btn-cg-primary" onclick="_scd.refresh()">${t('scores.refreshScoreboards')}</button>
        </div>
      </div>
    </div>
  </div>`;
}
