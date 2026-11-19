#!/usr/bin/env python3
"""Regression tests for the editor-grammar keyword check in scripts/check-docs-sync.py.

`check-docs-sync.py` reconciled `Lexer.KEYWORDS` against five places -- the
reference document, `CONTRIBUTING.md`, `docs/spec/design.md` and two agent files
-- and read neither the TextMate grammar that ships in the editor extension nor
the LSP. A keyword added to the lexer therefore failed two checks and reached
the editor silently: highlighting would call it an identifier in every installed
extension, and CI would be green.

The grammar is a JSON file with its keywords split across three repository
groups, so the check has more ways to fail quietly than the prose copies do:

  * a keyword missing from the grammar, which is the drift this exists to catch;
  * a word highlighted that the lexer does not treat as a keyword, which is the
    mirror image and makes the grammar claim a construct that does not exist;
  * a whole group deleted, which would otherwise shrink the comparison set until
    it agrees with whatever is left -- the silent-omission failure that let the
    agent-facing copies drift for two releases.

Each test mutates the real grammar and restores it, because the check reads the
file rather than taking its text as an argument. `tearDown` restores from a
captured original even when an assertion fails partway through.
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

GRAMMAR = ROOT / "editors" / "vscode" / "syntaxes" / "oq.tmLanguage.json"


def grammar_problems():
    """Run the grammar check and return its problems.

    The check reports into shared module state, so it is cleared first. Only
    `the_editor_grammar_knows_every_keyword` is invoked, so every problem
    returned comes from it.
    """
    checker.problems.clear()
    checker.checked.clear()
    checker.the_editor_grammar_knows_every_keyword()
    return list(checker.problems)


class TheRealGrammarAgrees(unittest.TestCase):
    def test_it_passes(self):
        self.assertEqual([], grammar_problems())

    def test_it_states_that_every_keyword_is_highlighted(self):
        checker.problems.clear()
        checker.checked.clear()
        checker.the_editor_grammar_knows_every_keyword()
        self.assertTrue(
            any("oq.tmLanguage.json highlights all" in item for item in checker.checked),
            "the check must say the grammar is covered, so a check that returned early "
            "is distinguishable from one that compared every keyword",
        )

    def test_the_check_is_in_the_check_list(self):
        # A function that nothing calls is a comment. The list is what main() runs.
        self.assertIn(
            checker.the_editor_grammar_knows_every_keyword,
            checker.CHECKS,
            "the grammar check must be reachable from main()",
        )


class AStaleGrammarIsRejected(unittest.TestCase):
    def setUp(self):
        self.original = GRAMMAR.read_text(encoding="utf-8")

    def tearDown(self):
        GRAMMAR.write_text(self.original, encoding="utf-8")

    def test_a_keyword_missing_from_the_grammar_is_rejected(self):
        # `const` is the newest control keyword and therefore the one a stale
        # grammar is most likely to lack.
        GRAMMAR.write_text(
            self.original.replace("|const|finally)", "|finally)", 1), encoding="utf-8")
        self.assertTrue(
            any("does not highlight ['const']" in item for item in grammar_problems()),
            "a keyword the lexer knows and the grammar does not must fail the gate",
        )

    def test_a_word_the_lexer_does_not_treat_as_a_keyword_is_rejected(self):
        # `match` is a legal name (spec:1260), so highlighting it as a keyword
        # would miscolour every variable called `match`.
        GRAMMAR.write_text(
            self.original.replace("|const|finally)", "|const|finally|match)", 1),
            encoding="utf-8")
        self.assertTrue(
            any("highlights ['match']" in item for item in grammar_problems()),
            "the grammar claiming a construct that does not exist is the mirror defect",
        )

    def test_a_deleted_group_is_rejected_rather_than_narrowing_the_check(self):
        GRAMMAR.write_text(
            self.original.replace('"constants": {', '"constants_removed": {', 1),
            encoding="utf-8")
        self.assertTrue(
            any("has no 'constants' group" in item for item in grammar_problems()),
            "a missing group must fail, not shrink the comparison set",
        )

    def test_an_unreadable_grammar_is_rejected(self):
        GRAMMAR.write_text("{ not json", encoding="utf-8")
        self.assertTrue(
            any("cannot read" in item for item in grammar_problems()),
            "a grammar that cannot be parsed must fail loudly, never pass silently",
        )


if __name__ == "__main__":
    unittest.main()
