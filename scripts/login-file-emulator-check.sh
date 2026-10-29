#!/usr/bin/env bash
# Pushes a KevBox Setup login file to the debug app on the local emulator and waits for the
# answer the way the phone does (ls, then cat, once a second for 30 s).
# Contract: ~/projects/kevbox-setup/docs/login-file.md
#
# Usage: scripts/login-file-emulator-check.sh <cold|warm> <email>
#   cold  force-stops the app first, so the file is found on start
#   warm  leaves the app as it is (already on screen, or in the background)
# Asks for the password without echoing it. Emulator only: never point this at a real TV box.
set -u
ADB=/home/kevin/Android/Sdk/platform-tools/adb
SERIAL=${KEVBOX_SERIAL:-emulator-5554}
PKG=tv.kevbox.debug
DIR=/sdcard/Android/data/$PKG/files
MODE=${1:?cold or warm}
EMAIL=${2:?email}

[[ $SERIAL == emulator-* ]] || { echo "refusing: $SERIAL is not an emulator" >&2; exit 1; }
read -rsp "Password for $EMAIL: " PASSWORD; echo

[[ $MODE == cold ]] && "$ADB" -s "$SERIAL" shell am force-stop $PKG
"$ADB" -s "$SERIAL" shell "mkdir -p $DIR; rm -f $DIR/kevbox-login.result.json"
NOW=$("$ADB" -s "$SERIAL" shell date +%s%3N | tr -d '\r')
TMP=$(mktemp)
python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2],"requestedAt":int(sys.argv[3])}), end="")' \
  "$EMAIL" "$PASSWORD" "$NOW" > "$TMP"
unset PASSWORD
"$ADB" -s "$SERIAL" push "$TMP" $DIR/kevbox-login.json >/dev/null 2>&1
rm -f "$TMP"
"$ADB" -s "$SERIAL" shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1

for i in $(seq 1 30); do
  sleep 1
  NAMES=$("$ADB" -s "$SERIAL" shell ls $DIR | tr -d '\r')
  if ! grep -qx kevbox-login.json <<<"$NAMES" && grep -qx kevbox-login.result.json <<<"$NAMES"; then
    echo "after ${i}s: login file gone, result: $("$ADB" -s "$SERIAL" shell cat $DIR/kevbox-login.result.json)"
    "$ADB" -s "$SERIAL" shell rm -f $DIR/kevbox-login.result.json
    exit 0
  fi
done
"$ADB" -s "$SERIAL" shell rm -f $DIR/kevbox-login.json $DIR/kevbox-login.result.json
echo "no answer within 30 s (the phone would say: did not sign in. Is it the newest version?)"
exit 1
