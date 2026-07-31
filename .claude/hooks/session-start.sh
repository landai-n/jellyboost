#!/bin/bash
# SessionStart hook: print governance/context banner to stdout (injected into context).
# Must exit 0 always -- this hook only informs, never blocks.

# Resolve repo root: prefer CLAUDE_PROJECT_DIR, else derive from this script's location.
REPO_ROOT="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$REPO_ROOT" ]; then
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi
cd "$REPO_ROOT" 2>/dev/null || exit 0

{
  echo "== Jellyboost governance =="
  echo "Check docs/PLAN.md before non-trivial decisions; log divergences in DECISIONS.md via /diverge"
  echo

  if [ -f STATUS.md ]; then
    # Print the "## Current milestone" line plus the "### Next" block.
    awk '
      /^## Current milestone/ { print; next }
      /^### Next/ { innext=1; print; next }
      innext && /^###/ && !/^### Next/ { innext=0 }
      innext { print }
    ' STATUS.md
    echo
  fi

  if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && git log -1 >/dev/null 2>&1; then
    git log -1 --format='last commit: %h %cr %s' 2>/dev/null
  fi

  # Verify staleness check.
  STALE=0
  if [ ! -f .claude/state/last-verify ]; then
    STALE=1
  else
    NEWER=$(find . -path ./build -prune -o -path './*/build' -prune -o \
      \( -name '*.kt' -o -name '*.kts' \) -newer .claude/state/last-verify -print 2>/dev/null | head -n 1)
    if [ -n "$NEWER" ]; then
      STALE=1
    fi
  fi

  if [ "$STALE" -eq 1 ]; then
    echo "verify: STALE — run /verify before committing"
  else
    echo "verify: fresh"
  fi
} 2>/dev/null

exit 0
