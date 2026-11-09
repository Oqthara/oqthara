# Architecture decisions

Every structural decision in this repository has a stable identifier, a state, and a
rationale. The state machine is fixed:

`PROPOSED` → `UNDER_REVIEW` → `APPROVED` | `REJECTED` | `DEFERRED`, and an approved
decision may advance to `IMPLEMENTED` and then `VERIFIED`.

Records are immutable once `APPROVED`. A later decision supersedes an earlier one rather
than editing it, exactly as in [`docs/adr/`](../adr/).

## Hive decision records

| ID | Title | State |
| --- | --- | --- |
| OQ-DEC-2026-001 | Agent architecture uses the native custom agent format | APPROVED |
| OQ-DEC-2026-002 | Per-agent model pinning is not configured | DEFERRED (superseded by 005) |
| OQ-DEC-2026-003 | Governance records live in `docs/governance/` | APPROVED |
| OQ-DEC-2026-004 | Baseline audit is read-only and evidence-bound | APPROVED |
| OQ-DEC-2026-005 | Hive agents pin the DeepSeek custom endpoint model | IMPLEMENTED |
| OQ-DEC-2026-006 | Tuples are immutable, comma-delimited and distinct from lists | APPROVED |
| OQ-DEC-2026-007 | Operator precedence is normative and documented from the parser | APPROVED |
| OQ-DEC-2026-008 | Conformance baselines are measured, never inherited from release notes | APPROVED |
| OQ-DEC-2026-009 | The keyword budget has one source of truth in `Lexer.KEYWORDS` | APPROVED |

## OQ-DEC-2026-001 — Agent architecture uses the native custom agent format

**State:** APPROVED

**Context.** The repository already describes an orchestrator and eleven specialists in
`.kilo/agents/*.md`. Those files use a different product's frontmatter (`mode:`, `steps:`,
`permission:`, a `robin/` model prefix) and are not read by the agent runtime used here.
Leaving the described architecture unloadable would mean the repository documents an
orchestration that cannot run.

**Decision.** The Hive is defined as six custom agents in `.github/agents/*.agent.md`,
which is the format this environment loads from the repository root. The six roles are:

| Agent | Mirrors `.kilo/agents/` roles |
| --- | --- |
| `oq-hive-orchestrator` | `oq-orchestrator` |
| `oq-hive-language-syntax` | `oq-compiler` (syntax surface) |
| `oq-hive-development` | `oq-runtime`, `oq-tooling`, `oq-compiler` (implementation) |
| `oq-hive-testing-security` | `oq-qa`, `oq-dogfood`, `oq-ci-release` |
| `oq-hive-review-escalation` | `oq-reviewer`, `oq-debugger` |
| `oq-hive-architecture-governance` | `oq-architect`, `oq-scribe`, `oq-docs` |

**Alternatives rejected.** (a) Keep only `.kilo/agents/`: rejected because those files do
not load, so the architecture would exist on paper only. (b) Delete `.kilo/agents/`:
rejected as an unrelated destructive change. (c) Eleven agents instead of six: rejected
because several `.kilo` roles overlap and the six-role split maps cleanly onto the audit
areas that actually need separation.

**Consequences.** The two directories now coexist and describe the same intent in two
formats. `.kilo/agents/` remains authoritative for the Kilo product; `.github/agents/` is
authoritative here. A future decision should merge or retire one of them.

**Evidence.** `.kilo/agents/oq-orchestrator.md:1-25` (the unloadable frontmatter);
`.github/agents/oq-hive-orchestrator.agent.md` (the loadable replacement).

## OQ-DEC-2026-002 — Per-agent model pinning is not configured

**State:** DEFERRED

**Context.** The intended design pins a DeepSeek model per agent. The `model` field in
custom-agent frontmatter is only honoured when the runtime can resolve the named model.
The CLI's own model enumeration does not include any DeepSeek identifier, and
`copilot --model 0c48b4ee-e62f-4e6a-ab1f-c8d2c83b6177/deepseek-ai/deepseek-v4.1-flash-1017`
fails with `Model "..." from --model flag is not available.` The `.kilo` files use a
`robin/deepseek-ai/deepseek-v4.1-flash-1017` identifier that belongs to the other product.

**Decision.** No `model` field is set on any Hive agent. Every agent inherits the session
model. This is recorded as a limitation, not presented as a configured capability.

**Why deferred rather than rejected.** The runtime does serve a DeepSeek model to this
session, so per-agent pinning may become available without any change to this repository.
It is not available today.

