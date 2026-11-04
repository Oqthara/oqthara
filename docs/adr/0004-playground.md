# ADR-0004 — Browser playground runs nothing (for now)

- **Status:** accepted
- **Date:** 2026-11-04
- **Deciders:** oq-docs, oq-architect

## Context

The roadmap asks for an interactive playground and suggests evaluating CheerpJ or TeaVM.
Both are real options, but each has a cost that is hard to justify before 1.0:

| Option | What it buys | What it costs |
|---|---|---|
| CheerpJ | runs the existing jar in the browser | a ~5-10 MB proprietary-licensed loader, plus a CDN dependency and a licence review |
| TeaVM | compiles the interpreter to WebAssembly | a second build toolchain, fragile reflection (the Java bridge uses `Class.forName`), and a separate test matrix |
| Nothing | a page that shows real programs and tells the truth | visitors cannot run code in the browser |

Oqthara's differentiator is the language and the error messages, not a browser runtime. A
playground that silently fails, or that is a second-class backend, damages the product more
than a missing playground does.

## Decision

Ship a **copy-and-run playground**: a page with real, selectable, editable programs and a
one-click copy button. The page states explicitly that execution needs the JVM and gives
the install command. No code is executed in the browser.

Groundwork that makes a real playground cheap later:

- programs live in `site/playground.js` as plain strings, so a future backend only has to
  replace the "run" action;
- the CLI stays a single jar with no native dependencies, which is exactly what CheerpJ
  needs;
- the site has no build step, so adding a bundle later does not restructure anything.

## Consequences

- The Pages deployment stays fast and has no third-party scripts.
- Evaluating CheerpJ or TeaVM is a backlog item with a clear trigger: after 1.0, or
  earlier if a sponsor needs in-browser execution.
- The site must not claim interactivity it does not have. The text says "copy it and run
  it locally" and the install command is directly above the playground.

## Revisit when

1. there is a stable 1.0 with a frozen language surface, and
2. a user asks for in-browser execution, or a contributor offers to own the WASM build.
