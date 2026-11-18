# Alpha 7 — Required first report

Directive §11 requires ten numbered items before any expansion of implementation. This is that
report. Every claim below was re-derived on the commit that carries this file; nothing is carried
forward from an earlier document, and where an earlier document is wrong the correction is stated
rather than inherited.

The report is deliberately not a plan. §11 asks what is true now, and four of the ten items are
findings that change what §3 through §5 can mean.

## Item 1 — Verified baseline and git state

| Check | Command | Result |
| --- | --- | --- |
| Working tree | `git status --short` | empty |
| `origin/main` | `git rev-parse --short origin/main` | `01dcbe3` |
| Branch content | `git diff --stat origin/main HEAD` | empty for the tree this report was written on |
| `main` CI | `gh api .../actions/runs/44211447899` | `completed/success` on `01dcbe3` |
| Open pull requests | `gh pr list --state open` | exactly one, #251 |

Baseline confirmed. The one open pull request is the Gradle-wrapper bump §2.4 names as separate
work, and it is **red**: the `ubuntu-latest / JDK 21` job fails the AI review gate with
"`docs/governance/reviews/PR-251.md` is missing: PR #251 has no AI review record", and
"Build and run the image" fails as well. It is a dependabot pull request that has never been
reviewed. That is a live blocker for the required-status-check set, not a cosmetic detail, and it
is carried into item 10.

## Item 2 — Full risk-register boundary review

Re-derived from the status column, not copied:

| Status | Count |
| --- | --- |
| Open | 7 |
| Mitigated | 10 |
| Accepted | 13 |
| **Total** | **30** |

Exposure buckets, which `scripts/check-risk-register.py` enforces arithmetically: Critical 1,
High 4, Medium 12, Low 13. The arithmetic closes.

The seven Open entries are **R-012, R-014, R-018, R-021, R-022, R-023, R-027**. Six of the seven
are documentation-or-tooling defects, not language defects. That distribution is itself the
finding: the risk register is mostly honest about what is unfinished, and what is unfinished is
mostly *claims*, not code.

## Item 3 — Remaining verified versus obsolete risks

Each Open entry was checked against the tree by executing or reading the artefact it names. None
status was accepted on the strength of its own text.

| Risk | Independently re-checked | Verdict |
| --- | --- | --- |
| R-012 | `docs/stdlib/reference.md` generated; the register's own correction table already revised 89 → 205 | Still open as a *breadth* question; the count itself is now stated |
| R-014 | `docs/adr/0032-static-fields-and-varargs.md` D5; direction out of Java still closed | Still open, correctly described |
| R-018 | 8 targets in `allFuzzTargets`; 10 named gap builtins have zero occurrences in the fuzz source | **Reproduced exactly** — see item 4 |
| R-021 | `grep -c "rollback\|--update" scripts/install.sh` → 0; no `oq update` | Still open. **But its stated reason is now false** |
| R-022 | `ls -d site` → absent; `OUT = ROOT / "site" / "_site" / "versions"` still in the script | **Reproduced exactly** |
| R-023 | `Lexer.KEYWORDS` has 22; three documents say 20 | **Reproduced exactly** — see below |
| R-027 | Citation-drift risk; the register's own correction table demonstrates the class | Still open; no automated fix exists |

Two substantive corrections follow from this pass.

**R-021 states a reason that is no longer true.** It says "The workstream is unowned, per
`alpha-4-workstream-assignment.md`." That file's row 9 reads "Covered (ownership resolved
2026-11-11)" and carries a section headed "Workstream 9 — Runtime Manager & Distribution
(ownership resolved 2026-11-11)". The risk is real and still open — there is still no `oq update`
and no rollback — but its justification cites a condition the assignment file says was resolved.
The repair is to re-base R-021 on the capability gap alone.

**R-023 reproduces, and it is worse than stated in one specific way.** Actual keyword count is 22.
`CONTRIBUTING.md` says "budget is 30; we are at 22", `docs/stdlib/reference.md` says "22 keywords
(budget: 30)", and `docs/spec/design.md`'s simplicity table says 22 — all correct. `AGENTS.md` says
"budget 30, now 20". And both `.github/agents/oq-hive-language-syntax.agent.md` and
`oq-hive-orchestrator.agent.md` say "budget is 30 … uses 20". That is six statements, not the five
R-023 claimed; its heading said "two ways across five documents" and it had missed `design.md`
entirely, the one stale-looking copy the gate *was* reading. `scripts/check-docs-sync.py` verifies
`reference.md`, `CONTRIBUTING.md` and `design.md` — it did **not** read the agent files. So the two
statements a language agent reads first were the two that CI could not catch. Since §4 hands
language-core evolution to exactly that agent, this stopped being a documentation nit and became a
prerequisite: an agent told "20 of a 30 budget" reasons about headroom that does not exist.