**Consequences.** Agents cannot be tuned individually by model. Anything that depends on a
specific model's behaviour is unreliable and must not be designed around.

**Evidence.** `copilot --model <deepseek-id>` → `Model "..." from --model flag is not
available.`; `copilot help config` model enumeration contains no DeepSeek entry.

## OQ-DEC-2026-003 — Governance records live in `docs/governance/`

**State:** APPROVED

**Context.** `AGENTS.md` places working state in `.oqthara-ai/`, which is gitignored.
Baseline audits and decision records are not ephemeral working state: they are the
institutional memory a reviewer needs six months from now. Putting them in a gitignored
directory would discard exactly the artifact the task exists to produce.

**Decision.** Decision records and audit reports live in `docs/governance/`, tracked in
git, and are registered in `docs/catalog.json` like every other document.

**Alternatives rejected.** `.oqthara-ai/` only: rejected because the content would never
be published or reviewed. Repository root: rejected because `docs/` is already the single
place documentation lives, and `docs/README.md` says so.

**Consequences.** Adding a governance document requires regenerating the catalog in the
same change; CI enforces that through `scripts/gen-docs-catalog.py --check`. The single
`# ` heading is a convention the catalog generator relies on for titles, not an assertion
it makes — a missing heading silently falls back to the file name.

**Evidence.** `docs/README.md:25-33` (the adding-a-document procedure); `.gitignore:9`
(`.oqthara-ai/` is ignored).

## OQ-DEC-2026-004 — Baseline audit is read-only and evidence-bound

**State:** APPROVED

**Context.** A baseline audit that changes the thing it measures produces a baseline of
its own edits. An audit that reports claims without command output produces a document
nobody can check.

**Decision.** The baseline audit changes no implementation file. Every finding cites a
`path:line` or a pasted command result. Anything a specialist could not inspect is marked
`NOT INSPECTED` rather than assumed.

**Consequences.** The audit can report gaps honestly, including gaps in its own coverage.
Coverage is quoted only where the build measures it: JaCoCo is configured and `coverageGate`
enforces an 80% line floor on `dev/oqthara/core`. No other module has a floor, so this audit
never generalises the one number it can prove.

**Evidence.** `docs/governance/audit/baseline-2026-11.md`.

## OQ-DEC-2026-005 — Hive agents pin the DeepSeek custom endpoint model

**State:** IMPLEMENTED

**Context.** OQ-DEC-2026-002 deferred per-agent model pinning because
`copilot --model <deepseek-id>` reported `Model "..." from --model flag is not available.`
That observation is still true, and it is not the whole mechanism. The DeepSeek model is
served by a *custom endpoint provider*, not by GitHub Copilot's own catalog, so the
`--model` flag is the wrong surface to test. Two facts resolve the question:

1. `~/.copilot/data.db` registers provider `0c48b4ee-e62f-4e6a-ab1f-c8d2c83b6177`
   (`type: custom`, `baseUrl: http://localhost:8990/deploy`) and model
   `deepseek-ai/deepseek-v4.1-flash-1017` (`display_name: DeepSeek 4.1 Flash (TheHive)`,
   `supported_reasoning_efforts: ["medium","high","max"]`).
2. Custom-agent frontmatter supports a `model:` string field, and the runtime resolves it
   against the available catalog. An unresolvable value produces an explicit warning:
   `Warning: Custom agent "probe" specifies model "definitely-not-a-real-model" which is
   not available; using "auto" instead`, then falls back to `auto`.

**Decision.** All six `.github/agents/*.agent.md` files carry
`model: deepseek-ai/deepseek-v4.1-flash-1017`. That is the bare model identifier as stored
in `provider_models.model_id`; the provider UUID is deliberately *not* prefixed, because
the frontmatter field accepts a model name and resolution is provider-aware. This
supersedes OQ-DEC-2026-002, which remains on record as the earlier, narrower finding.

**Alternatives rejected.** (a) `--model` flag only: rejected because it does not reach the
custom provider and fails, and because it is per-invocation rather than per-agent.
(b) `robin/deepseek-ai/...` or `customendpoint/Robin/deepseek-ai/...`: rejected because
both are rejected by this runtime (`Model "..." from --model flag is not available.`).
(c) `0c48b4ee-.../deepseek-ai/...`: rejected for the same reason. (d) `subagents.agents`
entries in `~/.copilot/config.json`: rejected because they live in a user's home directory
and are invisible to the repository, which is where the architecture has to be reviewable.

