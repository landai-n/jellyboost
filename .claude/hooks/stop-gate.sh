#!/bin/bash
# Stop hook: block ending the turn when there's uncommitted, unverified work, or work that's
# sat uncommitted too long. Only the two documented conditions below should exit 2; anything
# unexpected (not a git repo yet, no commits, parse errors) falls through to a safe exit 0.

REPO_ROOT="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$REPO_ROOT" ]; then
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi
cd "$REPO_ROOT" 2>/dev/null || exit 0

INPUT="$(cat)"

STOP_ACTIVE="$(printf '%s' "$INPUT" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("false")
    sys.exit(0)
print("true" if data.get("stop_hook_active") else "false")
' 2>/dev/null)"

# Loop guard: if this Stop hook already fired for this turn, do not fire again.
if [ "$STOP_ACTIVE" = "true" ]; then
  exit 0
fi

# Edge cases: not a git repo yet, or no commits -> nothing to gate on.
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0
git log -1 >/dev/null 2>&1 || exit 0

DIRTY=0
DIRTY_FILES="$(git status --porcelain 2>/dev/null)"
if [ -n "$DIRTY_FILES" ]; then
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    # porcelain lines: "XY path" (path may contain a rename "-> "); take the last field.
    path="${line#?? }"
    path="${path##* -> }"
    case "$path" in
      *.kt|*.kts|*.md) DIRTY=1 ;;
    esac
  done <<EOF
$DIRTY_FILES
EOF
fi

if [ "$DIRTY" -ne 1 ]; then
  exit 0
fi

STALE=0
if [ -f .claude/state/verify-stale ]; then
  STALE=1
elif [ ! -f .claude/state/last-verify ]; then
  STALE=1
else
  NEWER=$(git ls-files '*.kt' '*.kts' 2>/dev/null | while IFS= read -r f; do
    [ -f "$f" ] && [ "$f" -nt .claude/state/last-verify ] && echo "$f" && break
  done)
  if [ -n "$NEWER" ]; then
    STALE=1
  fi
fi

if [ "$STALE" -eq 1 ]; then
  echo "Quality gate: uncommitted changes with stale verification. Run /verify, then /checkpoint to commit before stopping." >&2
  exit 2
fi

LAST_COMMIT_TS="$(git log -1 --format=%ct 2>/dev/null)"
if [ -n "$LAST_COMMIT_TS" ]; then
  NOW_TS="$(date +%s)"
  AGE=$(( NOW_TS - LAST_COMMIT_TS ))
  if [ "$AGE" -gt 2700 ]; then
    echo "Quality gate: uncommitted work older than 45 min. Run /checkpoint to commit." >&2
    exit 2
  fi
fi

exit 0