Repaired in this change: all six statements now say 22, and the gate reads the three agent-facing
copies, treating a missing or reworded statement as a problem rather than a silent skip. Removing
the new check fails five of six regression tests in `scripts/tests/test_check_docs_sync_keywords.py`.

## Item 4 — R-018 reproduction and proposed A7.8 acceptance criteria

Reproduced, not restated. `build.gradle.kts` `allFuzzTargets` declares exactly 8 `FuzzTarget`
entries, matching the register's count. All ten builtins R-018 names as unreached have **zero**
occurrences in `oq-stdlib/src/fuzz/java/`: `csv_parse`, `csv_write`, `wrap_text`, `fill_text`,
`fnmatch`, `glob`, `ini_parse`, `properties_parse`, `zip`, `compress`. `docs/spec/fuzzing.md`'s own
"What is not covered yet" section names the same gaps. R-018 is accurate as written, and its own
justification stands: eight targets found seven real defects, which argues for writing more rather
than for trusting the current set.

Purity triage, because a target that mutates the filesystem is not a valid target:

| Candidate | Arity | Callable without a host |
| --- | --- | --- |
| `csv_parse` | 1/2/3 | yes |
| `csv_write` | 1/2 | yes |
| `wrap_text`, `fill_text` | 2 | yes |
| `fnmatch` | 2 | yes |
| `ini_parse`, `ini_write` | 1 | yes |
| `properties_parse` | 1 | yes |
| `Patterns.Glob(String, Span)` | — | yes, directly constructible |
| `glob` | 2 | **no** — calls `resolve(host, …)` and `search(...)` |
| path resolution | — | **no** — `FileSystem.resolve` is private and needs an `OqHost` |

So eight of the ten named gaps are pure and directly fuzzable. The path-resolution gap, which
`docs/spec/fuzzing.md` names explicitly, needs a design decision before it can be a target, because
the code path is private and host-dependent. Two honest options: a minimal stub `OqHost` in the
fuzz source set, or extracting the pure join/normalize/`InvalidPathException` logic into a
package-private helper that both `FileSystem` and the target call. The second is preferable — it
tests the real code rather than a stub's imitation of it — but it changes production code to enable
a test, so it needs a decision recorded either way.

Proposed A7.8 acceptance criteria, each of which would fail if the work were skipped:

1. Every new target exercises a real code path — proven by mutating the target's builtin to a stub
   that returns immediately and observing the target still passes. A target that cannot fail is not
   a target.
2. Invalid input yields `OqError` or a correct value; an internal exception type is a finding, not a
   tolerance. Matches `docs/spec/fuzzing.md`'s existing rule.
3. Structural postconditions, not merely absence of exceptions — e.g. `csv_parse` output length
   equals the record count, `csv_write`→`csv_parse` round-trips for the generated subset.
4. Every defect found becomes a permanent regression case, in the conformance corpus or a unit test,
   not only an entry in the findings table.
5. Each target runs as its own `@FuzzTest` in its own Gradle task registered in `allFuzzTargets` —
   Jazzer's JUnit integration silently runs only the first per JVM, so this is a correctness
   requirement, not tidiness.
6. A positive control: a deliberately defective variant of the target must fail, demonstrating the
   target observes the property it claims to.
7. `docs/spec/fuzzing.md` and R-018 are updated together, including the "What is not covered yet"
   section, which must shrink by exactly what landed and no more.

## Item 5 — Proposed Alpha 7 workstream assignments

The existing mapping is `docs/governance/alpha-4-workstream-assignment.md`: twelve workstreams,
eleven `Covered`, one `Under-covered`, none unowned. The `Under-covered` row is **workstream 2, Type
System & Values**, whose note reads "no role names types".

That is the row §5 collides with. §5 orders optional gradual typing with generics; the only
workstream that owns the type system is the one the repository already recorded as under-covered.
Alpha 7 therefore cannot assign §5 to an existing owner without first naming one.

| §3–§6 unit | Proposed owner | Rationale from the tree |
| --- | --- | --- |
| R-018 fuzzing reach (A7.8) | `oq-hive-testing-security` | Already owns `docs/spec/fuzzing.md` and both `src/fuzz` trees (workstream 10) |
| Language-core evolution (§4) | `oq-hive-language-syntax` | Sole owner per its own scope; workstream 1 |
| Optional typing and generics (§5) | **Unassigned — needs a decision** | Workstream 2 is `Under-covered`; assigning it is itself an ADR |
| Java Bridge Full (§6) | `oq-hive-development` | Workstream 6, `Covered`; owns `JavaBridge.java` |
| Runtime and observability (§5) | `oq-hive-development` | Workstreams 4 and 5 |
| Security continuation | `oq-hive-testing-security` | Workstreams 10 and 11 |

