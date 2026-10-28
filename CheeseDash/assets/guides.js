/**
 * ArkensiaAdmin — Module Guide System
 * Renders structured documentation/guide pages for each dashboard module.
 * All text uses i18n keys from lang-fr.js / lang-en.js.
 *
 * Usage:
 *   buildGuideTab('zones')  → returns HTML string for the guide tab content
 *   GUIDE_MODULES           → list of module IDs that have guides
 */

// ── Module guide definitions ─────────────────────────────────────────────────
// Each guide is an array of sections. Each section has:
//   { titleKey, sections: [{ subtitleKey?, contentKey?, list?: [key,...], note?: key, table?: {headersKey, rowKeys} }] }

const GUIDE_DEFS = {
  overview: {
    icon: '⬡',
    sections: [
      { key: 'guide.overview.what' },
      { key: 'guide.overview.statCards' },
      { key: 'guide.overview.activity' },
      { key: 'guide.overview.quickActions' },
    ]
  },
  zones: {
    icon: '⬜',
    sections: [
      { key: 'guide.zones.what' },
      { key: 'guide.zones.concepts' },
      { key: 'guide.zones.create' },
      { key: 'guide.zones.flags' },
      { key: 'guide.zones.members' },
      { key: 'guide.zones.groups' },
      { key: 'guide.zones.folders' },
      { key: 'guide.zones.priority' },
      { key: 'guide.zones.batch' },
      { key: 'guide.zones.tags' },
      { key: 'guide.zones.tagconfig' },
      { key: 'guide.zones.states' },
      { key: 'guide.zones.sale' },
    ]
  },
  dimensions: {
    icon: '🌀',
    sections: [
      { key: 'guide.dims.what' },
      { key: 'guide.dims.types' },
      { key: 'guide.dims.create' },
      { key: 'guide.dims.border' },
      { key: 'guide.dims.flags' },
      { key: 'guide.dims.snapshots' },
      { key: 'guide.dims.perPlayer' },
      { key: 'guide.dims.clearinv' },
      { key: 'guide.dims.commands' },
      { key: 'guide.dims.raidDens' },
      { key: 'guide.dims.access' },
    ]
  },
  events: {
    icon: '🎯',
    sections: [
      { key: 'guide.events.what' },
      { key: 'guide.events.modes' },
      { key: 'guide.events.lifecycle' },
      { key: 'guide.events.timeline' },
      { key: 'guide.events.participants' },
      { key: 'guide.events.templates' },
      { key: 'guide.events.playerLaunch' },
      { key: 'guide.events.effects' },
      { key: 'guide.events.minigameOverview' },
      { key: 'guide.events.racetrack' },
      { key: 'guide.events.mgThimble' },
      { key: 'guide.events.mgSpleef' },
      { key: 'guide.events.mgPropHunt' },
      { key: 'guide.events.mgCustom' },
      { key: 'guide.events.mgDeathmatch' },
      { key: 'guide.events.mgParkour' },
      { key: 'guide.events.mgKingOfHill' },
      { key: 'guide.events.mgFishingContest' },
    ]
  },
  players: {
    icon: '👥',
    sections: [
      { key: 'guide.players.what' },
      { key: 'guide.players.actions' },
      { key: 'guide.players.details' },
    ]
  },
  prison: {
    icon: '⛓',
    sections: [
      { key: 'guide.prison.overview' },
      { key: 'guide.prison.setup' },
      { key: 'guide.prison.imprison' },
      { key: 'guide.prison.behaviors' },
      { key: 'guide.prison.history' },
    ]
  },
  chunks: {
    icon: '🗺',
    sections: [
      { key: 'guide.chunks.what' },
      { key: 'guide.chunks.sets' },
      { key: 'guide.chunks.schedule' },
      { key: 'guide.chunks.suggestions' },
    ]
  },
  server: {
    icon: '⚙',
    sections: [
      { key: 'guide.server.what' },
      { key: 'guide.server.stats' },
      { key: 'guide.server.schedule' },
      { key: 'guide.server.console' },
      { key: 'guide.server.logs' },
      { key: 'guide.server.mods' },
      { key: 'guide.server.dangerZone' },
    ]
  },
  market: {
    icon: '🛒',
    sections: [
      { key: 'guide.market.what' },
      { key: 'guide.market.shops' },
      { key: 'guide.market.sets' },
      { key: 'guide.market.items' },
      { key: 'guide.market.csv' },
      { key: 'guide.market.layout' },
      { key: 'guide.market.transactions' },
    ]
  },
  eggs: {
    icon: '🥚',
    sections: [
      { key: 'guide.eggs.what' },
      { key: 'guide.eggs.create' },
      { key: 'guide.eggs.dna' },
      { key: 'guide.eggs.emissive' },
      { key: 'guide.eggs.preview3d' },
      { key: 'guide.eggs.discoveries' },
      { key: 'guide.eggs.ftbQuest' },
      { key: 'guide.eggs.config' },
    ]
  },
  scores: {
    icon: '🏆',
    sections: [
      { key: 'guide.scores.what' },
      { key: 'guide.scores.boards' },
      { key: 'guide.scores.images' },
      { key: 'guide.scores.config' },
      { key: 'guide.scores.minigames' },
      { key: 'guide.scores.sessions' },
    ]
  },
  shift: {
    icon: '✦',
    sections: [
      { key: 'guide.shift.what' },
      { key: 'guide.shift.transforms' },
      { key: 'guide.shift.abilities' },
      { key: 'guide.shift.species' },
      { key: 'guide.shift.hotpotato' },
      { key: 'guide.shift.config' },
    ]
  },
  dungeons: {
    icon: '🏰',
    sections: [
      { key: 'guide.dungeons.what' },
      { key: 'guide.dungeons.configs' },
      { key: 'guide.dungeons.tiers' },
      { key: 'guide.dungeons.loot' },
      { key: 'guide.dungeons.trainers' },
      { key: 'guide.dungeons.tickets' },
      { key: 'guide.dungeons.active' },
      { key: 'guide.dungeons.history' },
      { key: 'guide.dungeons.leaderboard' },
    ]
  },
  items: {
    icon: '🎁',
    sections: [
      { key: 'guide.items.what' },
      { key: 'guide.items.create' },
      { key: 'guide.items.flags' },
      { key: 'guide.items.effects' },
      { key: 'guide.items.conditions' },
      { key: 'guide.items.give' },
    ]
  },
  kart: {
    icon: '🏎',
    sections: [
      { key: 'guide.kart.what' },
      { key: 'guide.kart.presets' },
      { key: 'guide.kart.sessions' },
      { key: 'guide.kart.params' },
    ]
  },
  patrol: {
    icon: '🚶',
    sections: [
      { key: 'guide.patrol.what' },
      { key: 'guide.patrol.setup' },
      { key: 'guide.patrol.commands' },
      { key: 'guide.patrol.particles' },
      { key: 'guide.patrol.dashboard' },
    ]
  },
  store: {
    icon: '💎',
    sections: [
      { key: 'guide.store.what' },
      { key: 'guide.store.products' },
      { key: 'guide.store.orders' },
      { key: 'guide.store.wallets' },
      { key: 'guide.store.config' },
    ]
  },
  webhooks: {
    icon: '🔔',
    sections: [
      { key: 'guide.webhooks.what' },
      { key: 'guide.webhooks.create' },
      { key: 'guide.webhooks.events' },
      { key: 'guide.webhooks.advanced' },
    ]
  },
  accounts: {
    icon: '👤',
    sections: [
      { key: 'guide.accounts.what' },
      { key: 'guide.accounts.roles' },
      { key: 'guide.accounts.sessions' },
      { key: 'guide.accounts.tokenRegen' },
    ]
  },
  audit: {
    icon: '📋',
    sections: [
      { key: 'guide.audit.what' },
      { key: 'guide.audit.entries' },
      { key: 'guide.audit.filters' },
      { key: 'guide.audit.realtime' },
    ]
  },
  cobblecap: {
    icon: '🏅',
    sections: [
      { key: 'guide.cobblecap.overview' },
      { key: 'guide.cobblecap.players' },
      { key: 'guide.cobblecap.explock' },
      { key: 'guide.cobblecap.champions' },
      { key: 'guide.cobblecap.config' },
      { key: 'guide.cobblecap.stats' },
    ]
  },
  music: {
    icon: '🎵',
    sections: [
      { key: 'guide.music.overview' },
      { key: 'guide.music.setup' },
      { key: 'guide.music.modes' },
      { key: 'guide.music.fade' },
    ]
  },
};

