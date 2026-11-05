// Pure search logic: query parsing, ranking and keyboard movement. Keeping this out of the
// component means it runs in plain Node, so the tests do not need a DOM or a build.

export const SEARCH_LIMIT = 8;

/** Lowercase, strip accents and split on whitespace. Long queries stop at eight tokens. */
export function tokenizeQuery(query) {
  return String(query ?? "")
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 8);
}

/**
 * Returns the entries that contain every token, best match first.
 *
 * A token in the title is worth much more than the same token buried in the page text,
 * and a title that starts with the token wins over one that merely contains it. Ties keep
 * the index order, so the same query always produces the same list.
 */
export function rankEntries(entries, query, limit = SEARCH_LIMIT) {
  const tokens = tokenizeQuery(query);
  if (tokens.length === 0 || !Array.isArray(entries)) return [];

  const matches = [];
  entries.forEach((entry, order) => {
    const title = String(entry?.title ?? "").normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
    const text = String(entry?.text ?? "").normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
    const url = String(entry?.url ?? "").toLowerCase();

    let score = 0;
    for (const token of tokens) {
      let tokenScore = 0;
      if (title.startsWith(token)) tokenScore += 12;
      else if (title.includes(token)) tokenScore += 8;
      if (text.includes(token)) tokenScore += 3;
      if (url.includes(token)) tokenScore += 1;
      if (tokenScore === 0) return;
      score += tokenScore;
    }
    matches.push({ entry, order, score });
  });

  return matches
    .sort((a, b) => b.score - a.score || a.order - b.order)
    .slice(0, Math.max(0, limit))
    .map((match) => match.entry);
}

/**
 * Moves the active result for a keyboard event. Returns -1 when there is nothing to select.
 * Arrow keys stop at the ends, and no other key changes the selection.
 */
export function moveActive(current, key, count) {
  if (!Number.isInteger(count) || count <= 0) return -1;
  const index = Number.isInteger(current) ? Math.min(Math.max(current, -1), count - 1) : -1;
  switch (key) {
    case "ArrowDown":
      return index < 0 ? 0 : Math.min(index + 1, count - 1);
    case "ArrowUp":
      return index < 0 ? count - 1 : Math.max(index - 1, 0);
    default:
      return index;
  }
}

/**
 * Decides what a key press should do while search is open, so the component holds one
 * switch and the keyboard contract stays testable without a browser.
 */
export function keyAction(key, { expanded = false, active = -1, count = 0 } = {}) {
  if (key === "Escape") return expanded ? { type: "close" } : null;
  if (key === "Enter") {
    if (!expanded || count <= 0) return null;
    return { type: "open", index: Number.isInteger(active) && active >= 0 ? active : 0 };
  }
  if ((key === "ArrowDown" || key === "ArrowUp") && expanded && count > 0) {
    return { type: "move", index: moveActive(active, key, count) };
  }
  return null;
}

/** The id for one option, shared by the markup and `aria-activedescendant`. */
export function optionId(index) {
  return `site-search-option-${index}`;
}

/** The option id the combobox should point at, or undefined when nothing is active. */
export function activeOptionId(expanded, active) {
  return expanded && Number.isInteger(active) && active >= 0 ? optionId(active) : undefined;
}
