import { createElement as h } from "react";
import { hrefFor } from "../components/Layout.jsx";
import { VersionSelect } from "../components/VersionSelect.jsx";

// One rendered Markdown document. The HTML is produced at build time from the file in
// `docs/` and embedded in the page, so this component never parses Markdown and the page
// works without JavaScript.

export function DocPage({ doc, versions = [], latest = null }) {
  const toc = (doc.headings ?? []).filter((heading) => heading.level > 1 && heading.level <= 3);
  return h(
    "div",
    null,
    h(
      "p",
      { className: "eyebrow" },
      h("a", { href: hrefFor("/docs.html") }, "Documentation"),
    ),
    h(VersionSelect, { versions, latest }),
    toc.length
      ? h(
          "nav",
          { className: "toc", "aria-label": "On this page" },
          h("strong", null, "On this page"),
          h(
            "ul",
            null,
            toc.map((heading) =>
              h(
                "li",
                { key: heading.id, className: `toc-level-${heading.level}` },
                h("a", { href: `#${heading.id}` }, heading.text),
              ),
            ),
          ),
        )
      : null,
    h("article", {
      className: "doc",
      dangerouslySetInnerHTML: { __html: doc.html },
    }),
    h(
      "p",
      { className: "doc-source" },
      "Source: ",
      h(
        "a",
        { href: `https://github.com/Oqthara/oqthara/blob/main/${doc.source}` },
        doc.source,
      ),
      " · ",
      h("a", { href: hrefFor("/docs.html") }, "All documentation"),
    ),
  );
}
