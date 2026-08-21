#!/bin/bash
# PreToolUse hook (matcher: Bash): gate `git commit` invocations on a fresh /verify and a
# conventional-commit message. A bug in this script must never hard-block unrelated Bash
# calls -- any parsing failure falls through to a silent allow (exit 0, no output).

INPUT="$(cat)"

# Resolve the repo root of the checkout the command actually runs in: for a worktree-isolated
# agent that is the worktree (with its own gitignored .claude/state), while CLAUDE_PROJECT_DIR
# always names the main checkout — trusting it there gates commits on another agent's state.
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

COMMAND="$(printf '%s' "$INPUT" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("")
    sys.exit(0)
print(data.get("tool_input", {}).get("command", "") or "")
' 2>/dev/null)"

deny() {
  reason="$1"
  python3 -c '
import json, sys
reason = sys.argv[1]
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason,
    }
}))
' "$reason" 2>/dev/null
  exit 0
}

# Not a git commit invocation at all -> allow silently.
case "$COMMAND" in
  *"git commit"*) ;;
  *) exit 0 ;;
esac

# Audit-derived guardrails (2026-08-21). These run on EVERY commit, including docs-only
# ones — the QUAL-1 DECISIONS corruption and both identifier scrubs arrived via docs
# commits that the verify fast-path below deliberately lets through. Each script is a
# sub-second scan; a missing script (older checkout) falls through to allow.
if [ -f scripts/check_docs.py ]; then
  DOCS_OUT="$(python3 scripts/check_docs.py 2>&1)" || deny "check_docs failed: $DOCS_OUT"
fi
if [ -f scripts/check_identifiers.py ]; then
  IDS_OUT="$(python3 scripts/check_identifiers.py 2>&1)" || deny "check_identifiers failed: $IDS_OUT"
fi
if [ -f scripts/check_patterns.py ]; then
  PAT_OUT="$(python3 scripts/check_patterns.py 2>&1)" || deny "check_patterns failed: $PAT_OUT"
fi
if [ -f scripts/check_redaction.py ]; then
  RED_OUT="$(python3 scripts/check_redaction.py 2>&1)" || deny "check_redaction failed: $RED_OUT"
fi
if [ -f scripts/check_a11y_scaffolding.py ]; then
  A11Y_OUT="$(python3 scripts/check_a11y_scaffolding.py 2>&1)" || deny "check_a11y_scaffolding failed: $A11Y_OUT"
fi
# Agent gate (not a regex): a headless model reviews the staged diff's ADDED comment lines
# for authoring-process voice ("not this wave", "as requested", first-person edit
# narration). Fail-open on plumbing, deny on verdict — see comment-voice-gate.sh.
if [ -x .claude/hooks/comment-voice-gate.sh ]; then
  VOICE_OUT="$(.claude/hooks/comment-voice-gate.sh 2>/dev/null)" || deny "comment-voice gate: agent-process voice in added comments — rewrite them to describe the code, not the conversation: $VOICE_OUT"
fi

# Determine whether this commit touches only docs/markdown files.
DOCS_ONLY=0
STAGED_FILES="$(git diff --cached --name-only 2>/dev/null)"
if [ -n "$STAGED_FILES" ]; then
  DOCS_ONLY=1
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      *.md) ;;
      *) DOCS_ONLY=0 ;;
    esac
  done <<EOF
$STAGED_FILES
EOF
fi

if [ "$DOCS_ONLY" -ne 1 ]; then
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
    deny "Sources changed since last successful verify — run /verify first"
  fi
fi

# Conventional-commit message check (lenient parse of the first -m argument).
MSG_CHECK="$(printf '%s' "$COMMAND" | python3 -c '
import re, shlex, sys

command = sys.stdin.read()
try:
    tokens = shlex.split(command)
except ValueError:
    # Unbalanced quotes etc -- cannot parse leniently, do not block on this.
    print("NO_MESSAGE")
    sys.exit(0)

message = None
for i, tok in enumerate(tokens):
    if tok in ("-m", "--message") and i + 1 < len(tokens):
        message = tokens[i + 1]
        break
    if tok.startswith("--message="):
        message = tok[len("--message="):]
        break

if message is None:
    print("NO_MESSAGE")
    sys.exit(0)

pattern = r"^(feat|fix|refactor|test|docs|chore|build)(\(.+\))?: "
if re.match(pattern, message):
    print("OK")
else:
    print("BAD")
' 2>/dev/null)"

if [ "$MSG_CHECK" = "BAD" ]; then
  deny "Commit message must use a conventional prefix: feat|fix|refactor|test|docs|chore|build"
fi

# NO_MESSAGE (editor flow) or OK -> allow.
exit 0
