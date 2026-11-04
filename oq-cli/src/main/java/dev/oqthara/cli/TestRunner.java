package dev.oqthara.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers and runs {@code test_*.oq} files or anything under a {@code tests/} directory.
 * A test file passes when it runs without raising an error and exits with status 0.
 */
final class TestRunner {
    private static final String PASS = "\u001b[32mPASS\u001b[0m";
    private static final String FAIL = "\u001b[31mFAIL\u001b[0m";

    private TestRunner() {
    }

    static int run(PrintStream out, PrintStream err, String[] args) {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
        List<Path> files;
        try {
            files = discover(root);
        } catch (Exception e) {
            err.println("Cannot scan '" + root + "': " + e.getMessage());
            return 2;
        }
        if (files.isEmpty()) {
            out.println("No Oqthara tests found under " + root.toAbsolutePath());
            out.println("Create a file named test_<name>.oq, or put them in a tests/ directory.");
            return 0;
        }
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String display = root.getFileName() == null
                    ? file.toString()
                    : root.relativize(file).toString();
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                OqRunner.Result result = OqRunner.run(file.getFileName().toString(), source,
                        List.of(), "", file.toAbsolutePath().getParent());
                if (result.code() != 0) {
                    failures.add(display + "\n" + indent(result.stderr().strip()));
                    out.println((color ? FAIL : "FAIL") + " " + display);
                } else {
                    passed++;
                    out.println((color ? PASS : "PASS") + " " + display);
                }
            } catch (Exception e) {
                failures.add(display + "\n  " + e);
                out.println((color ? FAIL : "FAIL") + " " + display);
            }
        }
        out.println();
        out.println(passed + " passed, " + failures.size() + " failed, " + files.size() + " total");
        if (!failures.isEmpty()) {
            out.println();
            out.println("Failures:");
            for (String failure : failures) {
                out.println("  " + failure);
            }
            return 1;
        }
        return 0;
    }

    static List<Path> discover(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 6)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".oq"))
                    .filter(TestRunner::looksLikeTest)
                    .filter(p -> !isBuildOutput(p))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean looksLikeTest(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith("test_") || name.endsWith("_test.oq") || name.startsWith("test-")) {
            return true;
        }
        for (Path part : path) {
            if (part.toString().equals("tests") || part.toString().equals("test")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBuildOutput(Path path) {
        for (Path part : path) {
            String name = part.toString();
            if (name.equals("build") || name.equals(".gradle") || name.equals("node_modules")
                    || name.equals(".git")) {
                return true;
            }
        }
        return false;
    }

    private static String indent(String text) {
        return "  " + text.replace("\n", "\n  ");
    }
}
