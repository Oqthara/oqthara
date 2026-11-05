// Tests for site search: the pure ranking/keyboard logic, the build-time index generator,
// and the generated index in _site when a build is present.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { activeOptionId, keyAction, moveActive, optionId, rankEntries, tokenizeQuery } from "../src/search.js";
import { buildSearchIndex, decodeEntities, tidy } from "../src/search-index.js";

const here = dirname(fileURLToPath(import.meta.url));
const site = join(here, "..", "_site");
const hasBuild = existsSync(join(site, "search-index.json"));

const ENTRIES = [
  { title: "Standard library", page: "Standard library", url: "/oqthara/stdlib.html", text: "Generated reference" },
  { title: "Collections", page: "Tour", url: "/oqthara/tour.html#collections", text: "Lists, maps and sets" },
  { title: "Java interop", page: "Tour", url: "/oqthara/tour.html#java", text: "use imports any class from the standard library" },
  { title: "Status", page: "Status", url: "/oqthara/status.html", text: "What does not work yet" },
];

const PAGE = {
  file: "tour.html",
  html: `<!DOCTYPE html><html><head><title>Tour — Oqthara</title></head><body>
<main id="main"><div class="wrap">
<h1>Learn Oqthara</h1>
<p>Intro &amp; overview.</p>
<h2 id="values">Values</h2><p>Variables are inferred.</p>
<h2 id="java">Java interop</h2><p>Call <code>LocalDate.now()</code>.</p>
<h2>No anchor</h2><p>Still indexed.</p>
</div></main>
<script>var x = "hidden";</script>
</body></html>`,
};

test("tokenizeQuery normalises case, accents and whitespace", () => {
  assert.deepEqual(tokenizeQuery("  CafÉ   DATA "), ["cafe", "data"]);
  assert.deepEqual(tokenizeQuery(""), []);
  assert.deepEqual(tokenizeQuery(null), []);
  assert.equal(tokenizeQuery("a b c d e f g h i j").length, 8);
});

test("rankEntries prefers titles and requires every token", () => {
  assert.deepEqual(rankEntries(ENTRIES, ""), []);
  assert.deepEqual(rankEntries(ENTRIES, "zzz"), []);

  const ranked = rankEntries(ENTRIES, "java");
  assert.equal(ranked[0].title, "Java interop");

  const both = rankEntries(ENTRIES, "standard library");
  assert.equal(both[0].title, "Standard library");
  assert.ok(both.every((entry) => /standard|library/i.test(`${entry.title} ${entry.text}`)));

  assert.equal(rankEntries(ENTRIES, "the", 1).length, 1);
  assert.equal(rankEntries(ENTRIES, "e", 2).length, 2);
});

test("rankEntries is stable for equally scored entries", () => {
  const entries = [
    { title: "Same", text: "same text", url: "/oqthara/a.html" },
    { title: "Same", text: "same text", url: "/oqthara/b.html" },
  ];
  assert.deepEqual(
    rankEntries(entries, "same").map((entry) => entry.url),
    ["/oqthara/a.html", "/oqthara/b.html"],
  );
});

test("moveActive handles arrows and bounds", () => {
  assert.equal(moveActive(-1, "ArrowDown", 3), 0);
  assert.equal(moveActive(-1, "ArrowUp", 3), 2);
  assert.equal(moveActive(0, "ArrowDown", 3), 1);
  assert.equal(moveActive(2, "ArrowDown", 3), 2);
  assert.equal(moveActive(0, "ArrowUp", 3), 0);
  assert.equal(moveActive(1, "Enter", 3), 1);
  assert.equal(moveActive(5, "ArrowDown", 3), 2);
  assert.equal(moveActive(-7, "ArrowUp", 3), 2);
  assert.equal(moveActive(0, "ArrowDown", 0), -1);
  assert.equal(moveActive(0, "ArrowUp", -1), -1);
});

test("keyAction maps the keyboard contract", () => {
  assert.deepEqual(keyAction("Escape", { expanded: true }), { type: "close" });
  assert.equal(keyAction("Escape", { expanded: false }), null);
  assert.deepEqual(keyAction("Enter", { expanded: true, count: 3, active: -1 }), { type: "open", index: 0 });
  assert.deepEqual(keyAction("Enter", { expanded: true, count: 3, active: 2 }), { type: "open", index: 2 });
  assert.equal(keyAction("Enter", { expanded: false, count: 3, active: 0 }), null);
  assert.equal(keyAction("Enter", { expanded: true, count: 0 }), null);
  assert.deepEqual(keyAction("ArrowDown", { expanded: true, count: 2, active: -1 }), { type: "move", index: 0 });
  assert.deepEqual(keyAction("ArrowUp", { expanded: true, count: 2, active: -1 }), { type: "move", index: 1 });
  assert.equal(keyAction("ArrowDown", { expanded: false, count: 2 }), null);
  assert.equal(keyAction("Tab", { expanded: true, count: 2, active: 0 }), null);
});

