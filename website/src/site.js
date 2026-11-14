export const REPOSITORY_URL = 'https://github.com/Octo-o-o-o/qcom32-volte';
export const SISTER_REPOSITORY_URL = 'https://github.com/Octo-o-o-o/bb10-volte';
export const RELEASE_TAG = 'v1.1.0-r1';
export const MIRROR_BASE = 'https://qcom32-downloads.octoooo.com';
export const RELEASE_KEYS = [
  'windows',
  'windows-sha',
  'manager',
  'manager-sha',
  'portable',
  'portable-sha',
  'rom-apk',
  'rom-apk-sha',
  'source',
  'source-sha',
];

export function buildReleaseAssetNames(tag) {
  const ver = tag.replace(/^v/, '');
  const revMatch = ver.match(/-r(\d+)$/);
  const rev = revMatch ? revMatch[1] : '1';
  return {
    windows: `qcom32-volte-${ver}-windows-x64.exe`,
    'windows-sha': `qcom32-volte-${ver}-windows-x64.exe.sha256`,
    manager: `Qcom32VolteManager-${ver}.apk`,
    'manager-sha': `Qcom32VolteManager-${ver}.apk.sha256`,
    portable: `qcom32-volte-${ver}-portable.zip`,
    'portable-sha': `qcom32-volte-${ver}-portable.zip.sha256`,
    'rom-apk': `Qcom32VolteIms-2.4-r${rev}-platform.apk`,
    'rom-apk-sha': `Qcom32VolteIms-2.4-r${rev}-platform.apk.sha256`,
    source: `qcom32-volte-${ver}-source.zip`,
    'source-sha': `qcom32-volte-${ver}-source.zip.sha256`,
    checksums: 'SHA256SUMS.txt',
  };
}

export function selectRelease(releases) {
  if (!Array.isArray(releases)) return null;
  const candidates = releases
    .filter((r) => r && r.draft === false && /^v\d+\.\d+\.\d+-r\d+$/.test(r.tag_name))
    .sort((a, b) => Date.parse(b.published_at || b.created_at || 0) - Date.parse(a.published_at || a.created_at || 0));

  for (const release of candidates) {
    if (!Array.isArray(release.assets)) continue;
    const tag = release.tag_name;
    const names = buildReleaseAssetNames(tag);
    const available = new Set(release.assets.map((asset) => asset?.name));
    if (!Object.values(names).every((n) => available.has(n))) continue;
    const ghBase = `${REPOSITORY_URL}/releases/download/${tag}`;
    const urls = Object.fromEntries(Object.entries(names).map(([k, n]) => [k, `${ghBase}/${n}`]));
    return { tag, version: tag.slice(1), page: `${REPOSITORY_URL}/releases/tag/${tag}`, urls };
  }
  return null;
}

export function applyRelease(documentRoot, release) {
  if (!release) return;
  for (const key of RELEASE_KEYS) {
    documentRoot.querySelectorAll(`[data-download="${key}"]`).forEach((link) => {
      link.href = release.tag === RELEASE_TAG
        ? release.urls[key].replace(`${REPOSITORY_URL}/releases/download`, MIRROR_BASE)
        : release.urls[key];
    });
    documentRoot.querySelectorAll(`[data-github-download="${key}"]`).forEach((link) => {
      link.href = release.urls[key];
    });
  }
  documentRoot.querySelectorAll('[data-release-version]').forEach((el) => {
    el.textContent = release.tag;
  });
  documentRoot.querySelectorAll('[data-release-link]').forEach((link) => {
    link.href = release.page;
  });
  documentRoot.querySelectorAll('[data-release-checksums]').forEach((link) => {
    link.href = release.tag === RELEASE_TAG
      ? release.urls.checksums.replace(`${REPOSITORY_URL}/releases/download`, MIRROR_BASE)
      : release.urls.checksums;
  });
}

