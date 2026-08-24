#!/bin/bash
# Agent gate: does the staged diff add comments that don't earn their place? A comment
# survives only by stating a constraint, invariant, trap, or external fact the code cannot
# show. Flagged: authoring-process voice, provenance (audit/decision citations, milestones,
# dates, history), and noise (what-the-code-does narration, name-restating KDoc, design
# essays). This needs judgment, not a regex — so a headless model reviews only the ADDED
# comment lines and returns a verdict.
#
# Contract: prints nothing and exits 0 on CLEAN; prints the model's cited lines and exits 1
# on LEAK. Any infrastructure failure (CLI missing, timeout, unparseable output) exits 0
# silently — this gate must never block a commit on plumbing, only on verdicts.
#
# Invocation notes (each one load-bearing):
#   - stdin is closed: `claude -p` blocks forever on an inherited open stdin pipe.
#   - cwd is /private/tmp: run from the repo, the child loads this project's CLAUDE.md,
#     settings and hooks, and answers as a project session instead of a gate.
#   - perl's alarm is the timeout: macOS ships no `timeout(1)`.
#
# Testing: pass a diff file as $1 to review that instead of the staged diff.

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 0

if [ -n "$1" ] && [ -f "$1" ]; then
  DIFF="$(cat "$1")"
else
  DIFF="$(git diff --cached -U0 -- '*.kt' '*.kts' 2>/dev/null)"
fi

# Added lines that carry a comment (line comment anywhere, or block/KDoc continuation).
COMMENTS="$(printf '%s\n' "$DIFF" | grep -E '^\+' | grep -E '(//|^\+\s*\*|/\*)' | grep -vE '^\+\+\+' | head -200)"
[ -z "$COMMENTS" ] && exit 0

command -v claude >/dev/null 2>&1 || exit 0
command -v perl >/dev/null 2>&1 || exit 0

PROMPT="You are a commit gate for an Android codebase largely written by delegated coding agents. Below are the COMMENT lines a staged commit ADDS (unified-diff '+' lines). House rule: a comment earns its place ONLY by stating a constraint, invariant, trap, or external fact the code cannot show — in as few lines as possible. Decide whether any added comment breaks that rule.

FLAG (leak): (1) authoring-process voice — agents, waves, worktrees, sessions-of-work, the orchestrator/reviewer, instructions given to the author ('as requested', 'per the brief'), scope-of-my-work language, first-person narration of the editing process. (2) Provenance and history — citations of audit findings or decision/planning records ('audit UI-9', 'PERF-25', 'DECISIONS 2026-08-07', 'docs/PLAN.md'), milestone or development-date provenance, historical narration of past code states ('this used to be a runBlocking'). (3) Noise — comments that narrate what the adjacent code visibly does or how, KDoc that restates the declaration's name or signature, @param/@return lines that echo the name, design-rationale essays or storytelling, and restatements of documented framework behavior.

DO NOT FLAG: concise present-tense statements of a real constraint, invariant, trap, or measured external fact the code cannot show (threading/ordering/lifetime rules, magic-number arithmetic, server/codec/OEM quirks, 'deliberately not X because Y' guards); quoted protocol or UX semantics including first person inside quotes ('\"I am not ready yet\"'); references to the app's end user; TODOs that name code-owned work; short section dividers.

Reply with EXACTLY one first line: 'VERDICT: CLEAN' or 'VERDICT: LEAK'. If LEAK, list each offending line verbatim on following lines with one short reason each.

COMMENT LINES:
$COMMENTS"

OUT="$(cd /private/tmp && perl -e 'alarm shift; exec @ARGV' 90 \
  claude -p "$PROMPT" --model claude-haiku-4-5-20251001 </dev/null 2>/dev/null)"
[ -z "$OUT" ] && exit 0

case "$OUT" in
  "VERDICT: CLEAN"*) exit 0 ;;
  "VERDICT: LEAK"*)
    printf '%s\n' "$OUT" | tail -n +2 | head -10
    exit 1
    ;;
  *) exit 0 ;;  # unparseable → fail open
esac
