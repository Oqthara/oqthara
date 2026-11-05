// A small Markdown renderer for the repository's documentation.
//
// The docs use a deliberately small subset: headings, paragraphs, fenced code, pipe
// tables, ordered and unordered lists, blockquotes, rules and inline code, emphasis and
// links. Rendering that subset here keeps the build deterministic, needs no Markdown
// dependency, and lets the same code run in the prerenderer, in tests and in any build
// script that has Node available.
//
// Raw HTML is escaped rather than passed through. The docs are trusted, but a renderer
// that never emits unescaped input cannot accidentally turn a code sample into markup.

const ESCAPABLE = /[\\`*_[\]()#+.!>~-]/;

const escapeHtml = (text) =>
  String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const escapeAttribute = (text) => escapeHtml(text).replace(/"/g, "&quot;");

/** An anchor id derived from heading text: lowercase words joined by dashes. */
export function slugify(text) {
  return String(text)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, "")
    .trim()
    .replace(/[\s-]+/g, "-");
}

/** The visible text of an inline Markdown fragment, with formatting markers removed. */
export function plainInline(text) {
  return String(text)
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/`+([^`]*)`+/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/__([^_]+)__/g, "$1")
    .replace(/\*([^*]+)\*/g, "$1")
    .replace(/_([^_]+)_/g, "$1")
    .replace(/\\([\\`*_[\]()#+.!>~-])/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

const isBlank = (line) => line.trim() === "";
const isHeading = (line) => /^(#{1,6})\s+(.+)$/.test(line);
const isRule = (line) => /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line);
const isQuote = (line) => /^\s*>\s?(.*)$/.test(line);
const openFence = (line) => /^\s*```(.*)$/.exec(line);

function listMarker(line) {
  const bullet = /^(\s*)([-*+])\s+(.*)$/.exec(line);
  if (bullet) {
    return { indent: bullet[1].length, ordered: false, width: bullet[2].length + 1, content: bullet[3] };
  }
  const number = /^(\s*)(\d+)[.)]\s+(.*)$/.exec(line);
  if (number) {
    return { indent: number[1].length, ordered: true, width: number[2].length + 2, content: number[3] };
  }
  return null;
}

/** Splits a table row on pipes, except those inside an inline code span. */
function splitTableRow(line) {
  let text = line.trim();
  if (text.startsWith("|")) text = text.slice(1);
  if (text.endsWith("|") && !text.endsWith("\\|")) text = text.slice(0, -1);
  const cells = [];
  let cell = "";
  let inCode = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (char === "\\" && text[i + 1] === "|") {
      cell += "|";
      i++;
      continue;
    }
    if (char === "`") inCode = !inCode;
    if (char === "|" && !inCode) {
      cells.push(cell.trim());
      cell = "";
      continue;
    }
    cell += char;
  }
  cells.push(cell.trim());
  return cells;
}

const isTableSeparator = (line) => {
  const cells = splitTableRow(line);
  return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell));
};

const isTableStart = (lines, index) =>
  lines[index].includes("|") &&
  index + 1 < lines.length &&
  isTableSeparator(lines[index + 1]);

const startsBlock = (lines, index) => {
  const line = lines[index];
  return Boolean(
    openFence(line) ||
      isHeading(line) ||
      isRule(line) ||
      isQuote(line) ||
      listMarker(line) ||
      isTableStart(lines, index),
  );
};

function dedent(line, spaces) {
  let count = 0;
  while (count < spaces && line[count] === " ") count++;
  return line.slice(count);
}

function parseList(lines, start) {
  const first = listMarker(lines[start]);
  if (!first) return null;
  const baseIndent = first.indent;
  const ordered = first.ordered;
  const items = [];
  let index = start;

  while (index < lines.length) {
    const marker = listMarker(lines[index]);
    if (!marker || marker.indent !== baseIndent || marker.ordered !== ordered) break;
    const contentIndent = marker.indent + marker.width;
    const body = [marker.content];
    index++;
    while (index < lines.length && !isBlank(lines[index])) {
      const next = listMarker(lines[index]);
      if (next && next.indent <= baseIndent) break;
      body.push(dedent(lines[index], contentIndent));
      index++;
    }
    items.push(parseMarkdownLines(body));
  }

  return { block: { type: "list", ordered, items }, next: index };
}

function parseMarkdownLines(lines) {
  const blocks = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    if (isBlank(line)) {
      index++;
      continue;
    }

    const fence = openFence(line);
    if (fence) {
      const lang = fence[1].trim();
      const value = [];
      index++;
      while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) {
        value.push(lines[index]);
        index++;
      }
      if (index < lines.length) index++;
      blocks.push({ type: "code", lang, value: value.join("\n") });
      continue;
    }

    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) {
      blocks.push({ type: "heading", level: heading[1].length, text: heading[2].trim() });
      index++;
      continue;
    }

    if (isRule(line)) {
      blocks.push({ type: "rule" });
      index++;
      continue;
    }

    if (isTableStart(lines, index)) {
      const head = splitTableRow(lines[index]);
      const rows = [];
      index += 2;
      while (index < lines.length && !isBlank(lines[index]) && lines[index].includes("|")) {
        rows.push(splitTableRow(lines[index]));
        index++;
      }
      blocks.push({ type: "table", head, rows });
      continue;
    }

    if (isQuote(line)) {
      const quoted = [];
      while (index < lines.length && isQuote(lines[index])) {
        quoted.push(/^\s*>\s?(.*)$/.exec(lines[index])[1]);
        index++;
      }
      blocks.push({ type: "quote", blocks: parseMarkdownLines(quoted) });
      continue;
    }

    const list = parseList(lines, index);
    if (list) {
      blocks.push(list.block);
      index = list.next;
      continue;
    }

    const paragraph = [line.trim()];
    index++;
    while (index < lines.length && !isBlank(lines[index]) && !startsBlock(lines, index)) {
      paragraph.push(lines[index].trim());
      index++;
    }
    blocks.push({ type: "paragraph", text: paragraph.join(" ") });
  }

  return blocks;
}

