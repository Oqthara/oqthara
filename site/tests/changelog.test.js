// Tests for the generated changelog page.
//
// The page must be the repository's CHANGELOG.md rendered at build time, not a hand-written
// summary and not a second copy of the text. These tests check the conversion of headings,
// lists and links, that every bundled entry comes from the source, and that the changelog
// is not duplicated: once in the Markdown and once again in the site source or the page.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { extractReferences, parseMarkdown, plainInline, renderMarkdown } from "../src/markdown.js";
import { htmlToText, tidy } from "../src/search-index.js";
import { CHANGELOG_FILE, CHANGELOG_ROUTE, CHANGELOG_SOURCE } from "../src/changelog.js";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const site = join(here, "..", "_site");
const source = readFileSync(join(root, CHANGELOG_SOURCE), "utf8");
const rendered = renderMarkdown(source);
const built = existsSync(join(site, CHANGELOG_FILE)) ? readFileSync(join(site, CHANGELOG_FILE), "utf8") : "";
const skip = !built && "run `npm run build` first";

/** Every unordered list item in the source, as plain text. */
function sourceEntries() {
  return parseMarkdown(source)
    .filter((block) => block.type === "list")
    .flatMap((block) => block.items)
    .map((item) => plainInline(item[0].text));
}

test("the changelog constants name one route, file and source", () => {
  assert.equal(CHANGELOG_SOURCE, "CHANGELOG.md");
  assert.equal(CHANGELOG_ROUTE, "/changelog.html");
  assert.equal(CHANGELOG_FILE, "changelog.html");
});

test("headings become anchored semantic HTML with unique ids", () => {
  const ids = rendered.headings.map((heading) => heading.id);
  assert.equal(rendered.headings[0].level, 1);
  assert.equal(rendered.headings[0].text, "Changelog");
  assert.ok(ids.includes("unreleased"), "the unreleased section is anchored");
  assert.ok(ids.includes("section-010-alpha1-2026-11-04"), "the release section is anchored");
  assert.equal(new Set(ids).size, ids.length, "ids are unique, so every anchor resolves");
  assert.match(rendered.html, /<h2 id="unreleased">/);
  assert.match(rendered.html, /<h3 id="added">/);
});

test("change lists and entries render as real list markup", () => {
  const entries = sourceEntries();
  assert.ok(entries.length >= 40, `expected the full changelog history, found ${entries.length} entries`);
  assert.equal((rendered.html.match(/<li>/g) || []).length, entries.length, "one list item per entry");
  assert.match(rendered.html, /<ul>\n<li><code>oq build<\/code>/);
});

test("links, including reference-style ones, become anchors", () => {
  assert.match(rendered.html, /<a href="https:\/\/keepachangelog\.com\/en\/1\.1\.0\/">Keep a Changelog<\/a>/);
  assert.match(rendered.html, /<a href="https:\/\/semver\.org\/spec\/v2\.0\.0\.html">Semantic Versioning<\/a>/);
  assert.match(rendered.html, /<h2 id="unreleased"><a href="https:\/\/github\.com\/Oqthara\/oqthara\/compare\/[^"]+">Unreleased<\/a><\/h2>/);
  assert.match(rendered.html, /<a href="https:\/\/github\.com\/Oqthara\/oqthara\/releases\/tag\/[^"]+">0\.1\.0-alpha\.1<\/a>/);
  assert.ok(!rendered.html.includes("[Unreleased]:"), "link definitions do not leak as prose");
  assert.equal(extractReferences(source).size, 2);
  assert.equal(plainInline("[Unreleased]", extractReferences(source)), "Unreleased");
});

test("the built page is the full changelog, rendered from CHANGELOG.md", { skip }, () => {
  assert.match(built, /<article class="doc">/);
  assert.match(built, /<h1 id="changelog">Changelog<\/h1>/);
  assert.match(built, /<h2 id="unreleased">/);
  assert.match(built, /<h3 id="added">/);

  const entries = parseMarkdown(source)
    .filter((block) => block.type === "list")
    .flatMap((block) => block.items)
    .map((item) => item[0].text);
  assert.equal((built.match(/<li>/g) || []).length, entries.length, "every source entry appears exactly once");
  const text = tidy(htmlToText(built));
  for (const entry of entries) {
    const expected = tidy(htmlToText(renderMarkdown(entry).html));
    assert.ok(text.includes(expected.slice(0, 80)), `missing changelog entry: ${expected.slice(0, 80)}`);
  }
  assert.ok(!built.includes("[Unreleased]:"), "link definitions do not leak into the page");
});

test("no changelog content is duplicated between the page and its sources", { skip }, () => {
  const expectedHeadings = rendered.headings.filter((heading) => heading.level === 2).map((heading) => heading.id);
  const headings = [...built.matchAll(/<h2 id="([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(headings, expectedHeadings, "each version heading appears exactly once");

  const article = /<article class="doc">([\s\S]*?)<\/article>/.exec(built)?.[1] ?? "";
  assert.equal((article.match(/<h1\b/g) || []).length, 1, "one article heading");
  assert.equal((article.match(/id="changelog"/g) || []).length, 1, "the changelog title is not repeated");

  // A hand-written copy in the site source would be a second source of truth. The first
  // entry and the section headings are distinctive enough that copying them would trip this.
  const phrases = [
    sourceEntries()[0].slice(0, 60),
    "All notable changes to this project are documented here.",
    "The format is based on",
  ];
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (["node_modules", "_site", "dist", ".ssr"].includes(entry.name)) continue;
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!/\.(js|jsx|mjs|css|html|json)$/.test(entry.name) || entry.name === "changelog.test.js") continue;
      const content = readFileSync(path, "utf8");
      for (const phrase of phrases) {
        assert.ok(!content.includes(phrase), `${path.slice(root.length + 1)} duplicates changelog content`);
      }
    }
  };
  walk(join(root, "site"));
  assert.equal((built.match(/id="oq-changelog"/g) || []).length, 1, "the rendered changelog is embedded once");
});

test("the changelog is linked from the navigation on every built page", { skip }, () => {
  for (const name of readdirSync(site)) {
    if (!name.endsWith(".html")) continue;
    const html = readFileSync(join(site, name), "utf8");
    assert.ok(html.includes('href="/oqthara/changelog.html"'), `${name}: navigation does not link the changelog`);
  }
  assert.match(built, /aria-current="page"[^>]*>Changelog</);
});

test("search indexes the changelog entries", { skip }, () => {
  const entries = JSON.parse(readFileSync(join(site, "search-index.json"), "utf8"));
  const changelogEntries = entries.filter((entry) => entry.url.startsWith("/oqthara/changelog.html"));
  assert.ok(changelogEntries.length >= 2, "the page and its sections are indexed");
  assert.ok(changelogEntries.some((entry) => entry.url.endsWith("#unreleased")), "the unreleased section is indexed");
  assert.ok(changelogEntries.some((entry) => entry.text.includes("bytecode")), "entry text is indexed");
});
