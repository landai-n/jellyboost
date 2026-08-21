#!/usr/bin/env python3
"""Ratcheted source-pattern gate — audit 2026-08-08's recurring hazards, pinned.

Each pattern below caused repeated audit findings; the audits' remediation drove the
count to what `pattern-baseline.json` records. This script fails when any file EXCEEDS
its baselined count (or a new file appears for a pattern), so reintroducing a hazard is
a deliberate, reviewable act: either fix the code, or update the baseline in the same
commit with a justification the reviewer can see.

Counts may go DOWN freely; run with --update after genuine cleanups to tighten the
ratchet. Comment lines (// and * blocks) are skipped so prose about a hazard never
counts as one.

Patterns and their audit lineage:
  runBlocking            PERF-19 — the tree reached zero production runBlocking; keep it there.
  runCatching            H4 / HYG-4 / QUAL-2 — plain runCatching swallows CancellationException
                         in suspend contexts; use runCatchingUnlessCancelled (core/common).
  dispatchers-literal    HYG-11 / QUAL-3 — hardcoded Dispatchers.* defeats the injected
                         qualifiers (@IoDispatcher & co.); only DI providers may name them.
  globalscope            audit hygiene sweep — zero uses; keep it there.
  double-bang            HYG-6 — zero `!!` in production; keep it there.
  uppercase-no-locale    UI-9 — display casing without the UI locale (Turkish İ) and without
                         a sentence-case contentDescription; see TagPill in PlayerControls.
  conflict-marker        QUAL-1's sibling hazard — merge markers must never be committed.
"""

import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BASELINE = REPO / "scripts" / "pattern-baseline.json"

PATTERNS = {
    "runBlocking": {
        "regex": re.compile(r"\brunBlocking\b"),
        "main_only": True,
    },
    "runCatching": {
        "regex": re.compile(r"\brunCatching\b(?!\w)"),
        "exclude_line": re.compile(r"runCatchingUnlessCancelled"),
        "main_only": True,
    },
    "dispatchers-literal": {
        "regex": re.compile(r"\bDispatchers\.(IO|Main|Default|Unconfined)\b"),
        "main_only": True,
    },
    "globalscope": {
        "regex": re.compile(r"\bGlobalScope\b"),
        "main_only": False,
    },
    "double-bang": {
        "regex": re.compile(r"!!(?![=!])"),
        "main_only": True,
    },
    "uppercase-no-locale": {
        "regex": re.compile(r"\.uppercase\(\)"),
        "main_only": True,
    },
    "conflict-marker": {
        "regex": re.compile(r"^(<{7} |={7}$|>{7} )"),
        "main_only": False,
        "keep_comments": True,
    },
    # UI-04 (audit 2026-08): Modifier.composed defeats skipping and is deprecated guidance;
    # the perf waves removed the last use — Modifier.Node or plain factories only.
    "modifier-composed": {
        "regex": re.compile(r"\bcomposed\s*[({]"),
        "main_only": True,
    },
}

COMMENT = re.compile(r"^\s*(//|\*|/\*)")


def tracked_kotlin() -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files", "*.kt", "*.kts"], cwd=REPO, capture_output=True, text=True
    ).stdout.split()
    return [REPO / p for p in out if not p.startswith(".claude/")]


def scan() -> dict[str, dict[str, int]]:
    counts: dict[str, dict[str, int]] = {name: defaultdict(int) for name in PATTERNS}
    for path in tracked_kotlin():
        rel = path.relative_to(REPO).as_posix()
        is_main = "/src/main/" in rel
        try:
            lines = path.read_text(encoding="utf-8").split("\n")
        except (UnicodeDecodeError, FileNotFoundError):
            continue
        for line in lines:
            stripped_comment = bool(COMMENT.match(line))
            for name, spec in PATTERNS.items():
                if spec.get("main_only") and not is_main:
                    continue
                if stripped_comment and not spec.get("keep_comments"):
                    continue
                if "exclude_line" in spec and spec["exclude_line"].search(line):
                    continue
                if spec["regex"].search(line):
                    counts[name][rel] += 1
    return {name: dict(files) for name, files in counts.items()}


def main() -> int:
    current = scan()
    if "--update" in sys.argv:
        BASELINE.write_text(json.dumps(current, indent=2, sort_keys=True) + "\n")
        print(f"baseline updated: {BASELINE.relative_to(REPO)}")
        return 0

    if not BASELINE.exists():
        print("check_patterns: no baseline file — run scripts/check_patterns.py --update first")
        return 1

    baseline = json.loads(BASELINE.read_text())
    failures = []
    for name, files in current.items():
        base_files = baseline.get(name, {})
        for rel, n in sorted(files.items()):
            allowed = base_files.get(rel, 0)
            if n > allowed:
                failures.append(f"  {name}: {rel} has {n} (baseline {allowed})")

    if failures:
        print("check_patterns: ratchet violations — fix the code, or (deliberately, with a")
        print("reviewable reason) run scripts/check_patterns.py --update in the same commit:")
        print("\n".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
