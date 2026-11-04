#!/usr/bin/env python3
"""Builds versioned documentation for GitHub Pages.

Documentation must match the release it describes. This script copies the documentation
that belongs to a release tag into `site/_site/versions/<version>/`, and writes a small
index describing every published version, the newest first.

It reads only committed state, so the output is a pure function of the repository. That is
what makes it safe to run in CI and to re-run without producing diffs.
"""
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "site" / "_site" / "versions"
MANIFEST = OUT / "index.json"
# A copy outside _site, because the prerenderer wipes _site and must still know which
# versions exist. This is the file the site reads.
SITE_MANIFEST = ROOT / "site" / "versions.json"


def git(*args):
    """Runs git in the repository and returns stdout, or None when it fails."""
    try:
        return subprocess.run(
            ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def release_tags():
    """Tags that name a release, newest first.

    Version order comes from git's own tag sorting, which handles `v0.2.0` after `v0.1.0`
    without this script having to parse semver.
    """
    output = git("tag", "--list", "v*", "--sort=-v:refname")
    if not output:
        return []
    return [line.strip() for line in output.splitlines() if line.strip()]


def tag_commit(tag):
    return git("rev-list", "-n", "1", tag)


def docs_at(tag):
    """Every documentation file that exists at `tag`, as repository-relative paths."""
    output = git("ls-tree", "-r", "--name-only", tag, "docs/")
    if not output:
        return []
    return [
        line.strip()
        for line in output.splitlines()
        if line.strip().endswith((".md", ".oq"))
    ]


def tagged_version(tag):
    """The page path for a tag: `v0.1.0-alpha.1` becomes `0.1.0-alpha.1`."""
    return tag[1:] if tag.startswith("v") else tag


def build_one(tag, destination):
    """Writes one version's documentation, with a page stating what it belongs to."""
    files = docs_at(tag)
    if not files:
        return None
    destination.mkdir(parents=True, exist_ok=True)
    copied = 0
    for path in files:
        content = git("show", f"{tag}:{path}")
        if content is None:
            continue
        target = destination / pathlib.Path(path).relative_to("docs")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content + "\n", encoding="utf-8")
        copied += 1

    commit = tag_commit(tag) or ""
    (destination / "index.html").write_text(
        f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Oqthara {tag} documentation</title>
  <link rel="stylesheet" href="/oqthara/assets/site.css">
</head>
<body>
  <main id="main">
    <h1>Oqthara {tag}</h1>
    <p>Documentation as published in the <code>{tag}</code> release
    (commit <code>{commit[:12]}</code>).</p>
    <p><a href="/oqthara/docs.html">Current development documentation</a></p>
    <h2>Files</h2>
    <ul>
"""
        + "\n".join(
            f'      <li><a href="{pathlib.Path(p).relative_to("docs").as_posix()}">'
            f'{pathlib.Path(p).relative_to("docs").as_posix()}</a></li>'
            for p in files
        )
        + """
    </ul>
  </main>
</body>
</html>
""",
        encoding="utf-8",
    )
    return {"tag": tag, "path": destination.name, "commit": commit, "files": copied}


def main():
    tags = release_tags()
    if not tags:
        print("no release tags found; skipping versioned documentation")
        OUT.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(json.dumps({"versions": []}, indent=2) + "\n", encoding="utf-8")
        SITE_MANIFEST.write_text(json.dumps({"versions": []}, indent=2) + "\n", encoding="utf-8")
        return 0

    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)

    versions = []
    for tag in tags:
        entry = build_one(tag, OUT / tagged_version(tag))
        if entry:
            versions.append(entry)
            print(f"wrote {entry['path']} ({entry['files']} files) for {tag}")

    payload = json.dumps(
        {"latest": versions[0]["tag"] if versions else None, "versions": versions}, indent=2
    ) + "\n"
    MANIFEST.write_text(payload, encoding="utf-8")
    SITE_MANIFEST.write_text(payload, encoding="utf-8")
    print(f"wrote {MANIFEST.relative_to(ROOT)} with {len(versions)} versions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
