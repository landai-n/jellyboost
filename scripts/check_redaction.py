#!/usr/bin/env python3
"""Credential-redaction gate — born from NET-02, SEC-09 and SEC-12.

Three audits in a row found the same latent leak: a Kotlin `data class` whose generated
`toString()` would print a token, password, or tokened URL the moment anything logs it or
wraps it in an exception message (`StoredSession`, `LoginUiState`,
`RemotePlaybackMediaSource`). Each was fixed with a redacting `toString()` override pinned
by a test.

This script makes the pattern a gate: any production `data class` with a constructor
parameter whose name matches the credential shapes below must override `toString()` in its
body. No baseline — a hit is a hit; the fix (an override) is one function, and the audits'
test precedent (`StoredSessionTest`, `RemotePlaybackMediaSourceTest`) shows the shape.

Heuristic honesty: this is a line scanner, not a compiler. It finds `data class X(` and
scans the parameter list for suspicious names, then looks for `override fun toString`
between the declaration and the next top-level declaration. Names it watches:
token/password/secret/apiKey/accessKey/authorization, and *Url params in classes that
also carry one of the former (a URL beside a token is how SEC-12 happened).
"""

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

CREDENTIAL_PARAM = re.compile(
    r"\b(?:val|var)\s+\w*(token|password|secret|apiKey|accessKey|authorization)\w*\s*:",
    re.IGNORECASE,
)
DATA_CLASS = re.compile(r"^\s*(?:\w+\s+)*data class (\w+)\s*[(<]")
TOSTRING = re.compile(r"override fun toString\(")
TOP_LEVEL_DECL = re.compile(r"^(?:@\w|(?:internal |private |public )?(?:sealed |data |enum )*(?:class|interface|object|fun) )")


def main() -> int:
    tracked = subprocess.run(
        ["git", "ls-files", "*/src/main/*.kt"], cwd=REPO, capture_output=True, text=True
    ).stdout.split()

    failures = []
    for rel in tracked:
        if rel.startswith(".claude/worktrees/"):
            continue
        lines = (REPO / rel).read_text(encoding="utf-8", errors="replace").split("\n")
        i = 0
        while i < len(lines):
            m = DATA_CLASS.match(lines[i])
            if not m:
                i += 1
                continue
            name = m.group(1)
            # The class region: from the declaration to the next column-0 top-level
            # declaration (or EOF). Params and body both live inside it.
            end = i + 1
            while end < len(lines) and not (lines[end] and TOP_LEVEL_DECL.match(lines[end]) and not lines[end].startswith(" ")):
                end += 1
            region = "\n".join(lines[i:end])
            if CREDENTIAL_PARAM.search(region) and not TOSTRING.search(region):
                failures.append(f"  {rel}:{i + 1} data class {name} carries a credential-shaped param without a toString() override")
            i = end

    if failures:
        print("check_redaction: credential-bearing data classes must override toString()")
        print("(NET-02/SEC-12 precedent — see StoredSession.kt; pin the override with a test):")
        print("\n".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
