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
| Open | 5 |
| Mitigated | 12 |
| Accepted | 14 |
| **Total** | **31** |

Exposure buckets, which `scripts/check-risk-register.py` enforces arithmetically: Critical 1,
High 4, Medium 12, Low 14. The arithmetic closes.

The five Open entries are **R-012, R-021, R-022, R-023, R-027**. Four of the five are
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
| R-018 | 8 targets in `allFuzzTargets` at the time; 10 named gap builtins had zero occurrences in the fuzz source | **Reproduced exactly then**, and superseded twice — 11 after PR #253, 14 after Item 9h; see item 4 |
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
| Stale keyword count (20 vs 22) | **Fixed in PR #252.** Verified independently: `Lexer.KEYWORDS` holds 22 words (6 + 6 + 5 + 5), and the agent file reads 22 words |
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

**The fourth obstacle was a missing definition, and it is discharged too.** The three adjustments
above bounded ownership. They did not bound the work, because nothing in this repository said what
§4 ordered. **Item 6b** now records §4's scope, its exclusions, the ownership boundaries within it,
and the first deliverable. The prose above describing that absence is left standing as the record of
what was wrong and for how long; it is no longer the current state, and Item 6b is.

## Item 6b — §4's scope, recorded

Item 6 established that §4 was blocked because nothing in this repository said what it ordered, and
that the unblocking step was text from outside the repository. That text now exists: Alpha 7's
revised execution order, §3, supplies it. This item records the scope so the boundary lives in the
repository rather than in a directive a later session cannot read.

### What §4 covers

The normative language surface and its semantics:

- Lexer, tokens, grammar, parser, and AST.
- Operator precedence and expression evaluation.
- Statements, control flow, assignment, binding, and scope rules.
- Functions, lambdas, closures, and call semantics.
- Iteration, destructuring, and collection-related language constructs.
- Module and import semantics.
- Error and diagnostic behavior that forms part of the language contract.
- Interpreter and compiled-backend agreement for supported language behavior.
- Synchronization of syntax-facing editor grammars with the normative specification.

Existing object-oriented behavior may be audited for consistency, but OOP completion remains an
Alpha 9 objective. It is not part of §4's implementation scope.

### What §4 does not cover

| Excluded | Where it belongs instead |
| --- | --- |
| Java Bridge Full | §6, this report's Items 8 and 9 |
| Optional strict typing and annotations | §5, gated on ADR-0071 superseding ADR-0069 (Item 7) |
| Generics as a distinct pre-0.2.0 deliverable | Its own deliverable, not §4 |
| Standard-library breadth | Not §4 |
| Runtime Manager and observability | Item 9a, which names what the tree already needed |
| Alpha 7/8 security engineering | `alpha-7-security-baseline.md` |
| Grafeno and OQSS | Outside this repository's Alpha 7 work |

**The twelve ADR-0062 refusals remain refusals** unless a specific proposal formally reverses the
relevant decision. §4 is not permission to quietly revive rejected constructs, and this scope record
grants no authority to reopen them. They are the `NOT STARTED` rows of
[`alpha-6-requirement-matrix.md`](alpha-6-requirement-matrix.md) lines 38–50 — `match`/`case`, list,
map, set and generator comprehensions, `yield`, lazy range iteration, `switch`, `import` as a
statement, `throw`, `def`, and `async`/`await` — and each states the diagnostic it produces.
`docs/adr/0062-a-diagnostic-names-the-construct.md` records why the leader words among them are
refused by name rather than by a bare parse error. The matrix's row 70 pins the refusal with a test
that all twelve exit 1 and none exits 0.

### Ownership

`oq-hive-language-syntax` is the **sole technical design owner and author** for language-core
evolution, with authority to inspect the existing language and propose improvements from its own
technical judgment. Other agents must not create competing syntax designs or independently author
conflicting language contracts.

| Agent | Boundary |
| --- | --- |
| `oq-hive-language-syntax` | Language design, syntax decisions, the normative specification |
| `oq-hive-development` | Implements runtime, interpreter and compiler mechanics **against that specification** |
| `oq-hive-testing-security` | Adversarial tests, fuzzing, conformance review |
| `oq-hive-architecture-governance` | Records decisions requiring ADRs |

Implementation support does not grant another agent authority to replace or independently redesign
the language contract. This is not a new boundary: `.github/agents/oq-hive-language-syntax.agent.md`
line 45 already states it under "Where your ownership stops". `AGENTS.md` names both agent sets but
does not carry that section, so the agent file is the binding text and the operating instructions are
not — the same split Item 6 recorded when it found the operating instructions naming only `.kilo/`.

### First deliverable

A language-core capability matrix, before broad implementation. Each row needs:

1. The current documented behavior.
2. The implementing lexer/parser/AST/runtime/compiler locations.
3. Existing conformance and regression evidence.
4. A verdict: complete, defective, ambiguous, intentionally refused, or missing.
5. A proposed correction or improvement, where justified.
6. Compatibility implications and acceptance tests.

