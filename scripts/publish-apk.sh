#!/usr/bin/env bash
# Build the signed release APK (R8) and publish it to the F-Droid repo at update.bolukba.si.
# Versioning comes from app/build.gradle.kts (git commit count): commit before publishing.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk; do
    [ -x "$j/bin/javac" ] && { JAVA_HOME="$j"; break; }
  done
fi
export JAVA_HOME

[ -z "$(git status --porcelain)" ] || { echo "ERROR: commit first (the version code is the commit count)" >&2; exit 1; }
[ -f keystore.properties ] || { echo "ERROR: keystore.properties missing: release builds must be signed" >&2; exit 1; }

./gradlew :app:assembleRelease --no-daemon

APK="$(ls -t app/build/outputs/apk/release/flipdriver-v*-release.apk 2>/dev/null | head -1 || true)"
[ -n "$APK" ] || { echo "ERROR: no flipdriver-v*-release.apk produced" >&2; exit 1; }
mkdir -p dist
VERSIONED="$(basename "$APK")"
cp "$APK" "dist/$VERSIONED"

FDROID_PUBLISH="$HOME/services/fdroid/publish.sh"
[ -x "$FDROID_PUBLISH" ] || { echo "ERROR: $FDROID_PUBLISH not found (APK is in dist/$VERSIONED)" >&2; exit 1; }
"$FDROID_PUBLISH" "$PWD/dist/$VERSIONED"

# Keep the newest 3 in dist/.
ls -1 dist/flipdriver-v*.apk | sort -V | head -n -3 2>/dev/null | xargs rm -f 2>/dev/null || true
echo "published to F-Droid: $VERSIONED"
