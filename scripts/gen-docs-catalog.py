#!/usr/bin/env python3
"""Generate docs/catalog.json from the Markdown files under docs/.

The catalog is machine-readable metadata for the documentation tree: every
``docs/**/*.md`` file becomes one entry, sorted by path so the output is
stable across machines and runs.

Usage:
    python3 scripts/gen-docs-catalog.py            # rewrite docs/catalog.json
    python3 scripts/gen-docs-catalog.py --check    # fail if it is stale

Entry shape::

    {
      "path":    "docs/spec/backend.md",   # repo-relative
      "title":   "Backend",                # first '# ' heading, else filename
      "section": "spec",                   # first segment under docs/, "" at root
      "slug":    "docs/spec/backend"       # path without the .md suffix
    }

The file format is ``{"schema": 1, "docs": [...]}``, pretty-printed with a
trailing newline.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SCHEMA = 1

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
CATALOG = DOCS / "catalog.json"


def title_of(path: Path, text: str) -> str:
    """First Markdown level-one heading, else the file stem."""
    for line in text.splitlines():
        if line.startswith("# "):
            heading = line[2:].strip()
            if heading:
                return heading
    return path.stem


def entry_for(path: Path) -> dict:
    relative = path.relative_to(ROOT)
    parts = relative.parts
    # parts[0] is "docs"; a file directly in docs/ has no section.
    section = parts[1] if len(parts) > 2 else ""
    text = path.read_text(encoding="utf-8")
    return {
        "path": relative.as_posix(),
        "title": title_of(path, text),
        "section": section,
        "slug": relative.with_suffix("").as_posix(),
    }


def build() -> dict:
    paths = sorted(DOCS.rglob("*.md"), key=lambda p: p.relative_to(ROOT).as_posix())
    return {"schema": SCHEMA, "docs": [entry_for(p) for p in paths]}


def render(catalog: dict) -> str:
    return json.dumps(catalog, indent=2, ensure_ascii=False) + "\n"


def main(argv: list[str]) -> int:
    check = False
    for arg in argv:
        if arg == "--check":
            check = True
        elif arg in ("-h", "--help"):
            print(__doc__.strip())
            return 0
        else:
            print(f"gen-docs-catalog: unknown argument {arg!r}", file=sys.stderr)
            return 2

    expected = render(build())

    if check:
        actual = CATALOG.read_text(encoding="utf-8") if CATALOG.exists() else ""
        if actual != expected:
            print(
                "docs/catalog.json is stale: regenerate it with\n"
                "    python3 scripts/gen-docs-catalog.py",
                file=sys.stderr,
            )
            return 1
        count = len(json.loads(expected)["docs"])
        print(f"docs/catalog.json is current ({count} documents)")
        return 0

    CATALOG.write_text(expected, encoding="utf-8")
    count = len(json.loads(expected)["docs"])
    print(f"wrote {CATALOG.relative_to(ROOT).as_posix()} ({count} documents)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:  # fail loudly, never silently pass a check
        print(f"gen-docs-catalog: error: {exc}", file=sys.stderr)
        raise
