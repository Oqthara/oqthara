// Tests for the documentation pipeline: every docs/*.md file becomes a page, the index
// lists all of them, and links/anchors in the generated HTML resolve.
//
// These read the prerendered output, so they fail if the build stops rendering docs even
// though every component still looks correct in isolation.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { docHref, resolveDocLink } from "../src/docs.js";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const site = join(here, "..", "_site");
const hasBuild = existsSync(join(site, "docs.html"));

/** Every rendered source file and the page path it must produce. */
function documentationFiles() {
  const files = [];
  for (const dir of ["spec", "adr"]) {
    const absolute = join(root, "docs", dir);
    if (!existsSync(absolute)) continue;
    for (const name of readdirSync(absolute).sort()) {
      if (!name.endsWith(".md")) continue;
      files.push({
        source: `docs/${dir}/${name}`,
        page: `docs/${dir}/${name.replace(/\.md$/, ".html")}`,
      });
    }
  }
  return files;
}

const docs = documentationFiles();
const skip = !hasBuild && "run `npm run build` first";

test("every documentation file is discovered", () => {
  assert.ok(docs.length >= 12, `expected the spec and ADR files, found ${docs.length}`);
  for (const doc of docs) {
    assert.ok(existsSync(join(root, doc.source)), `${doc.source} must exist`);
  }
});

test("every documentation file gets a page", { skip }, () => {
  for (const doc of docs) {
    assert.ok(existsSync(join(site, doc.page)), `missing ${doc.page} for ${doc.source}`);
  }
});

test("the documentation index lists every rendered document", { skip }, () => {
  const html = readFileSync(join(site, "docs.html"), "utf8");
  for (const doc of docs) {
    const href = `/oqthara/${doc.page}`;
    assert.ok(html.includes(`href="${href}"`), `docs.html does not link ${href}`);
  }
});

test("a documentation page contains semantic HTML from the Markdown", { skip }, () => {
  const html = readFileSync(join(site, "docs/spec/language.html"), "utf8");
  assert.match(html, /<article class="doc">/);
  assert.match(html, /<h1 id="[^"]+">Oqthara language reference<\/h1>/);
  assert.match(html, /<h2 id="values">Values<\/h2>/);
  assert.match(html, /<table>\n<thead><tr><th>Type<\/th>/);
  assert.match(html, /<pre><code>/);
  assert.match(html, /<code>print "Hello, World!"<\/code>/, "inline code is preserved");
  assert.ok(!html.includes("<p>## "), "heading syntax leaked as prose");
});

test("relative Markdown links become site links", { skip }, () => {
  const html = readFileSync(join(site, "docs/spec/language.html"), "utf8");
  const links = [...html.matchAll(/href="([^"]*backend\.html)"/g)].map((match) => match[1]);
  assert.ok(links.length >= 2, "language reference must link backend.md twice");
  for (const link of links) assert.equal(link, "/oqthara/docs/spec/backend.html");
});

test("no relative Markdown link leaks into a docs page", { skip }, () => {
  for (const doc of docs) {
    const html = readFileSync(join(site, doc.page), "utf8");
    for (const match of html.matchAll(/href="([^"]+\.md[^"]*)"/g)) {
      assert.ok(
        match[1].startsWith("https://github.com/Oqthara/oqthara/"),
        `${doc.page}: relative .md link ${match[1]} was not resolved`,
      );
    }
  }
});

test("every internal link and anchor on a docs page resolves", { skip }, () => {
  for (const doc of docs) {
    const html = readFileSync(join(site, doc.page), "utf8");
    const ids = new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1]));
    for (const match of html.matchAll(/href="([^"]+)"/g)) {
      const target = match[1];
      if (target.startsWith("#")) {
        assert.ok(ids.has(target.slice(1)), `${doc.page}: missing anchor ${target}`);
        continue;
      }
      if (!target.startsWith("/oqthara/")) {
        assert.ok(
          target.startsWith("http") || target.startsWith("mailto:"),
          `${doc.page}: link ${target} is neither internal nor absolute`,
        );
        continue;
      }
      const relative = target.slice("/oqthara/".length).split("#")[0];
      assert.ok(existsSync(join(site, relative)), `${doc.page}: broken link ${target}`);
    }
  }
});

test("external links and same-page anchors are preserved", () => {
  const sources = docs.map((doc) => doc.source);
  assert.equal(
    resolveDocLink("https://example.com/x", "docs/spec/design.md", sources),
    "https://example.com/x",
  );
  assert.equal(resolveDocLink("#values", "docs/spec/design.md", sources), "#values");
  assert.equal(
    resolveDocLink("language.md#values", "docs/spec/design.md", sources),
    "/oqthara/docs/spec/language.html#values",
  );
  assert.equal(
    resolveDocLink("../other.md", "docs/spec/design.md", sources),
    "https://github.com/Oqthara/oqthara/blob/main/docs/other.md",
  );
});

test("docHref points at the generated page under the Pages base", () => {
  assert.equal(docHref("docs/adr/0001-syntax.md"), "/oqthara/docs/adr/0001-syntax.html");
});
