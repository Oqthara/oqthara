// Structural verification for the generated site. Runs in CI before publishing.
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const site = join(here, "_site");
const problems = [];

if (!existsSync(site)) {
  console.error("site/_site does not exist; run node site/build.mjs first");
  process.exit(1);
}

const pages = readdirSync(site).filter((f) => f.endsWith(".html"));
if (pages.length < 6) problems.push(`expected at least 6 pages, found ${pages.length}`);

for (const page of pages) {
  const html = readFileSync(join(site, page), "utf8");
  const required = [
    ["<!DOCTYPE html>", "doctype"],
    ['lang="en"', "lang attribute"],
    ["<title>", "title"],
    ['id="main"', "main landmark"],
    ["assets/site.css", "stylesheet"],
    ["skip-link", "skip link"],
    ['aria-label="Main"', "labelled navigation"],
    ["viewport", "viewport meta"],
  ];
  for (const [needle, label] of required) {
    if (!html.includes(needle)) problems.push(`${page}: missing ${label}`);
  }
  for (const match of html.matchAll(/href="([^"#][^"]*)"/g)) {
    const target = match[1];
    if (target.startsWith("http") || target.startsWith("mailto")) continue;
    if (target.endsWith(".md")) {
      if (!existsSync(join(site, target))) problems.push(`${page}: missing ${target}`);
      continue;
    }
    if (!existsSync(join(site, target))) problems.push(`${page}: broken link ${target}`);
  }
  // Unclosed placeholder or template leakage would ship as literal braces.
  if (html.includes("${")) problems.push(`${page}: template literal leaked into output`);
  if (statSync(join(site, page)).size < 1200) problems.push(`${page}: suspiciously small page`);
}

const css = join(site, "assets", "site.css");
if (!existsSync(css)) problems.push("missing assets/site.css");
else {
  const text = readFileSync(css, "utf8");
  for (const token of ["--bg", "--fg", "--accent", "--radius", "prefers-color-scheme",
    "prefers-reduced-motion", ":focus-visible"]) {
    if (!text.includes(token)) problems.push(`site.css: missing ${token}`);
  }
}

if (!existsSync(join(site, "stdlib.md"))) problems.push("missing stdlib.md");

if (problems.length) {
  console.error("Site verification failed:");
  for (const problem of problems) console.error("  -", problem);
  process.exit(1);
}
console.log(`Verified ${pages.length} pages, stylesheet and stdlib reference.`);
