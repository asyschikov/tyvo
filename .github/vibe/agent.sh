#!/usr/bin/env bash
# Hand the task to Mistral Vibe. Runs inside the emulator step, from the
# repo checkout. The agent leaves everything for the publish job in
# $VIBE_OUT: title.txt, summary.md and screenshots/.
#
# Env: MISTRAL_API_KEY, VIBE_OUT, VIBE_CONTEXT, VIBE_MAX_TURNS, VIBE_MAX_PRICE.
set -euo pipefail

BOT_DIR=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$VIBE_OUT/screenshots"
sed -e "s|{{OUT}}|$VIBE_OUT|g" \
    -e "s|{{CONTEXT}}|$VIBE_CONTEXT|g" \
    -e "s|{{OWNER}}|$GITHUB_REPOSITORY_OWNER|g" \
    "$BOT_DIR/prompt.md" > "$VIBE_OUT/task.md"

adb wait-for-device

# The task goes in a file, not on the command line: an issue with a long
# thread can outgrow the kernel's limit on a single argument. The session
# export goes outside the repo and outside the uploaded artifact.
SESSION="$RUNNER_TEMP/vibe-session"
status=0
vibe --trust --auto-approve \
    --max-turns "${VIBE_MAX_TURNS:-200}" \
    --max-price "${VIBE_MAX_PRICE:-5}" \
    --output-dir "$SESSION" \
    --prompt-file "$VIBE_OUT/task.md" \
    < /dev/null || status=$?

# Cost, tokens and why it stopped, for the log. The effective config is
# left out in case it ever carries a credential.
jq 'with_entries(select(.key | test("config") | not))' "$SESSION/export.json" || true
exit "$status"
