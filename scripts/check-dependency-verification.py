#!/usr/bin/env python3
"""Checks that Gradle dependency verification is present and still covers the pins.

TH-44 records that the build resolves two third-party coordinates without
checking what they are: `org.xerial:sqlite-jdbc` and `org.slf4j:slf4j-nop`. Both
moved version once already. A7.4 answers that with `gradle/verification-metadata.xml`,
but the file alone is not the control. Gradle reads it if it is there and silently
does something else if it is not, so a deleted file turns verification off with no
error anywhere. The two ways this control can stop working are both invisible to
`./gradlew build`:

1. The metadata file is deleted or renamed. Every build still succeeds; nothing
   is verified. This is the failure mode that matters, because it is what a
   careless merge conflict resolution looks like.
2. A coordinate is bumped in a `build.gradle.kts` and the metadata is not
   regenerated. Gradle does fail this one, but with a message naming a jar rather
   than the version that moved, so the fix is not obvious.

So this gate checks the file exists, that verification of both metadata and
artifact checksums is on, that each pinned coordinate in the build files has a
component in the metadata with a real sha256, and that no checksum is a placeholder.

What it deliberately does not check is whether a checksum is the *correct* one for
the published artifact — that needs the network and Gradle's own resolution, which
is what the build in the same CI job does. This gate covers the case where the
build would not run the check at all.

Exit codes: 0 all checks pass, 1 a mismatch, 2 the file could not be read.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
METADATA = Path("gradle/verification-metadata.xml")

# The coordinates TH-44 names, and the build files that pin them. The version is
# read from the build file rather than hardcoded here: a copy of the version in
# this script would be a second source of truth, and the one thing A7.4 exists to
# prevent is a pin and its checksum drifting apart.
PINS = [
    ("org.xerial", "sqlite-jdbc", "oq-stdlib/build.gradle.kts"),
    ("org.slf4j", "slf4j-nop", "oq-stdlib/build.gradle.kts"),
]

# A sha256 is 64 hexadecimal digits. Anything else is not a checksum, and a row of
# zeros is what a placeholder or a botched mutation looks like. This is checked
# explicitly because Gradle treats a well-formed but wrong 64-character value as a
# checksum mismatch (loud) and a short value as a parse error (also loud), but a
# zeros value is well formed everywhere and only fails when that artifact is
# actually resolved — which is exactly the runtime-only one.
PLACEHOLDER = "0" * 64


def read_pin_version(build_file: Path, group: str, name: str) -> str | None:
    """Returns the version a build file pins for a coordinate, or None.

    Matches the dependency notation the build files use, for example
    `implementation("org.xerial:sqlite-jdbc:3.54.1.0")`. Deliberately narrow: a
    looser pattern would also match a comment or a variable assignment and read a
    version that is not the one Gradle resolves.
    """
    try:
        text = build_file.read_text(encoding="utf-8")
    except OSError:
        return None
    pattern = re.compile(
        r'"%s:%s:([^"]+)"' % (re.escape(group), re.escape(name))
    )
    match = pattern.search(text)
    return match.group(1) if match else None


def check(text: str) -> list[str]:
    problems: list[str] = []

    if "<verify-metadata>true</verify-metadata>" not in text:
        problems.append(
            "verification of module metadata is not enabled"
            " (expected <verify-metadata>true</verify-metadata>)"
        )

    for group, name, build_path in PINS:
        build_file = ROOT / build_path
        version = read_pin_version(build_file, group, name)
        if version is None:
            problems.append(f"{group}:{name} is not pinned in {build_path}")
            continue

        component = re.search(
            r'<component group="%s" name="%s" version="%s">(.*?)</component>'
            % (re.escape(group), re.escape(name), re.escape(version)),
            text,
            re.S,
        )
        if component is None:
            problems.append(
                f"{group}:{name}:{version} is pinned in {build_path}"
                f" but has no component in {METADATA}"
            )
            continue

        checksums = re.findall(r'<sha256 value="([^"]*)"', component.group(1))
        if not checksums:
            problems.append(
                f"{group}:{name}:{version} has a component but no sha256 checksum"
            )
            continue
        for value in checksums:
            if not re.fullmatch(r"[0-9a-f]{64}", value) or value == PLACEHOLDER:
                problems.append(
                    f"{group}:{name}:{version} has a placeholder checksum {value!r}"
                )

    return problems


def main() -> int:
    path = ROOT / METADATA
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        # A missing file is the whole failure this gate exists to catch, so it is
        # reported as a problem rather than as an unreadable-input exit: a deleted
        # control must read as "the control is gone", not as "the checker broke".
        print(f"dependency verification: cannot read {METADATA}: {error}")
        print(
            f"dependency verification: {METADATA} is the control; without it"
            " Gradle verifies nothing and every build still succeeds"
        )
        return 1

    problems = check(text)
    if problems:
        for problem in problems:
            print(f"dependency verification: {problem}", file=sys.stderr)
        return 1

    components = len(re.findall(r"<component ", text))
    print(
        f"dependency verification: {components} components,"
        f" metadata and artifact checksums on, {len(PINS)} pins covered"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