Every proposed improvement must carry a minimal Oqthara example and precise expected behavior.
Proposals beyond the initial inventory are permitted; unbounded change is not. Each one needs a
defined semantic contract, a compatibility analysis, and measurable acceptance criteria. After the
audit establishes a bounded backlog, it is implemented in focused milestones — not as a broad parser
rewrite or an uncontrolled keyword expansion.

### Why this is recorded here

The report is the canonical Alpha 7 governance document, and only two things cite it by path:
`alpha-7-security-baseline.md` line 16 calls it "the report which reviews the risk-register
boundary", and `scripts/check-report-claims.py` parses it. That second one is the reason to record
the scope here rather than in a new file: a checker already reads this document, so a scope
recorded here can be gated, and a scope recorded in a fresh file would be one more document nothing
verifies.

The risk register and the threat model do **not** cite it — the paragraph this replaces said they
did, which was an assumption rather than a check. The argument survives without them, because the
gate is the stronger reader.

Item 6's own history is the evidence that an unscoped section is invisible: it reported "Unblocked"
for three passes on the strength of ownership adjustments while the section it cleared the way for
was described nowhere.

The scope above is a transcription of the order, not an interpretation of it. Where the order and
this repository disagree, the discrepancy is a finding to record, not a licence to pick the more
convenient reading.

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

**Next ADR number is 0071** (70 exist; highest are `0069-oqthara-stays-dynamically-typed.md` and
`0070-java-functional-values-stay-java-objects.md`). ADR-0070 was written by §2.3, not by the
typing work this item anticipates; §4's supersession therefore takes 0071.

## Item 8 — Java Bridge Full validation matrix

The authoritative inventory is `docs/governance/alpha-5-java-bridge-capability-inventory.md`:
**thirteen** directions, **55** capability rows, and **six** confirmed defects **D1–D6**. §6 asks
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
- **Was open, now half closed:** layout, painting, input dispatch and look-and-feel were untested
  because this section assumed a headless runner cannot cover them. That assumption was wrong:
  `c13_offscreen` now gates all four on both backends with no display. What remains open is
  *displaying* a window — and with it the platform's own window manager — which a headless runner
  genuinely cannot cover. The failure a program gets when there is no display *is* covered, by
  `c13_window` (Item 9j), which pins `-Djava.awt.headless=true` so the answer does not depend on
  the host.

What the pass established: components construct and answer, a closure runs on the event dispatch
thread, a closure is accepted as an `ActionListener` and receives the real `ActionEvent`, captures
survive the toolkit calling from a thread the program did not create, and interpreter and compiled
bytecode agree. What it also found: opening a `JFrame` without a display was reported as an
unchangeable collection, which is D5; and that drawing at all failed, because the bridge treated a
public JDK class as reachable when its package is not exported, which is D6.

A validation matrix for §6 has to be built, not consulted. The minimum it must cover, with each row
required to execute rather than be read:

| Area | Ceiling that already exists |
| --- | --- |
| Swing construction and display | direction 13, C13.1 and C13.2; display still untested. C13.7 gates the rest of the toolkit without a display |
| Event dispatch and thread affinity | direction 13, C13.3–C13.5; `print` is still non-atomic (R-029) |
| CLI, network, filesystem, JDBC | directions 1–3; JDBC behind R-006/R-007 |
| Streams and `java.util.concurrent` | direction 7 for callbacks; D1 fixed in #231, so callbacks are accepted |
| Third-party classpath | direction 11 |
| Interpreter-versus-compiled parity | direction 10; `run.py --both` covered C1–C8 in 19 probes when this table was written, and covers C1–C13 in 28 as of the offscreen-Swing pass. C11.1 — a class on the user's own classpath — used to be the one row the harness could not reach, because `java -jar` ignores `CLASSPATH`; `c11_user_classpath` now runs as the single `java -cp` probe and closes it. C13 was hand-executed until `c13_offscreen` made the parts of it that need no display a gated probe, and `c13_window` gates the one that does: it pins `-Djava.awt.headless=true` so the answer is the same on a desktop and on CI, which is what makes the display-dependent half testable where no display exists |

One constraint from the tree must be honoured, not worked around: the probe harness covered C1–C8
when this section was written and no direction was probed headlessly, so a GUI program's
compiled-versus-interpreted parity would not be gated by anything that exists today. That has
narrowed but not closed. `c13_offscreen` now gates layout, painting, input dispatch and
look-and-feel on both backends without a display — the earlier claim that a headless runner cannot
cover them was wrong, and the probe is the counter-example — but *displaying* a window still
needs one, so parity for a program that shows a real window still has no gate. Note that the call
that fails is `new Frame(...)`, not `setVisible`: `Window`'s constructor runs `checkHeadless()`
itself. `c13_window` gates that failure, so the boundary is tested even though the display is not.
The other constraint this section used to name — D1, a
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
6. **§4 language-core evolution**, after item 2 so the owner is reading a correct count. The three
   ownership adjustments are discharged and Item 6b records the scope, so nothing blocks this entry
   any longer. Its first deliverable is the capability matrix Item 6b specifies: an inventory with a
   verdict per row, not an implementation sweep.
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
| 6 | §4 language-core evolution | **Unblocked** — ownership, keyword count and the type-system scope split were discharged by Item 6's Resolution, and Item 6b now records §4's scope in this repository, which was the last thing missing. The next deliverable is the language-core capability matrix Item 6b defines, owned by `oq-hive-language-syntax` |
| 7 | §6 Java Bridge Full | Partly done — direction 13 added (C13.1–C13.7). Layout, painting, input dispatch and look-and-feel are gated by `c13_offscreen` with no display (Item 9i), and the no-display failure is gated by `c13_window` (Item 9j). Only displaying a window on a real screen is still open |
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
counted was closed rather than because the measurement was wrong: every direction the inventory
lists is now probed, C13 included, so the command counts 29 probe files where it counted 19. That
is the intended direction of change — the count grew because coverage grew — and it is why this
paragraph reads in the past tense instead of claiming a number that a reader would immediately
contradict.

