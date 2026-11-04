// The playground is deliberately offline: it shows real, copyable programs and lets the
// visitor edit them, but execution needs the JVM. A WebAssembly build is evaluated in
// ADR-0004 and is not part of 0.1.x.
const EXAMPLES = {
  hello: `name = "World"\nprint "Hello, {name}!"`,
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
origin = Point(0, 0)
p = Point(3, 4)
dx = p.x - origin.x
dy = p.y - origin.y
print "distance: {sqrt(dx * dx + dy * dy)}"`,
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
};

function init() {
  const select = document.getElementById("example-select");
  const code = document.getElementById("playground-code");
  const output = document.getElementById("playground-output");
  if (!select || !code) return;

  const load = () => {
    code.value = EXAMPLES[select.value] || "";
    if (output) {
      output.textContent = "Run this locally:\n\n  oq run " + select.value + ".oq\n\n" +
        "Or install the CLI with the one-line command above.";
    }
  };
  select.addEventListener("change", load);
  load();

  const copy = document.getElementById("copy-example");
  if (copy) {
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(code.value);
        copy.textContent = "Copied";
        setTimeout(() => { copy.textContent = "Copy program"; }, 1500);
      } catch (e) {
        copy.textContent = "Select and copy manually";
      }
    });
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
