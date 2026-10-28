/**
 * CheeseStore — Public Storefront (Arkensia)
 * Cinematic Ho-Oh themed storefront with Lenis smooth scroll, custom cursor,
 * magnetic buttons, ambient embers, scroll-triggered reveals.
 */

const STORE_API = '/store/api';

function storeApp() {
  return {
    // ── State ──────────────────────────────────────────────────────────
    loading: true,
    storeEnabled: true,
    storeName: 'Arkensia',
    storeSubtitle: 'Boutique Premium',
    currencyName: 'crédit',
    stripeKey: '',

    products: [],
    morphos: [],
    cosmetics: [],
    playCosmetics: [],
    allPlayers: [],

    // ── Identity state ─────────────────────────────────────────────────
    oauthEnabled: false,
    user: null,        // { sessionId, linkedUuid?, playerName?, credits? }
    linkCode: null,    // { code, expiresAt }
    linkModalOpen: false,

    purchase: {
      open: false,
      product: null,
      search: '',
      selected: null,
      dropdownOpen: false,
      filteredPlayers: [],
      loading: false,
    },
    /** Morpho lightbox: opened from a gallery card. Holds the catalog entry + a
     *  loading flag for the inline "Buy" button. */
    lightbox: { open: false, item: null, loading: false },
    /** Cosmetic preview+buy modal. `showFallbackImage` flips to true when the 3D
     *  viewer reports it cannot render (no texture / WebGL absent). `_viewer`
     *  holds the live viewer handle so we can dispose() it on close. */
    cosmetic: { open: false, item: null, loading: false, showFallbackImage: false, _viewer: null },
    /** Playable-cosmetic preview+buy modal (playcosmetics namespace). Mirrors the
     *  `cosmetic` modal but drives the new TRAIL/KILL_EFFECT/.../PET catalog and the
     *  /store/api/playcosmetics/{id}/buy endpoint. `showFallbackImage` flips when the
     *  3D viewer can't render the vanilla item texture (CORS/404). */
    playCos: { open: false, item: null, loading: false, showFallbackImage: false, _viewer: null },
    /** Active category filter for the playable cosmetics section ('' = all). */
    playCosCategory: '',
    /** Tag filters above the gallery — multiple actives behave as AND. */
    filters: { terrestrial: false, aquatic: false, aerial: false, shiny: false, legendary: false },
    returnStatus: null,

    _cardRect: null,

    // ── Init ───────────────────────────────────────────────────────────
    /** Catch-all error state — shows the "couldn't reach the store" screen
     *  with a Retry button when init() can't talk to /api/config. */
    loadError: null,
    async retryInit() {
      this.loadError = null;
      this.loading = true;
      await this.init();
    },
    async init() {
      const params = new URLSearchParams(window.location.search);
      const sessionId = params.get('session_id');

      try {
        const cfg = await fetchJson(`${STORE_API}/config`);
        this.storeName = cfg.storeName || 'Arkensia';
        this.storeSubtitle = cfg.storeSubtitle || 'Boutique Premium';
        this.currencyName = cfg.currencyName || 'crédit';
        this.stripeKey = cfg.stripePublishableKey || '';
        this.storeEnabled = cfg.storeEnabled !== false;
        this.oauthEnabled = !!cfg.oauthEnabled;
        document.title = `${this.storeName} — Boutique Premium`;

        if (!this.storeEnabled) { this.loading = false; return; }

        const [products, morphos, cosmetics, playCosmetics, players, me] = await Promise.all([
          fetchJson(`${STORE_API}/products`).catch(() => []),
          fetchJson(`${STORE_API}/morpho/list`).catch(() => []),
          fetchJson(`${STORE_API}/cosmetics`).catch(() => []),
          fetchJson(`${STORE_API}/playcosmetics`).catch(() => []),
          fetchJson(`${STORE_API}/players`).catch(() => []),
          this.oauthEnabled ? fetchJson(`${STORE_API}/oauth/me`).catch(() => null) : Promise.resolve(null),
        ]);

        this.products = products;
        this.morphos = morphos || [];
        this.cosmetics = cosmetics || [];
        // Playable cosmetics arrive enabled-only; sort by sortOrder then title so the
        // grid order is stable regardless of catalog insertion order.
        this.playCosmetics = (playCosmetics || []).slice().sort((a, b) =>
          (a.sortOrder || 0) - (b.sortOrder || 0) || String(a.title || '').localeCompare(String(b.title || '')));
        this.allPlayers = players.sort((a, b) => a.name.localeCompare(b.name));
        // /oauth/me always returns at least a session id; only treat as logged in
        // when an actual link to a Minecraft account is present.
        this.user = me && me.linkedUuid ? me : null;
        this.loadError = null;
      } catch (e) {
        // /config failure means the backend is unreachable — show a retry screen
        // instead of a blank page so the user knows what's going on.
        console.error('Failed to load store:', e);
        this.loadError = e && e.message ? e.message : 'network';
      }
      this.loading = false;

      if (sessionId) {
        window.history.replaceState({}, '', window.location.pathname);
        await this.checkSessionStatus(sessionId);
      }
    },

    // ── Card 3D tilt ──────────────────────────────────────────────────
    handleCardMove(e) {
      if (!matchMedia('(hover: hover) and (pointer: fine)').matches) return;
      const card = e.currentTarget;
      if (!this._cardRect || this._cardRect.el !== card) {
        const r = card.getBoundingClientRect();
        this._cardRect = { el: card, left: r.left, top: r.top, width: r.width, height: r.height };
      }
      const px = e.clientX - this._cardRect.left;
      const py = e.clientY - this._cardRect.top;
      card.style.setProperty('--mx', `${px}px`);
      card.style.setProperty('--my', `${py}px`);
      const rotY = ((px / this._cardRect.width)  - 0.5) *  14;
      const rotX = ((py / this._cardRect.height) - 0.5) * -14;
      card.style.setProperty('--tilt-x', `${rotX}deg`);
      card.style.setProperty('--tilt-y', `${rotY}deg`);
    },
    resetCard(e) {
      this._cardRect = null;
      const card = e.currentTarget;
      card.style.setProperty('--tilt-x', `0deg`);
      card.style.setProperty('--tilt-y', `0deg`);
    },

    // ── Purchase flow ─────────────────────────────────────────────────
    openPurchase(product) {
      this.purchase = {
        open: true, product,
        search: '', selected: null, dropdownOpen: false,
        filteredPlayers: this.allPlayers.slice(0, 50),
        loading: false,
      };
      document.body.style.overflow = 'hidden';
      if (window.__lenis) window.__lenis.stop();
    },
    closePurchase() {
      this.purchase.open = false;
      this.purchase.loading = false;
      if (!this.lightbox.open) document.body.style.overflow = '';
      if (window.__lenis) window.__lenis.start();
    },
    filterPlayers() {
      const q = (this.purchase.search || '').toLowerCase().trim();
      if (!q) {
        this.purchase.filteredPlayers = this.allPlayers.slice(0, 50);
      } else {
        this.purchase.filteredPlayers = this.allPlayers
          .filter(p => p.name.toLowerCase().includes(q))
          .slice(0, 50);
      }
      this.purchase.dropdownOpen = true;
    },
    selectPlayer(p) {
      this.purchase.selected = p;
      this.purchase.search = p.name;
      this.purchase.dropdownOpen = false;
    },
    async startCheckout() {
      if (!this.purchase.selected || !this.purchase.product) return;
      this.purchase.loading = true;
      try {
        const resp = await fetchJson(`${STORE_API}/checkout`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            productId:  this.purchase.product.id,
            playerUUID: this.purchase.selected.uuid,
            playerName: this.purchase.selected.name,
          }),
        });
        if (resp.error) {
          showStoreToast('Erreur : ' + resp.error, 'error');
          this.purchase.loading = false;
          return;
        }
        window.location.href = resp.checkoutUrl;
      } catch (e) {
        console.error('Checkout error:', e);
        showStoreToast('Impossible de lancer le paiement. Réessayez dans un instant.', 'error');
        this.purchase.loading = false;
      }
    },

    // ── Identity (anonymous session + in-game pairing) ────────────────
    _linkPollTimer: null,
    /** Open the pairing modal and start polling for the link to complete. */
    async openLinkCode() {
      try {
        const res = await fetchJson(`${STORE_API}/oauth/link-code`);
        if (res.alreadyLinked) {
          await this.refreshMe();
          return;
        }
        this.linkCode = res;
        this.linkModalOpen = true;
        document.body.style.overflow = 'hidden';
        if (window.__lenis) window.__lenis.stop();
        // Poll /oauth/me every 3s — close automatically once linked.
        if (this._linkPollTimer) clearInterval(this._linkPollTimer);
        this._linkPollTimer = setInterval(async () => {
          await this.refreshMe();
          if (this.user && this.user.linkedUuid) {
            this.closeLinkCode();
          }
        }, 3000);
      } catch (e) {
        showStoreToast('Impossible de générer un code. Veuillez réessayer.', 'error');
      }
    },
    closeLinkCode() {
      this.linkModalOpen = false;
      this.linkCode = null;
      document.body.style.overflow = '';
      if (window.__lenis) window.__lenis.start();
      if (this._linkPollTimer) { clearInterval(this._linkPollTimer); this._linkPollTimer = null; }
      this.refreshMe();
    },
    async logout() {
      try { await fetchJson(`${STORE_API}/oauth/logout`, { method: 'POST' }); }
      catch (e) { /* ignore */ }
      // After logout we still need the cookie to be re-issued — refresh me.
      await this.refreshMe();
    },
    async refreshMe() {
      try {
        const me = await fetchJson(`${STORE_API}/oauth/me`);
        // No `linkedUuid` field means the session is anonymous; treat as logged out.
        this.user = me && me.linkedUuid ? me : null;
      } catch (e) { this.user = null; }
    },

    // ── Morpho purchase flow (driven by the gallery lightbox) ─────────
    canBuyMorpho() {
      const m = this.lightbox.item;
      if (!m) return false;
      if (!this.user || !this.user.linkedUuid) return false;
      const balance = this.user.credits || 0;
      return balance >= (m.priceCredits || 0);
    },
    /** Pretty French translation for the error codes returned by /morpho/buy. */
    _morphoBuyErrorMessage(code) {
      switch (code) {
        case 'already_owned':         return 'Vous possédez déjà cette morpho dans votre codex.';
        case 'insufficient_credits':  return 'Solde insuffisant pour cet achat.';
        case 'not_authenticated':     return 'Identifiez-vous pour pouvoir acheter.';
        case 'not_linked':            return 'Liez votre compte Minecraft avant d\'acheter.';
        case 'morpho_not_found':      return 'Cette morpho n\'est plus disponible.';
        case 'server_not_ready':      return 'Serveur indisponible, réessayez dans un instant.';
        default:                      return code || 'Achat échoué.';
      }
    },
    async buyMorpho() {
      const m = this.lightbox.item;
      if (!m) return;
      this.lightbox.loading = true;
      try {
        const res = await fetchJson(`${STORE_API}/morpho/buy`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ morphoId: m.id }),
        });
        if (res.success) {
          if (this.user) this.user.credits = res.newBalance;
          showStoreToast(`🧬 ${m.title} ajouté à votre codex en jeu !`, 'success');
          this.closeLightbox();
        } else {
          showStoreToast(this._morphoBuyErrorMessage(res.error), 'error');
          this.lightbox.loading = false;
        }
      } catch (e) {
        showStoreToast(this._morphoBuyErrorMessage(e.message), 'error');
        this.lightbox.loading = false;
      }
    },

    // ── Lightbox ──────────────────────────────────────────────────────
    openLightbox(item) {
      this.lightbox = { open: true, item, loading: false };
      document.body.style.overflow = 'hidden';
      if (window.__lenis) window.__lenis.stop();
    },
    closeLightbox() {
      this.lightbox = { open: false, item: null, loading: false };
      if (!this.purchase.open && !this.linkModalOpen) document.body.style.overflow = '';
      if (window.__lenis) window.__lenis.start();
    },

    // ── Cosmetics ─────────────────────────────────────────────────────────────
    /** French label for a cosmetic type code (ITEM/HAT/TRAIL/PARTICLE/EFFECT). */
    cosmeticTypeLabel(type) {
      switch ((type || '').toUpperCase()) {
        case 'HAT':      return 'Chapeau';
        case 'ITEM':     return 'Item';
        case 'TRAIL':    return 'Traînée';
        case 'PARTICLE': return 'Particules';
        case 'EFFECT':   return 'Effet';
        default:         return 'Cosmétique';
      }
    },
    /** Small caption shown over the live viewer (3D vs animated FX vignette). */
    cosmeticViewerHint() {
      const t = (this.cosmetic.item?.type || '').toUpperCase();
      if (t === 'PARTICLE' || t === 'TRAIL' || t === 'EFFECT') return 'Aperçu animé';
      return 'Glissez pour pivoter';
    },
    /** Open the preview modal and mount the 3D/FX viewer into the panel. */
    openCosmetic(item) {
      this.cosmetic = { open: true, item, loading: false, showFallbackImage: false, _viewer: null };
      document.body.style.overflow = 'hidden';
      if (window.__lenis) window.__lenis.stop();
      // Wait for the modal DOM (x-ref) to exist before mounting the viewer.
      this.$nextTick(() => this._mountCosmeticViewer(item));
    },
    _mountCosmeticViewer(item) {
      const host = this.$refs.cosmeticViewer;
      if (!host) { this.cosmetic.showFallbackImage = true; return; }
      // If the viewer module is missing, or it can't render, fall back to the PNG.
      if (!window.McCosmeticViewer || typeof window.McCosmeticViewer.mount !== 'function') {
        this.cosmetic.showFallbackImage = true;
        return;
      }
      // The viewer signals "no 3D possible" via this hook (e.g. CORS image error).
      host.__onNoTexture = () => { this.cosmetic.showFallbackImage = true; };
      try {
        const handle = window.McCosmeticViewer.mount(host, {
          id: item.id,
          name: item.name,
          type: item.type,
          itemId: item.itemId,
          hasImage: item.hasImage,
          modelRef: item.modelRef,
        });
        this.cosmetic._viewer = handle;
        // Synchronous "noTexture" verdict (no 3D for this item/type) → show PNG.
        if (handle && handle.noTexture) this.cosmetic.showFallbackImage = true;
      } catch (e) {
        console.error('Cosmetic viewer mount failed:', e);
        this.cosmetic.showFallbackImage = true;
      }
    },
    closeCosmetic() {
      if (this.cosmetic._viewer && typeof this.cosmetic._viewer.dispose === 'function') {
        try { this.cosmetic._viewer.dispose(); } catch (e) { /* ignore */ }
      }
      this.cosmetic = { open: false, item: null, loading: false, showFallbackImage: false, _viewer: null };
      if (!this.purchase.open && !this.linkModalOpen && !this.lightbox.open) document.body.style.overflow = '';
      if (window.__lenis) window.__lenis.start();
    },
    /** Pretty French translation for the error codes returned by /cosmetics/buy. */
    _cosmeticBuyErrorMessage(code) {
      switch (code) {
        case 'insufficient_credits': return 'Solde insuffisant pour cet achat.';
        case 'not_authenticated':    return 'Identifiez-vous pour pouvoir acheter.';
        case 'not_linked':           return 'Liez votre compte Minecraft avant d\'acheter.';
        case 'cosmetic_not_found':   return 'Ce cosmétique n\'est plus disponible.';
        case 'forbidden_origin':     return 'Requête refusée. Rechargez la page et réessayez.';
        case 'too_many_requests':    return 'Trop de tentatives. Patientez un instant.';
        case 'server_not_ready':     return 'Serveur indisponible, réessayez dans un instant.';
        case 'buy_failed':           return 'Achat échoué. Réessayez dans un instant.';
        default:                     return code || 'Achat échoué.';
      }
    },
    async buyCosmetic() {
      const c = this.cosmetic.item;
      if (!c) return;
      this.cosmetic.loading = true;
      try {
        const res = await fetchJson(`${STORE_API}/cosmetics/buy`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ cosmeticId: c.id }),
        });
        if (res.success) {
          if (this.user) this.user.credits = res.newBalance;
          showStoreToast(`✨ ${c.name} débloqué en jeu !`, 'success');
          this.closeCosmetic();
        } else {
          showStoreToast(this._cosmeticBuyErrorMessage(res.error), 'error');
          this.cosmetic.loading = false;
        }
      } catch (e) {
        showStoreToast(this._cosmeticBuyErrorMessage(e.message), 'error');
        this.cosmetic.loading = false;
      }
    },

    // ── Playable cosmetics (playcosmetics namespace) ──────────────────────────
    /** Ordered category list for the filter bar + section grouping, with pretty FR
     *  labels. Codes match the backend CosmeticEntry.Category enum exactly. */
    playCosCategories: [
      { code: 'TRAIL',        label: 'Traînées' },
      { code: 'KILL_EFFECT',  label: 'Effets de kill' },
      { code: 'DEATH_EFFECT', label: 'Effets de mort' },
      { code: 'CHAT_TAG',     label: 'Tags chat' },
      { code: 'NAME_COLOR',   label: 'Couleur de pseudo' },
      { code: 'PET',          label: 'Familiers' },
    ],
    /** FR label for a single category code (used on the card badge + modal pill). */
    playCosCategoryLabel(code) {
      const c = (code || '').toUpperCase();
      const hit = this.playCosCategories.find(x => x.code === c);
      return hit ? hit.label : 'Cosmétique';
    },
    /** Only the categories that actually have at least one cosmetic — keeps the
     *  filter bar free of empty chips. */
    playCosVisibleCategories() {
      return this.playCosCategories.filter(c =>
        this.playCosmetics.some(p => (p.category || '').toUpperCase() === c.code));
    },
    /** How many cosmetics fall in a category (chip count). '' = total. */
    playCosCount(code) {
      if (!code) return this.playCosmetics.length;
      const c = code.toUpperCase();
      return this.playCosmetics.filter(p => (p.category || '').toUpperCase() === c).length;
    },
    /** Entries matching the active category filter ('' = all). */
    filteredPlayCosmetics() {
      if (!this.playCosCategory) return this.playCosmetics;
      const c = this.playCosCategory.toUpperCase();
      return this.playCosmetics.filter(p => (p.category || '').toUpperCase() === c);
    },
    setPlayCosCategory(code) {
      this.playCosCategory = (this.playCosCategory === code) ? '' : code;
    },
    /** Ordered list of texture URLs to try for a vanilla item id like
     *  "minecraft:blaze_powder". Most icons live under textures/item/, a handful of
     *  block-like ones (torch, tnt, leaves, flowers…) under textures/block/ with a
     *  couple of common suffix variants. The <img>/viewer walks this list on error
     *  and finally falls back to a CSS placeholder. CDN is CORS-enabled so the 3D
     *  viewer's canvas read works. */
    playCosIconCandidates(icon) {
      const raw = (icon || 'minecraft:barrier').replace(/^minecraft:/, '').trim();
      const base = 'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/textures';
      return [
        `${base}/item/${raw}.png`,
        `${base}/block/${raw}.png`,
        `${base}/block/${raw}_side.png`,
        `${base}/block/${raw}_top.png`,
      ];
    },
    /** <img onerror> handler: advance to the next candidate URL, or reveal the
     *  placeholder sibling once every candidate has 404'd. */
    onPlayCosIconError(ev, icon) {
      const img = ev.target;
      const list = this.playCosIconCandidates(icon);
      let idx = parseInt(img.dataset.candidate || '0', 10) + 1;
      if (idx < list.length) {
        img.dataset.candidate = String(idx);
        img.src = list[idx];
      } else {
        img.style.display = 'none';
        const ph = img.parentElement && img.parentElement.querySelector('.play-cos-thumb-fallback');
        if (ph) ph.style.display = 'flex';
      }
    },
    /** Open the preview modal and mount the 3D item viewer for the icon. */
    openPlayCosmetic(item) {
      this.playCos = { open: true, item, loading: false, showFallbackImage: false, _viewer: null };
      document.body.style.overflow = 'hidden';
      if (window.__lenis) window.__lenis.stop();
      this.$nextTick(() => this._mountPlayCosViewer(item));
    },
    _mountPlayCosViewer(item) {
      const host = this.$refs.playCosViewer;
      if (!host) { this.playCos.showFallbackImage = true; return; }
      if (!window.McCosmeticViewer || typeof window.McCosmeticViewer.mount !== 'function') {
        this.playCos.showFallbackImage = true;
        return;
      }
      host.__onNoTexture = () => { this.playCos.showFallbackImage = true; };
      try {
        // Always render the vanilla item icon as a 3D extruded item (type ITEM),
        // passing the first texture candidate. The viewer falls back via __onNoTexture.
        const handle = window.McCosmeticViewer.mount(host, {
          id: item.id,
          name: item.title,
          type: 'ITEM',
          itemId: item.icon,
          hasImage: true,
          imageUrl: this.playCosIconCandidates(item.icon)[0],
        });
        this.playCos._viewer = handle;
        if (handle && handle.noTexture) this.playCos.showFallbackImage = true;
      } catch (e) {
        console.error('Play-cosmetic viewer mount failed:', e);
        this.playCos.showFallbackImage = true;
      }
    },
    closePlayCosmetic() {
      if (this.playCos._viewer && typeof this.playCos._viewer.dispose === 'function') {
        try { this.playCos._viewer.dispose(); } catch (e) { /* ignore */ }
      }
      this.playCos = { open: false, item: null, loading: false, showFallbackImage: false, _viewer: null };
      if (!this.purchase.open && !this.linkModalOpen && !this.lightbox.open && !this.cosmetic.open) {
        document.body.style.overflow = '';
      }
      if (window.__lenis) window.__lenis.start();
    },
    /** Pretty French translation for the codes returned by /playcosmetics/{id}/buy. */
    _playCosBuyErrorMessage(code) {
      switch (code) {
        case 'insufficient_credits': return 'Solde insuffisant pour cet achat.';
        case 'not_authenticated':    return 'Identifiez-vous pour pouvoir acheter.';
        case 'not_linked':           return 'Liez votre compte Minecraft avant d\'acheter.';
        case 'already_owned':        return 'Vous possédez déjà ce cosmétique.';
        case 'cosmetic_not_found':   return 'Ce cosmétique n\'est plus disponible.';
        case 'forbidden_origin':     return 'Requête refusée. Rechargez la page et réessayez.';
        case 'too_many_requests':    return 'Trop de tentatives. Patientez un instant.';
        case 'server_not_ready':     return 'Serveur indisponible, réessayez dans un instant.';
        case 'buy_failed':           return 'Achat échoué. Réessayez dans un instant.';
        default:                     return code || 'Achat échoué.';
      }
    },
    async buyPlayCosmetic() {
      const c = this.playCos.item;
      if (!c) return;
      this.playCos.loading = true;
      try {
        const res = await fetchJson(`${STORE_API}/playcosmetics/${encodeURIComponent(c.id)}/buy`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({}),
        });
        if (res.success) {
          if (this.user) this.user.credits = res.newBalance;
          showStoreToast(`✨ ${c.title} débloqué en jeu !`, 'success');
          this.closePlayCosmetic();
        } else {
          // The non-2xx path throws below; this guards a 200 with {success:false}.
          showStoreToast(this._playCosBuyErrorMessage(res.error), 'error');
          this.playCos.loading = false;
        }
      } catch (e) {
        // fetchJson throws Error(message) where message is the backend `error` code
        // (401 not_authenticated, 402 insufficient_credits, 403 not_linked,
        //  409 already_owned, 429 too_many_requests…). 409 closes the modal since
        // re-buying is pointless once owned.
        const code = e && e.message ? e.message : 'buy_failed';
        showStoreToast(this._playCosBuyErrorMessage(code), 'error');
        this.playCos.loading = false;
        if (code === 'already_owned') this.closePlayCosmetic();
      }
    },

    // ── Tag filters ──────────────────────────────────────────────────
    // Alpine re-evaluates getters and method calls on every reactive tick. With
    // ~50-100 morphos and 3+ call-sites per render (x-show, x-show, x-for) this
    // becomes a bottleneck on mobile. Both results are memoised with a key
    // derived from `filters` + morphos length; recompute only when either flips.
    _filterKey: '',
    _filteredCache: null,
    _countsCache: null,
    _ensureFilterCache() {
      const f = this.filters;
      const key = `${this.morphos.length}|${+f.terrestrial}${+f.aquatic}${+f.aerial}${+f.shiny}${+f.legendary}`;
      if (this._filterKey === key) return;
      this._filterKey = key;
      const tags = ['terrestrial','aquatic','aerial','shiny','legendary'];
      const active = tags.filter(k => f[k]);
      this._filteredCache = active.length === 0
        ? this.morphos
        : this.morphos.filter(m => active.every(k => !!m[k]));
      const counts = {};
      for (const tag of tags) {
        const nextActive = active.includes(tag) ? active : [...active, tag];
        counts[tag] = this.morphos.filter(m => nextActive.every(k => !!m[k])).length;
      }
      this._countsCache = counts;
    },
    /** Apply the active filters: each active tag must be true on the morpho. */
    filteredMorphos() {
      this._ensureFilterCache();
      return this._filteredCache;
    },
    hasActiveFilter() {
      const f = this.filters;
      return f.terrestrial || f.aquatic || f.aerial || f.shiny || f.legendary;
    },
    /** Live count badge on each chip — how many morphos would remain if THIS tag
     *  was added on top of the currently active filters. Helps the user see ahead
     *  which combinations have results before clicking. */
    get filterCounts() {
      this._ensureFilterCache();
      return this._countsCache;
    },
    toggleFilter(key) {
      this.filters[key] = !this.filters[key];
    },
    clearFilters() {
      this.filters = { terrestrial: false, aquatic: false, aerial: false, shiny: false, legendary: false };
    },

    // ── Stripe return ─────────────────────────────────────────────────
    async checkSessionStatus(sessionId) {
      try {
        const resp = await fetchJson(
          `${STORE_API}/session-status?session_id=${encodeURIComponent(sessionId)}`
        );
        this.returnStatus = resp.status || 'unknown';
        if (resp.status === 'open') {
          setTimeout(() => { this.returnStatus = null; }, 3000);
        }
      } catch (e) {
        console.error('Session status check failed:', e);
        this.returnStatus = null;
      }
    },

    // ── Helpers ───────────────────────────────────────────────────────
    formatPrice(cents) {
      if (!cents && cents !== 0) return '';
      return (cents / 100).toFixed(2).replace('.', ',') + ' €';
    },
  };
}

