import { createElement as h, useEffect, useRef, useState } from "react";
import { BASE_PATH } from "../data/meta.js";
import { activeOptionId, keyAction, optionId, rankEntries } from "../search.js";
import "../search.css";

// The index is a static file written by prerender.mjs from the pages that were just
// rendered. Fetching it on first use keeps the initial page small and means the search
// needs no server and no runtime crawling.
const INDEX_URL = `${BASE_PATH}search-index.json`;

export function Search() {
  const [entries, setEntries] = useState(null);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const requested = useRef(false);
  const options = useRef(null);

  const results = entries === null ? [] : rankEntries(entries, query);
  const expanded = open && query.trim() !== "";

  // Keep the highlighted option visible when the list scrolls, which arrow keys alone
  // would not do once there are more results than fit in the popover.
  useEffect(() => {
    if (!expanded || active < 0 || !options.current) return;
    const node = options.current.querySelector(`#${optionId(active)}`);
    if (node && typeof node.scrollIntoView === "function") node.scrollIntoView({ block: "nearest" });
  }, [expanded, active]);

  function ensureIndex() {
    if (entries !== null || requested.current || failed) return;
    requested.current = true;
    fetch(INDEX_URL)
      .then((response) => {
        if (!response.ok) throw new Error(`search index returned ${response.status}`);
        return response.json();
      })
      .then((data) => {
        if (!Array.isArray(data)) throw new Error("search index is not an array");
        setEntries(data);
      })
      .catch(() => setFailed(true));
  }

  function onKeyDown(event) {
    const action = keyAction(event.key, { expanded, active, count: results.length });
    if (!action) return;
    event.preventDefault();
    if (action.type === "close") {
      setOpen(false);
      setActive(-1);
    } else if (action.type === "move") {
      setActive(action.index);
    } else if (action.type === "open") {
      window.location.assign(results[action.index].url);
    }
  }

  const status = !expanded
    ? null
    : failed
      ? "Search is unavailable."
      : entries === null
        ? "Loading search..."
        : results.length === 0
          ? "No matches."
          : null;

  return h(
    "div",
    {
      className: "site-search",
      role: "search",
      onBlur: (event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          setOpen(false);
          setActive(-1);
        }
      },
    },
    h("label", { htmlFor: "site-search-input", className: "visually-hidden" }, "Search the site"),
    h("input", {
      id: "site-search-input",
      type: "search",
      role: "combobox",
      placeholder: "Search",
      value: query,
      "aria-expanded": expanded ? "true" : "false",
      "aria-controls": "site-search-results",
      "aria-autocomplete": "list",
      "aria-activedescendant": activeOptionId(expanded, active),
      onFocus: () => {
        ensureIndex();
        if (query.trim() !== "") setOpen(true);
      },
      onChange: (event) => {
        ensureIndex();
        setQuery(event.target.value);
        setOpen(true);
        setActive(-1);
      },
      onKeyDown,
    }),
    h(
      "div",
      { className: "site-search-panel", hidden: expanded ? undefined : true },
      h(
        "div",
        {
          id: "site-search-results",
          role: expanded ? "listbox" : undefined,
          "aria-label": expanded ? "Search results" : undefined,
          ref: options,
        },
        results.map((entry, index) =>
          h(
            "a",
            {
              key: `${index}-${entry.url}`,
              id: optionId(index),
              className: "search-result",
              role: "option",
              "aria-selected": index === active ? "true" : "false",
              href: entry.url,
              tabIndex: -1,
              // Keeping focus on the input lets Enter continue to work and stops the
              // pointer from closing the popover before the click reaches the link.
              onMouseDown: (event) => event.preventDefault(),
              onClick: () => setOpen(false),
            },
            h("span", { className: "search-title" }, entry.title),
            entry.page && entry.page !== entry.title ? h("span", { className: "search-page" }, entry.page) : null,
          ),
        ),
      ),
      status ? h("p", { className: "search-status", role: "status" }, status) : null,
    ),
  );
}
