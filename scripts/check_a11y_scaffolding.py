#!/usr/bin/env python3
"""Accessibility-test scaffolding gate — accessibility audit 2026-08-05, CR-7.

CR-7 was "no `androidTest` source set in any of 17 modules": zero instrumented tests, the
ATF/espresso dependencies not even in the version catalog, and `connectedDebugAndroidTest`
with nothing to run. The remediation built the scaffolding — `AccessibilityChecks` (Google's
Accessibility Test Framework pointed at Compose's semantics tree) plus a suite per surface —
but only in the four modules the fixes happened to land in. Ten other modules draw
`@Composable` screens with no instrumented a11y coverage at all, and, more to the point,
nothing stops the eleventh from being added.

So this script asks one question of every Gradle module that declares composables: **is there
an accessibility test suite here, or a written reason there isn't?** A module satisfies it by

  (a) having a `src/androidTest` `.kt` file that either references the ATF harness
      (`AccessibilityChecks`, `AccessibilityValidator`, the `…accessibility.framework` package)
      or is named `*A11y*Test.kt` — the project's two shapes of accessibility test, one
      checking a rendered view with ATF and one asserting the semantics tree directly; or

  (b) appearing in `scripts/a11y-scaffolding-allowlist.json`, whose value is the reason and
      the date the module started owing one.

The allowlist is seeded with today's real gaps, so this passes on the tree as it stands. It is
a ratchet in the same spirit as `check_patterns.py`: adding a module with screens and no a11y
tests is now a deliberate act that costs a line of JSON and a sentence of justification, and
stale entries (a module that has since grown a suite, or stopped drawing anything) fail too,
so the list cannot quietly become a graveyard.

What this does NOT check is whether the suite is any good — that is what
`connectedDebugAndroidTest` on a real device is for (milestone DoD; it is not in `/verify`,
which is device-less). This is a presence check, and presence is the thing that was missing.
"""

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ALLOWLIST = REPO / "scripts" / "a11y-scaffolding-allowlist.json"
SETTINGS = REPO / "settings.gradle.kts"

INCLUDE = re.compile(r'^include\("(:[^"]+)"\)', re.MULTILINE)
COMPOSABLE = re.compile(r"@Composable\b")
ATF_MARKERS = (
    "AccessibilityChecks",
    "AccessibilityValidator",
    "accessibility.framework",
    "setComposeAccessibilityValidator",
)
A11Y_TEST_FILENAME = re.compile(r"A11y.*Test\.kt$", re.IGNORECASE)


def modules() -> list[str]:
    """Gradle paths from settings.gradle.kts, in declaration order."""
    return INCLUDE.findall(SETTINGS.read_text(encoding="utf-8"))


def module_dir(gradle_path: str) -> Path:
    return REPO / gradle_path.lstrip(":").replace(":", "/")


def draws_composables(directory: Path) -> bool:
    main = directory / "src" / "main"
    return main.is_dir() and any(
        COMPOSABLE.search(f.read_text(encoding="utf-8", errors="replace"))
        for f in main.rglob("*.kt")
    )


def has_a11y_suite(directory: Path) -> bool:
    android_test = directory / "src" / "androidTest"
    if not android_test.is_dir():
        return False
    for f in android_test.rglob("*.kt"):
        if A11Y_TEST_FILENAME.search(f.name):
            return True
        text = f.read_text(encoding="utf-8", errors="replace")
        if any(marker in text for marker in ATF_MARKERS):
            return True
    return False


def main() -> int:
    raw = json.loads(ALLOWLIST.read_text(encoding="utf-8")) if ALLOWLIST.exists() else {}
    # `_`-prefixed keys are the file's own documentation — JSON has no comments and the reason
    # this list exists at all does not belong only in this script.
    allowlist = {k: v for k, v in raw.items() if not k.startswith("_")}
    declared = modules()
    errors = []

    owing = []
    for gradle_path in declared:
        directory = module_dir(gradle_path)
        if not directory.is_dir():
            errors.append(f"{gradle_path}: declared in settings.gradle.kts but the directory is missing")
            continue
        if draws_composables(directory) and not has_a11y_suite(directory):
            owing.append(gradle_path)

    for gradle_path in owing:
        if gradle_path not in allowlist:
            errors.append(
                f"{gradle_path}: draws @Composable screens but has no accessibility test in "
                f"src/androidTest (an ATF suite, or a *A11y*Test.kt semantics test). Add one, or "
                f"add an entry to scripts/a11y-scaffolding-allowlist.json saying why not and since when."
            )

    # The other direction: an allowlist that outlives its reason stops being a record of debt
    # and becomes a place modules go to be forgotten.
    for gradle_path in allowlist:
        if gradle_path not in declared:
            reason = "is not a module in settings.gradle.kts"
        elif gradle_path in owing:
            continue
        elif not draws_composables(module_dir(gradle_path)):
            reason = "no longer declares any @Composable"
        else:
            reason = "now has an accessibility test — the debt is paid"
        errors.append(
            f"{gradle_path}: allowlisted, but it {reason}. Remove the entry from "
            f"scripts/a11y-scaffolding-allowlist.json."
        )

    if errors:
        print("check_a11y_scaffolding: accessibility scaffolding violations:")
        for e in errors:
            print(f"  {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