## Item 6 — Language-core ownership and agent-scope adjustments

Three adjustments are needed before §4 can be executed.

**The agent set the repository designates has no language-syntax role.** `AGENTS.md` line 63 says
agents "live in `.kilo/agents/` (`oq-orchestrator` primary)". That directory holds twelve agents:
architect, ci-release, compiler, debugger, docs, dogfood, orchestrator, qa, reviewer, runtime,
scribe, tooling. There is no language-syntax agent. The six `oq-hive-*` agents, including
`oq-hive-language-syntax`, live in `.github/agents/` and are referenced by the governance documents
but not by the operating instructions. A directive that hands language-core to
`oq-hive-language-syntax` is therefore pointing at a role that the primary orchestration path does
not include.

**`oq-hive-language-syntax` carries a stale keyword count** — item 3. It says 20 where the lexer has
22, and CI does not read it.

**Scope must not silently absorb the type system.** `oq-hive-language-syntax` owns the lexer,
parser, AST, keyword set and the normative specification. Optional typing touches the grammar (so it
is in scope) *and* the value model and a new semantic-analysis pass that does not exist (which are
`oq-hive-development`'s and workstream 2's). If §5 proceeds, the split has to be written down, or a
single role will be asked to do two roles' work under one name.

## Item 7 — Type-system decisions requiring superseding ADRs

This is the report's most consequential finding, and it is why §5 cannot begin as written.

| Artefact | Says | Date |
| --- | --- | --- |
| `docs/governance/remediation/type-system-decision-brief.md` | "DECIDED … question 1 answered 'remains dynamically typed', which resolves questions 2 and 3 as **Option A, no typed fields**" | 2026-11-13 |
| [`docs/adr/0015-type-families.md`](../adr/0015-type-families.md) | "Oqthara is dynamically typed and has no type checker … there is no semantic-analysis pass, no type annotations anywhere in the grammar" | accepted 2026-11-09 |
| `conformance/246-typed-field-names-the-decision-error.oq` | `data P(x: Int, y)` → `typed fields are not supported` | — |
| `conformance/247-typed-parameter-names-the-decision-error.oq` | `fun f(a: Int)` → `typed parameters are not supported` | — |

The language does not merely lack typing; it *refuses* annotations with explicit diagnostics that
cite the decision. Two conformance cases pin the refusal. §5 therefore requires superseding ADRs —
one for ADR-0015 and one for the brief — not additive work behind an existing feature. Editing the
two conformance cases without that record would silently contradict a decided question, which the
repository's own governance forbids.

The lever is in the brief itself: "This does not close the door … Reversing it costs one decision,
not one migration." The decision was made because the maintainer was unavailable and Option A was
"reversible at zero cost". That reasoning is exactly what makes supersession cheap and legitimate —
and exactly why it must be an ADR rather than an edit.

**Next ADR number is 0070** (69 exist; highest are `0068-glob-matching-is-a-linear-dynamic-program.md`
and `0069-oqthara-stays-dynamically-typed.md`).

## Item 8 — Java Bridge Full validation matrix

The authoritative inventory is `docs/governance/alpha-5-java-bridge-capability-inventory.md`:
twelve directions, 46 capability rows, and four confirmed defects **D1–D4**. §6 asks for "Full",
and the inventory already defines what that would have to mean. (This report previously claimed
five, **D1–D5**. The `d3_shadowed_names.oq` probe was then named `d5_unit_names.oq`, which made a
fifth defect look real; it is a precedence regression probe for D3, and no D5 section was ever
written.)

§6 orders Swing first. The tree does not support that ordering yet:

- No Swing or AWT program exists in `examples/` or `conformance/` — zero occurrences of `JFrame` or
  `javax.swing`.
- The inventory mentions Swing, the EDT, or Event Dispatch **zero** times. There is no row for it,
  so "Swing works" is currently an unverified claim in either direction.
- Nothing in the bridge's 12 directions covers event dispatch, threading affinity, or GUI lifecycle.

A validation matrix for §6 has to be built, not consulted. The minimum it must cover, with each row
required to execute rather than be read:

| Area | Ceiling that already exists |
| --- | --- |
| Swing construction and display | no inventory row; needs one |
| Event dispatch and thread affinity | no row; `print` is already known non-atomic (R-029) |
| CLI, network, filesystem, JDBC | directions 1–3; JDBC behind R-006/R-007 |
| Streams and `java.util.concurrent` | direction 7 for callbacks; D1 fixed in #231, so callbacks are accepted |
| Third-party classpath | direction 11 |
| Interpreter-versus-compiled parity | direction 10; `run.py --both` covers C1–C8 only, 19 probes |

