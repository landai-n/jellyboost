#!/bin/bash
# PostToolUse hook (Edit|Write): mark verify state stale for Kotlin files, run ktlint as a
# warning-only check. Must never hard-block: any unexpected error falls through to exit 0.

INPUT="$(cat)"

# Resolve the repo root of the checkout the edit actually happened in: for a
# worktree-isolated agent that is the worktree (with its own gitignored .claude/state) —
# trusting CLAUDE_PROJECT_DIR there would stale-mark the main checkout's verify state
# (same bug as fixed in pre-commit-gate.sh).
HOOK_CWD="$(printf '%s' "$INPUT" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("")
    sys.exit(0)
print(data.get("cwd", "") or "")
' 2>/dev/null)"

REPO_ROOT=""
if [ -n "$HOOK_CWD" ]; then
  REPO_ROOT="$(git -C "$HOOK_CWD" rev-parse --show-toplevel 2>/dev/null)"
fi
if [ -z "$REPO_ROOT" ]; then
  REPO_ROOT="${CLAUDE_PROJECT_DIR:-}"
fi
if [ -z "$REPO_ROOT" ]; then
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi
cd "$REPO_ROOT" 2>/dev/null || exit 0

FILE_PATH="$(printf '%s' "$INPUT" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("")
    sys.exit(0)
print(data.get("tool_input", {}).get("file_path", "") or "")
' 2>/dev/null)"

# Anything goes wrong above (bad JSON, no python3, etc.) -> just exit quietly.
if [ -z "$FILE_PATH" ]; then
  exit 0
fi

case "$FILE_PATH" in
  *.kt|*.kts)
    ;;
  *)
    exit 0
    ;;
esac

(
  mkdir -p .claude/state && touch .claude/state/verify-stale
) 2>/dev/null

if command -v ktlint >/dev/null 2>&1; then
  KTLINT_OUTPUT="$(ktlint --relative "$FILE_PATH" 2>&1)"
  KTLINT_STATUS=$?
  if [ "$KTLINT_STATUS" -ne 0 ] && [ -n "$KTLINT_OUTPUT" ]; then
    echo "ktlint (non-blocking): $KTLINT_OUTPUT"
  fi
fi

exit 0
