import { createElement as h, useEffect, useState } from "react";
import { hrefFor } from "./Layout.jsx";

// The playground edits and copies real programs. It does not execute them: running Oqthara
// needs the JVM, and pretending otherwise in the browser would be dishonest. The textarea
// and the copy button are the whole feature, which is why this stayed a small component.

export const EXAMPLES = {
  hello: `print "Hello, World!"`,
  fizzbuzz: `for n in 1 to 15
  if n % 15 == 0
    print "FizzBuzz"
  else if n % 3 == 0
    print "Fizz"
  else if n % 5 == 0
    print "Buzz"
  else
    print n
  end
end`,
  records: `data Point(x, y)

p = Point(3, 4)
print "point: {p}"
print "sum of squares: {p.x * p.x + p.y * p.y}"

home = Point(0, 0)
print "same as origin: {p == home}"`,
  wordcount: `text = "the quick brown fox jumps over the lazy dog the end"
counts = {}
for word in text.split(" ")
  if word in counts
    counts[word] = counts[word] + 1
  else
    counts[word] = 1
  end
end
for word in counts.keys().sort()
  print "{word}: {counts[word]}"
end`,
  interop: `use java.time.LocalDate

start = LocalDate(2026, 1, 1)
today = LocalDate.now()
print "days since 2026-01-01: {today.toEpochDay() - start.toEpochDay()}"`,
  parallel: `squares = par_map([1, 2, 3, 4], x -> x * x)
print squares

task = spawn(fun()
  return "done on a virtual thread"
end)
print await(task)`,
};

const ORDER = [
  ["hello", "hello"],
  ["fizzbuzz", "fizzbuzz"],
  ["records", "records"],
  ["wordcount", "word count"],
  ["interop", "java interop"],
  ["parallel", "virtual threads"],
];

export function Playground() {
  // The select and textarea are controlled, so the server and client render the same
  // selected option and the same initial program.
  const [selected, setSelected] = useState("hello");
  const [code, setCode] = useState(EXAMPLES.hello);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return undefined;
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
    } catch {
      // Clipboard access can be denied; the text stays selectable, so say nothing false.
      setCopied(false);
    }
  }

  return h(
    "div",
    { className: "playground" },
    h(
      "div",
      { className: "playground-bar" },
      h("label", { htmlFor: "example-select", className: "visually-hidden" }, "Choose an example"),
      h(
        "select",
        {
          id: "example-select",
          // Controlled, so the server and the client agree on which option is selected.
          value: selected,
          onChange: (event) => {
            const next = event.target.value;
            setSelected(next);
            setCode(EXAMPLES[next]);
          },
        },
        ORDER.map(([value, label]) => h("option", { key: value, value }, label)),
      ),
      h(
        "button",
        { className: "action", type: "button", onClick: copy },
        copied ? "Copied" : "Copy program",
      ),
      h("a", { className: "action action-link", href: hrefFor("/tour.html") }, "Full tour"),
    ),
    h("textarea", {
      id: "playground-code",
      spellcheck: "false",
      "aria-label": "Oqthara program",
      value: code,
      onChange: (event) => setCode(event.target.value),
    }),
  );
}
