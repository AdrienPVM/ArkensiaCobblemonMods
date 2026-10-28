/**
 * ArkensiaAdmin — Internationalization Engine
 * Bilingual FR/EN support for the CheeseDash dashboard.
 *
 * Usage:
 *   t('common.save')                        -> "Sauvegarder" or "Save"
 *   t('zones.created', {name: 'Arena'})     -> 'Zone "Arena" created'
 *   tip('tip.zones.priority')               -> tooltip HTML span
 *   I18N.setLang('en')                      -> switch + reload
 */

window.I18N = {
  _lang: localStorage.getItem('cg-lang') || 'fr',
  _data: {},

  /** Register a language pack */
  register(lang, data) {
    this._data[lang] = data;
  },

  /** Translate a key, with optional {param} interpolation */
  t(key, params) {
    let s = this._data[this._lang]?.[key] ?? this._data['fr']?.[key] ?? key;
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        s = s.replaceAll('{' + k + '}', v);
      }
    }
    return s;
  },

  /** Returns a tooltip (?) icon with translated help text. Optional extra CSS class (e.g. 'field-tip-down'). */
  tip(key, cls) {
    const text = this.t(key);
    if (!text || text === key) return '';
    return '<span class="field-tip' + (cls ? ' ' + cls : '') + '" data-tip="' +
      text.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;') +
      '">?</span>';
  },

  get lang() { return this._lang; },

  /** Switch language and reload the page */
  setLang(lang) {
    if (lang === this._lang) return;
    this._lang = lang;
    localStorage.setItem('cg-lang', lang);
    window.location.reload();
  },

  /** Format a date string according to current locale */
  fmtDate(iso) {
    if (!iso) return '\u2014';
    const locale = this._lang === 'fr' ? 'fr-FR' : 'en-US';
    try {
      return new Date(iso).toLocaleString(locale, {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit'
      });
    } catch { return iso; }
  },

  /** Format an epoch timestamp */
  fmtTs(epoch) {
    if (!epoch) return '\u2014';
    return this.fmtDate(new Date(epoch).toISOString());
  },

  /** Format uptime from milliseconds */
  fmtUptime(ms) {
    if (!ms || ms < 0) return '\u2014';
    const s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400);
    const h = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    if (this._lang === 'fr') {
      return (d ? d + 'j ' : '') + h + 'h' + (m ? String(m).padStart(2, '0') + 'min' : '');
    }
    return (d ? d + 'd ' : '') + h + 'h' + (m ? String(m).padStart(2, '0') + 'm' : '');
  }
};

/** Short alias — use everywhere in views */
function t(key, params) { return I18N.t(key, params); }

/** Short alias — tooltip helper */
function tip(key, cls) { return I18N.tip(key, cls); }
