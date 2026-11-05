// Unit tests for the in-repo Markdown renderer used to turn docs/*.md into site pages.
//
// The site has no Markdown dependency on purpose, so this renderer is the only thing
// standing between a documentation file and the published HTML. These tests pin the
// constructs the docs actually use: headings, fenced code, tables and links.

import { test } from "node:test";
import assert from "node:assert/strict";
import { parseMarkdown, plainInline, renderMarkdown, slugify } from "../src/markdown.js";

const render = (source, options) => renderMarkdown(source, options).html;

test("headings become anchored h1-h6 with stable ids", () => {
  const { html, headings } = renderMarkdown("# Title\n\n## Getting started\n\n### `oq build`\n");
  assert.match(html, /<h1 id="title">Title<\/h1>/);
  assert.match(html, /<h2 id="getting-started">Getting started<\/h2>/);
  assert.match(html, /<h3 id="oq-build"><code>oq build<\/code><\/h3>/);
  assert.deepEqual(
    headings.map((heading) => [heading.level, heading.id, heading.text]),
    [
      [1, "title", "Title"],
      [2, "getting-started", "Getting started"],
      [3, "oq-build", "oq build"],
    ],
  );
  assert.equal(slugify("ADR-0003 — Bytecode backend strategy"), "adr-0003-bytecode-backend-strategy");
});

test("duplicate headings get distinct ids so every anchor resolves", () => {
  const { html, headings } = renderMarkdown("## Notes\n\n## Notes\n\n## Notes\n");
  assert.deepEqual(headings.map((heading) => heading.id), ["notes", "notes-1", "notes-2"]);
  assert.equal((html.match(/id="notes/g) || []).length, 3);
});

test("fenced code blocks keep their content and escape markup", () => {
  const html = render("```bash\noq run hi.oq # <tag> & \"quotes\"\n```\n");
  assert.equal(html, '<pre><code class="language-bash">oq run hi.oq # &lt;tag&gt; &amp; "quotes"</code></pre>');
});

test("code fences may omit the language and keep blank lines", () => {
  const html = render("```\na\n\nb\n```\n");
  assert.equal(html, '<pre><code>a\n\nb</code></pre>');
});

test("code spans are not parsed as emphasis or links", () => {
  const html = render("Use `a * b` and `[x](y)` literally, but **bold** and [real](https://example.com).\n");
  assert.match(html, /<code>a \* b<\/code>/);
  assert.match(html, /<code>\[x\]\(y\)<\/code>/);
  assert.match(html, /<strong>bold<\/strong>/);
  assert.match(html, /<a href="https:\/\/example.com">real<\/a>/);
});

test("pipe tables become thead/tbody, including pipes inside code spans", () => {
  const html = render(
    "| Command | Meaning |\n|---|---|\n| `x \\| y` | bitwise or |\n| `a` | plain |\n",
  );
  assert.match(html, /<table>\n<thead><tr><th>Command<\/th><th>Meaning<\/th><\/tr><\/thead>/);
  assert.match(html, /<tbody>\n<tr><td><code>x \| y<\/code><\/td><td>bitwise or<\/td><\/tr>/);
  assert.match(html, /<tr><td><code>a<\/code><\/td><td>plain<\/td><\/tr>\n<\/tbody>/);
});

test("tables end at a blank line and do not swallow the next paragraph", () => {
  const html = render("| A | B |\n|---|---|\n| 1 | 2 |\n\nAfter.\n");
  assert.match(html, /<\/table>\n<p>After\.<\/p>/);
});

test("ordered, unordered and nested lists are semantic", () => {
  const html = render("1. first\n2. second\n   - nested\n   - also\n\n- top\n- top two\n");
  assert.match(html, /<ol>\n<li>first<\/li>/);
  assert.match(html, /<li><p>second<\/p>\n<ul>\n<li>nested<\/li>\n<li>also<\/li>\n<\/ul><\/li>/);
  assert.match(html, /<ul>\n<li>top<\/li>\n<li>top two<\/li>\n<\/ul>/);
});

test("blockquotes and horizontal rules render as elements", () => {
  const html = render("> quoted **text**\n\n---\n");
  assert.equal(html, "<blockquote>\n<p>quoted <strong>text</strong></p>\n</blockquote>\n<hr>");
});

test("relative Markdown links resolve through the provided callback", () => {
  const seen = [];
  const html = render("See [backend](backend.md) and [raw](other.md#part).\n", {
    resolveHref: (href) => {
      seen.push(href);
      return `/site/${href}`;
    },
  });
  assert.deepEqual(seen, ["backend.md", "other.md#part"]);
  assert.match(html, /<a href="\/site\/backend\.md">backend<\/a>/);
  assert.match(html, /<a href="\/site\/other\.md#part">raw<\/a>/);
});

test("links with parentheses in the URL survive", () => {
  const html = render("[wiki](https://example.com/Foo_(bar)).\n");
  assert.match(html, /href="https:\/\/example\.com\/Foo_\(bar\)"/);
});

test("plainInline strips formatting for summaries and titles", () => {
  assert.equal(
    plainInline("The **compiler** uses `oq build` and [docs](x.md)."),
    "The compiler uses oq build and docs.",
  );
  assert.equal(plainInline("a * b"), "a * b");
});

test("paragraph text is joined across source line breaks", () => {
  const blocks = parseMarkdown("one\ntwo\n\nthree\n");
  assert.deepEqual(
    blocks.map((block) => block.text),
    ["one two", "three"],
  );
});

test("raw HTML in prose is escaped, never emitted", () => {
  const html = render("<script>alert(1)</script>\n");
  assert.equal(html, "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>");
});
