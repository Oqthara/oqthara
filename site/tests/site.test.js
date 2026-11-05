// Component and integration tests for the React site.
//
// These run in plain Node with no DOM, because the important guarantees are about the
// markup this site actually ships: that every route renders real content, that the base
// path is right for GitHub Pages, and that the standard library page is generated rather
// than hand-copied.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const site = join(here, "..", "_site");
const hasBuild = existsSync(join(site, "index.html"));

const page = (file) => readFileSync(join(site, file), "utf8");
const textOf = (html) =>
  html.replace(/<script[\s\S]*?<\/script>/g, " ").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();

test("every route is prerendered with real content", { skip: !hasBuild && "run `npm run build` first" }, () => {
  const routes = [
    ["index.html", "pseudocode"],
    ["tour.html", "Learn Oqthara in one sitting"],
    ["docs.html", "Documentation"],
    ["examples.html", "Complete programs"],
    ["stdlib.html", "Standard library"],
    ["changelog.html", "All notable changes"],
    ["status.html", "What does not work yet"],
  ];
  for (const [file, marker] of routes) {
    const html = page(file);
    assert.equal((html.match(/<div id="root">/g) || []).length, 1, `${file}: one root container`);
    assert.ok(textOf(html).includes(marker), `${file}: expected "${marker}" in prerendered text`);
    assert.ok(textOf(html).length > 400, `${file}: content looks empty`);
  }
});

test("internal links use the GitHub Pages base path", { skip: !hasBuild && "run `npm run build` first" }, () => {
  for (const file of ["index.html", "tour.html", "docs.html", "examples.html", "stdlib.html", "changelog.html", "status.html"]) {
    const html = page(file);
    for (const match of html.matchAll(/href="(\/[^"]*)"/g)) {
      assert.ok(match[1].startsWith("/oqthara/"), `${file}: ${match[1]} must be under /oqthara/`);
    }
  }
});

test("every internal link resolves to a real file", { skip: !hasBuild && "run `npm run build` first" }, () => {
  for (const file of ["index.html", "tour.html", "docs.html", "examples.html", "stdlib.html", "changelog.html", "status.html"]) {
    const html = page(file);
    for (const match of html.matchAll(/href="(\/oqthara\/[^"]*)"/g)) {
      const relative = match[1].slice("/oqthara/".length);
      if (relative === "") continue;
      assert.ok(existsSync(join(site, relative)), `${file}: ${match[1]} does not exist`);
    }
  }
});

test("the stdlib page is generated from the real reference", { skip: !hasBuild && "run `npm run build` first" }, () => {
  const html = page("stdlib.html");
  const reference = readFileSync(join(here, "..", "..", "docs", "stdlib", "reference.md"), "utf8");
  const keywords = reference.match(/## Keywords\n\n([^\n]+)/);
  assert.ok(keywords, "reference must list keywords");
  for (const keyword of keywords[1].split(",").map((word) => word.replace(/[`\s]/g, ""))) {
    assert.ok(html.includes(keyword), `stdlib page is missing the keyword ${keyword}`);
  }
  assert.ok(html.includes("Global functions"), "stdlib page must render the functions table");
});

test("accessibility structure is present on every page", { skip: !hasBuild && "run `npm run build` first" }, () => {
  for (const file of ["index.html", "tour.html", "docs.html", "examples.html", "stdlib.html", "changelog.html", "status.html"]) {
    const html = page(file);
    assert.ok(html.includes('class="skip-link"'), `${file}: skip link`);
    assert.ok(html.includes('href="#main"'), `${file}: skip link target`);
    assert.ok(html.includes('<main id="main">'), `${file}: main landmark`);
    assert.ok(html.includes('<header class="site">'), `${file}: header landmark`);
    assert.ok(html.includes('aria-label="Main"'), `${file}: labelled nav`);
    assert.ok(html.includes('lang="en"'), `${file}: language`);
  }
});

test("the current page is marked in the navigation", { skip: !hasBuild && "run `npm run build` first" }, () => {
  const html = page("tour.html");
  assert.ok(/aria-current="page"[^>]*>Tour</.test(html) || /href="\/oqthara\/tour.html"[^>]*aria-current="page"/.test(html),
    "the tour link must be marked as the current page");
  assert.ok(!/aria-current="page"[^>]*>Docs</.test(html), "only one link may be current");
});

test("no placeholder or template leakage ships", { skip: !hasBuild && "run `npm run build` first" }, () => {
  for (const file of ["index.html", "tour.html", "docs.html", "examples.html", "stdlib.html", "changelog.html", "status.html"]) {
    const html = page(file);
    assert.ok(!html.includes("${"), `${file}: template literal leaked`);
    assert.ok(!html.includes("__REFERENCE__"), `${file}: reference placeholder leaked`);
    assert.ok(!html.includes("undefined"), `${file}: undefined shipped in markup`);
  }
});
