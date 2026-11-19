#!/usr/bin/env python3
"""Checks that the Alpha 7 report's quantitative claims still match the tree.

Four consecutive review records — PR-260, PR-261, PR-262, PR-263 — found the same
defect: a sentence in `docs/governance/alpha-7-first-report.md` that was true when
written and had quietly stopped being true. Each pass repaired the sentences by
hand. Nothing re-derived them, so the next pass found new ones. This is the
mechanism those records asked for and none of them added.

What is checked, and why each item is here:

1. Every `<number> <noun>` claim this script can derive — probes, capability rows,
   directions, keywords, fuzz targets, ADRs, confirmed defects. The number is
   *extracted from the sentence*, never listed here, and compared against a value
   *derived from the file that owns it*. A registry of expected totals would be a
   second copy to go stale, which is the very defect being fixed.
2. Every `Cn–Cm` probe-coverage range: the upper bound must equal the highest
   construct group the harness actually probes. PR-263 found a stale `C1–C8`
   claim that a grep for the number 19 had missed, because the stale thing was
   the range, not the count.
3. Every risk-register status figure, in the Item 2 table and in the "N Open
   entries are R-…" sentence. `check-risk-register.py` enforces the exposure
   arithmetic but never reads the status column, so the register could hold
   6 Open / 12 Mitigated / 13 Accepted while a report table said anything at all.
   Exposure buckets ("Critical 1, High 4, …") are deliberately not re-derived
   here: that gate already does it, and a second reading of one figure is the
   fault this file exists to prevent.
4. That no claim-shaped sentence goes unclassified. A sentence pairing a count
   with a governance noun that no rule above recognises is a failure, not a pass.
   A gate that silently ignores what it cannot parse reports success for exactly
   the defect it was built to catch — finding 5 of PR-263, finding 4 of PR-260.

Three ways a figure may legitimately differ from today's tree, all real in the
record, are encoded rather than special-cased:

- **Dated snapshot.** The sentence says "when this table was written", "at the
  time", "was", or carries a backticked commit. A historical figure is not drift;
  §9c quotes `19` on purpose as what a command printed then.
- **Quoted material.** Text inside backticks or double quotes is quoted from
  another document or from a tool's output, not asserted by the report.
  `docs/stdlib/reference.md` "says 22 keywords" is a claim about that file; the
  gate message ``claims 19 keywords`` illustrates bad input.
- **Pairing form.** "covered C1–C8 in 19 … and covers C1–C12 in 24" states both
  sides of a change. Its later half must be right — that is PR-263 finding 5 —
  but the earlier half is not required to equal today's count.

Sentences, not lines, are the unit. Markdown wraps a paragraph wherever it likes,
and the dating clause frequently lands on the following line: "the probe harness
covered C1–C8" / "when this section was written". A line-at-a-time reader calls
that drift; a paragraph-at-a-time reader calls it what it is.

Exit codes: 0 every claim agrees, 1 a claim disagrees or could not be classified,
2 the report or an authority file could not be read.
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

REPORT = Path("docs/governance/alpha-7-first-report.md")
INVENTORY = Path("docs/governance/alpha-5-java-bridge-capability-inventory.md")
LEXER = Path("oq-core/src/main/java/dev/oqthara/core/Lexer.java")
BUILD = Path("build.gradle.kts")
RISK_REGISTER = Path("docs/governance/alpha-4-risk-register.md")
ADR_DIR = Path("docs/adr")
PROBE_DIR = Path("docs/governance/alpha-5/java-bridge-probes")
PROBE_HARNESS = PROBE_DIR / "run.py"
RISK_GATE = Path("scripts/check-risk-register.py")
# The twelve refusals are a claim about a file this gate did not previously
# read. §4's scope record now states the figure, so it is derived rather than
# trusted: mutating "twelve" to "thirteen" used to pass every gate.
REFUSAL_MATRIX = Path("docs/governance/alpha-6-requirement-matrix.md")
# The capability matrix is §4's first deliverable and states its own arithmetic.
# Hand-counting it produced three different wrong totals in one sitting, which is
# exactly the argument for deriving the verdicts from the rows instead.
CAPABILITY_MATRIX = Path("docs/governance/alpha-7-language-core-capability-matrix.md")
# Every verdict the matrix is allowed to reach. Recounting only the verdicts that
# happen to appear leaves a counter unguarded the moment one drops to zero: with
# "| Missing | 0 |" a stray "| Missing | 1 |" passed, because there was no row to
# iterate over. The set is closed so a zeroed verdict is still reconciled.
VERDICTS = (
    "Complete",
    "Missing",
    "Undocumented",
    "Ambiguous",
    "Intentionally refused",
    "Defective",
)

# Prose spells small counts out. Leaving these unreadable is how "thirteen
# directions" survived four reviews while every numeral in the file was checked.
_UNITS = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
_TENS = "twenty thirty forty fifty sixty seventy eighty ninety".split()
NUMWORDS = {word: index for index, word in enumerate(_UNITS, start=1)}
NUMWORDS.update({word: 10 * (index + 2) for index, word in enumerate(_TENS)})
NUMWORD = "|".join(list(NUMWORDS) + [rf"(?:{'|'.join(_TENS)})-(?:{'|'.join(_UNITS[:9])})"])
NUMBER = rf"(?:\d+|{NUMWORD})"

# "Written then" rather than "true now". These are the phrasings the report uses.
#
# Only phrases that date the figure they sit beside belong here. A bare "was",
# "were" or "had" dates nothing: "these were all still true when this pass ran"
# is a present-tense assertion about claims that must still be checked, and
# treating it as history exempted three live figures. "still true when" is out
# for the same reason, and so is "as of", which marks a figure as current.
DATED = re.compile(
    r"\bwas written\b|\bthis pass ran\b|\bas written\b"
    r"|\bat the time\b|\bat that point\b"
    r"|\bused to\b|\bpreviously\b|\bhad been\b"
    r"|\bonce (?:claimed|said|read|reported|stated|counted)\b",
    re.IGNORECASE,
)
# Read from the raw sentence: masking blanks backticked text to spaces, so a
# pattern looking for a quoted SHA never fires on the masked copy.
BACKTICK_SHA = re.compile(r"`[0-9a-f]{7,40}`")

# Nouns whose bare phrase names the whole population, so a figure attached to one
# is a total that must equal today's count. Longer phrases first: "capability
# rows" must not be read as "rows".
#
# "probes" and "defects" are deliberately absent. In this report both are always
# scoped — "the five `d1_*` probes", "eight targets found seven real defects" —
# and their totals are stated with a coverage range instead ("covers C1–C12 in
# 24"), which RANGE_PROBES reads. Treating the bare noun as a total would report
# a correctly scoped subset as drift.
NOUNS = [
    ("capability rows", "capability_rows"),
    ("probe files", "probe_files"),
    ("confirmed defects", "defects"),
    ("fuzz targets", "fuzz_targets"),
    # The fuzz totals are written bare: "8 targets" (line 77), "eight targets"
    # (line 115), "eight targets exist" (line 664). Only the quoted "added three
    # targets" at line 118 is a delta rather than a total, and it is dated.
    ("targets", "fuzz_targets"),
    ("directions", "directions"),
    ("keywords", "keywords"),
    ("ADRs", "adrs"),
    # The risk total is stated bare as well as in the Item 2 table. Without this
    # a sentence saying "the register holds 30 risks" passed unread, and the
    # register holds 31.
    ("risks", "risk_total"),
    # Bare, in §4's scope record: "the twelve ADR-0062 refusals remain
    # refusals". The figure was unchecked while the scope record was being
    # written, which is the failure the report charges elsewhere. The phrase
    # always reads "<number> ADR-0062 refusals", so the figure reaches the noun
    # through an optional ident, never through the number inside the id.
    ("refusals", "refusals"),
]
# Keyed lowercase because the lookup lowercases the matched text. Keying this on
# the noun as written made "ADRs" unreachable and turned any live "69 ADRs" into
# a KeyError, which no test caught while that phrase only appeared inside a fenced
# block.
NOUN_KEY = {noun.lower(): key for noun, key in NOUNS}
FIGURE = re.compile(
    rf"\b({NUMBER})\s+(?:(?:pure|real|confirmed|live)\s+)?"
    rf"(?:ADR-\d+\s+)?"
    rf"({'|'.join(re.escape(noun) for noun, _ in NOUNS)})\b",
    re.IGNORECASE,
)

BACKTICK = re.compile(r"`[^`]*`")
DOUBLE_QUOTED = re.compile(r'"[^"]*"')
EMPHASIS = re.compile(r"\*{1,3}")

STATUS_TABLE_ROW = re.compile(r"^\|\s*(Open|Mitigated|Accepted)\s*\|\s*\*{0,2}(\d+)\*{0,2}\s*\|")
STATUS_TABLE_TOTAL = re.compile(r"^\|\s*\*\*Total\*\*\s*\|\s*\*{0,2}(\d+)\*{0,2}\s*\|")
OPEN_IDS_SENTENCE = re.compile(
    rf"\b({NUMBER})\s+(?:\*\*)?Open entries are(?:\*\*)?\s+"
    r"((?:\*{0,2}R-\d{3}\*{0,2}(?:,\s*|\s+and\s+)?)+)"
)
COVERAGE = re.compile(r"\bC(\d+)\s*[–-]\s*C(\d+)\b")
# "covers C1–C12 in 24" and "C1–C8 with 19 probes": a number after a coverage
# range is the harness total, so it is checked even though a bare "N probes" is a
# scoped subset this script must not read as a total.
# The unit is often implicit: line 356 says "covers C1–C12 in 24" and line 543
# says the same, with no "probes" anywhere near it, because the surrounding
# paragraph has already established what is being counted.
RANGE_PROBES = re.compile(
    rf"\bC\d+\s*[–-]\s*C\d+\s+(?:in|with)\s+(?:\*\*)?({NUMBER})(?:\*\*)?",
    re.IGNORECASE,
)
# "the five `d1_*` probes" counts the probes in one defect group, and the group
# glob names its own authority: the files on disk. Read from the raw sentence,
# because masking removes the backticked glob that says which group is meant.
SCOPED_PROBES = re.compile(rf"\b({NUMBER})\s+`d(\d)_\*`\s+probes\b")
DEFECT_RANGE = re.compile(r"\bD1\s*[–-]\s*D(\d+)\b")

# A currency verb introduces a figure whose unit has been dropped: "so the
# command prints 26", "re-running it gives `13`, `52`, `26`, `69`". Every rule
# above reads a figure through the noun attached to it, and this shape attaches
# none, so no rule can fire and no rule reports the miss either — CLAIM_SHAPE
# needs a governance noun near the number and there is none. Item 9c carried two
# stale figures in exactly this shape through the review that was auditing
# figures; the sentence had been repaired in the commit that re-staled it.
#
# The gate must not guess which total is meant. The quantities it can derive —
# probes, capability rows, directions, ADRs — coincide at 28, 55, 13 and 69
# today, and they did not coincide last week. Reading one positionally as
# another would produce a confident wrong answer, so the shape is refused and
# the prose has to name what it counted. Positional matching was the first draft
# and it is the reason this comment exists.
#
# Digits only, never a spelled-out number: "it gives five reasons" is ordinary
# English, while "prints 26" is a claim about a total. Read on the raw sentence,
# because masking blanks a backticked figure's digits along with its backticks.
CURRENCY_VERB = r"(?:prints|printed|gives|reports|returns|answers|yields|reads)"
_NUMERAL = r"\d+(?:\.\d+)?(?:\s*/\s*\d+)?"
_QUOTED_NUMERAL = rf"(?:`\s*)?{_NUMERAL}(?:\s*`)?"
# `%` is a unit that happens to be a symbol. Reading "gives 92.3%" as a figure
# that dropped its unit would flag a sentence that is already unambiguous.
_UNIT_SYMBOL = r"(?:%|\u2030)"
CURRENCY_FIGURE = re.compile(
    rf"\b{CURRENCY_VERB}\b\s+(?:out\s+|back\s+)?"
    rf"({_QUOTED_NUMERAL}(?:\s*,\s*{_QUOTED_NUMERAL})*)"
    # Refuse a partial numeral ("reads 22 words" must not match "2"), then refuse
    # a trailing word: a figure followed by its unit is fine, and only a figure
    # that ends the clause has dropped one.
    rf"(?!\d)(?!\.\d)(?!\s*{_UNIT_SYMBOL})(?!\s*[A-Za-z])",
    re.IGNORECASE,
)
# Printed in the failure message so the repair is mechanical rather than a guess.
CLAIM_SHAPE_HINT = ", ".join(noun for noun, _ in NOUNS)

# A sentence states a before and an after only when it says so. "**thirteen**
# directions, **52** capability rows, and **five** confirmed defects" carries
# three figures and no change at all, so treating any multi-figure sentence as a
# pairing would switch off the very checks this gate exists to run — which is
# exactly what an early draft of it did.
#
# A bare "now" does not mark a pairing. "Measured now: 13 directions, 55
# capability rows, 27 probe files, 69 ADRs" is an ordinary list of four current
# totals, and the word dates none of them — but reading it as a pairing checked
# only the last figure and passed the other three unread, which is how a stale
# "27 probe files" survived the pass that added the 28th probe. Only "took
# ... from ... to" and the other phrasings below actually name a change.
PAIRED = re.compile(
    r"\bno longer\b|\binstead\b|\bas of\b|\btook\b|\bfrom\b[^.]{0,20}\bto\b"
    r"|\band covers\b|\bsuperseded\b|\bstill true when\b|\bpreviously\b",
    re.IGNORECASE,
)

# A count beside a governance noun that no rule above can read. Both orders are
# matched: "41 capability rows" is the usual prose form, but a table, a summary
# line or a heading may write "capability rows: 41", and reading only the first
# order let that shape through unclassified.
CLAIM_SHAPE = re.compile(
    rf"\b(?:{NUMBER})\b[^.;]{{0,12}}?\b(?:"
    + "|".join(re.escape(noun) for noun, _ in NOUNS)
    + r"|probes|defects)\b"
    + r"|\b(?:"
    + "|".join(re.escape(noun) for noun, _ in NOUNS)
    + rf"|probes|defects)\b\s*:\s*\*{{0,2}}(?:{NUMBER})\b",
    re.IGNORECASE,
)

# Every shape a figure can appear in, used only to find where the last one in a
# sentence ends: a dating phrase after that point governs the whole sentence.
CLAIM_SPAN = re.compile(
    "|".join(
        pattern.pattern if hasattr(pattern, "pattern") else pattern
        for pattern in (FIGURE, COVERAGE, RANGE_PROBES, DEFECT_RANGE, SCOPED_PROBES)
    ),
    re.IGNORECASE,
)

# Counts that are real claims but have no authority file to re-derive them from.
# Each is listed with its reason, so "checked and found exempt" is visible here
# rather than indistinguishable from "never looked at".
EXEMPT = [    (
        re.compile(rf"\b{NUMBER}\s+(?:real\s+)?defects\b(?!.{0,24}(?:D1|confirmed))"),
        "fuzz findings: what a fuzz run turned up is a past event with no file in the tree",
    ),
]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def number(token: str) -> int:
    """A numeral or a spelled-out count, as an int."""
    token = token.strip().lower()
    if token.isdigit():
        return int(token)
    if token in NUMWORDS:
        return NUMWORDS[token]
    tens, _, units = token.partition("-")
    return NUMWORDS[tens] + NUMWORDS[units]


def derive(cwd: Path = Path(".")) -> dict:
    """Every figure the report might state, read from the file that owns it."""
    values = {}

    harness = _load("oq_probe_harness", cwd / PROBE_HARNESS)
    # The default run's total, which is what an unqualified "N probes" describes. Probes in
    # `DISPLAY` run only under `--under-display` and are excluded here for the same reason the
    # harness excludes them: a figure quoted without a mode has to count the run a reader gets
    # by default. `--under-display` adds them, and that figure is stated with the mode named.
    values["probes"] = sum(1 for n, _, _ in harness.PROBES if n not in harness.DISPLAY)
    # A second, different quantity that prose states as "probes": the files on
    # disk. They agree at 28 today and did not two commits ago, so the two are
    # kept apart rather than folded together — a rule that swaps them is a rule
    # that reports the right number for the wrong reason.
    values["probe_files"] = len(list((cwd / PROBE_DIR).glob("*.oq")))

    inventory = (cwd / INVENTORY).read_text(encoding="utf-8")
    values["capability_rows"] = len(re.findall(r"^\| C\d+\.\d+ ", inventory, re.M))
    values["directions"] = len(re.findall(r"^#{2,3} Direction", inventory, re.M))
    values["defects"] = len(re.findall(r"^#{3,4} D\d+ ", inventory, re.M))

    # The highest construct group the harness covers, taken from the probe files
    # themselves rather than run.py's registry: a registry entry pointing at a
    # deleted file would let a range describe coverage that cannot execute.
    groups = {
        int(match.group(1))
        for path in (cwd / PROBE_DIR).glob("c*.oq")
        for match in [re.match(r"c(\d+)_", path.name)]
        if match
    }
    values["probed_groups"] = max(groups) if groups else 0
    values["probes_in_group"] = {
        digit: len(list((cwd / PROBE_DIR).glob(f"d{digit}_*.oq"))) for digit in "123456789"
    }

    lexer = (cwd / LEXER).read_text(encoding="utf-8")
    words = re.search(r"KEYWORDS = Set\.of\((.*?)\);", lexer, re.S)
    values["keywords"] = len(re.findall(r'"[a-z]+"', words.group(1))) if words else -1

    build = (cwd / BUILD).read_text(encoding="utf-8")
    targets = re.search(r"val allFuzzTargets = listOf\((.*?)\n\)", build, re.S)
    values["fuzz_targets"] = len(re.findall(r"FuzzTarget\(", targets.group(1))) if targets else -1

    values["adrs"] = len(list((cwd / ADR_DIR).glob("*.md")))

    # The status column is the risk gate's own parser, reused so one reading of
    # the register serves both gates instead of two that could diverge.
    register = _load("oq_risk_gate", cwd / RISK_GATE)
    rows = register.parse_rows((cwd / RISK_REGISTER).read_text(encoding="utf-8"))
    values["risk_total"] = len(rows)
    for name in ("Open", "Mitigated", "Accepted"):
        values[name] = sum(1 for row in rows if row["status"] == name)
    values["open_ids"] = sorted(row["id"] for row in rows if row["status"] == "Open")

    # Refusal rows in the Alpha 6 matrix, counted the same way the matrix counts
    # them: a `NOT STARTED` status cell. The status legend lines are prose, not
    # table rows, so anchoring on a leading pipe keeps them out.
    matrix = (cwd / REFUSAL_MATRIX).read_text(encoding="utf-8")
    values["refusals"] = len(re.findall(r"^\|.*?\|\s*`NOT STARTED`\s*\|", matrix, re.M))
    return values


def paragraphs(text: str) -> list[tuple[int, str, bool]]:
    """Logical units of the report: `(starting line, text, is fenced)`.

    Consecutive prose lines join into one paragraph, because Markdown wraps a
    sentence wherever it likes. Headings, table rows and list items stand alone.
    Fenced blocks are returned flagged, never silently dropped.
    """
    out: list[tuple[int, str, bool]] = []
    buffered: list[str] = []
    start = 0
    fenced = False

    def flush() -> None:
        nonlocal buffered
        if buffered:
            out.append((start, " ".join(buffered), fenced))
            buffered = []

    for index, line in enumerate(text.splitlines(), start=1):
        if line.startswith("```"):
            flush()
            fenced = not fenced
            out.append((index, line, True))
            continue
        standalone = fenced or not line.strip() or line.lstrip()[:2] in ("# ", "##", "| ", "> ", "- ", "* ")
        if standalone:
            flush()
            if line.strip():
                out.append((index, line, fenced))
            continue
        if not buffered:
            start = index
        buffered.append(line.strip())
    flush()
    return out


def sentences(paragraph: str) -> list[str]:
    """Split a paragraph into sentences, keeping terminal punctuation attached."""
    return [part for part in re.split(r"(?<=[.:;])\s+(?=[A-Z*`\"(])", paragraph) if part.strip()]


def mask(sentence: str) -> str:
    """Blank out quoted material and emphasis, leaving every offset in place.

    Quoted text belongs to another document or to a tool message. A backticked
    `C1–C8` is a quote of a program's output; "22 keywords" is quoted from
    `docs/stdlib/reference.md`. Neither is the report asserting a fact about the
    tree. Emphasis is blanked because `**52** capability rows` is a figure and
    only a formatting choice stops it looking like one.

    Every replacement keeps the original length. Offsets are then the same in
    both copies, which is what lets a figure found in the masked text be located
    and read again in the raw one.
    """
    for pattern in (BACKTICK, DOUBLE_QUOTED, EMPHASIS):
        sentence = pattern.sub(lambda match: " " * len(match.group()), sentence)
    return sentence


CLAUSE_BREAK = re.compile(r"[;,]|\band\b")


def clause(text: str, start: int, end: int) -> str:
    """The clause of `text` holding this span.

    Dating is a property of the clause a figure sits in, not of its sentence.
    Line 356 reads "covered C1–C8 in 19 probes when this table was written, and
    covers C1–C12 in 24 as of the probe-coverage pass": exempting the whole
    sentence exempted the current half too, and a probe total of 24 could be
    mutated to 19 with the gate silent.
    """
    left = 0
    for match in CLAUSE_BREAK.finditer(text, 0, start):
        left = match.end()
    right = len(text)
    match = CLAUSE_BREAK.search(text, end)
    if match:
        right = match.start()
    return text[left:right]


def is_dated(raw: str, masked: str, start: int, end: int, first: int, final: int) -> bool:
    """Whether the claim at `start:end` describes then rather than now.

    A dating phrase in the sentence's frame — before the first figure or after
    the last — governs every figure in it: "R-018 is accurate as written, and its
    own justification stands: eight targets found seven real defects" frames the
    whole sentence as history, and so does the trailing "were all still true when
    this pass ran" on line 528.

    A phrase *between* figures governs only its own clause, which is what line
    356 needs: "covered C1–C8 in 19 probes when this table was written, and
    covers C1–C12 in 24 as of the probe-coverage pass" dates the first half and
    the first half alone. Exempting the whole sentence let a probe total of 24 be
    mutated to 19 with the gate silent.

    Read on the masked copy for the time phrases and on the raw one for a quoted
    SHA, since masking blanks backticked text to spaces.
    """
    for match in DATED.finditer(masked):
        if match.end() <= first or match.start() >= final:
            return True
        if clause(masked, start, end) == clause(masked, *match.span()):
            return True
    return bool(BACKTICK_SHA.search(clause(raw, start, end)))


def classify(raw: str, sentence: str, line: int, values: dict) -> list[str]:
    """Problems a sentence carries.

    `raw` is the sentence with its quotes intact, for the checks that need to see
    a backticked qualifier; `sentence` is masked, for the ones comparing figures.
    Both copies are the same length, so a match found in one can be located in
    the other.
    """
    problems: list[str] = []
    claimed = False
    paired = bool(PAIRED.search(sentence))

    spans = [match.span() for match in CLAIM_SPAN.finditer(sentence)]
    first = min((start for start, _ in spans), default=0)
    final = max((end for _, end in spans), default=0)

    def dated(start: int, end: int) -> bool:
        return is_dated(raw, sentence, start, end, first, final)

    figures = list(FIGURE.finditer(sentence))
    for index, match in enumerate(figures):
        claimed = True
        # Only a sentence that says a change happened may state a before figure,
        # and then only in its earlier position. A sentence listing several
        # unrelated counts is not a pairing and every figure in it is checked.
        if paired and index != len(figures) - 1:
            continue
        if dated(*match.span()):
            continue
        stated, noun = match.group(1), match.group(2)
        value = values[NOUN_KEY[noun.lower()]]
        if number(stated) != value:
            problems.append(f"line {line}: says {stated} {noun} but the tree has {value}")

    ranges = list(COVERAGE.finditer(sentence))
    for index, match in enumerate(ranges):
        claimed = True
        if paired and index != len(ranges) - 1 and not dated(*match.span()):
            continue
        if dated(*match.span()):
            continue
        upper = int(match.group(2))
        if upper != values["probed_groups"]:
            problems.append(
                f"line {line}: claims coverage to C{upper} but the harness probes"
                f" to C{values['probed_groups']}"
            )

    # A total stated after a coverage range, checked per clause: the historical
    # half of line 356 pairs an old range with an old total, and only the current
    # half states a figure that must still hold.
    for match in RANGE_PROBES.finditer(sentence):
        claimed = True
        if dated(*match.span()):
            continue
        stated = number(match.group(1))
        if stated != values["probes"]:
            problems.append(
                f"line {line}: says {stated} probes but the harness runs {values['probes']}"
            )

    # A count scoped to one defect group, checked against that group's files.
    for match in SCOPED_PROBES.finditer(raw):
        claimed = True
        if dated(*match.span()):
            continue
        actual = values["probes_in_group"][match.group(2)]
        if number(match.group(1)) != actual:
            problems.append(
                f"line {line}: says {match.group(1)} d{match.group(2)} probes"
                f" but there are {actual}"
            )

    for match in DEFECT_RANGE.finditer(sentence):
        claimed = True
        if dated(*match.span()):
            continue
        stated = int(match.group(1))
        if stated != values["defects"]:
            problems.append(
                f"line {line}: says D1–D{stated} but the inventory documents"
                f" {values['defects']} defects"
            )

    # A figure with no unit word, attached to a verb that presents it as the
    # command's current answer. Nothing above can read it, so refuse it rather
    # than let it pass unread; dating it is still the way to quote an old total.
    # Read from the raw sentence, because the digits sit inside backticks.
    for match in CURRENCY_FIGURE.finditer(raw):
        if dated(*match.span()):
            continue
        problems.append(
            f"line {line}: a figure with no unit word after \"{match.group(0)}\" —"
            f" name what was counted ({CLAIM_SHAPE_HINT})"
        )

    # The count and the ID list must agree with each other and with the register:
    # a right count naming the wrong entries is a claim no arithmetic check in
    # the risk gate would notice.
    match = OPEN_IDS_SENTENCE.search(sentence)
    if match:
        claimed = True
        stated = number(match.group(1))
        named = sorted(re.findall(r"R-\d{3}", match.group(2)))
        if stated != len(named):
            problems.append(f"line {line}: says {stated} Open entries but names {len(named)}")
        if stated != values["Open"]:
            problems.append(
                f"line {line}: says {stated} Open entries but the register holds {values['Open']}"
            )
        elif named != values["open_ids"]:
            problems.append(
                f"line {line}: names {', '.join(named)} but the Open entries are"
                f" {', '.join(values['open_ids'])}"
            )

    if not claimed and any(pattern.search(sentence) for pattern, _ in EXEMPT):
        return problems

    # A count beside a governance noun that no rule above read. Failing here is
    # the point: four rounds of drift survived four reviews by being unreadable.
    if not claimed and CLAIM_SHAPE.search(sentence):
        problems.append(
            f"line {line}: a figure this script cannot classify: {sentence.strip()[:90]}"
        )

    return problems


# A figure recorded in the report is only worth gating if the section holding it
# still exists. Deleting Item 6b outright passed every gate: the refusals figure
# and the §4 status both vanished and nothing noticed, because every rule below
# searches for what is present and none requires anything to be.
REQUIRED_SECTIONS = [
    # (heading, phrases the section must still state)
    (
        "## Item 6b — §4's scope, recorded",
        [
            # The in-scope surface, sampled at both ends: a section that loses
            # its first and last bullets is a section that was rewritten, not
            # edited.
            "Operator precedence and expression evaluation.",
            "Interpreter and compiled-backend agreement for supported language behavior.",
            # The out-of-scope boundary. These are the rows a future pass is most
            # likely to delete while "tidying" a table, and deleting one silently
            # widens §4.
            "| Java Bridge Full |",
            "| Generics as a distinct pre-0.2.0 deliverable |",
            "| Grafeno and OQSS |",
            # The refusal rule, which is the one boundary that protects decisions
            # already made.
            "The twelve ADR-0062 refusals remain refusals",
            # The ownership table, so a scope record cannot outlive its owner.
            "`oq-hive-language-syntax` | Language design, syntax decisions",
            # The first deliverable, which is what makes the scope actionable.
            "A language-core capability matrix, before broad implementation.",
        ],
    ),
]


def check_required_sections(text: str) -> list[str]:
    """Problems where a section that must exist is missing or hollowed out."""
    problems: list[str] = []
    for heading, phrases in REQUIRED_SECTIONS:
        start = text.find(heading)
        if start < 0:
            problems.append(f"the report no longer has a {heading!r} section")
            continue
        nxt = text.find("\n## ", start + len(heading))
        section = text[start : nxt if nxt >= 0 else len(text)]
        for phrase in phrases:
            if phrase not in section:
                problems.append(
                    f"{heading!r} no longer states {phrase!r}; a scope record that"
                    " drops a boundary is worse than none"
                )
    return problems


def check_capability_matrix(text: str | None = None) -> list[str]:
    """Problems where the matrix's stated arithmetic disagrees with its own rows.

    The verdict counts are derived from the table, never from the prose. Three
    hand-counts of the same document gave 29, 49 and 50 before this existed.
    """
    problems: list[str] = []
    if text is None:
        try:
            text = CAPABILITY_MATRIX.read_text(encoding="utf-8")
        except OSError as unreadable:
            return [f"cannot read {CAPABILITY_MATRIX}: {unreadable}"]

    verdicts: dict[str, int] = {}
    total = 0
    for line in text.splitlines():
        if not re.match(r"^\| L\d", line):
            continue
        cells = [cell.strip() for cell in line.split("|")[1:-1]]
        if len(cells) < 3:
            continue
        total += 1
        verdict = cells[2].replace("*", "").strip()
        verdicts[verdict] = verdicts.get(verdict, 0) + 1
    total = total

    if total == 0:
        return [f"{CAPABILITY_MATRIX} has no capability rows; the matrix was emptied"]

    stated_total = re.search(r"^(\d+) rows, counted from the table IDs", text, re.M)
    if not stated_total:
        problems.append("the matrix no longer states its row total")
    elif int(stated_total.group(1)) != total:
        problems.append(
            f"the matrix says {stated_total.group(1)} rows but its table holds {total}"
        )

    unknown = sorted(set(verdicts) - set(VERDICTS))
    for verdict in unknown:
        problems.append(
            f"the matrix uses the verdict {verdict!r}, which is not one of the six it defines"
        )

    # Iterate the closed set, not the observed verdicts, so a verdict that fell to
    # zero still has its counter read. Otherwise "| Missing | 0 |" could be edited
    # to any number and nothing would notice.
    for verdict in VERDICTS:
        count = verdicts.get(verdict, 0)
        stated = re.search(rf"^\| {verdict} \| (\d+) \|", text, re.M)
        if not stated:
            problems.append(f"the matrix lists {count} {verdict!r} rows but does not count them")
        elif int(stated.group(1)) != count:
            problems.append(
                f"the matrix says {stated.group(1)} {verdict!r} rows but the table holds {count}"
            )

    summed = sum(verdicts.values())
    if summed != total:
        problems.append(f"the verdicts sum to {summed} but the table holds {total} rows")

    # The closing paragraph restates every verdict in prose. Restating a number in
    # two places is how this document was wrong three times, so both are checked.
    # Each clause is read on its own: the verb varies ("two are ambiguous" but "one
    # is undocumented"), and a single pattern across all six silently matched
    # nothing when it required "are" everywhere. Reading them from the closing
    # paragraph only, because "L3.11 is missing" earlier in the document matches the
    # Missing clause and reads "L3" as the count.
    words = {
        "zero": 0, "none": 0, "no": 0, "one": 1, "two": 2, "three": 3, "four": 4,
        "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    }
    anchor = text.find("rows behave as")
    if anchor >= 0:
        # Back up over "**N " so the Complete clause can read its own total.
        line_start = text.rfind("\n", 0, anchor)
        anchor = line_start + 1 if line_start >= 0 else 0
    closing = text[anchor : text.find("\n## ", anchor)] if anchor >= 0 else ""
    clauses = [
        (r"\*\*(\d+) rows behave as", "Complete"),
        (r"(\w+)\s+(?:are|is)\s+ambiguous", "Ambiguous"),
        (r"(\w+)\s+(?:are|is)\s+undocumented", "Undocumented"),
        (r"(\w+)\s+(?:are|is)\s+missing", "Missing"),
        (r"(\w+)\s+(?:are|is)\s+defective", "Defective"),
        (r"(\w+)\s+(?:are|is)\s+refused deliberately", "Intentionally refused"),
    ]
    if not closing:
        problems.append("the matrix's closing paragraph no longer states the row total")
    else:
        for pattern, verdict in clauses:
            found = re.search(pattern, closing)
            if not found:
                problems.append(
                    f"the matrix's closing paragraph no longer states how many"
                    f" {verdict!r} rows there are"
                )
                continue
            raw = found.group(1)
            claimed = int(raw) if raw.isdigit() else words.get(raw, -1)
            if claimed != verdicts.get(verdict, 0):
                problems.append(
                    f"the matrix's closing paragraph says {claimed} {verdict!r}"
                    f" rows but the table holds {verdicts.get(verdict, 0)}"
                )

    return problems


def check(text: str, values: dict) -> list[str]:
    """Every figure problem in the report; empty means every claim agrees.

    Structure is checked separately by `check_required_sections`, because a
    synthetic one-line text is a legitimate input to this function and is not
    a report.
    """
    problems: list[str] = []
    for line, unit, fenced in paragraphs(text):
        if fenced:
            continue
        if unit.lstrip().startswith("|"):
            match = STATUS_TABLE_ROW.match(unit)
            if match and int(match.group(2)) != values[match.group(1)]:
                problems.append(
                    f"line {line}: table says {match.group(1)} {match.group(2)} but the"
                    f" register holds {values[match.group(1)]}"
                )
            match = STATUS_TABLE_TOTAL.match(unit)
            if match and int(match.group(1)) != values["risk_total"]:
                problems.append(
                    f"line {line}: table says {match.group(1)} rows but the register holds"
                    f" {values['risk_total']}"
                )
            # A status cell is not the only kind of claim a table row can carry:
            # line 356 states a coverage range and a probe total in its evidence
            # column. Skipping the row after reading its status once let a probe
            # total of 24 be mutated to 19 without a word.
        for sentence in sentences(unit):
            problems.extend(classify(sentence, mask(sentence), line, values))
    return problems


def main() -> int:
    try:
        text = REPORT.read_text(encoding="utf-8")
        values = derive()
    except OSError as unreadable:
        print(f"cannot read a file the check needs: {unreadable}", file=sys.stderr)
        return 2

    problems = check_required_sections(text) + check(text, values) + check_capability_matrix()
    if problems:
        print(f"report claims: {len(problems)} problem(s)")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(
        f"report claims: every figure agrees — {values['probes']} probes,"
        f" {values['probe_files']} probe files,"
        f" {values['capability_rows']} capability rows, {values['directions']} directions,"
        f" {values['keywords']} keywords, {values['fuzz_targets']} fuzz targets,"
        f" {values['adrs']} ADRs, {values['defects']} defects, {values['risk_total']} risks"
        f" ({values['Open']} Open)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
