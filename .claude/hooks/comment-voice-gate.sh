#!/bin/bash
# Agent gate: does the staged diff add code comments that describe where the code came
# from instead of what it does? Flagged: authoring-process voice, audit/decision-record
# citations, milestone/date provenance, and historical narration. This distinction needs
# judgment, not a regex — "session", "the user", and quoted first person are all legitimate
# app-domain vocabulary — so a headless model reviews only the ADDED comment lines and
# returns a verdict.
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

PROMPT="You are a commit gate for an Android codebase largely written by delegated coding agents. Below are the COMMENT lines a staged commit ADDS (unified-diff '+' lines). Decide whether any comment describes where the code CAME FROM — the authoring process, its provenance, or its edit history — instead of what the code does.

FLAG (leak): references to the development conversation or orchestration — agents, waves, worktrees, sessions-of-work, the orchestrator/reviewer, instructions or prompts given to the author ('as requested', 'per the brief', 'the task said'), scope-of-my-work language ('not this wave', 'out of my scope', 'a sibling agent owns'), or first-person narration of the editing process ('I moved this', 'let me', 'I'll keep'). ALSO FLAG provenance and history: citations of audit findings or decision/planning records ('audit UI-9', 'PERF-25', 'DECISIONS 2026-08-07', 'STATUS backlog', 'docs/PLAN.md'), milestone or development-date provenance ('M7', 'added 2026-08-08'), and historical narration of past code states ('this used to be a runBlocking', 'previously', 'originally').

DO NOT FLAG: present-tense statements of a real constraint or rationale the code cannot show; quoted protocol or UX semantics including first person inside quotes ('\"I am not ready yet\"'); references to the app's end user ('the user asked to be offline'); TODOs that name code-owned work.

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
