// SSR entry: renders every route to a string. `vite build --ssr` compiles this, so the
// prerender step uses exactly the same JSX transform and module graph as the client build.
import { createElement as h } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { App, ROUTES } from "./App.jsx";
import { PAGE_META } from "./data/meta.js";

const escapeHtml = (text) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

export function renderAll(reference, assets, manifest = { versions: [], latest: null }) {
  const script = assets.script;
  const stylesheet = assets.stylesheet;
  // Escaping `<` keeps the embedded JSON safe even if the reference ever contains a
  // sequence that would otherwise close the script element early.
  const embedded = JSON.stringify(reference ?? "").replace(/</g, "\\u003c");
  const embeddedVersions = JSON.stringify(manifest ?? { versions: [], latest: null })
    .replace(/</g, "\\u003c");
  return ROUTES.map((route) => {
    const meta = PAGE_META[route.path] ?? PAGE_META["/"];
    const body = renderToStaticMarkup(
      h(App, { path: route.path, reference, versions: manifest.versions ?? [], latest: manifest.latest ?? null }),
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
  <script type="module" src="${script}"></script>
</body>
</html>
`,
    };
  });
}
