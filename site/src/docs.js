// Pure helpers for the documentation pipeline: which files are rendered, where their
// pages live, and how a link written for a Markdown file maps to a site URL.
//
// Nothing here reads the filesystem, so the same rules run in the prerenderer and in the
// tests. `docs/` stays the only source of truth; these helpers only name its pages.

import { BASE_PATH } from "./data/meta.js";

/** The documentation sections that become site pages, in menu order. */
export const DOC_SECTIONS = [
  {
    dir: "spec",
    title: "Specification",
    blurb: "The language, the compiler and the guarantees behind them.",
  },
  {
    dir: "adr",
    title: "Decision records",
    blurb: "Why the syntax, the backend and the site work the way they do.",
  },
];

const SITE_ROOT = BASE_PATH.replace(/\/$/, "");

/** `docs/spec/language.md` becomes `docs/spec/language.html`. */
export function slugForSource(source) {
  return String(source).replace(/\.md$/, ".html");
}

/** The site path for a source file, without the GitHub Pages base. */
export function routeForSource(source) {
  return `/${slugForSource(source)}`;
}

/** The section metadata for a source file, or a sensible fallback. */
export function sectionForSource(source) {
  const parts = String(source).split("/");
  return DOC_SECTIONS.find((section) => section.dir === parts[1]) ?? {
    dir: parts[1] ?? "",
    title: "Documentation",
    blurb: "",
  };
}

/** Normalises `a/../b` without depending on Node's path module. */
export function normalizePath(path) {
  const parts = [];
  for (const part of String(path).split("/")) {
    if (part === "" || part === ".") continue;
    if (part === "..") parts.pop();
    else parts.push(part);
  }
  return parts.join("/");
}

/**
 * Resolves a link from one documentation file.
 *
 * A relative `.md` link to another rendered document becomes that document's page. Links
 * outside the rendered set keep going to GitHub rather than becoming a 404. External URLs,
 * mail links and same-page anchors are returned unchanged.
 */
export function resolveDocLink(href, fromSource, sources) {
  if (!href) return href;
  if (/^(?:https?:|mailto:|tel:)/i.test(href)) return href;
  if (href.startsWith("#")) return href;

  const hashAt = href.indexOf("#");
  const target = hashAt === -1 ? href : href.slice(0, hashAt);
  const anchor = hashAt === -1 ? "" : href.slice(hashAt);
  if (!target) return href;

  const base = String(fromSource).split("/").slice(0, -1).join("/");
  const resolved = normalizePath(`${base}/${target}`);

  if (sources.includes(resolved)) {
    return `${SITE_ROOT}${routeForSource(resolved)}${anchor}`;
  }
  if (resolved.endsWith(".md")) {
    return `https://github.com/Oqthara/oqthara/blob/main/${resolved}${anchor}`;
  }
  return href;
}

/** The first substantial prose paragraph, for the index summary and page description. */
export function firstParagraph(blocks) {
  for (const block of blocks) {
    if (block.type === "paragraph" && block.text.length >= 40) return block.text;
    if (block.type === "quote") {
      const nested = firstParagraph(block.blocks);
      if (nested) return nested;
    }
  }
  return "";
}

/** Site routes for every rendered document. */
export function docsRoutes(docs) {
  return docs.map((doc) => ({
    path: routeForSource(doc.source),
    file: slugForSource(doc.source),
    doc,
  }));
}

/** Finds the document that owns a site path, tolerating a missing `.html`. */
export function docForPath(path, docs) {
  const cleaned = String(path).replace(/\/+$/, "");
  const withSuffix = cleaned.endsWith(".html") ? cleaned : `${cleaned}.html`;
  return docs.find((doc) => routeForSource(doc.source) === withSuffix) ?? null;
}

/** The public URL for a documentation page, including the Pages base path. */
export function docHref(source) {
  return `${SITE_ROOT}${routeForSource(source)}`;
}

export { SITE_ROOT };
