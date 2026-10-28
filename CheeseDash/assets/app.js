/**
 * ArkensiaAdmin — Alpine.js Application
 * Main store: authentication, navigation, view routing, toast system.
 */

// ── Login form component ──────────────────────────────────────────────────────
function loginForm() {
  return {
    token: '',
    remember: !!localStorage.getItem('cg_remember_token'),
    loading: false,
    error: null,
    showToken: false,

    async submit() {
      this.error = null;
      if (!this.token.trim()) return;
      this.loading = true;
      try {
        const rawToken = this.token.trim();
        const session = await API.login(rawToken);
        API.setSession(session.sessionId, session.username, session.role, this.remember);
        // Save raw token so we can re-login after server restart
        if (this.remember) localStorage.setItem('cg_remember_token', rawToken);
        else localStorage.removeItem('cg_remember_token');
        this.$dispatch('cg-authenticated');
      } catch (e) {
        const msg = e.message || '';
        if (msg.includes('invalide') || msg === 'UNAUTHORIZED') this.error = t('error.invalidToken');
        else if (msg === 'RATE_LIMITED') this.error = t('error.tooManyAttempts');
        else if (msg === 'NETWORK_ERROR') this.error = t('error.serverUnreachable');
        else this.error = t('error.generic', {msg});
      } finally {
        this.loading = false;
      }
    },
  };
}

// ── Navigation configuration ──────────────────────────────────────────────────
const NAV_SECTIONS = [
  {
    label: t('nav.section.general'),
    items: [
      { id: 'overview',    icon: '⬡',  label: t('nav.overview'),        description: t('nav.overview.desc') },
    ]
  },
  {
    label: t('nav.section.cheeseguard'),
    prefix: 'cheeseguard',
    items: [
      { id: 'zones',       icon: '⬜',  label: t('nav.zones'),           description: t('nav.zones.desc') },
      { id: 'chunks',      icon: '🗺',  label: t('nav.chunks'),          description: t('nav.chunks.desc') },
      { id: 'dimensions',  icon: '🌀',  label: t('nav.dimensions'),      description: t('nav.dimensions.desc') },
      { id: 'events',      icon: '🎯',  label: t('nav.events'),          description: t('nav.events.desc') },
      { id: 'players',     icon: '👥',  label: t('nav.players'),         description: t('nav.players.desc') },
      { id: 'prison',      icon: '⛓',  label: t('nav.prison'),          description: t('nav.prison.desc') },
      { id: 'server',      icon: '⚙',  label: t('nav.server'),          description: t('nav.server.desc') },
    ]
  },
  {
    label: t('nav.section.cheesemarket'),
    prefix: 'market',
    items: [
      { id: 'market',       icon: '🛒',  label: t('nav.market'),          description: t('nav.market.desc') },
    ]
  },
  {
    label: t('nav.section.eggandcheese'),
    prefix: 'eggs',
    items: [
      { id: 'eggs',         icon: '🥚',  label: t('nav.eggs'),            description: t('nav.eggs.desc') },
    ]
  },
  {
    label: t('nav.section.cobblecheesescore'),
    prefix: 'scores',
    items: [
      { id: 'scores',       icon: '🏆',  label: t('nav.scores'),          description: t('nav.scores.desc') },
    ]
  },
  {
    label: t('nav.section.cobbleshift'),
    prefix: 'shift',
    items: [
      { id: 'shift',        icon: '✦',   label: t('nav.shift'),           description: t('nav.shift.desc') },
    ]
  },
  {
    label: t('nav.section.cobblemondungeons'),
    prefix: 'dungeons',
    items: [
      { id: 'dungeons',     icon: '🏰',  label: t('nav.dungeons'),        description: t('nav.dungeons.desc') },
    ]
  },
  {
    label: t('nav.section.cheeseitems'),
    prefix: 'items',
    items: [
      { id: 'items',        icon: '🎁',  label: t('nav.items'),           description: t('nav.items.desc') },
      { id: 'morphos',      icon: '🧬',  label: t('nav.morphos'),         description: t('nav.morphos.desc') },
    ]
  },
  {
    label: t('nav.section.cheesekart'),
    prefix: 'kart',
    items: [
      { id: 'kart',         icon: '🏎',  label: t('nav.kart'),            description: t('nav.kart.desc') },
    ]
  },
  {
    label: t('nav.section.cheesepatrol'),
    prefix: 'patrol',
    items: [
      { id: 'patrol',       icon: '🚶',  label: t('nav.patrol'),          description: t('nav.patrol.desc') },
    ]
  },
  {
    label: t('nav.section.cheesestore'),
    prefix: 'store',
    items: [
      { id: 'store',        icon: '💎',  label: t('nav.store'),           description: t('nav.store.desc') },
      { id: 'playcosmetics', icon: '✨',  label: 'Cosmétiques',           description: 'Boutique premium & gestion des cosmétiques jouables' },
    ]
  },
  {
    label: t('nav.section.cobblecap'),
    prefix: 'cobblecap',
    items: [
      { id: 'cobblecap', icon: '🏅', label: t('nav.cobblecap'), description: t('nav.cobblecap.desc') },
    ]
  },
  {
    label: t('nav.section.cheesemusic'),
    prefix: 'music',
    items: [
      { id: 'music', icon: '🎵', label: t('nav.music'), description: t('nav.music.desc') },
    ]
  },
  {
    label: 'Guerre de Factions',
    prefix: 'factionwar',
    items: [
      { id: 'factionwar', icon: '⚔️', label: 'Guerre de Factions', description: 'Suivi & admin de l\'événement PvP' },
      { id: 'wizard', icon: '🧙', label: 'Assistant de partie', description: 'Wizard de mise en place de l\'événement' },
    ]
  },
  {
    key: 'admin',
    label: t('nav.section.admin'),
    items: [
      { id: 'webhooks',     icon: '🔔',  label: t('nav.webhooks'),        description: t('nav.webhooks.desc') },
      { id: 'accounts',     icon: '👤',  label: t('nav.accounts'),        description: t('nav.accounts.desc') },
      { id: 'audit',        icon: '📋',  label: t('nav.audit'),           description: t('nav.audit.desc') },
    ]
  },
];

