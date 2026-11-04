import { createElement as h } from "react";
import { hrefFor, Note, PageHead, Table } from "../components/Layout.jsx";
import { Code } from "../components/Code.jsx";
import { VersionSelect } from "../components/VersionSelect.jsx";

const CARDS = [
  {
    href: "https://github.com/Oqthara/oqthara/blob/main/docs/spec/language.md",
    title: "Language reference",
    body: "Every construct, operator, method and built-in.",
  },
  {
    href: hrefFor("/stdlib.html"),
    title: "Standard library",
    body: "Generated from the implementation, so it cannot drift.",
  },
  {
    href: "https://github.com/Oqthara/oqthara/blob/main/docs/spec/backend.md",
    title: "Compiler and JVM backend",
    body: "What compiles, what falls back, and why.",
  },
  {
    href: "https://github.com/Oqthara/oqthara/blob/main/docs/spec/fuzzing.md",
    title: "Fuzzing",
    body: "Jazzer targets, the regression corpus and findings.",
  },
  {
    href: "https://github.com/Oqthara/oqthara/tree/main/docs/adr",
    title: "Decision records",
    body: "Why the syntax, the CI split and the class-file writer look like this.",
  },
  {
    href: "https://github.com/Oqthara/oqthara/blob/main/CONTRIBUTING.md",
    title: "Contributing",
    body: "How to add a feature, a conformance case or a fuzz target.",
  },
];

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

export function DocsPage({ versions = [], latest = null }) {
  return h(
    "div",
    null,
    h(PageHead, {
      eyebrow: "Documentation",
      title: "Documentation",
      lead: "Reference material for the language, the CLI, the compiler and the standard library. Everything here describes what is implemented today.",
    }),

    h(
      "div",
      { className: "grid" },
      CARDS.map((card) =>
        h(
          "a",
          { key: card.title, className: "card card-link", href: card.href },
          h("h3", null, card.title),
          h("p", null, card.body),
        ),
      ),
    ),

    h(VersionSelect, { versions, latest }),

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