test("option ids are shared by the markup and aria-activedescendant", () => {
  assert.equal(optionId(3), "site-search-option-3");
  assert.equal(activeOptionId(true, 1), "site-search-option-1");
  assert.equal(activeOptionId(true, -1), undefined);
  assert.equal(activeOptionId(false, 1), undefined);
});

test("decodeEntities and tidy clean generated markup", () => {
  assert.equal(decodeEntities("&amp; &lt;x&gt; &#65; &#x42; &nbsp; &unknown;"), "& <x> A B   &unknown;");
  assert.equal(tidy("Point ( x , y ) ."), "Point (x, y).");
});

test("buildSearchIndex splits pages on headings and anchors them", () => {
  const entries = buildSearchIndex([PAGE]);
  assert.deepEqual(entries.map((entry) => entry.title), [
    "Learn Oqthara",
    "Values",
    "Java interop",
    "No anchor",
  ]);
  assert.deepEqual(entries.map((entry) => entry.url), [
    "/oqthara/tour.html",
    "/oqthara/tour.html#values",
    "/oqthara/tour.html#java",
    "/oqthara/tour.html",
  ]);
  assert.ok(entries[0].text.includes("Intro & overview"));
  assert.ok(entries[2].text.includes("LocalDate.now()"));
  assert.ok(!entries.some((entry) => entry.text.includes("hidden")), "scripts must not be indexed");
});

test("buildSearchIndex is deterministic and ignores unusable input", () => {
  const first = JSON.stringify(buildSearchIndex([PAGE, PAGE]));
  const second = JSON.stringify(buildSearchIndex([PAGE, PAGE]));
  assert.equal(first, second);
  assert.deepEqual(buildSearchIndex([]), []);
  assert.deepEqual(buildSearchIndex([{ file: "../evil.html", html: "<main><h1>x</h1></main>" }]), []);
  assert.deepEqual(buildSearchIndex([{ file: "empty.html", html: "<main></main>" }]), []);
});

test("the generated index only points at real prerendered routes", { skip: !hasBuild && "run `npm run build` first" }, () => {
  const raw = readFileSync(join(site, "search-index.json"), "utf8");
  const entries = JSON.parse(raw);
  assert.ok(Array.isArray(entries) && entries.length > 0, "index must have entries");
  assert.ok(Buffer.byteLength(raw) < 32 * 1024, `index is ${Buffer.byteLength(raw)} bytes`);

  const routes = new Set();
  for (const entry of entries) {
    assert.ok(entry.title && entry.title.trim(), "every entry has a title");
    assert.ok(entry.url.startsWith("/oqthara/"), `${entry.url} must use the base path`);

    const [path, anchor] = entry.url.split("#");
    const relative = path.slice("/oqthara/".length) || "index.html";
    const file = relative.endsWith("/") ? `${relative}index.html` : relative;
    routes.add(file);
    assert.ok(existsSync(join(site, file)), `${entry.url} does not resolve to a page`);
    if (anchor) {
      const html = readFileSync(join(site, file), "utf8");
      assert.ok(html.includes(`id="${anchor}"`), `${entry.url} has no matching element`);
    }
  }
  assert.deepEqual([...routes].sort(), [
    "docs.html",
    "examples.html",
    "index.html",
    "status.html",
    "stdlib.html",
    "tour.html",
  ]);
});

test("the search input ships with combobox semantics", { skip: !hasBuild && "run `npm run build` first" }, () => {
  const html = readFileSync(join(site, "index.html"), "utf8");
  assert.ok(html.includes('role="search"'), "search landmark");
  assert.ok(html.includes('type="search"'), "search input");
  assert.ok(html.includes('role="combobox"'), "combobox role");
  assert.ok(html.includes('aria-controls="site-search-results"'), "combobox controls the results");
  assert.ok(html.includes('aria-autocomplete="list"'), "combobox offers a list");
  assert.ok(html.includes('aria-expanded="false"'), "combobox starts collapsed");
  assert.ok(html.includes('for="site-search-input"'), "input has a label");
  assert.ok(html.includes('id="site-search-results"'), "results container is present");
  assert.ok(html.includes('class="site-search-panel"'), "results panel is present");
});

test("the search component exposes the listbox when open", () => {
  const source = readFileSync(join(here, "..", "src", "components", "Search.jsx"), "utf8");
  assert.ok(source.includes('role: expanded ? "listbox" : undefined'), "listbox role is used while open");
  assert.ok(source.includes('role: "option"'), "results are options");
  assert.ok(source.includes("aria-selected"), "the active option is marked");
  assert.ok(source.includes("aria-activedescendant"), "the combobox points at the active option");
  assert.ok(source.includes("keyAction("), "keyboard handling uses the tested keyAction");
  assert.ok(source.includes("onKeyDown"), "the input listens for keys");
});