// ── Main App component ────────────────────────────────────────────────────────
function cgApp() {
  return {
    authenticated: false,
    sidebarOpen: true,
    mobileMenuOpen: false,
    currentView: 'overview',
    viewHtml: '',
    viewLoading: false,
    toasts: [],
    _toastCounter: 0,
    _lastToastMsg: '',
    _lastToastTime: 0,
    _wsLostAt: 0,
    _wsDownSeconds: 0,

    navSections: NAV_SECTIONS,
    breadcrumbs: [],

    serverStatus: {
      tps: null,
      playerCount: null,
      maxPlayers: null,
    },
    serverOnline: false,
    refreshing: false,
    adminPlayer: localStorage.getItem('cg_admin_player') || '',
    currentTheme: localStorage.getItem('cg-theme') || 'cg-dark',
    // Session info
    sessionUsername: '',
    sessionRole: '',
    notifications: [],
    _notifCounter: 0,

    // ── Init ────────────────────────────────────────────────────────────────
    async init() {
      // Listen for login event from child component
      this.$el.addEventListener('cg-authenticated', () => {
        this.onAuthenticated();
      });

      // Auto-login if session/token stored
      if (API.hasSession()) {
        try {
          const session = await API.verifySession();
          if (session) {
            this.sessionUsername = session.username;
            this.sessionRole = session.role;
            this.onAuthenticated();
          } else {
            await this._tryRememberLogin();
          }
        } catch (e) {
          await this._tryRememberLogin();
        }
      } else {
        await this._tryRememberLogin();
      }

      // Handle browser back/forward
      window.addEventListener('popstate', (e) => {
        if (e.state?.view) this.navigate(e.state.view, false);
      });
    },

    async _tryRememberLogin() {
      const saved = localStorage.getItem('cg_remember_token');
      if (!saved) { API.clearSession(); return; }
      try {
        const session = await API.login(saved);
        API.setSession(session.sessionId, session.username, session.role, true);
        this.onAuthenticated();
      } catch (e) {
        localStorage.removeItem('cg_remember_token');
        API.clearSession();
      }
    },

    async onAuthenticated() {
      this.authenticated = true;
      this.sessionUsername = API.getUsername();
      this.sessionRole = API.getRole();
      // Fetch the set of backend-registered modules so the sidebar only shows installed ones.
      // On failure, keep ALL sections (graceful: never hide a section just because the probe failed).
      let installed = null;
      try {
        const mods = await API.get('/modules');
        if (Array.isArray(mods)) installed = new Set(mods.map(m => m.prefix));
      } catch (e) { installed = null; }
      // Filter nav sections by: (1) role (hide admin for non-admins), and
      // (2) installed module set — a section with a `prefix` only shows if that module is registered.
      // Sections without a `prefix` (Général, Administration) are infrastructure and always kept.
      this.navSections = NAV_SECTIONS.filter(s =>
        (s.key !== 'admin' || API.canAdmin()) &&
        (!s.prefix || installed === null || installed.has(s.prefix))
      );
      await this.refreshStatus();
      _initGlobalDataLists(); // non-blocking — injects species/items/dims datalists
      this.initWs();
      // Load admin player from server config
      try {
        const ap = await API.admin.getPlayer();
        if (ap.name) {
          this.adminPlayer = ap.name;
          localStorage.setItem('cg_admin_player', ap.name);
        }
      } catch (e) { /* ignore */ }
      // If no admin player set, prompt after first load
      if (!this.adminPlayer) {
        setTimeout(() => this.openAdminPlayerModal(), 500);
      }
      // Restore view from URL hash if present
      const hash = location.hash.replace('#', '');
      await this.navigate(hash || 'overview');
      // Fallback polling every 10s when WS is disconnected
      this._statusInterval = setInterval(() => {
        if (!this._wsConnected) this.refreshStatus();
        // Update WS down timer for disconnect banner (only show after sustained disconnect)
        this._wsDownSeconds = (this._wsLostAt && !this._wsConnected) ? Math.floor((Date.now() - this._wsLostAt) / 1000) : 0;
      }, 2000);
    },

    // ── WebSocket ─────────────────────────────────────────────────────────
    ws: null,
    _wsConnected: false,
    _wsReconnectTimer: null,
    tpsHistory: [],
    consoleLogs: [],
    livePlayerList: [],
    activeSchedule: null,

    initWs() {
      if (this.ws) { try { this.ws.close(); } catch(e){} }
      // WS port = HTTP port + 1 (returned in /status as wsPort)
      const wsPort = this.serverStatus?.wsPort || (parseInt(location.port) + 1);
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      const url = `${proto}://${location.hostname}:${wsPort}`;
      try {
        this.ws = new WebSocket(url);
      } catch(e) { return; }

      this.ws.onopen = () => {
        this.ws.send(JSON.stringify({ type: 'auth', token: API.getToken() }));
      };

      this.ws.onmessage = (event) => {
        try { this.handleWsMessage(JSON.parse(event.data)); } catch(e) {}
      };

      this.ws.onclose = () => {
        const wasConnected = this._wsConnected;
        this._wsConnected = false;
        if (wasConnected) {
          this._wsLostAt = Date.now();
        }
        if (this.authenticated) {
          this._wsReconnectTimer = setTimeout(() => this.initWs(), 3000);
        }
      };

      this.ws.onerror = () => {};
    },

    handleWsMessage(msg) {
      switch (msg.type) {
        case 'auth_ok':
          this._wsConnected = true;
          // Only show restore toast if WS was down for >5s (avoid spam on brief reconnects)
          if (this._wsLostAt && (Date.now() - this._wsLostAt) > 5000) {
            this.toast(t('header.wsConnected'), 'success', 2500);
          }
          this._wsLostAt = 0;
          this._wsDownSeconds = 0;
          this.ws.send(JSON.stringify({
            type: 'subscribe',
            channels: ['tps', 'players', 'logs', 'events', 'dimensions', 'market', 'eggs', 'audit', 'schedule', 'items', 'dungeons', 'store', 'prison']
          }));
          break;

        case 'tps':
          this.serverStatus.tps = msg.value;
          this.tpsHistory.push(msg.value);
          if (this.tpsHistory.length > 60) this.tpsHistory.shift();
          // Update TPS display in topbar if visible
          this._updateTpsDisplay();
          break;

        case 'player_count':
          this.serverStatus.playerCount = msg.count;
          this.serverStatus.maxPlayers = msg.max;
          break;

        case 'player_join':
          this.toast(msg.name + ' ' + t('common.online'), 'info', 2500);
          break;

        case 'player_leave':
          this.toast(msg.name + ' ' + t('common.offline'), 'info', 2500);
          break;

        case 'player_list':
          this.livePlayerList = msg.players || [];
          this.serverStatus.playerCount = this.livePlayerList.length;
          break;

        case 'log':
          this.consoleLogs.push(msg.line);
          if (this.consoleLogs.length > 500) this.consoleLogs.shift();
          // Update console view if active
          if (this.currentView === 'server' && window._sv) {
            window._sv.onWsLog(msg.line);
          }
          break;

        case 'event_update':
          // Refresh events view if active
          if (this.currentView === 'events' && window._ev) {
            window._ev.onWsEventUpdate(msg);
          }
          break;

        case 'dim_update':
          // Refresh dimensions view if active
          if (this.currentView === 'dimensions' && window._dv) {
            window._dv.onWsDimUpdate(msg);
          }
          break;

        case 'snapshot_done':
          this.toast(`${t('dims.snapshot')} — ${msg.dimId} (${fmtSize(msg.sizeKb)})`, 'success');
          break;

        case 'market_purchase':
          this.toast(`${msg.playerName} — ${msg.itemName}${msg.quantity > 1 ? ' x' + msg.quantity : ''}`, 'info', 3000);
          this._addActivity({ type: 'market', icon: '🛒', text: `${msg.playerName} — ${msg.itemName}`, time: msg.timestamp || Date.now() });
          if (this.currentView === 'market' && window._mk) window._mk.refresh();
          break;

        case 'egg_discovery':
          this.toast(`${msg.playerName} — ${msg.eggId}`, 'info', 3000);
          this._addActivity({ type: 'egg', icon: '🥚', text: `${msg.playerName} — ${msg.eggId}`, time: msg.timestamp || Date.now() });
          if (this.currentView === 'eggs' && window._eg) window._eg.refresh();
          break;

        case 'item_update':
          if (msg.action === 'created') this.toast(t('items.created') + ': ' + (msg.itemName || msg.itemId), 'success', 3000);
          else if (msg.action === 'updated') this.toast(t('common.edit') + ': ' + (msg.itemName || msg.itemId), 'info', 2500);
          else if (msg.action === 'deleted') this.toast(t('items.deleted') + ': ' + msg.itemId, 'warn', 3000);
          this._addActivity({ type: 'item', icon: '🎁', text: `${msg.action}: ${msg.itemName || msg.itemId}`, time: Date.now() });
          if (this.currentView === 'items' && window._ci) window._ci.refresh();
          break;

        case 'item_used':
          this._addActivity({ type: 'item', icon: '🎁', text: `${msg.playerName} — ${msg.itemName || msg.itemId}`, time: Date.now() });
          break;

        case 'audit':
          // Real-time audit event
          this._addNotification({
            icon: '📋',
            text: `${msg.username}: ${msg.action}`,
            detail: msg.detail,
            time: msg.timestamp ? new Date(msg.timestamp).getTime() : Date.now(),
          });
          // Update audit view if active
          if (this.currentView === 'audit' && window._au) {
            window._au.onWsAudit(msg);
          }
          break;

        case 'schedule_created':
          this.activeSchedule = msg.action || null;
          this.toast(t('server.restartScheduled') + ': ' + (msg.action?.type || '?'), 'info');
          if (this.currentView === 'server' && window._sv) window._sv.onScheduleUpdate(msg);
          break;

        case 'schedule_cancelled':
          this.activeSchedule = null;
          this.toast(t('common.cancel'), 'info');
          if (this.currentView === 'server' && window._sv) window._sv.onScheduleUpdate(msg);
          break;

        case 'schedule_tick':
          this.activeSchedule = { type: msg.actionType, remainingSeconds: msg.remaining, message: msg.message };
          if (this.currentView === 'server' && window._sv) window._sv.onScheduleTick(msg);
          break;

        case 'schedule_fired':
          this.activeSchedule = null;
          this.toast(t('common.success') + ': ' + (msg.actionType || ''), 'warn');
          if (this.currentView === 'server' && window._sv) window._sv.onScheduleUpdate(msg);
          break;

        case 'dungeon_start':
          this.toast(`${t('dungeons.title')}: ${msg.dungeonName} (T${msg.tier}) — ${msg.players?.length || 0} ${t('common.players')}`, 'info');
          this.addActivity('🏰', `${msg.dungeonName} T${msg.tier} ${t('common.start')}`);
          if (this.currentView === 'dungeons' && window._dg) window._dg.refresh();
          break;
        case 'dungeon_complete':
          this.toast(`${t('dungeons.completed')}: ${msg.dungeonName} (T${msg.tier})`, 'success');
          this.addActivity('🏰', `${msg.dungeonName} T${msg.tier} ${t('dungeons.completed')}`);
          if (this.currentView === 'dungeons' && window._dg) window._dg.refresh();
          break;
        case 'dungeon_fail':
          this.toast(`${t('dungeons.failed')}: ${msg.dungeonName} (T${msg.tier})`, 'warn');
          this.addActivity('🏰', `${msg.dungeonName} T${msg.tier} ${t('dungeons.failed')}`);
          if (this.currentView === 'dungeons' && window._dg) window._dg.refresh();
          break;
        case 'dungeon_boss_spawn':
          this.toast(`${t('dungeons.boss')}: ${msg.bossSpecies || '?'} — ${msg.dungeonName}`, 'info');
          if (this.currentView === 'dungeons' && window._dg) window._dg.refresh();
          break;
        case 'dungeon_boss_defeated':
          this.toast(`${t('dungeons.boss')} — ${msg.dungeonName}`, 'success');
          if (this.currentView === 'dungeons' && window._dg) window._dg.refresh();
          break;
        case 'dungeon_player_death':
          if (this.currentView === 'dungeons' && window._dg) window._dg.refresh();
          break;

        case 'store_purchase':
          this.toast(`${msg.playerName} — ${msg.productName} (${((msg.priceCents||0)/100).toFixed(2)}\u20AC)`, 'success', 5000);
          this._addActivity({ type: 'store', icon: '\uD83D\uDC8E', text: `${msg.playerName} — ${msg.productName}`, time: Date.now() });
          if (this.currentView === 'store' && window._st) { window._st._loaded = false; window._st.refresh(); }
          break;
        case 'store_update':
          if (this.currentView === 'store' && window._st) { window._st._loaded = false; window._st.refresh(); }
          break;

        case 'prison_added':
        case 'prison_released':
        case 'prison_updated':
          if (this.currentView === 'prison' && window._pr) window._pr.refresh();
          break;

        case 'pong':
          break;
      }
    },

    // ── Notifications ──────────────────────────────────────────────────────
    _addNotification(notif) {
      notif.id = ++this._notifCounter;
      notif.read = false;
      this.notifications.unshift(notif);
      if (this.notifications.length > 50) this.notifications.pop();
    },

    get unreadNotifCount() {
      return this.notifications.filter(n => !n.read).length;
    },

    markAllNotifsRead() {
      this.notifications.forEach(n => { n.read = true; });
    },

    // ── Activity feed ──────────────────────────────────────────────────────────
    activityFeed: [],
    _addActivity(entry) {
      this.activityFeed.unshift(entry);
      if (this.activityFeed.length > 50) this.activityFeed.pop();
      if (this.currentView === 'overview') this._renderActivityFeed();
    },
    _renderActivityFeed() {
      const el = document.getElementById('ov-activity-feed');
      if (!el) return;
      if (!this.activityFeed.length) { el.innerHTML = '<div class="text-muted text-center text-sm py-4">' + t('overview.noActivity') + '</div>'; return; }
      el.innerHTML = this.activityFeed.slice(0, 15).map(a => `
        <div class="flex items-center gap-2 py-1.5 border-b border-bg-border last:border-0">
          <span class="text-sm">${a.icon}</span>
          <span class="text-sm text-tx-dim flex-1">${escHtml(a.text)}</span>
          <span class="text-xs text-muted font-mono">${I18N.fmtDate(a.time)}</span>
        </div>`).join('');
    },

    _updateTpsDisplay() {
      const el = document.getElementById('topbar-tps');
      if (el) el.textContent = this.serverStatus.tps?.toFixed(1) || '—';
    },

    // ── Navigation ──────────────────────────────────────────────────────────
    pushBreadcrumb(label, viewId) {
      this.breadcrumbs.push({ label, viewId });
    },

    navigateBreadcrumb(idx) {
      if (idx < 0 || idx >= this.breadcrumbs.length) return;
      const crumb = this.breadcrumbs[idx];
      this.breadcrumbs = this.breadcrumbs.slice(0, idx);
      if (crumb.viewId) this.navigate(crumb.viewId);
    },

    async navigate(viewId, pushState = true) {
      if (this.currentView === viewId && this.viewHtml) return;
      this.mobileMenuOpen = false;
      this.currentView = viewId;
      this.viewLoading = true;

      // Show inline skeleton immediately while view loads
      this.viewHtml = skeletonPage();

      // Determine if this is a detail view (e.g. "zones:test_zone")
      const colonIdx = viewId.indexOf(':');
      const isDetail = colonIdx > 0;
      const baseView = isDetail ? viewId.substring(0, colonIdx) : viewId;

      // Build breadcrumbs
      // Groups are nested under Zones — map their breadcrumb parent accordingly
      const parentView = baseView === 'groups' ? 'zones' : baseView;
      const navItem = this.navSections.flatMap(s => s.items).find(i => i.id === parentView);
      if (isDetail) {
        const detailId = viewId.substring(colonIdx + 1);
        this.breadcrumbs = [
          { label: navItem?.label || parentView, viewId: parentView },
          { label: detailId, viewId },
        ];
      } else {
        this.breadcrumbs = navItem ? [{ label: navItem.label, viewId }] : [{ label: viewId, viewId }];
      }

      if (pushState) {
        history.pushState({ view: viewId }, '', `#${viewId}`);
      }

      try {
        this.viewHtml = await this.loadView(viewId);
      } catch (e) {
        this.viewHtml = `<div class="empty-state"><span class="empty-state-icon">\u26A0</span><span class="empty-state-text">${escHtml(e.message)}</span></div>`;
      } finally {
        this.viewLoading = false;
        // Load CheeseItems custom icons after view HTML is injected
        this.$nextTick(() => _ciLoadIcons());
      }
    },

    get currentNavItem() {
      const baseView = this.currentView.includes(':') ? this.currentView.split(':')[0] : this.currentView;
      for (const section of this.navSections) {
        const found = section.items.find(i => i.id === baseView);
        if (found) return found;
      }
      return null;
    },

    getGuideModule() {
      const base = this.currentView.includes(':') ? this.currentView.split(':')[0] : this.currentView;
      return GUIDE_DEFS[base] ? base : null;
    },

    openHeaderGuide() {
      const moduleId = this.getGuideModule();
      if (!moduleId) return;
      const el = document.getElementById('header-guide-content');
      if (el) el.innerHTML = buildGuideTab(moduleId);
      document.getElementById('header-guide-modal')?.showModal();
    },

    // ── View loading ────────────────────────────────────────────────────────
    async loadView(viewId) {
      // Parse detail routes: "zones:test_zone" → base="zones", detailId="test_zone"
      const colonIdx = viewId.indexOf(':');
      const base = colonIdx > 0 ? viewId.substring(0, colonIdx) : viewId;
      const detailId = colonIdx > 0 ? viewId.substring(colonIdx + 1) : null;

      // Detail routes
      if (detailId) {
        switch (base) {
          case 'zones':       return await buildZoneDetail(this, detailId);
          case 'groups':      return await buildGroupDetail(this, detailId);
          case 'dimensions':
            if (detailId === 'new') return await buildDimensionCreate(this);
            return await buildDimensionDetail(this, detailId);
          case 'events':
            if (detailId === 'templates') return await buildEventTemplates(this);
            return await buildEventDetail(this, detailId);
          case 'market':      return await buildMarketDetail(this, detailId);
          case 'eggs':        return await buildEggDetail(this, detailId);
          case 'chunks':      return await buildChunkSetDetail(this, detailId);
          case 'scores':
            if (detailId === 'config') return await buildScoreConfig(this);
            if (detailId === 'minigames') return await buildMiniGames(this);
            if (detailId.startsWith('mg:')) return await buildMiniGameDetail(this, detailId.substring(3));
            return await buildScoreDetail(this, detailId);
          case 'timeline':    return await buildTimeline(this, detailId);
          case 'dungeons':    return await buildDungeonDetail(this, detailId);
          default:
            return `<div class="empty-state"><span class="empty-state-icon">\u{1F6A7}</span><span class="empty-state-text">${t('error.unknownView', {viewId: escHtml(viewId)})}</span></div>`;
        }
      }

      // List routes
      switch (base) {
        case 'overview':    return await buildOverview(this);
        case 'zones':       return await buildZones(this);
        case 'groups':      return await buildZones(this); // Groups merged into zones view
        case 'dimensions':  return await buildDimensions(this);
        case 'events':      return await buildEvents(this);
        case 'players':     return await buildPlayers(this);
        case 'prison':      return await buildPrison(this);
        case 'chunks':      return await buildChunks(this);
        case 'server':      return await buildServer(this);
        case 'market':      return await buildMarket(this);
        case 'eggs':        return await buildEggs(this);
        case 'scores':      return await buildScores(this);
        case 'shift':       return await buildShift(this);
        case 'dungeons':    return await buildDungeons(this);
        case 'items':       return await buildItems(this);
        case 'morphos':     return await buildMorphos(this);
        case 'kart':        return await buildKart(this);
        case 'patrol':      return await buildPatrol(this);
        case 'store':       return await buildStore(this);
        case 'raids':       return await buildRaids(this);
        case 'playcosmetics': return await buildPlayCosmetics(this);
        case 'cobblecap':   return await buildCobblecap(this);
        case 'music':       return await buildMusic(this);
        case 'factionwar':  return await buildFactionWar(this);
        case 'wizard':      return await buildWizard(this);
        case 'webhooks':    return await buildWebhooks(this);
        case 'accounts':    return await buildAccounts(this);
        case 'audit':       return await buildAudit(this);
        default:
          return `<div class="empty-state"><span class="empty-state-icon">\u{1F6A7}</span><span class="empty-state-text">${t('common.error')}</span></div>`;
      }
    },

    // ── Status ──────────────────────────────────────────────────────────────
    async refreshStatus() {
      this.refreshing = true;
      try {
        const s = await API.status();
        this.serverStatus = s;
        this.serverOnline = !!s.online;
      } catch (e) {
        this.serverOnline = false;
      } finally {
        this.refreshing = false;
      }
    },

    // ── Toast system (with dedup + close button) ────────────────────────────
    toast(message, type = 'info', duration = 0) {
      const icons = { success: '✓', error: '✗', warn: '⚠', info: 'ℹ' };
      // Default durations by type
      if (!duration) duration = type === 'error' ? 5000 : type === 'warn' ? 4000 : 3500;
      // Session expiry: show one clear message, stop polling, go to login
      if (type === 'error' && message && message.includes('reconnect')) {
        if (!this._sessionExpired) {
          this._sessionExpired = true;
          this.authenticated = false;
          if (this._statusInterval) { clearInterval(this._statusInterval); this._statusInterval = null; }
          if (this._wsReconnectTimer) { clearTimeout(this._wsReconnectTimer); this._wsReconnectTimer = null; }
          if (this.ws) { try { this.ws.close(); } catch(e){} this.ws = null; }
          this.toasts = [];
          message = t('error.sessionExpired');
          type = 'warn';
          duration = 8000;
          // Fall through to show as a normal toast (no reload loop)
        } else {
          return; // Already showing session expiry toast
        }
      }
      // Dedup: if same message shown <2s ago, skip
      const now = Date.now();
      if (message === this._lastToastMsg && now - this._lastToastTime < 2000) return;
      this._lastToastMsg = message;
      this._lastToastTime = now;
      // Cap visible toasts at 4
      while (this.toasts.length >= 4) this.toasts.shift();

      const id = ++this._toastCounter;
      const toast = { id, message, type, icon: icons[type] || 'ℹ', visible: true };
      this.toasts.push(toast);
      this._scheduleToastDismiss(id, duration);
    },
    _scheduleToastDismiss(id, duration) {
      setTimeout(() => {
        const t = this.toasts.find(t => t.id === id);
        if (t) t.visible = false;
        setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id); }, 200);
      }, duration);
    },
    dismissToast(id) {
      const t = this.toasts.find(t => t.id === id);
      if (t) t.visible = false;
      setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id); }, 200);
    },

    toastSuccess: (msg) => cgApp().toast(msg, 'success'),
    toastError:   (msg) => cgApp().toast(msg, 'error'),

    // ── Admin Player Modal ──────────────────────────────────────────────────
    openAdminPlayerModal() {
      const modal = document.getElementById('admin-player-modal');
      const inp = document.getElementById('admin-player-input');
      const suggestions = document.getElementById('admin-player-suggestions');
      const saveBtn = document.getElementById('admin-player-save');
      if (!modal || !inp) return;
      inp.value = this.adminPlayer || '';
      suggestions.innerHTML = '<div class="text-sm text-muted text-center py-2">' + t('common.loading') + '</div>';
      modal.showModal();
      inp.focus();
      // Load player suggestions
      const loadSuggestions = async (filter) => {
        try {
          const online = await API.players.list();
          const offline = await API.players.offline(20);
          const all = [];
          (online || []).forEach(p => all.push({ name: p.name, online: true }));
          (offline || []).forEach(p => { if (!all.find(a => a.name === p.name)) all.push({ name: p.name, online: false }); });
          const filtered = filter ? all.filter(p => p.name.toLowerCase().includes(filter.toLowerCase())) : all;
          if (filtered.length === 0) {
            suggestions.innerHTML = '<div class="text-sm text-muted text-center py-2">' + t('common.noResults') + '</div>';
          } else {
            suggestions.innerHTML = filtered.map(p => `
              <button class="w-full text-left px-3 py-1.5 rounded text-sm font-mono hover-elevated transition-all flex items-center gap-2"
                onclick="document.getElementById('admin-player-input').value='${escHtml(p.name)}'">
                <span class="status-dot ${p.online ? 'status-online' : 'status-idle'}"></span>
                <span class="text-tx">${escHtml(p.name)}</span>
                ${p.online ? '<span class="text-xs text-green ml-auto">' + t('common.online') + '</span>' : ''}
              </button>
            `).join('');
          }
        } catch (e) {
          suggestions.innerHTML = '<div class="text-sm text-muted text-center py-2">' + t('common.playerSearch') + '</div>';
        }
      };
      loadSuggestions('');
      inp.oninput = debounce(() => loadSuggestions(inp.value), 300);
      saveBtn.onclick = async () => {
        const name = inp.value.trim();
        if (!name) return;
        try {
          await API.admin.setPlayer(name);
          this.adminPlayer = name;
          localStorage.setItem('cg_admin_player', name);
          modal.close();
          this.toast(t('header.adminPlayerTitle') + ': ' + name, 'success');
        } catch (e) {
          this.toast(e.message, 'error');
        }
      };
      inp.onkeydown = (e) => { if (e.key === 'Enter') saveBtn.click(); };
    },

    // ── Logout ──────────────────────────────────────────────────────────────
    logout() {
      API.logout(); // destroys session server-side + clears local state
      this.authenticated = false;
      this.sessionUsername = '';
      this.sessionRole = '';
      this.viewHtml = '';
      this.currentView = 'overview';
      // Close WebSocket
      if (this._wsReconnectTimer) clearTimeout(this._wsReconnectTimer);
      if (this.ws) { try { this.ws.close(); } catch(e){} this.ws = null; }
      this._wsConnected = false;
      if (this._statusInterval) clearInterval(this._statusInterval);
    },

    // ── Theme ─────────────────────────────────────────────────────────────────
    toggleTheme() {
      this.currentTheme = this.currentTheme === 'cg-dark' ? 'cg-light' : 'cg-dark';
      document.documentElement.setAttribute('data-theme', this.currentTheme);
      localStorage.setItem('cg-theme', this.currentTheme);
    },
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// PLAYER PICKER — reusable modal for selecting a player name
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Opens a modal to select a player from online/offline lists.
 * @param {object} opts - { title?: string, onSelect: (name) => void }
 */
