// The changelog page is rendered from the repository's CHANGELOG.md at build time.
//
// These constants are the only place the route, file and source are named, so the app, the
// prerenderer and the tests cannot disagree about where the page lives or what it reads.

export const CHANGELOG_SOURCE = "CHANGELOG.md";
export const CHANGELOG_ROUTE = "/changelog.html";
export const CHANGELOG_FILE = "changelog.html";