/** Parses Markdown into a tiny block tree. Inline formatting stays as source text. */
export function parseMarkdown(source) {
  return parseMarkdownLines(String(source).replace(/\r\n?/g, "\n").split("\n"));
}

function findCodeClose(text, from, runLength) {
  let index = from;
  while ((index = text.indexOf("`", index)) !== -1) {
    let run = 0;
    while (text[index + run] === "`") run++;
    if (run === runLength) return index;
    index += run;
  }
  return -1;
}

function findClosingParen(text, from) {
  let depth = 1;
  for (let index = from; index < text.length; index++) {
    if (text[index] === "\\") {
      index++;
      continue;
    }
    if (text[index] === "(") depth++;
    if (text[index] === ")") {
      depth--;
      if (depth === 0) return index;
    }
  }
  return -1;
}

function parseLink(text, start) {
  let depth = 0;
  let index = start;
  for (; index < text.length; index++) {
    if (text[index] === "\\") {
      index++;
      continue;
    }
    if (text[index] === "[") depth++;
    if (text[index] === "]") {
      depth--;
      if (depth === 0) break;
    }
  }
  if (index >= text.length || text[index + 1] !== "(") return null;
  const close = findClosingParen(text, index + 2);
  if (close === -1) return null;
  const href = text.slice(index + 2, close).trim().replace(/^<|>$/g, "");
  return { label: text.slice(start + 1, index), href, end: close + 1 };
}

