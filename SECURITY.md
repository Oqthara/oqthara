# Security policy

## Supported versions

Until 1.0, only the latest published release receives security fixes.

## Reporting a vulnerability

Do not open a public issue. Report privately with GitHub's "Report a vulnerability"
button on the repository's Security tab, or email the maintainers listed in the
repository profile.

Include:

- the Oqthara version (`oq --version`)
- the Java version
- a minimal `.oq` program that reproduces the issue
- what an attacker could do with it

You can expect an acknowledgement within 72 hours and a fix or mitigation plan within
14 days. Credit will be given in the release notes unless you ask otherwise.

## Scope

The interpreter, the parser, the standard library, and the release artifacts are in
scope. Programs written by users are not: Oqthara executes arbitrary code by design,
so a malicious `.oq` file is equivalent to a malicious jar.
