import { createElement as h, StrictMode } from "react";
import { createRoot, hydrateRoot } from "react-dom/client";
import { App, ROUTES } from "./App.jsx";
import { BASE_PATH } from "./data/meta.js";
import "../assets/site.css";

// GitHub Pages serves prerendered HTML, so this hydrates rather than replacing the DOM.
// Every route works without JavaScript; the client only takes over for the playground.

/** Reads the generated stdlib reference the prerenderer embedded in the page. */
function readReference() {
  const node = document.getElementById("oq-reference");
  if (!node) return "";
  try {
    return JSON.parse(node.textContent || '""');
  } catch {
    return "";
  }
}

/** Maps the current location onto a route, tolerating both `/tour` and `/tour.html`. */
function currentRoute() {
  let path = window.location.pathname;
  if (path.startsWith(BASE_PATH)) path = path.slice(BASE_PATH.length - 1);
  const cleaned = path.replace(/\/+$/, "") || "/";
  return ROUTES.find((route) => route.path === cleaned) ?? ROUTES[0];
}

/** Reads the version manifest embedded by the prerenderer, if there is one. */
function readVersions() {
  const node = document.getElementById("oq-versions");
  if (!node) return { versions: [], latest: null };
  try {
    return JSON.parse(node.textContent || "{}");
  } catch {
    return { versions: [], latest: null };
  }
}

const route = currentRoute();
const container = document.getElementById("root");
const { versions, latest } = readVersions();
const tree = h(
  StrictMode,
  null,
  h(App, { path: route.path, reference: readReference(), versions, latest }),
);

if (container.hasChildNodes()) {
  hydrateRoot(container, tree);
} else {
  createRoot(container).render(tree);
}
