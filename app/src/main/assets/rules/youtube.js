// YouTube mobile web (m.youtube.com) rules. Version must match BrowserScripts.ruleVersion.
// Coverage and verification status: docs/COMPATIBILITY.md.
var DEREEL_RULES = {
  version: 'yt-web-0.1.0',

  routeOf: function (path) {
    if (path === '/' || path === '') return 'home';
    if (/^\/shorts(\/|$)/.test(path)) return 'shorts';
    if (/^\/watch/.test(path)) return 'watch';
    if (/^\/results/.test(path)) return 'search';
    return 'other';
  },

  blockedPath: function (path) {
    return /^\/shorts(\/|$)/.test(path);
  },

  css: function (cfg) {
    var css = '';
    var controls = cfg.controls || {};
    if (cfg.denied) {
      css += [
        'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
        'ytm-reel-shelf-renderer',
        'ytm-shorts-lockup-view-model',
        'ytm-shorts-lockup-view-model-v2',
        'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)',
        'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)',
        'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model-v2)',
        'grid-shelf-view-model:has(ytm-shorts-lockup-view-model)',
        'grid-shelf-view-model:has(ytm-shorts-lockup-view-model-v2)',
        'ytm-video-with-context-renderer:has(a[href^="/shorts/"])',
        'ytm-rich-item-renderer:has(a[href^="/shorts/"])',
        'ytm-compact-video-renderer:has(a[href^="/shorts/"])'
      ].join(',') + '{display:none!important}';
    }
    if (controls.hideHomeFeed) {
      css += 'html[data-dereel-route="home"] ytm-browse ytm-rich-grid-renderer,' +
        'html[data-dereel-route="home"] ytm-browse ytm-section-list-renderer,' +
        'html[data-dereel-route="home"] ytm-browse ytm-feed-filter-chip-bar-renderer{display:none!important}' +
        'html[data-dereel-route="home"] ytm-browse::before{content:"Home recommendations are hidden by Dereel. Search, Subscriptions and channels still work.";' +
        'display:block;padding:32px 16px;text-align:center;color:#606060;font:14px/1.4 Roboto,Arial,sans-serif}';
    }
    if (controls.hideWatchNext) {
      css += [
        'html[data-dereel-route="watch"] ytm-item-section-renderer[section-identifier="related-items"]',
        'html[data-dereel-route="watch"] ytm-watch-next-secondary-results-renderer',
        'html[data-dereel-route="watch"] ytm-single-column-watch-next-results-renderer ytm-item-section-renderer[data-content-type="related"]',
        'html[data-dereel-route="watch"] .ytp-endscreen-content',
        'html[data-dereel-route="watch"] .ytp-autonav-endscreen-upnext-container',
        'html[data-dereel-route="watch"] ytm-autonav-bar'
      ].join(',') + '{display:none!important}';
    }
    if (controls.filterPromoted) {
      // Best effort: promoted feed and search items only. Video ads in the player are not removed.
      css += [
        'ytm-promoted-sparkles-web-renderer',
        'ytm-promoted-video-renderer',
        'ytm-promoted-sparkles-text-search-renderer',
        'ytm-companion-ad-renderer',
        'ad-slot-renderer',
        'ytm-ad-slot-renderer',
        'ytm-rich-item-renderer:has(ad-slot-renderer)',
        'ytm-rich-section-renderer:has(ad-slot-renderer)',
        'ytm-item-section-renderer:has(ad-slot-renderer)'
      ].join(',') + '{display:none!important}';
    }
    return css;
  }
};
