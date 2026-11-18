#!/usr/bin/env python3
"""Regression tests for the agent-facing keyword counts in scripts/check-docs-sync.py.

R-023 recorded that `Lexer.KEYWORDS` held 22 entries while `AGENTS.md` and two
`oq-hive-*` agent files said 20. The gate cross-checked `docs/stdlib/reference.md`,
`CONTRIBUTING.md` and `docs/spec/design.md` -- the three copies nobody reads before
writing code -- and left the agent-facing three unread, so the drift survived
inside the documents whose whole purpose is to brief an agent.

These tests fail without the `agent_facing` block, and they cover both of its
failure modes because a check that has only ever passed cannot be told from one
that never runs:

  * a stated count that disagrees with the lexer (the drift that actually
    happened: 20 against 22);
  * a statement reworded or removed, which would otherwise drop the file out of
    coverage while every remaining check still passed -- the silent-omission
    failure that let the stale copy live this long.
"""
import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))

_spec = importlib.util.spec_from_file_location("check_docs_sync", SCRIPTS / "check-docs-sync.py")
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

AGENTS = ROOT / "AGENTS.md"
SYNTAX = ROOT / ".github" / "agents" / "oq-hive-language-syntax.agent.md"
ORCHESTRATOR = ROOT / ".github" / "agents" / "oq-hive-orchestrator.agent.md"


def keyword_problems():
    """Run the keyword check and return its problems.

    The check reports into shared module state, so it is cleared first. Only
    `keyword_budget_is_honest` is invoked, so every problem returned comes from it;
    filtering on a word here would have hidden the budget messages, which name a
    budget rather than a keyword.
    """
    checker.problems.clear()
    checker.checked.clear()
    checker.keyword_budget_is_honest()
    return list(checker.problems)


class TheRealTreeAgrees(unittest.TestCase):
    def test_it_passes(self):
        self.assertEqual([], keyword_problems())

    def test_it_states_how_many_files_it_read(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.keyword_budget_is_honest()
        self.assertTrue(
            any("agent-facing keyword counts match the lexer in 3 files" in item
                for item in checker.checked),
            "the check must say how many agent-facing files it read, so a file that "
            "drops out of coverage is visible rather than merely falling silent",
        )


class BadCountsAreRejected(unittest.TestCase):
    def setUp(self):
        self.originals = {path: path.read_text(encoding="utf-8")
                          for path in (AGENTS, SYNTAX, ORCHESTRATOR)}

    def tearDown(self):
        for path, text in self.originals.items():
            path.write_text(text, encoding="utf-8")

    def test_a_stale_count_in_agents_md_is_rejected(self):
        AGENTS.write_text(
            self.originals[AGENTS].replace("keyword (budget 30, now 22)",
                                           "keyword (budget 30, now 20)"),
            encoding="utf-8")
        self.assertTrue(
            any("AGENTS.md claims 20 keywords" in item for item in keyword_problems()),
            "the historical drift was 20 against 22 and must fail the gate",
        )

    def test_a_stale_count_in_the_language_syntax_agent_is_rejected(self):
        SYNTAX.write_text(
            self.originals[SYNTAX].replace("It currently uses 22.", "It currently uses 20."),
            encoding="utf-8")
        self.assertTrue(
            any("oq-hive-language-syntax.agent.md claims 20 keywords" in item
                for item in keyword_problems()),
            "the language agent is the copy handed the language-core workstream",
        )

    def test_a_stale_budget_is_rejected(self):
        ORCHESTRATOR.write_text(
            self.originals[ORCHESTRATOR].replace("keyword budget is 30.",
                                                 "keyword budget is 40."),
            encoding="utf-8")
        self.assertTrue(
            any("states a budget of 40" in item for item in keyword_problems()),
            "a budget that disagrees with the reference is the same defect as a count",
        )

    def test_a_reworded_statement_is_rejected_rather_than_ignored(self):
        ORCHESTRATOR.write_text(
            self.originals[ORCHESTRATOR].replace("It currently uses 22.", "It uses a few."),
            encoding="utf-8")
        self.assertTrue(
            any("does not state the keyword budget and count" in item
                for item in keyword_problems()),
            "a missing statement must be a problem, not a silent skip: the check only "
            "works if a file cannot quietly leave its own coverage",
        )


if __name__ == "__main__":
    unittest.main()
