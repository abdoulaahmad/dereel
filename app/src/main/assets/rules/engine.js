// Dereel page-rule engine. Bundled with the app and never downloaded (FR-39).
// It runs only in the top-level frame of the platform's own origins (SEC-04).
// Expects DEREEL_CONFIG and DEREEL_RULES from the surrounding closure.
// Everything stays inside the page: no page text, URLs or content is sent to Dereel. The only
// messages are "rules ready" and "a blocked entry point was tapped".
if (window.top !== window) return;
if (window.__dereel) { window.__dereel.apply(DEREEL_CONFIG); return; }

var cfg = DEREEL_CONFIG;
var styleEl = null;
var scanQueued = false;

function post(msg) {
  try {
    if (window.dereelBridge && typeof window.dereelBridge.postMessage === 'function') {
      window.dereelBridge.postMessage(JSON.stringify(msg));
    }
  } catch (e) { /* bridge unavailable: native navigation checks still apply */ }
}

function currentRoute() {
  try { return DEREEL_RULES.routeOf(location.pathname || '/', location.search || ''); } catch (e) { return 'other'; }
}

function applyRouteAttr() {
  var root = document.documentElement;
  if (!root) return;
  var route = currentRoute();
  if (root.getAttribute('data-dereel-route') !== route) root.setAttribute('data-dereel-route', route);
}

function ensureStyle() {
  var root = document.documentElement;
  if (!root) return;
  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = 'dereel-rules';
  }
  var css = DEREEL_RULES.css(cfg);
  if (styleEl.textContent !== css) styleEl.textContent = css;
  if (!styleEl.isConnected) (document.head || root).appendChild(styleEl);
}

function scan() {
  scanQueued = false;
  ensureStyle();
  applyRouteAttr();
  if (DEREEL_RULES.scan && document.body) {
    try { DEREEL_RULES.scan(document.body, cfg); } catch (e) { /* keep page usable */ }
  }
}

function queueScan() {
  if (scanQueued) return;
  scanQueued = true;
  if (typeof requestAnimationFrame === 'function') requestAnimationFrame(scan); else setTimeout(scan, 16);
}

// In-page route changes (FR-13).
['pushState', 'replaceState'].forEach(function (name) {
  var original = history[name];
  if (typeof original !== 'function') return;
  history[name] = function () {
    var result = original.apply(this, arguments);
    queueScan();
    return result;
  };
});
window.addEventListener('popstate', queueScan, true);

// Blocked entry points are stopped before the page navigates (FR-15).
document.addEventListener('click', function (event) {
  if (!cfg.denied) return;
  var target = event.target;
  var link = target && target.closest ? target.closest('a[href]') : null;
  if (!link) return;
  var url;
  try { url = new URL(link.getAttribute('href'), location.href); } catch (e) { return; }
  if (url.origin !== location.origin) return;
  if (!DEREEL_RULES.blockedPath(url.pathname, url.search)) return;
  if (DEREEL_RULES.allowSharedTap && DEREEL_RULES.allowSharedTap(currentRoute(), cfg)) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  post({ v: 1, type: 'blockedEntry' });
}, true);

var observer = new MutationObserver(queueScan);
function start() {
  if (!document.documentElement) return false;
  observer.observe(document.documentElement, { childList: true, subtree: true });
  scan();
  return true;
}
if (!start()) {
  var waiter = new MutationObserver(function () { if (start()) waiter.disconnect(); });
  waiter.observe(document, { childList: true });
}

Object.defineProperty(window, '__dereel', {
  value: Object.freeze({
    apply: function (next) {
      if (next && typeof next === 'object' && next.v === 1) { cfg = next; queueScan(); }
    }
  }),
  configurable: false,
  writable: false
});

post({ v: 1, type: 'ready', rules: DEREEL_RULES.version });