function initializeAppearance() {
  const select = document.querySelector('#appearance');
  if (!select) return;
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const root = document.documentElement;
  const apply = (preference, save = false) => {
    const resolved = preference === 'system' ? (media.matches ? 'dark' : 'light') : preference;
    root.dataset.appearance = preference;
    root.dataset.theme = resolved;
    select.value = preference;
    const themeColor = document.querySelector('meta[name="theme-color"]');
    if (themeColor) themeColor.content = resolved === 'dark' ? '#171d19' : '#f4f5f0';
    if (save) {
      try { localStorage.setItem('qcom32-volte-theme', preference); } catch {}
    }
  };
  apply(['system', 'light', 'dark'].includes(root.dataset.appearance) ? root.dataset.appearance : 'system');
  select.addEventListener('change', () => apply(select.value, true));
  media.addEventListener('change', () => {
    if (root.dataset.appearance === 'system') apply('system');
  });
}

export function menuLabel(language, open) {
  return language === 'en'
    ? (open ? 'Close navigation menu' : 'Open navigation menu')
    : (open ? '关闭导航菜单' : '打开导航菜单');
}

function initializeLanguage() {
  const language = document.documentElement.lang;
  document.querySelectorAll('[data-language-switch]').forEach((link) => {
    const updateDestination = () => {
      const destination = new URL(link.href, location.href);
      destination.search = location.search;
      destination.hash = location.hash;
      link.href = destination.href;
    };
    updateDestination();
    window.addEventListener('hashchange', updateDestination);
    link.addEventListener('click', updateDestination);
  });
  document.querySelectorAll('[data-star-count]').forEach((el) => {
    el.textContent = language === 'en' ? 'Star on GitHub' : '在 GitHub 点个 Star';
  });
}

function initializeMenu() {
  const toggle = document.querySelector('#menu-toggle');
  const navigation = document.querySelector('#site-nav');
  if (!toggle || !navigation) return;
  const close = (focus = false) => {
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-label', menuLabel(document.documentElement.lang, false));
    navigation.dataset.open = 'false';
    if (focus) toggle.focus();
  };
  toggle.addEventListener('click', () => {
    const open = toggle.getAttribute('aria-expanded') !== 'true';
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', menuLabel(document.documentElement.lang, open));
    navigation.dataset.open = String(open);
  });
  navigation.addEventListener('click', (event) => {
    if (event.target.closest('a')) close();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') close(true);
  });
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.site-header')) close();
  });
  window.matchMedia('(min-width: 1101px)').addEventListener('change', (event) => {
    if (event.matches) close();
  });
}

async function fetchJson(url, timeout = 6000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, {
      headers: { Accept: 'application/vnd.github+json' },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`GitHub returned ${response.status}`);
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

function initializeGithubData() {
  const repoApi = REPOSITORY_URL.replace('https://github.com/', 'https://api.github.com/repos/');
  if (document.querySelector('[data-download]')) {
    fetchJson(`${repoApi}/releases?per_page=10`)
      .then((releases) => applyRelease(
        document,
        selectRelease(Array.isArray(releases) ? releases.filter((r) => r.tag_name === RELEASE_TAG) : [])
      ))
      .catch(() => {});
  }
  if (!document.querySelector('[data-star-count]')) return;
  fetchJson(repoApi)
    .then((repository) => {
      if (!Number.isSafeInteger(repository?.stargazers_count) || repository.stargazers_count < 0) return;
      document.querySelectorAll('[data-star-count]').forEach((el) => {
        const language = document.documentElement.lang;
        const label = language === 'en' ? 'Star on GitHub' : '在 GitHub 点个 Star';
        el.textContent = `${label} · ${repository.stargazers_count.toLocaleString(language)}`;
      });
    })
    .catch(() => {});
}

if (typeof document !== 'undefined') {
  initializeLanguage();
  initializeAppearance();
  initializeMenu();
  initializeGithubData();
  document.querySelectorAll('[data-current-year]').forEach((el) => {
    el.textContent = String(new Date().getFullYear());
  });
}
