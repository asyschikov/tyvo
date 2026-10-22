#!/usr/bin/env bash
# Turn the agent's output into a commit, a PR, a signed preview APK and a
# comment. Runs in a job that never executes agent-written code: it applies
# a patch, signs a finished APK and talks to the GitHub API. That is why the
# release key can be here and nowhere near the agent.
#
# Run from a checkout of the default branch.
# Env: GH_TOKEN, OUT, ISSUE, PR, BRANCH, EXISTS, VERSION, BUILD, RUN_URL,
#      DEFAULT_BRANCH, TYVO_KEYSTORE_BASE64, TYVO_KEYSTORE_PASSWORD,
#      TYVO_KEY_PASSWORD, TYVO_KEY_ALIAS.
set -euo pipefail

REPO=$GITHUB_REPOSITORY
ASSETS=vibe-assets
# gate.sh looks for this: the owner's reply to a marked comment needs no tag.
QUESTION_MARK='<!-- vibe:question -->'

# The agent gave up and asked. Push nothing; ask where the work is happening.
if [ -s "$OUT/question.md" ]; then
    {
        echo "$QUESTION_MARK"
        echo "## I need your call before I go on"
        echo
        cat "$OUT/question.md"
        echo
        echo "_Reply here and I will pick it up from your answer; no need to tag me._"
        echo
        echo "<sub>[Run log]($RUN_URL)</sub>"
    } > "$RUNNER_TEMP/question.md"
    gh issue comment "${PR:-$ISSUE}" -R "$REPO" --body-file "$RUNNER_TEMP/question.md"
    exit 0
fi

TITLE=$(head -n1 "$OUT/title.txt" 2>/dev/null | cut -c1-72 || true)
[ -n "$TITLE" ] || TITLE="Work on #$ISSUE"
SUMMARY=$(cat "$OUT/summary.md" 2>/dev/null || echo "_The agent left no summary._")

git config user.name 'github-actions[bot]'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'

# --- commit and push ---------------------------------------------------------

if [ "$EXISTS" = true ]; then
    git checkout -q -B "$BRANCH" "origin/$BRANCH"
else
    git checkout -q -b "$BRANCH"
fi

PUSHED=false
if [ -s "$OUT/changes.patch" ]; then
    # --3way: the branch may have moved since the agent checked it out.
    git apply --3way --index "$OUT/changes.patch"
    if git diff --cached --no-renames --name-only | grep -q '^\.github/'; then
        echo "::error::The agent changed files under .github/; refusing to push." >&2
        exit 1
    fi
    if ! git diff --cached --quiet; then
        git commit -q -m "$TITLE" -m "Refs #$ISSUE"
        git push -q origin "$BRANCH"
        PUSHED=true
    fi
fi

if [ -z "$PR" ] && [ "$PUSHED" = true ]; then
    BODY=$(printf '%s\n\nCloses #%s\n' "$SUMMARY" "$ISSUE")
    PR_URL=$(gh pr create -R "$REPO" --head "$BRANCH" --base "$DEFAULT_BRANCH" \
        --title "$TITLE" --body "$BODY")
    PR=${PR_URL##*/}
    gh issue comment "$ISSUE" -R "$REPO" --body "Opened #$PR."
fi

# Nothing pushed and no PR yet: the agent answered instead of changing code.
if [ -z "$PR" ]; then
    gh issue comment "$ISSUE" -R "$REPO" --body "$(printf '%s\n\n<sub>[Run log](%s)</sub>\n' "$SUMMARY" "$RUN_URL")"
    exit 0
fi

# --- screenshots, kept on an orphan branch so the PR can link to them ---------

SHOTS=""
if compgen -G "$OUT/screenshots/*.png" >/dev/null; then
    DIR="pr-$PR/$GITHUB_RUN_ID"
    W="$RUNNER_TEMP/assets"
    if git fetch -q origin "$ASSETS" 2>/dev/null; then
        git worktree add -q -B "$ASSETS" "$W" FETCH_HEAD
    else
        git worktree add -q --orphan -b "$ASSETS" "$W"
    fi
    mkdir -p "$W/$DIR"
    for f in "$OUT"/screenshots/*.png; do
        name=$(basename "$f" | sed 's/[^A-Za-z0-9._-]/-/g')
        cp "$f" "$W/$DIR/$name"
        SHOTS+="<img src=\"https://raw.githubusercontent.com/$REPO/$ASSETS/$DIR/$name\" width=\"270\" alt=\"$name\"> "
    done
    git -C "$W" add -A
    git -C "$W" commit -q -m "Screenshots for #$PR, run $GITHUB_RUN_ID"
    git -C "$W" push -q origin "$ASSETS"
fi

# --- signed APK, as a prerelease per PR -------------------------------------

APK_LINE=""
UNSIGNED="$OUT/app-release-unsigned.apk"
if [ "$BUILD" = success ] && [ -f "$UNSIGNED" ] && [ -n "${TYVO_KEYSTORE_BASE64:-}" ]; then
    KS="$RUNNER_TEMP/tyvo-upload.jks"
    trap 'rm -f "$KS"' EXIT
    printf '%s' "$TYVO_KEYSTORE_BASE64" | base64 -d > "$KS"

    BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
    NAME="tyvo-$VERSION.apk"
    APK="$RUNNER_TEMP/$NAME"
    "$BUILD_TOOLS/zipalign" -p -f 4 "$UNSIGNED" "$RUNNER_TEMP/aligned.apk"
    "$BUILD_TOOLS/apksigner" sign --ks "$KS" \
        --ks-pass env:TYVO_KEYSTORE_PASSWORD \
        --ks-key-alias "${TYVO_KEY_ALIAS:-tyvo}" \
        --key-pass env:TYVO_KEY_PASSWORD \
        --out "$APK" "$RUNNER_TEMP/aligned.apk"
    rm -f "$KS"

    # Not v*: release.yml builds every tag that starts with a v.
    TAG="preview-pr-$PR"
    gh release delete "$TAG" -R "$REPO" --yes --cleanup-tag 2>/dev/null || true
    gh release create "$TAG" "$APK" -R "$REPO" --prerelease \
        --target "$(git rev-parse "origin/$BRANCH")" \
        --title "Preview $VERSION (#$PR)" \
        --notes "Preview build of #$PR. Signed with the release key, so it installs over Tyvo from GitHub releases. Not over a Play Store install."
    APK_LINE="**APK:** [$NAME](https://github.com/$REPO/releases/download/$TAG/$NAME), installs over your Tyvo"
elif [ "$BUILD" != success ]; then
    APK_LINE="**APK:** none, the unit tests or the release build failed. See the run log."
fi

# --- CI, which a push by this token does not start on its own --------------------

if [ "$PUSHED" = true ]; then
    gh workflow run ci.yml -R "$REPO" --ref "$BRANCH" || true
fi

# --- report on the PR ---------------------------------------------------------

{
    if [ "$PUSHED" = true ]; then
        echo "## $TITLE"
    else
        echo "## No code changes this time"
    fi
    echo
    echo "$SUMMARY"
    echo
    [ -n "$APK_LINE" ] && { echo "$APK_LINE"; echo; }
    [ -n "$SHOTS" ] && { echo "### Screenshots"; echo; echo "$SHOTS"; echo; }
    echo "<sub>[Run log]($RUN_URL)</sub>"
} > "$RUNNER_TEMP/comment.md"
gh pr comment "$PR" -R "$REPO" --body-file "$RUNNER_TEMP/comment.md"
