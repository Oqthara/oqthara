import { createElement as h, StrictMode } from "react";
import { createRoot, hydrateRoot } from "react-dom/client";
import { App, ROUTES } from "./App.jsx";
import { docForPath } from "./docs.js";
import { BASE_PATH } from "./data/meta.js";
import "../assets/site.css";
import "./docs.css";

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

/** Reads the documentation the prerenderer embedded, if there is one. */
function readDocs() {
  const node = document.getElementById("oq-docs");
  if (!node) return [];
  try {
    const value = JSON.parse(node.textContent || "[]");
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}

/** Reads the rendered changelog the prerenderer embedded, if there is one. */
function readChanges() {
  const node = document.getElementById("oq-changelog");
  if (!node) return null;
  try {
    return JSON.parse(node.textContent || "null");
  } catch {
    return null;
  }
}

/** Maps the current location onto a route, tolerating both `/tour` and `/tour.html`. */
function currentPath() {
  let path = window.location.pathname;
  if (path.startsWith(BASE_PATH)) path = path.slice(BASE_PATH.length - 1);
  return path.replace(/\/{2,}/g, "/") || "/";
}

const docs = readDocs();
let route = currentPath();
if (!route.endsWith(".html")) route = route.replace(/\/+$/, "") || "/";
if (!ROUTES.some((candidate) => candidate.path === route) && !docForPath(route, docs)) {
  route = "/";
}

const container = document.getElementById("root");
const { versions, latest } = readVersions();
const tree = h(
  StrictMode,
  null,
  h(App, { path: route, reference: readReference(), versions, latest, docs, changes: readChanges() }),
);

if (container.hasChildNodes()) {
  hydrateRoot(container, tree);
} else {
  createRoot(container).render(tree);
}
