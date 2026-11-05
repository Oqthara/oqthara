# Scripts

Everything in `scripts/` is a plain, dependency-free helper: Python 3 standard
library only, no install step. Every script is run from the repository root.

## Installation & distribution

| Path | What it does |
| --- | --- |
| `install.sh` | Installs the `oq` CLI on macOS and Linux. |
| `install.ps1` | Installs the `oq` CLI on Windows. |
| `tests/` | Checks for the installers. |

## Documentation

| Path | What it does |
| --- | --- |
| `check-docs-sync.py` | Verifies the docs against the language: generated stdlib reference, keyword budget, CLI coverage, catalog freshness, backend counts, version consistency. |
| `check-links.py` | Verifies that relative links in `docs/**/*.md`, `README.md`, `CHANGELOG.md` and `examples/**` resolve. |
| `gen-docs-catalog.py` | Regenerates `docs/catalog.json`; `--check` fails when it is stale. |
| `build-versioned-docs.py` | Builds the versioned documentation bundle used by the website. |

## CI & release verification

| Path | What it does |
| --- | --- |
| `check-circleci.py` | Verifies the CircleCI configuration matches the Gradle tasks CI runs. |

## Benchmarks

| Path | What it does |
| --- | --- |
| `bench-backends.py` | Times the same programs on the interpreter and the compiled backend. |

## Development utilities

| Path | What it does |
| --- | --- |
| `gen_conformance.py` | Scaffolds conformance programs. |

## Retired

`ai-watchdog.py` and `test_watchdog.py` were retired with the move to
chat-native orchestration (ADR-0009). There is no detached loop, watchdog, or
launchd unit anymore: the orchestrator session dispatches work and the
checkpoint files under `.oqthara-ai/` carry state. Do not install or start
them.

They remain in the tree only because `docs/adr/0009-chat-native-orchestration.md`
still names them; that record is immutable and owned by `oq-architect`. Once
that ADR is superseded, delete both files and drop this section.
