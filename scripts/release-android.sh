#!/usr/bin/env bash
# Builds a release-signed APK and publishes it as a GitHub release.
#
#   scripts/release-android.sh [notes.md]
#
# The release is tagged v<versionName> from android/app/build.gradle.kts
# (bump versionCode + versionName first) and carries the APK as
# "wakelink.apk", which is the name both the in-app updater
# (update/AppUpdater.kt) and the stable link
# https://github.com/seolcu/wakelink/releases/latest/download/wakelink.apk
# rely on. Without a notes file, GitHub generates notes from commits.
#
# Needs: gh (authenticated), a full JDK 17+, the Android SDK, the real
# app/google-services.json, and keystore/wakelink-upload.properties --
# the APK must be signed with the same key every time, or installed
# copies can't update to it.
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

notes_file=${1:-}
if [[ -n "$notes_file" && ! -f "$notes_file" ]]; then
    echo "notes file not found: $notes_file" >&2
    exit 1
fi

if [[ -n $(git status --porcelain) ]]; then
    echo "working tree is dirty; commit first so the tag matches the APK" >&2
    exit 1
fi
git fetch --quiet origin
if [[ $(git rev-parse HEAD) != $(git rev-parse '@{upstream}') ]]; then
    echo "HEAD is not pushed (or is behind upstream); push/pull first" >&2
    exit 1
fi

version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' android/app/build.gradle.kts)
tag="v$version"
if [[ -z "$version" ]]; then
    echo "could not read versionName from android/app/build.gradle.kts" >&2
    exit 1
fi
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || gh release view "$tag" >/dev/null 2>&1; then
    echo "$tag already exists; bump versionCode/versionName first" >&2
    exit 1
fi

for f in android/keystore/wakelink-upload.properties android/app/google-services.json; do
    [[ -f "$f" ]] || { echo "missing $f" >&2; exit 1; }
done
if cmp -s android/app/google-services.json android/app/google-services.json.example; then
    echo "app/google-services.json is the placeholder; push notifications would not work" >&2
    exit 1
fi

export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/jdks/jdk-21.0.12.1+1}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.local/share/android-sdk}
export PATH="$JAVA_HOME/bin:$PATH"
(cd android && ./gradlew --quiet clean assembleRelease)

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cp android/app/build/outputs/apk/release/app-release.apk "$out/wakelink.apk"

notes_args=(--generate-notes)
[[ -n "$notes_file" ]] && notes_args=(--notes-file "$notes_file")

gh release create "$tag" "$out/wakelink.apk" \
    --target "$(git rev-parse HEAD)" \
    --title "WakeLink $version" \
    --latest \
    "${notes_args[@]}"

echo "published $tag"
echo "latest APK: https://github.com/seolcu/wakelink/releases/latest/download/wakelink.apk"
