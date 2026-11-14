# Passport Android 11 VoLTE Website (`passport-volte.octoooo.com`)

Static bilingual (`zh-CN` / `en`) website deployed on Cloudflare Pages (`passport-volte`), paired with Cloudflare R2 release downloads (`passport-downloads.octoooo.com`) and GitHub Releases (`Octo-o-o-o/passport-android11-volte`).

## Build & Verify

```bash
cd website
npm test
```

Zero third-party npm packages are required: `scripts/build.mjs` and `tests/site.test.mjs` run natively on Node.js 20+ built-in `node:test`.
