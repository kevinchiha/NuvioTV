#!/usr/bin/env bash
# Notify (via ntfy) when upstream NuvioMedia/NuvioTV publishes a new release.
#
# Part of the KevBox upstream-sync toolchain (see UPSTREAM-SYNC.md): upstream tags its
# betas as full GitHub Releases with APK assets, so the newest release tag is the signal
# that it's time to `git merge upstream/dev` + `./release.sh`.
#
# Stateful: records the last-seen tag in STATE_FILE and only notifies on a CHANGE, with
# the notify gated on having a prior baseline. So a missed run (machine off) just DELAYS
# the notification to the next run — it is never dropped, and you never get spammed for a
# release you already know about.
#
# Config (env; the systemd unit loads it from ~/.config/nuvio-release-watch.env):
#   NTFY_TOPIC    (required) ntfy topic to publish to, e.g. kevbox-nuvio-xxxxxxxx
#   NTFY_SERVER   (optional) default https://ntfy.sh
#   REPO          (optional) default NuvioMedia/NuvioTV
#   STATE_FILE    (optional) default ${XDG_CACHE_HOME:-~/.cache}/nuvio-release-last.txt
#
# Exit codes: 0 = ok / nothing-new / soft-skip on network hiccup; 2 = misconfigured.
set -uo pipefail

REPO="${REPO:-NuvioMedia/NuvioTV}"
NTFY_SERVER="${NTFY_SERVER:-https://ntfy.sh}"
STATE_FILE="${STATE_FILE:-${XDG_CACHE_HOME:-$HOME/.cache}/nuvio-release-last.txt}"

if [ -z "${NTFY_TOPIC:-}" ]; then
  echo "nuvio-release-watch: NTFY_TOPIC is not set" >&2
  exit 2
fi
mkdir -p "$(dirname "$STATE_FILE")"

# Newest release tag (the /releases list includes prereleases; /latest would skip *-beta).
# On rate-limit/network errors the pipeline fails and we soft-skip until the next run.
latest=$(curl -fsSL --max-time 30 "https://api.github.com/repos/$REPO/releases?per_page=1" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['tag_name'] if d else '')") || exit 0
[ -n "$latest" ] || exit 0

last=$(cat "$STATE_FILE" 2>/dev/null || true)
[ "$latest" = "$last" ] && exit 0   # already seen — nothing to do

# First run (no baseline yet): record silently so we don't notify for the current version.
if [ -n "$last" ]; then
  curl -fsSL \
    -H "Title: NuvioTV $latest released" \
    -H "Tags: tv,arrow_up" \
    -H "Click: https://github.com/$REPO/releases/tag/$latest" \
    -d "Upstream NuvioTV published $latest (was $last). Sync the fork: git merge upstream/dev -> ./release.sh" \
    "$NTFY_SERVER/$NTFY_TOPIC" >/dev/null || exit 0   # notify failed: don't advance state, retry next run
fi
echo "$latest" > "$STATE_FILE"