The other three figures in that block need the same care, and the first draft of this paragraph did
not give it. It said the block had one stale figure and quoted the rest as current. In fact the
inventory gained a capability row and a probe file in `ec8947a`, the commit that closed the C13 gap,
so by the time this paragraph was written the block held **two** figures that had moved, not one.
Measured now: 13 directions, 55 capability rows, 29 probe files, 70 ADRs. Two of the four differ
from the block above, which is the **two** this paragraph just said had moved.

That matters for the conclusion: the decay is not uniform, and the reason is mechanical. Item 8 cites
a document that has not been edited since the report was written, so nothing could drift. That was
true when it was written and is false now: `66e68f8` and `ec8947a` both edit the capability
inventory, and `ec8947a` edits `run.py` as well. The ADR count survived every edit made before §2.3,
because nothing then in this branch wrote to `docs/adr/`; ADR-0070 ended that, and the same paragraph
now carries 70. A figure stays true here for exactly as long as no commit touches its subject — which
is a property of the branch, not of the figure.

**Five more claims had decayed**, all corrected in place above:

| Claim | Was true at | Why it changed |
| --- | --- | --- |
| Item 2: Open 7 / Mitigated 10 / Accepted 13, total 30, Low 13 | `d306833` | PR #253 moved R-018 to Mitigated; PR #254 added R-031 |
| Item 3: R-018 and R-023 "Reproduced exactly" | `d306833` | Both were repaired by PRs #252 and #253 |
| Item 4: `allFuzzTargets` "declares exactly 8" | `d306833` | PR #253 took it to 11, and Item 9h to 14 |
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

The status was corrected from **Unblocked** to **Blocked as written** at the time, with the
unblocking step being to obtain §4's text rather than make another adjustment. Recording it as
blocked was more useful than recording it as unblocked, because "unblocked" invites someone to start,
and starting an unbounded task is how a 30-minute task becomes a rewrite. That text has since been
supplied and **Item 6b** records it, so the status is now **Unblocked** on a written scope rather
than on an absent one — which is the distinction the original correction was making.

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

**The gate is tested in both directions.** 30 tests in `scripts/tests/test_check_report_claims.py`:
seventeen reintroduce a real class of drift and require rejection, eight assert the dated snapshots and
unambiguous sentences the record depends on stay silent, and five guard the derivation and the
exemption lists. The silence tests are not decoration. A gate that rejects §9c's deliberate quotation
of a historical figure is a gate the first person to hit it deletes from CI.

**What mutation testing found while building it.** Fifteen deliberate corruptions of the real report,
against the first version: a mutated total, a mutated spelled-out total, a mutated coverage range, a
mutated probe total written without the unit word, a mutated bare target count, three mutated status
cells, a mutated table total, a wrong Open ID, a mutated defect range, and a mutated per-group subset.
That harness reported seven misses on its first run, which was the useful result — the pairing rule
treated any multi-figure sentence as a before/after pair. The current harness runs seventeen catching
mutations and eight silence mutations, all correct:

```
$ python3 scripts/check-report-claims.py
report claims: every figure agrees — 28 probes, 29 probe files, 55 capability rows,
13 directions, 22 keywords, 14 fuzz targets, 70 ADRs, 6 defects, 31 risks (5 Open)
exit=0
```

**The gate could not read the sentence that defeated it, and this section held the same defect.** The
first version of the paragraph above stated the gate's own totals, and every one of them had moved:
the probe total, the capability-row total, the fuzz-target total and the defect total were all
smaller than the tree. They survived because they sit in a fenced block, and fenced blocks are exempt:
a gate that compares a figure against the tree cannot also read the transcript of a command that
produced it. The distinction is only honest if the transcript is labelled as a transcript. So this
block is now the gate's *actual* output, re-derived when the sentence around it was last edited, and
any figure in it that a reader wants to trust must be re-run rather than believed. That is the same
rule the report asks of its own claims, applied to the evidence it shows for them.

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
"fourteen targets exist today, that the run at `d306833` covered eight of them", which is what was
always meant. It read eleven from `e43d384` until Item 9h added the compression targets, so the same
sentence has now gone stale twice, once per target batch.

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

## Item 9g — A7.11: a blocker that was resolved the day it was written

Item 10 carried this sentence through every revision of this report: "nothing generates a review
record on the automated path, so the next dependabot pull request will be red for the same reason."
It is a prediction, and predictions are the easiest kind of claim to leave standing, because nothing
tests them until the predicted event happens.

