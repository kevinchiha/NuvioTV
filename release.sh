#!/usr/bin/env bash
# KevBox TV release pipeline (self-hosted auto-update, persovps).
# Adapted from kevbox-support/server/scripts/release.sh.
#
# Usage: ./release.sh <versionName> [release notes...]
#   e.g. ./release.sh 0.7.5-beta "Fix login on TV; faster startup"
#
# What it does:
#   1. Bumps versionCode (+1) and versionName in app/build.gradle.kts.
#   2. Builds the SIGNED, flavored release: ./gradlew assembleFullRelease
#      (reads NUVIO_RELEASE_* signing creds from ~/kevbox-keys/release.env).
#   3. Publishes the selected ABI's apk (default arm64-v8a; set KEVBOX_TV_APK_ABI):
#         app/build/outputs/apk/full/release/app-full-<abi>-release.apk
#      scp'd to persovps, installed under /var/www/kevbox-tv/, and pointed to by the
#      stable /download symlink (kevbox-tv-latest.apk) for first-install sideloads.
#   4. Computes sha256 ON THE HOST and writes /var/www/kevbox-tv/version.json
#      ({ versionCode, versionName, url, sha256, notes }) — the manifest the
#      in-app updater (UpdateRepository) fetches from ${UPDATE_BASE_URL}/version.json.
#   5. Optionally commits the version bump (set KEVBOX_COMMIT=1).
#
# ⚠️ Same-key invariant: every release MUST be signed with the SAME keystore
#    (~/kevbox-keys/kevboxtv.jks) or installs fail with "signatures don't match".
#    Never debug-sign a family release.
# ⚠️ KevBox TV APKs are LARGE (~80–150 MB: bundled FFmpeg/ExoPlayer/mpv/native libs).
#    Prune old versions on the VPS to mind storage and family download bandwidth.
#
# This is a documented operational script: review the host/web-root values below
# before running it. It targets the persovps VPS over SSH.
set -euo pipefail

note() { printf '\033[36m→ %s\033[0m\n' "$*"; }
err()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

VER="${1:-}"
[[ -z "$VER" ]] && err "Usage: $0 <versionName> [notes...]   e.g.  $0 0.7.5-beta \"What's new\""
shift || true
NOTES="${*:-Bug fixes and improvements.}"

REPO="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
GRADLE="$REPO/app/build.gradle.kts"
KEYENV="$HOME/kevbox-keys/release.env"

# persovps connection + deploy target (mirror the kevbox-support deploy).
SSH_ALIAS="${KEVBOX_SSH_ALIAS:-persovps}"
SSH_PORT="${KEVBOX_SSH_PORT:-1788}"
WEB_ROOT="${KEVBOX_TV_WEB_ROOT:-/var/www/kevbox-tv}"
BASE_URL="${KEVBOX_TV_BASE_URL:-https://tv.kevbox.dev}"

# Which APK to publish. Default = arm64-v8a: every consumer Android TV is arm64, the APK is
# smaller, and it sidesteps the large-universal-APK packaging OOM at the repo's -Xmx4096m. Set to
# "universal" for an any-ABI APK (needs more Gradle heap) or another split (x86_64/armeabi-v7a/x86).
APK_ABI="${KEVBOX_TV_APK_ABI:-arm64-v8a}"

# --- 1. Read current versionCode and bump it ------------------------------------
CURRENT_CODE=$(grep -E '^\s*versionCode\s*=' "$GRADLE" | grep -oE '[0-9]+' | head -1)
[[ -z "$CURRENT_CODE" ]] && err "Could not read versionCode from $GRADLE"
NEW_CODE=$(( CURRENT_CODE + 1 ))
note "Bumping versionCode $CURRENT_CODE → $NEW_CODE, versionName → $VER"
# Keep version.json.versionCode in lockstep with the gradle versionCode (the updater
# compares manifest.versionCode > BuildConfig.VERSION_CODE).
sed -i -E "s/(versionCode\s*=\s*)$CURRENT_CODE/\1$NEW_CODE/" "$GRADLE"
sed -i -E "s/(versionName\s*=\s*\")[^\"]*(\")/\1$VER\2/" "$GRADLE"