**Consequences.** The model is now a repository-visible property of each agent. Because the
provider is a local custom endpoint, an agent pin is only satisfiable on a machine where
that endpoint is reachable; on any other machine the runtime warns and degrades to `auto`,
which is explicit rather than silent. Model behaviour is now stable enough to design
against. Changing it means editing six frontmatter lines and re-recording evidence, not
editing a user's home directory.

**Evidence.** Command: agent inventory read from the runtime's own event stream.

```
$ COPILOT_PROVIDER_BASE_URL=http://localhost:8990/deploy \
  COPILOT_MODEL=deepseek-ai/deepseek-v4.1-flash-1017 \
  copilot -p "Reply with exactly: AGENTS_LOADED" --trust-folder --output-format json
```

`session.custom_agents_updated` reports, with `warnings: []` and `errors: []`:

| Agent | `model` | `tools` |
| --- | --- | --- |
| `oq-hive-architecture-governance` | `deepseek-ai/deepseek-v4.1-flash-1017` | read, edit, search, execute, todo |
| `oq-hive-development` | `deepseek-ai/deepseek-v4.1-flash-1017` | read, edit, search, execute, todo |
| `oq-hive-language-syntax` | `deepseek-ai/deepseek-v4.1-flash-1017` | read, edit, search, execute, todo |
| `oq-hive-orchestrator` | `deepseek-ai/deepseek-v4.1-flash-1017` | execute, read, edit, search, agent, web, todo |
| `oq-hive-review-escalation` | `deepseek-ai/deepseek-v4.1-flash-1017` | read, search, execute, todo |
| `oq-hive-testing-security` | `deepseek-ai/deepseek-v4.1-flash-1017` | read, edit, search, execute, todo |

Delegation was verified end to end, for the specialist rather than only the orchestrator.
The orchestrator delegated via the `task` tool with
`agent_type: oq-hive-language-syntax`, and the runtime reported the child's effective model:

```json
{"agentName": "oq-hive-language-syntax",
 "model": "deepseek-ai/deepseek-v4.1-flash-1017",
 "firstDispatchedModel": "deepseek-ai/deepseek-v4.1-flash-1017",
 "modelSelectionSource": "agent_definition_default"}
```

`modelSelectionSource: agent_definition_default` is the load-bearing field: the child's
model came from the agent definition, not from the parent session. Every
`model.call_final_result` in that run reported `"isByok": true`, confirming inference went
to the custom endpoint rather than to a Copilot-hosted model.

Permission separation is verified from the same inventory: `oq-hive-review-escalation` has
`read, search, execute, todo` and therefore no `edit` and no `agent`; only
`oq-hive-orchestrator` carries the `agent` tool.

## OQ-DEC-2026-006 — Tuples are immutable, comma-delimited and distinct from lists

**State.** APPROVED

**Context.** Alpha 3 must add tuples to the language. The directive requires that a tuple
be distinguishable from a list in the contract, and that the design be reasoned from
Oqthara's own rules rather than copied from a host language. The risk of copying is
concrete: several languages reach for destructuring, pattern matching or static typing to
make tuples worth having, and each of those imports a large feature set that Oqthara does
not have and does not want.

**Decision.**

1. **`(a, b)` is a tuple; `(a)` is grouping.** One comma makes a tuple. Drop the
   comma and the parentheses keep their current meaning. This is the rule that makes
   tuples additive: no existing program changes meaning, and `(1)` keeps evaluating to
   the number `1`.
2. **`(a,)` is the one-item tuple.** The trailing comma is required, because `(a)` is
   grouping. A trailing comma is accepted on any tuple, so appending an item never
   requires editing the previous line.
3. **`()` is an error.** The language has `nothing` for absence. An empty tuple would be
   a second spelling of an idea that already exists, so it is refused by name.
4. **Tuples are immutable.** Lists are the mutable ordered collection and already serve
   that purpose. A tuple earns its place by being the immutable one: fixed length,
   read-only, safe to share. Mutation (`push`, `pop`, `insert`, `remove`, `sort`,
   `t[0] = x`) is refused with an error that names tuples.
5. **A tuple is never equal to a list.** `(1, 2) == [1, 2]` is `false`. Equality is
   structural and order sensitive within the same type, matching every other value in the
   language. Without this rule a tuple would be an alias, not a type.
6. **Reads are allowed.** Indexing, negative indexing, `length`, `first`, `last`,
   `contains`, `in`, iteration with `for`, `==`, `!=`, and lexicographic `<`, `<=`, `>`,
   `>=` all work, mirroring lists. Comparison is item by item, left to right.