The predicted event did not happen, and the entry should have known. `db01622` — merged 2026-11-18
02:28, before #251 merged at 03:00 — added `app/dependabot` to `AUTOMATED_AUTHORS`. The gate no
longer asks a bot for a review record, so a dependency pull request cannot go red for that reason.
This report's own Item 9 status table already recorded the fix as **Done** at line 401. The report
therefore said, in two places, that the gate was repaired and that it was not.

### The evidence, run rather than read

```
$ python3 scripts/check-ai-review.py --pr 251
  - docs/governance/reviews/PR-251.md is absent, and PR #251 was opened by app/dependabot, an
    automated author that cannot review its own change; every other required context still applies
AI review gate passed for PR #251.                                         [exit 0]
$ ls docs/governance/reviews/PR-251.md
ls: docs/governance/reviews/PR-251.md: No such file or directory
```

The record is absent and the gate passes anyway. That is the exemption working, on the live API, on
the exact pull request the old entry named as evidence that it did not work. `gh pr view 251 --json
author` reports `app/dependabot` with `is_bot: true`, which is the spelling `db01622` added.

### Why the wrong version survived

Because it was never in scope to check. Item 10 is a list of blockers; a blocker is a claim that
something *will* go wrong, so its truth value is settled in the future and no gate that reads the
current tree can settle it. The A7.9 claim gate from Item 9e reads figures back from files. This
sentence contains no figure, no governance noun and no number, so it was invisible to that gate by
design rather than by oversight — Item 9e's "Not verified" section already says so.

The failure mode is worth naming precisely, because it recurs. A cross-reference made the report
self-contradictory: line 401 said the red check was repaired, line 784 said the gap was unowned.
Nothing compared them. Every review record on this branch read one or the other.

### What changed

- Item 10's entry now states what is true: the gate exempts the bot, nothing generates a record on
  the automated path, and nothing needs to. The residual risk is R-031 rather than a certain future
  failure.
- The correction is dated and cites the commands above rather than asserting the outcome.
- `.oqthara-ai/RESUME.md` repeated the same false prediction and is corrected with it.

### What this does not claim

- It does not claim no automated pull request can be red. `github-actions[bot]` is deliberately
  *not* exempt, and a workflow-authored change still needs a record or it fails; PR-254 finding 1
  records why that asymmetry exists.
- It does not claim the exemption is robust. It matches a login against a hard-coded list, and the
  first version of that list matched nothing. R-031 is that class of defect, and `db01622`'s own
  message says the test that should have caught it stubbed the same wrong spelling as the code.
- It does not claim any gate now catches a failed prediction. None does, and none can without
  something that re-runs the prediction. This was found by reading a cross-reference and then
  running one command.
## Item 9h — The compression gap, closed and then made to fail on purpose

Item 4 named two genuinely unreached builtins, `zip` and `compress`, and stopped there because the
remaining work was "write targets", not "decide". This item writes them. The reason to write them
first is that `oq-stdlib/.../Compression.java` is the most security-relevant unreached surface in
the library: it parses gzip and zip framing by hand rather than delegating the whole container to
the JDK, and it enforces a 16 MiB amplification cap *inside* the inflate loop, because a length
field in the input can be lied to and only counted output cannot.

**The gap, re-measured rather than remembered.** Before writing anything, the ten builtins Item 4
names were counted again across `oq-stdlib/src/fuzz/java/`:

```
$ for b in csv_parse csv_write wrap_text fill_text fnmatch glob ini_parse \
    properties_parse zip compress zlib_compress gzip_compress zip_decompress \
    zlib_decompress gzip_decompress; do
    printf '%s=%s ' "$b" "$(grep -rl "$b" oq-stdlib/src/fuzz/java/ | wc -l | tr -d ' ')"
  done
csv_parse=1 csv_write=1 wrap_text=1 fill_text=1 fnmatch=1 glob=1 ini_parse=1
properties_parse=1 zip=1 compress=1 zlib_compress=1 gzip_compress=1 zip_decompress=1
zlib_decompress=1 gzip_decompress=1
```

Every one is now non-zero. The measurement is a grep, and a grep counts a mention in a comment the
same as a call, which is why the counts below are not the evidence — the executed runs are.

**Each new target asserts a property rather than the absence of an exception.**

- `decompressorsNeverThrowUnexpectedExceptions` feeds arbitrary hex to `zlib_decompress`,
  `gzip_decompress` and `zip_decompress`. `OqError` is a normal outcome; anything else, including
  `StackOverflowError` and `OutOfMemoryError`, is a defect. Every result must be lowercase hex,
  which is the shape all three document.
- `compressionRoundTripsItsOwnOutput` compresses then decompresses and requires the original bytes.
  `gzip_compress` takes text and `zlib_compress` takes hex, so the two are driven separately: for
  gzip the identity is the text's UTF-8 bytes, not the raw fuzzer bytes, because the writer
  re-encodes.
