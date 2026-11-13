"""The remap tool exists so a line-shifting edit does not become a prose edit.

The stdlib inventory cites registration lines, so inserting a helper into
`Stdlib.java` moves every citation below it. Fixing 49 citations by hand has
already gone wrong once in this repository: a first attempt rewrote them with a
bare `, `.join and silently dropped the separators between entries, turning
readable prose into `817`json`:819`. The document still parsed and the checker
still passed, because the checker reads numbers. These tests pin the property
that made that attempt unacceptable: the rewrite touches digits and nothing else.
"""

import importlib.util
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def load():
    spec = importlib.util.spec_from_file_location(
        "remap", ROOT / "scripts/remap-stdlib-citations.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RemapTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        (self.root / "docs/governance").mkdir(parents=True)
        self.stdlib = self.root / "oq-stdlib/src/main/java/dev/oqthara/stdlib"
        self.stdlib.mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def inventory(self, body):
        return self.write("docs/governance/alpha-4-stdlib-inventory.md",
                          "### The 3 names, by registration site\n\n" + body
                          + "\n### What the 3 do not include\n\nNothing.\n")

    def test_separators_survive_the_rewrite(self):
        self.write("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java",
                   "line\nline\n"
                   'interpreter.defineBuiltin(NativeFunction.of("alpha", 1, f));\n'
                   'interpreter.defineBuiltin(NativeFunction.of("beta", 1, f));\n')
        doc = self.inventory("`alpha`:1, `beta`:9.\n")
        subprocess.run([sys.executable, str(ROOT / "scripts/remap-stdlib-citations.py"),
                        str(self.root)], check=True, capture_output=True)
        self.assertIn("`alpha`:3, `beta`:4.", doc.read_text())

    def test_a_count_mismatch_is_refused_not_papered_over(self):
        # Three cited sites for a name registered once is a structural difference, not a
        # moved line: expressing it means adding a separator, and rewriting digits cannot.
        # The tool must leave the entry alone and report it, rather than scatter whatever
        # numbers remain and produce `alpha`:3, `beta`:4 where three sites were claimed.
        self.write("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java",
                   "a\nb\n"
                   'interpreter.defineBuiltin(NativeFunction.of("alpha", 1, f));\n')
        doc = self.inventory("`alpha`:1, 2 and 3.\n")
        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts/remap-stdlib-citations.py"), str(self.root)],
            capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("needs a human", result.stderr)
        self.assertIn("`alpha`:1, 2 and 3", doc.read_text())

    def test_only_digits_change(self):
        self.write("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java",
                   "a\nb\nc\n"
                   'interpreter.defineBuiltin(NativeFunction.of("alpha", 1, f));\n')
        doc = self.inventory("`alpha`:1.\n")
        before = doc.read_text()
        subprocess.run([sys.executable, str(ROOT / "scripts/remap-stdlib-citations.py"),
                        str(self.root)], check=True, capture_output=True)
        after = doc.read_text()
        self.assertEqual(re.sub(r"\d+", "#", before), re.sub(r"\d+", "#", after))

    def test_a_name_nothing_registers_is_left_alone(self):
        self.write("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java",
                   "a\n"
                   'interpreter.defineBuiltin(NativeFunction.of("alpha", 1, f));\n')
        doc = self.inventory("`alpha`:1, `absent`:7.\n")
        subprocess.run([sys.executable, str(ROOT / "scripts/remap-stdlib-citations.py"),
                        str(self.root)], check=True, capture_output=True)
        self.assertIn("`absent`:7", doc.read_text())

    def test_running_twice_is_a_no_op(self):
        self.write("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java",
                   "a\nb\n"
                   'interpreter.defineBuiltin(NativeFunction.of("alpha", 1, f));\n')
        doc = self.inventory("`alpha`:1.\n")
        for _ in range(2):
            subprocess.run([sys.executable, str(ROOT / "scripts/remap-stdlib-citations.py"),
                            str(self.root)], check=True, capture_output=True)
        self.assertIn("`alpha`:3", doc.read_text())


if __name__ == "__main__":
    unittest.main()
