# Deploying KevBox Admin to persovps

`admin.kevbox.dev` runs on **persovps** (the same VPS as `tv.kevbox.dev`): a Fastify
process managed by **systemd**, behind **nginx** with **Let's Encrypt** TLS.

- App root on VPS: `/opt/kevbox-admin` (`current` → `releases/release-<ts>`)
- systemd unit: `kevbox-admin.service`
- nginx vhost: `/etc/nginx/sites-available/admin.kevbox.dev`
- Secrets: `/etc/kevbox-admin/env` (mode `600`, owner `kevbox-admin`) — **never in git**
- Local port: `8787` (loopback only; nginx is the public ingress)
- SSH: alias `persovps`, port `1788` (same as NuvioTV `release.sh`)

## Least-privileged DB role (do once, in the Supabase SQL editor)

`SUPABASE_DB_URL` must NOT use the `postgres` superuser — that would give a public-facing
process god-mode over the entire project. Create a scoped role that can manage `member_addon`
and read member emails, nothing else. `bypassrls` lets it see every member's rows (the admin
is cross-member by design) while the column/table grants cap its actual reach:

```sql
-- Run once as postgres (Supabase dashboard → SQL editor).
create role kevbox_admin with login password 'CHANGE_ME_strong_password' bypassrls;
grant connect on database postgres to kevbox_admin;
grant usage on schema public to kevbox_admin;
grant select, insert, update, delete on public.member_addon to kevbox_admin;
grant execute on function public.default_member_addons() to kevbox_admin;
-- Email lookup join — only the columns the admin needs from auth.users.
grant usage on schema auth to kevbox_admin;
grant select (id, email, created_at) on auth.users to kevbox_admin;
```

Put this role (not `postgres`) in `SUPABASE_DB_URL`, via the **Session pooler** host (step 4).

## One-time bootstrap (do once, in order)

1. **DNS** — add an A record `admin.kevbox.dev → <persovps IP>` (same IP as
   `tv.kevbox.dev`). Verify: `dig +short admin.kevbox.dev A`.

2. **VPS user + dirs**
   ```bash
   ssh persovps -p 1788 "set -e
     id -u kevbox-admin >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin kevbox-admin
     sudo mkdir -p /opt/kevbox-admin/releases /etc/kevbox-admin
     sudo chown -R kevbox-admin:kevbox-admin /opt/kevbox-admin"
   ```

3. **systemd unit**
   ```bash
   scp -P 1788 deploy/kevbox-admin.service persovps:/tmp/kevbox-admin.service
   ssh persovps -p 1788 "sudo install -m 644 /tmp/kevbox-admin.service /etc/systemd/system/kevbox-admin.service && rm -f /tmp/kevbox-admin.service && sudo systemctl daemon-reload && sudo systemctl enable kevbox-admin"
   ```

4. **Secrets** — create `/etc/kevbox-admin/env` from the template, then edit REAL values:
   ```bash
   scp -P 1788 deploy/env.example persovps:/tmp/env.example
   ssh persovps -p 1788 "sudo install -m 600 -o kevbox-admin -g kevbox-admin /tmp/env.example /etc/kevbox-admin/env && rm -f /tmp/env.example"
   ssh -t persovps -p 1788 "sudo -e /etc/kevbox-admin/env"
   ```

5. **nginx vhost** (HTTP first; certbot adds TLS next)
   ```bash
   scp -P 1788 deploy/nginx-admin.kevbox.dev.conf persovps:/tmp/admin.kevbox.dev.conf
   ssh persovps -p 1788 "sudo install -m 644 /tmp/admin.kevbox.dev.conf /etc/nginx/sites-available/admin.kevbox.dev && rm -f /tmp/admin.kevbox.dev.conf && sudo ln -sfn /etc/nginx/sites-available/admin.kevbox.dev /etc/nginx/sites-enabled/admin.kevbox.dev && sudo nginx -t && sudo systemctl reload nginx"
   ```

6. **TLS** (after DNS resolves)
   ```bash
   ssh persovps -p 1788 "sudo certbot --nginx -d admin.kevbox.dev --non-interactive --agree-tos -m kevin.chiha@gmail.com --redirect"
   ```