- `zipRoundTripsOneEntry` writes a one-entry archive and reads it back. Entry names are filtered to
  what the format can carry without the name becoming a claim about the filesystem: the reader
  refuses absolute names, backslashes and `..` segments by policy, so generating one and asserting a
  round trip would be asserting that a deliberate security decision is a bug.

All three are registered in `allFuzzTargets` as separate `FuzzTarget` entries, one JVM per target,
because Jazzer's JUnit integration runs exactly one `@FuzzTest` per invocation and silently skips
the rest.

```
$ ./gradlew -q printFuzzTargets | cat -n | tail -3
    12	CompressionDecode
    13	CompressionRoundTrip
    14	ZipRoundTrip

$ JAZZER_FUZZ=1 ./gradlew --no-daemon :oq-stdlib:fuzzCompressionDecode \
    :oq-stdlib:fuzzCompressionRoundTrip :oq-stdlib:fuzzZipRoundTrip
Done 893846 runs in 31 second(s)
CompressionFuzzTest > zipRoundTripsOneEntry(FuzzedDataProvider) > Fuzzing... PASSED
BUILD SUCCESSFUL in 2m 1s
```

**A passing run is not evidence the target can fail.** A target that asserts nothing, or whose fix
generator never reaches the assertion, passes forever. So the target was made to fail against a
deliberately broken implementation, in two separate mutations, each reverted afterwards:

```
# Mutation 1: gzip_decompress raises on any 0x78 second byte (i.e. on real zlib input)
$ JAZZER_FUZZ=1 ./gradlew --no-daemon :oq-stdlib:fuzzCompressionDecode
> Task :oq-stdlib:fuzzCompressionDecode FAILED
CompressionFuzzTest > decompressorsNeverThrowUnexpectedExceptions(FuzzedDataProvider) > Fuzzing... FAILED
    java.lang.AssertionError: gzip_decompress() crashed on 1f8b0800000000006500ab000300000000299e12:
    java.lang.IllegalStateException: deliberate positive control

# Mutation 2: zlib_compress drops its last byte
$ JAZZER_FUZZ=1 ./gradlew --no-daemon :oq-stdlib:fuzzCompressionRoundTrip
CompressionFuzzTest > compressionRoundTripsItsOwnOutput(FuzzedDataProvider) > 004c85dc... FAILED
    java.lang.AssertionError: zlib_compress/decompress did not round-trip 000029000000012931ff003e...
```

Both mutations were reverted and `git diff` on `Compression.java` is empty. This is A7.8's
acceptance criterion 6, executed rather than asserted.

**What the runs do not prove.** They ran for 31 seconds per target at 2 GiB of heap. They did not
reach the amplification cap, because generating input that expands past 16 MiB inside a 30-second
budget spends the budget on allocation; the cap is covered by `CompressionTest` and remains a place
where a fuzz run would only ever agree with itself. `docs/spec/fuzzing.md` now says so, and R-018's
target count moved from eleven to fourteen with `printFuzzTargets` still named as the authority.

**The count was stale in four places, and the gate flagged two.** Registering the targets made
`check-report-claims.py` fail on two lines — the R-018 row in the re-check table (line 77) and Item
9g's "does not claim" bullet (line 899). Two other places carried the same obsolete figure and were
not flagged: the `Was true at` claims table's `d306833` row, which is legitimately dated, and Item
9g's quotation of the half-sentence it was describing, which the gate treats as a quotation and not
as a live claim. All four were corrected together.

The two it missed are the two where being missed is most defensible, and that is the point rather
than a defect: the gate resolves *figures*, and a figure embedded in a quotation of a broken claim
looks, to a parser, exactly like the broken claim. The reason the count is now right everywhere is
that four places were edited by hand after two were pointed at — not that the gate found them.

It also cannot tell a total from a delta. Writing "three targets, each asserting a property" about
the batch just added failed the gate on the first draft of this item, because a bare figure before
the word "targets" is a total as far as the rule can see. The sentence was rewritten to name no
figure. That is the right trade — a false positive that costs one rephrasing is cheaper than a
checker that guesses which figures are deltas — but it means the gate's green is evidence that the
totals agree, and nothing more.

## Item 9i — §6: the GUI gap was two gaps, and only one of them was the display

Item 8 recorded three obstacles under §6 and closed two of them. The third read:

> **Still open:** no test opens a *visible window*. Layout, painting, input dispatch and
> look-and-feel are untested, because a headless runner cannot cover them.

The premise is false for four of the five things it lists. Only *realising* a window needs a
display. Constructing components, laying them out, painting them into an offscreen image,
dispatching an input event and asking the look-and-feel for a colour all run under
`-Djava.awt.headless=true`:

```
$ java -Djava.awt.headless=true -cp /tmp/oqcls Off
headless=true
count=2 lbl=java.awt.Rectangle[x=10,y=10,width=100,height=20]
painted=true px=19922
laf=Metal
```

So the gap was never "the runner has no screen". It was two separate things, and separating them
is what made the second one findable.

### What was actually blocking painting

Trying the same thing through the bridge found it immediately:

```
$ java -jar oq.jar run t2.oq
sun.java2d.SunGraphics2D

error: cannot call dispose: class dev.oqthara.core.JavaBridge cannot access class sun.java2d.SunGraphics2D
       (in module java.desktop) because module java.desktop does not export sun.java2d to unnamed
       module @150c158
```

