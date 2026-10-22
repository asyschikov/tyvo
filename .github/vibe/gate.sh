#!/usr/bin/env bash
# Decide whether a comment or review is a job for the vibe bot, and if so
# write down everything the agent needs to know. Runs from the default
# branch, never from a PR branch, so a PR cannot change what it does.
#
# A run starts when the repo owner
#   - writes @tyvo-bot in a comment on an issue,
#   - replies on an issue where the bot's last comment was a question, or
#   - comments on, or reviews, an open vibe/* pull request (no tag needed).
#
# Env: GH_TOKEN, CONTEXT_DIR, and the usual GITHUB_* from Actions.
# Outputs: run, issue, pr, branch, exists, version.
set -euo pipefail

REPO=$GITHUB_REPOSITORY
OWNER=$GITHUB_REPOSITORY_OWNER
EV=$GITHUB_EVENT_PATH
TRIGGER='@tyvo-bot'

out() { echo "$1=$2" >> "$GITHUB_OUTPUT"; }
skip() { echo "skip: $*"; out run false; exit 0; }

# "Add a dark mode toggle to settings" -> "dark-mode-toggle": the first three
# words that carry meaning, at most 24 characters.
slugify() {
    tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9' ' ' | awk '
        BEGIN {
            split("a an the to of for in on and or is it be with when add fix make should please bug feature", s, " ")
            for (i in s) stop[s[i]] = 1
        }
        { for (i = 1; i <= NF && n < 3; i++) if (!($i in stop)) w[++n] = $i }
        END { for (i = 1; i <= n; i++) printf "%s%s", (i > 1 ? "-" : ""), w[i] }
    ' | cut -c1-24 | sed 's/-*$//'
}

AUTHOR=$(jq -r '.comment.user.login // .review.user.login' "$EV")
[ "$AUTHOR" = "$OWNER" ] || skip "@$AUTHOR is not the repo owner"

ISSUE='' PR=''
case "$GITHUB_EVENT_NAME" in
    issue_comment)
        TRIGGER_BODY=$(jq -r .comment.body "$EV")
        TRIGGER_URL=$(jq -r .comment.html_url "$EV")
        if jq -e .issue.pull_request "$EV" >/dev/null; then
            PR=$(jq -r .issue.number "$EV")
        else
            ISSUE=$(jq -r .issue.number "$EV")
            # No tag needed to answer a question the bot asked here.
            if ! grep -qiF "$TRIGGER" <<<"$TRIGGER_BODY"; then
                ASKED=$(gh api --paginate "repos/$REPO/issues/$ISSUE/comments" \
                    -q '.[] | select(.user.login == "github-actions[bot]") | .body | contains("<!-- vibe:question -->")' \
                    | tail -n1)
                [ "$ASKED" = true ] || skip "no $TRIGGER, and no open question from the bot"
            fi
        fi
        ;;
    pull_request_review)
        PR=$(jq -r .pull_request.number "$EV")
        TRIGGER_BODY=$(jq -r '.review.body // ""' "$EV")
        TRIGGER_URL=$(jq -r .review.html_url "$EV")
        ;;
    *) skip "unexpected event $GITHUB_EVENT_NAME" ;;
esac

if [ -n "$PR" ]; then
    read -r BRANCH STATE < <(gh pr view "$PR" -R "$REPO" --json headRefName,state -q '.headRefName + " " + .state')
    case "$BRANCH" in vibe/*) ;; *) skip "PR #$PR is not a vibe PR" ;; esac
    [ "$STATE" = OPEN ] || skip "PR #$PR is $STATE"
    ISSUE=${BRANCH#vibe/}
    ISSUE=${ISSUE%%-*}
    EXISTS=true
else
    # A second @tyvo-bot on the same issue continues the work already started.
    REF=$(gh api "repos/$REPO/git/matching-refs/heads/vibe/$ISSUE-" -q '.[0].ref // empty')
    if [ -n "$REF" ]; then
        BRANCH=${REF#refs/heads/}
        EXISTS=true
        PR=$(gh pr list -R "$REPO" --head "$BRANCH" --state open --json number -q '.[0].number // empty')
    else
        SLUG=$(gh issue view "$ISSUE" -R "$REPO" --json title -q .title | slugify)
        BRANCH="vibe/$ISSUE-${SLUG:-issue}"
        EXISTS=false
    fi
fi

# Preview builds are named after the latest published release plus the slug:
# 0.5.0-dark-mode. Prereleases (our own previews among them) do not count.
LATEST=$(gh release view -R "$REPO" --json tagName -q .tagName 2>/dev/null || echo v0.0.0)
VERSION="${LATEST#v}-${BRANCH#vibe/"$ISSUE"-}"

# Seen. The reaction API has no endpoint for review bodies, so those go
# without.
if [ "$GITHUB_EVENT_NAME" = issue_comment ]; then
    gh api -X POST "repos/$REPO/issues/comments/$(jq -r .comment.id "$EV")/reactions" \
        -f content=eyes >/dev/null || true
fi

# --- the context the agent reads --------------------------------------------

comments='.[] | "### @\(.user.login) at \(.created_at)\n\n\(.body)\n"'

mkdir -p "$CONTEXT_DIR"
{
    gh issue view "$ISSUE" -R "$REPO" --json title,body,author \
        -q '"# Issue #'"$ISSUE"': \(.title)\n\n_Opened by @\(.author.login)_\n\n\(.body)\n"'
    echo "## Comments on the issue"
    echo
    gh api --paginate "repos/$REPO/issues/$ISSUE/comments" -q "$comments"

    if [ -n "$PR" ]; then
        gh pr view "$PR" -R "$REPO" --json title,body \
            -q '"# Pull request #'"$PR"': \(.title)\n\n\(.body)\n"'
        echo "## Conversation on the pull request"
        echo
        gh api --paginate "repos/$REPO/issues/$PR/comments" -q "$comments"
        echo "## Reviews"
        echo
        gh api --paginate "repos/$REPO/pulls/$PR/reviews" \
            -q '.[] | select(.body != "") | "### @\(.user.login), \(.state), at \(.submitted_at)\n\n\(.body)\n"'
        echo "## Inline review comments"
        echo
        gh api --paginate "repos/$REPO/pulls/$PR/comments" \
            -q '.[] | "### @\(.user.login) on \(.path):\(.line // .original_line) at \(.created_at)\n\n```diff\n\(.diff_hunk)\n```\n\n\(.body)\n"'
        LAST=$(gh pr view "$PR" -R "$REPO" --json commits -q '.commits[-1].committedDate')
    fi

    echo "# What started this run"
    echo
    echo "$TRIGGER_URL"
    echo
    printf '%s\n\n' "$TRIGGER_BODY"
    if [ -n "${LAST:-}" ]; then
        echo "The branch was last updated at $LAST. Everything @$OWNER wrote after"
        echo "that is new feedback: address all of it, not only the message above."
    fi
} > "$CONTEXT_DIR/context.md"

out run true
out issue "$ISSUE"
out pr "$PR"
out branch "$BRANCH"
out exists "$EXISTS"
out version "$VERSION"
echo "issue #$ISSUE, PR #${PR:-none}, branch $BRANCH (exists: $EXISTS), version $VERSION"
