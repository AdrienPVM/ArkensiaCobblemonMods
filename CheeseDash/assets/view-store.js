// ── CheeseStore ──────────────────────────────────────────────────────────────
async function buildStore(app) {
  const _prev = window._st;
  let products, orders, stats, config, wallets, gallery, cosmetics;

  // Use cached data for tab/edit switches, fetch fresh otherwise
  if (_prev && _prev.products && _prev._loaded) {
    products = _prev.products;
    orders = _prev.orders;
    stats = _prev.stats;
    config = _prev.config;
    wallets = _prev.wallets;
    gallery = _prev.gallery || [];
    cosmetics = _prev.cosmetics || [];
  } else {
    try {
      [products, orders, stats, config, wallets, gallery, cosmetics] = await Promise.all([
        API.store.products(),
        API.store.orders(),
        API.store.stats(),
        API.store.config(),
        API.store.wallets(),
        API.store.gallery().catch(() => []),
        API.store.cosmetics().catch(() => []),
      ]);
    } catch (e) {
      // Graceful degradation: a missing/disabled store module answers 404.
      const unavailable = /HTTP 404/i.test(e.message || '') || e.code === 'NOT_FOUND';
      const msg = unavailable ? t('store.unavailable') : e.message;
      if (!unavailable) app.toast(e.message, 'error');
      return `<div class="empty-state"><span class="empty-state-icon">&#x26A0;</span><span class="empty-state-text">${escHtml(msg)}</span></div>`;
    }
  }

  window._st = {
    products, orders, stats, config, wallets, gallery, cosmetics, app,
    _loaded: true,
    // Cache-buster bumped each time we re-render the view; lets freshly
    // uploaded product/gallery images bypass the browser cache.
    imgVersion: Date.now(),
    tab: _prev?.tab || 'products',
    editing: _prev?.editing || null,
    editingWallet: _prev?.editingWallet || null,
    editingGallery: _prev?.editingGallery || null,
    editingCosmetic: _prev?.editingCosmetic || null,

    _rerender() {
      app.viewHtml = '';
      app.navigate('store');
    },

    async refresh() {
      try {
        [this.products, this.orders, this.stats, this.wallets, this.gallery, this.cosmetics] = await Promise.all([
          API.store.products(), API.store.orders(), API.store.stats(), API.store.wallets(),
          API.store.gallery().catch(() => []),
          API.store.cosmetics().catch(() => []),
        ]);
        this.config = await API.store.config();
      } catch (e) { app.toast(e.message, 'error'); }
      this._rerender();
    },

    openCreate() {
      this.editing = {
        _isNew: true, name: '', description: '', priceCents: 500, credits: 10, currency: 'eur',
        category: 'pack', commands: [''], enabled: true, featured: false, sortOrder: 0, imageBase64: null,
      };
      this._rerender();
    },

    openEdit(p) {
      this.editing = { ...p, commands: [...(p.commands || [])], imageBase64: null, _isNew: false };
      if (this.editing.commands.length === 0) this.editing.commands = [''];
      this._rerender();
    },

    closeEdit() {
      this.editing = null;
      this.refresh();
    },

    addCommand() {
      if (this.editing) this.editing.commands.push('');
      this._rerender();
    },

    removeCommand(i) {
      if (this.editing && this.editing.commands.length > 1) {
        this.editing.commands.splice(i, 1);
        this._rerender();
      }
    },

    async saveProduct() {
      if (!this.editing) return;
      const e = this.editing;
      // Send metadata as a small payload, then upload the image separately. Bundling
      // a multi-MB base64 string into the PUT body was making the API thread choke
      // on large JSON parses and locking the dashboard.
      const data = {
        name: e.name, description: e.description, priceCents: parseInt(e.priceCents) || 0,
        credits: parseInt(e.credits) || 0,
        currency: e.currency, category: e.category, enabled: e.enabled, featured: !!e.featured,
        sortOrder: parseInt(e.sortOrder) || 0,
        commands: e.commands.filter(c => c.trim()),
      };
      try {
        let saved;
        if (e._isNew) {
          saved = await API.store.createProduct(data);
          app.toast(t('store.productCreated'), 'success');
        } else {
          saved = await API.store.updateProduct(e.id, data);
          app.toast(t('store.productUpdated'), 'success');
        }
        if (e.imageBase64 && saved && saved.id) {
          try {
            await API.store.uploadImage(saved.id, { imageBase64: e.imageBase64 });
          } catch (imgErr) {
            app.toast(imgErr.message || 'Image upload failed', 'error');
          }
        }
        this.closeEdit();
      } catch (err) { app.toast(err.message, 'error'); }
    },

    async deleteProduct(id, name) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.store.deleteProduct(id);
        app.toast(t('store.productDeleted'), 'success');
        this.refresh();
      } catch (err) { app.toast(err.message, 'error'); }
    },

    async deliverOrder(orderId) {
      try {
        await API.store.deliver(orderId);
        app.toast(t('store.deliveryDone'), 'success');
        this.refresh();
      } catch (err) { app.toast(err.message, 'error'); }
    },

    async saveConfig() {
      try {
        await API.store.updateConfig(this.config);
        app.toast(t('common.success'), 'success');
      } catch (err) { app.toast(err.message, 'error'); }
    },

    async handleImageUpload(input) {
      const file = input.files[0];
      if (!file) return;
      try {
        const b64 = await optimizeImageFile(file, { maxDim: 1280 });
        if (this.editing) this.editing.imageBase64 = b64;
        this._rerender();
      } catch (err) {
        app.toast(err.message || 'Image error', 'error');
      }
    },

    async editWallet(uuid, name, currentBalance) {
      this.editingWallet = { uuid, name, balance: currentBalance };
      this._rerender();
    },

    async saveWallet() {
      if (!this.editingWallet) return;
      try {
        await API.store.updateWallet(this.editingWallet.uuid, {
          balance: parseInt(this.editingWallet.balance) || 0,
          playerName: this.editingWallet.name,
        });
        app.toast(t('store.balanceUpdated'), 'success');
        this.editingWallet = null;
        this.refresh();
      } catch (e) { app.toast(e.message, 'error'); }
    },

    cancelEditWallet() {
      this.editingWallet = null;
      this._rerender();
    },

    switchTab(t) {
      this.tab = t;
      this._rerender();
    },

    // ── Gallery ────────────────────────────────────────────────────────
    openCreateGallery() {
      this.editingGallery = {
        _isNew: true, title: '', description: '', priceCredits: 100,
        isNew: true, imageBase64: null,
      };
      this._rerender();
    },

    openEditGallery(g) {
      this.editingGallery = { ...g, imageBase64: null, _isNew: false };
      this._rerender();
    },

    closeEditGallery() {
      this.editingGallery = null;
      this.refresh();
    },

    async handleGalleryImageUpload(input) {
      const file = input.files[0];
      if (!file) return;
      try {
        const b64 = await optimizeImageFile(file, { maxDim: 1280 });
        if (this.editingGallery) this.editingGallery.imageBase64 = b64;
        this._rerender();
      } catch (err) {
        app.toast(err.message || 'Image error', 'error');
      }
    },

    async saveGallery() {
      if (!this.editingGallery) return;
      const e = this.editingGallery;
      const data = {
        title: e.title,
        description: e.description || '',
        priceCredits: parseInt(e.priceCredits) || 0,
        isNew: !!e.isNew,
      };
      if (e.imageBase64) data.imageBase64 = e.imageBase64;
      try {
        if (e._isNew) {
          await API.store.createGallery(data);
          app.toast(t('store.galleryCreated'), 'success');
        } else {
          await API.store.updateGallery(e.id, data);
          app.toast(t('store.galleryUpdated'), 'success');
        }
        this.closeEditGallery();
      } catch (err) { app.toast(err.message, 'error'); }
    },

    async deleteGallery(id) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.store.deleteGallery(id);
        app.toast(t('store.galleryDeleted'), 'success');
        this.refresh();
      } catch (err) { app.toast(err.message, 'error'); }
    },

    async toggleGalleryNew(id, currentValue) {
      try {
        await API.store.updateGallery(id, { isNew: !currentValue });
        const g = this.gallery.find(x => x.id === id);
        if (g) g.isNew = !currentValue;
        this._rerender();
      } catch (err) { app.toast(err.message, 'error'); }
    },

    _galleryDragId: null,

    onGalleryDragStart(ev, id) {
      this._galleryDragId = id;
      ev.dataTransfer.effectAllowed = 'move';
      try { ev.dataTransfer.setData('text/plain', id); } catch (_) {}
      ev.currentTarget.classList.add('dragging');
    },

    onGalleryDragOver(ev) {
      ev.preventDefault();
      ev.dataTransfer.dropEffect = 'move';
    },

    onGalleryDragEnd(ev) {
      ev.currentTarget.classList.remove('dragging');
      this._galleryDragId = null;
    },

    async onGalleryDrop(ev, targetId) {
      ev.preventDefault();
      const srcId = this._galleryDragId;
      if (!srcId || srcId === targetId) return;
      // Reorder among non-new items only — newest items stay pinned to top
      const ids = this.gallery.map(g => g.id);
      const srcIdx = ids.indexOf(srcId);
      const tgtIdx = ids.indexOf(targetId);
      if (srcIdx < 0 || tgtIdx < 0) return;
      ids.splice(srcIdx, 1);
      ids.splice(tgtIdx, 0, srcId);
      // Update local state immediately for snappy UX
      this.gallery = ids.map(id => this.gallery.find(g => g.id === id));
      this._rerender();
      try {
        await API.store.reorderGallery(ids);
        app.toast(t('store.galleryReordered'), 'success');
      } catch (err) { app.toast(err.message, 'error'); this.refresh(); }
    },

    // ── Cosmetics ──────────────────────────────────────────────────────
    openCreateCosmetic() {
      this.editingCosmetic = {
        _isNew: true, name: '', description: '', type: 'ITEM', priceCredits: 100,
        itemId: '', enabled: true, featured: false, sortOrder: 0,
        applyCommands: [''], imageBase64: null,
      };
      this._rerender();
    },

    openEditCosmetic(c) {
      this.editingCosmetic = {
        ...c,
        applyCommands: [...(c.applyCommands || [])],
        imageBase64: null, _isNew: false,
      };
      if (this.editingCosmetic.applyCommands.length === 0) this.editingCosmetic.applyCommands = [''];
      this._rerender();
    },

    closeEditCosmetic() {
      this.editingCosmetic = null;
      this.refresh();
    },

    addCosmeticCommand() {
      if (this.editingCosmetic) this.editingCosmetic.applyCommands.push('');
      this._rerender();
    },

    removeCosmeticCommand(i) {
      if (this.editingCosmetic && this.editingCosmetic.applyCommands.length > 1) {
        this.editingCosmetic.applyCommands.splice(i, 1);
        this._rerender();
      }
    },

    async handleCosmeticImageUpload(input) {
      const file = input.files[0];
      if (!file) return;
      try {
        const b64 = await optimizeImageFile(file, { maxDim: 1280 });
        if (this.editingCosmetic) this.editingCosmetic.imageBase64 = b64;
        this._rerender();
      } catch (err) {
        app.toast(err.message || 'Image error', 'error');
      }
    },

    async saveCosmetic() {
      if (!this.editingCosmetic) return;
      const e = this.editingCosmetic;
      // POST /store/cosmetics is an upsert: include the id (when editing) so the
      // backend updates in place instead of creating a duplicate.
      const data = {
        name: e.name,
        description: e.description || '',
        type: e.type || 'ITEM',
        priceCredits: parseInt(e.priceCredits) || 0,
        itemId: (e.itemId || '').trim(),
        enabled: !!e.enabled,
        featured: !!e.featured,
        sortOrder: parseInt(e.sortOrder) || 0,
        applyCommands: e.applyCommands.filter(c => c.trim()),
      };
      if (!e._isNew && e.id) data.id = e.id;
      try {
        const saved = await API.store.saveCosmetic(data);
        // Upload image separately to keep the metadata payload small.
        if (e.imageBase64 && saved && saved.id) {
          try {
            await API.store.uploadCosmeticImage(saved.id, { imageBase64: e.imageBase64 });
          } catch (imgErr) {
            app.toast(imgErr.message || 'Image upload failed', 'error');
          }
        }
        app.toast(e._isNew ? t('store.cosmeticCreated') : t('store.cosmeticUpdated'), 'success');
        this.closeEditCosmetic();
      } catch (err) { app.toast(err.message, 'error'); }
    },

    async deleteCosmetic(id) {
      if (!await confirmAction(t('common.deleteConfirm'))) return;
      try {
        await API.store.deleteCosmetic(id);
        app.toast(t('store.cosmeticDeleted'), 'success');
        this.refresh();
      } catch (err) { app.toast(err.message, 'error'); }
    },
  };

  const st = window._st;

  // ── Stat Cards ──────────────────────────────────────────────────────
  const statCards = `<div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
    <div class="stat-card"><div class="stat-icon">&#x1F4E6;</div><div class="stat-value">${stats.totalProducts}</div><div class="stat-label">${t('store.products')}</div></div>
    <div class="stat-card"><div class="stat-icon">&#x1F6D2;</div><div class="stat-value">${stats.totalOrders}</div><div class="stat-label">${t('store.orders')}</div></div>
    <div class="stat-card"><div class="stat-icon">&#x23F3;</div><div class="stat-value">${stats.pendingOrders}</div><div class="stat-label">${t('store.pending')}</div></div>
    <div class="stat-card stat-card-glow"><div class="stat-icon">&#x1F4B0;</div><div class="stat-value">${(stats.totalRevenueCents / 100).toFixed(2)} &euro;</div><div class="stat-label">${t('store.revenue')}</div></div>
  </div>`;

  // ── Tabs ─────────────────────────────────────────────────────────────
  // Reset stale tab values (e.g., legacy "gallery" persisted in _prev).
  if (!['products','cosmetics','orders','fromage','config'].includes(st.tab)) st.tab = 'products';

  const tabs = `<div class="detail-tabs mb-6">
    <button class="detail-tab ${st.tab==='products'?'active':''}" onclick="window._st.switchTab('products')">${t('store.tabProducts')}</button>
    <button class="detail-tab ${st.tab==='cosmetics'?'active':''}" onclick="window._st.switchTab('cosmetics')">${t('store.tabCosmetics', {count: (cosmetics||[]).length})}</button>
    <button class="detail-tab ${st.tab==='orders'?'active':''}" onclick="window._st.switchTab('orders')">${t('store.tabOrders', {count: orders.length})}</button>
    <button class="detail-tab ${st.tab==='fromage'?'active':''}" onclick="window._st.switchTab('fromage')">${t('store.tabFromage')}</button>
    <button class="detail-tab ${st.tab==='config'?'active':''}" onclick="window._st.switchTab('config')">${t('common.config')}</button>
  </div>`;

  let content = '';

  // ── Products Tab ─────────────────────────────────────────────────────
  if (st.tab === 'products' && !st.editing) {
    const rows = products.map(p => `<tr>
      <td>${p.hasImage ? `<img src="/api/v1/store/products/${escHtml(p.id)}/image?v=${st.imgVersion}" style="width:32px;height:32px;border-radius:4px;object-fit:cover">` : '<span style="opacity:0.3">-</span>'}</td>
      <td>${escHtml(p.name)}</td>
      <td><span class="id-badge">${escHtml(p.category || '-')}</span></td>
      <td class="td-mono">${(p.priceCents/100).toFixed(2)} &euro;</td>
      <td>${p.commands ? p.commands.length : 0}</td>
      <td><span class="status-dot ${p.enabled ? 's-online' : 's-offline'}"></span> ${p.enabled ? t('common.active') : t('common.inactive')}</td>
      <td>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.openEdit(window._st.products.find(x=>x.id==='${escHtml(p.id)}'))">${t('common.edit')}</button>
        <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._st.deleteProduct('${escHtml(p.id)}','${escHtml(p.name)}')">${t('common.delete')}</button>
      </td>
    </tr>`).join('');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('store.products')} (${products.length})</span>
        <div class="flex gap-2 items-center">
          <a href="/store/" target="_blank" class="btn-cg btn-cg-sm btn-cg-ghost">${t('store.viewShop')}</a>
          <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="window._st.openCreate()">${t('store.newProduct')}</button>
        </div>
      </div>
      <div class="section-card-body">
        ${products.length ? `<table class="cg-table"><thead><tr>
          <th style="width:40px">${t('store.thImg')}</th><th>${t('common.name')}</th><th>${t('store.thCat')}</th><th>${t('store.thPrice')}</th><th>${t('store.thCmds')}</th><th>${t('common.status')}</th><th>${t('common.actions')}</th>
        </tr></thead><tbody>${rows}</tbody></table>` :
        `<div class="empty-state"><span class="empty-state-icon">&#x1F4E6;</span><span class="empty-state-text">${t('common.noResults')}</span></div>`}
      </div>
    </div>`;
  }

  // ── Product Editor ───────────────────────────────────────────────────
  if (st.tab === 'products' && st.editing) {
    const e = st.editing;
    const commandRows = e.commands.map((cmd, i) => `<div class="flex gap-2 mb-2">
      <input class="input input-sm input-bordered flex-1" value="${escHtml(cmd)}" placeholder="/give {player} diamond 1"
        oninput="window._st.editing.commands[${i}]=this.value">
      <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._st.removeCommand(${i})" ${e.commands.length<=1?'disabled':''}>&#x2715;</button>
    </div>`).join('');

    const imgPreview = e.imageBase64
      ? `<img src="${e.imageBase64}" style="width:80px;height:80px;border-radius:8px;object-fit:cover">`
      : (e.hasImage ? `<img src="/api/v1/store/products/${escHtml(e.id)}/image?v=${st.imgVersion}" style="width:80px;height:80px;border-radius:8px;object-fit:cover">` : '');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${e._isNew ? t('store.newProduct') : t('store.editProduct', {name: escHtml(e.name)})}</span>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.closeEdit()">${t('common.cancel')}</button>
      </div>
      <div class="section-card-body" style="max-width:600px">
        <div class="grid grid-cols-2 gap-4 mb-4">
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('common.name')}</label>
            <input class="input input-sm input-bordered w-full" value="${escHtml(e.name)}" oninput="window._st.editing.name=this.value">
          </div>
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.category')}</label>
            <select class="select select-sm select-bordered w-full" onchange="window._st.editing.category=this.value">
              <option value="pack" ${e.category==='pack'?'selected':''}>${t('store.catPack')}</option>
              <option value="gems" ${e.category==='gems'?'selected':''}>${t('store.catCredits')}</option>
            </select>
          </div>
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('common.description')}</label>
          <textarea class="textarea textarea-sm textarea-bordered w-full" rows="2" oninput="window._st.editing.description=this.value">${escHtml(e.description || '')}</textarea>
        </div>
        <div class="grid grid-cols-3 gap-4 mb-4">
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.priceCents')}</label>
            <input type="number" class="input input-sm input-bordered w-full" value="${e.priceCents}" oninput="window._st.editing.priceCents=parseInt(this.value)||0">
          </div>
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.credits')}</label>
            <input type="number" class="input input-sm input-bordered w-full" value="${e.credits || 0}" min="0"
              oninput="window._st.editing.credits=parseInt(this.value)||0">
          </div>
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.sortOrder')}</label>
            <input type="number" class="input input-sm input-bordered w-full" value="${e.sortOrder}" oninput="window._st.editing.sortOrder=parseInt(this.value)||0">
          </div>
        </div>
        <div class="grid grid-cols-2 gap-4 mb-4">
          <div class="flex items-center gap-2">
            <input type="checkbox" class="toggle toggle-sm" ${e.enabled?'checked':''} onchange="window._st.editing.enabled=this.checked">
            <label class="text-xs" style="color:var(--muted)">${t('common.active')}</label>
          </div>
          <div class="flex items-center gap-2">
            <input type="checkbox" class="toggle toggle-sm" ${e.featured?'checked':''} onchange="window._st.editing.featured=this.checked">
            <label class="text-xs" style="color:var(--muted)">${t('store.featured')} ${tip('tip.store.featured')}</label>
          </div>
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.productImage')}</label>
          <div class="flex items-center gap-4 mt-1">
            ${imgPreview}
            <input type="file" accept="image/*" class="file-input file-input-sm file-input-bordered" onchange="window._st.handleImageUpload(this)">
          </div>
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.commandsLabel')}</label>
          <div class="mt-1">${commandRows}</div>
          <button class="btn-cg btn-cg-sm btn-cg-ghost mt-1" onclick="window._st.addCommand()">${t('store.addCommand')}</button>
        </div>
        <div class="section-divider"></div>
        <div class="flex justify-end gap-2 mt-4">
          <button class="btn-cg btn-cg-ghost" onclick="window._st.closeEdit()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-primary" onclick="window._st.saveProduct()">${t('common.save')}</button>
        </div>
      </div>
    </div>`;
  }

  // ── Cosmetics Tab ────────────────────────────────────────────────────
  if (st.tab === 'cosmetics' && !st.editingCosmetic) {
    const list = cosmetics || [];
    const typeLabel = (ty) => t('store.cosType.' + (ty || 'ITEM'));
    const cosRows = list.map(c => {
      const thumb = c.hasImage
        ? `<img src="/api/v1/store/cosmetics/${escHtml(c.id)}/image?v=${st.imgVersion}" style="width:32px;height:32px;border-radius:4px;object-fit:cover">`
        : (c.itemId
          ? `<span class="id-badge" title="${escHtml(c.itemId)}" style="font-size:var(--type-xs)">${escHtml(c.itemId)}</span>`
          : '<span style="opacity:0.3">-</span>');
      return `<tr>
        <td>${thumb}</td>
        <td>${escHtml(c.name)}${c.featured ? ` <span class="id-badge" style="font-size:var(--type-xs)">${t('store.featured')}</span>` : ''}</td>
        <td><span class="id-badge">${escHtml(typeLabel(c.type))}</span></td>
        <td class="td-mono">${(c.priceCredits || 0).toLocaleString()}</td>
        <td>${(c.applyCommands || []).length}</td>
        <td><span class="status-dot ${c.enabled ? 's-online' : 's-offline'}"></span> ${c.enabled ? t('common.active') : t('common.inactive')}</td>
        <td>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick='window._st.openEditCosmetic(window._st.cosmetics.find(x=>x.id===${JSON.stringify(c.id)}))'>${t('common.edit')}</button>
          <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._st.deleteCosmetic('${escHtml(c.id)}')">${t('common.delete')}</button>
        </td>
      </tr>`;
    }).join('');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('store.cosmetics')} (${list.length})</span>
        <div class="flex gap-2 items-center">
          <a href="/store/" target="_blank" class="btn-cg btn-cg-sm btn-cg-ghost">${t('store.viewShop')}</a>
          <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="window._st.openCreateCosmetic()">${t('store.newCosmetic')}</button>
        </div>
      </div>
      <div class="section-card-body">
        <p style="color:var(--muted);font-size:var(--type-xs);margin-bottom:0.75rem">${t('store.cosmeticsHint')}</p>
        ${list.length ? `<table class="cg-table"><thead><tr>
          <th style="width:40px">${t('store.thImg')}</th><th>${t('common.name')}</th><th>${t('store.thType')}</th><th>${t('store.thPriceCredits')}</th><th>${t('store.thCmds')}</th><th>${t('common.status')}</th><th>${t('common.actions')}</th>
        </tr></thead><tbody>${cosRows}</tbody></table>` :
        `<div class="empty-state"><span class="empty-state-icon">&#x1F3A9;</span><span class="empty-state-text">${t('store.cosmeticsEmpty')}</span></div>`}
      </div>
    </div>`;
  }

  // ── Cosmetic Editor ──────────────────────────────────────────────────
  if (st.tab === 'cosmetics' && st.editingCosmetic) {
    const e = st.editingCosmetic;
    const commandRows = e.applyCommands.map((cmd, i) => `<div class="flex gap-2 mb-2">
      <input class="input input-sm input-bordered flex-1" value="${escHtml(cmd)}" placeholder="/lp user {player} permission set cosmetic.hat true"
        oninput="window._st.editingCosmetic.applyCommands[${i}]=this.value">
      <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._st.removeCosmeticCommand(${i})" ${e.applyCommands.length<=1?'disabled':''}>&#x2715;</button>
    </div>`).join('');

    const imgPreview = e.imageBase64
      ? `<img src="${e.imageBase64}" style="width:80px;height:80px;border-radius:8px;object-fit:cover">`
      : (e.hasImage ? `<img src="/api/v1/store/cosmetics/${escHtml(e.id)}/image?v=${st.imgVersion}" style="width:80px;height:80px;border-radius:8px;object-fit:cover">` : '');

    const types = ['HAT','TRAIL','PARTICLE','ITEM','EFFECT'];
    const typeOptions = types.map(ty => `<option value="${ty}" ${e.type===ty?'selected':''}>${t('store.cosType.' + ty)}</option>`).join('');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${e._isNew ? t('store.newCosmetic') : t('store.editCosmetic', {name: escHtml(e.name)})}</span>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.closeEditCosmetic()">${t('common.cancel')}</button>
      </div>
      <div class="section-card-body" style="max-width:600px">
        <div class="grid grid-cols-2 gap-4 mb-4">
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('common.name')}</label>
            <input class="input input-sm input-bordered w-full" value="${escHtml(e.name)}" oninput="window._st.editingCosmetic.name=this.value">
          </div>
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.cosmeticType')}</label>
            <select class="select select-sm select-bordered w-full" onchange="window._st.editingCosmetic.type=this.value">
              ${typeOptions}
            </select>
          </div>
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('common.description')}</label>
          <textarea class="textarea textarea-sm textarea-bordered w-full" rows="2" oninput="window._st.editingCosmetic.description=this.value">${escHtml(e.description || '')}</textarea>
        </div>
        <div class="grid grid-cols-3 gap-4 mb-4">
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.priceCredits')}</label>
            <input type="number" class="input input-sm input-bordered w-full" value="${e.priceCredits || 0}" min="0"
              oninput="window._st.editingCosmetic.priceCredits=parseInt(this.value)||0">
          </div>
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.itemId')} ${tip('tip.store.cosmeticItemId')}</label>
            <input class="input input-sm input-bordered w-full" value="${escHtml(e.itemId || '')}" placeholder="minecraft:diamond_helmet"
              oninput="window._st.editingCosmetic.itemId=this.value">
          </div>
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.sortOrder')}</label>
            <input type="number" class="input input-sm input-bordered w-full" value="${e.sortOrder || 0}" oninput="window._st.editingCosmetic.sortOrder=parseInt(this.value)||0">
          </div>
        </div>
        <div class="grid grid-cols-2 gap-4 mb-4">
          <div class="flex items-center gap-2">
            <input type="checkbox" class="toggle toggle-sm" ${e.enabled?'checked':''} onchange="window._st.editingCosmetic.enabled=this.checked">
            <label class="text-xs" style="color:var(--muted)">${t('common.active')}</label>
          </div>
          <div class="flex items-center gap-2">
            <input type="checkbox" class="toggle toggle-sm" ${e.featured?'checked':''} onchange="window._st.editingCosmetic.featured=this.checked">
            <label class="text-xs" style="color:var(--muted)">${t('store.featured')} ${tip('tip.store.featured')}</label>
          </div>
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.cosmeticImage')}</label>
          <div class="flex items-center gap-4 mt-1">
            ${imgPreview}
            <input type="file" accept="image/*" class="file-input file-input-sm file-input-bordered" onchange="window._st.handleCosmeticImageUpload(this)">
          </div>
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.applyCommandsLabel')} ${tip('tip.store.applyCommands')}</label>
          <div class="mt-1">${commandRows}</div>
          <button class="btn-cg btn-cg-sm btn-cg-ghost mt-1" onclick="window._st.addCosmeticCommand()">${t('store.addCommand')}</button>
        </div>
        <div class="section-divider"></div>
        <div class="flex justify-end gap-2 mt-4">
          <button class="btn-cg btn-cg-ghost" onclick="window._st.closeEditCosmetic()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-primary" onclick="window._st.saveCosmetic()">${t('common.save')}</button>
        </div>
      </div>
    </div>`;
  }

  // ── Gallery Tab ──────────────────────────────────────────────────────
  if (st.tab === 'gallery' && !st.editingGallery) {
    const newCount = (gallery || []).filter(g => g.isNew).length;
    const galleryRows = (gallery || []).map(g => {
      const thumb = g.hasImage
        ? `<img src="/api/v1/store/gallery/${escHtml(g.id)}/image?v=${st.imgVersion}" style="width:48px;height:48px;border-radius:6px;object-fit:cover">`
        : `<div style="width:48px;height:48px;border-radius:6px;background:var(--bg-elevated);display:inline-flex;align-items:center;justify-content:center;opacity:0.4">&#x1F5BC;</div>`;
      return `<tr draggable="true"
        ondragstart="window._st.onGalleryDragStart(event, '${escHtml(g.id)}')"
        ondragover="window._st.onGalleryDragOver(event)"
        ondrop="window._st.onGalleryDrop(event, '${escHtml(g.id)}')"
        ondragend="window._st.onGalleryDragEnd(event)"
        style="cursor:move">
        <td style="width:24px;color:var(--muted);user-select:none">&#x2630;</td>
        <td>${thumb}</td>
        <td>${escHtml(g.title || '')}</td>
        <td class="td-mono">${(g.priceCredits || 0).toLocaleString()}</td>
        <td>
          <input type="checkbox" class="toggle toggle-sm" ${g.isNew ? 'checked' : ''}
            onchange="window._st.toggleGalleryNew('${escHtml(g.id)}', ${!!g.isNew})">
        </td>
        <td>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick='window._st.openEditGallery(window._st.gallery.find(x=>x.id===${JSON.stringify(g.id)}))'>${t('common.edit')}</button>
          <button class="btn-cg btn-cg-sm btn-cg-danger" onclick="window._st.deleteGallery('${escHtml(g.id)}')">${t('common.delete')}</button>
        </td>
      </tr>`;
    }).join('');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('store.galleryTitle')} (${(gallery||[]).length}${newCount ? ` &mdash; ${newCount} ${t('store.galleryNewBadge').toLowerCase()}` : ''})</span>
        <div class="flex gap-2 items-center">
          <a href="/store/" target="_blank" class="btn-cg btn-cg-sm btn-cg-ghost">${t('store.viewShop')}</a>
          <button class="btn-cg btn-cg-sm btn-cg-primary" onclick="window._st.openCreateGallery()">${t('store.galleryNew')}</button>
        </div>
      </div>
      <div class="section-card-body">
        <p style="color:var(--muted);font-size:var(--type-xs);margin-bottom:0.75rem">${t('store.galleryHint')}</p>
        ${(gallery||[]).length ? `<table class="cg-table"><thead><tr>
          <th></th><th style="width:64px">${t('store.thImg')}</th><th>${t('common.name')}</th><th>${t('store.thPriceCredits')}</th><th>${t('store.galleryNewBadge')}</th><th>${t('common.actions')}</th>
        </tr></thead><tbody>${galleryRows}</tbody></table>` :
        `<div class="empty-state"><span class="empty-state-icon">&#x1F5BC;</span><span class="empty-state-text">${t('store.galleryEmpty')}</span></div>`}
      </div>
    </div>`;
  }

  // ── Gallery Editor ───────────────────────────────────────────────────
  if (st.tab === 'gallery' && st.editingGallery) {
    const e = st.editingGallery;
    const imgPreview = e.imageBase64
      ? `<img src="${e.imageBase64}" style="width:120px;height:120px;border-radius:8px;object-fit:cover">`
      : (e.hasImage ? `<img src="/api/v1/store/gallery/${escHtml(e.id)}/image?v=${st.imgVersion}" style="width:120px;height:120px;border-radius:8px;object-fit:cover">` : '');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${e._isNew ? t('store.galleryNew') : t('store.galleryEdit', {name: escHtml(e.title || '')})}</span>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.closeEditGallery()">${t('common.cancel')}</button>
      </div>
      <div class="section-card-body" style="max-width:600px">
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('common.name')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(e.title || '')}" oninput="window._st.editingGallery.title=this.value">
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('common.description')}</label>
          <textarea class="textarea textarea-sm textarea-bordered w-full" rows="2" oninput="window._st.editingGallery.description=this.value">${escHtml(e.description || '')}</textarea>
        </div>
        <div class="grid grid-cols-2 gap-4 mb-4">
          <div>
            <label class="text-xs" style="color:var(--muted)">${t('store.galleryPriceCredits')}</label>
            <input type="number" class="input input-sm input-bordered w-full" value="${e.priceCredits || 0}" oninput="window._st.editingGallery.priceCredits=parseInt(this.value)||0">
          </div>
          <div class="flex items-end gap-2">
            <label class="text-xs" style="color:var(--muted)">${t('store.galleryNewBadge')}</label>
            <input type="checkbox" class="toggle toggle-sm" ${e.isNew?'checked':''} onchange="window._st.editingGallery.isNew=this.checked">
          </div>
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.galleryImage')}</label>
          <div class="flex items-center gap-4 mt-1">
            ${imgPreview}
            <input type="file" accept="image/*" class="file-input file-input-sm file-input-bordered" onchange="window._st.handleGalleryImageUpload(this)">
          </div>
        </div>
        <div class="section-divider"></div>
        <div class="flex justify-end gap-2 mt-4">
          <button class="btn-cg btn-cg-ghost" onclick="window._st.closeEditGallery()">${t('common.cancel')}</button>
          <button class="btn-cg btn-cg-primary" onclick="window._st.saveGallery()">${t('common.save')}</button>
        </div>
      </div>
    </div>`;
  }

  // ── Orders Tab ───────────────────────────────────────────────────────
  if (st.tab === 'orders') {
    const orderRows = orders.slice(0, 100).map(o => {
      const statusClass = o.status === 'DELIVERED' ? 's-online' : o.status === 'PENDING' ? 's-warn' : 's-offline';
      const statusLabel = o.status === 'DELIVERED' ? t('store.statusDelivered') : o.status === 'PENDING' ? t('store.statusPending') : t('store.statusFailed');
      const date = o.createdAt ? I18N.fmtDate(o.createdAt) : '-';
      return `<tr>
        <td class="td-mono" style="font-size:var(--type-xs)">${date}</td>
        <td>${escHtml(o.playerName || '-')}</td>
        <td>${escHtml(o.productName || '-')}</td>
        <td class="td-mono">${(o.priceCents/100).toFixed(2)} &euro;</td>
        <td><span class="status-dot ${statusClass}"></span> ${statusLabel}</td>
        <td>${o.status === 'PENDING' ? `<button class="btn-cg btn-cg-sm btn-cg-success" onclick="window._st.deliverOrder('${escHtml(o.orderId)}')">${t('store.deliver')}</button>` :
             o.status === 'FAILED' ? `<button class="btn-cg btn-cg-sm btn-cg-primary" onclick="window._st.deliverOrder('${escHtml(o.orderId)}')">${t('store.retry')}</button>` : ''}</td>
      </tr>`;
    }).join('');

    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('store.orders')} (${orders.length})</span>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.refresh()">${t('common.refresh')}</button>
      </div>
      <div class="section-card-body">
        ${orders.length ? `<table class="cg-table"><thead><tr>
          <th>${t('common.date')}</th><th>${t('common.player')}</th><th>${t('store.thProduct')}</th><th>${t('store.thPrice')}</th><th>${t('common.status')}</th><th>${t('common.actions')}</th>
        </tr></thead><tbody>${orderRows}</tbody></table>` :
        `<div class="empty-state"><span class="empty-state-icon">&#x1F4CB;</span><span class="empty-state-text">${t('common.noResults')}</span></div>`}
      </div>
    </div>`;
  }

  // ── Fromage Tab ─────────────────────────────────────────────────────
  if (st.tab === 'fromage') {
    const sortedWallets = [...wallets].sort((a, b) => b.balance - a.balance);
    const walletRows = sortedWallets.map(w => {
      const isEditing = st.editingWallet && st.editingWallet.uuid === w.uuid;
      const date = w.lastUpdated ? I18N.fmtDate(w.lastUpdated) : '-';
      if (isEditing) {
        return `<tr>
          <td>${escHtml(w.playerName || '-')}</td>
          <td>
            <div class="flex items-center gap-2">
              <input type="number" class="input input-sm input-bordered" style="width:120px" value="${st.editingWallet.balance}"
                oninput="window._st.editingWallet.balance=this.value">
              <button class="btn-cg btn-cg-sm btn-cg-success" onclick="window._st.saveWallet()">OK</button>
              <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.cancelEditWallet()">${t('common.cancel')}</button>
            </div>
          </td>
          <td class="td-mono" style="font-size:var(--type-xs)">${date}</td>
          <td></td>
        </tr>`;
      }
      return `<tr>
        <td>${escHtml(w.playerName || '-')}</td>
        <td class="td-mono">${w.balance.toLocaleString()}</td>
        <td class="td-mono" style="font-size:var(--type-xs)">${date}</td>
        <td>
          <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.editWallet('${escHtml(w.uuid)}','${escHtml(w.playerName || '')}',${w.balance})">${t('common.edit')}</button>
        </td>
      </tr>`;
    }).join('');

    content = `<div class="grid grid-cols-2 gap-4 mb-6">
      <div class="stat-card stat-card-glow"><div class="stat-icon">&#x1F9C0;</div><div class="stat-value">${(stats.totalFromage || 0).toLocaleString()}</div><div class="stat-label">${t('store.fromageCirculation')}</div></div>
      <div class="stat-card"><div class="stat-icon">&#x1F4B3;</div><div class="stat-value">${stats.walletCount || 0}</div><div class="stat-label">${t('store.wallets')}</div></div>
    </div>
    <div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('store.playerWallets')} (${wallets.length})</span>
        <button class="btn-cg btn-cg-sm btn-cg-ghost" onclick="window._st.refresh()">${t('common.refresh')}</button>
      </div>
      <div class="section-card-body">
        ${wallets.length ? `<table class="cg-table"><thead><tr>
          <th>${t('common.player')}</th><th>${t('store.thBalance')}</th><th>${t('store.thLastUpdated')}</th><th>${t('common.actions')}</th>
        </tr></thead><tbody>${walletRows}</tbody></table>` :
        `<div class="empty-state"><span class="empty-state-icon">&#x1F9C0;</span><span class="empty-state-text">${t('common.noResults')}</span></div>`}
      </div>
    </div>`;
  }

  // ── Config Tab ───────────────────────────────────────────────────────
  if (st.tab === 'config') {
    const c = st.config;
    content = `<div class="section-card">
      <div class="section-card-header">
        <span class="section-card-title">${t('store.stripeConfig')}</span>
      </div>
      <div class="section-card-body" style="max-width:600px">
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.stripePublishableKey')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(c.stripePublishableKey || '')}"
            oninput="window._st.config.stripePublishableKey=this.value">
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.stripeSecretKey')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(c.stripeSecretKey || '')}"
            oninput="window._st.config.stripeSecretKey=this.value" type="password" autocomplete="off">
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.stripeWebhookSecret')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(c.stripeWebhookSecret || '')}"
            oninput="window._st.config.stripeWebhookSecret=this.value" type="password" autocomplete="off">
        </div>
        <div class="section-divider"></div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.storeName')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(c.storeName || '')}"
            oninput="window._st.config.storeName=this.value">
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.storeSubtitle')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(c.storeSubtitle || '')}"
            oninput="window._st.config.storeSubtitle=this.value">
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.storeBaseUrl')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(c.storeBaseUrl || '')}"
            oninput="window._st.config.storeBaseUrl=this.value" placeholder="${t('store.baseUrlPlaceholder')}">
        </div>
        <div class="mb-4">
          <label class="text-xs" style="color:var(--muted)">${t('store.currencyName')} ${tip('tip.store.currencyName')}</label>
          <input class="input input-sm input-bordered w-full" value="${escHtml(c.currencyName || '')}"
            oninput="window._st.config.currencyName=this.value" placeholder="fromage">
        </div>
        <div class="mb-4 flex items-center gap-3">
          <label class="text-xs" style="color:var(--muted)">${t('store.storeEnabled')}</label>
          <input type="checkbox" class="toggle toggle-sm" ${c.storeEnabled?'checked':''}
            onchange="window._st.config.storeEnabled=this.checked">
        </div>
        <div class="flex justify-end mt-4">
          <button class="btn-cg btn-cg-primary" onclick="window._st.saveConfig()">${t('common.save')}</button>
        </div>
      </div>
    </div>`;
  }

  return `<div class="max-w-6xl mx-auto slide-in">
    <div class="flex items-center gap-3 mb-6">
      <span style="font-size:1.6rem">&#x1F48E;</span>
      <div>
        <h2 style="font-size:var(--type-lg);font-weight:700;color:var(--text-primary)">${t('store.title')}</h2>
        <p style="color:var(--muted);font-size:var(--type-sm)">${t('store.subtitle')}</p>
      </div>
    </div>
    ${statCards}
    ${tabs}
    ${content}
  </div>`;
}