`BufferedImage.createGraphics()` answers a `sun.java2d.SunGraphics2D`. That class is *public*, and
`Modifier.isPublic` said yes, so the bridge handed the `Method` straight to `invoke` — but a
modular JDK requires the class's package to be **exported** as well, and `java.desktop` exports no
`sun.*` at all. The identical declaration on `java.awt.Graphics` invoked fine, which is what made
it a bug in the bridge rather than a limit of the JDK. This is **D6**.

The repair is one predicate, `reachable`, used wherever `accessible` and `publicDeclaration` used
to ask `Modifier.isPublic`:

```java
if (!Modifier.isPublic(type.getModifiers())) return false;
Module owner = type.getModule();
if (owner == null || !owner.isNamed()) return true;
return owner.isExported(type.getPackageName(), JavaBridge.class.getModule());
```

A class on the classpath lives in an unnamed module, which is open by definition, so nothing about
application classes changed. Two tests in `JavaBridgeAccessibilityTest` pin it, and both fail when
the predicate is reverted:

```
$ ./gradlew :oq-core:test --tests '*JavaBridgeAccessibilityTest*'     # fixed
5 tests completed

$ # reachable() cut back to "return Modifier.isPublic(...)"
$ ./gradlew :oq-core:test --tests '*JavaBridgeAccessibilityTest*'
5 tests completed, 2 failed
```

### The probe, and the assertion it deliberately does not make

C13.7 is now gated rather than hand-run. `c13_offscreen` lays a `JButton` out inside a `JPanel`,
paints the panel into a `BufferedImage`, counts the pixels the paint changed, clicks the button on
the event dispatch thread and checks the closure received `["go"]`:

```
$ python3 docs/governance/alpha-5/java-bridge-probes/run.py oq-cli/build/libs/oq.jar --both
ok: 28 probes match their expectation
ok: 28 probes compared compiled bytecode against the interpreter
```

Two details in that program are there to stop it from measuring the wrong thing. It prints
`painted > 0` **and** the count, because a boolean alone passes if a single stray pixel is painted;
the count `20000` is the exact number a correct `fillRect`-plus-`paint` produces. And it prints
`getLookAndFeel() != nothing` rather than the look-and-feel's *name*, because the name is
`Mac OS X` on this machine and `Metal` on a bare Linux JVM — naming it would make the probe pass or
fail on the machine instead of on the bridge, which is the same mistake D5's missing conformance
case avoids.

The probe was mutation-tested before it was trusted, because a passing probe is evidence of
nothing until it can be shown to fail:

```
$ # c13_offscreen with `print painted > 0` changed to `print false`
$ python3 .../run.py oq-cli/build/libs/oq.jar; echo $?
FAILED
  c13_offscreen: expected ['true', '20000', '["go"]', 'true', 'true'], got ['false', ...]
1
```

### What this closes and what it does not

Closed: layout, painting, input dispatch and look-and-feel are gated on both backends, in CI, with
no display required. Direction 13 is no longer supported only by runs somebody did by hand.

Not closed: opening a *visible* window still has no gate that exercises a real display. The
matrix row in Item 8 says exactly that instead of claiming the whole toolkit is untestable.

### The second half, and where the boundary actually is

The paragraph above first said this would need a virtual framebuffer on the CI runner. That was
another unexamined premise, and checking it moved the boundary. A Java program was run under
`-Djava.awt.headless=true`:

```
$ java -Djava.awt.headless=true -cp . H
headless=true
Exception in thread "main" java.awt.HeadlessException
	at java.desktop/java.awt.GraphicsEnvironment.checkHeadless(GraphicsEnvironment.java:164)
	at java.desktop/java.awt.Window.<init>(Window.java:553)
	at java.desktop/java.awt.Frame.<init>(Frame.java:428)
```

The heading said "`setVisible` needs a display". The call that fails is `new Frame(...)`:
`Window`'s constructor runs `checkHeadless()` itself, so `setVisible` is never reached. This
matters for the same reason D5 did — a note that names the wrong call cannot be turned into a
test, because the test would call the working method and pass.

The last open part is therefore *displaying* a window, not constructing one, and the construction
failure is testable exactly where no display exists. `c13_window.oq` gates it. It prints whether
the JVM reported itself headless, then constructs a frame inside a `try`, and asserts four things:
that the headless flag is set, that the construction was caught, that the caught error carries a
message, and that a second attempt fails the same way rather than returning a half-built window.

```
$ python3 docs/governance/alpha-5/java-bridge-probes/run.py oq-cli/build/libs/oq.jar --both
ok: 28 probes match their expectation
ok: 28 probes compared compiled bytecode against the interpreter
```

The probe asserts no exception class and no message text. The class is `HeadlessException` on this
JDK, but that is `java.desktop`'s choice rather than the bridge's, and pinning it would test the
JDK version. This is the same distinction the offscreen probe draws when it refuses to print the
look-and-feel name.