7. **`type_of` answers `tuple` and `repr` prints `(1, 2)`.** A one-item tuple prints with
   its trailing comma, `(1,)`, so its text form round-trips.
8. **The runtime type is an immutable `List`.** `OqTuple` implements `java.util.List`
   over unmodifiable storage. This reuses the existing list read paths and Java interop
   for free, while mutators throw `UnsupportedOperationException`. The distinction lives
   in `typeName`, `repr`, `equal` and comparison, which is exactly the contract above.

**Alternatives rejected.**

- *Copy a host language's tuple design.* Rejected: the directive forbids it, and every
  such design pulls in destructuring, pattern matching or static types that are explicit
  non-goals.
- *Make `(a)` a tuple.* Rejected: it silently changes the meaning of every parenthesised
  expression in every existing program.
- *Allow `()` as an empty tuple.* Rejected: duplicates `nothing`.
- *Make tuples mutable.* Rejected: duplicates lists and removes the only reason to have
  both.
- *Make a tuple compare equal to a list with the same items.* Rejected: violates equality
  being type-aware and makes `type_of` a lie.
- *Add destructuring in the same change.* Rejected as scope: it is a second syntax
  feature with its own decisions, and the roadmap records it as deferred.

**Consequences.** Adding tuples is additive; no existing program changes meaning. The
only observable change to existing surface is a new string from `type_of`. Tuple
destructuring, named or typed tuple fields, and tuple arithmetic are deferred and named
in the roadmap, so the deferred set is explicit rather than accidental.

**Evidence.**

```
$ printf 'print (1, 2)\n' | ... before this decision
error: expected to close the expression
  help: Found ',' instead.
```

The same program after implementation is covered in the conformance suite; the exact
cases are listed in the Alpha 3 final report.

## OQ-DEC-2026-007 — Operator precedence is normative and documented from the parser

**State.** APPROVED

**Context.** The specification listed the boolean operators and nothing else:

> Operators: `==`, `!=`, `<`, `<=`, `>`, `>=`, `and`, `or`, `not`.

Precedence existed only in `Parser.java`. A reader could not determine whether
`-2 ** 2` is `4` or `-4`, whether `a or b and c` groups as `a or (b and c)`, or how
comparison chains behave, without reading the implementation. A language whose reference
disagrees with its parser is not specified.

**Decision.** The ten precedence levels in `Parser.java` are normative, and the reference
transcribes them from the code rather than from a host language. The method chain
`expression` → `or` → `and` → `equality` → `comparison` → `additive` → `multiplicative`
→ `power` → `unary` → `postfix` → `primary` is the specification of record.

Two behaviors are called out explicitly because they surprise readers who arrive from
another language:

- **Unary minus binds tighter than `**`.** `-2 ** 2` is `(-2) ** 2` and prints `4`.
- **Comparisons do not chain.** They share one level and evaluate left to right, so
  `1 < 2 < 3` compares `1 < 2` with `3` and fails.

**Alternatives rejected.**

- *Leave precedence undocumented.* Rejected: the finding is HIGH precisely because a
  reader cannot predict behavior.
- *Document precedence from Python or another host language.* Rejected: it would be
  wrong. Python prints `-4` for `-2 ** 2`; Oqthara prints `4`.
- *Change the parser to match a host language.* Rejected: that is a breaking change with
  no user request behind it, and the directive forbids copying host-language semantics.

**Consequences.** The reference is now checkable against the parser. Precedence is
covered by conformance cases per level so the document and the code cannot drift silently.

**Evidence.**

```
$ java -jar oq-cli/build/libs/oq.jar p1.oq
4
512
5
5
true
false
7
1
true
true
```

Each line pins one level: `-2 ** 2` = `4`, `2 ** 3 ** 2` = `512` (right associative),
`10 - 2 - 3` = `5` (left associative), `100 / 10 / 2` = `5` (left associative),
`true or false and false` = `true` (`and` first), `not true and false` = `false`,
`1 + 2 * 3` = `7`, `7 % 3` = `1`, `2 + 3 == 5` = `true`, `1 < 2 == true` = `true`.

## OQ-DEC-2026-008 — Conformance baselines are measured, never inherited from release notes

**State:** APPROVED

