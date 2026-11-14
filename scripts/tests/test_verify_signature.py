#!/usr/bin/env python3
"""Regression tests for scripts/verify-signature.sh.

The signature check is the one control that distinguishes tampering from corruption, so
these tests exist to prove the script actually fails when it should. Every case drives the
real script as a subprocess; `cosign` is replaced by a stub whose verdict the test
controls, so the suite runs anywhere without downloading a 130 MB binary.

The stub records its own argv, which is how the tests verify the *arguments* the script
builds rather than only its exit status: a verifier that passes the wrong key or the wrong
bundle would still exit 0 against a stub that always agrees.
"""
import os
import pathlib
import stat
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "verify-signature.sh"


def write_stub(directory, verdict="ok"):
    """Create a `cosign` stand-in that exits 0 for `ok` and 1 for `deny`."""
    path = directory / "cosign"
    path.write_text(
        "#!/usr/bin/env bash\n"
        'printf "%s\\n" "$*" >> "$(dirname "$0")/argv.log"\n'
        f'exit {"0" if verdict == "ok" else "1"}\n'
    )
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


class SignatureTest(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp(prefix="oq-sig-"))
        self.artifact = self.root / "oq.jar"
        self.artifact.write_bytes(b"release artifact")
        self.bundle = self.root / "oq.jar.sigstore.json"
        self.bundle.write_text("{}")
        self.key = self.root / "oq.pub"
        self.key.write_text("a public key")
        self.stub = write_stub(self.root)
        self.argv_log = self.root / "argv.log"
        self.env = {
            "PATH": os.environ.get("PATH", ""),
            "OQ_COSIGN_KEY": str(self.key),
            "OQ_COSIGN_BIN": str(self.stub),
        }

    def run_script(self, *args, env=None):
        return subprocess.run(
            [str(SCRIPT), *args],
            capture_output=True, text=True, env=env if env is not None else self.env)

    def recorded(self):
        if not self.argv_log.exists():
            return ""
        return self.argv_log.read_text()

    def test_a_matching_artifact_verifies(self):
        result = self.run_script(str(self.artifact), str(self.bundle))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("verified", result.stdout)

    def test_a_mismatched_artifact_fails(self):
        write_stub(self.root, verdict="deny")
        result = self.run_script(str(self.artifact), str(self.bundle))
        self.assertEqual(1, result.returncode,
                         "a failed verification must not exit 0")
        self.assertIn("FAILED", result.stderr)
        self.assertIn("Do not run it", result.stderr)

    def test_the_supplied_key_and_bundle_are_the_ones_used(self):
        self.run_script(str(self.artifact), str(self.bundle))
        argv = self.recorded()
        self.assertIn("--key", argv)
        self.assertIn(str(self.key), argv)
        self.assertIn(str(self.bundle), argv)
        self.assertIn(str(self.artifact), argv)
        self.assertIn("verify-blob", argv)

    def test_without_a_key_the_identity_path_is_used(self):
        env = dict(self.env)
        del env["OQ_COSIGN_KEY"]
        env["OQ_COSIGN_IDENTITY"] = "a-specific-identity"
        self.run_script(str(self.artifact), str(self.bundle), env=env)
        argv = self.recorded()
        self.assertIn("--certificate-identity-regexp", argv)
        self.assertIn("a-specific-identity", argv)
        self.assertIn("--certificate-oidc-issuer", argv)
        self.assertNotIn("--key", argv)

    def test_the_default_identity_is_a_regexp_that_matches_a_release_tag(self):
        env = dict(self.env)
        del env["OQ_COSIGN_KEY"]
        self.run_script(str(self.artifact), str(self.bundle), env=env)
        argv = self.recorded()
        self.assertIn("--certificate-identity-regexp", argv)
        self.assertNotIn("--certificate-identity ", argv)

    def test_the_default_identity_regexp_actually_matches_a_real_tag(self):
        # A pattern that is passed to cosign but matches nothing is the defect this
        # covers: a glob was passed as --certificate-identity, which cosign compares
        # literally, so every real release was rejected while the stub-based tests
        # stayed green because the stub ignores its arguments.
        env = dict(self.env)
        del env["OQ_COSIGN_KEY"]
        self.run_script(str(self.artifact), str(self.bundle), env=env)
        argv = self.recorded()
        pattern = argv.split("--certificate-identity-regexp", 1)[1].split()[0]
        identity = ("https://github.com/Oqthara/oqthara/.github/workflows/"
                    "release.yml@refs/tags/v0.1.0-alpha.4")
        # fullmatch, not search: a pattern matching only a prefix of the identity
        # would pass a search and still be wrong, because cosign requires the whole
        # certificate subject to match.
        self.assertIsNotNone(
            __import__("re").fullmatch(pattern, identity),
            f"the default identity must match a real release tag exactly; "
            f"pattern {pattern!r} does not cover {identity!r}")

    def test_an_exact_identity_is_passed_literally_when_asked(self):
        env = dict(self.env)
        del env["OQ_COSIGN_KEY"]
        env["OQ_COSIGN_IDENTITY"] = "a-litera[l]identity"
        env["OQ_COSIGN_IDENTITY_EXACT"] = "1"
        self.run_script(str(self.artifact), str(self.bundle), env=env)
        argv = self.recorded()
        self.assertIn("--certificate-identity", argv)
        self.assertNotIn("--certificate-identity-regexp", argv)

    def test_a_missing_cosign_is_an_error_not_a_pass(self):
        env = dict(self.env)
        env["OQ_COSIGN_BIN"] = str(self.root / "absent")
        result = self.run_script(str(self.artifact), str(self.bundle), env=env)
        self.assertEqual(2, result.returncode)
        self.assertIn("not installed", result.stderr)
        self.assertIn("detects corruption", result.stderr)

    def test_the_identity_path_fails_when_cosign_denies(self):
        write_stub(self.root, verdict="deny")
        env = dict(self.env)
        del env["OQ_COSIGN_KEY"]
        result = self.run_script(str(self.artifact), str(self.bundle), env=env)
        self.assertEqual(1, result.returncode,
                         "a failed identity verification must not exit 0")
        self.assertIn("FAILED", result.stderr)

    def test_a_missing_artifact_is_refused_before_cosign_runs(self):
        result = self.run_script(str(self.root / "absent.jar"), str(self.bundle))
        self.assertEqual(2, result.returncode)
        self.assertIn("artifact not found", result.stderr)
        self.assertNotIn("verify-blob", self.recorded())

    def test_a_missing_bundle_is_refused_before_cosign_runs(self):
        result = self.run_script(str(self.artifact), str(self.root / "absent.json"))
        self.assertEqual(2, result.returncode)
        self.assertIn("bundle not found", result.stderr)
        self.assertNotIn("verify-blob", self.recorded())

    def test_a_missing_key_file_is_refused(self):
        env = dict(self.env)
        env["OQ_COSIGN_KEY"] = str(self.root / "absent.pub")
        result = self.run_script(str(self.artifact), str(self.bundle), env=env)
        self.assertEqual(2, result.returncode)
        self.assertIn("not a file", result.stderr)

    def test_wrong_arity_prints_usage(self):
        result = self.run_script(str(self.artifact))
        self.assertEqual(2, result.returncode)
        self.assertIn("usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
