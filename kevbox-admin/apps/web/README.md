# @kevbox-admin/web

Fastify server + Vite/React SPA for managing KevBox member addons.

## Env (`.env`, see `.env.example`)
- `SUPABASE_DB_URL` — direct Postgres (server-side data access; never in the browser)
- `SUPABASE_URL`, `SUPABASE_ANON_KEY` — verify the admin's Supabase JWT
- `ADMIN_EMAILS` — comma-separated allowlist (e.g. `kevin.chiha@gmail.com`)
- `PORT` — local port nginx proxies to (default 8787)
- `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY` — baked into the SPA at build time

## Develop
```bash
npm run dev:server -w @kevbox-admin/web   # Fastify on :8787 (API only)
npm run dev:web -w @kevbox-admin/web      # Vite on :5173, proxies /api → :8787
```

## Build & run (production)
```bash
npm run build -w @kevbox-admin/web        # → dist/public (SPA) + dist/server.js (bundled server)
npm run start -w @kevbox-admin/web        # node dist/server.js, serves SPA + /api + /healthz
```
The server build (`build:server`) is a single self-contained **esbuild bundle** at `dist/server.js`
(all deps incl. `@kevbox-admin/core` inlined), so production needs no `node_modules`.

## Deploy (persovps)
- `deploy.sh` (repo root): build → scp **just `dist/`** (`server.js` + `public/`, no `node_modules`) → restart systemd unit.
- systemd runs `node dist/server.js` bound to `127.0.0.1:$PORT`.
- `GET /healthz` (unauthenticated, runs `select 1`) is the deploy smoke-check.
- nginx reverse-proxies `admin.kevbox.dev` → that port; certbot for TLS.
- Secrets live only in the server's env file on the VPS (not in git).

## Auth model
The SPA signs the admin in with Supabase (email/password → JWT) and sends
`Authorization: Bearer <jwt>` on every `/api/*` call. The server verifies the JWT
(`auth.getUser`, anon key) and requires `email ∈ ADMIN_EMAILS`. Family members are
authenticated but rejected (403) because they are not in the allowlist.
