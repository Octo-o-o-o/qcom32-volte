import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildReleaseAssetNames, selectRelease, RELEASE_TAG, MIRROR_BASE, REPOSITORY_URL, VERIFICATION_SCOPE } from '../src/site.js';
import worker from '../worker.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const dist = path.join(root, 'dist');

test('verification scope distinguishes calls, registration and unimplemented features', () => {
  assert.match(VERIFICATION_SCOPE.verifiedCalls, /46001/);
  assert.match(VERIFICATION_SCOPE.telecom, /one 46011 unit.*speakerphone.*silent earpiece/);
  assert.match(VERIFICATION_SCOPE.mobile, /untested/);
  assert.match(VERIFICATION_SCOPE.imsSms, /not implemented/);
  assert.match(VERIFICATION_SCOPE.emergencyCalls, /not supported/);
  assert.equal(VERIFICATION_SCOPE.oldReleaseHasEditionGate, false);
});

test('bilingual pages warn about unverified editions, EFS backup and old release gate', async () => {
  for (const rel of ['index.html', 'guide.html', 'en/index.html', 'en/guide.html']) {
    const html = await readFile(path.join(dist, rel), 'utf8');
    assert.match(html, /oslo/);
    assert.match(html, /46001/);
    assert.match(html, /46003/);
    assert.match(html, /149/);
    assert.match(html, /v1\.2\.4-r1/);
    assert.match(html, /v1\.2\.5-r1/);
    assert.ok(html.includes(RELEASE_TAG));
    assert.match(html, /oslo/);
    assert.match(html, /wichita/);
    assert.match(html, /keian/);
    assert.match(html, /wseries/);
    assert.match(html, /windermere/);
    assert.match(html, /modemst1/);
    assert.match(html, /modemst2/);
    assert.match(html, /fsg/);
    assert.doesNotMatch(html, /全系|all editions supported/i);
    for (const match of html.matchAll(/https:\/\/(?:qcom32-downloads\.octoooo\.com|github\.com\/Octo-o-o-o\/qcom32-volte\/releases\/download)\/([^"\s]+)/g)) {
      assert.ok(match[1].startsWith(`${RELEASE_TAG}/`), `stale download path in ${rel}`);
    }
    if (rel.startsWith('en/')) {
      assert.match(html, /still permit one-click writes/i);
      assert.ok(html.includes(`upgrade to ${RELEASE_TAG}`));
      assert.match(html, /not tests on a failing real device/i);
      assert.match(html, /not supported or certified/i);
      assert.match(html, /inspection log/i);
      assert.match(html, /speakerphone.*silent earpiece/i);
      assert.match(html, /SMS over IMS is not implemented/i);
    } else {
      assert.match(html, /仍允许对未经验证版本一键写入/);
      assert.ok(html.includes(`升级至 ${RELEASE_TAG}`));
      assert.match(html, /未在故障真机上验证/);
      assert.match(html, /紧急呼叫不支持/);
      assert.match(html, /检查日志/);
      assert.match(html, /免提.*听筒无声/);
      assert.match(html, /IMS 短信未实现/);
    }
  }
});

test('guides explain combined preflight and fixed rollback limitations', async () => {
  for (const rel of ['guide.html', 'en/guide.html']) {
    const html = await readFile(path.join(dist, rel), 'utf8');
    assert.match(html, /\.DI\.4\.0\.1-00158-M8974AAAAANAZM-1/);
    assert.match(html, /AAAAANAZM/);
    assert.match(html, /AAB982/);
    assert.match(html, /(?:离线\/模拟验证|offline or with mocks only)/);
    assert.match(html, /id="radio-diagnostics"/);
    assert.match(html, /prepare-oslo-rom --apply/);
  }
});

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
    assert.ok(html.includes(`${MIRROR_BASE}/${RELEASE_TAG}/qcom32-volte-1.2.8-r1-windows-x64.exe`));
    assert.ok(html.includes(`${REPOSITORY_URL}/releases/download/${RELEASE_TAG}/qcom32-volte-1.2.8-r1-windows-x64.exe`));
    assert.ok(html.includes(`${MIRROR_BASE}/${RELEASE_TAG}/Qcom32VolteIms-2.10-r1-platform.apk`));
    assert.ok(html.includes('oslo'));
    assert.ok(html.includes('imsqmidaemon'));
    assert.ok(html.includes('.agents/skills/qcom32-volte'));
  }
});

test('all bilingual pages enforce PolyForm-Noncommercial-1.0.0 + Commercial dual-licensing with zero stale Apache-2.0 claims', async () => {
  for (const rel of ['index.html', 'guide.html', 'en/index.html', 'en/guide.html']) {
    const html = await readFile(path.join(dist, rel), 'utf8');
    assert.equal(html.includes('Apache-2.0'), false, `stale Apache-2.0 in ${rel}`);
    assert.equal(html.includes('Apache License'), false, `stale Apache License in ${rel}`);
    assert.ok(html.includes('PolyForm'), `missing PolyForm license declaration in ${rel}`);
    assert.ok(html.includes('octoooo@octoooo.com'), `missing commercial license email in ${rel}`);
    assert.ok(html.includes('COMMERCIAL-LICENSE.md'), `missing COMMERCIAL-LICENSE.md link in ${rel}`);
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

test('selectRelease validates full v1.2.8-r1 asset set with IMS 2.10', () => {
  const names = buildReleaseAssetNames(RELEASE_TAG);
  assert.deepEqual(Object.values(names).sort(), [
    'qcom32-volte-1.2.8-r1-windows-x64.exe', 'qcom32-volte-1.2.8-r1-windows-x64.exe.sha256',
    'Qcom32VolteManager-1.2.8-r1.apk', 'Qcom32VolteManager-1.2.8-r1.apk.sha256',
    'Qcom32VolteIms-2.10-r1-platform.apk', 'Qcom32VolteIms-2.10-r1-platform.apk.sha256',
    'qcom32-volte-1.2.8-r1-portable.zip', 'qcom32-volte-1.2.8-r1-portable.zip.sha256',
    'qcom32-volte-1.2.8-r1-source.zip', 'qcom32-volte-1.2.8-r1-source.zip.sha256', 'SHA256SUMS.txt',
  ].sort());
  const mockReleases = [
    {
      tag_name: RELEASE_TAG,
      draft: false,
      published_at: '2026-11-15T12:00:00Z',
      assets: Object.values(names).map((name) => ({ name })),
    },
  ];
  const selected = selectRelease(mockReleases);
  assert.ok(selected);
  assert.equal(selected.tag, RELEASE_TAG);
  assert.equal(
    selected.urls.windows,
    `${REPOSITORY_URL}/releases/download/${RELEASE_TAG}/qcom32-volte-1.2.8-r1-windows-x64.exe`
  );
  assert.equal(
    selected.urls['rom-apk'],
    `${REPOSITORY_URL}/releases/download/${RELEASE_TAG}/Qcom32VolteIms-2.10-r1-platform.apk`
  );
});

test('both home pages include all 11 fallback assets on each mirror', async () => {
  for (const rel of ['index.html', 'en/index.html']) {
    const html = await readFile(path.join(dist, rel), 'utf8');
    for (const name of Object.values(buildReleaseAssetNames(RELEASE_TAG))) {
      for (const base of [MIRROR_BASE, `${REPOSITORY_URL}/releases/download`]) {
        assert.ok(html.includes(`${base}/${RELEASE_TAG}/${name}"`), `missing ${name} in ${rel}`);
      }
    }
  }
});
