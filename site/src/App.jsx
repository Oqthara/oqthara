import { createElement as h } from "react";
import { Layout } from "./components/Layout.jsx";
import { HomePage } from "./pages/HomePage.jsx";
import { TourPage } from "./pages/TourPage.jsx";
import { DocsPage } from "./pages/DocsPage.jsx";
import { DocPage } from "./pages/DocPage.jsx";
import { ExamplesPage } from "./pages/ExamplesPage.jsx";
import { StdlibPage } from "./pages/StdlibPage.jsx";
import { StatusPage } from "./pages/StatusPage.jsx";
import { ChangelogPage } from "./pages/ChangelogPage.jsx";
import { docForPath } from "./docs.js";
import { CHANGELOG_FILE, CHANGELOG_ROUTE } from "./changelog.js";

// Routes are keyed by path, and every one of them is also emitted as a real HTML file by
// prerender.mjs, so GitHub Pages serves working content before any JavaScript runs.
// Documentation pages are derived from `docs/` at build time, not listed here.
export const ROUTES = [
  { path: "/", file: "index.html", component: HomePage },
  { path: "/tour.html", file: "tour.html", component: TourPage },
  { path: "/docs.html", file: "docs.html", component: DocsPage },
  { path: "/examples.html", file: "examples.html", component: ExamplesPage },
  { path: "/stdlib.html", file: "stdlib.html", component: StdlibPage },
  { path: CHANGELOG_ROUTE, file: CHANGELOG_FILE, component: ChangelogPage },
  { path: "/status.html", file: "status.html", component: StatusPage },
];

export function App({ path, reference, versions = [], latest = null, docs = [], changes = null }) {
  const route = ROUTES.find((candidate) => candidate.path === path);
  if (route) {
    const Page = route.component;
    return h(Layout, { current: route.path }, h(Page, { reference, versions, latest, docs, changes }));
  }

  const doc = docForPath(path, docs);
  if (doc) {
    return h(
      Layout,
      { current: "/docs.html" },
      h(DocPage, { doc, versions, latest }),
    );
  }

  return h(Layout, { current: "/" }, h(HomePage, { versions, latest, docs }));
}
