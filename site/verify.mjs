// Structural verification for the prerendered React site. Runs in CI before publishing.
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const site = join(here, "_site");
const problems = [];

if (!existsSync(site)) {
  console.error("site/_site does not exist; run `npm run build` in site/ first");
  process.exit(1);
}

const pageFiles = [];
function collectHtml(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "versions" || entry.name === "assets") continue;
      collectHtml(path);
    } else if (entry.name.endsWith(".html")) {
      pageFiles.push(relative(site, path));
    }
  }
}
collectHtml(site);

const EXPECTED = ["index.html", "tour.html", "docs.html", "examples.html", "stdlib.html", "status.html"];
for (const page of EXPECTED) {
  if (!pageFiles.includes(page)) problems.push(`missing page ${page}`);
}

// Every Markdown document under docs/spec and docs/adr must have a generated page.
const renderedDocs = [];
for (const dir of ["spec", "adr"]) {
  const absolute = join(root, "docs", dir);
  if (!existsSync(absolute)) continue;
  for (const name of readdirSync(absolute).sort()) {
    if (!name.endsWith(".md")) continue;
    const source = `docs/${dir}/${name}`;
    const page = `docs/${dir}/${name.replace(/\.md$/, ".html")}`;
    renderedDocs.push({ source, page });
    if (!pageFiles.includes(page)) problems.push(`missing documentation page ${page}`);
  }
}
if (!renderedDocs.length) problems.push("no documentation pages were discovered under docs/");

const ids = new Map();
for (const page of pageFiles) {
  const html = readFileSync(join(site, page), "utf8");
  const required = [
    ["<!DOCTYPE html>", "doctype"],
    ['lang="en"', "lang attribute"],
    ["<title>", "title"],
    ['id="main"', "main landmark"],
    ["skip-link", "skip link"],
    ['aria-label="Main"', "labelled navigation"],
    ["viewport", "viewport meta"],
    ["/oqthara/assets/", "base-path asset reference"],
  ];
  for (const [needle, label] of required) {
    if (!html.includes(needle)) problems.push(`${page}: missing ${label}`);
  }

  // The page must contain real prerendered content, not an empty React shell.
  if (!html.includes('<div id="root">')) problems.push(`${page}: missing root container`);
  const text = html.replace(/<script[\s\S]*?<\/script>/g, "").replace(/<[^>]+>/g, " ");
  if (text.trim().length < 400) {
    problems.push(`${page}: prerendered text is suspiciously short (${text.trim().length} chars)`);
  }

  ids.set(page, new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1])));

  for (const match of html.matchAll(/href="([^"]*)"/g)) {
    const target = match[1];
    if (target.startsWith("http") || target.startsWith("mailto")) continue;
    if (target.startsWith("#")) {
      if (!ids.get(page).has(target.slice(1))) {
        problems.push(`${page}: missing anchor target ${target}`);
      }
      continue;
    }
    if (!target.startsWith("/oqthara/")) {
      problems.push(`${page}: internal link without the Pages base path: ${target}`);
      continue;
    }
    const relativeTarget = target.slice("/oqthara/".length) || "index.html";
    const resolved = relativeTarget.endsWith("/") ? `${relativeTarget}index.html` : relativeTarget;
    if (!existsSync(join(site, resolved))) problems.push(`${page}: broken link ${target}`);
  }

  if (html.includes("${")) problems.push(`${page}: template literal leaked into output`);
  if (html.includes("__REFERENCE__") || html.includes("__FILE__")) {
    problems.push(`${page}: placeholder leaked into output`);
  }
  if (statSync(join(site, page)).size < 1200) problems.push(`${page}: suspiciously small page`);
}

// The docs index must link every rendered document, not a hand-picked subset.
const docsIndex = readFileSync(join(site, "docs.html"), "utf8");
for (const doc of renderedDocs) {
  const url = `/oqthara/${doc.page.replace(/\.html$/, ".html")}`;
  if (!docsIndex.includes(`href="${url}"`)) problems.push(`docs.html: does not list ${doc.source}`);
  const html = readFileSync(join(site, doc.page), "utf8");
  if (!html.includes('<article class="doc">')) problems.push(`${doc.page}: missing article`);
  if (!/<h1 id="[^"]+">/.test(html)) problems.push(`${doc.page}: missing anchored h1`);
}

