import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildReleaseAssetNames, selectRelease, RELEASE_TAG, MIRROR_BASE, REPOSITORY_URL } from '../src/site.js';
import worker from '../worker.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const dist = path.join(root, 'dist');

function collectIds(html) {
  return new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));
}

test('dist contains all bilingual pages, security headers, and brand assets', async () => {
  const required = [
    'index.html',
    'guide.html',
    '404.html',
    'en/index.html',
    'en/guide.html',
    'en/404.html',
    '_headers',
    'robots.txt',
    'sitemap.xml',
    'assets/icon.png',
    'assets/app-icon.png',
    'assets/og-card.png',
  ];
  for (const rel of required) {
    const info = await stat(path.join(dist, rel));
    assert.equal(info.isFile(), true, `missing ${rel}`);
    assert.ok(info.size > 0, `empty ${rel}`);
  }
});

test('Chinese and English pages maintain symmetric section anchor IDs for language switcher', async () => {
  const zhHome = await readFile(path.join(dist, 'index.html'), 'utf8');
  const enHome = await readFile(path.join(dist, 'en/index.html'), 'utf8');
  assert.deepEqual(collectIds(zhHome), collectIds(enHome));

  const zhGuide = await readFile(path.join(dist, 'guide.html'), 'utf8');
  const enGuide = await readFile(path.join(dist, 'en/guide.html'), 'utf8');
  assert.deepEqual(collectIds(zhGuide), collectIds(enGuide));
});

test('pages use canonical qcom32-volte.octoooo.com domain and cross-link bb10-volte', async () => {
  for (const rel of ['index.html', 'guide.html', '404.html', 'en/index.html', 'en/guide.html', 'en/404.html', 'robots.txt', 'sitemap.xml']) {
    const content = await readFile(path.join(dist, rel), 'utf8');
    assert.equal(content.includes('passport-volte.octoooo.com'), false, `stale legacy domain in ${rel}`);
    assert.equal(content.includes('passport-android11-volte.octoooo.com'), false, `stale intermediate domain in ${rel}`);
    assert.equal(content.includes('passport-downloads.octoooo.com'), false, `stale legacy mirror in ${rel}`);
    assert.equal(content.includes('Octo-o-o-o/passport-android11-volte'), false, `stale legacy repo in ${rel}`);
  }
  for (const rel of ['index.html', 'en/index.html']) {
    const html = await readFile(path.join(dist, rel), 'utf8');
    assert.match(html, /https:\/\/qcom32-volte\.octoooo\.com\//);
    assert.match(html, /https:\/\/bb10-volte\.octoooo\.com\//);
    assert.match(html, /https:\/\/github\.com\/Octo-o-o-o\/bb10-volte/);
    assert.ok(html.includes(`${MIRROR_BASE}/${RELEASE_TAG}/qcom32-volte-1.2.0-r1-windows-x64.exe`));
    assert.ok(html.includes(`${REPOSITORY_URL}/releases/download/${RELEASE_TAG}/qcom32-volte-1.2.0-r1-windows-x64.exe`));
  }
});

test('worker 301-redirects legacy passport-volte.octoooo.com to qcom32-volte.octoooo.com', async () => {
  const redirectRes = await worker.fetch(new Request('https://passport-volte.octoooo.com/en/guide?from=old'), {
    ASSETS: { fetch: async () => new Response('unexpected') },
  });
  assert.equal(redirectRes.status, 301);
  assert.equal(redirectRes.headers.get('location'), 'https://qcom32-volte.octoooo.com/en/guide?from=old');

  const canonicalRes = await worker.fetch(new Request('https://qcom32-volte.octoooo.com/guide'), {
    ASSETS: { fetch: async (req) => new Response(`ok:${new URL(req.url).pathname}`, { status: 200 }) },
  });
  assert.equal(canonicalRes.status, 200);
  assert.equal(await canonicalRes.text(), 'ok:/guide');
});

test('selectRelease validates full v1.2.0-r1 asset set', () => {
  const names = buildReleaseAssetNames(RELEASE_TAG);
  const mockReleases = [
    {
      tag_name: RELEASE_TAG,
      draft: false,
      published_at: '2026-11-14T15:00:00Z',
      assets: Object.values(names).map((name) => ({ name })),
    },
  ];
  const selected = selectRelease(mockReleases);
  assert.ok(selected);
  assert.equal(selected.tag, RELEASE_TAG);
  assert.equal(
    selected.urls.windows,
    `${REPOSITORY_URL}/releases/download/${RELEASE_TAG}/qcom32-volte-1.2.0-r1-windows-x64.exe`
  );
});
