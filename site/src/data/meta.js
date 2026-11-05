// Site metadata. One source of truth for the version, so no page can drift from it.

export const VERSION = process.env.OQ_VERSION || "0.1.0-alpha.1";

/**
 * The GitHub Pages project path. Both the client bundle and the prerenderer use this exact
 * value, so the markup React renders on the server matches what the browser produces.
 */
export const BASE_PATH = "/oqthara/";

export const NAV = [
  { href: "/", label: "Home" },
  { href: "/tour.html", label: "Tour" },
  { href: "/docs.html", label: "Docs" },
  { href: "/examples.html", label: "Examples" },
  { href: "/stdlib.html", label: "Stdlib" },
  { href: "/changelog.html", label: "Changelog" },
  { href: "/status.html", label: "Status" },
];

export const PAGE_META = {
  "/": {
    title: "Oqthara — a small language for the JVM",
    description:
      "Oqthara is a small language for the JVM that reads like pseudocode.",
  },
  "/tour.html": {
    title: "Tour — Oqthara",
    description:
      "Learn Oqthara in one sitting: values, text, control flow, functions, collections, data types, errors, concurrency and Java interop.",
  },
  "/docs.html": {
    title: "Documentation — Oqthara",
    description:
      "Language reference, standard library, compiler notes and decision records.",
  },
  "/examples.html": {
    title: "Examples — Oqthara",
    description: "Complete, runnable Oqthara programs.",
  },
  "/stdlib.html": {
    title: "Standard library — Oqthara",
    description: "Generated standard library reference.",
  },
  "/changelog.html": {
    title: "Changelog — Oqthara",
    description: "Every notable change to Oqthara, generated from the repository changelog.",
  },
  "/status.html": {
    title: "Status — Oqthara",
    description: "Honest status of the Oqthara language, compiler and tooling.",
  },
};
