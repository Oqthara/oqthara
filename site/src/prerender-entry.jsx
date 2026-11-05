// SSR entry: renders every route to a string. `vite build --ssr` compiles this, so the
// prerender step uses exactly the same JSX transform and module graph as the client build.
import { createElement as h } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { App, ROUTES } from "./App.jsx";
import { PAGE_META } from "./data/meta.js";
import { docsRoutes } from "./docs.js";
import { CHANGELOG_ROUTE } from "./changelog.js";

const escapeHtml = (text) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

const TITLE_SUFFIX = " — Oqthara";

/** Keeps page titles inside the 70-character limit html-validate enforces. */
function pageTitle(text) {
  const full = `${text}${TITLE_SUFFIX}`;
  if (full.length <= 70) return full;
  const room = 70 - TITLE_SUFFIX.length - 1;
  const cut = text.slice(0, room);
  const boundary = cut.lastIndexOf(" ");
  return `${(boundary > 30 ? cut.slice(0, boundary) : cut).trimEnd()}…${TITLE_SUFFIX}`;
}

/** Serialises JSON for a script element without letting its content close the element. */
const embedJson = (value) =>
  JSON.stringify(value ?? null).replace(/</g, "\\u003c").replace(/\u2028/g, "\\u2028");

export function renderAll(reference, assets, manifest = { versions: [], latest: null }, docs = [], changes = null) {
  const script = assets.script;
  const stylesheet = assets.stylesheet;
  // Escaping `<` keeps the embedded JSON safe even if the reference ever contains a
  // sequence that would otherwise close the script element early.
  const embedded = embedJson(reference ?? "");
  const embeddedVersions = embedJson(manifest ?? { versions: [], latest: null });

  // Only the documentation a page hydrates is embedded. The index needs titles and
  // summaries; a document page needs its own HTML. Shipping every rendered document to
  // every page would multiply the published bytes for no benefit. The changelog is
  // embedded only on its own page, exactly like a documentation page.
  const metadata = (doc) => ({
    source: doc.source,
    route: doc.route,
    file: doc.file,
    title: doc.title,
    summary: doc.summary,
  });

  const routes = [...ROUTES, ...docsRoutes(docs)];

  return routes.map((route) => {
    const routeDocs = route.doc
      ? [route.doc]
      : route.path === "/docs.html"
        ? docs.map(metadata)
        : [];
    const routeChanges = route.path === CHANGELOG_ROUTE ? changes : null;
    const embeddedDocs = embedJson(routeDocs);
    // Like a documentation page, the changelog page embeds what it rendered so the client
    // hydrates identical markup. No other page carries the changelog.
    const embeddedChangelog = embedJson(routeChanges);
    const changelogScript = routeChanges
      ? `  <script id="oq-changelog" type="application/json">${embeddedChangelog}</script>\n`
      : "";
    const meta = route.doc
      ? {
          title: pageTitle(route.doc.title),
          description: route.doc.summary || PAGE_META["/docs.html"].description,
        }
      : (PAGE_META[route.path] ?? PAGE_META["/"]);
    const body = renderToStaticMarkup(
      h(App, {
        path: route.path,
        reference,
        versions: manifest.versions ?? [],
        latest: manifest.latest ?? null,
        docs,
        changes: routeChanges,
      }),
    );
    return {
      file: route.file,
      html: `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(meta.title)}</title>
  <meta name="description" content="${escapeHtml(meta.description)}">
  <link rel="stylesheet" href="${stylesheet}">
</head>
<body>
<div id="root">${body}</div>
  <script id="oq-reference" type="application/json">${embedded}</script>
  <script id="oq-versions" type="application/json">${embeddedVersions}</script>
  <script id="oq-docs" type="application/json">${embeddedDocs}</script>
${changelogScript}  <script type="module" src="${script}"></script>
</body>
</html>
`,
    };
  });
}