async function openPlayerPicker(opts = {}) {
  const modal = document.getElementById('player-picker-modal');
  const titleEl = document.getElementById('player-picker-title');
  const search = document.getElementById('player-picker-search');
  const list = document.getElementById('player-picker-list');
  if (!modal || !search || !list) return;
  titleEl.textContent = opts.title || t('common.selectPlayer');
  search.value = '';
  list.innerHTML = '<div class="text-sm text-center py-4" style="color:var(--text-muted)">' + t('common.loading') + '</div>';
  modal.showModal();
  search.focus();

  // Load recent picks from localStorage
  let recentPicks = [];
  try { recentPicks = JSON.parse(localStorage.getItem('cg_recent_players') || '[]'); } catch(e) {}

  let allPlayers = [];
  try {
    const online = await API.players.list();
    const offline = await API.players.offline(30);
    (online || []).forEach(p => allPlayers.push({ name: p.name, online: true }));
    (offline || []).forEach(p => { if (!allPlayers.find(a => a.name === p.name)) allPlayers.push({ name: p.name, online: false }); });
  } catch (e) {
    list.innerHTML = '<div class="text-sm text-center py-4" style="color:var(--text-muted)">' + t('common.error') + '</div>';
    return;
  }

  function rememberPick(name) {
    let recent = recentPicks.filter(n => n !== name);
    recent.unshift(name);
    if (recent.length > 5) recent = recent.slice(0, 5);
    localStorage.setItem('cg_recent_players', JSON.stringify(recent));
  }

  function renderList(filter) {
    const filtered = filter ? allPlayers.filter(p => p.name.toLowerCase().includes(filter.toLowerCase())) : allPlayers;
    if (filtered.length === 0 && !filter) {
      list.innerHTML = '<div class="text-sm text-center py-4" style="color:var(--text-muted)">' + t('common.noResults') + '</div>';
    } else if (filtered.length === 0 && filter) {
      list.innerHTML = `<button class="w-full text-left px-3 py-2 rounded text-sm font-mono hover-elevated transition-all" style="color:var(--amber)"
           data-pick="${escHtml(filter)}">${t('common.useValue', {value: escHtml(filter)})}</button>`;
    } else {
      let html = '';
      // Show recent picks section (when no filter active)
      if (!filter && recentPicks.length > 0) {
        html += '<div class="text-sm font-mono px-2 py-1 mt-1" style="color:var(--amber)">' + t('common.recentPicks') + '</div>';
        recentPicks.forEach(name => {
          const p = allPlayers.find(a => a.name === name);
          const isOnline = p ? p.online : false;
          html += `<button class="w-full text-left px-3 py-1.5 rounded text-sm font-mono hover-elevated transition-all flex items-center gap-2" data-pick="${escHtml(name)}">
            <span class="status-dot ${isOnline ? 'status-online' : 'status-idle'}"></span><span style="color:var(${isOnline ? '--text-primary' : '--muted-light'})">${escHtml(name)}</span>
          </button>`;
        });
        html += '<hr style="border-color:var(--bg-border);margin:0.375rem 0">';
      }
      const onlinePlayers = filtered.filter(p => p.online);
      const offlinePlayers = filtered.filter(p => !p.online);
      if (onlinePlayers.length > 0) {
        html += '<div class="text-sm font-mono px-2 py-1 mt-1" style="color:var(--green)">' + t('common.online') + '</div>';
        onlinePlayers.forEach(p => {
          html += `<button class="w-full text-left px-3 py-1.5 rounded text-sm font-mono hover-elevated transition-all flex items-center gap-2" data-pick="${escHtml(p.name)}">
            <span class="status-dot status-online"></span><span style="color:var(--text-primary)">${escHtml(p.name)}</span>
          </button>`;
        });
      }
      if (offlinePlayers.length > 0) {
        html += '<div class="text-sm font-mono px-2 py-1 mt-1" style="color:var(--text-muted)">' + t('common.offline') + '</div>';
        offlinePlayers.forEach(p => {
          html += `<button class="w-full text-left px-3 py-1.5 rounded text-sm font-mono hover-elevated transition-all flex items-center gap-2" data-pick="${escHtml(p.name)}">
            <span class="status-dot status-idle"></span><span style="color:var(--muted-light)">${escHtml(p.name)}</span>
          </button>`;
        });
      }
      if (filter && !filtered.find(p => p.name.toLowerCase() === filter.toLowerCase())) {
        html += `<button class="w-full text-left px-3 py-1.5 rounded text-sm font-mono hover-elevated transition-all mt-1" style="color:var(--amber)" data-pick="${escHtml(filter)}">
          ${t('common.useValue', {value: escHtml(filter)})}
        </button>`;
      }
      list.innerHTML = html;
    }
    list.querySelectorAll('[data-pick]').forEach(btn => {
      btn.onclick = () => {
        const name = btn.getAttribute('data-pick');
        rememberPick(name);
        modal.close();
        if (opts.onSelect) opts.onSelect(name);
      };
    });
  }

  renderList('');
  search.oninput = debounce(() => renderList(search.value), 200);
  search.onkeydown = (e) => {
    if (e.key === 'Enter' && search.value.trim()) {
      rememberPick(search.value.trim());
      modal.close();
      if (opts.onSelect) opts.onSelect(search.value.trim());
    }
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// VIEW BUILDERS — each returns an HTML string rendered into the main content area
// ═══════════════════════════════════════════════════════════════════════════════

// ── Shared: skeleton loader ────────────────────────────────────────────────────
function skeletonCard(rows = 4) {
  return `
    <div class="section-card">
      <div class="section-card-body flex flex-col gap-3">
        ${Array(rows).fill('<div class="skeleton-line" style="width:' + (60 + Math.random()*30).toFixed(0) + '%"></div>').join('')}
      </div>
    </div>`;
}

function skeletonPage() {
  return `<div class="max-w-6xl mx-auto fade-in flex flex-col gap-4">
    <div class="flex gap-4" style="flex-wrap:wrap">
      <div class="stat-card flex-1" style="min-width:160px"><div class="skeleton-line" style="width:40%;height:1.5rem"></div><div class="skeleton-line" style="width:70%;margin-top:0.5rem"></div></div>
      <div class="stat-card flex-1" style="min-width:160px"><div class="skeleton-line" style="width:50%;height:1.5rem"></div><div class="skeleton-line" style="width:60%;margin-top:0.5rem"></div></div>
      <div class="stat-card flex-1" style="min-width:160px"><div class="skeleton-line" style="width:35%;height:1.5rem"></div><div class="skeleton-line" style="width:80%;margin-top:0.5rem"></div></div>
    </div>
    ${skeletonCard(6)}
  </div>`;
}


// ═══════════════════════════════════════════════════════════════════════════════
// SHARED HELPERS — used across multiple views
// ═══════════════════════════════════════════════════════════════════════════════


// ── Flag metadata (matches Flag.java enum) ──────────────────────────────────
const FLAG_META = [
  { id: 'block-break',     name: 'flags.block_break',     icon: '\u{1FA93}', cat: 'flags.cat.protection' },
  { id: 'block-place',     name: 'flags.block_place',     icon: '\u{1F9F1}', cat: 'flags.cat.protection' },
  { id: 'interact',        name: 'flags.interact',        icon: '\u{1F6AA}', cat: 'flags.cat.protection' },
  { id: 'entity-damage',   name: 'flags.entity_damage',   icon: '\u2694',     cat: 'flags.cat.protection' },
  { id: 'pvp',             name: 'flags.pvp',             icon: '\u{1F5E1}', cat: 'flags.cat.protection' },
  { id: 'use-items',       name: 'flags.use_items',       icon: '\u{1FAA3}', cat: 'flags.cat.protection' },
  { id: 'explosions',      name: 'flags.explosions',      icon: '\u{1F4A3}', cat: 'flags.cat.protection' },
  { id: 'item-drop',       name: 'flags.item_drop',       icon: '\u{1F4E6}', cat: 'flags.cat.protection' },
  { id: 'item-pickup',     name: 'flags.item_pickup',     icon: '\u{1F90F}', cat: 'flags.cat.protection' },
  { id: 'ender-pearl',     name: 'flags.ender_pearl',     icon: '\u{1F52E}', cat: 'flags.cat.protection' },
  { id: 'entry',           name: 'flags.entry',           icon: '\u{1F6B6}', cat: 'flags.cat.protection' },
  { id: 'exit',            name: 'flags.exit',            icon: '\u{1F3C3}', cat: 'flags.cat.protection' },
  { id: 'fire-spread',     name: 'flags.fire_spread',     icon: '\u{1F525}', cat: 'flags.cat.environment' },
  { id: 'mob-spawning',    name: 'flags.mob_spawning',    icon: '\u{1F47E}', cat: 'flags.cat.environment' },
  { id: 'mob-grief',       name: 'flags.mob_grief',       icon: '\u{1F41B}', cat: 'flags.cat.environment' },
  { id: 'trample',         name: 'flags.trample',         icon: '\u{1F33E}', cat: 'flags.cat.environment' },
  { id: 'fly',             name: 'flags.fly',             icon: '\u{1FABD}', cat: 'flags.cat.environment' },
  { id: 'cobblemon-fly',   name: 'flags.cobblemon_fly',   icon: '\u{1F409}', cat: 'flags.cat.cobblemon' },
  { id: 'cobblemon-mount', name: 'flags.cobblemon_mount', icon: '\u{1F40E}', cat: 'flags.cat.cobblemon' },
  { id: 'cobblemon-summon',name: 'flags.cobblemon_summon', icon: '\u{1F4E4}', cat: 'flags.cat.cobblemon' },
  { id: 'cobbleshift-transform', name: 'flags.cobbleshift_transform', icon: '\u{1F98A}', cat: 'flags.cat.cobblemon' },
  { id: 'fall-damage',     name: 'flags.fall_damage',     icon: '\u{1F480}', cat: 'flags.cat.environment' },
  { id: 'hunger',          name: 'flags.hunger',          icon: '\u{1F356}', cat: 'flags.cat.environment' },
  { id: 'leaf-decay',      name: 'flags.leaf_decay',      icon: '\u{1F342}', cat: 'flags.cat.environment' },
  { id: 'ice-melt',        name: 'flags.ice_melt',        icon: '\u{1F9CA}', cat: 'flags.cat.environment' },
  { id: 'snow-form',       name: 'flags.snow_form',       icon: '\u2744',     cat: 'flags.cat.environment' },
  { id: 'vine-grow',       name: 'flags.vine_grow',       icon: '\u{1F33F}', cat: 'flags.cat.environment' },
  { id: 'redstone',        name: 'flags.redstone',        icon: '\u26A1',     cat: 'flags.cat.environment' },
  { id: 'nametag-hide',    name: 'flags.nametag_hide',    icon: '\u{1F441}', cat: 'flags.cat.environment' },
  { id: 'coral-death',     name: 'flags.coral_death',     icon: '\u{1FAB8}', cat: 'flags.cat.environment' },
];

// ── Shared view helpers ─────────────────────────────────────────────────────────

function worldBadgeHtml(worldId) {
  const name = fmtWorld(worldId);
  let cls = 'text-muted';
  if (worldId.includes('overworld')) cls = 'text-green';
  else if (worldId.includes('nether')) cls = 'text-red';
  else if (worldId.includes('end')) cls = 'text-purple-400';
  else cls = 'text-amber';
  return '<span class="id-badge ' + cls + '">' + escHtml(name) + '</span>';
}

function escHtml(s) {
  if (s == null || s === '') return '';
  if (typeof s !== 'string') s = String(s);
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

/**
 * Copie robuste vers le presse-papiers, fonctionnelle en HTTP simple.
 *
 * `navigator.clipboard` n'est disponible QUE dans un contexte sécurisé
 * (https ou localhost). Le dashboard est servi en http sur une IP/port LAN
 * (44311) → `navigator.clipboard` est absent et tous les boutons 📋 échouent
 * silencieusement. On retombe alors sur un `<textarea>` masqué +
 * `document.execCommand('copy')`, qui marche en http car l'appel se fait
 * dans le gesture utilisateur (onclick).
 *
 * Retourne une Promise (resolve = copié, reject = échec) pour rester
 * compatible avec les chaînes `.then(onOk, onErr)` / `.catch()` existantes.
 *
 * @param {string} text
 * @returns {Promise<void>}
 */
function cgCopy(text) {
  text = text == null ? '' : String(text);
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(text).catch(() => cgCopyFallback(text));
  }
  return cgCopyFallback(text);
}

function cgCopyFallback(text) {
  return new Promise((resolve, reject) => {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-9999px';
    ta.style.left = '-9999px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    // Sauvegarde de la sélection courante pour la restaurer après coup
    const sel = document.getSelection();
    const savedRange = sel && sel.rangeCount > 0 ? sel.getRangeAt(0) : null;
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    if (savedRange && sel) { sel.removeAllRanges(); sel.addRange(savedRange); }
    ok ? resolve() : reject(new Error('copy failed'));
  });
}

/**
 * Read an image File and return an optimized base64 data URL ready to upload.
 *
 * The image is decoded in a Canvas, downscaled to fit `maxDim` while preserving
 * aspect ratio, then re-encoded as PNG (preserves transparency for sprites and
 * matches the backend's stored .png extension). The downscale alone trims ~95%
 * off a typical phone photo before any base64 round-trip happens.
 *
 * Animated GIFs are returned untouched so the animation survives.
 *
 * @param {File} file
 * @param {Object} [opts] - { maxDim?: number, quality?: number }
 *   maxDim defaults to 800 (square fit). quality applies only when the source
 *   is JPEG and we keep that encoding (range 0.5–1.0).
 * @returns {Promise<string>} base64 data URL
 */
function optimizeImageFile(file, opts) {
  const o = opts || {};
  const maxDim = Math.max(64, o.maxDim || 1280);
  const quality = Math.min(1, Math.max(0.5, o.quality || 0.92));

  if (!file) return Promise.reject(new Error('no file'));
  // Animated GIFs would lose their animation through canvas — pass through.
  if (file.type === 'image/gif') return _readAsDataURL(file);

  return _readAsDataURL(file).then((dataUrl) => new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const w0 = img.naturalWidth, h0 = img.naturalHeight;
      if (!w0 || !h0) { resolve(dataUrl); return; }

      let w = w0, h = h0;
      if (w0 > maxDim || h0 > maxDim) {
        const ratio = Math.min(maxDim / w0, maxDim / h0);
        w = Math.round(w0 * ratio);
        h = Math.round(h0 * ratio);
      }

      const canvas = document.createElement('canvas');
      canvas.width = w; canvas.height = h;
      const ctx = canvas.getContext('2d');
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, 0, 0, w, h);

      // Keep JPEG when the source was JPEG (much smaller for opaque photos);
      // otherwise emit PNG so alpha is preserved.
      const fmt = (file.type === 'image/jpeg') ? 'image/jpeg' : 'image/png';
      let out = canvas.toDataURL(fmt, fmt === 'image/jpeg' ? quality : undefined);
      // Sanity guard: never blow up to *more* than the original.
      if (out.length > dataUrl.length) out = dataUrl;
      resolve(out);
    };
    img.onerror = () => reject(new Error('image decode failed'));
    img.src = dataUrl;
  }));
}