function renderInline(text, context) {
  let output = "";
  let index = 0;
  while (index < text.length) {
    const char = text[index];

    if (char === "\\" && index + 1 < text.length && ESCAPABLE.test(text[index + 1])) {
      output += escapeHtml(text[index + 1]);
      index += 2;
      continue;
    }

    if (char === "`") {
      let run = 0;
      while (text[index + run] === "`") run++;
      const close = findCodeClose(text, index + run, run);
      if (close !== -1) {
        let value = text.slice(index + run, close).replace(/\n/g, " ");
        if (value.startsWith(" ") && value.endsWith(" ") && value.trim() !== "") {
          value = value.slice(1, -1);
        }
        output += `<code>${escapeHtml(value)}</code>`;
        index = close + run;
        continue;
      }
    }

    if (char === "!" && text[index + 1] === "[") {
      const link = parseLink(text, index + 1);
      if (link) {
        output += `<img src="${escapeAttribute(context.resolveHref(link.href))}" alt="${escapeAttribute(plainInline(link.label))}">`;
        index = link.end;
        continue;
      }
    }

    if (char === "[") {
      const link = parseLink(text, index);
      if (link) {
        output += `<a href="${escapeAttribute(context.resolveHref(link.href))}">${renderInline(link.label, context)}</a>`;
        index = link.end;
        continue;
      }
    }

    if (char === "*" || char === "_") {
      const previous = index === 0 ? "" : text[index - 1];
      if (char === "_" && /[\p{L}\p{N}]/u.test(previous)) {
        output += char;
        index++;
        continue;
      }
      const double = text[index + 1] === char;
      const marker = double ? char + char : char;
      const close = text.indexOf(marker, index + marker.length);
      if (close !== -1 && close > index + marker.length) {
        const inner = text.slice(index + marker.length, close);
        const opens = !/^\s/.test(inner);
        const closes = !/\s$/.test(inner);
        if (opens && closes) {
          const tag = double ? "strong" : "em";
          output += `<${tag}>${renderInline(inner, context)}</${tag}>`;
          index = close + marker.length;
          continue;
        }
      }
    }

    output += escapeHtml(char);
    index++;
  }
  return output;
}

function renderBlocksWithContext(blocks, context) {
  return blocks.map((block) => renderBlock(block, context)).join("\n");
}

function renderBlock(block, context) {
  switch (block.type) {
    case "heading": {
      const text = plainInline(block.text);
      const base = slugify(text) || "section";
      const seen = context.ids.get(base) ?? 0;
      context.ids.set(base, seen + 1);
      const id = seen === 0 ? base : `${base}-${seen}`;
      context.headings.push({ level: block.level, id, text });
      return `<h${block.level} id="${escapeAttribute(id)}">${renderInline(block.text, context)}</h${block.level}>`;
    }
    case "paragraph":
      return `<p>${renderInline(block.text, context)}</p>`;
    case "code": {
      const language = block.lang.replace(/[^A-Za-z0-9_+-]/g, "");
      const attribute = language ? ` class="language-${language}"` : "";
      return `<pre><code${attribute}>${escapeHtml(block.value)}</code></pre>`;
    }
    case "table": {
      const head = block.head.map((cell) => `<th>${renderInline(cell, context)}</th>`).join("");
      const rows = block.rows
        .map((row) => `<tr>${row.map((cell) => `<td>${renderInline(cell, context)}</td>`).join("")}</tr>`)
        .join("\n");
      return `<table>\n<thead><tr>${head}</tr></thead>\n<tbody>\n${rows}\n</tbody>\n</table>`;
    }
    case "list": {
      const tag = block.ordered ? "ol" : "ul";
      const items = block.items
        .map((item) => {
          const inner =
            item.length === 1 && item[0].type === "paragraph"
              ? renderInline(item[0].text, context)
              : renderBlocksWithContext(item, context);
          return `<li>${inner}</li>`;
        })
        .join("\n");
      return `<${tag}>\n${items}\n</${tag}>`;
    }
    case "quote":
      return `<blockquote>\n${renderBlocksWithContext(block.blocks, context)}\n</blockquote>`;
    case "rule":
      return "<hr>";
    default:
      return "";
  }
}

/** Renders a parsed block tree, returning both the HTML and the headings it produced. */
export function renderBlocks(blocks, options = {}) {
  const context = {
    ids: new Map(),
    headings: [],
    resolveHref: options.resolveHref ?? ((href) => href),
  };
  return {
    html: renderBlocksWithContext(blocks, context),
    headings: context.headings,
  };
}

/** Renders Markdown source to semantic HTML plus its heading list. */
export function renderMarkdown(source, options = {}) {
  return renderBlocks(parseMarkdown(source), options);
}

