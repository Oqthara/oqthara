# 0011 — Extract the website into its own repository

Status: accepted (2026-11-05, OQ-ALPHA-CLOSEOUT)

## Context

The React website lived in `site/` and deployed from the language repository through
`.github/workflows/pages.yml`. Every documentation-only change that needed a site fix
meant touching the language build, and `oq-cli/src/test/java/dev/oqthara/cli/SiteTest.java`
reached across the module boundary to assert on website sources. Node tooling and a
`package-lock.json` also sat in a repository whose build is otherwise pure Gradle, and
`scripts/check-docs-sync.py` imported implementation truth from `site/src/pages/*.jsx`.

The lockfile, the React dependency tree and the prerenderer have nothing to do with the
language, its compiler or its conformance corpus.

## Decision

The website is its own public repository, `Oqthara/Oqthara.github.io`. Because the owning
account is `Oqthara` (a user, not an organisation), this is GitHub's user-site repository
and the site is served from the domain root, `https://oqthara.github.io/`, with Vite
`base: "/"` and no path prefix.

`site/`, `.github/workflows/pages.yml` and `SiteTest` are removed from the language
repository. The language repository no longer builds, tests or deploys the website, and its
CI no longer needs Node. The site repository has its own `pages.yml`, its own test suite and
its own deploy.

`docs/` stays here. It is the only authoritative documentation; the site repository does
not keep a copy of any specification, ADR or generated reference.

## Consequences

- A website change no longer touches the language build, and a language change no longer
  can break site deployment. Two independent pipelines, two independent red/green states.
- The documentation is no longer baked into the site at build time. Rendering it from
  GitHub at request time (ADR-0012) is what keeps one source of truth now that the site
  cannot read `../docs`.
- Historical ADR-0008 still describes the structure as it was when the site lived in
  `site/`. It is a record of that decision, not a description of today's tree, and is left
  unchanged.
- The former project path `https://oqthara.github.io/oqthara/` stops serving the site. The
  root now serves it; links were updated in the README and the site itself.