One thing had to change in the runner for this to be honest. Ungated, the probe prints
`false` / `visible` on a machine with a display and `true` / `caught` on a CI runner, so it would
pass or fail on the host rather than on the bridge — the failure mode this whole item is about.
`run.py` now holds a `HEADLESS` set and launches only those probes with `-Djava.awt.headless=true`,
which makes the recorded expectation the same one in both places. The flags are passed to the
`oq build` command as well as to the run, because `oq build` executes the program to decide the
codepath; a build launched without them would record an output that never matched the run it is
compared against.

The pin was mutation-tested, because a conditional that silently never fires looks identical to
one that works. Replacing `HEADLESS = {"c13_window"}` with `HEADLESS = set()` made the harness
report

```
c13_window: expected ['true', 'caught', 'true', 'caught-again'], got ['false', 'visible', 'constructed-again'] (exit 0)
```

and exit **1** — which also demonstrates that this machine has a display, and that without the pin
the probe would have been testing the machine. Restored, the run returns to 27/27 and exit 0.
Five tests in `test_ci_probe_wiring.py` pin the flags onto the jar, classpath and compiled launch
forms and assert that an unlisted probe gets none.

The Swing path is now gated twice, and the two gates answer different questions. The probes harness
asks what the bridge did on the day it was measured; `conformance/257-swing-runs-with-no-display`
asks whether it still does, on both backends, in the ordinary suite. It compiles, so the corpus
moves to 215 of 224 executable programs, measured rather than assumed.

What remains genuinely open is narrower than it was, and it is worth stating precisely: no test in
this repository displays a window on a real screen, so painting *as composited by a window manager*
is unexercised. Everything up to that point — layout, painting into an image, input dispatch,
look-and-feel, and the failure a program sees when there is no display — is gated. Reaching the
last step turned out not to need a virtual framebuffer at all. `c13_display` asserts the
*relationship* rather than a host-specific value: `headless == not worked` and `worked or headless`
is `true` / `true` whether or not a display exists, so one recorded expectation is correct on a
macOS desktop and on a Linux runner alike. Verified in all four combinations of interpreter and
compiled, each with and without `-Djava.awt.headless=true`. What is still unexercised is how a
*window manager* composites the window — a property of the desktop, not of the bridge.

## Item 9j — §2.3: the completion gate for every capability row

The Alpha 7 order's completion checklist for the bridge asks for "executable evidence or an
explicit, accepted limitation" for **every** capability row. That is a per-row question, and no gate
answered it. `check-report-claims.py` re-derives the *figures* — 55 rows, 13 directions, six
defects — with `re.findall(r"^\| C\d+\.\d+ ", inventory, re.M)`, a shape match. A row can therefore
exist, be counted, and assert a result nothing executes. The count is the only thing checked, and
the count is not the claim a reader consults the table for.

**`scripts/check-bridge-coverage.py` is the per-row gate.** Each row's Evidence column now ends with
a marker naming the probe files that exercise it:

| C1.1 | Construct with `ClassName(args)` | Complete | `ArrayList()` → `[]` | `c1_construct` |

Six rules, each of which the tests break on purpose:

1. Every row carries a marker. A row without one is not evidence-backed, whatever its Evidence
   column argues.
2. Every named probe exists. A row citing a deleted probe is the drift this gate exists to catch.
3. Every named probe is registered in `run.py`'s `PROBES`. A file no harness runs is not executable
   evidence, however correct its contents.
4. Every registered probe is cited. An orphan is either a row that lost its marker or a probe no row
   claims; both mean the map and the harness diverged.
5. Every direction's rows reach at least one probe of that direction. This catches a marker copied
   from the row above, which satisfies 1–4 while proving nothing about the row it sits on. Cross-
   direction citation stays legal, because a constructor refusal is legitimately proven by whichever
   file exercises it — C1.3 and C1.4 are proven by `c4_conversion` and C3.4 by `c2_instance`.
6. A row classified `Missing`, `Incorrect` or `Unverified` cannot cite a probe whose expected output
   is a pass. Those classifications assert the capability is absent; a green probe beside one is a
   contradiction. `run.py` already distinguishes the two: a list expectation is a value, a string is
   a diagnostic, and a diagnostic is how a refusal is asserted.

The linkage is declared, not inferred. A probe named `c2_instance.oq` beside a row `C2.1` *looks*
like coverage, but the file exercises `size`, `isEmpty` and `contains` while the row might be about a
`Class` value. Direction-level naming says where a probe lives, not what it proves, so the marker is
a claim the document makes about itself and the gate checks the claim is still true.

**It found three real defects on its first run.** Two are pre-existing. `C13.7` and `C13.8` carried
only four cells: their argument was folded into the Class column and there was no Evidence column at
all, so those two rows had never been readable as rows. Restructured, both now parse. The third is a
hole §2.2 left: `c13_mapped` and `c13_display` — the display-backed probes it added — had no
capability row. `C13.9` records both, which is why the table carries 55 rows.

The gate then caught prose that §2.2 had invalidated. The Swing section's heading had claimed that
every row beneath it ran with `java.awt.headless=true`, which the display-backed probes make untrue,
and the section had closed by denying that any test opens a window, which `c13_display` does.
Both sentences are rewritten. The non-claim that Xvfb is not a window manager survives intact.

