// Facebook mobile web (m.facebook.com) rules. Version must match BrowserScripts.ruleVersion.
// Coverage and verification status: docs/COMPATIBILITY.md.
var DEREEL_RULES = {
  version: 'fb-web-0.1.0',

  routeOf: function (path, search) {
    if (path === '/' || path === '' || /^\/home\.php$/i.test(path)) return 'home';
    if (DEREEL_RULES.blockedPath(path, search)) return 'reels';
    return 'other';
  },

  // Short-video routes. The native navigation guard enforces the same list (WebRoutes.kt):
  // reel pages, the Reels feed, reel share links, and the Video tab unless it names one video.
  blockedPath: function (path, search) {
    if (/^\/reels?(\/|$)/i.test(path)) return true;
    if (/^\/share\/r(\/|$)/i.test(path)) return true;
    if (/^\/watch(\/|$)/i.test(path)) {
      if (/^\/watch\/search(\/|$)/i.test(path)) return false;
      return !/[?&]v=\d{5,25}(&|$)/.test(search || '');
    }
    return false;
  },

  css: function (cfg) {
    if (!cfg.denied) return '';
    // Shortcuts to the Reels feed and the Video tab. A link to one reel stays visible and is
    // stopped when tapped, so posts in the feed are not removed.
    return [
      'a[href="/reel/"]',
      'a[href^="/reel/?"]',
      'a[href="/reels/"]',
      'a[href^="/reels/?"]',
      'a[href="/watch/"]',
      'a[href="/watch"]',
      'a[href^="/watch/?ref"]'
    ].join(',') + '{display:none!important}';
  }
};
