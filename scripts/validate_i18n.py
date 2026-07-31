#!/usr/bin/env python3
"""Validate Jellyboost translated strings.xml files against the English sources.

Usage: python3 validate_i18n.py [--locales fr,it,...] [--root <worktree>]
Checks, per locale file:
  - XML well-formedness
  - string/plurals name parity with the source (missing / extra), skipping
    translatable="false" source entries
  - format-placeholder parity (%1$s, %d, ...): exact multiset for <string>,
    subset-of-source allowed for plural items (CLDR lets 'one' drop the number)
  - plural quantities are legal and include 'other'
  - unescaped apostrophes / double quotes in text nodes
Exit 0 if clean, 1 otherwise. Prints one line per problem.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

MODULES = [
    "app/src/main/res",
    "core/ui/src/main/res",
    "data/downloads/src/main/res",
    "feature/auth/src/main/res",
    "feature/detail/src/main/res",
    "feature/downloads/src/main/res",
    "feature/home/src/main/res",
    "feature/library/src/main/res",
    "feature/search/src/main/res",
    "feature/settings/src/main/res",
    "player/src/main/res",
]

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdif]")
LEGAL_QUANTITIES = {"zero", "one", "two", "few", "many", "other"}
# raw ' or " not preceded by a backslash (and not an XML entity — ET already decoded)
BAD_APOSTROPHE = re.compile(r"(?<!\\)'")
BAD_QUOTE = re.compile(r'(?<!\\)"')


def text_of(elem):
    return "".join(elem.itertext())


def parse_file(path):
    """Return ({string_name: text}, {plural_name: {quantity: text}})."""
    tree = ET.parse(path)
    strings, plurals = {}, {}
    for child in tree.getroot():
        if child.tag == "string":
            if child.get("translatable") == "false":
                continue
            strings[child.get("name")] = text_of(child)
        elif child.tag == "plurals":
            items = {}
            for item in child:
                items[item.get("quantity")] = text_of(item)
            plurals[child.get("name")] = items
    return strings, plurals


def check_text(problems, where, text):
    # A fully double-quoted resource ("...") is legal Android and disables escaping.
    if len(text) >= 2 and text.startswith('"') and text.endswith('"'):
        return
    if BAD_APOSTROPHE.search(text):
        problems.append(f"{where}: unescaped apostrophe in: {text!r}")
    if BAD_QUOTE.search(text):
        problems.append(f"{where}: unescaped double quote in: {text!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--locales", help="comma-separated locale qualifiers (values-<q>); default: all found")
    ap.add_argument("--root", default=".", help="worktree root")
    args = ap.parse_args()
    root = Path(args.root)
    want = set(args.locales.split(",")) if args.locales else None

    problems = []
    checked = 0
    for module in MODULES:
        src = root / module / "values" / "strings.xml"
        s_strings, s_plurals = parse_file(src)
        locale_dirs = sorted(d for d in (root / module).iterdir() if d.name.startswith("values-"))
        for ld in locale_dirs:
            q = ld.name[len("values-"):]
            if want is not None and q not in want:
                continue
            f = ld / "strings.xml"
            where = f.relative_to(root)
            if not f.is_file():
                problems.append(f"{where}: missing strings.xml")
                continue
            try:
                t_strings, t_plurals = parse_file(f)
            except ET.ParseError as e:
                problems.append(f"{where}: XML parse error: {e}")
                continue
            checked += 1
            for name in sorted(set(s_strings) - set(t_strings)):
                problems.append(f"{where}: missing string '{name}'")
            for name in sorted(set(t_strings) - set(s_strings)):
                problems.append(f"{where}: extra string '{name}' not in source")
            for name in sorted(set(s_plurals) - set(t_plurals)):
                problems.append(f"{where}: missing plurals '{name}'")
            for name in sorted(set(t_plurals) - set(s_plurals)):
                problems.append(f"{where}: extra plurals '{name}' not in source")

            for name, text in t_strings.items():
                if name not in s_strings:
                    continue
                if Counter(PLACEHOLDER.findall(text)) != Counter(PLACEHOLDER.findall(s_strings[name])):
                    problems.append(
                        f"{where}: placeholder mismatch in '{name}': "
                        f"source {PLACEHOLDER.findall(s_strings[name])} vs {PLACEHOLDER.findall(text)}"
                    )
                check_text(problems, f"{where}:{name}", text)

            for name, items in t_plurals.items():
                if name not in s_plurals:
                    continue
                src_ph = Counter(PLACEHOLDER.findall(s_plurals[name]["other"]))
                if "other" not in items:
                    problems.append(f"{where}: plurals '{name}' has no 'other' quantity")
                for qty, text in items.items():
                    if qty not in LEGAL_QUANTITIES:
                        problems.append(f"{where}: plurals '{name}' illegal quantity '{qty}'")
                    got = Counter(PLACEHOLDER.findall(text))
                    if not all(got[k] <= src_ph[k] for k in got) and got != src_ph:
                        problems.append(
                            f"{where}: plurals '{name}' [{qty}] placeholders {sorted(got.elements())} "
                            f"not a subset of source {sorted(src_ph.elements())}"
                        )
                    check_text(problems, f"{where}:{name}[{qty}]", text)

    for p in problems:
        print(p)
    print(f"-- checked {checked} locale files, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
