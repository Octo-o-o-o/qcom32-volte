# Qcom32 VoLTE Website (`qcom32-volte.octoooo.com`)

Static bilingual (`zh-CN` / `en`) website deployed on Cloudflare Workers Static Assets (`passport-volte-site`), serving canonical domain `https://qcom32-volte.octoooo.com/` (with a permanent `301` redirect from legacy `passport-volte.octoooo.com`), paired with Cloudflare R2 release downloads (`qcom32-downloads.octoooo.com`) and GitHub Releases (`Octo-o-o-o/qcom32-volte`).

## Build & Verify

```bash
cd website
npm test
```

Zero third-party npm packages are required: `scripts/build.mjs` and `tests/site.test.mjs` run natively on Node.js 20+ built-in `node:test`.