const GUIDE_MODULES = Object.keys(GUIDE_DEFS);

/**
 * Render a guide section. Each section key resolves to:
 *   guide.xxx.title  → section heading
 *   guide.xxx.body   → paragraph(s), supports \n for line breaks
 *   guide.xxx.list   → optional bullet list (array joined by |)
 *   guide.xxx.note   → optional callout/tip box
 */
function _guideSection(key) {
  const title = t(key + '.title');
  const body = t(key + '.body');
  const listRaw = t(key + '.list');
  const note = t(key + '.note');

  // Skip if no translation found
  if (title === key + '.title' && body === key + '.body') return '';

  let html = `<div class="guide-section">`;
  if (title !== key + '.title') {
    html += `<h3 class="guide-subtitle">${escHtml(title)}</h3>`;
  }
  if (body !== key + '.body') {
    // Support multiple paragraphs separated by \n\n
    const paragraphs = body.split('\n\n');
    for (const p of paragraphs) {
      html += `<p class="guide-text">${escHtml(p).replace(/\n/g, '<br>')}</p>`;
    }
  }
  if (listRaw !== key + '.list') {
    const items = listRaw.split('|');
    html += `<ul class="guide-list">`;
    for (const item of items) {
      if (item.trim()) {
        // Support bold prefix with ** markers
        const formatted = escHtml(item.trim()).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        html += `<li>${formatted}</li>`;
      }
    }
    html += `</ul>`;
  }
  if (note !== key + '.note') {
    html += `<div class="guide-note"><span class="guide-note-icon">💡</span><span>${escHtml(note)}</span></div>`;
  }
  html += `</div>`;
  return html;
}