**Context.** `scripts/verify-conformance.py` carried two constants, `FLOOR_COMPILED = 182`
and `EXPECTED_EXECUTABLE = 197`, and the CI job that runs it failed on `main`. Both numbers
trace back to the annotated tag message of `v0.1.0-alpha.2`, which states "182 of 197
executable conformance programs compile to bytecode with zero measured divergences from the
interpreter." They were accurate when alpha.2 was cut. They were then copied into the
verifier as invariants and never re-measured while the corpus grew to 223 programs, 207 of
them executable, with 192 compiled and 19 falling back to the interpreter.

The two constants also enforced different things, which was not obvious from reading them:

- `EXPECTED_EXECUTABLE` was an **exact-match** assertion, so it failed the moment the
  corpus grew past 197.
- `FLOOR_COMPILED` was a **lower bound**, so the drifted value 182 silently passed against
  a real 192. The check had been ten programs weaker than measured and no one noticed,
  because the other constant failed first and masked it.

**Decision.** The verifier pins one measured baseline in a single `BASELINE` dictionary,
and a comment records where the numbers came from and that they must be re-measured after a
corpus change, not copied from a release note. `evaluate()` keeps exact-match semantics for
the executable count, because the executable set is a property of the corpus and should not
move without a deliberate update. The compiled count is a floor, so an improvement in the
compiler does not fail the gate; the executable pin still catches accidental corpus drift.

`measure_corpus()` and `evaluate()` are extracted as pure functions and covered by
`scripts/tests/test_verify_conformance.py` (8 tests), which runs in CI. The verifier is
required CI from this change onward.

**Alternatives rejected.**

- *Update the constants by hand to 192/207.* Rejected on its own: it fixes today's values
  and leaves the same trap for the next corpus change. The numbers are now one dictionary
  with a provenance comment and a test that fails if the corpus and the pin disagree.
- *Make both checks exact-match.* Rejected: it would fail whenever the compiler improves,
  turning a success into a red build.
- *Make both checks floors.* Rejected: then nothing detects a corpus that silently shrank.
- *Delete the verifier.* Rejected: it is the only check that compares two execution engines
  against each other, and it found real divergences during development.

**Consequences.** The gate now fails for exactly one reason — the corpus moved and no one
updated the recorded baseline — which is actionable. Adding a conformance program requires
updating `BASELINE` in the same commit, which is a deliberate act rather than a silent drift.

**Evidence.**

```
$ python3 scripts/verify-conformance.py
ok: 192 compiled, 19 fallback, 0 divergences across 223 programs (207 executable)

$ python3 -m unittest discover -s scripts/tests -v
Ran 8 tests in 0.31s
OK
```

## OQ-DEC-2026-009 — The keyword budget has one source of truth in `Lexer.KEYWORDS`

**State:** APPROVED

**Context.** `CONTRIBUTING.md` stated the language has 18 reserved keywords. It has 20.
`scripts/check-docs-sync.py` was supposed to catch that, and did not, because it validated
the documented count against a **hardcoded list of twenty names held inside the checker
itself**. The checker's copy of the truth was correct; the document it was checking was
wrong; and nothing compared either one to the lexer. The drift detector had its own
private copy of the thing it was detecting drift in.

**Decision.** `oq-core/src/main/java/dev/oqthara/core/Lexer.java` remains the only
definition. `check-docs-sync.py` parses `Lexer.KEYWORDS` out of the source, and cross-checks
both the count stated in `CONTRIBUTING.md` and the budget stated in that document against
the parsed set. A change to the lexer without a matching documentation change now fails, and
so does a documentation change the lexer does not support.

**Alternatives rejected.**

- *Correct 18 to 20 and stop.* Rejected: the checker would still be validating a copy.
- *Have the checker invoke the JVM to read the keyword set.* Rejected as disproportionate:
  the check runs in a Python step that should not need a built jar, and the constant is a
  simple literal list.
- *Generate `CONTRIBUTING.md` from the lexer.* Rejected: prose does not generate cleanly,
  and the surrounding sentences carry meaning a generator would lose.

**Consequences.** Two independent drift directions are now caught, and both were verified by
mutation: restoring `18` in `CONTRIBUTING.md` exits 1, and adding a bogus `"when"` to the
lexer exits 1.

**Evidence.**

```
$ python3 scripts/check-docs-sync.py
ok: all 7 checks passed

$ sed -i '' 's/we are at 20/we are at 18/' CONTRIBUTING.md && python3 scripts/check-docs-sync.py; echo "exit $?"
...
exit 1
```

**Related.** `docs/governance/remediation/alpha-3-release-provenance.md` records the
release-note origin of the stale conformance constants, which is the same class of defect:
a number written once, quoted into a second place, and never re-derived.
