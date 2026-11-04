#!/usr/bin/env python3
"""Fails when a local link in the site or docs points at a missing file."""
import pathlib, re, sys

ROOTS = [pathlib.Path("site"), pathlib.Path("docs"), pathlib.Path("README.md"),
         pathlib.Path("CONTRIBUTING.md"), pathlib.Path("CHANGELOG.md"),
         pathlib.Path("SECURITY.md"), pathlib.Path("examples")]
LINK = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")

# Generated and vendored directories hold third-party markdown whose links are relative to
# packages we do not ship. Checking them reports failures we can neither fix nor publish.
SKIP_DIRS = {"node_modules", "_site", "dist", ".ssr", "build", ".gradle", ".git"}

def local_target(source, target):
    target = target.split("#")[0].strip()
    if not target or target.startswith(("http://", "https://", "mailto:", "#")):
        return None
    return (source.parent / target).resolve()

def main():
    broken = []
    checked = 0
    for root in ROOTS:
        if root.is_file():
            files = [root]
        else:
            files = [path for path in root.rglob("*")
                     if not SKIP_DIRS.intersection(path.relative_to(root).parts)]
        for path in files:
            if path.suffix not in {".md", ".html"}:
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            for match in LINK.finditer(text):
                target = local_target(path, match.group(2))
                if target is None:
                    continue
                checked += 1
                if not target.exists():
                    broken.append(f"{path}: {match.group(2)}")
    if broken:
        print("Broken local links:")
        for item in broken:
            print("  " + item)
        return 1
    print(f"Checked {checked} local links, all resolve.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
