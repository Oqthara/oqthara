import { createElement as h } from "react";

// A tiny highlighter for Oqthara snippets. It is deliberately not a full parser: the site
// shows short, hand-written examples, and a tokenizer that handles comments, strings,
// numbers and keywords is enough to make them readable. Anything it does not recognise is
// emitted as plain text, so a mistake here can only lose colour, never content.

const KEYWORDS = new Set([
  "if", "else", "end", "for", "in", "to", "until", "while", "break", "continue",
  "fun", "return", "data", "try", "catch", "use", "spawn", "await", "done",
  "and", "or", "not", "true", "false", "nothing",
]);

const BUILTINS = new Set([
  "print", "write", "error", "assert", "par_map", "length", "range", "int",
  "text", "bool", "json_parse", "json_write", "read", "save", "env", "exit",
]);

function classify(word) {
  if (KEYWORDS.has(word)) return "kw";
  if (BUILTINS.has(word)) return "fn";
  return null;
}

/**
 * Splits source into tokens. Line comments start with `#`, strings are double-quoted with
 * `{{` escaping a literal brace, and everything else is scanned for words and numbers.
 */
export function tokenize(source) {
  const tokens = [];
  let index = 0;
  while (index < source.length) {
    const char = source[index];

    if (char === "#") {
      const end = source.indexOf("\n", index);
      const stop = end === -1 ? source.length : end;
      tokens.push({ kind: "com", value: source.slice(index, stop) });
      index = stop;
      continue;
    }

    if (char === '"') {
      let end = index + 1;
      while (end < source.length) {
        if (source[end] === "\\") {
          end += 2;
          continue;
        }
        if (source[end] === '"') break;
        end++;
      }
      tokens.push({ kind: "str", value: source.slice(index, end + 1) });
      index = end + 1;
      continue;
    }

    // `{name}` inside a string is interpolation, but by the time we get here the string
    // has been consumed whole, so braces outside strings are just braces.
    if (/[0-9]/.test(char)) {
      let end = index;
      while (end < source.length && /[0-9._]/.test(source[end])) end++;
      tokens.push({ kind: "num", value: source.slice(index, end) });
      index = end;
      continue;
    }

    if (/[A-Za-z_]/.test(char)) {
      let end = index;
      while (end < source.length && /[A-Za-z0-9_]/.test(source[end])) end++;
      const word = source.slice(index, end);
      tokens.push({ kind: classify(word) ?? "ident", value: word });
      index = end;
      continue;
    }

    tokens.push({ kind: "punct", value: char });
    index++;
  }
  return tokens;
}

/** Renders one code block as highlighted React spans. */
export function Code({ children, label }) {
  const source = String(children).replace(/^\n+|\n+$/g, "");
  const tokens = tokenize(source);
  // Every token becomes an element, including plain text. Returning a bare string here made
  // React group adjacent strings into one text node on the server but keep them separate on
  // the client, which is exactly the hydration mismatch React reports as #418.
  return h(
    "pre",
    null,
    h(
      "code",
      label ? { "aria-label": label } : null,
      tokens.map((token, index) =>
        h(
          "span",
          {
            key: index,
            className: token.kind === "ident" || token.kind === "punct" ? undefined : token.kind,
          },
          token.value,
        ),
      ),
    ),
  );
}
