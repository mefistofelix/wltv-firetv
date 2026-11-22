#!/bin/bash
# Offers an APK to the installed WLTV apps (Updater.kt): uploads it as release wltv-N of the public repository
# mefistofelix/firetv-apps and points wltv.json in that repository at it (same scheme as FireBro's firebro.json).
# usage: bash scripts/publish-update.sh N path/to/WLTV-N.apk
#   - GitHub Actions runs it after each build when the secret FIRETV_APPS_TOKEN exists (token allowed to write
#     contents of mefistofelix/firetv-apps);
#   - by hand: bash scripts/publish-update.sh N out/apk/WLTV-N.apk (uses the local gh login).
set -e
N="$1"; APK="$2"
[ -n "$N" ] && [ -f "$APK" ] || { echo "usage: publish-update.sh N apk"; exit 2; }
REPO=mefistofelix/firetv-apps
NAME="WLTV-$N.apk"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
gh release view "wltv-$N" -R "$REPO" > /dev/null 2>&1 ||
  gh release create "wltv-$N" "$APK#$NAME" -R "$REPO" --title "WLTV 0.1.$N" --notes "WLTV build $N" --latest=false > /dev/null
URL="https://github.com/$REPO/releases/download/wltv-$N/$NAME"
JSON=$(printf '{"versionCode": %s, "versionName": "0.1.%s", "url": "%s", "sha256": "%s", "size": %s}\n' "$N" "$N" "$URL" "$SHA" "$SIZE")
OLD=$(gh api "repos/$REPO/contents/wltv.json" -q .sha 2>/dev/null || true)
gh api -X PUT "repos/$REPO/contents/wltv.json" -f message="WLTV build $N" \
  -f content="$(printf '%s' "$JSON" | base64 -w0)" ${OLD:+-f sha="$OLD"} > /dev/null
echo "update offered: $URL"
