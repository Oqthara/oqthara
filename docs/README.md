# Documentation

The documentation is plain Markdown in this directory. GitHub renders it
directly, and the website publishes the same files, so there is no build step
between the source and the reader.

## What lives where

| Directory | Contents |
| --- | --- |
| `docs/spec/` | The normative specification: the language itself and the backend contract. If the implementation and this directory disagree, the specification is the bug. |
| `docs/adr/` | Architecture decision records. Numbered, immutable once accepted; a later decision supersedes an earlier one instead of editing it. |
| `docs/reference/` | The user-facing reference, including one section per `oq` subcommand in `cli.md`. |
| `docs/stdlib/` | The generated standard library reference. Never hand-edited: it is produced from the implementation by `oq doc`. |

The tutorial-level material lives at the repository root and in `examples/`.

## How documentation is published

The website fetches the Markdown from the repository and renders it as-is
(GitHub raw rendering). Because publication is a plain fetch, a document is live
as soon as it is committed to `main`; there is no separate docs deploy to keep
in sync.

## Adding a document

1. Create the `.md` file in the right directory, starting with a single `# `
   heading (the heading becomes the catalog title).
2. Regenerate the catalog:
   `python3 scripts/gen-docs-catalog.py`
3. Commit the new file and `docs/catalog.json` together. CI runs
   `python3 scripts/gen-docs-catalog.py --check` and fails if the catalog is
   stale.

Every example in a document must be a real program whose output came from the
current build. Do not hand-write `oq` output.

## Which script validates what

| Script | Checks |
| --- | --- |
| `scripts/check-docs-sync.py` | The stdlib reference matches a fresh `oq doc`; the keyword budget matches the lexer; every CLI command in the help text has a section in `docs/reference/cli.md`; `docs/catalog.json` is current; the compiled-backend counts in `docs/spec/backend.md` match the conformance suite and `BytecodeCompilerTest`; the version in `Main.java`, `build.gradle.kts` and `CHANGELOG.md` agrees. |
| `scripts/check-links.py` | Every relative link in `docs/**/*.md`, `README.md`, `CHANGELOG.md` and `examples/**` resolves on disk. |
| `scripts/gen-docs-catalog.py` | Generates `docs/catalog.json`; `--check` fails when the committed file differs. |

Run both checkers before opening a documentation pull request:

```bash
python3 scripts/check-docs-sync.py
python3 scripts/check-links.py
```