// Markdown conversion must produce real structure, not escaped literal syntax.
const spec = readFileSync(join(site, "docs/spec/language.md".replace(/\.md$/, ".html")), "utf8");
for (const [needle, label] of [
  ["<table>", "table"],
  ["<pre><code", "fenced code"],
  ["<h2 id=", "headings"],
  ["<ul>", "list"],
  ["<a href=", "links"],
]) {
  if (!spec.includes(needle)) problems.push(`rendered specification: missing ${label}`);
}

// The embedded reference must actually be present, so /stdlib renders from generated data.
const stdlib = readFileSync(join(site, "stdlib.html"), "utf8");
if (!stdlib.includes('id="oq-reference"')) problems.push("stdlib.html: missing embedded reference");
if (!stdlib.includes("Global functions")) problems.push("stdlib.html: reference has no functions section");

// Documentation pages must embed the same documentation the client hydrates from.
const docsData = readFileSync(join(site, "docs/spec/design.html"), "utf8");
if (!docsData.includes('id="oq-docs"')) problems.push("docs pages: missing embedded documentation data");

const assets = join(site, "assets");
if (!existsSync(assets)) problems.push("missing assets/");
else {
  const files = readdirSync(assets);
  if (!files.some((file) => /^site\.css$/.test(file))) problems.push("missing unbundled site.css");
  if (!files.some((file) => /^style-[^/]+\.css$/.test(file))) problems.push("missing built stylesheet");
  if (!files.some((file) => /^index-[^/]+\.js$/.test(file))) problems.push("missing built script");

  const css = readFileSync(join(assets, "site.css"), "utf8");
  for (const token of ["--bg", "--fg", "--accent", "--radius", "prefers-color-scheme",
    "prefers-reduced-motion", ":focus-visible"]) {
    if (!css.includes(token)) problems.push(`site.css: missing ${token}`);
  }
  if (!css.includes("animation: none !important")) problems.push("site.css: reduced motion must disable animation");
  if (!css.includes("transition: none !important")) problems.push("site.css: reduced motion must disable transition");
}

if (!existsSync(join(site, "stdlib.md"))) problems.push("missing stdlib.md");

// Search is a static index generated during prerendering, so check that it exists, stays
// small, and only names pages and anchors that were actually written.
const indexPath = join(site, "search-index.json");
if (!existsSync(indexPath)) {
  problems.push("missing search-index.json");
} else {
  const raw = readFileSync(indexPath, "utf8");
  if (Buffer.byteLength(raw) > 64 * 1024) {
    problems.push(`search-index.json is too large (${Buffer.byteLength(raw)} bytes)`);
  }
  let entries = null;
  try {
    entries = JSON.parse(raw);
  } catch {
    problems.push("search-index.json is not valid JSON");
  }
  if (entries !== null) {
    if (!Array.isArray(entries) || entries.length === 0) {
      problems.push("search-index.json has no entries");
    } else {
      for (const entry of entries) {
        const url = typeof entry?.url === "string" ? entry.url : "";
        if (!url.startsWith("/oqthara/")) {
          problems.push(`search-index.json: ${url || "(missing url)"} must use the base path`);
          continue;
        }
        const [path, anchor] = url.split("#");
        const relative = path.slice("/oqthara/".length) || "index.html";
        const file = relative.endsWith("/") ? `${relative}index.html` : relative;
        if (!existsSync(join(site, file))) {
          problems.push(`search-index.json: ${url} does not resolve to a page`);
          continue;
        }
        if (anchor) {
          const target = readFileSync(join(site, file), "utf8");
          if (!target.includes(`id="${anchor}"`)) problems.push(`search-index.json: ${url} has no anchor`);
        }
      }
    }
  }
}

const home = readFileSync(join(site, "index.html"), "utf8");
for (const needle of ['role="search"', 'role="combobox"', 'aria-controls="site-search-results"']) {
  if (!home.includes(needle)) problems.push(`index.html: missing search markup ${needle}`);
}

if (problems.length) {
  console.error("Site verification failed:");
  for (const problem of problems) console.error("  -", problem);
  process.exit(1);
}
console.log(`Verified ${pageFiles.length} prerendered pages (${renderedDocs.length} from docs/), assets and stdlib reference.`);
