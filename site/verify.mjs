// Structural verification for the prerendered React site. Runs in CI before publishing.
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const site = join(here, "_site");
const problems = [];

if (!existsSync(site)) {
  console.error("site/_site does not exist; run `npm run build` in site/ first");
  process.exit(1);
}

const pages = readdirSync(site).filter((file) => file.endsWith(".html"));
const EXPECTED = ["index.html", "tour.html", "docs.html", "examples.html", "stdlib.html", "status.html"];
for (const page of EXPECTED) {
  if (!pages.includes(page)) problems.push(`missing page ${page}`);
}

for (const page of pages) {
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

  for (const match of html.matchAll(/href="([^"]*)"/g)) {
    const target = match[1];
    if (target.startsWith("http") || target.startsWith("mailto") || target.startsWith("#")) continue;
    if (!target.startsWith("/oqthara/")) {
      problems.push(`${page}: internal link without the Pages base path: ${target}`);
      continue;
    }
    const relative = target.slice("/oqthara/".length) || "index.html";
    const resolved = relative.endsWith("/") ? `${relative}index.html` : relative;
    if (relative.endsWith(".md")) {
      if (!existsSync(join(site, relative))) problems.push(`${page}: missing ${target}`);
      continue;
    }
    if (!existsSync(join(site, resolved))) problems.push(`${page}: broken link ${target}`);
  }

  if (html.includes("${")) problems.push(`${page}: template literal leaked into output`);
  if (html.includes("__REFERENCE__") || html.includes("__FILE__")) {
    problems.push(`${page}: placeholder leaked into output`);
  }
  if (statSync(join(site, page)).size < 1200) problems.push(`${page}: suspiciously small page`);
}

// The embedded reference must actually be present, so /stdlib renders from generated data.
const stdlib = readFileSync(join(site, "stdlib.html"), "utf8");
if (!stdlib.includes('id="oq-reference"')) problems.push("stdlib.html: missing embedded reference");
if (!stdlib.includes("Global functions")) problems.push("stdlib.html: reference has no functions section");

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

if (problems.length) {
  console.error("Site verification failed:");
  for (const problem of problems) console.error("  -", problem);
  process.exit(1);
}
console.log(`Verified ${pages.length} prerendered pages, assets and stdlib reference.`);
