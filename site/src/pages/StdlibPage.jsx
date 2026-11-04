import { createElement as h } from "react";
import { hrefFor, PageHead } from "../components/Layout.jsx";

// The standard library page is generated from docs/stdlib/reference.md, which `oq doc`
// writes from the implementation. Parsing it here rather than rendering a hand-written
// copy means the site cannot advertise a function that does not exist.

function parseSections(markdown) {
  const sections = [];
  let current = null;
  for (const line of markdown.split("\n")) {
    if (line.startsWith("## ")) {
      current = { title: line.slice(3).trim(), lines: [] };
      sections.push(current);
      continue;
    }
    if (!current) continue;
    current.lines.push(line);
  }
  return sections;
}

function parseTable(lines) {
  const rows = lines.filter((line) => line.trim().startsWith("|"));
  if (rows.length < 2) return null;
  const cells = (row) =>
    row
      .trim()
      .replace(/^\||\|$/g, "")
      .split("|")
      .map((cell) => cell.trim());
  const head = cells(rows[0]);
  const body = rows.slice(2).map(cells); // row 1 is the --- separator
  return { head, body };
}

function renderInline(text) {
  // Only `code` spans appear in the generated reference.
  const parts = text.split(/(`[^`]*`)/g).filter((part) => part !== "");
  return parts.map((part, index) =>
    part.startsWith("`") && part.endsWith("`") && part.length > 1
      ? h("code", { key: index }, part.slice(1, -1))
      : part,
  );
}

export function StdlibPage({ reference }) {
  const sections = parseSections(reference);
  return h(
    "div",
    null,
    h(PageHead, {
      eyebrow: "Standard library",
      title: "Standard library",
      lead: "Generated from the implementation by oq doc. If a function is listed here, it exists; CI fails when this page is out of date.",
    }),

    sections.map((section) => {
      const prose = section.lines.filter((line) => line.trim() !== "" && !line.trim().startsWith("|"));
      const table = parseTable(section.lines);
      return h(
        "section",
        { key: section.title },
        h("h2", null, section.title),
        prose.map((line, index) => h("p", { key: index }, renderInline(line))),
        table
          ? h(
              "table",
              null,
              h("thead", null, h("tr", null, table.head.map((cell, i) => h("th", { key: i }, renderInline(cell))))),
              h(
                "tbody",
                null,
                table.body.map((row, r) =>
                  h("tr", { key: r }, row.map((cell, c) => h("td", { key: c }, renderInline(cell)))),
                ),
              ),
            )
          : null,
      );
    }),

    h("p", null, h("a", { href: hrefFor("/stdlib.md") }, "Open the raw generated reference →")),
  );
}
