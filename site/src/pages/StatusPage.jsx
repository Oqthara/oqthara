import { createElement as h } from "react";
import { Note, PageHead, Table } from "../components/Layout.jsx";

const WORKS = [
  ["Lexer, parser, interpreter", "Complete for the documented language"],
  ["Diagnostics", "File, line, column, caret and a hint on every user error"],
  ["Collections and text", "Rich method surface; no streams or builders"],
  ["Modules", "use \"oq:name\", loaded once, cycle-safe"],
  ["Concurrency", "spawn, await, par_map on virtual threads"],
  ["Java interop", "Constructors, instance and static methods"],
  ["CLI", "run, check, repl, fmt, test, new, doc, build"],
  ["Compiled jars", "Real class files; real bytecode for functions, arithmetic, variables and control flow"],
  ["Fuzzing", "Seven Jazzer targets, about four million cases per run"],
  ["CI", "Linux, Windows and macOS × JDK 21 and 25, plus fuzzing"],
];

const GAPS = [
  ["Bytecode for closures", "Functions that read an enclosing variable, lambdas and callbacks fall back to the launcher jar"],
  ["Bytecode for collections", "Lists, maps, sets and data types fall back to the launcher jar"],
  ["Pattern matching", "Not implemented"],
  ["Type annotations", "Not implemented; inference only"],
  ["Language server", "Not started"],
  ["In-browser execution", "The playground copies programs; it does not run them"],
];

export function StatusPage() {
  return h(
    "div",
    null,
    h(PageHead, {
      eyebrow: "Status",
      title: "Where Oqthara stands",
      lead: "Honest status, including what does not work yet. The project is pre-1.0 and the compiler backend is incomplete by design.",
    }),

    h("h2", null, "What works"),
    h(Table, { head: ["Area", "State"], rows: WORKS }),

    h("h2", null, "What does not work yet"),
    h(Table, { head: ["Gap", "Detail"], rows: GAPS }),

    h(
      Note,
      null,
      "Oqthara is ",
      h("strong", null, "pre-1.0"),
      ". The language surface may change between minor versions, and the compiled backend is explicitly a work in progress. Do not treat this site as a promise of API stability.",
    ),

    h("h2", null, "Release policy"),
    h(
      "p",
      null,
      "Pre-releases are tagged ",
      h("code", null, "alpha"),
      ", ",
      h("code", null, "beta"),
      " or ",
      h("code", null, "rc"),
      ". A stable release requires green CI on every OS and JDK, 100% conformance on both backends, no open P0 or P1 bugs, updated docs and verified artifacts. The bytecode backend is not yet at that bar, so no stable release exists.",
    ),

    h("p", null, h("a", { href: "https://github.com/Oqthara/oqthara/releases" }, "Releases on GitHub →")),
  );
}