function _readAsDataURL(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = () => reject(new Error('read failed'));
    r.readAsDataURL(file);
  });
}

/** Global datalists — injected once after auth, available for all views */
let _globalDLReady = false;
async function _initGlobalDataLists() {
  if (_globalDLReady) return;
  _globalDLReady = true;
  // Species + Items from constants (always available)
  let html = '<datalist id="global-species-dl">' + COBBLEMON_SPECIES.map(s => '<option value="' + s + '">').join('') + '</datalist>';
  html += '<datalist id="global-items-dl">' + MK_COMMON_ITEMS.map(i => '<option value="' + i + '">').join('') + '</datalist>';
  // Dimensions from API (with hardcoded MC defaults as fallback)
  const defaultDims = ['minecraft:overworld','minecraft:the_nether','minecraft:the_end'];
  let dimIds = defaultDims;
  try {
    const dims = await API.dimensions.list();
    dimIds = dims.map(d => d.id);
    if (!dimIds.includes('minecraft:overworld')) dimIds.unshift(...defaultDims);
  } catch (e) { /* use defaults */ }
  html += '<datalist id="global-dims-dl">' + dimIds.map(d => '<option value="' + escHtml(d) + '">').join('') + '</datalist>';
  // Zones datalist
  let zoneOpts = '';
  try {
    const zones = await API.zones.list();
    zoneOpts = zones.map(z => '<option value="' + escHtml(z.id) + '" label="' + escHtml(z.name) + '">').join('');
  } catch (e) { /* no zones */ }
  html += '<datalist id="global-zones-dl">' + zoneOpts + '</datalist>';
  const container = document.createElement('div');
  container.style.display = 'none';
  container.innerHTML = html;
  document.body.appendChild(container);
}

