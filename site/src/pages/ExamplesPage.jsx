import { createElement as h } from "react";
import { hrefFor, PageHead } from "../components/Layout.jsx";
import { Code } from "../components/Code.jsx";

const PROGRAMS = [
  {
    title: "FizzBuzz",
    file: "fizzbuzz.oq",
    code: `for n in 1 to 30
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
  },
  {
    title: "Word count",
    file: "wordcount.oq",
    code: `file = "README.md"
if args().length() > 0
  file = args()[0]
end

counts = {}
for word in read(file).lower().words()
  counts[word] = counts.get(word, 0) + 1
end

pairs = []
for word in counts.keys()
  pairs.push([word, counts[word]])
end

for pair in pairs.sort(p -> -p[1]).take(10)
  print "{pair[0]}: {pair[1]}"
end`,
  },
  {
    title: "Binary search tree",
    file: "bst.oq",
    code: `data Node(value, left = nothing, right = nothing)

fun insert(tree, value)
  if tree == nothing
    return Node(value)
  end
  if value < tree.value
    tree.left = insert(tree.left, value)
  else
    tree.right = insert(tree.right, value)
  end
  return tree
end

fun inorder(tree, out)
  if tree == nothing
    return out
  end
  inorder(tree.left, out)
  out.push(tree.value)
  inorder(tree.right, out)
  return out
end

tree = nothing
for v in [5, 3, 8, 1, 4, 7, 9]
  tree = insert(tree, v)
end
print inorder(tree, [])`,
  },
  {
    title: "Parallel work",
    file: "parallel.oq",
    code: `fun fetch(name)
  sleep(0.05)
  return "{name} ready"
end

tasks = []
for name in ["alpha", "beta", "gamma"]
  tasks.push(spawn(fun()
    return fetch(name)
  end))
end

for task in tasks
  print await(task)
end`,
  },
  {
    title: "Recursive-descent calculator",
    file: "calculator.oq",
    code: `fun tokenize(expr)
  tokens = []
  current = ""
  for ch in expr
    if "+-*/()".contains(ch)
      if current != ""
        tokens.push(current)
        current = ""
      end
      tokens.push(ch)
    else if ch != " "
      current = current + ch
    end
  end
  if current != ""
    tokens.push(current)
  end
  return tokens
end

print tokenize("12 + 3*(4-1)")`,
  },
];

export function ExamplesPage() {
  return h(
    "div",
    null,
    h(PageHead, {
      eyebrow: "Examples",
      title: "Complete programs",
      lead: "Real, runnable Oqthara. Every file below lives in the repository under examples/ and is executed by CI on Linux, Windows and macOS.",
    }),

    PROGRAMS.map((program) =>
      h("section", { key: program.title }, h("h2", null, program.title), h(Code, null, program.code)),
    ),

    h(
      "p",
      null,
      h(
        "a",
        { href: "https://github.com/Oqthara/oqthara/tree/main/examples" },
        "All 33 examples in the repository →",
      ),
    ),
  );
}
