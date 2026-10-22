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
# --max-price alone is no limit: Vibe prices the server-routed model at
# zero, so the token cap is the one that actually holds.
vibe --trust --auto-approve \
    --max-turns "${VIBE_MAX_TURNS:-200}" \
    --max-price "${VIBE_MAX_PRICE:-5}" \
    --max-tokens "${VIBE_MAX_TOKENS:-20000000}" \
    --output-dir "$SESSION" \
    --prompt-file "$VIBE_OUT/task.md" \
    < /dev/null || status=$?

# Cost, tokens and why it stopped, for the log. The effective config is
# left out in case it ever carries a credential.
jq 'with_entries(select(.key | test("config") | not))' "$SESSION/export.json" || true

# What the run cost, for the PR comment. Vibe's own figure when it has one;
# otherwise an estimate from the tokens at Mistral Medium 3.5's list prices
# (USD per million: input 1.50, cached input 0.15, output 7.50).
jq --arg vin "${VIBE_PRICE_IN:-1.5}" --arg vcached "${VIBE_PRICE_CACHED:-0.15}" \
    --arg vout "${VIBE_PRICE_OUT:-7.5}" '
    .usage as $u
    | ($u.cached_input_tokens // 0) as $cached
    | {
        cost: (if (.cost_usd // 0) > 0 then .cost_usd
               else ((($u.input_tokens - $cached) * ($vin | tonumber)
                      + $cached * ($vcached | tonumber)
                      + $u.output_tokens * ($vout | tonumber)) / 1e6) end),
        estimated: ((.cost_usd // 0) <= 0),
        tokens: $u.total_tokens,
        cached: $cached,
        steps: .steps,
        minutes: (((.ended_at | sub("\\.[0-9]+Z$"; "Z") | fromdate)
                   - (.started_at | sub("\\.[0-9]+Z$"; "Z") | fromdate)) / 60 | floor),
        outcome: .outcome,
        stop_reason: .stop_reason
    }' "$SESSION/export.json" > "$VIBE_OUT/usage.json" || true
exit "$status"
