#!/usr/bin/env bash
# KevBox Admin deploy pipeline (admin.kevbox.dev on persovps).
# Mirrors the NuvioTV release.sh scp-to-persovps pattern (alias persovps, port 1788,
# sudo install / sudo systemctl over ssh).
#
# Usage: ./deploy.sh
#
# What it does:
#   1. Builds @kevbox-admin/core and apps/web (Vite SPA + a self-contained esbuild
#      server bundle at apps/web/dist/server.js — all deps incl. @kevbox-admin/core inlined).
#   2. Stages the runtime artifact: just dist/ (server.js + public/). No node_modules,
#      no package.json, no npm ci — the bundle is self-contained.
#   3. tars the stage and scp's it to persovps:/tmp.
#   4. Over ssh: unpacks into $REMOTE_DIR (atomic swap via a `current` symlink) and
#      restarts the systemd unit.
#   5. Smoke-checks the live site (curl https://admin.kevbox.dev/healthz then /).
#
# Secrets are NOT shipped by this script — they live only in /etc/kevbox-admin/env
# on the VPS (see deploy/env.example and deploy/README.md). One-time VPS bootstrap
# (systemd unit, nginx, certbot, env file) is in deploy/README.md.
set -euo pipefail

note() { printf '\033[36m→ %s\033[0m\n' "$*"; }
err()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# --- persovps connection + deploy target (override via env; mirrors release.sh) ---
SSH_ALIAS="${KEVBOX_SSH_ALIAS:-persovps}"
SSH_PORT="${KEVBOX_SSH_PORT:-1788}"
REMOTE_DIR="${KEVBOX_ADMIN_REMOTE_DIR:-/opt/kevbox-admin}"
SERVICE="${KEVBOX_ADMIN_SERVICE:-kevbox-admin}"
DOMAIN="${KEVBOX_ADMIN_DOMAIN:-admin.kevbox.dev}"

REPO="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$REPO"

# --- 1. Build core + web --------------------------------------------------------
note "Building @kevbox-admin/core…"
npm run build -w @kevbox-admin/core --silent

note "Building apps/web (SPA + server bundle)…"
npm run build -w @kevbox-admin/web --silent

SERVER_ENTRY="apps/web/dist/server.js"
SPA_DIR="apps/web/dist/public"
[[ -f "$SERVER_ENTRY" ]] || err "Missing $SERVER_ENTRY — did the web build run? (npm run build -w @kevbox-admin/web)"
[[ -d "$SPA_DIR" ]]      || err "Missing $SPA_DIR — Vite SPA build output not found"
note "Build OK: $SERVER_ENTRY + $SPA_DIR/"

# --- 2. Stage the self-contained runtime artifact (just dist/) -------------------
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
note "Staging runtime artifact in $STAGE…"
mkdir -p "$STAGE/dist"
cp -R apps/web/dist/. "$STAGE/dist/"
# dist/server.js is a self-contained esbuild bundle (all deps + @kevbox-admin/core
# inlined), so there is NO package.json/lockfile to ship and NO npm ci to run on the VPS.

# --- 3. tar + scp to persovps ---------------------------------------------------
TARBALL="kevbox-admin-$(date +%Y%m%d-%H%M%S).tgz"
note "Packing $TARBALL…"
tar -C "$STAGE" -czf "/tmp/$TARBALL" .
note "Uploading $TARBALL to $SSH_ALIAS:/tmp…"
scp -P "$SSH_PORT" "/tmp/$TARBALL" "$SSH_ALIAS:/tmp/$TARBALL"
rm -f "/tmp/$TARBALL"

# --- 4. Unpack into a timestamped release dir + atomic symlink swap + restart ----
RELEASE="release-$(date +%Y%m%d-%H%M%S)"
note "Deploying $RELEASE on $SSH_ALIAS and restarting $SERVICE…"
ssh "$SSH_ALIAS" -p "$SSH_PORT" "set -euo pipefail
  sudo mkdir -p '$REMOTE_DIR/releases/$RELEASE'
  sudo tar -C '$REMOTE_DIR/releases/$RELEASE' -xzf '/tmp/$TARBALL'
  rm -f '/tmp/$TARBALL'
  sudo ln -sfn '$REMOTE_DIR/releases/$RELEASE' '$REMOTE_DIR/current'
  sudo systemctl restart '$SERVICE'
  # Prune all but the 5 most recent releases.
  ls -1dt '$REMOTE_DIR'/releases/release-* | tail -n +6 | xargs -r sudo rm -rf"

# --- 5. Smoke check -------------------------------------------------------------
note "Waiting for service to come up…"
ssh "$SSH_ALIAS" -p "$SSH_PORT" "sudo systemctl is-active '$SERVICE'" \
  || err "Service '$SERVICE' is not active after restart — check: ssh $SSH_ALIAS -p $SSH_PORT 'sudo journalctl -u $SERVICE -n 50'"
# /healthz runs `select 1` against the pool, so a 200 here proves nginx + the unit +
# DB connectivity (catches a wrong/unreachable SUPABASE_DB_URL that a static-/ check misses).
note "Smoke-checking https://$DOMAIN/healthz …"
HEALTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN/healthz" || echo 000)"
[[ "$HEALTH_CODE" == "200" ]] \
  || err "https://$DOMAIN/healthz returned $HEALTH_CODE — service or DB connectivity is broken (deploy/README.md troubleshooting; check SUPABASE_DB_URL host/role)"
note "Smoke-checking https://$DOMAIN …"
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN/" || echo 000)"
case "$HTTP_CODE" in
  200|301|302) note "Live: https://$DOMAIN returned $HTTP_CODE (healthz OK)" ;;
  *) err "https://$DOMAIN returned $HTTP_CODE — check nginx + the unit (deploy/README.md troubleshooting)" ;;
esac

note "Deploy complete: $RELEASE → https://$DOMAIN"
