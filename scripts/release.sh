#!/bin/bash
# Release flow, as in FireBro (scripts/remote-build.sh there): GitHub Actions builds the pushed HEAD and publishes it
# as release build-N of this repository; this script, run on the dev PC with the local gh login (no token stored in the
# repository), waits for that build, downloads the APK to out/apk and offers it to the installed apps through
# scripts/publish-update.sh (release wltv-N + wltv.json in mefistofelix/firetv-apps). Run from the project root, Git Bash.
# usage: bash scripts/release.sh            (after git push)
set -e
REPO=mefistofelix/wltv-firetv
git fetch -q origin && git merge-base --is-ancestor HEAD "@{u}" || { echo "push HEAD first (git push)"; exit 2; }
SHA=$(git rev-parse HEAD)
# the run of this commit (it may take a few seconds to appear after the push)
for i in $(seq 1 30); do
  RUN=$(gh run list -R "$REPO" -L 20 --json databaseId,headSha,event -q ".[] | select(.headSha==\"$SHA\") | .databaseId" | head -1)
  [ -n "$RUN" ] && break
  sleep 5
done
[ -n "$RUN" ] || { echo "no Actions run for $SHA"; exit 1; }
gh run watch "$RUN" -R "$REPO" --exit-status > /dev/null || { echo "build failed: gh run view $RUN -R $REPO --log-failed"; exit 1; }
N=$(gh run view "$RUN" -R "$REPO" --json number -q .number)
mkdir -p out/apk
gh release download "build-$N" -R "$REPO" -p "*.apk" -D out/apk --clobber
echo "release build-$N: out/apk/WLTV-$N.apk"
bash scripts/publish-update.sh "$N" "out/apk/WLTV-$N.apk"
