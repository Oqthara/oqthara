import { createElement as h } from "react";
import { hrefFor, Note, PageHead, Table } from "../components/Layout.jsx";
import { Code } from "../components/Code.jsx";
import { VersionSelect } from "../components/VersionSelect.jsx";
import { DOC_SECTIONS, docHref, sectionForSource } from "../docs.js";

const COMMANDS = [
  ["oq file.oq", "Run a program"],
  ["oq run file.oq", "Same, explicit"],
  ["oq check file.oq", "Parse without running"],
  ["oq repl", "Interactive session"],
  ["oq fmt file.oq", "Canonical, idempotent formatting"],
  ["oq test [dir]", "Run test_*.oq and tests/"],
  ["oq new name", "Create a project"],
  ["oq build file.oq", "Produce an executable jar"],
  ["oq build file.oq --emit-ir", "Print the intermediate representation"],
  ["oq doc .", "Regenerate the stdlib reference"],
  ["oq version", "Show the version"],
  ["oq help", "Show the command list"],
];

/**
 * The documentation index. Every entry is read from `docs/` during the build, so a new
 * Markdown file appears here and gets its own page without anyone editing this component.
 */
function DocList({ docs }) {
  return h(
    "div",
    { className: "doc-index" },
    DOC_SECTIONS.map((section) => {
      const entries = docs.filter((doc) => sectionForSource(doc.source).dir === section.dir);
      if (!entries.length) return null;
      return h(
        "section",
        { key: section.dir },
        h("h2", null, section.title),
        section.blurb ? h("p", { className: "lead lead-tight" }, section.blurb) : null,
        h(
          "ul",
          { className: "doc-list" },
          entries.map((doc) =>
            h(
              "li",
              { key: doc.source },
              h("a", { href: docHref(doc.source) }, doc.title),
              doc.summary ? h("p", null, doc.summary) : null,
              h("code", null, doc.source),
            ),
          ),
        ),
      );
    }),
  );
}

export function DocsPage({ versions = [], latest = null, docs = [] }) {
  const rendered = docs.filter(
    (doc) => DOC_SECTIONS.some((section) => section.dir === sectionForSource(doc.source).dir),
  );
  return h(
    "div",
    null,
    h(PageHead, {
      eyebrow: "Documentation",
      title: "Documentation",
      lead: "Reference material for the language, the CLI, the compiler and the standard library. Every page is generated from the Markdown in this repository, so the site and the source cannot drift.",
    }),

    h(VersionSelect, { versions, latest }),

    h(DocList, { docs: rendered }),

    h(
      "p",
      null,
      "The ",
      h("a", { href: hrefFor("/stdlib.html") }, "standard library"),
      " and ",
      h("a", { href: hrefFor("/examples.html") }, "examples"),
      " pages are generated from the implementation and the example corpus.",
    ),

    h("h2", null, "The oq command"),
    h(Table, {
      head: ["Command", "What it does"],
      rows: COMMANDS.map(([command, what]) => [h("code", null, command), what]),
    }),

    h("h2", null, "Installation"),
    h(Code, null, `# macOS and Linux
curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash

# Windows PowerShell
irm https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.ps1 | iex

# Docker
docker run --rm -v "$PWD:/work" oqthara/oq:latest hello.oq`),

    h(
      Note,
      null,
      "A compiled jar uses the interpreter for most programs and emits real bytecode for ",
      h("code", null, "if"),
      ", loops, variables, arithmetic and ",
      h("code", null, "print"),
      ". This is stated plainly in the compiler documentation rather than implied to be more than it is.",
    ),
  );
}