/**
 * Global species picker — input with sprite preview + autocomplete datalist.
 * Used everywhere a Cobblemon species name is entered (dungeons, shift, timeline).
 * @param {string} value - Current species name
 * @param {object} opts - Options: id, placeholder, datalist, width, fullWidth, inputClass, disabled, oninput
 */
function speciesPickerHtml(value, opts) {
  opts = opts || {};
  const w = opts.width || 180;
  const dl = opts.datalist || 'global-species-dl';
  const idAttr = opts.id ? ` id="${opts.id}"` : '';
  const phAttr = opts.placeholder ? ` placeholder="${escHtml(opts.placeholder)}"` : '';
  const cls = opts.inputClass || 'input input-sm input-bordered text-sm';
  const disabled = opts.disabled ? ' disabled' : '';
  const extraOninput = opts.oninput ? opts.oninput + ';' : '';
  const onkeydownAttr = opts.onkeydown ? ` onkeydown="${opts.onkeydown}"` : '';
  const low = (value || '').toLowerCase().replace(/\s+/g, '');
  const inputStyle = opts.fullWidth ? 'flex:1;min-width:0' : `width:${w - 30}px`;
  return `<div class="flex items-center gap-1" style="min-width:${w}px">
    <img src="https://img.pokemondb.net/sprites/home/normal/${encodeURIComponent(low)}.png" width="24" height="24"
      style="image-rendering:pixelated;flex-shrink:0" onerror="this.style.visibility='hidden'" onload="this.style.visibility='visible'">
    <input type="text"${idAttr} list="${dl}" value="${escHtml(value || '')}" class="${cls}" style="${inputStyle}"${phAttr}${disabled}${onkeydownAttr}
      oninput="${extraOninput}var _i=this.parentElement.querySelector('img');if(_i){_i.src='https://img.pokemondb.net/sprites/home/normal/'+encodeURIComponent(this.value.toLowerCase().replace(/\\s+/g,''))+'.png';_i.style.visibility='visible'}">
  </div>`;
}

