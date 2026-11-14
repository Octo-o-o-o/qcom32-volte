const LEGACY_HOSTS = new Set([
  'passport-volte.octoooo.com',
  'passport-android11-volte.octoooo.com',
]);
const CANONICAL_HOST = 'qcom32-volte.octoooo.com';

const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
  'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' https://api.github.com; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; upgrade-insecure-requests",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (LEGACY_HOSTS.has(url.hostname)) {
      url.hostname = CANONICAL_HOST;
      url.protocol = 'https:';
      return Response.redirect(url.toString(), 301);
    }
    const assetResponse = await env.ASSETS.fetch(request);
    const headers = new Headers(assetResponse.headers);
    for (const [k, v] of Object.entries(SECURITY_HEADERS)) {
      if (!headers.has(k)) {
        headers.set(k, v);
      }
    }
    if (url.pathname.startsWith('/assets/') && !headers.has('Cache-Control')) {
      headers.set('Cache-Control', 'public, max-age=86400');
    }
    return new Response(assetResponse.body, {
      status: assetResponse.status,
      statusText: assetResponse.statusText,
      headers,
    });
  },
};
