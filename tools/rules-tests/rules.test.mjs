// Regression tests for the bundled page rules (FR-39). No dependencies: run with
//   node --test tools/rules-tests/rules.test.mjs
// The route fixtures are shared with the Kotlin classifier tests (core RouteFixturesTest).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const fixtures = JSON.parse(readFileSync(path.join(root, 'core/src/test/resources/route-fixtures.json'), 'utf8'));

function loadRules(name) {
  const source = readFileSync(path.join(root, 'app/src/main/assets/rules', name), 'utf8');
  const context = {};
  vm.runInNewContext(source, context);
  return context.DEREEL_RULES;
}

const rules = { instagram: loadRules('instagram.js'), youtube: loadRules('youtube.js'), facebook: loadRules('facebook.js') };
const allOn = { hideExplore: true, hideSuggested: true, filterSponsored: true, hideHomeFeed: true, hideWatchNext: true, filterPromoted: true };

for (const platform of ['instagram', 'youtube', 'facebook']) {
  test(`${platform}: page rules agree with the native route classifier`, () => {
    for (const { path: p, search = '', shortVideo } of fixtures[platform]) {
      assert.equal(rules[platform].blockedPath(p, search), shortVideo, `${platform} ${p}${search}`);
    }
  });

  test(`${platform}: nothing is hidden when short videos are allowed and controls are off`, () => {
    assert.equal(rules[platform].css({ v: 1, denied: false, sharedAllowed: false, controls: {} }), '');
  });

  test(`${platform}: denial adds entry-point hiding`, () => {
    const css = rules[platform].css({ v: 1, denied: true, sharedAllowed: false, controls: {} });
    assert.ok(css.includes('display:none!important'));
  });
}

test('instagram: shared reels inside a conversation are left to the native exception check', () => {
  const ig = rules.instagram;
  assert.equal(ig.routeOf('/direct/t/123/'), 'thread');
  assert.equal(ig.allowSharedTap('thread', { sharedAllowed: true }), true);
  assert.equal(ig.allowSharedTap('thread', { sharedAllowed: false }), false);
  assert.equal(ig.allowSharedTap('home', { sharedAllowed: true }), false);
  // Denied CSS hides the Reels tab but never individual reel links, which appear in messages.
  const css = ig.css({ v: 1, denied: true, sharedAllowed: true, controls: {} });
  assert.ok(css.includes('a[href="/reels/"]'));
  assert.ok(!css.includes('a[href^="/reel/"]'));
});

test('facebook: reel and video shortcuts are hidden but single reel links are left to the tap check', () => {
  const fb = rules.facebook;
  const css = fb.css({ v: 1, denied: true, sharedAllowed: false, controls: {} });
  assert.ok(css.includes('a[href="/reel/"]'));
  assert.ok(css.includes('a[href="/watch/"]'));
  // A link to one reel inside a post stays in the page. Tapping it is stopped instead.
  assert.ok(!css.includes('a[href^="/reel/"]'));
  assert.equal(fb.routeOf('/reel/1234567890123456/', ''), 'reels');
  assert.equal(fb.routeOf('/watch/', ''), 'reels');
  assert.equal(fb.routeOf('/watch/', '?v=987654321012345'), 'other');
  assert.equal(fb.routeOf('/', ''), 'home');
  // Facebook has no shared-video exception, so no tap is ever waved through.
  assert.equal(fb.allowSharedTap, undefined);
});

test('instagram: explore hiding keeps search and is scoped to the explore route', () => {
  const css = rules.instagram.css({ v: 1, denied: false, controls: { hideExplore: true } });
  assert.ok(css.includes('html[data-dereel-route="explore"]'));
  assert.equal(rules.instagram.routeOf('/explore/search/'), 'search');
});

test('youtube: recommendation controls are scoped to their routes', () => {
  const css = rules.youtube.css({ v: 1, denied: false, controls: allOn });
  assert.ok(css.includes('html[data-dereel-route="home"]'));
  assert.ok(css.includes('html[data-dereel-route="watch"]'));
  assert.equal(rules.youtube.routeOf('/watch'), 'watch');
});

test('instagram: suggested and sponsored scanning hides only matching articles', () => {
  const makeArticle = (labels) => {
    const article = {
      attrs: {}, hidden: false,
      setAttribute(k, v) { this.attrs[k] = v; },
      style: { setProperty(k, v) { if (k === 'display' && v === 'none') article.hidden = true; } },
      querySelectorAll() { return labels.map((t) => ({ textContent: t })); },
    };
    return article;
  };
  const suggested = makeArticle(['someone', 'Suggested for you']);
  const sponsored = makeArticle(['brand', 'Sponsored']);
  const normal = makeArticle(['friend', 'Liked by others and 3 more', 'A long caption that mentions Sponsored content in passing']);
  const body = { querySelectorAll: () => [suggested, sponsored, normal] };
  rules.instagram.scan(body, { controls: { hideSuggested: true, filterSponsored: false } });
  assert.equal(suggested.hidden, true);
  assert.equal(sponsored.hidden, false);
  assert.equal(normal.hidden, false);
  rules.instagram.scan(body, { controls: { hideSuggested: true, filterSponsored: true } });
  assert.equal(sponsored.hidden, true);
  assert.equal(normal.hidden, false);
});