7. **First deploy**
   ```bash
   ./deploy.sh
   ```

## Routine deploys

```bash
./deploy.sh
```

Builds core + web, ships a self-contained `dist/` bundle (no `node_modules`), atomically
swaps the `current` symlink, restarts `kevbox-admin`, and smoke-checks `/healthz` then `/`.

## Rotating secrets

Secrets live only in `/etc/kevbox-admin/env` (and, for the anon key, baked into the SPA).

- **DB password / `SUPABASE_DB_URL` or `ADMIN_EMAILS`** — edit the env file and restart; no rebuild:
  ```bash
  ssh -t persovps -p 1788 "sudo -e /etc/kevbox-admin/env && sudo systemctl restart kevbox-admin"
  ```
  (To rotate the DB password itself, `alter role kevbox_admin with password '…'` in the Supabase
  SQL editor first, then update the env string.)
- **`SUPABASE_ANON_KEY`** — this one is ALSO baked into the SPA at build time
  (`VITE_SUPABASE_ANON_KEY`). Rotating it requires editing **both** the server env file **and**
  `apps/web/.env`, then **re-running `./deploy.sh`** (a plain restart is not enough — the old key
  stays in the shipped JS until you rebuild).
- **Removing an admin** — drop the address from `ADMIN_EMAILS` and restart (takes effect on the
  next request; existing JWTs are still rejected because the allowlist is checked per-request).

## Rollback

`deploy.sh` keeps the 5 most recent releases in `/opt/kevbox-admin/releases/`. To roll
back to the previous release, repoint `current` and restart:

```bash
ssh persovps -p 1788 "set -e
  PREV=\$(ls -1dt /opt/kevbox-admin/releases/release-* | sed -n '2p')
  echo \"Rolling back to \$PREV\"
  sudo ln -sfn \"\$PREV\" /opt/kevbox-admin/current
  sudo systemctl restart kevbox-admin"
curl -I https://admin.kevbox.dev/
```

To roll back further, list releases (`ls -1dt /opt/kevbox-admin/releases/release-*`)
and point `current` at the desired one. Secrets/env are unaffected by rollback (they
live in `/etc/kevbox-admin/env`, outside the release dirs).

## Troubleshooting

- **502 from nginx** → the unit is down. `ssh persovps -p 1788 "sudo systemctl status kevbox-admin --no-pager; sudo journalctl -u kevbox-admin -n 50 --no-pager"`. Common cause: a bad value in `/etc/kevbox-admin/env` (e.g. `SUPABASE_DB_URL`).
- **Service active but `/healthz` or `/api/*` returns 500/503** → DB connectivity. Most common cause on a fresh VPS: `SUPABASE_DB_URL` points at the **direct** host `db.<ref>.supabase.co`, which is **IPv6-only** — an IPv4-only VPS gets `ENETUNREACH`/`ENOTFOUND`. Fix: use the **Session pooler** host (`…pooler.supabase.com:5432`, IPv4-reachable; copy it from Project Settings → Database → Connection string → Session pooler). Also confirm `?sslmode=require`, the `kevbox_admin` role/password, and that the role has the grants from "Least-privileged DB role". Quick check from the VPS: `ssh persovps -p 1788 "getent hosts <db-host>; node -e 'require(\"net\").connect(5432,\"<db-host>\").on(\"connect\",()=>{console.log(\"ok\");process.exit()}).on(\"error\",e=>{console.log(e.code);process.exit()})'"`.
- **`/api/*` returns 401 with a valid token** → `ADMIN_EMAILS` mismatch, or `SUPABASE_URL`/`SUPABASE_ANON_KEY` wrong (JWT verification fails). Confirm they match the Supabase project.
- **Not listening on 127.0.0.1:8787** → `PORT` in the env file disagrees with nginx `proxy_pass`, or the server binds `0.0.0.0`. `ss -ltnp | grep 8787`.
- **Cert won't issue** → DNS not pointing at the VPS yet (`dig +short admin.kevbox.dev A`); wait for TTL and retry certbot.
