import { createElement as h } from "react";
import { Layout } from "./components/Layout.jsx";
import { HomePage } from "./pages/HomePage.jsx";
import { TourPage } from "./pages/TourPage.jsx";
import { DocsPage } from "./pages/DocsPage.jsx";
import { ExamplesPage } from "./pages/ExamplesPage.jsx";
import { StdlibPage } from "./pages/StdlibPage.jsx";
import { StatusPage } from "./pages/StatusPage.jsx";

// Routes are keyed by path, and every one of them is also emitted as a real HTML file by
// prerender.mjs, so GitHub Pages serves working content before any JavaScript runs.
export const ROUTES = [
  { path: "/", file: "index.html", component: HomePage },
  { path: "/tour.html", file: "tour.html", component: TourPage },
  { path: "/docs.html", file: "docs.html", component: DocsPage },
  { path: "/examples.html", file: "examples.html", component: ExamplesPage },
  { path: "/stdlib.html", file: "stdlib.html", component: StdlibPage },
  { path: "/status.html", file: "status.html", component: StatusPage },
];

export function App({ path, reference, versions = [], latest = null }) {
  const route = ROUTES.find((candidate) => candidate.path === path) ?? ROUTES[0];
  const Page = route.component;
  return h(Layout, { current: route.path }, h(Page, { reference, versions, latest }));
}
