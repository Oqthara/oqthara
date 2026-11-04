import { readFileSync, writeFileSync, mkdirSync, cpSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = here;
const out = join(here, "_site");
const VERSION = process.env.OQ_VERSION || "0.1.0-alpha.1";

const nav = [
  ["index.html", "Home"],
  ["tour.html", "Tour"],
  ["docs.html", "Docs"],
  ["examples.html", "Examples"],
  ["stdlib.html", "Stdlib"],
  ["status.html", "Status"],
];

function layout({ title, description, current, body }) {
  const links = nav
    .map(([href, label]) => {
      const currentAttr = href === current ? ' aria-current="page"' : "";
      return `<a href="${href}"${currentAttr}>${label}</a>`;
    })
    .join("\n        ");
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${title}</title>
  <meta name="description" content="${description}">
  <link rel="stylesheet" href="assets/site.css">
</head>
<body>
  <a class="skip-link" href="#main">Skip to content</a>
  <header class="site">
    <div class="wrap">
      <a class="brand" href="index.html">oq<span>thara</span></a>
      <nav class="site" aria-label="Main">
        ${links}
      </nav>
    </div>
  </header>
  <main id="main">
    <div class="wrap">
${body}
    </div>
  </main>
  <footer class="site">
    <div class="wrap">
      <span>Oqthara ${VERSION} · MIT · built on the JVM</span>
      <span><a href="https://github.com/Oqthara/oqthara">GitHub</a> · <a href="https://github.com/Oqthara/oqthara/releases">Releases</a></span>
    </div>
  </footer>
  <script src="playground.js"></script>
</body>
</html>
`;
}

const home = `
<section>
  <p class="eyebrow">${VERSION} · JVM · MIT</p>
  <h1>A small language<br>that reads like <em class="accent-em">pseudocode</em>.</h1>
  <p class="lead">Oqthara runs on the JVM and gives you every Java library, without the classes,
  the <code>main</code>, the <code>new</code>, the semicolons or the ceremony.</p>

  <div class="install">
    <pre><code>curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash</code></pre>
    <button type="button" class="copy-btn" data-copy="curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash">Copy</button>
  </div>

  <pre><code><span class="fn">print</span> <span class="str">"Hello, World!"</span></code></pre>

  <div class="grid">
    <div class="card"><h3>No boilerplate</h3><p>Code at file level. No class, no package, no <code>public static void main</code>.</p></div>
    <div class="card"><h3>Data in one line</h3><p><code>data Point(x, y)</code> gives a constructor, fields, equality, hash and text.</p></div>
    <div class="card"><h3>Collections that work</h3><p><code>.map</code>, <code>.filter</code>, <code>.sort</code>, <code>.sum</code> on every list. No streams.</p></div>
    <div class="card"><h3>Errors you can read</h3><p>Every diagnostic shows the line, a caret, an explanation and a fix.</p></div>
    <div class="card"><h3>Java when you need it</h3><p><code>use java.time.LocalDate</code>, then call it with the same dot syntax.</p></div>
    <div class="card"><h3>Small surface</h3><p>20 keywords. One way to do each thing. Nothing to memorise twice.</p></div>
  </div>

  <h2>Playground</h2>
  <p class="lead lead-tight">Edit a real program and copy it. Running needs the JVM,
  so this page stays honest instead of pretending to execute in the browser.</p>
  <div class="playground">
    <div class="playground-bar">
      <label for="example-select" class="visually-hidden">Choose an example</label>
      <select id="example-select">
        <option value="hello">hello</option>
        <option value="fizzbuzz">fizzbuzz</option>
        <option value="records">records</option>
        <option value="wordcount">word count</option>
        <option value="interop">java interop</option>
        <option value="parallel">virtual threads</option>
      </select>
      <button class="action" id="copy-example" type="button">Copy program</button>
      <a class="action action-link" href="tour.html">Full tour</a>
    </div>
    <textarea id="playground-code" spellcheck="false" aria-label="Oqthara program"></textarea>
    <pre id="playground-output" class="playground-output"></pre>
  </div>

  <h2>What it looks like</h2>
  <pre><code><span class="kw">data</span> Point(x, y)

<span class="kw">fun</span> <span class="fn">distance</span>(a, b)
  dx = a.x - b.x
  dy = a.y - b.y
  <span class="kw">return</span> sqrt(dx * dx + dy * dy)
<span class="kw">end</span>

points = [Point(3, 4), Point(6, 8)]
<span class="kw">for</span> p <span class="kw">in</span> points
  <span class="fn">print</span> <span class="str">"distance: {distance(Point(0, 0), p)}"</span>
<span class="kw">end</span></code></pre>

  <pre><code><span class="com"># concurrent work on virtual threads</span>
task = <span class="fn">spawn</span>(<span class="kw">fun</span>()
  <span class="kw">return</span> fetch(<span class="str">"https://example.com"</span>)
<span class="kw">end</span>)
<span class="fn">print</span> <span class="fn">await</span>(task)

<span class="kw">try</span>
  data = <span class="fn">json_parse</span>(<span class="str">"{not json}"</span>)
<span class="kw">catch</span> err
  <span class="fn">print</span> <span class="str">"line {err.line}: {err.message}"</span>
<span class="kw">end</span></code></pre>

  <h2>Java, simplified</h2>
  <table>
    <thead><tr><th>Java</th><th>Oqthara</th></tr></thead>
    <tbody>
      <tr><td><code>public static void main(String[] args)</code></td><td>nothing</td></tr>
      <tr><td><code>new ArrayList&lt;&gt;()</code></td><td><code>[]</code></td></tr>
      <tr><td><code>list.stream().map(x -&gt; x * 2).toList()</code></td><td><code>list.map(x -&gt; x * 2)</code></td></tr>
      <tr><td>getters, setters, equals, hashCode, toString</td><td><code>data Point(x, y)</code></td></tr>
      <tr><td><code>String.format("Hi %s", name)</code></td><td><code>"Hi {name}"</code></td></tr>
      <tr><td>checked exceptions</td><td>none</td></tr>
    </tbody>
  </table>

  <h2>Start here</h2>
  <pre><code>oq new hello      <span class="com"># create a project</span>
oq hello/main.oq  <span class="com"># run it</span>
oq repl           <span class="com"># try things</span>
oq fmt file.oq    <span class="com"># format, idempotently</span>
oq test           <span class="com"># run test_*.oq files</span>
oq build file.oq  <span class="com"># produce an executable jar</span>
oq doc .          <span class="com"># regenerate the stdlib reference</span></code></pre>

  <p><a href="tour.html">Take the tour →</a></p>
</section>
`;

const tour = `
<section>
  <p class="eyebrow">Tour</p>
  <h1>Learn Oqthara in one sitting</h1>
  <p class="lead">Everything below is real, runnable Oqthara. Start at the top and work down;
  nothing needs explaining twice.</p>

  <nav class="toc" aria-label="On this page">
    <strong>On this page</strong>
    <ul>
      <li><a href="#values">Values and variables</a></li>
      <li><a href="#text">Text</a></li>
      <li><a href="#flow">Conditions and loops</a></li>
      <li><a href="#functions">Functions and closures</a></li>
      <li><a href="#collections">Collections</a></li>
      <li><a href="#data">Data types</a></li>
      <li><a href="#errors">Errors</a></li>
      <li><a href="#concurrency">Concurrency</a></li>
      <li><a href="#java">Java interop</a></li>
    </ul>
  </nav>

  <h2 id="values">Values and variables</h2>
  <pre><code>name = <span class="str">"Ada"</span>          <span class="com"># text</span>
age = <span class="num">36</span>             <span class="com"># whole number</span>
height = <span class="num">1.68</span>        <span class="com"># decimal</span>
ready = <span class="kw">true</span>          <span class="com"># boolean</span>
nothing_here = <span class="kw">nothing</span>

age = age + <span class="num">1</span>       <span class="com"># assignment creates or updates</span></code></pre>
  <p>Assignment creates the variable if it does not exist. There is no <code>let</code>, no
  <code>var</code> and no <code>const</code>, and types are always inferred.</p>

  <h2 id="text">Text</h2>
  <pre><code>greeting = <span class="str">"Hello, {name}!"</span>
<span class="fn">print</span> greeting
<span class="fn">print</span> <span class="str">"2 + 2 = {2 + 2}"</span>
<span class="fn">print</span> <span class="str">"literal braces: {{like this}}"</span>

<span class="fn">print</span> <span class="str">"a,b,c"</span>.split(<span class="str">","</span>)
<span class="fn">print</span> <span class="str">"  padded  "</span>.trim()
<span class="fn">print</span> <span class="str">"abc"</span>.upper()</code></pre>

  <h2 id="flow">Conditions and loops</h2>
  <pre><code><span class="kw">if</span> score &gt;= <span class="num">90</span>
  <span class="fn">print</span> <span class="str">"A"</span>
<span class="kw">else if</span> score &gt;= <span class="num">70</span>
  <span class="fn">print</span> <span class="str">"B"</span>
<span class="kw">else</span>
  <span class="fn">print</span> <span class="str">"C"</span>
<span class="kw">end</span>

<span class="kw">for</span> n <span class="kw">in</span> <span class="num">1</span> to <span class="num">5</span>        <span class="com"># inclusive</span>
  <span class="fn">print</span> n
<span class="kw">end</span>

<span class="kw">for</span> n <span class="kw">in</span> <span class="num">0</span> until <span class="num">5</span>     <span class="com"># exclusive</span>
  <span class="fn">print</span> n
<span class="kw">end</span>

<span class="kw">while</span> running
  running = step()
<span class="kw">end</span></code></pre>
  <p><code>to</code> and <code>until</code> are contextual: they are range operators between two
  expressions, so they still work as variable and parameter names.</p>

  <h2 id="functions">Functions and closures</h2>
  <pre><code><span class="kw">fun</span> <span class="fn">add</span>(a, b)
  <span class="kw">return</span> a + b
<span class="kw">end</span>

<span class="fn">print</span> add(<span class="num">2</span>, <span class="num">3</span>)

double = x -&gt; x * <span class="num">2</span>          <span class="com"># short lambda</span>
add = (a, b) -&gt; a + b

<span class="kw">fun</span> <span class="fn">counter</span>()
  n = <span class="num">0</span>
  <span class="kw">return</span> <span class="kw">fun</span>()
    n = n + <span class="num">1</span>
    <span class="kw">return</span> n
  <span class="kw">end</span>
<span class="kw">end</span></code></pre>

  <h2 id="collections">Collections</h2>
  <pre><code>xs = [<span class="num">1</span>, <span class="num">2</span>, <span class="num">3</span>]
<span class="fn">print</span> xs.map(x -&gt; x * x)
<span class="fn">print</span> xs.filter(x -&gt; x &gt; <span class="num">1</span>)
<span class="fn">print</span> xs.sum()

m = {<span class="str">"a"</span>: <span class="num">1</span>, <span class="str">"b"</span>: <span class="num">2</span>}
<span class="fn">print</span> m[<span class="str">"a"</span>]
<span class="fn">print</span> m.keys().sort()

s = {<span class="num">1</span>, <span class="num">2</span>, <span class="num">3</span>}
s.add(<span class="num">4</span>)</code></pre>
  <p>Lists, maps and sets have literals and methods; there are no streams and no builders.</p>

  <h2 id="data">Data types</h2>
  <pre><code><span class="kw">data</span> Point(x, y)
<span class="kw">data</span> Person(name, age = <span class="num">0</span>)

p = Point(<span class="num">3</span>, <span class="num">4</span>)
<span class="fn">print</span> p.x
p.y = <span class="num">5</span>
<span class="fn">print</span> p

<span class="fn">print</span> Point(<span class="num">1</span>, <span class="num">2</span>) == Point(<span class="num">1</span>, <span class="num">2</span>)   <span class="com"># true</span></code></pre>
  <p>Field defaults are evaluated per instance, so a mutable default like <code>items = []</code>
  does not leak between objects.</p>

  <h2 id="errors">Errors</h2>
  <pre><code><span class="kw">try</span>
  <span class="fn">print</span> [<span class="num">1</span>, <span class="num">2</span>][<span class="num">9</span>]
<span class="kw">catch</span> err
  <span class="fn">print</span> <span class="str">"line {err.line}: {err.message}"</span>
  <span class="fn">print</span> err.hint
<span class="kw">end</span></code></pre>
  <p>Every runtime error carries a file, line, column and a human hint. <code>error("message")</code>
  raises your own; <code>assert(condition, "why")</code> guards one.</p>

  <h2 id="concurrency">Concurrency</h2>
  <pre><code>task = <span class="fn">spawn</span>(<span class="kw">fun</span>()
  <span class="kw">return</span> expensive()
<span class="kw">end</span>)

<span class="fn">print</span> <span class="fn">await</span>(task)      <span class="com"># blocks, returns the value</span>
<span class="fn">print</span> <span class="fn">done</span>(task)       <span class="com"># true when finished</span>

squares = <span class="fn">par_map</span>([<span class="num">1</span>, <span class="num">2</span>, <span class="num">3</span>], x -&gt; x * x)</code></pre>
  <p>Work runs on virtual threads, so blocking is cheap and there is no pool to tune.</p>

  <h2 id="java">Java interop</h2>
  <pre><code><span class="kw">use</span> java.time.LocalDate

start = LocalDate(<span class="num">2026</span>, <span class="num">1</span>, <span class="num">1</span>)
today = LocalDate.now()
<span class="fn">print</span> <span class="str">"days: {today.toEpochDay() - start.toEpochDay()}"</span></code></pre>
  <p><code>use</code> imports any class; constructors, instance methods and static methods use the
  same dot syntax as Oqthara values.</p>

  <p class="spaced"><a href="examples.html">See complete programs →</a></p>
</section>
`;

const docs = `
<section>
  <p class="eyebrow">Documentation</p>
  <h1>Documentation</h1>
  <p class="lead">Reference material for the language, the CLI, the compiler and the standard
  library. Everything here describes what is implemented today.</p>

  <div class="grid">
    <a class="card card-link" href="https://github.com/Oqthara/oqthara/blob/main/docs/spec/language.md">
      <h3>Language reference</h3><p>Every construct, operator, method and built-in.</p>
    </a>
    <a class="card card-link" href="stdlib.html">
      <h3>Standard library</h3><p>Generated from the implementation, so it cannot drift.</p>
    </a>
    <a class="card card-link" href="https://github.com/Oqthara/oqthara/blob/main/docs/spec/backend.md">
      <h3>Compiler and JVM backend</h3><p>What compiles, what falls back, and why.</p>
    </a>
    <a class="card card-link" href="https://github.com/Oqthara/oqthara/blob/main/docs/spec/fuzzing.md">
      <h3>Fuzzing</h3><p>Jazzer targets, the regression corpus and findings.</p>
    </a>
    <a class="card card-link" href="https://github.com/Oqthara/oqthara/tree/main/docs/adr">
      <h3>Decision records</h3><p>Why the syntax, the CI split and the class-file writer look like this.</p>
    </a>
    <a class="card card-link" href="https://github.com/Oqthara/oqthara/blob/main/CONTRIBUTING.md">
      <h3>Contributing</h3><p>How to add a feature, a conformance case or a fuzz target.</p>
    </a>
  </div>

  <h2>The oq command</h2>
  <table>
    <thead><tr><th>Command</th><th>What it does</th></tr></thead>
    <tbody>
      <tr><td><code>oq file.oq</code></td><td>Run a program</td></tr>
      <tr><td><code>oq run file.oq</code></td><td>Same, explicit</td></tr>
      <tr><td><code>oq check file.oq</code></td><td>Parse without running</td></tr>
      <tr><td><code>oq repl</code></td><td>Interactive session</td></tr>
      <tr><td><code>oq fmt file.oq</code></td><td>Canonical, idempotent formatting</td></tr>
      <tr><td><code>oq test [dir]</code></td><td>Run <code>test_*.oq</code> and <code>tests/</code></td></tr>
      <tr><td><code>oq new name</code></td><td>Create a project</td></tr>
      <tr><td><code>oq build file.oq</code></td><td>Produce an executable jar</td></tr>
      <tr><td><code>oq build file.oq --emit-ir</code></td><td>Print the intermediate representation</td></tr>
      <tr><td><code>oq doc .</code></td><td>Regenerate the stdlib reference</td></tr>
      <tr><td><code>oq version</code></td><td>Show the version</td></tr>
    </tbody>
  </table>

  <h2>Installation</h2>
  <pre><code><span class="com"># macOS and Linux</span>
curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash

<span class="com"># Windows PowerShell</span>
irm https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.ps1 | iex

<span class="com"># Docker</span>
docker run --rm -v "$PWD:/work" oqthara/oq:latest hello.oq</code></pre>

  <div class="note">The compiled-jar backend is a <strong>launcher</strong> for the interpreter
  for most programs, and emits real bytecode for the arithmetic/variable subset. This is stated
  plainly in the compiler documentation rather than implied to be more than it is.</div>
</section>
`;

const examples = `
<section>
  <p class="eyebrow">Examples</p>
  <h1>Complete programs</h1>
  <p class="lead">Real, runnable Oqthara. Every file below lives in the repository under
  <code>examples/</code> and is executed by CI on Linux, Windows and macOS.</p>

  <h2>FizzBuzz</h2>
  <pre><code><span class="kw">for</span> n <span class="kw">in</span> <span class="num">1</span> to <span class="num">30</span>
  <span class="kw">if</span> n % <span class="num">15</span> == <span class="num">0</span>
    <span class="fn">print</span> <span class="str">"FizzBuzz"</span>
  <span class="kw">else if</span> n % <span class="num">3</span> == <span class="num">0</span>
    <span class="fn">print</span> <span class="str">"Fizz"</span>
  <span class="kw">else if</span> n % <span class="num">5</span> == <span class="num">0</span>
    <span class="fn">print</span> <span class="str">"Buzz"</span>
  <span class="kw">else</span>
    <span class="fn">print</span> n
  <span class="kw">end</span>
<span class="kw">end</span></code></pre>

  <h2>Word count</h2>
  <pre><code>file = <span class="str">"README.md"</span>
<span class="kw">if</span> args().length() &gt; <span class="num">0</span>
  file = args()[<span class="num">0</span>]
<span class="kw">end</span>

counts = {}
<span class="kw">for</span> word <span class="kw">in</span> read(file).lower().words()
  counts[word] = counts.get(word, <span class="num">0</span>) + <span class="num">1</span>
<span class="kw">end</span>

pairs = []
<span class="kw">for</span> word <span class="kw">in</span> counts.keys()
  pairs.push([word, counts[word]])
<span class="kw">end</span>

<span class="kw">for</span> pair <span class="kw">in</span> pairs.sort(p -&gt; -p[<span class="num">1</span>]).take(<span class="num">10</span>)
  <span class="fn">print</span> <span class="str">"{pair[0]}: {pair[1]}"</span>
<span class="kw">end</span></code></pre>

  <h2>Binary search tree</h2>
  <pre><code><span class="kw">data</span> Node(value, left = <span class="kw">nothing</span>, right = <span class="kw">nothing</span>)

<span class="kw">fun</span> <span class="fn">insert</span>(tree, value)
  <span class="kw">if</span> tree == <span class="kw">nothing</span>
    <span class="kw">return</span> Node(value)
  <span class="kw">end</span>
  <span class="kw">if</span> value &lt; tree.value
    tree.left = insert(tree.left, value)
  <span class="kw">else</span>
    tree.right = insert(tree.right, value)
  <span class="kw">end</span>
  <span class="kw">return</span> tree
<span class="kw">end</span>

<span class="kw">fun</span> <span class="fn">inorder</span>(tree, out)
  <span class="kw">if</span> tree == <span class="kw">nothing</span>
    <span class="kw">return</span> out
  <span class="kw">end</span>
  inorder(tree.left, out)
  out.push(tree.value)
  inorder(tree.right, out)
  <span class="kw">return</span> out
<span class="kw">end</span>

tree = <span class="kw">nothing</span>
<span class="kw">for</span> v <span class="kw">in</span> [<span class="num">5</span>, <span class="num">3</span>, <span class="num">8</span>, <span class="num">1</span>, <span class="num">4</span>, <span class="num">7</span>, <span class="num">9</span>]
  tree = insert(tree, v)
<span class="kw">end</span>
<span class="fn">print</span> inorder(tree, [])</code></pre>

  <h2>Parallel work</h2>
  <pre><code><span class="kw">fun</span> <span class="fn">fetch</span>(name)
  sleep(<span class="num">0.05</span>)
  <span class="kw">return</span> <span class="str">"{name} ready"</span>
<span class="kw">end</span>

tasks = []
<span class="kw">for</span> name <span class="kw">in</span> [<span class="str">"alpha"</span>, <span class="str">"beta"</span>, <span class="str">"gamma"</span>]
  tasks.push(<span class="fn">spawn</span>(<span class="kw">fun</span>()
    <span class="kw">return</span> fetch(name)
  <span class="kw">end</span>))
<span class="kw">end</span>

<span class="kw">for</span> task <span class="kw">in</span> tasks
  <span class="fn">print</span> <span class="fn">await</span>(task)
<span class="kw">end</span></code></pre>

  <h2>Recursive-descent calculator</h2>
  <pre><code><span class="kw">fun</span> <span class="fn">tokenize</span>(expr)
  tokens = []
  current = <span class="str">""</span>
  <span class="kw">for</span> ch <span class="kw">in</span> expr
    <span class="kw">if</span> <span class="str">"+-*/()"</span>.contains(ch)
      <span class="kw">if</span> current != <span class="str">""</span>
        tokens.push(current)
        current = <span class="str">""</span>
      <span class="kw">end</span>
      tokens.push(ch)
    <span class="kw">else if</span> ch != <span class="str">" "</span>
      current = current + ch
    <span class="kw">end</span>
  <span class="kw">end</span>
  <span class="kw">if</span> current != <span class="str">""</span>
    tokens.push(current)
  <span class="kw">end</span>
  <span class="kw">return</span> tokens
<span class="kw">end</span>

<span class="fn">print</span> tokenize(<span class="str">"12 + 3*(4-1)"</span>)</code></pre>

  <p><a href="https://github.com/Oqthara/oqthara/tree/main/examples">All 33 examples in the repository →</a></p>
</section>
`;

const status = `
<section>
  <p class="eyebrow">Status</p>
  <h1>Where Oqthara stands</h1>
  <p class="lead">Honest status, including what does not work yet. The project is pre-1.0 and
  the compiler backend is incomplete by design.</p>

  <h2>What works</h2>
  <table>
    <thead><tr><th>Area</th><th>State</th></tr></thead>
    <tbody>
      <tr><td>Lexer, parser, interpreter</td><td>Complete for the documented language</td></tr>
      <tr><td>Diagnostics</td><td>File, line, column, caret and a hint on every user error</td></tr>
      <tr><td>Collections and text</td><td>Rich method surface; no streams or builders</td></tr>
      <tr><td>Modules</td><td><code>use "oq:name"</code>, loaded once, cycle-safe</td></tr>
      <tr><td>Concurrency</td><td><code>spawn</code>, <code>await</code>, <code>par_map</code> on virtual threads</td></tr>
      <tr><td>Java interop</td><td>Constructors, instance and static methods</td></tr>
      <tr><td>CLI</td><td>run, check, repl, fmt, test, new, doc, build</td></tr>
      <tr><td>Compiled jars</td><td>Real class files; real bytecode for arithmetic and variables</td></tr>
      <tr><td>Fuzzing</td><td>Seven Jazzer targets, about four million cases per run</td></tr>
      <tr><td>CI</td><td>Linux, Windows and macOS × JDK 21 and 25, plus fuzzing</td></tr>
    </tbody>
  </table>

  <h2>What does not work yet</h2>
  <table>
    <thead><tr><th>Gap</th><th>Detail</th></tr></thead>
    <tbody>
      <tr><td>Bytecode for control flow</td><td><code>if</code>, loops and functions fall back to the launcher jar</td></tr>
      <tr><td>Pattern matching</td><td>Not implemented</td></tr>
      <tr><td>Type annotations</td><td>Not implemented; inference only</td></tr>
      <tr><td>Language server</td><td>Not started</td></tr>
      <tr><td>In-browser execution</td><td>The playground copies programs; it does not run them</td></tr>
    </tbody>
  </table>

  <div class="note">Oqthara is <strong>pre-1.0</strong>. The language surface may change between
  minor versions, and the compiled backend is explicitly a work in progress. Do not treat this
  site as a promise of API stability.</div>

  <h2>Release policy</h2>
  <p>Pre-releases are tagged <code>alpha</code>, <code>beta</code> or <code>rc</code>. A stable
  release requires green CI on every OS and JDK, 100% conformance on both backends, no open P0
  or P1 bugs, updated docs and verified artifacts. The bytecode backend is not yet at that bar,
  so no stable release exists.</p>

  <p><a href="https://github.com/Oqthara/oqthara/releases">Releases on GitHub →</a></p>
</section>
`;

const stdlib = `
<section>
  <p class="eyebrow">Standard library</p>
  <h1>Standard library</h1>
  <p class="lead">Generated from the implementation by <code>oq doc</code>. If a function is
  listed here, it exists; CI fails when this page is out of date.</p>
  <div id="stdlib-content">
    <p><a href="stdlib.md">Open the generated reference →</a></p>
  </div>
</section>
`;

const pages = [
  { file: "index.html", title: "Oqthara — a small language for the JVM", description: "Oqthara is a small language for the JVM that reads like pseudocode.", current: "index.html", body: home },
  { file: "tour.html", title: "Tour — Oqthara", description: "Learn Oqthara in one sitting: values, text, control flow, functions, collections, data types, errors, concurrency and Java interop.", current: "tour.html", body: tour },
  { file: "docs.html", title: "Documentation — Oqthara", description: "Language reference, standard library, compiler notes and decision records.", current: "docs.html", body: docs },
  { file: "examples.html", title: "Examples — Oqthara", description: "Complete, runnable Oqthara programs.", current: "examples.html", body: examples },
  { file: "status.html", title: "Status — Oqthara", description: "Honest status of the Oqthara language, compiler and tooling.", current: "status.html", body: status },
  { file: "stdlib.html", title: "Standard library — Oqthara", description: "Generated standard library reference.", current: "stdlib.html", body: stdlib },
];

mkdirSync(out, { recursive: true });
cpSync(join(root, "assets"), join(out, "assets"), { recursive: true });
cpSync(join(root, "playground.js"), join(out, "playground.js"));
for (const page of pages) {
  writeFileSync(join(out, page.file), layout(page), "utf8");
  console.log("wrote", page.file);
}

const reference = join(root, "..", "docs", "stdlib", "reference.md");
if (existsSync(reference)) {
  cpSync(reference, join(out, "stdlib.md"));
  console.log("copied stdlib.md");
}

console.log("site built into", out);