/**
 * Build the full guide HTML for a module.
 * @param {string} moduleId - The module ID (e.g. 'zones', 'market')
 * @returns {string} HTML string
 */
function buildGuideTab(moduleId) {
  const def = GUIDE_DEFS[moduleId];
  if (!def) return `<div class="empty-state"><span class="empty-state-icon">📖</span><span class="empty-state-text">${t('guide.unavailable')}</span></div>`;

  const moduleTitle = t('guide.' + moduleId + '.title');
  const moduleDesc = t('guide.' + moduleId + '.desc');

  let html = `<div class="guide-container fade-in">`;

  // Header
  html += `<div class="guide-header">
    <span class="guide-header-icon">${def.icon}</span>
    <div>
      <h2 class="guide-title">${moduleTitle !== 'guide.' + moduleId + '.title' ? escHtml(moduleTitle) : escHtml(moduleId)}</h2>
      ${moduleDesc !== 'guide.' + moduleId + '.desc' ? `<p class="guide-desc">${escHtml(moduleDesc)}</p>` : ''}
    </div>
  </div>`;

  // Table of contents
  const tocItems = def.sections.map(s => {
    const title = t(s.key + '.title');
    return title !== s.key + '.title' ? { key: s.key, title } : null;
  }).filter(Boolean);

  if (tocItems.length > 3) {
    html += `<div class="guide-toc">
      <span class="guide-toc-label">${t('guide.toc')}</span>
      <div class="guide-toc-items">
        ${tocItems.map((item, i) => `<a class="guide-toc-link" href="#" onclick="event.preventDefault();document.querySelector('[data-guide-section=&quot;${item.key}&quot;]')?.scrollIntoView({behavior:'smooth',block:'start'})">${i + 1}. ${escHtml(item.title)}</a>`).join('')}
      </div>
    </div>`;
  }

  // Sections
  for (const section of def.sections) {
    html += `<div data-guide-section="${section.key}">`;
    html += _guideSection(section.key);
    html += `</div>`;
  }

  html += `</div>`;
  return html;
}

/**
 * Helper: returns a guide tab panel <div> for use inside any view.
 * @param {string} moduleId - The module ID
 * @param {string} prefix - The tab prefix used by switchDetailTab (e.g. 'zv', 'dd')
 * @returns {string} HTML string for a hidden tab panel containing the guide
 */
function guideTabPanel(moduleId, prefix) {
  return `<div data-tab-panel-${prefix}="guide" style="display:none">${buildGuideTab(moduleId)}</div>`;
}

/**
 * Helper: returns a guide tab entry { id: 'guide', label: '📖 Guide' }
 * to be appended to any tab array.
 */
function guideTabEntry() {
  return { id: 'guide', label: '\u{1F4D6} ' + t('guide.tabLabel') };
}

/**
 * Helper: returns a guide button + modal dialog for views without tab bars.
 * Place in a section-card-header or toolbar area.
 */
function guideButton(moduleId) {
  const dlgId = 'guide-dlg-' + moduleId;
  return `<button class="btn-cg btn-cg-ghost btn-cg-sm" onclick="document.getElementById('${dlgId}').showModal()">\u{1F4D6} ${t('guide.tabLabel')}</button>
    <dialog id="${dlgId}" class="modal"><div class="modal-box max-w-2xl" style="max-height:80vh;overflow-y:auto">
      <button class="modal-close" onclick="this.closest('dialog').close()">\u2715</button>
      ${buildGuideTab(moduleId)}
    </div><form method="dialog" class="modal-backdrop"><button>close</button></form></dialog>`;
}
