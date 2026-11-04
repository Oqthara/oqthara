// The playground is deliberately offline: it shows real, copyable programs and lets the
// visitor edit them, but execution needs the JVM. A WebAssembly build is evaluated in
// ADR-0004 and is not part of 0.x.
const EXAMPLES = {
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

fun length(p)
  return sqrt(p.x * p.x + p.y * p.y)
end

p = Point(3, 4)
print "length: {length(p)}"
print p`,
  wordcount: `text = "the cat and the dog and the bird"
counts = {}
for word in text.split(" ")
  counts[word] = counts.get(word, 0) + 1
end
for word in counts.keys().sort()
  print "{word}: {counts[word]}"
end`,
  interop: `use java.time.LocalDate

start = LocalDate(2026, 1, 1)
today = LocalDate.now()
print "days since start: {today.toEpochDay() - start.toEpochDay()}"`,
  parallel: `fun fetch(name)
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
};

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

function flash(button, text) {
  const original = button.textContent;
  button.textContent = text;
  setTimeout(() => { button.textContent = original; }, 1600);
}

function init() {
  const select = document.getElementById("example-select");
  const code = document.getElementById("playground-code");
  const output = document.getElementById("playground-output");

  if (select && code) {
    const load = () => {
      code.value = EXAMPLES[select.value] || "";
      if (output) {
        output.textContent =
          "Run this locally:\n\n  oq run " + select.value + ".oq\n\n" +
          "Install the CLI with the one-line command at the top of the page.";
      }
    };
    select.addEventListener("change", load);
    load();
  }

  const copyExample = document.getElementById("copy-example");
  if (copyExample && code) {
    copyExample.addEventListener("click", async () => {
      const ok = await copyText(code.value);
      flash(copyExample, ok ? "Copied" : "Select and copy");
    });
  }

  for (const button of document.querySelectorAll("[data-copy]")) {
    button.addEventListener("click", async () => {
      const ok = await copyText(button.getAttribute("data-copy"));
      flash(button, ok ? "Copied" : "Select and copy");
    });
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