**Rule 6 is vacuous on the current tree, and the tests say so.** Every row is `Complete` since Alpha 6
fixed D1–D6, so no row asserts absence and rule 6 has nothing to bite on. The tests that exercise it
run against a small synthetic tree that is wrong in exactly one way. There is also a test asserting
the real inventory still has no absence classification — not a tautology, but a tripwire: if a future
defect reopens a `Missing` row, that test fails and the fixture's assumptions get revisited.

```
$ python3 scripts/check-bridge-coverage.py
Every one of 55 capability rows names executable evidence (68 row/probe citations, 29 registered probes).
exit=0
```

Nineteen tests in `scripts/tests/test_check_bridge_coverage.py`, and the gate runs in the same CI step
as the other eight. What this does **not** claim: that a passing probe is a correct probe. Every rule
here is about a row pointing at something that runs, and a probe can run, pass, and still measure the
wrong thing — Item 9i's mutation-testing of `c13_offscreen` is the discipline that answers that
question, and it is per-probe, not per-row. Nor does it claim every JDK class works: it claims every
row the inventory makes is backed by something executed, and the inventory's own boundaries are
unchanged.

## Item 10 — Remaining blockers and risks

- **The type-system conflict is resolved, in the opposite direction to §5.** ADR-0069 (Accepted,
  2026-11-18) supersedes ADR-0015's scope and the type-system brief: Oqthara stays dynamically
  typed and `data` fields stay untyped. This entry previously read "unresolved … until an ADR
  supersedes ADR-0015", which was true when the report was written and is not true now. §5 as
  ordered cannot start because the question it asks has been answered *against* it, so any typing
  work needs a further ADR superseding ADR-0069 — not merely one superseding ADR-0015. The two
  conformance cases keep saying what they say, and now they say it on a decided question rather
  than a pending one.
- **#251 was red on a required check**, and this entry used to say the next dependabot pull request
  would be red for the same reason. That prediction was already false when it was written and is
  now demonstrated false. `db01622` exempts `app/dependabot` by login, and
  `python3 scripts/check-ai-review.py --pr 251` exits 0 on the live API, naming `app/dependabot` as
  an automated author that cannot review its own change. Nothing *generates* a review record on the
  automated path — that part is still true — but nothing needs to, because the gate no longer asks
  a bot for one. The residual risk is narrower and is recorded: R-031, an allow-list that matches an
  external identifier can stop matching silently when the identifier changes, which is exactly how
  this gate failed the first time. See Item 9g.
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
- **§4's directive text was not in this repository, and now the scope is.** Item 6 reported
  "Unblocked" for three passes on the strength of the ownership adjustments, which were real but
  insufficient: items 7 and 8 both stated what their section ordered, while item 6 stated what it
  cleared and not what it authorized. The status was corrected to **Blocked as written** rather than
  left overstated, and the unblocking step was to obtain §4's text rather than make another
  adjustment. Alpha 7's revised execution order supplied that text, and **Item 6b** now records its
  scope, exclusions, ownership boundaries, and first deliverable here. Status is **Unblocked**; the
  outstanding work is the capability matrix, not a definition.
- **Swing has no example and no conformance case.** This entry used to add two claims that were
  wrong when written and are now demonstrated wrong. "Nothing in CI would notice a regression" was
  false: the probes runner is a CI step on the Ubuntu leg, and deleting either direction 13
  probe makes it exit non-zero on both backends. Deleting `c13_offscreen.oq` and re-running the
  harness gives `FAILED / c13_offscreen: missing probe file` and exit 1; restoring it gives
  `ok: 28 probes match their expectation` and exit 0. "What cannot be covered headlessly
  is still unmeasured" was the premise Item 9i overturned — only *realising* a window needs a
  display, and layout, painting, input dispatch and look-and-feel all run under
  `-Djava.awt.headless=true` and are gated by C13.7. What is genuinely absent is smaller than
  either claim: no `examples/*.oq` and no `conformance/*.oq` exercises Swing, so the bridge's Swing
  path is gated by a governance harness rather than by the ordinary test suite, and a reader
  looking for how to use it has nothing to read. The remaining unmeasurable is one thing: painting
  as composited by a real window manager, which needs a virtual framebuffer on the Linux runner.
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
- It does not claim the fuzz suite is trustworthy because it passes. It claims fourteen targets exist
  today, that the run at `d306833` covered eight of them and found seven defects, and that the gaps
  R-018 and `docs/spec/fuzzing.md` name by name are still gaps. It does not claim a count of "unreached
  builtins": any such number depends on calling a builtin "pure", and this item measured reachability
  by grep, which cannot make that judgement.
- It does not claim §5 is illegal. It claims §5 contradicts a *decided* question and therefore needs
  a superseding record, which the brief's own "one decision, not one migration" makes affordable.
- It does not claim `oq-hive-language-syntax` is unusable. It claimed it was not where `AGENTS.md`
  said agents live and that its keyword count was stale; both were corrected in #252 and #257.
- It does not claim Swing works. It claims six specific capabilities were executed under a headless
  JVM and matched their expectation, and that nothing tests layout, painting, input dispatch or
  look-and-feel.
