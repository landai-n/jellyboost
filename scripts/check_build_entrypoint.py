#!/usr/bin/env python3
"""The verify skill, CLAUDE.md, and docs/PLAN.md must name the same single build
entry point, and its name must be a bare command — never a path, never anything
that could quietly become a hostname (governance rule 5: no infrastructure
identifiers in the tree)."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILES = [
    ".claude/skills/verify/SKILL.md",
    "CLAUDE.md",
    "docs/PLAN.md",
]
# The quality-gate task triple only ever follows the entry-point command.
GATE_CMD = re.compile(r"([^\s`]+)\s+ktlintCheck detekt testDebugUnitTest")
NAME = re.compile(r"^[a-z][a-z0-9-]{2,31}$")


def main() -> int:
    failures = []
    names = {}
    for rel in FILES:
        text = (ROOT / rel).read_text(encoding="utf-8")
        found = set(GATE_CMD.findall(text))
        if not found:
            failures.append(f"{rel}: no quality-gate command found")
        else:
            names[rel] = found
    all_names = set().union(*names.values()) if names else set()
    if len(all_names) > 1 or any(len(v) > 1 for v in names.values()):
        listing = ", ".join(f"{rel}: {sorted(v)}" for rel, v in sorted(names.items()))
        failures.append(f"build entry point is not single and consistent — {listing}")
    for name in all_names:
        if not NAME.match(name):
            failures.append(
                f"entry point {name!r} is not a bare lowercase command name "
                f"(paths and host-like tokens are forbidden)"
            )
    for f in failures:
        print(f"check_build_entrypoint: {f}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