One constraint from the tree must be honoured, not worked around: the probe harness covers C1–C8
only, so a GUI program's compiled-versus-interpreted parity would not be
gated by anything that exists today. The other constraint this section used to name — D1, a
callable Java object refused at `sort`, `sort_by`, `reduce` and `list_sort` — was fixed in PR #231
(`39b6078`) on 2026-11-15, two days before this report. All four builtins now accept a Java
functional value whose arity fits, and a wrong-arity value is refused by count rather than by
denying callability. The five `d1_*` probes pass, so §6's callback-dependent surface is unblocked
without further defect work.

## Item 9 — Dependencies and execution sequence

Ordered by what actually blocks what, not by what reads best.

1. **Repair the red required check on #251.** A permanently red required context is the failure mode
   R-024 describes; every subsequent merge inherits it.
2. **Correct R-021's stale justification and the three stale keyword counts.** Both are prerequisites
   for trusting the agent that §4 hands language-core to.
3. **A7.8 — R-018 fuzzing reach.** Seven pure targets are ready today; the path target needs the
   helper-extraction decision. Nothing else depends on it, which is why it ships first.
4. **ADR-0069 superseding ADR-0015 and the type-system brief.** Blocks all of §5. No typing work
   before it.
5. **D1 — already done, does not block.** Fixed in PR #231 (`39b6078`) on 2026-11-15, before this
   report. Verified: the five `d1_*` probes pass and `ListSortTest` covers the arity refusal, so
   §6's callback-dependent surface is unblocked. Nothing to do here; recorded because the ordering
   below previously gated §6 on it.
6. **§4 language-core evolution**, after item 2 so the owner is reading a correct count.
7. **§6 Java Bridge Full**, after the inventory row creation (item 5 is already done, so it gates
   nothing).
8. **Runtime and observability**, independent of 4–7.

Items 1–3 were independent of 4–6 and could have run in parallel worktrees; item 4 was the gate for
the whole typing branch of the Alpha and is now closed (ADR-0069, PR #255). Item 5 needed no work at
all: D1 was already fixed in PR #231 before this report was written.

## Status after the first execution pass

| Item | Sequence entry | State |
| --- | --- | --- |
| 1 | Repair the red required check on #251 | **Done** — `db01622` fixed the gate, Dependabot rebased, #251 merged as `9eb3561` after 17/17 checks |
| 2 | Correct R-021 and the stale keyword counts | **Done** — PR #252 |
| 3 | A7.8, R-018 fuzzing reach | **Done** — PR #253 |
| 4 | ADR superseding ADR-0015 | **Done** — ADR-0069, PR #255 |
| 5 | Fix D1 | **Already done** — PR #231, two days before this report |
| 6 | §4 language-core evolution | Open |
| 7 | §6 Java Bridge Full | Open — needs the Swing inventory row first |
| 8 | Runtime and observability | Open, independent |

## Item 10 — Remaining blockers and risks

- **The type-system conflict is unresolved.** Until an ADR supersedes ADR-0015 and the brief, §5
  cannot start, and the two conformance cases must keep saying what they say.
- **#251 is red on a required check** and has been since dependabot opened it, because ADR-0059
  requires an AI review record per pull request and no one wrote one.
- **The path-resolution fuzz target has no agreed design.** Private, host-dependent; either a stub
  `OqHost` or a production-code extraction. The second is better and is a change to production code
  justified by a test, which needs saying out loud.
- **`oq-hive-language-syntax` is not on the primary orchestration path**, and the copy of it that
  exists carries a stale keyword count.
- **Workstream 2 is `Under-covered`** and §5 lands entirely inside it.
- **Swing has zero coverage** — no example, no conformance case, no inventory row. §6's "Swing
  first" is a direction, not a measurement.
- **R-002's dangling "See R-008."** at line 95 is a pre-existing inaccuracy, wrong since `1e87f00`,
  deliberately deferred and still deferred.
- **No release, tag, deployment or version change is authorized** (§10), so none of the above may be
  validated by shipping.

## What this report does not claim

- It does not claim the risk register is wrong beyond the two corrections named; the other five Open
  entries were re-checked and hold.
- It does not claim the fuzz suite is trustworthy because it passes. It claims eight targets exist,
  found seven defects, and that eight more pure builtins are reachable and untested.
- It does not claim §5 is illegal. It claims §5 contradicts a *decided* question and therefore needs
  a superseding record, which the brief's own "one decision, not one migration" makes affordable.
- It does not claim `oq-hive-language-syntax` is unusable. It claims it is not where `AGENTS.md`
  says agents live, and that its keyword count is stale.
- It does not claim Swing fails. It claims nothing in the tree has ever tested it, in either
  direction.
