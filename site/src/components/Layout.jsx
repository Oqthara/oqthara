import { createElement as h } from "react";
import { BASE_PATH, NAV, VERSION } from "../data/meta.js";
import { Search } from "./Search.jsx";

/**
 * Page links are root-relative in the nav data so the routes read clearly. On GitHub Pages
 * the site lives under a subpath, so they are prefixed with Vite's base at render time.
 */
export function hrefFor(path) {
  if (path === "/") return BASE_PATH;
  return BASE_PATH.replace(/\/$/, "") + path;
}

/** Shared page chrome: skip link, header, nav, main landmark and footer. */
export function Layout({ current, children }) {
  return h(
    "div",
    { className: "site-root" },
    h("a", { className: "skip-link", href: "#main" }, "Skip to content"),
    h(
      "header",
      { className: "site" },
      h(
        "div",
        { className: "wrap" },
        h("a", { className: "brand", href: hrefFor("/") }, "oq", h("span", null, "thara")),
        h(
          "nav",
          { className: "site", "aria-label": "Main" },
          NAV.map((item) =>
            h(
              "a",
              {
                key: item.href,
                href: hrefFor(item.href),
                "aria-current": item.href === current ? "page" : undefined,
              },
              item.label,
            ),
          ),
        ),
        h(Search, null),
      ),
    ),
    h("main", { id: "main" }, h("div", { className: "wrap" }, children)),
    h(
      "footer",
      { className: "site" },
      h(
        "div",
        { className: "wrap" },
        h("span", null, `Oqthara ${VERSION} · MIT · built on the JVM`),
        h(
          "span",
          null,
          h("a", { href: "https://github.com/Oqthara/oqthara" }, "GitHub"),
          " · ",
          h("a", { href: "https://github.com/Oqthara/oqthara/releases" }, "Releases"),
        ),
      ),
    ),
  );
}

/** Consistent page header: eyebrow, headline and lead paragraph. */
export function PageHead({ eyebrow, title, lead }) {
  return h(
    "section",
    { className: "page-head" },
    eyebrow ? h("p", { className: "eyebrow" }, eyebrow) : null,
    h("h1", null, title),
    lead ? h("p", { className: "lead" }, lead) : null,
  );
}

export function Note({ children }) {
  return h("div", { className: "note" }, children);
}

export function Card({ title, children }) {
  return h("div", { className: "card" }, h("h3", null, title), h("p", null, children));
}

export function Grid({ children }) {
  return h("div", { className: "grid" }, children);
}

/** A two-column table. Rows are [left, right] pairs rendered as text or nodes. */
export function Table({ head, rows }) {
  return h(
    "table",
    null,
    h("thead", null, h("tr", null, head.map((cell, i) => h("th", { key: i }, cell)))),
    h(
      "tbody",
      null,
      rows.map((row, r) => h("tr", { key: r }, row.map((cell, c) => h("td", { key: c }, cell)))),
    ),
  );
}
