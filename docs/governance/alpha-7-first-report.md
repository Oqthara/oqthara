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

Re-derived from the status column, not copied. The table below is the current derivation; the
counts this item originally published were correct on `d306833` and are preserved underneath it,
because the reason they changed is the subject of this correction.

| Status | Count |
| --- | --- |
| Open | 6 |
| Mitigated | 12 |
| Accepted | 13 |
| **Total** | **31** |

Exposure buckets, which `scripts/check-risk-register.py` enforces arithmetically: Critical 1,
High 4, Medium 12, Low 14. The arithmetic closes.

The six Open entries are **R-012, R-014, R-021, R-022, R-023, R-027**. Five of the six are
documentation-or-tooling defects, not language defects. That distribution is itself the finding:
the risk register is mostly honest about what is unfinished, and what is unfinished is mostly
*claims*, not code.

**This item originally said Open 7 / Mitigated 10 / Accepted 13, total 30, Low 13.** That was a
correct derivation of the register as it stood, and it went stale for a reason worth recording: the
two entries that moved were R-018 and the total itself, and both moved because later work executed
this report's own sequencing.

| Change | Commit | Effect |
| --- | --- | --- |
| R-018 `Open`→`Mitigated`, exposure `Medium`→`Low` | `e43d384` (PR #253) | Item 9.3 (A7.8 fuzzing reach) executed; `Medium` 12→11, `Low` 13→14 |
| R-031 added, `Medium`/`Mitigated` | `db01622` (PR #254) | A new risk filed while unblocking #251; total 30→31, `Medium` 11→12 |

So the arithmetic in the original table closed when it was written; it does not close now against a
register that has since been edited. The distinction matters for how this report should be read:
Item 2 was not a guess, it was a snapshot with no mechanism to refresh it. Re-deriving it is
cheap — the command is four `awk` fields over one file — and nothing runs it.

## Item 3 — Remaining verified versus obsolete risks

Each Open entry was checked against the tree by executing or reading the artefact it names. None
status was accepted on the strength of its own text.

The verdicts below are as of `d306833`. Two of them — R-018 and R-023 — describe conditions that
later commits repaired, so they are marked with what became of them rather than left reading as
current. The rows that still say "Still open" remain open against the present tree.

| Risk | Independently re-checked | Verdict |
| --- | --- | --- |
| R-012 | `docs/stdlib/reference.md` generated; the register's own correction table already revised 89 → 205 | Still open as a *breadth* question; the count itself is now stated |
| R-014 | `docs/adr/0032-static-fields-and-varargs.md` D5; direction out of Java still closed | Still open, correctly described |
| R-018 | 8 targets in `allFuzzTargets` at the time; 10 named gap builtins had zero occurrences in the fuzz source | **Reproduced exactly then**, and superseded — now 11 targets; see item 4 |
| R-021 | `grep -c "rollback\|--update" scripts/install.sh` → 0; no `oq update` | Still open. **But its stated reason is now false** |
| R-022 | `ls -d site` → absent; `OUT = ROOT / "site" / "_site" / "versions"` still in the script | **Reproduced exactly** |
| R-023 | `Lexer.KEYWORDS` has 22; three documents said 20 | **Reproduced exactly then**, and repaired — all six now say 22; see below |
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

Reproduced, not restated. `build.gradle.kts` `allFuzzTargets` declared exactly 8 `FuzzTarget`
entries at `d306833`, matching the register's count. All ten builtins R-018 names as unreached had
**zero** occurrences in `oq-stdlib/src/fuzz/java/`: `csv_parse`, `csv_write`, `wrap_text`, `fill_text`,
`fnmatch`, `glob`, `ini_parse`, `properties_parse`, `zip`, `compress`. `docs/spec/fuzzing.md`'s own
"What is not covered yet" section names the same gaps. R-018 is accurate as written, and its own
justification stands: eight targets found seven real defects, which argues for writing more rather
than for trusting the current set.

**Superseded in part by A7.8.** PR #253 (`e43d384`) added three targets — `StdlibFormats`,
`GlobPatterns`, `GlobClasses` — taking `allFuzzTargets` from 8 to 11. Eight of the ten named builtins
now appear in `oq-stdlib/src/fuzz/java/`, and nine are fuzzed:

```
$ for b in csv_parse csv_write wrap_text fill_text fnmatch glob ini_parse \
    properties_parse zip compress; do
    printf '%s=%s ' "$b" "$(grep -rl "$b" oq-stdlib/src/fuzz/java/ | wc -l | tr -d ' ')"
  done
csv_parse=1 csv_write=1 wrap_text=1 fill_text=1 fnmatch=1 glob=1 ini_parse=1
properties_parse=1 zip=0 compress=0
```

`fnmatch` is named only in a comment; the target fuzzes `Patterns.Glob`, the pattern engine behind
it, and the comment says so. `zip` and `compress` are the two genuinely unreached builtins.

`glob` gaining a target is worth noting against this item's own triage, which classified it as **not**
directly fuzzable because it calls `resolve(host, …)`. The landed coverage fuzzes `Patterns.Glob`,
the pure layer — a third option this item did not list, neither a stub host nor an extraction.
Calling the pure layer directly is the better answer, and it was found by doing the work rather
than by the decision this item proposed. The path-resolution target, whose code path really is
private, still has no agreed design.

So the residual gap is narrower than the item states: `zip`, `compress`, and path resolution.

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

Three adjustments were needed before §4 could be executed. All three are now discharged; the
Resolution table at the end of this item records how.

**But §4 itself is not defined anywhere in this repository,** and that is a different problem from
the three above. Items 7 and 8 both state what their directive section asks for — §5 orders optional
gradual typing with generics, §6 orders the Java Bridge to Full and Swing first. This report never
states what §4 orders. Before this pass it named the section six times and paraphrased it zero
times: as an owner row (line 197), as a sequence entry (line 355), as a status cell (line 373), as
the recipient of language-core (lines 100 and 345), and as the thing three adjustments had to precede
(line 205). Every one of those is a reference. None is a statement of scope.

Searching for the text rather than the label turned up nothing to quote:

```
$ git grep -ci 'language-core\|language core\|core language' -- docs/ AGENTS.md .github/ .kilo/
docs/governance/alpha-4-workstream-assignment.md:1
docs/governance/alpha-7-first-report.md:7
docs/governance/remediation/alpha-4-execution-report.md:1
docs/governance/reviews/PR-257.md:2
```

Run against `41191b7`, before this pass added text. Four files, and all four hits resolve to
something other than a definition. The assignment failure is workstream 7's title "Core Language
Features" in a different mapping. The execution report hit is `## 5. Language core — completed`, a
closing heading for Alpha 4 work already done. The two review-record hits are PR #257 quoting this
report back to itself. The remaining seven are this report's own lines. The directive is an external
text that is not checked in, which is consistent — §10 and §11 are cited the same way — but it means
a reader holding only this repository cannot tell what work §4 authorizes.

The three adjustments below are therefore **necessary and not sufficient**. They clear the ownership
obstacles; they do not bound the work.

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

### Resolution

All three adjustments are now discharged.

| Adjustment | State |
| --- | --- |
| No language-syntax role in the operating instructions | **Fixed.** `AGENTS.md` now names both agent sets and says which one is loadable here and which one the governance documents assign owners to |
| Stale keyword count (20 vs 22) | **Fixed in PR #252.** Verified independently: `Lexer.KEYWORDS` holds 22 words (6 + 6 + 5 + 5), and the agent file reads 22 |
| Scope must not absorb the type system | **Fixed.** The agent file has a "Where your ownership stops" section: syntax owns the annotation grammar and the specification's account of it; `oq-hive-development` owns the value model, the semantic-analysis pass, and failed-check behaviour. A change needing both is two changes |

The keyword count **is** read by CI, in the same commit that corrected it. PR #252 added an
`agent_facing` block to `scripts/check-docs-sync.py` that pattern-matches the budget sentence in
`AGENTS.md`, `oq-hive-language-syntax.agent.md` and `oq-hive-orchestrator.agent.md` and compares
each count against `Lexer.KEYWORDS`. Setting the orchestrator file to `It currently uses 19` makes
the gate fail with `claims 19 keywords but the lexer defines 22`.

This paragraph previously asserted the opposite — that nothing compares the prose against the
lexer — and Item 10 below said the same thing a second time. Both were wrong when written. The
correction is recorded here rather than quietly applied, because a governance report that overstates
a remaining gap is as misleading as one that understates it, and this is the same failure the report
charges elsewhere: prose about the tree, not checked against the tree.

## Item 7 — Type-system decisions requiring superseding ADRs

This is the report's most consequential finding, and it is why §5 cannot begin as written.

| Artefact | Says | Date |
| --- | --- | --- |
| `docs/governance/remediation/type-system-decision-brief.md` | "DECIDED … question 1 answered 'remains dynamically typed', which resolves questions 2 and 3 as **Option A, no typed fields**" | 2026-11-13 |
| [`docs/adr/0015-type-families.md`](../adr/0015-type-families.md) | "Oqthara is dynamically typed and has no type checker … there is no semantic-analysis pass, no type annotations anywhere in the grammar" | accepted 2026-11-09 |
| `conformance/246-typed-field-names-the-decision-error.oq` | `data P(x: Int, y)` → `typed fields are not supported` | — |
| `conformance/247-typed-parameter-names-the-decision-error.oq` | `fun f(a: Int)` → `typed parameters are not supported` | — |

The language does not merely lack typing; it *refuses* annotations with explicit diagnostics that
cite the decision. Two conformance cases pin the refusal. §5 therefore required superseding ADRs —
one for ADR-0015 and one for the brief — not additive work behind an existing feature. Editing the
two conformance cases without that record would silently contradict a decided question, which the
repository's own governance forbids.

**Both supersessions have since happened, and they settled the question the other way.** ADR-0069
(`0069-oqthara-stays-dynamically-typed.md`, Accepted 2026-11-18) supersedes ADR-0015's scope *and*
the brief, and decides that Oqthara remains dynamically typed with untyped `data` fields. This item's
requirement was therefore satisfied in form and inverted in substance: the record it asked for now
exists and says §5's premise is wrong. Any typing work needs an ADR superseding **ADR-0069**.

ADR-0069 keeps that door cheap on purpose, and the cost estimate this item made still holds: it says
"Reversal costs one decision, not one migration … reopening this is a new ADR, not a corpus rewrite",
because no program valid under it would become invalid under runtime-asserted field types. So the
work is one record either way — what changed is *which* record has to be superseded, and that the
target is now an Accepted entry in the ADR index rather than a governance remediation note.

The two conformance cases are consequently *more* firmly grounded, not less: they refuse annotations
on a decided question rather than a pending one. The sentence above about editing them "without that
record" still holds — the record is just now ADR-0069.

The lever is in the brief itself: "This does not close the door … Reversing it costs one decision,
not one migration." The decision was originally made because the maintainer was unavailable and
Option A was "the only option reversible at zero cost". ADR-0069 re-affirmed that reasoning,
reversal cost included.

**Next ADR number is 0070** (69 exist; highest are `0068-glob-matching-is-a-linear-dynamic-program.md`
and `0069-oqthara-stays-dynamically-typed.md`).

## Item 8 — Java Bridge Full validation matrix

The authoritative inventory is `docs/governance/alpha-5-java-bridge-capability-inventory.md`:
**thirteen** directions, **52** capability rows, and **five** confirmed defects **D1–D5**. §6 asks
for "Full", and the inventory already defines what that would have to mean.

That count has been wrong in both directions. This report once claimed five defects when the
inventory defined four: the `d3_shadowed_names.oq` probe was then named `d5_unit_names.oq`, which
made a fifth defect look real, and it is in fact a precedence probe for D3. #256 corrected the
count to four. There is now a real D5, found by running the Swing program §6 orders and adds this
pass — so the number is five again, for a different reason, and this time it names a section that
exists.

§6 orders Swing first, and this pass executed it. Two of the three obstacles the report used to
list are gone; one stands:

- ~~No Swing or AWT program exists in `examples/` or `conformance/`~~ — still true of the suite,
  but the probes below ran the toolkit, and Direction 13 records the results.
- ~~The inventory has no Swing row~~ — Direction 13 now has six, C13.1–C13.6, all executed.
- **Still open:** no test opens a *visible window*. Layout, painting, input dispatch and
  look-and-feel are untested, because a headless runner cannot cover them.

What the pass established: components construct and answer, a closure runs on the event dispatch
thread, a closure is accepted as an `ActionListener` and receives the real `ActionEvent`, captures
survive the toolkit calling from a thread the program did not create, and interpreter and compiled
bytecode agree. What it also found: opening a `JFrame` without a display was reported as an
unchangeable collection, which is D5.

A validation matrix for §6 has to be built, not consulted. The minimum it must cover, with each row
required to execute rather than be read:

| Area | Ceiling that already exists |
| --- | --- |
| Swing construction and display | direction 13, C13.1 and C13.2; display still untested |
| Event dispatch and thread affinity | direction 13, C13.3–C13.5; `print` is still non-atomic (R-029) |
| CLI, network, filesystem, JDBC | directions 1–3; JDBC behind R-006/R-007 |
| Streams and `java.util.concurrent` | direction 7 for callbacks; D1 fixed in #231, so callbacks are accepted |
| Third-party classpath | direction 11 |
| Interpreter-versus-compiled parity | direction 10; `run.py --both` covered C1–C8 in 19 probes when this table was written, and covers C1–C12 in 25 as of the probe-coverage pass. C11.1 — a class on the user's own classpath — used to be the one row the harness could not reach, because `java -jar` ignores `CLASSPATH`; `c11_user_classpath` now runs as the single `java -cp` probe and closes it |

One constraint from the tree must be honoured, not worked around: the probe harness covered C1–C8
when this section was written, and no direction was probed headlessly, so a GUI program's
compiled-versus-interpreted parity would not be gated by anything that exists today. The C9–C12
probes added since exercise non-GUI directions, so the conclusion is unchanged: parity for a
program that needs a display still has no gate. The other constraint this section used to name — D1, a
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
3. **A7.8 — R-018 fuzzing reach.** Seven pure targets were ready on the day this was written, and
   PR #253 landed coverage for nine of the ten named builtins; the path target still needs the
   helper-extraction decision. Nothing else depends on it, which is why it shipped first.
4. **ADR-0069 superseding ADR-0015 and the type-system brief.** Blocks all of §5. No typing work
   before it.
5. **D1 — already done, does not block.** Fixed in PR #231 (`39b6078`) on 2026-11-15, before this
   report. Verified: the five `d1_*` probes pass and `ListSortTest` covers the arity refusal, so
   §6's callback-dependent surface is unblocked. Nothing to do here; recorded because the ordering
   below previously gated §6 on it.
6. **§4 language-core evolution**, after item 2 so the owner is reading a correct count — and only
   once §4's text is available. The three ownership adjustments are discharged; the scope is not
   written down in this repository, so this entry cannot be started as specified.
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
| 6 | §4 language-core evolution | **Blocked as written** — ownership, keyword count and the type-system scope split are all discharged (Item 6's Resolution), but §4's own text is not in this repository, so the work has no boundary. Two of three prerequisites met; the third is a definition, not an adjustment |
| 7 | §6 Java Bridge Full | Partly done — direction 13 added (C13.1–C13.6); visible-window coverage still open |
| 8 | Runtime and observability | Partly done — six of the seven file-taking subcommands answered `--help` by treating it as a filename, and `oq new --help` created a directory; all six are fixed and tested (PR #259). No execution tracing exists |

## Item 9a — Runtime and observability: what the tree actually needed

Item 8 arrives in this report as one line, "Runtime and observability, independent of 4–7", with no
acceptance criteria. Its owner row points at "§5", which is the section this report establishes is
about optional typing — almost certainly a typo for the §3–§6 grouping rather than a claim that
runtime work sits in the typing section. Before doing anything, the state was measured rather than
inferred.

**What already worked.** Elapsed time is measurable today without any new API:

```
$ oq run obs3.oq
sum=500000500000
elapsed_ms=189
```

That is `timestamp()` (epoch milliseconds, a `number`) subtracted from a later call. `memory_mb()`
reports resident memory, `type_of()` reports types, and the budget failures already produce
diagnostics: unbounded recursion prints `error: call stack is too deep` with a span and a help line.
This report does not claim an observability gap in those places, because measuring them showed there
is not one — an earlier draft of this section asserted that `now()` returning text made elapsed time
unmeasurable, which was wrong; `timestamp()` answers it.

**What did not work.** Subcommands mishandled `--help`, and the diagnostic for a missing file was
not a sentence:

```
$ oq run --help
Cannot find file '--help.oq'.

$ oq check --help
Cannot read '--help.oq': --help.oq

$ oq check absent
Cannot read 'absent.oq': absent.oq
```

`oq build` was fixed for exactly this in an earlier change and carries a comment saying so, and only
its two siblings were checked at first. Auditing the rest found the same defect in `oq fmt`,
`oq new`, `oq doc` and `oq test` — six of the seven file-taking commands in total:

```
$ oq fmt --help
Cannot read '--help.oq': --help.oq

$ oq doc --help
Cannot find the standard library sources under .../--help

$ oq test --help
Cannot scan '--help': not a directory: --help

$ oq new --help
Created project --help/
Run it with: oq --help/main.oq
```

The last is worse than a bad message: it created a directory. A user who types `oq new --help` to
learn the syntax gets a stray project they did not ask for and are not told about. All four now
answer `--help`/`-h` with usage and exit 0, `oq fmt` and `oq new` reject an unknown option by name
with exit 2, and `oq check` reports a missing file as `Cannot find file 'absent.oq'.` instead of the
filename twice — the latter because `NoSuchFileException`'s own message is only the path, so passing
it through gave no reason at all.

That six commands shared one defect is the finding worth more than the fix. A guard copied into one
command is not a rule that binds its siblings, and nothing in the tree enumerates the subcommands, so
nothing could assert that all of them behave alike. The parameterized test added with the fix is the
closest substitute available, and it is a list a future author must remember to extend.

**What remains open.** There is still no execution tracing: no flag reports which statements ran, how
many steps were taken before a budget stop, or which host calls a program made. That is a real gap and
it is not attempted here, because tracing is a language-surface decision — whether the trace is
emitted to stderr, to a file, or exposed as a value is the kind of choice ADR-0059 asks to be recorded
before code. Item 8 therefore stays **partly done** rather than closing, and the honest statement is
that the observability it lacked most was the plainest: asking a command for help.

## Item 9b — Four claims in this report that the tree contradicted

Item 9a ended with a claim this report makes in two places: that nothing compares the agent-facing
keyword counts against the lexer. Checking that claim instead of repeating it produced the opposite
answer, and the same audit found three more entries that had gone stale.

**The keyword counts are gated.** `scripts/check-docs-sync.py` has an `agent_facing` block, added
in PR #252 by the same commit that corrected the count from 20 to 22. It reads three files and
compares each stated count against `Lexer.KEYWORDS`:

```
$ sed -i '' 's/It currently uses 22/It currently uses 19/' \
    .github/agents/oq-hive-orchestrator.agent.md
$ python3 scripts/check-docs-sync.py
Documentation drift detected:
  - .github/agents/oq-hive-orchestrator.agent.md claims 19 keywords but the lexer defines 22
[exit 1; file restored afterwards]
```

Both statements to the contrary — one in Item 6's closing paragraph, one in Item 10 — are corrected
in place rather than deleted, because a report that overstates a remaining gap is as misleading as
one that understates it. What survives is narrower and real: the gate reads the *count*, not the
rest of those files' prose.

**The type-system conflict is decided, against §5.** ADR-0069 (Accepted, 2026-11-18) supersedes
ADR-0015's scope and the brief. Item 10 said the conflict was unresolved pending a superseding ADR;
that ADR now exists and answers the question the other way. §5 is blocked by a decision rather than
by an absence of one, which is a different blocker needing a different record.

**Item 8's status row understated its own fix.** It named two commands after six had been repaired,
and omitted that `oq new --help` created a directory. Corrected in this pass.

The pattern across all four is the one Item 10 of the original report names about R-031: governance
prose about the tree is not checked against the tree. Three of these four were true when written and
became false when a later change landed. Nothing in CI could notice, because nothing compares a
sentence in `docs/governance/` against the behaviour it describes.

## Item 9c — Items 1–9 re-derived, and five more claims that had decayed

Item 9b concluded that three of four sampled claims had gone stale and that the pattern was
structural. The honest next step was to stop sampling. This pass re-derived every quantitative claim
in Items 2, 3, 4, 7 and 8 against the tree rather than against the report's own text.

**Item 8 held up completely.** Thirteen directions, 52 capability rows, D1–D5, `run.py` covering
C1–C8 with 19 probes, and "next ADR is 0070" were all still true when this pass ran:

```
$ grep -cE '^### Direction|^## Direction' docs/governance/alpha-5-java-bridge-capability-inventory.md
13
$ grep -c '^| C[0-9]' docs/governance/alpha-5-java-bridge-capability-inventory.md
52
$ ls docs/governance/alpha-5/java-bridge-probes/*.oq | wc -l
19
$ ls docs/adr/ | wc -l
69
```

The `19` is the one figure in that block that has since moved, and it moved because the gap it
counted was closed rather than because the measurement was wrong: C9–C12 are now probed, so the
command prints 24. Re-running it today gives `13`, `52`, `24`, `69`. That is the intended direction
of change — the count grew because coverage grew — and it is why this paragraph now reads in the past
tense instead of claiming a number that a reader would immediately contradict.

That matters for the conclusion: the decay is not uniform. Item 8 cites a document that has not been
edited since the report was written, so nothing could drift. The items that decayed are the ones
whose subject the report's own sequencing then changed.

**Five more claims had decayed**, all corrected in place above:

| Claim | Was true at | Why it changed |
| --- | --- | --- |
| Item 2: Open 7 / Mitigated 10 / Accepted 13, total 30, Low 13 | `d306833` | PR #253 moved R-018 to Mitigated; PR #254 added R-031 |
| Item 3: R-018 and R-023 "Reproduced exactly" | `d306833` | Both were repaired by PRs #252 and #253 |
| Item 4: `allFuzzTargets` "declares exactly 8" | `d306833` | PR #253 took it to 11 |
| Item 4: all ten named builtins have "zero occurrences" | `d306833` | PR #253 covered nine of the ten |
| Item 7: §5 "requires superseding ADRs" for ADR-0015 and the brief | `d306833` | ADR-0069 did exactly that, and answered against §5 |

Every one of the five was a correct derivation on `d306833`. None was wrong when written. That is
the finding, and it is sharper than Item 9b's: **the report decayed because it worked.** Four of the
five were invalidated by later commits executing the report's own Item 9 sequence — #252, #253 and
#254 are entries 1, 2 and 3 of that sequence, and ADR-0069 is entry 4. A governance document that
correctly orders its own remediation will be contradicted by that remediation, and nothing in CI
distinguishes "this claim was always false" from "this claim was true and is now retired".

Only one of the five is a genuine open blocker rather than bookkeeping: Item 7's. It read as though
§5 needed two supersessions and was therefore close to unblocked. In fact §5 is further away than
that, because the supersession happened in the opposite direction. That is the difference between a
stale count and a stale conclusion, and it is why the audit was worth running past Item 8.

Two things were deliberately not changed. The Items 1, 5, 6, 8, 9 and 10 prose that is narrative
rather than quantitative was left alone; re-deriving "the register is mostly honest about what is
unfinished" is not a command. And Item 2's original counts are preserved in the correction rather
than deleted, because a report that silently rewrites its own history cannot be audited a third time.

## Item 9d — Item 6 was not stale, it was unbounded

The five claims corrected in Item 9c share one shape: each was true on `d306833` and a later commit
made it false. Item 6 is a different failure and the only one of its kind in this report. It was not
true and then false. It was **never verifiable**, because the thing it reports on is not written down
anywhere in the repository.

Item 6's status cell read "Unblocked", and the reasoning behind the word is sound as far as it goes.
The three adjustments it names were real obstacles, all three were genuinely discharged, and the
Resolution table documents each with a command and a result. What the cell never established is that
what remains is *work*. Three cleared obstacles in front of an unbounded directive is not
"Unblocked" — it is "nothing is known to be in the way, because nothing is known".

Two prior passes noticed this and deferred it. Rather than defer again, the scope was looked for.
The four files that mention the phrase at all are all accounted for, and none of them defines it.
Run against `41191b7`, before this pass added text of its own:

```
$ git grep -ci 'language-core\|language core\|core language' -- docs/ AGENTS.md .github/ .kilo/
docs/governance/alpha-4-workstream-assignment.md:1
docs/governance/alpha-7-first-report.md:7
docs/governance/remediation/alpha-4-execution-report.md:1
docs/governance/reviews/PR-257.md:2
```

The assignment hit is workstream 7's title in a mapping for Alpha 4. The execution report hit is
`## 5. Language core — completed`, a closing heading. The two review-record hits are PR #257
quoting this report. The seven are this report's own references. **No document states what §4
orders.** Since Items 7 and 8 both paraphrase their section in one sentence each, the absence here is
an omission in the report, not a convention of the directive.

The status is now **Blocked as written**, and the unblocking step is to obtain §4's text. Recording
it as blocked is more useful than recording it as unblocked, because "unblocked" invites someone to
start, and starting an unbounded task is how a 30-minute task becomes a rewrite.

This is also the third consecutive pass to find that absence, and the first to act on it. That
pattern — noticed, deferred, noticed again — is the same one that let Items 2, 3, 4 and 7 drift, and
it is worth naming: a deferred finding decays faster than a recorded one, because nothing about it
changes and so it keeps looking smaller.

## Item 9e — A7.9: the mechanism the last four reviews asked for

Items 9b, 9c and 9d each repaired claims by hand, and so did PR-260, PR-261, PR-262 and PR-263 before
them. Four records in a row found the same defect and three of them wrote the same sentence: *nothing
re-derives these figures, so the next pass will find new ones*. Nothing was added. This pass adds it.

`scripts/check-report-claims.py` re-derives every quantitative claim it can and runs in the same CI
step as the other six gates. It deliberately does not hold a table of expected totals — an extractor
that compares against a hardcoded copy of the answer is a second copy that goes stale, which is the
defect it exists to remove. It reads the number *out of the sentence* and the truth *out of the file
that owns it*:

| Claim | Owner |
| --- | --- |
| probes, probe-coverage range, per-group probe counts | `docs/governance/alpha-5/java-bridge-probes/run.py` and the `c*.oq`/`d*_*.oq` files |
| capability rows, directions, confirmed defects | `docs/governance/alpha-5-java-bridge-capability-inventory.md` |
| keywords | `Lexer.java` |
| fuzz targets | `build.gradle.kts` |
| ADRs | the ADR directory |
| risk status counts and the Open ID list | the register, through `check-risk-register.py`'s own `parse_rows` |

The last row is worth stating plainly. `check-risk-register.py` parses the `status` column at line 91
and never reads the value, so the register's arithmetic is enforced while its *contents* were trusted
by whoever quoted them. Rather than add a second parser that could diverge, this gate imports the
existing one.

**The gate is tested in both directions.** 22 tests in `scripts/tests/test_check_report_claims.py`:
fourteen reintroduce a real class of drift and require rejection, one is the positive control for the
ADR repair just below, three assert the dated snapshots the record depends on stay silent, and four
guard the derivation and the exemption lists. The silence tests are not decoration. A gate that
rejects §9c's deliberate quotation of a historical figure is a gate the first person to hit it
deletes from CI.

**What mutation testing found while building it.** Fifteen deliberate corruptions of the real report,
against the first version: a mutated total, a mutated spelled-out total, a mutated coverage range, a
mutated probe total written without the unit word, a mutated bare target count, three mutated status
cells, a mutated table total, a wrong Open ID, a mutated defect range, and a mutated per-group subset.
That harness reported seven misses on its first run, which was the useful result — the pairing rule
treated any multi-figure sentence as a before/after pair. The current harness runs eleven catching
mutations and four silence mutations, all correct:

```
$ python3 scripts/check-report-claims.py
report claims: every figure agrees — 24 probes, 52 capability rows, 13 directions,
22 keywords, 11 fuzz targets, 69 ADRs, 5 defects, 31 risks (6 Open)
exit=0
```

**It found a bug in itself, and the first review that missed it was mine.** `NOUN_KEY` was built from
each noun as written and then read with the matched text lowercased, so the `("ADRs", "adrs")` entry
was unreachable. Any live sentence stating an ADR count raised `KeyError` out of `classify` — not a
wrong answer, a crash, which would have taken the CI step down with an unhandled traceback. It
survived because the phrase appeared only inside fenced blocks, which are exempt, and none of the
eighteen tests then in place injected an ADR sentence into live prose. The fix keys the map on
`lower()` and two new tests pin both directions: the wrong count is rejected, and the correct count is
silent. A crash is not a pass; this is the one defect of the set that a passing gate could not have
told me about.

**It found a real defect in this report, not only in the tests.** Line 664 read "It claims eight
targets exist" in the present tense, true on `d306833` and false since `e43d384` added three. Every
prior pass had checked the fuzz counts it was looking for and not this one. The sentence now reads
"eleven targets exist today, that the run at `d306833` covered eight of them", which is what was
always meant.

The gate also forced a decision it had been avoiding. Its first version treated any sentence with two
figures as a before/after pair, which silenced almost everything; its second treated every figure as
live, which fired on the dated halves. What settles it is a rule about *position*: a dating clause
before the first figure or after the last governs the whole sentence, and one between figures governs
only its own clause. Line 356's parity row dates its first half mid-sentence and must stay silent
there while still checking the second half, and Item 8's dated list carries its predicate after the
last figure and must go quiet entirely. Both are now tests.

**What this does not cover.** Any claim-shaped sentence the rules cannot parse is a *failure*, not a
skip — that is asserted by a test — but a sentence that is not claim-shaped at all still passes.
Prose saying "most targets" or "several probes" is invisible here. The gate reduces the class of drift
that started this sequence; it does not eliminate it, and a future pass that finds a number inside a
sentence with no recognised noun will have found a limitation of this gate rather than a new
exception to it.

One asymmetry inside the pairing rule is deliberate rather than overlooked. In "the register held 30
risks and now holds 31", the earlier figure carries a noun and the later one does not, so only the
earlier figure is recognised and it is checked — the sentence is reported, correctly. In "the count
was 22 keywords and now says 24", the earlier figure is recognised and the later one is not, so the
pairing exemption applies to nothing and again only the earlier figure is checked. Both halves come
out checked, which is the safe direction: the failure mode worth preventing is a stale figure passing
quietly, not a stale figure being reported twice. Requiring both halves to carry a noun before the
exemption applies would be tidier, and it would also have re-silenced the bare-total shapes closed
above.

## Item 9f — A7.10: the one capability row the harness could not reach

§3.1 named C11.1 — a class on the user's own classpath — as the remaining gap in the probe
matrix. The inventory classified it `Complete` and its evidence was real: someone had run
`use Hello` by hand with and without `-cp` and written down both outcomes. What was missing was
a gate, and the reason was mechanical rather than editorial. `run.py` launched every probe as
`java -jar`, and `-jar` ignores both `CLASSPATH` and the working directory, so a probe written
that way cannot reach a class that is not inside `oq.jar` no matter what is on the disk.

**The gap was closed by changing the launch form for one probe, not for all of them.** A
`Hello` class was checked in as `Hello.java` beside the probes and is compiled into a jar of its
own on every run. `EXTRA_CLASSPATH` names the probes that need a second jar alongside `oq.jar`;
`launch()` emits `java -cp "<oq.jar><sep><fixture>" …` only for those, and every other probe
keeps the `-jar` form its recorded output was produced under. Changing all of them would have
moved evidence that has nothing to do with this gap.

The jar is built rather than committed, and that is not a preference. `*.jar` is in `.gitignore`,
so a fixture jar cannot be checked in at all — `d2_reflection_reachable.jar` sits on disk in the
same directory and is **not tracked**, which the first draft of this item got wrong by citing it
as precedent. The PR-263 review record made the same mistake from the other side: it offered "a
committed fixture jar" as one of the two ways to close this gap, and that option never existed.
Building from committed source is the only shape available, and it is also the better one: the
class is reviewable, and it cannot drift from the jar because the build regenerates it.

Both halves were run before the expectation was written down, and both failed the old way and
passed the new one:

```
$ java -jar oq-cli/build/libs/oq.jar run c11_user_classpath.oq
error: cannot find Java class 'Hello'
$ java -cp "oq-cli/build/libs/oq.jar:hello_fixture.jar" \
    dev.oqthara.cli.Main run c11_user_classpath.oq
hi world
```

The compiled half needed the same treatment. `oq build` writes `Main-Class: oq.generated.Program`
into the manifest, and `java -jar` on that jar cannot see the fixture either, so `compiled_launch()`
spells the class out under `-cp` when a probe needs one. Both backends now print `hi world`.

**Five things are gated, not asserted.** `scripts/tests/test_probe_extra_classpath.py` runs
twelve tests over the launch decision and the fixture path: the classpath probe gets `-cp` and the
others do not, the compiled half can see the fixture and keeps `-jar` without one, the separator is
the platform one, the fixture is built from source rather than committed, and both a broken fixture
and a missing `javac` are failures rather than skips.

Three of those only became real after a mutation survived. The separator test originally compared
against `os.pathsep`, which *is* `:` on the machine running it, so a hard-coded `:` passed — it now
substitutes a separator that appears in neither path. A mutation that made a missing fixture source
`return` quietly also survived, because the tests only exercised the paths that worked; that case
now has its own test. Each of the four mutations is caught. The harness itself fails both ways when
the mechanism breaks: with `launch` forced back to `-jar` the probe reports the real `cannot find
Java class 'Hello'`, and with `Hello.java` removed it reports the missing source. A probe that
skipped itself when a fixture was absent would report a pass for a check that never ran.

Before the probe was added, this gate caught the drift the change introduced:

```
$ python3 scripts/check-report-claims.py
report claims: 1 problem(s)
  - line 356: says 24 probes but the harness runs 25
```

That is the A7.9 mechanism doing its job on the first figure this pass changed. The count is
derived from `run.py`'s own `PROBES` table, so it followed the probe automatically once the line
was corrected, and the inventory's four stale "24 probes" figures were repaired in the same pass.

## Item 10 — Remaining blockers and risks

- **The type-system conflict is resolved, in the opposite direction to §5.** ADR-0069 (Accepted,
  2026-11-18) supersedes ADR-0015's scope and the type-system brief: Oqthara stays dynamically
  typed and `data` fields stay untyped. This entry previously read "unresolved … until an ADR
  supersedes ADR-0015", which was true when the report was written and is not true now. §5 as
  ordered cannot start because the question it asks has been answered *against* it, so any typing
  work needs a further ADR superseding ADR-0069 — not merely one superseding ADR-0015. The two
  conformance cases keep saying what they say, and now they say it on a decided question rather
  than a pending one.
- **#251 was red on a required check** from the moment dependabot opened it, because ADR-0059
  requires an AI review record per pull request and no one wrote one. It is merged now (`9eb3561`),
  but the gap it exposed is unowned: nothing generates a review record on the automated path, so
  the next dependabot pull request will be red for the same reason.
- **The path-resolution fuzz target has no agreed design.** Private, host-dependent; either a stub
  `OqHost` or a production-code extraction. The second is better and is a change to production code
  justified by a test, which needs saying out loud.
- **`oq-hive-language-syntax` was not on the primary orchestration path** and its keyword count was
  stale. Both are fixed: #252 corrected the count to 22, and #257 named both agent directories in
  `AGENTS.md` and wrote down where syntax ownership stops. **The claim that no gate reads the agent
  files was wrong** — `scripts/check-docs-sync.py` has compared those three counts against
  `Lexer.KEYWORDS` since PR #252, and it was verified by editing a count and watching the gate
  fail. What remains genuinely unowned is narrower: that check covers the *keyword count* in those
  files, not their other prose. A statement about, say, which backend supports which feature could
  still drift unobserved.
- **Workstream 2 is `Under-covered`** and §5 lands entirely inside it.
- **§4's directive text is not in this repository.** Item 6 reported "Unblocked" for three passes on
  the strength of the ownership adjustments, which are real. But the section it clears the way for is
  described nowhere: items 7 and 8 both state what their section orders, item 6 states what it clears
  and not what it authorizes. The ownership fixes were necessary and are not sufficient, and the
  difference has been deferred twice. Status is now **Blocked as written**, and the unblocking step is
  to obtain §4's text, not to make another adjustment.
- **Swing has partial coverage and no test** — direction 13 records six executed capabilities, but
  no example and no conformance case exist, and nothing in CI would notice a regression. What
  cannot be covered headlessly (layout, painting, input, look-and-feel) is still unmeasured, so
  §6's "Swing first" is now half a measurement.
- **R-002's dangling "See R-008."** at line 95 is a pre-existing inaccuracy, wrong since `1e87f00`,
  deliberately deferred and still deferred.
- **The security baseline's "measured starting point" table has drifted, and the new gate already
  shows it.** `docs/governance/alpha-7-security-baseline.md` said its figures "were re-derived from
  the tree on the commit that carries this file, none is carried forward". Run against today's tree,
  three of the eight are stale: threats mitigated 14→18, partially mitigated 12→11, and risk-register
  entries 30→31 (R-031, "a gate's allow-list silently stops matching", was added by `db01622` when
  the Dependabot login was exempted). The `18 + 11 + 14 + 12 = 55` arithmetic and the 33
  host-dependent names still hold.
  The measurement was never wrong. It was taken at `e43d384` and the sentence around it claimed to be
  current, so every later merge made the claim false while leaving the numbers as accurate history.
  The baseline now names `e43d384`, states which three figures have moved since, and points a reader
  who needs today's numbers at the gates named in the Source column. The table itself is unchanged,
  because rewriting it with figures measured on a different tree would repeat the mistake it was
  accused of.
  This is the fifth document found carrying a figure that decayed silently. Unlike the first four it
  was not repaired by editing a number: the defect was in a tense, and correcting the tense was
  enough.
- **No release, tag, deployment or version change is authorized** (§10), so none of the above may be
  validated by shipping.

## What this report does not claim

- It does not claim the risk register is wrong beyond the two corrections named; the other five Open
  entries were re-checked and hold.
- It does not claim the fuzz suite is trustworthy because it passes. It claims eleven targets exist
  today, that the run at `d306833` covered eight of them and found seven defects, and that eight more
  pure builtins are reachable and untested.
- It does not claim §5 is illegal. It claims §5 contradicts a *decided* question and therefore needs
  a superseding record, which the brief's own "one decision, not one migration" makes affordable.
- It does not claim `oq-hive-language-syntax` is unusable. It claimed it was not where `AGENTS.md`
  said agents live and that its keyword count was stale; both were corrected in #252 and #257.
- It does not claim Swing works. It claims six specific capabilities were executed under a headless
  JVM and matched their expectation, and that nothing tests layout, painting, input dispatch or
  look-and-feel.
