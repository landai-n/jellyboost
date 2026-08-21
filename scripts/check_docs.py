#!/usr/bin/env python3
"""Governance-doc integrity gate — born from audit finding QUAL-1 (2026-08-08).

A docs commit once pasted the same DECISIONS.md entry 45 times, scattering ~750 noise
lines through the governance record every auditor and subagent is primed on — and it
went unnoticed for two days because nothing checked the file's own invariants. This
script pins them:

  1. DECISIONS.md `## ` headings are unique (the corruption's exact signature).
  2. No merge-conflict markers survive in any tracked .md file (this session's merges
     left them briefly in the working tree more than once; committing them is the
     same class of silent corruption).
  3. DECISIONS.md dated headings are roughly chronological — the file's template says
     append-only, and the 45x paste broke exactly this. Retroactive entries a few days
     late are established practice (two exist as of 2026-08-21), so only a back-step
     of MORE than 3 days fails: that still catches the corruption's 8-day splices.
"""

import datetime
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONFLICT = re.compile(r"^(<{7} |={7}$|>{7} )", re.MULTILINE)
DATED_HEADING = re.compile(r"^## (\d{4}-\d{2}-\d{2})", re.MULTILINE)
MAX_BACKSTEP_DAYS = 3


def main() -> int:
    errors = []

    decisions = (REPO / "DECISIONS.md").read_text(encoding="utf-8")
    headings = re.findall(r"^## .+$", decisions, re.MULTILINE)
    seen: dict[str, int] = {}
    for h in headings:
        seen[h] = seen.get(h, 0) + 1
    for h, n in seen.items():
        if n > 1:
            errors.append(f"DECISIONS.md: heading appears {n} times: {h[:80]}")

    dates = [datetime.date.fromisoformat(d) for d in DATED_HEADING.findall(decisions)]
    for prev, cur in zip(dates, dates[1:]):
        if (prev - cur).days > MAX_BACKSTEP_DAYS:
            errors.append(
                f"DECISIONS.md: dated entries out of order ({prev} then {cur}, "
                f">{MAX_BACKSTEP_DAYS} days back) — append, don't splice"
            )
            break

    tracked_md = subprocess.run(
        ["git", "ls-files", "*.md"], cwd=REPO, capture_output=True, text=True
    ).stdout.split()
    for rel in tracked_md:
        if rel.startswith(".claude/"):
            continue
        text = (REPO / rel).read_text(encoding="utf-8", errors="replace")
        if CONFLICT.search(text):
            errors.append(f"{rel}: contains merge-conflict markers")

    if errors:
        print("check_docs: governance-doc integrity violations:")
        for e in errors:
            print(f"  {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
