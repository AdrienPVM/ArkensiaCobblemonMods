/**
 * ArkensiaAdmin — API Client
 * Centralized HTTP client with auth, error handling, and request helpers.
 * No dependencies — vanilla JS only.
 *
 * Phase 5: Session-based auth with 24h expiry.
 *  - login() creates a session from a token
 *  - All subsequent requests use the sessionId
 *  - Session info (username, role) stored locally
 */

const API = (() => {
  const BASE = '/api/v1';

  // ── Error code → i18n key mapping ─────────────────────────────────────
  const ERROR_KEYS = {
    'NETWORK_ERROR':    'error.network',
    'UNAUTHORIZED':     'error.sessionExpired',
    'FORBIDDEN':        'error.forbidden',
    'RATE_LIMITED':     'error.rateLimited',
    'NOT_AUTHENTICATED':'error.notAuthenticated',
  };
  function translateError(msg) {
    if (!msg) return t('error.unknown');
    if (ERROR_KEYS[msg]) return t(ERROR_KEYS[msg]);
    // Backend errors: "Zone not found: xxx" → keep as-is (already descriptive)
    return msg;
  }

  // ── Token / Session management ─────────────────────────────────────────
  let _sessionId = null;
  let _username = null;
  let _role = null;

  function setSession(sessionId, username, role, persist = false) {
    _sessionId = sessionId;
    _username = username;
    _role = role;
    const store = persist ? localStorage : sessionStorage;
    store.setItem('cg_session', sessionId);
    store.setItem('cg_username', username);
    store.setItem('cg_role', role);
  }

  function getSessionId() {
    return _sessionId
      || sessionStorage.getItem('cg_session')
      || localStorage.getItem('cg_session')
      // Backwards compat: check for legacy raw token
      || sessionStorage.getItem('cg_token')
      || localStorage.getItem('cg_token')
      || null;
  }

  function getUsername() {
    return _username
      || sessionStorage.getItem('cg_username')
      || localStorage.getItem('cg_username')
      || 'admin';
  }

  function getRole() {
    return _role
      || sessionStorage.getItem('cg_role')
      || localStorage.getItem('cg_role')
      || 'SUPER_ADMIN';
  }

  function clearSession() {
    _sessionId = null;
    _username = null;
    _role = null;
    sessionStorage.removeItem('cg_session');
    sessionStorage.removeItem('cg_username');
    sessionStorage.removeItem('cg_role');
    localStorage.removeItem('cg_session');
    localStorage.removeItem('cg_username');
    localStorage.removeItem('cg_role');
    // Also clear legacy token (but NOT cg_remember_token — it survives session expiry)
    sessionStorage.removeItem('cg_token');
    localStorage.removeItem('cg_token');
  }

  function hasSession() {
    return !!getSessionId();
  }

  // Backwards compat aliases
  function setToken(token, persist = false) {
    // Legacy — store as session for backwards compat with raw token auth
    const store = persist ? localStorage : sessionStorage;
    store.setItem('cg_session', token);
  }
  function getToken() { return getSessionId(); }
  function clearToken() { clearSession(); }
  function hasToken() { return hasSession(); }

  // ── Role helpers ─────────────────────────────────────────────────────────
  function canWrite() {
    const r = getRole();
    return r === 'OPERATOR' || r === 'SUPER_ADMIN';
  }

  function canAdmin() {
    return getRole() === 'SUPER_ADMIN';
  }

  function isReadOnly() {
    return getRole() === 'READ_ONLY';
  }

  // ── Core request ─────────────────────────────────────────────────────────
  async function request(method, path, body = null) {
    const sid = getSessionId();
    if (!sid) throw new Error('NOT_AUTHENTICATED');

    const opts = {
      method,
      headers: {
        'Authorization': `Bearer ${sid}`,
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    };

    if (body !== null && method !== 'GET') {
      opts.body = JSON.stringify(body);
    }

    let res;
    try {
      res = await fetch(BASE + path, opts);
    } catch (e) {
      const err = new Error(translateError('NETWORK_ERROR'));
      err.code = 'NETWORK_ERROR';
      throw err;
    }

    if (res.status === 401) {
      clearSession();
      const err = new Error(translateError('UNAUTHORIZED'));
      err.code = 'UNAUTHORIZED';
      throw err;
    }

    if (res.status === 403) {
      const err = new Error(translateError('FORBIDDEN'));
      err.code = 'FORBIDDEN';
      throw err;
    }

    if (res.status === 429) {
      const err = new Error(translateError('RATE_LIMITED'));
      err.code = 'RATE_LIMITED';
      throw err;
    }

    let data;
    const ct = res.headers.get('content-type') || '';
    if (ct.includes('application/json')) {
      data = await res.json();
    } else {
      data = await res.text();
    }

    if (!res.ok) {
      const msg = (typeof data === 'object' && data.error) ? data.error : `HTTP ${res.status}`;
      throw new Error(translateError(msg));
    }

    return data;
  }

  // ── Shorthand methods ────────────────────────────────────────────────────
  const get    = (path)        => request('GET',    path);
  const post   = (path, body)  => request('POST',   path, body || {});
  const put    = (path, body)  => request('PUT',    path, body || {});
  const del    = (path)        => request('DELETE', path);
  const patch  = (path, body)  => request('PATCH',  path, body || {});

  // ── Auth ─────────────────────────────────────────────────────────────────

  /**
   * Login with a token. Returns session info {sessionId, username, role, expiresIn}.
   */
  async function login(token) {
    const res = await fetch(BASE + '/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    });
    if (res.status === 401) throw new Error(t('error.invalidToken'));
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    return await res.json();
  }

  /**
   * Verify a stored session is still valid.
   */
  async function verifySession() {
    const sid = getSessionId();
    if (!sid) return null;
    try {
      const session = await request('GET', '/auth/session');
      // Update local state from server
      _username = session.username;
      _role = session.role;
      return session;
    } catch (e) {
      return null;
    }
  }

  /**
   * Legacy: verify raw token by calling ping.
   */
  async function verify(token) {
    const prev = _sessionId;
    _sessionId = token;
    try {
      await get('/ping');
      return true;
    } catch (e) {
      _sessionId = prev;
      throw e;
    }
  }

  /**
   * Logout: destroy session server-side and clear local state.
   */
  async function logout() {
    try {
      await post('/auth/logout');
    } catch (e) { /* ignore */ }
    localStorage.removeItem('cg_remember_token');
    clearSession();
  }

  // ── Endpoint helpers ─────────────────────────────────────────────────────
  const CG = '/cheeseguard';

  const endpoints = {
    // Status (CheeseDash core)
    ping:     ()           => get('/ping'),
    status:   ()           => get('/status'),

    // Zones
    zones: {
      list:         (params)        => get(`${CG}/zones` + (params?.world ? `?world=${encodeURIComponent(params.world)}` : '')),
      get:          (id)            => get(`${CG}/zones/${id}`),
      create:       (data)          => post(`${CG}/zones`, data),
      update:       (id, data)      => put(`${CG}/zones/${id}`, data),
      delete:       (id)            => del(`${CG}/zones/${id}`),
      members:      (id)            => get(`${CG}/zones/${id}/members`),
      addMember:    (id, data)      => post(`${CG}/zones/${id}/members`, data),
      removeMember: (id, uuid)      => del(`${CG}/zones/${id}/members/${uuid}`),
      transferOwner:(id, data)      => put(`${CG}/zones/${id}/owner`, data),
      removeOwner:  (id)            => del(`${CG}/zones/${id}/owner`),
      replaceSelection: (id, data)  => post(`${CG}/zones/${id}/replace-selection`, data || {}),
      // Tags
      tags:             ()              => get(`${CG}/zones/tags`),
      tagConfigs:       ()              => get(`${CG}/zones/tag-configs`),
      tagConfigGet:     (tagId)         => get(`${CG}/zones/tag-configs/${tagId}`),
      tagConfigPut:     (tagId, data)   => put(`${CG}/zones/tag-configs/${tagId}`, data),
      tagConfigDelete:  (tagId)         => del(`${CG}/zones/tag-configs/${tagId}`),
      // Sale
      setSale:          (id, config)    => put(`${CG}/zones/${id}/sale`, config),
      removeSale:       (id)            => del(`${CG}/zones/${id}/sale`),
      spawnHologram:    (id)            => post(`${CG}/zones/${id}/sale/hologram`),
      removeHologram:   (id)            => del(`${CG}/zones/${id}/sale/hologram`),
      giveSign:         (id, playerName)=> post(`${CG}/zones/${id}/sale/sign?player=${encodeURIComponent(playerName)}`),
      relistSale:       (id)            => post(`${CG}/zones/${id}/sale/relist`),
      // States
      states:           (id)            => get(`${CG}/zones/${id}/states`),
      stateCapture:     (id, data)      => post(`${CG}/zones/${id}/states`, data),
      stateRename:      (id, stateId, data) => put(`${CG}/zones/${id}/states/${stateId}`, data),
      stateDelete:      (id, stateId)   => del(`${CG}/zones/${id}/states/${stateId}`),
      stateApply:       (id, stateId, data) => post(`${CG}/zones/${id}/states/${stateId}/apply`, data),
      stateCancel:      (id)            => post(`${CG}/zones/${id}/states/cancel`),
    },

    // Groups
    groups: {
      list:         ()              => get(`${CG}/groups`),
      get:          (id)            => get(`${CG}/groups/${id}`),
      create:       (data)          => post(`${CG}/groups`, data),
      update:       (id, data)      => put(`${CG}/groups/${id}`, data),
      delete:       (id)            => del(`${CG}/groups/${id}`),
      zones:        (id)            => get(`${CG}/groups/${id}/zones`),
      assignZone:   (id, zoneId)    => post(`${CG}/groups/${id}/zones/${zoneId}`),
      unassignZone: (id, zoneId)    => del(`${CG}/groups/${id}/zones/${zoneId}`),
    },

    // Dimensions
    dimensions: {
      list:           ()              => get(`${CG}/dimensions`),
      genTypes:       ()              => get(`${CG}/dimensions/gen-types`),
      get:            (id)            => get(`${CG}/dimensions/${id}`),
      create:         (data)          => post(`${CG}/dimensions`, data),
      update:         (id, data)      => put(`${CG}/dimensions/${id}`, data),
      delete:         (id)            => del(`${CG}/dimensions/${id}`),
      snapshot:       (id)            => post(`${CG}/dimensions/${id}/snapshot`),
      reset:          (id)            => post(`${CG}/dimensions/${id}/reset`),
      toggle:         (id, key)       => post(`${CG}/dimensions/${id}/toggle`, { key }),
      snapshotConfig: (id, data)      => put(`${CG}/dimensions/${id}/snapshot/config`, data),
      playerDims:     (id)            => get(`${CG}/dimensions/${id}/players`),
      deletePlayer:   (id, uuid8)     => del(`${CG}/dimensions/${id}/players/${uuid8}`),
      resetPlayer:    (id, uuid8)     => post(`${CG}/dimensions/${id}/players/${uuid8}/reset`),
      addWhitelist:   (id, data)      => post(`${CG}/dimensions/${id}/whitelist`, data),
      removeWhitelist:(id, uuid)      => del(`${CG}/dimensions/${id}/whitelist/${uuid}`),
      addBuilder:     (id, data)      => post(`${CG}/dimensions/${id}/builders`, data),
      removeBuilder:  (id, uuid)      => del(`${CG}/dimensions/${id}/builders/${uuid}`),
    },

    // Events
    events: {
      list:              ()              => get(`${CG}/events`),
      get:               (id)            => get(`${CG}/events/${id}`),
      create:            (data)          => post(`${CG}/events`, data),
      update:            (id, data)      => put(`${CG}/events/${id}`, data),
      delete:            (id)            => del(`${CG}/events/${id}`),
      start:             (id)            => post(`${CG}/events/${id}/start`),
      stop:              (id)            => post(`${CG}/events/${id}/stop`),
      announce:          (id)            => post(`${CG}/events/${id}/announce`),
      participants:      (id)            => get(`${CG}/events/${id}/participants`),
      addParticipant:    (id, data)      => post(`${CG}/events/${id}/participants`, data),
      removeParticipant: (id, uuid)      => del(`${CG}/events/${id}/participants/${uuid}`),
      score:             (id, data)      => post(`${CG}/events/${id}/score`, data),
      saveTemplate:      (id, data)      => post(`${CG}/events/${id}/save-template`, data),
      templates:         ()              => get(`${CG}/events/templates`),
      loadTemplate:      (name)          => post(`${CG}/events/templates/${encodeURIComponent(name)}/load`),
      deleteTemplate:    (name)          => del(`${CG}/events/templates/${encodeURIComponent(name)}`),
      updateActs:        (id, acts)      => put(`${CG}/events/${id}`, { acts, useActSystem: true }),
      minigameTypes:     ()              => get(`${CG}/events/minigame-types`),
      // Launch config
      getLaunchConfig:    ()              => get(`${CG}/events/launch-config`),
      updateLaunchConfig: (data)          => put(`${CG}/events/launch-config`, data),
      // PropHunt icons
      prophuntIcons:     ()                    => get(`${CG}/events/prophunt/icons`),
      prophuntUploadIcon: (key, b64)          => post(`${CG}/events/prophunt/icons/${encodeURIComponent(key)}`, { imageBase64: b64 }),
      prophuntDeleteIcon: (key)               => del(`${CG}/events/prophunt/icons/${encodeURIComponent(key)}`),
      prophuntGetIcon: async (key) => {
          const sid = getSessionId();
          if (!sid) throw new Error('NOT_AUTHENTICATED');
          const res = await fetch(`${BASE}${CG}/events/prophunt/icons/${encodeURIComponent(key)}`, {
              headers: { 'Authorization': `Bearer ${sid}` }
          });
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          const blob = await res.blob();
          return URL.createObjectURL(blob);
      },
    },

    // Players
    players: {
      list:     ()                => get(`${CG}/players`),
      offline:  (n)               => get(`${CG}/players/offline` + (n ? `?n=${n}` : '')),
      get:      (name)            => get(`${CG}/players/${encodeURIComponent(name)}`),
      kick:     (name, msg)       => post(`${CG}/players/${encodeURIComponent(name)}/kick`, { message: msg }),
      tp:       (name, data)      => post(`${CG}/players/${encodeURIComponent(name)}/tp`, data),
      gamemode: (name, mode)      => post(`${CG}/players/${encodeURIComponent(name)}/gamemode`, { mode }),
      message:  (name, text, type)=> post(`${CG}/players/${encodeURIComponent(name)}/message`, { text, type: type || 'chat' }),
    },

    // Prison
    prison: {
      all:        ()              => get(`${CG}/prison`),
      active:     ()              => get(`${CG}/prison/active`),
      history:    ()              => get(`${CG}/prison/history`),
      config:     ()              => get(`${CG}/prison/config`),
      saveConfig: (data)          => put(`${CG}/prison/config`, data),
      imprison:   (data)          => post(`${CG}/prison`, data),
      release:    (uuid)          => del(`${CG}/prison/${encodeURIComponent(uuid)}`),
      update:     (uuid, data)    => put(`${CG}/prison/${encodeURIComponent(uuid)}`, data),
    },

    // Chunks
    chunks: {
      sets:            ()         => get(`${CG}/chunks/sets`),
      getSet:          (name)     => get(`${CG}/chunks/sets/${encodeURIComponent(name)}`),
      createSet:       (data)     => post(`${CG}/chunks/sets`, data),
      deleteSet:       (name)     => del(`${CG}/chunks/sets/${encodeURIComponent(name)}`),
      removeEntry:     (name, x, z, radius) => request('DELETE', `${CG}/chunks/sets/${encodeURIComponent(name)}/entry`, { x, z, radius }),
      resetSet:        (name)     => post(`${CG}/chunks/sets/${encodeURIComponent(name)}/reset`),
      setSchedule:     (name, data) => request('PUT', `${CG}/chunks/sets/${encodeURIComponent(name)}/schedule`, data),
      suggestions:     ()         => get(`${CG}/chunks/suggestions`),
      dismiss:         (id)       => del(`${CG}/chunks/suggestions/${id}`),
      acceptSuggestion:(id, setName, radius) => post(`${CG}/chunks/suggestions/${id}/accept`, { setName, radius: radius || 1 }),
    },

    // Races (track scanning & config)
    races: {
      scan:       (data)     => post(`${CG}/races/scan`, data),
      heightmap:  (data)     => post(`${CG}/races/heightmap`, data),
      getTrack:   (eventId)  => get(`${CG}/races/${encodeURIComponent(eventId)}/track`),
      putTrack:   (eventId, data) => put(`${CG}/races/${encodeURIComponent(eventId)}/track`, data),
      deleteTrack:(eventId)  => del(`${CG}/races/${encodeURIComponent(eventId)}/track`),
    },

    // Server (CheeseDash core — no CG prefix)
    server: {
      status:  ()           => get('/status'),
      stats:   ()           => get('/server/stats'),
      command: (cmd)        => post('/server/command', { command: cmd }),
      logs:    (n)          => get('/server/logs' + (n ? `?n=${n}` : '')),
      mods:    ()           => get('/server/mods'),
      regenerateToken: ()   => post('/dashboard/token/regenerate', { confirm: true }),
    },

    // Admin player identity (CheeseDash core)
    admin: {
      getPlayer:   ()       => get('/dashboard/admin-player'),
      setPlayer:   (name)   => put('/dashboard/admin-player', { name }),
      getPosition: ()       => get('/dashboard/admin-player/position'),

      // Multi-admin account management (SUPER_ADMIN only)
      accounts:     ()                  => get('/admin/accounts'),
      createAccount:(username, role)    => post('/admin/accounts', { username, role }),
      updateAccount:(username, role)    => put(`/admin/accounts/${encodeURIComponent(username)}`, { role }),
      deleteAccount:(username)          => del(`/admin/accounts/${encodeURIComponent(username)}`),
      regenAccountToken: (username)     => post(`/admin/accounts/${encodeURIComponent(username)}/regenerate`),

      // Session management (SUPER_ADMIN only)
      sessions:     ()                  => get('/admin/sessions'),
      revokeSession:(id)                => del(`/admin/sessions/${encodeURIComponent(id)}`),

      // Audit log
      audit:        (n, offset)         => get(`/admin/audit?n=${n || 100}&offset=${offset || 0}`),
    },

    // Zone Folders (visual organization)
    zoneFolders: {
      list:   ()     => get('/dashboard/zone-folders'),
      save:   (data) => put('/dashboard/zone-folders', data),
      create: (data) => post('/dashboard/zone-folders', data),
      delete: (id)   => del(`/dashboard/zone-folders/${encodeURIComponent(id)}`),
    },

    // Auth
    auth: {
      login:    (token) => login(token),
      logout:   ()      => logout(),
      session:  ()      => get('/auth/session'),
    },

    // CheeseMarket
    market: {
      shops:          ()                         => get('/market/shops'),
      getShop:        (id)                       => get(`/market/shops/${encodeURIComponent(id)}`),
      createShop:     (data)                     => post('/market/shops', data),
      updateShop:     (id, data)                 => put(`/market/shops/${encodeURIComponent(id)}`, data),
      deleteShop:     (id)                       => del(`/market/shops/${encodeURIComponent(id)}`),
      createSet:      (shopId, data)             => post(`/market/shops/${encodeURIComponent(shopId)}/sets`, data),
      updateSet:      (shopId, setId, data)      => put(`/market/shops/${encodeURIComponent(shopId)}/sets/${encodeURIComponent(setId)}`, data),
      deleteSet:      (shopId, setId)            => del(`/market/shops/${encodeURIComponent(shopId)}/sets/${encodeURIComponent(setId)}`),
      createItem:     (shopId, setId, data)      => post(`/market/shops/${encodeURIComponent(shopId)}/sets/${encodeURIComponent(setId)}/items`, data),
      updateItem:     (shopId, setId, itemId, d) => put(`/market/shops/${encodeURIComponent(shopId)}/sets/${encodeURIComponent(setId)}/items/${encodeURIComponent(itemId)}`, d),
      deleteItem:     (shopId, setId, itemId)    => del(`/market/shops/${encodeURIComponent(shopId)}/sets/${encodeURIComponent(setId)}/items/${encodeURIComponent(itemId)}`),
      toggleItem:     (shopId, setId, itemId)    => post(`/market/shops/${encodeURIComponent(shopId)}/sets/${encodeURIComponent(setId)}/items/${encodeURIComponent(itemId)}/toggle`),
      transactions:   (params)                   => get('/market/transactions' + buildQuery(params)),
      stats:          ()                         => get('/market/stats'),
      // Layout
      getLayout:      (shopId)                   => get(`/market/shops/${encodeURIComponent(shopId)}/layout`),
      saveLayout:     (shopId, layout)           => put(`/market/shops/${encodeURIComponent(shopId)}/layout`, layout),
      // Assets
      listAssets:     (shopId)                   => get(`/market/shops/${encodeURIComponent(shopId)}/assets`),
      uploadAsset:    (shopId, name, base64)     => post(`/market/shops/${encodeURIComponent(shopId)}/assets`, { name, data: base64 }),
      deleteAsset:    (shopId, name)             => del(`/market/shops/${encodeURIComponent(shopId)}/assets/${encodeURIComponent(name)}`),
      assetUrl:       (shopId, name)             => `${BASE}/market/assets/${encodeURIComponent(shopId)}/${encodeURIComponent(name)}`,
      // Templates
      listTemplates:  ()                         => get('/market/templates'),
      getTemplate:    (name)                     => get(`/market/templates/${encodeURIComponent(name)}`),
      saveTemplate:   (name, layout)             => post('/market/templates', { name, layout }),
      deleteTemplate: (name)                     => del(`/market/templates/${encodeURIComponent(name)}`),
    },

    // EggAndCheese
    eggs: {
      list:            ()                => get('/eggs/collectibles'),
      get:             (id)              => get(`/eggs/collectibles/${encodeURIComponent(id)}`),
      create:          (data)            => post('/eggs/collectibles', data),
      update:          (id, data)        => put(`/eggs/collectibles/${encodeURIComponent(id)}`, data),
      delete:          (id)              => del(`/eggs/collectibles/${encodeURIComponent(id)}`),
      rename:          (id, newId)       => post(`/eggs/collectibles/${encodeURIComponent(id)}/rename`, { newId }),
      randomizeDna:    (id, seed)        => post(`/eggs/collectibles/${encodeURIComponent(id)}/randomize-dna`, seed ? { seed } : {}),
      discoveries:     (id)              => get(`/eggs/collectibles/${encodeURIComponent(id)}/discoveries`),
      addDiscovery:    (id, playerName)  => post(`/eggs/collectibles/${encodeURIComponent(id)}/discoveries`, { playerName }),
      clearDiscoveries:(id)              => del(`/eggs/collectibles/${encodeURIComponent(id)}/discoveries`),
      removeDiscovery: (id, uuid)        => del(`/eggs/collectibles/${encodeURIComponent(id)}/discoveries/${encodeURIComponent(uuid)}`),
      giveAdmin:       (id, playerName) => post(`/eggs/collectibles/${encodeURIComponent(id)}/give-admin`, { playerName }),
      giveDeco:        (id, playerName) => post(`/eggs/collectibles/${encodeURIComponent(id)}/give-deco`, { playerName }),
      config:          ()                => get('/eggs/config'),
      updateConfig:    (data)            => put('/eggs/config', data),
      syncFtb:         ()                => post('/eggs/config/sync-ftb'),
      enums:           ()                => get('/eggs/enums'),
      stats:           ()                => get('/eggs/stats'),
      integrity:       ()                => get('/eggs/integrity'),
      reconcile:       ()                => post('/eggs/integrity/reconcile'),
    },

    // CobbleCheeseScore
    scores: {
      scoreboards:     ()                => get('/scores/scoreboards'),
      getScoreboard:   (id)              => get(`/scores/scoreboards/${encodeURIComponent(id)}`),
      getImage:        async (id)        => {
        const sid = getSessionId();
        if (!sid) throw new Error('NOT_AUTHENTICATED');
        const res = await fetch(`${BASE}/scores/scoreboards/${encodeURIComponent(id)}/image`, {
          headers: { 'Authorization': `Bearer ${sid}` }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        return url;
      },
      revokeImage:     (url) => { if (url) URL.revokeObjectURL(url); },
      refresh:         ()                => post('/scores/refresh'),
      config:          ()                => get('/scores/config'),
      updateConfig:    (data)            => put('/scores/config', data),
      stats:           ()                => get('/scores/stats'),
      // Per-scoreboard edit
      updateScoreboard: (id, data)       => put(`/scores/scoreboards/${encodeURIComponent(id)}`, data),
      // Mini-games
      minigames:        ()               => get('/scores/minigames'),
      minigame:         (id)             => get(`/scores/minigames/${encodeURIComponent(id)}`),
      createMinigame:   (data)           => post('/scores/minigames', data),
      updateMinigame:   (id, data)       => put(`/scores/minigames/${encodeURIComponent(id)}`, data),
      deleteMinigame:   (id)             => del(`/scores/minigames/${encodeURIComponent(id)}`),
      addSession:       (id, data)       => post(`/scores/minigames/${encodeURIComponent(id)}/sessions`, data),
      deleteSession:    (id, idx)        => del(`/scores/minigames/${encodeURIComponent(id)}/sessions/${idx}`),
      globalRanking:    ()               => get('/scores/minigames/global'),
    },

    // CobblemonDungeons
    dungeons: {
      configs:       ()              => get('/dungeons/configs'),
      config:        (id)            => get(`/dungeons/configs/${encodeURIComponent(id)}`),
      createConfig:  (data)          => post('/dungeons/configs', data),
      updateConfig:  (id, data)      => put(`/dungeons/configs/${encodeURIComponent(id)}`, data),
      deleteConfig:  (id)            => del(`/dungeons/configs/${encodeURIComponent(id)}`),
      reload:        ()              => post('/dungeons/configs/reload'),
      tickets:       ()              => get('/dungeons/tickets'),
      updateTickets: (data)          => put('/dungeons/tickets', data),
      trainers:      ()              => get('/dungeons/trainers'),
      trainer:       (id)            => get(`/dungeons/trainers/${encodeURIComponent(id)}`),
      updateTrainer: (id, data)      => put(`/dungeons/trainers/${encodeURIComponent(id)}`, data),
      active:        ()              => get('/dungeons/active'),
      failRun:       (uuid)          => post('/dungeons/active/fail', { playerUuid: uuid }),
      leavePlayer:   (uuid)          => post('/dungeons/active/leave', { playerUuid: uuid }),
      stats:         ()              => get('/dungeons/stats'),
      history:       (n)             => get(`/dungeons/stats/history?n=${n || 50}`),
      mainConfig:    ()              => get('/dungeons/config'),
      leaderboard:   (id)            => get(`/dungeons/leaderboard${id ? '/'+enc(id) : ''}`),
    },

    // CobbleShift
    shift: {
      transforms:      ()                => get('/shift/transforms'),
      transform:       (data)            => post('/shift/transform', data),
      revert:          (data)            => post('/shift/revert', data),
      revertAll:       ()                => post('/shift/revertall'),
      abilities:       ()                => get('/shift/abilities'),
      ability:         (data)            => post('/shift/ability', data),
      abilityGlobal:   (data)            => post('/shift/ability/global', data),
      abilityToggle:   (data)            => post('/shift/ability/toggle', data),
      abilityDisabled: ()                => get('/shift/ability/disabled'),
      species:         ()                => get('/shift/species'),
      speciesInfo:     (name)            => get(`/shift/species/${encodeURIComponent(name)}`),
      config:          ()                => get('/shift/config'),
      updateConfig:    (data)            => put('/shift/config', data),
    },

    // Server Scheduling
    schedule: {
      get:             ()                => get('/server/schedule'),
      create:          (data)            => post('/server/schedule', data),
      cancelActive:    ()                => del('/server/schedule/active'),
      addRecurring:    (data)            => post('/server/schedule/recurring', data),
      updateRecurring: (id, data)        => put(`/server/schedule/recurring/${encodeURIComponent(id)}`, data),
      removeRecurring: (id)              => del(`/server/schedule/recurring/${encodeURIComponent(id)}`),
    },

    // Discord Webhooks
    webhooks: {
      list:        ()                    => get('/webhooks'),
      get:         (id)                  => get(`/webhooks/${encodeURIComponent(id)}`),
      create:      (data)               => post('/webhooks', data),
      update:      (id, data)           => put(`/webhooks/${encodeURIComponent(id)}`, data),
      remove:      (id)                 => del(`/webhooks/${encodeURIComponent(id)}`),
      test:        (id)                 => post(`/webhooks/${encodeURIComponent(id)}/test`),
      eventTypes:  ()                   => get('/webhooks/event-types'),
    },

    // CheeseItems
    items: {
      list:       ()             => get('/items'),
      get:        (id)           => get(`/items/${encodeURIComponent(id)}`),
      create:     (data)         => post('/items', data),
      update:     (id, data)     => put(`/items/${encodeURIComponent(id)}`, data),
      delete:     (id)           => del(`/items/${encodeURIComponent(id)}`),
      give:       (id, data)     => post(`/items/${encodeURIComponent(id)}/give`, data),
      reload:     ()             => post('/items/reload'),
      enums:      ()             => get('/items/enums'),
      uploadImage: (id, b64) => post(`/items/${encodeURIComponent(id)}/image`, { imageBase64: b64 }),
      deleteImage: (id)      => del(`/items/${encodeURIComponent(id)}/image`),
      getImage: async (id) => {
        const sid = getSessionId();
        if (!sid) throw new Error('NOT_AUTHENTICATED');
        const res = await fetch(`${BASE}/items/${encodeURIComponent(id)}/image`, {
          headers: { 'Authorization': `Bearer ${sid}` }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        return URL.createObjectURL(blob);
      },
    },

    // CheeseKart
    kart: {
      presets:     ()             => get('/kart/presets'),
      preset:      (id)           => get(`/kart/presets/${encodeURIComponent(id)}`),
      createPreset:(data)         => post('/kart/presets', data),
      updatePreset:(id, data)     => put(`/kart/presets/${encodeURIComponent(id)}`, data),
      deletePreset:(id)           => del(`/kart/presets/${encodeURIComponent(id)}`),
      active:      ()             => get('/kart/active'),
      start:       (data)         => post('/kart/start', data),
      stop:        (data)         => post('/kart/stop', data),
      stopAll:     ()             => post('/kart/stopall'),
    },

    // CheesePatrol
    patrol: {
      list: () => get('/patrol/list'),
      tp:   (data) => post('/patrol/tp', data),
    },

    // CheeseRaids — modular server raids (Hoopa invasion, ...)
    raids: {
      overview:    ()     => get('/raids/'),
      definitions: ()     => get('/raids/definitions'),
      instances:   ()     => get('/raids/instances'),
      start:       (raid) => post('/raids/start', { raid }),
      stop:        (raid) => post('/raids/stop', { raid }),
      reload:      ()     => post('/raids/reload'),
    },

    // FactionWar — Guerre de Factions (live war board + FULL admin control).
    //
    // Wrappers below mirror the entire /factionwar command tree (FactionWarCommands.java).
    // The mod exposes these through its REST dispatcher (FactionWarApi.java). Endpoints that a
    // given mod build may not yet implement simply 404 — every caller in view-factionwar.js
    // degrades gracefully (try/catch -> toast / "indisponible"), so a missing endpoint never
    // breaks the view. Paths use the same {faction,value,player,...} body convention as the
    // already-shipped endpoints.
    factionwar: {
      // ── Read ────────────────────────────────────────────────────────────────
      state:        ()                 => get('/factionwar/state'),
      config:       ()                 => get('/factionwar/config'),
      updateConfig: (data)             => put('/factionwar/config', data),
      roster:       ()                 => get('/factionwar/roster'),
      mapZones:     ()                 => get('/factionwar/map/zones'),
      preflight:    ()                 => get('/factionwar/preflight'),
      // Players known to the war (online names + all known names) for picker UIs.
      players:      ()                 => get('/factionwar/players'),

      // ── PvP window / freeze / finale ─────────────────────────────────────────
      pvpOn:        ()                 => post('/factionwar/pvp/on'),
      pvpOff:       ()                 => post('/factionwar/pvp/off'),
      freeze:       (on)               => post('/factionwar/freeze', { on: !!on }),

      // ── Phase / day ──────────────────────────────────────────────────────────
      phase:        ()                 => get('/factionwar/phase'),
      // action: "set" (needs day) | "next" | "finale" (on) | "auto" (on)
      phaseAction:  (action, opts)     => post('/factionwar/phase', Object.assign({ action }, opts || {})),

      // ── Scores & pools ───────────────────────────────────────────────────────
      setPool:      (faction, value)   => post('/factionwar/pool/set',    { faction, value }),
      poolRespawn:  (player)           => post('/factionwar/pool/respawn',{ player }),
      setPoints:    (faction, value)   => post('/factionwar/points/set',  { faction, value }),
      addPoints:    (faction, delta)   => post('/factionwar/points/add',  { faction, delta }),
      pointsRecap:  ()                 => post('/factionwar/points/recap'),
      pointsResolve:()                 => post('/factionwar/points/resolve'),

      // ── Roster / factions ────────────────────────────────────────────────────
      setFaction:   (player, faction)  => post('/factionwar/faction/set',     { player, faction }),
      setCaptain:   (player, faction)  => post('/factionwar/captain/set',     { player, faction }),
      setName:      (faction, name)    => post('/factionwar/faction/name',    { faction, name }),
      setColor:     (faction, color)   => post('/factionwar/faction/color',   { faction, color }),
      // spawn set <A|B> — uses the dashboard admin-player's CURRENT position on the server thread.
      setSpawn:     (faction)          => post('/factionwar/spawn/set',       { faction }),

      // ── Vote & draft ─────────────────────────────────────────────────────────
      voteStart:    (minutes)          => post('/factionwar/vote/start', minutes != null ? { minutes } : {}),
      voteClose:    ()                 => post('/factionwar/vote/close'),
      voteCast:     (voter, target)    => post('/factionwar/vote/cast',  { voter, target }),
      draftPick:    (captain, player)  => post('/factionwar/draft/pick', { captain, player }),

      // ── Objectives ───────────────────────────────────────────────────────────
      objComplete:  (id, faction)      => post('/factionwar/obj/complete', { id, faction }),
      objResetDaily:()                 => post('/factionwar/obj/resetdaily'),

      // ── Respec (player class change, run on their behalf) ─────────────────────
      respec:       (player, klass)    => post('/factionwar/respec', { player, class: klass }),

      // ── Capture zones ────────────────────────────────────────────────────────
      // zone add <id> <radius> <points> <seconds> — uses the admin-player's position for the centre.
      zoneAdd:      (data)             => post('/factionwar/zone/add', data),
      zoneRemove:   (id)               => post('/factionwar/zone/remove', { id }),
      zoneMajor:    (id, value)        => post('/factionwar/zone/major',  { id, value: !!value }),

      // ── Faction origins ──────────────────────────────────────────────────────
      origins:      ()                 => get('/factionwar/faction/origins'),
      setOrigin:    (faction, originId)=> post('/factionwar/faction/origin', { faction, originId }),
      // Captain picks/overrides their own faction's origin (by player, not faction).
      setOriginCaptain: (player, originId) => post('/factionwar/faction/origin/captain', { player, originId }),

      // ── Supply drop ──────────────────────────────────────────────────────────
      // Spawns a supply drop at the given coords (dimension optional, defaults server-side).
      supplyDrop:   ({ x, y, z, dimension }) => post('/factionwar/supplydrop', Object.assign({ x, y, z }, dimension ? { dimension } : {})),

      // ── Moderation ───────────────────────────────────────────────────────────
      // action: "mute" | "unmute" | "jail" | "free"
      mod:          (action, player)   => post('/factionwar/mod/' + encodeURIComponent(action), { player }),

      // ── Broadcast ────────────────────────────────────────────────────────────
      broadcast:        (kind, text, sound) => post('/factionwar/broadcast',         { kind, text, sound: sound || null }),
      broadcastFaction: (faction, kind, text, sound) => post('/factionwar/broadcast/faction', { faction, kind, text, sound: sound || null }),

      // ── Hard reset ───────────────────────────────────────────────────────────
      reset:        ()                 => post('/factionwar/reset'),
    },

    // CheeseStore
    store: {
      products:       ()             => get('/store/products'),
      createProduct:  (data)         => post('/store/products', data),
      updateProduct:  (id, data)     => put(`/store/products/${encodeURIComponent(id)}`, data),
      deleteProduct:  (id)           => del(`/store/products/${encodeURIComponent(id)}`),
      uploadImage:    (id, data)     => post(`/store/products/${encodeURIComponent(id)}/image`, data),
      orders:         ()             => get('/store/orders'),
      deliver:        (id)           => post(`/store/orders/${encodeURIComponent(id)}/deliver`),
      stats:          ()             => get('/store/stats'),
      config:         ()             => get('/store/config'),
      updateConfig:   (data)         => put('/store/config', data),
      wallets:        ()             => get('/store/wallets'),
      wallet:         (uuid)         => get(`/store/wallets/${encodeURIComponent(uuid)}`),
      updateWallet:   (uuid, data)   => put(`/store/wallets/${encodeURIComponent(uuid)}`, data),
      // Gallery (informational reward catalog)
      gallery:        ()             => get('/store/gallery'),
      createGallery:  (data)         => post('/store/gallery', data),
      updateGallery:  (id, data)     => put(`/store/gallery/${encodeURIComponent(id)}`, data),
      deleteGallery:  (id)           => del(`/store/gallery/${encodeURIComponent(id)}`),
      uploadGalleryImage: (id, data) => post(`/store/gallery/${encodeURIComponent(id)}/image`, data),
      reorderGallery: (ids)          => post('/store/gallery/reorder', { ids }),
      // Cosmetics (credit-purchasable, admin CRUD). Backend POST /store/cosmetics
      // is an upsert (create or update by id), so saveCosmetic covers both.
      cosmetics:          ()         => get('/store/cosmetics'),
      saveCosmetic:       (data)     => post('/store/cosmetics', data),
      updateCosmetic:     (id, data) => put(`/store/cosmetics/${encodeURIComponent(id)}`, data),
      deleteCosmetic:     (id)       => del(`/store/cosmetics/${encodeURIComponent(id)}`),
      uploadCosmeticImage:(id, data) => post(`/store/cosmetics/${encodeURIComponent(id)}/image`, data),

      // Playable cosmetics (namespace "playcosmetics" — distinct from the legacy
      // informational "cosmetics" above). Admin CRUD + reorder + grant + enums.
      // Storefront/public buy endpoint is served separately and not needed here.
      playcosmetics: {
        list:    ()              => get('/store/playcosmetics'),
        create:  (data)          => post('/store/playcosmetics', data),
        update:  (id, data)      => put(`/store/playcosmetics/${encodeURIComponent(id)}`, data),
        remove:  (id)            => del(`/store/playcosmetics/${encodeURIComponent(id)}`),
        reorder: (ids)           => post('/store/playcosmetics/reorder', { ids }),
        grant:   (data)          => post('/store/playcosmetics/grant', data),
        enums:   ()              => get('/store/playcosmetics/enums'),
      },
    },
    // Morpho catalog (CheeseItems)
    morphos: {
      list:        ()             => get('/morphos'),
      get:         (id)           => get(`/morphos/${encodeURIComponent(id)}`),
      create:      (data)         => post('/morphos', data),
      update:      (id, data)     => put(`/morphos/${encodeURIComponent(id)}`, data),
      delete:      (id)           => del(`/morphos/${encodeURIComponent(id)}`),
      uploadImage: (id, b64)      => post(`/morphos/${encodeURIComponent(id)}/image`, { imageBase64: b64 }),
      deleteImage: (id)           => del(`/morphos/${encodeURIComponent(id)}/image`),
      reorder:     (ids)          => post('/morphos/reorder', { ids }),
      grant:       (data)         => post('/morphos/grant', data),
      global:      ()             => get('/morphos/global'),
      saveGlobal:  (data)         => put('/morphos/global', data),
    },
    // CobbleCap
    cobblecap: {
      players:    ()           => get('/cobblecap/players'),
      getPlayer:  (uuid)       => get(`/cobblecap/players/${encodeURIComponent(uuid)}`),
      setCap:     (uuid, data) => post(`/cobblecap/players/${encodeURIComponent(uuid)}/cap`, data),
      champions:  ()           => get('/cobblecap/champions'),
      saveChamps: (data)       => put('/cobblecap/champions', data),
      config:     ()           => get('/cobblecap/config'),
      saveConfig: (data)       => put('/cobblecap/config', data),
      testFireworks: ()        => post('/cobblecap/fireworks/test'),
      stats:      ()           => get('/cobblecap/stats'),
      toggleExpLock: (uuid)   => post(`/cobblecap/players/${encodeURIComponent(uuid)}/explock`),
    },
    // CheeseMusic
    music: {
      assignments:      ()            => get('/music/assignments'),
      createAssignment: (data)        => post('/music/assignments', data),
      updateAssignment: (id, data)    => put(`/music/assignments/${encodeURIComponent(id)}`, data),
      deleteAssignment: (id)          => del(`/music/assignments/${encodeURIComponent(id)}`),
      tracks:           ()            => get('/music/tracks'),
      uploadTrack:      (file)        => {
        const url = `${BASE}/music/tracks/upload?name=${encodeURIComponent(file.name)}`;
        return fetch(url, { method:'POST', headers: {'Authorization': `Bearer ${getSessionId()}`}, body: file })
          .then(r => { if (!r.ok) return r.json().then(j => { throw new Error(j.error || r.statusText); }); return r.json(); });
      },
      deleteTrack:      (name)        => del(`/music/tracks/${encodeURIComponent(name)}`),
      zones:            ()            => get('/music/zones'),
      config:           ()            => get('/music/config'),
      updateConfig:     (data)        => put('/music/config', data),
      status:           ()            => get('/music/status'),
    },
  };

  // ── Public API ─────────────────────────────────────────────────────────
  return {
    setToken,
    getToken,
    clearToken,
    hasToken,
    verify,
    // New session API
    setSession,
    getSessionId,
    getUsername,
    getRole,
    clearSession,
    hasSession,
    canWrite,
    canAdmin,
    isReadOnly,
    login,
    logout,
    verifySession,
    // HTTP methods
    get,
    post,
    put,
    delete: del,
    patch,
    ...endpoints,
  };
})();

// ── Utility: build query string from params object ──────────────────────────
function buildQuery(params) {
  if (!params) return '';
  const parts = Object.entries(params).filter(([,v]) => v != null && v !== '').map(([k,v]) => `${k}=${encodeURIComponent(v)}`);
  return parts.length ? '?' + parts.join('&') : '';
}

// ── Utility: format MC world ID ─────────────────────────────────────────────
function fmtWorld(worldId) {
  if (!worldId) return '—';
  if (worldId.includes('overworld')) return 'Overworld';
  if (worldId.includes('nether'))    return 'Nether';
  if (worldId.includes('end'))       return 'End';
  const parts = worldId.split(':');
  return parts[1] || worldId;
}

// ── Utility: format bytes ────────────────────────────────────────────────────
function fmtSize(kb) {
  if (!kb) return '—';
  if (kb >= 1024) return (kb / 1024).toFixed(1) + ' MB';
  return kb + ' KB';
}

// ── Utility: format date ─────────────────────────────────────────────────────
function fmtDate(ms) {
  if (!ms) return '—';
  const locale = I18N.lang === 'fr' ? 'fr-FR' : 'en-US';
  return new Date(ms).toLocaleString(locale, {
    day: '2-digit', month: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

// ── Utility: format uptime ───────────────────────────────────────────────────
function fmtUptime(seconds) {
  if (!seconds || seconds < 0) return '—';
  return I18N.fmtUptime(seconds * 1000);
}

// ── Utility: debounce ────────────────────────────────────────────────────────
function debounce(fn, ms = 300) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

// ── Global: click-to-copy on .id-badge ──────────────────────────────────────
document.addEventListener('click', (e) => {
  const badge = e.target.closest('.id-badge');
  if (!badge) return;
  // Don't hijack if badge is inside a clickable row (let row clicks work too)
  const text = badge.textContent.trim();
  if (!text) return;
  e.stopPropagation();
  cgCopy(text).then(() => {
    badge.classList.add('copied');
    badge.dataset.origText = badge.textContent;
    badge.textContent = t('common.copied');
    setTimeout(() => {
      badge.classList.remove('copied');
      badge.textContent = badge.dataset.origText;
      delete badge.dataset.origText;
    }, 800);
  }).catch(() => {});
});

// ── Global: Escape key closes open dialogs ──────────────────────────────────
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    const openDialog = document.querySelector('dialog[open]');
    if (openDialog) openDialog.close();
  }
});
