# Oqthara examples

Every program here runs with the released CLI:

```bash
java -jar oq.jar examples/hello.oq
```

| File | What it shows |
|---|---|
| `hello.oq` | the one-line hello world |
| `fizzbuzz.oq` | conditionals and ranges |
| `records.oq` | `data` and methods on records |
| `collections.oq` | list methods and lambdas |
| `closures.oq` | lexical closures with mutable state |
| `fibonacci.oq` | recursion and a memoised version |
| `json.oq` | parsing and re-serialising JSON |
| `interop.oq` | calling `java.time.LocalDate` directly |
| `wordcount.oq` | reading a file and ranking words |
| `grep.oq` | a small CLI with arguments and exit codes |
| `todo.oq` | mutating records in a list |
| `csv.oq` | parsing text and formatting a report |
| `tokenizer.oq` | splitting an expression into tokens |
| `calculator.oq` | recursion, precedence and a parser over tokens |
| `rpn.oq` | a stack machine using a list |
| `frequency.oq` | character frequency with multi-key sorting |
| `maze.oq` | breadth-first search on a grid |
| `matrix.oq` | nested lists and numeric loops |
| `sudoku.oq` | validation with sets and nested loops |
| `state_machine.oq` | a map-driven state machine |
| `text_stats.oq` | text statistics over a file |
| `logfilter.oq` | structured log filtering and counting |
| `inventory.oq` | grouping and aggregation over records |
| `guess.oq` | interactive input with `try`/`catch` |
| `parallel.oq` | `spawn`/`await`/`par_map` on virtual threads |
| `tests/` | the language-level test suite for `oq test` |

`guess.oq` is interactive; the rest are deterministic and safe to run in CI.
