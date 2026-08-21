#!/usr/bin/env python3
"""Personal/infrastructure identifier gate — born from two full history rewrites.

This repo's git history has been scrubbed twice (2026-08-01: server host/IP + test-device
identifiers; 2026-08-08: personal names in design mocks, fixtures and docs). Both scrubs
were expensive — filter-repo over every ref, a force-push, and coordination with live
worktrees. The cheap version is to never commit the identifiers again.

The denylist deliberately lives OUTSIDE the repo (../jellyboost-denylist.txt, next to
env.sh): committing the list would itself leak every entry. One lowercase token or
regex per line; `#` comments allowed. If the file is absent (CI, fresh clones), the
check skips silently — it is a local last line of defense, not a portable gate.

On a hit the script reports the file and the token's LINE NUMBER ONLY — never the token —
so its own output stays safe to paste into issues, logs, or transcripts.
"""

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DENYLIST = REPO.parent / "jellyboost-denylist.txt"

TEXT_SUFFIXES = {
    ".kt", ".kts", ".md", ".xml", ".html", ".json", ".yml", ".yaml", ".txt",
    ".pro", ".properties", ".css", ".js", ".svg", ".py", ".sh",
}


def load_patterns() -> list[re.Pattern]:
    patterns = []
    for line in DENYLIST.read_text(encoding="utf-8").split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        patterns.append(re.compile(line, re.IGNORECASE))
    return patterns


def main() -> int:
    if not DENYLIST.exists():
        return 0  # local-only guard; nothing to check against

    patterns = load_patterns()
    if not patterns:
        return 0

    tracked = subprocess.run(
        ["git", "ls-files"], cwd=REPO, capture_output=True, text=True
    ).stdout.split("\n")

    hits = []
    for rel in tracked:
        if not rel or rel.startswith(".claude/"):
            continue
        if Path(rel).suffix not in TEXT_SUFFIXES:
            continue
        try:
            lines = (REPO / rel).read_text(encoding="utf-8", errors="replace").split("\n")
        except FileNotFoundError:
            continue
        for i, line in enumerate(lines, start=1):
            for p_index, pattern in enumerate(patterns):
                if pattern.search(line):
                    hits.append(f"  {rel}:{i} (denylist entry #{p_index + 1})")

    if hits:
        print("check_identifiers: denylisted identifier present in tracked files —")
        print("remove it before committing (the token itself is deliberately not printed):")
        print("\n".join(hits[:40]))
        if len(hits) > 40:
            print(f"  ... and {len(hits) - 40} more")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
