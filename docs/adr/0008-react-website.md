# ADR-0008 — The website is a prerendered React application

- **Status:** accepted
- **Date:** 2026-11-04
- **Deciders:** oq-docs, oq-architect
- **Related:** ADR-0004, ADR-0009

## Context

The website began as `site/build.mjs`: one file that concatenated template literals into six
HTML files. It served its purpose and had real guarantees — link checking, HTML validation and
an HTTP smoke test after deployment — but it had two problems:

1. **Every page was a string.** Navigation, header, footer and page chrome were duplicated in
   each template, so changing the footer meant changing six literals. Content and presentation
   were inseparable, and the pages could not share behaviour.
2. **No component model.** The playground reimplemented copy logic inline, and a syntax
   highlighter could not exist because everything was a string.

The product also needs documentation that stays in sync with releases, which is easier to do
with structured content than with `const tour = \`...\``.

## Decision

Build the site as a **React application with Vite**, and **prerender every route to static
HTML** at build time.

- `site/src/App.jsx` owns the route table.
- `site/src/pages/*` holds one component per page.
- `site/src/components/*` holds shared chrome, the code block and the playground.
- `site/src/data/meta.js` holds navigation, page metadata and the version.
- `site/prerender.mjs` runs the same React tree through `react-dom/server` and writes one
  file per route into `site/_site/`, which is what GitHub Pages publishes.
- The browser **hydrates** that markup, so the playground is interactive while `curl` still
  receives the real page.

Routes keep the `.html` suffix (`/oqthara/tour.html`) because GitHub Pages has no rewrites and
a `/oqthara/tour` link would 404.

## Consequences

Positive:

- One layout component, so the header, footer and navigation cannot drift between pages.
- Every page works without JavaScript, which matters for documentation, for search engines
  and for anyone on a slow connection.
- Content lives in data structures and components, not in strings, so the stdlib page can be
  generated from `docs/stdlib/reference.md` and the tour can be a list of sections.
- Real tests are possible: `site/tests/*.test.js` asserts on the prerendered output.

Negative:

- The build needs Node and a lockfile, where before it needed only `node site/build.mjs`.
  The Gradle `SiteTest` still works without Node by reading sources and the output directory.
- Vite emits hashed asset names, so the post-deploy smoke test reads the stylesheet and script
  paths from the page instead of hard-coding them.
- Hydration requires the prerendered markup to match the client tree exactly. The build is
  deterministic and the tree has no time- or random-dependent content, so this holds.

## Alternatives rejected

**Client-only React.** Would ship an empty `<div id="root">` to anyone without JavaScript and
to crawlers. For a language documentation site that is unacceptable.

**Astro or Next.js static export.** Both are reasonable. Astro would be a good fit but adds a
second framework's concepts for six pages; Next.js brings a large runtime for the same output
Vite produces here. React plus a 40-line prerenderer is the smallest thing that satisfies
"the website must use React" without adopting a meta-framework the project does not otherwise
need.

**Keeping the string generator.** Cheapest today, but it already duplicated the chrome six
times and blocks every improvement the directive asks for.