// ── Lenis smooth scroll (desktop only) ───────────────────────────────────────
// On mobile/touch devices, Lenis hijacks the wheel/touch handlers and conflicts
// with native scroll-on-touch — making the page feel frozen and unresponsive.
// We expose a stub on window.__lenis so the rest of the code (anchor smooth
// scroll, modal stop/start) keeps working without branching everywhere.
(() => {
  const isCoarse = matchMedia('(pointer: coarse)').matches;
  if (isCoarse || typeof Lenis === 'undefined') {
    window.__lenis = {
      stop: () => {},
      start: () => {},
      on: () => {},
      scrollTo: (target, opts) => {
        if (typeof target === 'string') target = document.querySelector(target);
        if (target && target.scrollIntoView) {
          target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      },
    };
    // Anchor smooth-scroll fallback (native, no Lenis hijack)
    document.addEventListener('click', (e) => {
      const a = e.target.closest && e.target.closest('a[href^="#"]');
      if (!a) return;
      const id = a.getAttribute('href');
      if (id.length > 1) {
        const tgt = document.querySelector(id);
        if (tgt) { e.preventDefault(); tgt.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
      }
    });
    // Mini-header visibility on native scroll (desktop tablet portrait, etc.)
    const miniHeader = document.getElementById('miniHeader');
    const progressBar = document.getElementById('progressBar');
    if (miniHeader || progressBar) {
      let ticking = false;
      window.addEventListener('scroll', () => {
        if (ticking) return;
        ticking = true;
        requestAnimationFrame(() => {
          const scroll = window.scrollY || window.pageYOffset || 0;
          if (miniHeader) miniHeader.classList.toggle('is-visible', scroll > 320);
          if (progressBar) {
            const docH = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
            progressBar.style.transform = `scaleX(${Math.min(1, scroll / docH)})`;
          }
          ticking = false;
        });
      }, { passive: true });
    }
    return;
  }
  const lenis = new Lenis({
    duration: 1.15,
    easing: (t) => Math.min(1, 1.001 - Math.pow(2, -10 * t)),
    smoothWheel: true,
    wheelMultiplier: 0.9,
    touchMultiplier: 1.5,
  });
  window.__lenis = lenis;

  function raf(time) { lenis.raf(time); requestAnimationFrame(raf); }
  requestAnimationFrame(raf);

  let docH = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
  const recomputeDocH = () => { docH = Math.max(1, document.documentElement.scrollHeight - window.innerHeight); };
  window.addEventListener('resize', recomputeDocH);
  if (window.ResizeObserver) new ResizeObserver(recomputeDocH).observe(document.body);

  const progressBar = document.getElementById('progressBar');
  const miniHeader  = document.getElementById('miniHeader');
  let miniVisible = false;

  lenis.on('scroll', ({ scroll }) => {
    // transform: scaleX is composited (no layout); style.width is not.
    if (progressBar) progressBar.style.transform = `scaleX(${Math.min(1, scroll / docH)})`;
    const shouldShow = scroll > 320;
    if (shouldShow !== miniVisible && miniHeader) {
      miniHeader.classList.toggle('is-visible', shouldShow);
      miniVisible = shouldShow;
    }
    document.documentElement.style.setProperty('--scroll', scroll);
  });

  // Smooth scroll on anchor click
  document.addEventListener('click', (e) => {
    const a = e.target.closest && e.target.closest('a[href^="#"]');
    if (!a) return;
    const id = a.getAttribute('href');
    if (id.length > 1) {
      const tgt = document.querySelector(id);
      if (tgt) {
        e.preventDefault();
        lenis.scrollTo(tgt, { offset: -20 });
      }
    }
  });
})();

// ── Custom cursor + magnetic buttons ─────────────────────────────────────────
(() => {
  const dot  = document.getElementById('cursorDot');
  const ring = document.getElementById('cursorRing');
  if (!dot || !ring) return;
  const isFinePointer = matchMedia('(hover: hover) and (pointer: fine)').matches;
  if (!isFinePointer) { dot.style.display = 'none'; ring.style.display = 'none'; return; }

  let mx = 0, my = 0, rx = 0, ry = 0;
  const HOVER_SEL = 'a, button, .store-card, .gallery-card, [role="button"]';
  const CARD_SEL  = '.store-card, .gallery-card';

  window.addEventListener('mousemove', (e) => {
    mx = e.clientX; my = e.clientY;
    dot.style.transform = `translate(${mx}px, ${my}px) translate(-50%, -50%)`;
  }, { passive: true });

  function frame() {
    rx += (mx - rx) * 0.18;
    ry += (my - ry) * 0.18;
    ring.style.transform = `translate(${rx}px, ${ry}px) translate(-50%, -50%)`;
    requestAnimationFrame(frame);
  }
  frame();

  document.addEventListener('mouseover', (e) => {
    const t = e.target;
    if (t.closest && t.closest(HOVER_SEL)) {
      ring.classList.add('hover');
      dot.classList.add('hover');
      if (t.closest(CARD_SEL)) ring.classList.add('card');
    }
  });
  document.addEventListener('mouseout', (e) => {
    const t = e.target;
    if (t.closest && t.closest(HOVER_SEL)) {
      const r = e.relatedTarget;
      if (!r || !r.closest || !r.closest(HOVER_SEL)) {
        ring.classList.remove('hover');
        dot.classList.remove('hover');
        ring.classList.remove('card');
      }
    }
  });
  document.addEventListener('mousedown', () => ring.classList.add('click'));
  document.addEventListener('mouseup',   () => ring.classList.remove('click'));
  document.addEventListener('mouseleave',() => { dot.style.opacity = '0'; ring.style.opacity = '0'; });
  document.addEventListener('mouseenter',() => { dot.style.opacity = '1'; ring.style.opacity = '1'; });

  // Magnetic buttons via CSS variables
  document.querySelectorAll('.magnetic').forEach(btn => {
    btn.addEventListener('mousemove', (e) => {
      const rect = btn.getBoundingClientRect();
      const cx = rect.left + rect.width / 2;
      const cy = rect.top + rect.height / 2;
      const dx = (e.clientX - cx) * 0.25;
      const dy = (e.clientY - cy) * 0.35;
      btn.style.setProperty('--mag-x', dx + 'px');
      btn.style.setProperty('--mag-y', dy + 'px');
    });
    btn.addEventListener('mouseleave', () => {
      btn.style.setProperty('--mag-x', '0px');
      btn.style.setProperty('--mag-y', '0px');
    });
  });
})();

// ── Scroll-triggered reveals ─────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  const reveals = document.querySelectorAll('.scroll-reveal');
  const io = new IntersectionObserver((entries) => {
    entries.forEach(e => {
      if (e.isIntersecting) {
        e.target.classList.add('is-visible');
        io.unobserve(e.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -80px 0px' });
  reveals.forEach(el => io.observe(el));
});

// ── Fetch helper ─────────────────────────────────────────────────────────────
async function fetchJson(url, opts = {}) {
  const resp = await fetch(url, opts);
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({ error: 'HTTP ' + resp.status }));
    throw new Error(err.error || 'HTTP ' + resp.status);
  }
  return resp.json();
}

// ── Toast (top-right slide-down, auto-dismiss) ──────────────────────────────
function showStoreToast(message, type = 'info') {
  let host = document.getElementById('store-toast-host');
  if (!host) {
    host = document.createElement('div');
    host.id = 'store-toast-host';
    document.body.appendChild(host);
  }
  const t = document.createElement('div');
  t.className = 'store-toast store-toast-' + type;
  t.textContent = message;
  host.appendChild(t);
  // Auto-dismiss after 4s, with fade-out animation
  setTimeout(() => { t.classList.add('is-leaving'); }, 3600);
  setTimeout(() => { t.remove(); if (!host.children.length) host.remove(); }, 4200);
}