/** Global navigation — works from inline onclick handlers in x-html views */
function _nav(viewId) {
  const el = document.querySelector('[x-data]');
  if (el && el._x_dataStack) el._x_dataStack[0].navigate(viewId);
}
function _toast(msg, type) {
  const el = document.querySelector('[x-data]');
  if (el && el._x_dataStack) el._x_dataStack[0].toast(msg, type || 'info');
}

/** Generic confirm dialog — returns a Promise<boolean> */
function confirmAction(message) {
  return new Promise(resolve => {
    let dlg = document.getElementById('generic-confirm-dlg');
    if (!dlg) {
      dlg = document.createElement('dialog');
      dlg.id = 'generic-confirm-dlg';
      dlg.className = 'modal';
      dlg.innerHTML = '<div class="modal-box max-w-sm">'
        + '<p id="gcf-msg" class="text-sm text-tx mb-4"></p>'
        + '<div class="flex justify-end gap-2">'
        + '<button id="gcf-cancel" class="btn-cg btn-cg-ghost">' + t('common.cancel') + '</button>'
        + '<button id="gcf-ok" class="btn-cg btn-cg-danger">' + t('common.confirm') + '</button>'
        + '</div></div>'
        + '<form method="dialog" class="modal-backdrop"><button>close</button></form>';
      document.body.appendChild(dlg);
    }
    document.getElementById('gcf-msg').textContent = message;
    const ok = document.getElementById('gcf-ok');
    const cancel = document.getElementById('gcf-cancel');
    const cleanup = (val) => { dlg.close(); resolve(val); };
    ok.onclick = () => cleanup(true);
    cancel.onclick = () => cleanup(false);
    dlg.onclose = () => resolve(false);
    dlg.showModal();
  });
}

