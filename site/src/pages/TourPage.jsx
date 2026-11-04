import { createElement as h } from "react";
import { hrefFor, PageHead } from "../components/Layout.jsx";
import { Code } from "../components/Code.jsx";

const SECTIONS = [
  {
    id: "values",
    title: "Values and variables",
    code: `name = "Ada"          # text
age = 36             # whole number
height = 1.68        # decimal
ready = true          # boolean
nothing_here = nothing

age = age + 1       # assignment creates or updates`,
    body: h(
      "p",
      null,
      "Assignment creates the variable if it does not exist. There is no ",
      h("code", null, "let"),
      ", no ",
      h("code", null, "var"),
      " and no ",
      h("code", null, "const"),
      ", and types are always inferred.",
    ),
  },
  {
    id: "text",
    title: "Text",
    code: `greeting = "Hello, {name}!"
print greeting
print "2 + 2 = {2 + 2}"
print "literal braces: {{like this}}"

print "a,b,c".split(",")
print "  padded  ".trim()
print "abc".upper()`,
  },
  {
    id: "flow",
    title: "Conditions and loops",
    code: `if score >= 90
  print "A"
else if score >= 70
  print "B"
else
  print "C"
end

for n in 1 to 5        # inclusive
  print n
end

for n in 0 until 5     # exclusive
  print n
end

while running
  running = step()
end`,
    body: h(
      "p",
      null,
      h("code", null, "to"),
      " and ",
      h("code", null, "until"),
      " are contextual: they are range operators between two expressions, so they still work as variable and parameter names.",
    ),
  },
  {
    id: "functions",
    title: "Functions and closures",
    code: `fun add(a, b)
  return a + b
end

print add(2, 3)

double = x -> x * 2          # short lambda
add = (a, b) -> a + b

fun counter()
  n = 0
  return fun()
    n = n + 1
    return n
  end
end`,
  },
  {
    id: "collections",
    title: "Collections",
    code: `xs = [1, 2, 3]
print xs.map(x -> x * x)
print xs.filter(x -> x > 1)
print xs.sum()

m = {"a": 1, "b": 2}
print m["a"]
print m.keys().sort()

s = {1, 2, 3}
s.add(4)`,
    body: h("p", null, "Lists, maps and sets have literals and methods; there are no streams and no builders."),
  },
  {
    id: "data",
    title: "Data types",
    code: `data Point(x, y)
data Person(name, age = 0)

p = Point(3, 4)
print p.x
p.y = 5
print p

print Point(1, 2) == Point(1, 2)   # true`,
    body: h(
      "p",
      null,
      "Field defaults are evaluated per instance, so a mutable default like ",
      h("code", null, "items = []"),
      " does not leak between objects.",
    ),
  },
  {
    id: "errors",
    title: "Errors",
    code: `try
  print [1, 2][9]
catch err
  print "line {err.line}: {err.message}"
  print err.hint
end`,
    body: h(
      "p",
      null,
      "Every runtime error carries a file, line, column and a human hint. ",
      h("code", null, 'error("message")'),
      " raises your own; ",
      h("code", null, 'assert(condition, "why")'),
      " guards one.",
    ),
  },
  {
    id: "concurrency",
    title: "Concurrency",
    code: `task = spawn(fun()
  return expensive()
end)

print await(task)      # blocks, returns the value
print done(task)       # true when finished

squares = par_map([1, 2, 3], x -> x * x)`,
    body: h("p", null, "Work runs on virtual threads, so blocking is cheap and there is no pool to tune."),
  },
  {
    id: "java",
    title: "Java interop",
    code: `use java.time.LocalDate

start = LocalDate(2026, 1, 1)
today = LocalDate.now()
print "days: {today.toEpochDay() - start.toEpochDay()}"`,
    body: h(
      "p",
      null,
      h("code", null, "use"),
      " imports any class; constructors, instance methods and static methods use the same dot syntax as Oqthara values.",
    ),
  },
];

export function TourPage() {
  return h(
    "div",
    null,
    h(PageHead, {
      eyebrow: "Tour",
      title: "Learn Oqthara in one sitting",
      lead: "Everything below is real, runnable Oqthara. Start at the top and work down; nothing needs explaining twice.",
    }),

    h(
      "nav",
      { className: "toc", "aria-label": "On this page" },
      h("strong", null, "On this page"),
      h(
        "ul",
        null,
        SECTIONS.map((section) => h("li", { key: section.id }, h("a", { href: `#${section.id}` }, section.title))),
      ),
    ),

    SECTIONS.map((section) =>
      h(
        "section",
        { key: section.id },
        h("h2", { id: section.id }, section.title),
        h(Code, null, section.code),
        section.body ?? null,
      ),
    ),

    h("p", { className: "spaced" }, h("a", { href: hrefFor("/examples.html") }, "See complete programs →")),
  );
}
