// ── Overview ─────────────────────────────────────────────────────────────────
async function buildOverview(app) {
  let s = app.serverStatus;

  // Fetch module stats + modules list (graceful if unavailable)
  let marketStats = null, eggsStats = null, scoresStats = null, dashModules = [];
  try { marketStats = await API.market.stats(); } catch(e) {}
  try { eggsStats = await API.eggs.stats(); } catch(e) {}
  try { scoresStats = await API.scores.stats(); } catch(e) {}
  try { dashModules = await API.get('/modules'); } catch(e) {}

  // Stat cards data
  const stats = [
    { label: t('overview.playersOnline'), value: s.playerCount != null ? `${s.playerCount}/${s.maxPlayers}` : '—', icon: '👥', color: 'text-cyan' },
    { label: t('overview.activeZones'),    value: s.zoneCount ?? '—',                    icon: '⬜', color: 'text-amber' },
    { label: t('overview.dimensions'),       value: s.dimensionCount ?? '—',               icon: '🌀', color: 'text-purple-400' },
    { label: t('overview.activeEvents'),value: s.activeEvents ?? '—',                 icon: '🎯', color: 'text-green' },
    { label: t('overview.uptime'),           value: s.uptime ? I18N.fmtUptime(s.uptime) : '—',  icon: '⏱', color: 'text-muted-light' },
    { label: t('overview.mods'),             value: s.modCount ?? '—',                      icon: '📦', color: 'text-muted' },
  ];

  const statCards = stats.map(st => `
    <div class="stat-card">
      <div class="stat-card-glow"></div>
      <div class="stat-label">${st.label}</div>
      <div class="stat-value ${st.color} mt-2">${st.value}</div>
      <div class="stat-icon">${st.icon}</div>
    </div>
  `).join('');

  // Module cards from dynamic modules list + stats
  const modStatMap = {};
  if (marketStats) modStatMap['market'] = { value: marketStats.shopCount, sub: `${marketStats.totalItems} ${t('market.items')}, ${marketStats.transactionCount} ${t('market.transactions')}` };
  if (eggsStats) modStatMap['eggs'] = { value: eggsStats.totalCollectibles ?? eggsStats.total ?? '—', sub: `${eggsStats.totalDiscoveries ?? 0} ${t('eggs.discoveries')}` };
  if (scoresStats) modStatMap['scores'] = { value: scoresStats.totalScoreboards ?? scoresStats.total ?? '—', sub: `${scoresStats.enabledScoreboards ?? scoresStats.enabled ?? 0} ${t('common.active')}` };
  const modColorMap = { cheeseguard: 'text-amber', market: 'text-amber', eggs: 'text-cyan', scores: 'text-green', shift: 'text-purple-400' };

  const modCardsHtml = (dashModules || []).map(mod => {
    const st = modStatMap[mod.prefix] || {};
    const color = modColorMap[mod.prefix] || 'text-muted-light';
    return `<button onclick="_nav('${escHtml(mod.prefix)}')" class="stat-card text-left cursor-pointer hover:border-amber/20 transition-colors">
      <div class="stat-icon">${mod.icon || '📦'}</div>
      <div class="font-semibold text-sm ${color} mt-2">${escHtml(mod.displayName)}</div>
      <div class="stat-label">${escHtml(mod.description || mod.prefix)}</div>
      ${st.sub ? `<div class="text-sm text-muted mt-1">${st.sub}</div>` : ''}
    </button>`;
  }).join('');

  // Quick nav cards
  const modules = [
    { id: 'zones',      icon: '\u2B1C', name: t('nav.zones'),       desc: t('nav.zones.desc'),  count: s.zoneCount },
    { id: 'dimensions', icon: '\uD83C\uDF00', name: t('nav.dimensions'),  desc: t('nav.dimensions.desc'),    count: s.dimensionCount },
    { id: 'events',     icon: '\uD83C\uDFAF', name: t('nav.events'),  desc: t('nav.events.desc'),      count: s.eventCount },
    { id: 'players',    icon: '\uD83D\uDC65', name: t('nav.players'),     desc: t('nav.players.desc'),     count: s.playerCount },
    { id: 'chunks',     icon: '\uD83D\uDDFA', name: t('nav.chunks'),      desc: t('nav.chunks.desc'),  count: null },
    { id: 'server',     icon: '\u2699', name: t('nav.server'),     desc: t('nav.server.desc'),      count: null },
  ];

  const moduleCards = modules.map(m => `
    <button
      onclick="_nav('${m.id}')"
      class="section-card text-left p-4 hover:border-amber/20 transition-colors cursor-pointer"
      style="background: var(--bg-card)"
    >
      <div class="flex items-start justify-between mb-3">
        <span class="text-2xl">${m.icon}</span>
        ${m.count != null ? `<span class="id-badge">${m.count}</span>` : ''}
      </div>
      <div class="font-semibold text-sm text-tx">${m.name}</div>
      <div class="text-sm text-muted mt-0.5">${m.desc}</div>
    </button>
  `).join('');

  // Build HTML then schedule activity feed render after DOM ready
  const html = `
    <div class="max-w-6xl mx-auto space-y-8" style="animation: slide-in-right 0.2s ease-out">

      <!-- Header -->
      <div>
        <h2 class="text-xl font-semibold" style="color:var(--text-heading)">${t('overview.dashboard')}</h2>
        <p class="text-sm mt-0.5 font-mono" style="color:var(--text-muted)">
          ${I18N.fmtDate(new Date().toISOString())}
        </p>
      </div>

      <!-- Stats grid -->
      <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
        ${statCards}
      </div>

      <!-- Server status banner -->
      <div class="section-card">
        <div class="section-card-header">
          <span class="text-sm">⚡</span>
          <span class="section-card-title">${t('overview.serverStatus')}</span>
        </div>
        <div class="section-card-body">
          <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div>
              <div class="text-sm font-mono mb-1" style="color:var(--text-muted)">${t('overview.motd')}</div>
              <div class="text-sm font-mono truncate" style="color:var(--text-primary)">${s.motd || t('common.unknown')}</div>
            </div>
            <div>
              <div class="text-sm font-mono mb-1" style="color:var(--text-muted)">${t('overview.version')}</div>
              <div class="text-sm font-mono" style="color:var(--text-primary)">${s.version || '—'}</div>
            </div>
            <div>
              <div class="text-sm font-mono mb-1" style="color:var(--text-muted)">${t('overview.connection')}</div>
              <div class="flex items-center gap-2">
                <span class="status-dot ${app.serverOnline ? 'status-online' : 'status-offline'}"></span>
                <span class="text-sm font-mono" style="color:${app.serverOnline ? 'var(--green)' : 'var(--red)'}">${app.serverOnline ? t('common.online') : t('common.offline')}</span>
              </div>
            </div>
            <div>
              <div class="text-sm font-mono mb-1" style="color:var(--text-muted)">${t('overview.uptimeLabel')}</div>
              <div class="text-sm font-mono" style="color:var(--text-primary)">${s.uptime ? I18N.fmtUptime(s.uptime) : '—'}</div>
            </div>
          </div>
        </div>
      </div>

      ${modCardsHtml ? `
      <!-- Modules CheeseDash -->
      <div>
        <h3 class="text-sm font-mono mb-3 tracking-widest uppercase" style="color:var(--text-muted)">${t('overview.modules')}</h3>
        <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-${Math.min((dashModules || []).length, 5)} gap-3">
          ${modCardsHtml}
        </div>
      </div>` : ''}

      <!-- Quick actions -->
      <div>
        <h3 class="text-sm font-mono mb-3 tracking-widest uppercase" style="color:var(--text-muted)">${t('overview.quickActions')}</h3>
        <div class="flex gap-2 flex-wrap">
          <button class="btn-cg btn-cg-primary" onclick="_nav('events')">${t('overview.createEvent')}</button>
          <button class="btn-cg btn-cg-ghost" onclick="_nav('dimensions')">${t('overview.newDimension')}</button>
          <button class="btn-cg btn-cg-ghost" onclick="_nav('players')">${t('overview.viewPlayers')}</button>
          <button class="btn-cg btn-cg-ghost" onclick="_nav('server')">${t('overview.serverConsole')}</button>
        </div>
      </div>

      <!-- Module quick access -->
      <div>
        <h3 class="text-sm font-mono mb-3 tracking-widest uppercase" style="color:var(--text-muted)">${t('overview.quickNav')}</h3>
        <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
          ${moduleCards}
        </div>
      </div>

      <!-- Activity feed -->
      <div class="section-card">
        <div class="section-card-header">
          <span class="text-sm">📋</span>
          <span class="section-card-title">${t('overview.recentActivity')}</span>
        </div>
        <div class="section-card-body" id="ov-activity-feed">
          <div class="text-center text-sm py-4" style="color:var(--text-muted)">${t('overview.noActivity')}</div>
        </div>
      </div>

    </div>`;

  // Render activity feed after DOM is mounted
  setTimeout(() => app._renderActivityFeed(), 50);
  return html;
}
