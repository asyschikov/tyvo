#!/bin/sh
# Cut a release: bump the version, commit, tag, push — and stop. The tests,
# the build, the signing and the publishing all happen in CI; pushing the
# tag is what triggers release.yml. Nothing is built on this machine.
#
# Usage: scripts/release.sh 0.2.1
set -eu

GRADLE=app/build.gradle.kts

die() { echo "error: $*" >&2; exit 1; }

if [ $# -ne 1 ]; then
    echo "usage: $0 <version>   e.g. $0 0.2.1" >&2
    exit 2
fi

VERSION="$1"
TAG="v$VERSION"

case "$VERSION" in
    [0-9]*.[0-9]*.[0-9]) ;;
    *) die "version must look like 0.2.1 (got '$VERSION')" ;;
esac

# --- guards: a release is cut from a clean, current main -------------------

[ -z "$(git status --porcelain)" ] || die "worktree is not clean"
[ "$(git branch --show-current)" = "main" ] || die "switch to main first"
git fetch origin --quiet || die "git fetch origin failed"
git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null \
    || die "no origin/main to compare against"
[ -z "$(git rev-list origin/main..HEAD)" ] || die "main is ahead of origin/main; push first"
[ -z "$(git rev-list HEAD..origin/main)" ] || die "main is behind origin/main; pull first"

if git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null 2>&1; then
    die "tag $TAG already exists locally"
fi
if git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
    die "tag $TAG already exists on the remote"
fi

CURRENT_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE")
CURRENT_CODE=$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE")
[ -n "$CURRENT_NAME" ] && [ -n "$CURRENT_CODE" ] \
    || die "cannot find versionCode/versionName in $GRADLE"
NEW_CODE=$((CURRENT_CODE + 1))

echo "release $TAG: versionCode $CURRENT_CODE -> $NEW_CODE, versionName $CURRENT_NAME -> $VERSION"

# --- bump -----------------------------------------------------------------

sed -i.bak \
    -e "s/versionCode = [0-9][0-9]*/versionCode = $NEW_CODE/" \
    -e "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" \
    "$GRADLE"
rm -f "$GRADLE.bak"
if git diff --quiet -- "$GRADLE"; then
    die "the bump changed nothing in $GRADLE"
fi

git add "$GRADLE"
git commit -m "Bump version to $VERSION"

# --- tag and push ----------------------------------------------------------

# Signed when a key is configured, annotated otherwise. The workflow checks
# the signing certificate of the artifacts, not the tag signature.
if ! git tag -s "$TAG" -m "Tyvo $TAG" 2>/dev/null; then
    echo "note: no signing key configured; tagging annotated instead of signed"
    git tag -a "$TAG" -m "Tyvo $TAG"
fi

git push origin main "$TAG"

echo
echo "Pushed $TAG. release.yml now runs the tests, builds and signs the"
echo "APK and AAB, and publishes the GitHub Release — watch Actions -> Release."
