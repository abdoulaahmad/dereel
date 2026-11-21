// Instagram web rules. Version must match BrowserScripts.ruleVersion.
// Coverage and verification status: docs/COMPATIBILITY.md. Text labels are English-only (D-06).
var DEREEL_RULES = {
  version: 'ig-web-0.1.0',

  routeOf: function (path) {
    if (path === '/' || path === '') return 'home';
    if (/^\/reels?(\/|$)/.test(path)) return 'reels';
    if (/^\/direct\/t\//.test(path)) return 'thread';
    if (/^\/direct(\/|$)/.test(path)) return 'inbox';
    if (/^\/explore\/?$/.test(path)) return 'explore';
    if (/^\/explore\//.test(path)) return 'search';
    return 'other';
  },

  // Short-video routes. The native navigation guard enforces the same list (WebRoutes.kt).
  blockedPath: function (path) {
    return /^\/reels?(\/|$)/.test(path) || /^\/[A-Za-z0-9._]{1,30}\/reel\//.test(path);
  },

  // A reel tapped inside a conversation is left to Dereel's shared-video check (FR-20).
  allowSharedTap: function (route, cfg) {
    return route === 'thread' && cfg.sharedAllowed === true;
  },

  css: function (cfg) {
    var css = '';
    if (cfg.denied) {
      // Reels tab and feed entry points. Individual shared reels in messages stay visible.
      css += 'a[href="/reels/"],a[href="/reels"]{display:none!important}';
    }
    if (cfg.controls && cfg.controls.hideExplore) {
      css += 'html[data-dereel-route="explore"] main a[href^="/p/"],' +
        'html[data-dereel-route="explore"] main a[href^="/reel/"]{display:none!important}' +
        'html[data-dereel-route="explore"] main::after{content:"Explore recommendations are hidden by Dereel. Search still works.";' +
        'display:block;padding:32px 16px;text-align:center;color:#737373;font:14px/1.4 system-ui,sans-serif}';
    }
    return css;
  },

  scan: function (body, cfg) {
    var controls = cfg.controls || {};
    if (!controls.hideSuggested && !controls.filterSponsored) return;
    var suggested = ['Suggested for you', 'Suggested posts', 'Suggested reels'];
    var sponsored = ['Sponsored', 'Ad'];
    var articles = body.querySelectorAll('article:not([data-dereel-hidden])');
    for (var i = 0; i < articles.length; i++) {
      var article = articles[i];
      var labels = article.querySelectorAll('span, a, div[role="button"]');
      var limit = Math.min(labels.length, 40);
      for (var j = 0; j < limit; j++) {
        var text = (labels[j].textContent || '').trim();
        if (text.length > 24) continue;
        if ((controls.hideSuggested && suggested.indexOf(text) >= 0) ||
            (controls.filterSponsored && sponsored.indexOf(text) >= 0)) {
          article.setAttribute('data-dereel-hidden', '1');
          article.style.setProperty('display', 'none', 'important');
          break;
        }
      }
    }
  }
};