/** Teleport the admin player to a position or dimension */
async function _tpAdmin(opts) {
  const el = document.querySelector('[x-data]');
  const app = el?._x_dataStack?.[0];
  const name = app?.adminPlayer;
  if (!name) { app?.toast(t('header.adminPlayerTitle'), 'error'); return; }
  try {
    await API.players.tp(name, opts);
    const where = opts.dimension ? opts.dimension : (opts.x != null ? `${Math.round(opts.x)}, ${Math.round(opts.y)}, ${Math.round(opts.z)}` : '?');
    app.toast(t('common.tp') + ' ' + where, 'success');
  } catch (e) { app?.toast(e.message || t('common.error'), 'error'); }
}

function flagBtnCls(state, active) {
  if (!active) return 'btn-cg btn-cg-ghost';
  if (state === 'ALLOW') return 'btn-cg btn-cg-success';
  if (state === 'DENY') return 'btn-cg btn-cg-danger';
  return 'btn-cg btn-cg-primary';
}

function buildFlagsGrid(flags, onClickPrefix) {
  const cats = {};
  FLAG_META.forEach(f => {
    if (!cats[f.cat]) cats[f.cat] = [];
    const state = flags[f.id] || 'INHERIT';
    cats[f.cat].push({ ...f, state });
  });
  let html = '<div style="max-width:28rem">';
  for (const [cat, items] of Object.entries(cats)) {
    html += '<div class="text-sm font-mono text-muted tracking-wider uppercase mt-3 mb-1.5">' + escHtml(t(cat)) + '</div>';
    items.forEach(f => {
      html += '<div class="flex items-center gap-2 py-0.5" id="flag-' + onClickPrefix + '-' + f.id + '">';
      html += '<span class="w-5 text-center shrink-0">' + f.icon + '</span>';
      html += '<span class="text-sm text-tx-dim flex-1 truncate">' + escHtml(t(f.name)) + '</span>';
      html += '<div class="flex gap-1 shrink-0">';
      ['ALLOW','DENY','INHERIT'].forEach(s => {
        const active = f.state === s;
        const label = s === 'ALLOW' ? t('flags.allow') : s === 'DENY' ? t('flags.deny') : t('flags.inherit');
        html += '<button class="' + flagBtnCls(s, active) + '" style="width:2.5rem;height:1.5rem;padding:0;font-size:0.75rem;justify-content:center" ';
        html += 'data-state="' + s + '" ';
        html += 'onclick="' + onClickPrefix + 'Flag(\'' + f.id + '\',\'' + s + '\')">' + label + '</button>';
      });
      html += '</div></div>';
    });
  }
  html += '</div>';
  return html;
}

function updateFlagBtnVisuals(prefix, flagId, newState) {
  const row = document.getElementById('flag-' + prefix + '-' + flagId);
  if (!row) return;
  row.querySelectorAll('button[data-state]').forEach(btn => {
    const s = btn.dataset.state;
    btn.className = flagBtnCls(s, s === newState);
  });
}

// ── Shared: detail page back button ──────────────────────────────────────────
function detailBackBtn(viewId, label) {
  return `<button class="btn-cg btn-cg-ghost btn-cg-sm mb-3" onclick="_nav('${escHtml(viewId)}')">← ${escHtml(label)}</button>`;
}

// ── Shared: detail page tab bar ─────────────────────────────────────────────
function detailTabBar(tabs, activeId, onClickFn) {
  return `<div class="detail-tabs">${tabs.map(t =>
    `<button class="detail-tab ${t.id === activeId ? 'active' : ''}" onclick="${onClickFn}('${t.id}',this)">${escHtml(t.label)}</button>`
  ).join('')}</div>`;
}

// ── Shared: switch detail tabs via DOM ──────────────────────────────────────
function switchDetailTab(tabId, btn, prefix) {
  // Hide all tab panels
  document.querySelectorAll('[data-tab-panel-' + prefix + ']').forEach(el => el.style.display = 'none');
  // Show target
  const target = document.querySelector('[data-tab-panel-' + prefix + '="' + tabId + '"]');
  if (target) target.style.display = '';
  // Update button states
  if (btn) {
    btn.closest('.detail-tabs').querySelectorAll('.detail-tab').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
  }
}

