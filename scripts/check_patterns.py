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
    # DL-01 (audit 2026-07): readTimeout(0) = a socket that never fails, never retries, and
    # wedges the drain lease for the process. Zero-baseline; timeouts must be finite.
    "readtimeout-zero": {
        "regex": re.compile(r"readTimeout\(\s*(0[,)]|Duration\.ZERO)"),
        "main_only": True,
    },
    # PERF-18 / DL-05 / PERF-3 (audits 2026-07/08): full-row reads of the `items` table pull
    # multi-KB dto blobs per row; hot paths must use projections (ItemCacheKey/FacetKey
    # pattern). Existing full-row reads are baselined; new ones need the projection question
    # answered first.
    "select-star-items": {
        "regex": re.compile(r"(?i)SELECT\s+(\*|\w+\.\*)\s+FROM\s+items\b"),
        "main_only": True,
    },
    # UI-03 / a11y text-scaling wave (2026-08-05): fixed-height frames around sp text clip at
    # font scale 2.0. requiredHeight is the sharpest offender shape; ratcheted, shrink-only.
    "required-height": {
        "regex": re.compile(r"\brequiredHeight\s*\("),
        "main_only": True,
    },
    # UI-6 (audit 2026-08-08): user-visible formatting hardcoded to a fixed locale draws
    # `8.6` beside `8,6` on the same screen. Protocol formatting belongs in named helpers;
    # display formatting takes the UI locale.
    "hardcoded-locale": {
        "regex": re.compile(r"\bLocale\.(US|ENGLISH|ROOT)\b"),
        "main_only": True,
    },
    # ARCH-2 (audits 2026-08-06/08, twice): modules in the layered core kept accreting public
    # top-level declarations with zero external consumers — :player needed a 129→28 sweep,
    # then :data needed a 14-declaration sweep the next audit. Every NEW public top-level
    # declaration in data/* or core/* now requires a deliberate baseline bump; default to
    # `internal`. (Feature modules and :app are exempt — screens are public by nature.)
    "public-toplevel-layered": {
        "regex": re.compile(
            r"^(?:@\w+(?:\([^)]*\))?\s+)*"
            r"(?:(?:sealed|data|enum|open|abstract|value|annotation|inline|fun)\s+)*"
            r"(?:class|interface|object|fun|val|var)\s"
        ),
        "main_only": True,
        # core/ui is exempt: a design system exports composables by design, and ratchet
        # fatigue there would devalue the gate everywhere else.
        "path_filter": re.compile(r"^(data|core/(common|network|database|datastore))/"),
        "exclude_line": re.compile(r"^\s|^(internal|private)\b"),
    },
    # ARCH-1/9/10 (audits 2026-08-06/08): `api(...)` exports leaked the SDK, Coil and Media3
    # onto consumers' compile classpaths; each demotion to `implementation` was an audit
    # finding. A new `api(` in any build file is now a deliberate, baselined act.
    "api-dependency": {
        "regex": re.compile(r"^\s*api\((?!.*projects\.)"),
        "main_only": False,
        "path_filter": re.compile(r"build\.gradle\.kts$"),
        "keep_comments": False,
    },
    # Agent-process voice in code comments. Most of this codebase is written by delegated
    # agents, and a comment addressed to the reviewer/orchestrator ("as requested", "per the
    # brief", "this wave", first-person narration) is noise the moment the change lands — it
    # describes the conversation, not the code. House style still WANTS audit citations
    # ("audit UI-9") and historical KDoc ("this used to be a runBlocking"); those are about
    # the code and are deliberately not matched here. Comments only; zero tolerance.
}
# Comment-voice checking is out of scope for this file: the offending vocabulary
# ("session", "the user", first person) is also legitimate app-domain vocabulary, so the
# distinction needs judgment, not a regex. It lives in .claude/hooks/comment-voice-gate.sh.

COMMENT = re.compile(r"^\s*(//|\*|/\*)")


def tracked_kotlin() -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files", "*.kt", "*.kts"], cwd=REPO, capture_output=True, text=True
    ).stdout.split()
    return [REPO / p for p in out if not p.startswith(".claude/worktrees/")]


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
                if "path_filter" in spec and not spec["path_filter"].search(rel):
                    continue
                if spec.get("comments_only"):
                    # A "comment line" for this purpose: block/KDoc continuation, or any
                    # line with a line comment — check only the comment tail there.
                    if stripped_comment:
                        pass
                    elif "//" in line:
                        line_for_match = line[line.index("//"):]
                        if spec["regex"].search(line_for_match):
                            counts[name][rel] += 1
                        continue
                    else:
                        continue
                elif stripped_comment and not spec.get("keep_comments"):
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
