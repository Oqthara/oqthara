import { createElement as h, useState } from "react";
import { VERSION } from "../data/meta.js";
import { hrefFor } from "./Layout.jsx";

// The version selector is generated from the manifest that scripts/build-versioned-docs.py
// writes from the real release tags, so it can never advertise a version whose documentation
// was not published alongside it. When no release has been tagged yet it renders nothing
// rather than offering a dead link.

export function VersionSelect({ versions = [], latest = null }) {
  if (!versions.length) return null;
  // The page being read is the development documentation, always. A version whose tag
  // happens to equal the current VERSION is still a snapshot of released docs, not this
  // page, so it must not be preselected.
  const [selected, setSelected] = useState("dev");

  return h(
    "div",
    { className: "version-select" },
    h("label", { htmlFor: "version-select", className: "visually-hidden" }, "Documentation version"),
    h(
      "select",
      {
        id: "version-select",
        value: selected,
        onChange: (event) => {
          const value = event.target.value;
          setSelected(value);
          window.location.href =
            value === "dev" ? hrefFor("/docs.html") : hrefFor(`/versions/${value}/index.html`);
        },
      },
      h("option", { value: "dev" }, `Development (${VERSION})`),
      versions.map((entry) =>
        h("option", { key: entry.tag, value: entry.tag.replace(/^v/, "") }, entry.tag),
      ),
    ),
    latest
      ? h(
          "p",
          { className: "version-hint" },
          "You are reading development documentation. The newest release is ",
          h("strong", null, latest),
          ".",
        )
      : null,
  );
}