const MK_COMMON_ITEMS = [
  'cheesestore:fromage',
  'minecraft:diamond','minecraft:emerald','minecraft:gold_ingot','minecraft:iron_ingot','minecraft:netherite_ingot',
  'minecraft:copper_ingot','minecraft:lapis_lazuli','minecraft:redstone','minecraft:coal','minecraft:amethyst_shard',
  'minecraft:diamond_sword','minecraft:diamond_pickaxe','minecraft:diamond_axe','minecraft:diamond_shovel','minecraft:diamond_helmet',
  'minecraft:diamond_chestplate','minecraft:diamond_leggings','minecraft:diamond_boots','minecraft:netherite_sword','minecraft:netherite_pickaxe',
  'minecraft:golden_apple','minecraft:enchanted_golden_apple','minecraft:apple','minecraft:bread','minecraft:cooked_beef',
  'minecraft:cooked_porkchop','minecraft:cake','minecraft:cookie','minecraft:pumpkin_pie',
  'minecraft:ender_pearl','minecraft:blaze_rod','minecraft:ghast_tear','minecraft:nether_star','minecraft:experience_bottle',
  'minecraft:totem_of_undying','minecraft:elytra','minecraft:trident','minecraft:shulker_box','minecraft:name_tag',
  'minecraft:oak_log','minecraft:stone','minecraft:cobblestone','minecraft:glass','minecraft:obsidian',
  'minecraft:bookshelf','minecraft:torch','minecraft:glowstone','minecraft:sea_lantern','minecraft:beacon',
  'minecraft:arrow','minecraft:bow','minecraft:crossbow','minecraft:shield','minecraft:fishing_rod',
  'minecraft:saddle','minecraft:lead','minecraft:bone','minecraft:string','minecraft:slime_ball',
];

const COBBLEMON_SPECIES = [
  // Gen 1
  'bulbasaur','ivysaur','venusaur','charmander','charmeleon','charizard','squirtle','wartortle','blastoise',
  'pikachu','raichu','nidoking','nidoqueen','clefable','ninetales','arcanine','machamp','alakazam','gengar',
  'onix','steelix','gyarados','lapras','eevee','vaporeon','jolteon','flareon','espeon','umbreon','leafeon',
  'glaceon','sylveon','snorlax','dragonite','mewtwo','mew',
  // Gen 2
  'typhlosion','feraligatr','meganium','ampharos','scizor','heracross','houndoom','tyranitar','lugia','ho-oh',
  'celebi','togekiss','umbreon','espeon',
  // Gen 3
  'blaziken','swampert','sceptile','gardevoir','aggron','flygon','milotic','absol','salamence','metagross',
  'rayquaza','groudon','kyogre','latios','latias','jirachi','deoxys',
  // Gen 4
  'torterra','infernape','empoleon','luxray','roserade','garchomp','lucario','hippowdon','weavile',
  'magnezone','rhyperior','electivire','magmortar','togekiss','gallade','darkrai','arceus','dialga','palkia',
  'giratina','cresselia','heatran',
  // Gen 5
  'serperior','emboar','samurott','excadrill','conkeldurr','zoroark','reuniclus','chandelure','haxorus',
  'hydreigon','volcarona','reshiram','zekrom','kyurem','genesect',
  // Gen 6
  'greninja','talonflame','aegislash','goodra','noivern','xerneas','yveltal','zygarde',
  // Gen 7
  'decidueye','incineroar','primarina','mimikyu','kommo-o','solgaleo','lunala','necrozma','toxapex',
  // Gen 8
  'cinderace','rillaboom','inteleon','corviknight','dragapult','zacian','zamazenta','eternatus','urshifu',
  // Gen 9
  'meowscarada','skeledirge','quaquaval','palafin','baxcalibur','gholdengo','miraidon','koraidon',
  // Popular / Dungeon-worthy
  'tyrantrum','aurorus','bastiodon','rampardos','aerodactyl','kabutops','armaldo','cradily',
  'ditto','porygon-z','rotom','spiritomb','dusknoir','froslass','gliscor','tangrowth',
];

function mkItemIconUrl(itemId) {
  if (!itemId) return '';
  if (itemId === 'cheesestore:fromage') return '/store/cheese-icon.png';
  const clean = itemId.replace('minecraft:', '').replace(/[^a-z0-9_]/g, '');
  // Convert snake_case to Title_Case for Invicon_ pattern: diamond_sword → Diamond_Sword
  const title = clean.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join('_');
  return `https://minecraft.wiki/images/Invicon_${title}.png`;
}

function mkItemIconFallback(img, itemId) {
  const clean = (itemId || '').replace('minecraft:', '').replace(/[^a-z0-9_]/g, '');
  const kebab = clean.replace(/_/g, '-');
  const src = img.src || '';
  if (src.includes('Invicon_')) {
    img.src = `https://minecraft.wiki/images/ItemSprite_${kebab}.png`;
  } else if (src.includes('ItemSprite_')) {
    img.src = `https://minecraft.wiki/images/BlockSprite_${kebab}.png`;
  } else {
    img.style.display = 'none';
  }
}

function mkItemImg(itemId, size) {
  if (!itemId) return '';
  // CheeseItems custom icons: use data-ci-icon (loaded via _ciLoadIcons with auth)
  if (itemId.startsWith('cheeseitems:')) {
    const ciId = itemId.replace('cheeseitems:', '');
    return `<img data-ci-icon="${escHtml(ciId)}" width="${size || 24}" height="${size || 24}" style="image-rendering:pixelated;vertical-align:middle;border-radius:2px">`;
  }
  const clean = itemId.replace('minecraft:', '').replace(/[^a-z0-9_]/g, '');
  const url = mkItemIconUrl(itemId);
  return `<img src="${url}" width="${size || 24}" height="${size || 24}" alt="${escHtml(clean)}" style="image-rendering:pixelated;vertical-align:middle" onerror="mkItemIconFallback(this,'${escHtml(clean)}')">`;
}

// ── CheeseItems icon cache & loader (shared across views) ─────────────
const _ciIconCache = {};
async function _ciLoadIcons(root) {
  const imgs = (root || document).querySelectorAll('img[data-ci-icon]');
  for (const img of imgs) {
    const id = img.dataset.ciIcon;
    if (!id) continue;
    if (_ciIconCache[id]) { img.src = _ciIconCache[id]; continue; }
    try {
      const url = await API.items.getImage(id);
      _ciIconCache[id] = url;
      img.src = url;
    } catch(e) { /* icon load failed, leave blank */ }
  }
}
function _ciInvalidateIconCache(id) {
  if (_ciIconCache[id]) { URL.revokeObjectURL(_ciIconCache[id]); delete _ciIconCache[id]; }
}

// ── Shared Egg/Market Detail helpers ─────────────────────────────────────────

function egdEnumSelect(id, enumList, current) {
  const opts = (enumList || []).map(e => `<option value="${e.id}" ${e.id === current ? 'selected' : ''}>${escHtml(e.displayName || e.id)}</option>`).join('');
  return `<select id="${id}" class="select select-sm select-bordered w-full text-xs">${opts}</select>`;
}

function egdRenderDiscoveries(discs) {
  const el = document.getElementById('egd-disc-body');
  if (!el) return;
  const addHtml = `
    <div class="flex gap-2 mb-3">
      <input id="egd-disc-name" class="input input-sm input-bordered flex-1" placeholder="${t('common.playerName')}" autocomplete="off">
      <button class="btn-cg btn-cg-primary" onclick="_egd.addDiscovery()">${t('common.add')}</button>
      <button class="btn-cg btn-cg-danger" onclick="_egd.clearDiscoveries()">${t('common.clearAll')}</button>
    </div>`;
  if (!discs || !discs.length) {
    el.innerHTML = addHtml + '<div class="text-center py-4 text-sm" style="color:var(--text-muted)">' + t('common.noResults') + '</div>';
    return;
  }
  el.innerHTML = addHtml + `
    <table class="cg-table text-xs">
      <thead><tr><th>${t('common.player')}</th><th>${t('common.uuid')}</th><th></th></tr></thead>
      <tbody>${discs.map(d => `
        <tr>
          <td>${escHtml(d.name)}</td>
          <td class="font-mono" style="color:var(--text-muted)">${escHtml(d.uuid.substring(0, 8))}</td>
          <td class="text-right"><button class="text-sm hover:underline" style="color:var(--red)" onclick="_egd.removeDiscovery('${d.uuid}')">&#x00d7;</button></td>
        </tr>
      `).join('')}</tbody>
    </table>`;
}
