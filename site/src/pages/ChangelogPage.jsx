import { createElement as h } from "react";
import { hrefFor } from "../components/Layout.jsx";
import { CHANGELOG_SOURCE } from "../changelog.js";

// The changelog is rendered from the repository's CHANGELOG.md during the build and passed
// in as HTML. This page adds only its own chrome; the entries themselves have exactly one
// source, the Markdown file, so nothing here can drift from it.
//
// The Markdown already supplies the page's h1 ("Changelog"), so this component adds no
// second heading of its own.

export function ChangelogPage({ changes }) {
  return h(
    "div",
    null,
    h("p", { className: "eyebrow" }, "Project history"),
    changes?.html
      ? h("article", {
          className: "doc",
          dangerouslySetInnerHTML: { __html: changes.html },
        })
      : h("p", null, "The changelog is missing."),
    h(
      "p",
      { className: "doc-source" },
      "Source: ",
      h(
        "a",
        { href: `https://github.com/Oqthara/oqthara/blob/main/${CHANGELOG_SOURCE}` },
        CHANGELOG_SOURCE,
      ),
      " · ",
      h("a", { href: hrefFor("/status.html") }, "Status"),
      " · ",
      h("a", { href: "https://github.com/Oqthara/oqthara/releases" }, "Releases"),
    ),
  );
}
