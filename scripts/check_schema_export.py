#!/usr/bin/env python3
"""The exported Room schema for the current DATABASE_VERSION must exist in the tree.

Room writes schema exports into the SOURCE tree (`core/database/schemas/`), not under
`build/`, and the remote build wrapper pulls back only `build/{outputs,reports,test-results}`.
A schema bump gated remotely therefore passes green while `<N>.json` exists only on the
build host — the migration test suite over there sees it, the commit here does not. This
check makes that failure loud on the machine that commits.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONSTANTS = REPO / "core/database/src/main/kotlin/dev/jellyboost/core/database/DatabaseConstants.kt"
SCHEMAS = REPO / "core/database/schemas/dev.jellyboost.core.database.JellyfinDatabase"


def main() -> int:
    text = CONSTANTS.read_text(encoding="utf-8")
    match = re.search(r"DATABASE_VERSION\s*=\s*(\d+)", text)
    if not match:
        print(f"check_schema_export: DATABASE_VERSION not found in {CONSTANTS}")
        return 1

    version = int(match.group(1))
    schema = SCHEMAS / f"{version}.json"
    if not schema.is_file():
        print(
            f"check_schema_export: DATABASE_VERSION is {version} but {schema.relative_to(REPO)} "
            f"does not exist. If the gate ran remotely, the export stayed on the build host — "
            f"generate it locally (JB_LOCAL_ONLY=1 gradlew-remote :core:database:compileDebugKotlin) "
            f"and commit it."
        )
        return 1

    try:
        exported = json.loads(schema.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        print(f"check_schema_export: {schema.relative_to(REPO)} is not valid JSON: {error}")
        return 1

    exported_version = exported.get("database", {}).get("version")
    if exported_version != version:
        print(
            f"check_schema_export: {schema.relative_to(REPO)} declares database version "
            f"{exported_version}, not {version}."
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