# --- 2. Build SIGNED, flavored release APK --------------------------------------
note "Building signed assembleFullRelease…"
[[ -f "$KEYENV" ]] || err "Keystore env not found at $KEYENV (needed to sign the release)"
# shellcheck source=/dev/null
source "$KEYENV"
# Never fall back to debug signing for a family release.
(cd "$REPO" && CI_USE_DEBUG_SIGNING=false ./gradlew assembleFullRelease --quiet)

# Publish the selected ABI's apk from the flavored release path (build.gradle.kts splits{}
# produces per-ABI APKs + a universal one). Default arm64-v8a (see APK_ABI above).
APK="$REPO/app/build/outputs/apk/full/release/app-full-${APK_ABI}-release.apk"
[[ -f "$APK" ]] || err "APK not found at $APK — build may have failed, or KEVBOX_TV_APK_ABI=$APK_ABI is wrong"
note "APK built: $APK ($(du -h "$APK" | cut -f1))"

# --- 3. Upload APK to persovps --------------------------------------------------
REMOTE_APK="kevbox-tv-$VER.apk"
note "Uploading $REMOTE_APK to $SSH_ALIAS:$WEB_ROOT…"
scp -P "$SSH_PORT" "$APK" "$SSH_ALIAS:/tmp/$REMOTE_APK"
# Install the APK and (re)point the stable /download symlink at it for first-install sideloads.
ssh "$SSH_ALIAS" -p "$SSH_PORT" "sudo install -m 644 /tmp/$REMOTE_APK $WEB_ROOT/$REMOTE_APK && sudo ln -sfn $WEB_ROOT/$REMOTE_APK $WEB_ROOT/kevbox-tv-latest.apk && rm -f /tmp/$REMOTE_APK"

# --- 4. Compute SHA-256 on the host and write version.json ----------------------
note "Computing sha256 on host and writing version.json…"
SHA=$(ssh "$SSH_ALIAS" -p "$SSH_PORT" "sha256sum $WEB_ROOT/$REMOTE_APK | awk '{print \$1}'")
[[ -n "$SHA" ]] || err "Failed to compute sha256 on host"
# Write version.json on the host (kevbox-support style: one inline `sudo tee` heredoc).
# Escape notes for JSON embedding (backslashes, double-quotes, newlines); keep notes concise.
NOTES_ESC=${NOTES//\\/\\\\}; NOTES_ESC=${NOTES_ESC//\"/\\\"}; NOTES_ESC=${NOTES_ESC//$'\n'/ }
ssh "$SSH_ALIAS" -p "$SSH_PORT" "sudo tee $WEB_ROOT/version.json > /dev/null <<EOF
{
  \"versionCode\": $NEW_CODE,
  \"versionName\": \"$VER\",
  \"url\": \"$BASE_URL/$REMOTE_APK\",
  \"sha256\": \"$SHA\",
  \"notes\": \"$NOTES_ESC\"
}
EOF"
note "version.json updated (versionCode=$NEW_CODE sha256=${SHA:0:16}…)"

# --- 5. Commit + push the version bump (like kevbox-support) ----------------------
note "Committing version bump…"
git -C "$REPO" add app/build.gradle.kts
git -C "$REPO" commit -m "chore(app): bump version to $VER (versionCode $NEW_CODE)"
# origin is your fork (github.com/kevinchiha/NuvioTV) and there is no upstream remote, so this only
# ever pushes the current branch to your own repo. `HEAD` works even before push-tracking is set.
git -C "$REPO" push origin HEAD

note "Release $VER (versionCode $NEW_CODE) is live at $BASE_URL/$REMOTE_APK"
note "Manifest: $BASE_URL/version.json"
