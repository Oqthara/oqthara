// Builds the static search index from the prerendered HTML.
//
// The index is derived from the pages the site actually wrote, never from a separate list
// of links, so every result points at a route that exists. The parser is deliberately
// small: extract `<main>`, split on `<h2>` headings, keep a fixed amount of plain text.
// That makes the output deterministic and keeps the shipped index under a few kilobytes.

import { BASE_PATH } from "./data/meta.js";

export const MAX_ENTRIES = 96;
export const MAX_TEXT = 220;

const NAMED = {
  amp: "&",
  apos: "'",
  gt: ">",
  hellip: "…",
  ldquo: "“",
  lsquo: "‘",
  lt: "<",
  mdash: "—",
  nbsp: " ",
  ndash: "–",
  quot: '"',
  rdquo: "”",
  rsquo: "’",
  rarr: "→",
};

/** Decodes the entities React emits in static markup, leaving unknown ones untouched. */
export function decodeEntities(value) {
  return String(value ?? "").replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, (match, name) => {
    if (name[0] === "#") {
      const code = name[1].toLowerCase() === "x"
        ? Number.parseInt(name.slice(2), 16)
        : Number.parseInt(name.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff
        ? String.fromCodePoint(code)
        : match;
    }
    return NAMED[name.toLowerCase()] ?? match;
  });
}

/** Turns a fragment of HTML into one line of text. Scripts and styles never contribute. */
export function htmlToText(value) {
  return decodeEntities(
    String(value ?? "")
      .replace(/<script\b[\s\S]*?<\/script>/gi, " ")
      .replace(/<style\b[\s\S]*?<\/style>/gi, " ")
      .replace(/<[^>]*>/g, " "),
  )
    .replace(/\s+/g, " ")
    .trim();
}

/** The route a generated file is served from, including the GitHub Pages base path. */
export function routeForFile(file) {
  const base = BASE_PATH.replace(/\/$/, "");
  return file === "index.html" ? `${base}/` : `${base}/${file}`;
}

function mainContent(html) {
  const match = /<main\b[^>]*>([\s\S]*?)<\/main>/i.exec(html);
  return match ? match[1] : "";
}

/** Collapses the spacing that block elements leave around punctuation in plain text. */
export function tidy(text) {
  return String(text ?? "")
    .replace(/\(\s+/g, "(")
    .replace(/\s+\)/g, ")")
    .replace(/\s+([,.;:!?])/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

function pageTitle(html, main) {
  const heading = /<h1\b[^>]*>([\s\S]*?)<\/h1>/i.exec(main);
  if (heading) return tidy(htmlToText(heading[1]));
  const title = /<title\b[^>]*>([\s\S]*?)<\/title>/i.exec(html);
  return title ? tidy(htmlToText(title[1]).replace(/\s+—\s+Oqthara$/, "")) : "";
}

function anchorOf(attributes) {
  const match = /\bid="([^"]+)"/.exec(attributes);
  if (!match) return "";
  const id = decodeEntities(match[1]);
  return /^[A-Za-z0-9_-]+$/.test(id) ? id : "";
}

function clip(text) {
  if (text.length <= MAX_TEXT) return text;
  return `${text.slice(0, MAX_TEXT - 3).trimEnd()}...`;
}

/**
 * Builds the index from `{ file, html }` pairs, in the order given. Text before the first
 * `<h2>` becomes the page entry; every heading becomes an entry anchored to its id.
 */
export function buildSearchIndex(pages) {
  const entries = [];
  for (const page of Array.isArray(pages) ? pages : []) {
    const file = typeof page?.file === "string" ? page.file : "";
    if (!/^[A-Za-z0-9_.-]+\.html$/.test(file)) continue;

    const html = String(page.html ?? "");
    const main = mainContent(html);
    if (!main) continue;

    const title = pageTitle(html, main);
    if (!title) continue;

    const url = routeForFile(file);
    const headings = [...main.matchAll(/<h2\b([^>]*)>([\s\S]*?)<\/h2>/gi)];
    const introEnd = headings.length ? headings[0].index : main.length;
    entries.push({ title, page: title, url, text: clip(tidy(htmlToText(main.slice(0, introEnd)))) });

    headings.forEach((match, index) => {
      const heading = tidy(htmlToText(match[2]));
      if (!heading) return;
      const start = match.index + match[0].length;
      const end = index + 1 < headings.length ? headings[index + 1].index : main.length;
      const anchor = anchorOf(match[1]);
      entries.push({
        title: heading,
        page: title,
        url: anchor ? `${url}#${anchor}` : url,
        text: clip(tidy(htmlToText(main.slice(start, end)))),
      });
    });

    if (entries.length >= MAX_ENTRIES) break;
  }
  return entries.slice(0, MAX_ENTRIES);
}
