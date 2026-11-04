import { createElement as h } from "react";
import { Card, Grid, hrefFor, PageHead } from "../components/Layout.jsx";
import { Code } from "../components/Code.jsx";
import { Playground } from "../components/Playground.jsx";
import { VERSION } from "../data/meta.js";

const INSTALL =
  "curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash";

const FEATURES = [
  ["No boilerplate", "Code at file level. No class, no package, no public static void main."],
  ["Data in one line", "data Point(x, y) gives a constructor, fields, equality, hash and text."],
  ["Collections that work", ".map, .filter, .sort and .sum on every list. No streams."],
  ["Errors you can read", "Every diagnostic shows the line, a caret, an explanation and a fix."],
  ["Java when you need it", "use java.time.LocalDate, then call it with the same dot syntax."],
  ["Small surface", "20 keywords. One way to do each thing. Nothing to memorise twice."],
];

export function HomePage() {
  return h(
    "div",
    null,
    h(PageHead, {
      eyebrow: `${VERSION} · JVM · MIT`,
      title: h("span", null, "A small language", h("br", { key: "br" }), "that reads like ", h("em", { className: "accent-em" }, "pseudocode"), "."),
      lead: "Oqthara runs on the JVM and gives you every Java library, without the classes, the main, the new, the semicolons or the ceremony.",
    }),

    h(
      "div",
      { className: "install" },
      h(Code, null, INSTALL),
      h(
        "button",
        {
          type: "button",
          className: "copy-btn",
          "data-copy": INSTALL,
          onClick: (event) => {
            const target = event.currentTarget;
            navigator.clipboard?.writeText(INSTALL).then(
              () => {
                target.textContent = "Copied";
                setTimeout(() => {
                  target.textContent = "Copy";
                }, 2000);
              },
              () => {},
            );
          },
        },
        "Copy",
      ),
    ),

    h(Code, null, 'print "Hello, World!"'),

    h(Grid, null, FEATURES.map(([title, body]) => h(Card, { key: title, title }, body))),

    h("h2", null, "Playground"),
    h(
      "p",
      { className: "lead lead-tight" },
      "Edit a real program and copy it. Running needs the JVM, so this page stays honest instead of pretending to execute in the browser.",
    ),
    h(Playground, null),

    h("h2", null, "What it looks like"),
    h(Code, null, `data Point(x, y)

fun distance(a, b)
  dx = a.x - b.x
  dy = a.y - b.y
  return sqrt(dx * dx + dy * dy)
end

points = [Point(3, 4), Point(6, 8)]
for p in points
  print "distance: {distance(Point(0, 0), p)}"
end`),

    h(Code, null, `# concurrent work on virtual threads
squares = par_map([1, 2, 3, 4], x -> x * x)
print squares`),

    h("h2", null, "Start here"),
    h(
      "p",
      null,
      "The ",
      h("a", { href: hrefFor("/tour.html") }, "tour"),
      " teaches the whole language in one sitting. ",
      h("a", { href: hrefFor("/examples.html") }, "Examples"),
      " has complete programs. ",
      h("a", { href: hrefFor("/docs.html") }, "Documentation"),
      " goes deeper, and ",
      h("a", { href: hrefFor("/status.html") }, "Status"),
      " says plainly what does not work yet.",
    ),
  );
}
