package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Filesystem pattern matching: {@code fnmatch} for one name, {@code glob} for a directory.
 *
 * <p>The language had {@code list_files} and {@code walk} and nothing to filter them with, so
 * every program that wanted "the text files in this directory" wrote the predicate by hand and
 * got it subtly wrong.
 *
 * <p>The pattern language is defined here rather than delegated to
 * {@link java.nio.file.FileSystems#getPathMatcher}, because that matcher does not mean by
 * {@code **} what every other glob dialect means. Measured on this platform,
 * {@code **}{@code /*.oq} does not match the top-level file {@code a.oq}: Java requires at
 * least one directory for a globstar. Python's {@code **} matches zero or more directories, so
 * a program written against the reference would silently miss files. Translating the pattern
 * here keeps the semantics explicit and testable; {@code GlobTest} pins each rule.
 */
final class Patterns {
    private Patterns() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("fnmatch", 2, (host, args, span) -> {
            String name = Values.display(args.get(0));
            String pattern = Values.display(args.get(1));
            // Matched as text, not through Paths.get: on Windows a name holding '|' or '?'
            // is not a legal path, so Paths.get throws InvalidPathException before the pattern
            // is consulted. fnmatch asks whether two strings match, and a name does not have to
            // be a path on this host to be worth testing.
            return new Glob(pattern, span).matchesText(name);
        }));
        interpreter.defineBuiltin(NativeFunction.of("glob", 2, (host, args, span) -> {
            Path directory = resolve(host, args.get(0));
            String pattern = Values.display(args.get(1));
            return search(directory, pattern, host, span);
        }));
    }

    /**
     * Lists the entries under {@code directory} whose relative path matches {@code pattern}.
     *
     * <p>Recursion is decided by the pattern rather than by a flag: a match that reaches past
     * one path segment can only be satisfied by descending, so a pattern containing {@code **}
     * or a separator walks the tree, while a bare {@code *.oq} lists one level. That keeps one
     * function instead of two, and the common case never pays for a traversal it cannot use.
     */
    private static List<Object> search(Path directory, String pattern, OqHost host, Span span) {
        if (!Files.isDirectory(directory)) {
            throw new OqError("not a directory: " + directory, span,
                    "glob() searches under a directory. Use fnmatch for a single name.");
        }
        Glob glob = new Glob(pattern, span);
        // Symbolic links are not followed: walk() settled this already. Following one can
        // revisit a directory, and the entry cap would then trip for the wrong reason.
        List<Object> found = new ArrayList<>();
        try (Stream<Path> entries = glob.recursive()
                ? Files.walk(directory)
                : Files.list(directory)) {
            Iterator<Path> iterator = entries.sorted().iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (entry.equals(directory)) continue;
                Path relative = directory.relativize(entry);
                if (!glob.matches(relative)) continue;
                if (found.size() >= Stdlib.MAX_LIST_ENTRIES) {
                    throw new OqError("pattern matched too many entries: " + pattern, span,
                            "It matched at least " + Stdlib.MAX_LIST_ENTRIES + " entries under "
                                    + directory + ". Narrow the pattern.");
                }
                found.add(relative.toString().replace(java.io.File.separatorChar, '/'));
            }
        } catch (OqError error) {
            throw error;
        } catch (java.io.UncheckedIOException e) {
            // Files.walk and Files.list report a traversal failure as an unchecked wrapper, so
            // the IOException branch below never sees it and the failure would surface as an
            // internal error with exit 70. walk() has the same shape on main; this is the
            // corrected form for new code.
            throw new OqError("cannot search '" + directory + "'", span, e.getCause().getMessage());
        } catch (IOException e) {
            throw new OqError("cannot search '" + directory + "'", span, e.getMessage());
        }
        Stdlib.chargeTransfer(host, found.size());
        return found;
    }

    /**
     * One compiled pattern.
     *
     * <p>{@code *} matches any run of characters except a separator, {@code ?} one character
     * except a separator, {@code **} any run including separators, and {@code [abc]} a
     * character class. Everything else is literal, and every literal is quoted before it
     * reaches the regex engine, so a pattern cannot inject regex syntax.
     *
     * <p>Inside a class, {@code !} negates only as the first character, matching Python and
     * {@code fnmatch(3)}; every other member is a literal, so {@code [^a]} is "a caret or an
     * a" rather than a negated class, and {@code &&} is two ampersands rather than an
     * intersection.
     */
    static final class Glob {
        private final Pattern pattern;
        private final boolean recursive;

        Glob(String source, Span span) {
            if (source.isEmpty()) {
                throw new OqError("a pattern cannot be empty", span);
            }
            String normalised = source.replace(java.io.File.separatorChar, '/');
            this.recursive = normalised.contains("**") || normalised.indexOf('/') >= 0;
            try {
                this.pattern = Pattern.compile(translate(normalised, span));
            } catch (java.util.regex.PatternSyntaxException e) {
                // A class body the dialect accepts but the regex engine rejects, such as the
                // reversed range in `[z-a]`. Without this the pattern escapes as an internal
                // error and exits 70, so a script watching for the usual exit 1 sees a
                // different class of failure for what is really a bad argument.
                throw new OqError("bad pattern: " + e.getDescription(), span,
                        "Character class ranges must run low to high, as in [a-z].");
            }
        }

        boolean matches(Path relative) {
            return matchesText(relative.toString());
        }

        /**
         * Matches a name against the pattern without treating it as a path on this host.
         *
         * <p>{@code fnmatch} takes a bare file name, and on Windows some names that a pattern can
         * describe -- {@code a|b}, {@code a?b} -- are not legal paths at all, so converting the
         * name to a {@link Path} would reject it before the pattern was ever consulted.
         */
        boolean matchesText(String name) {
            return pattern.matcher(name.replace(java.io.File.separatorChar, '/')).matches();
        }

        /**
         * True when a match could reach past one path segment. A pattern with no separator and
         * no globstar describes names inside one directory, so the search does not descend.
         */
        boolean recursive() {
            return recursive;
        }

        private static String translate(String source, Span span) {
            StringBuilder regex = new StringBuilder();
            int i = 0;
            while (i < source.length()) {
                char c = source.charAt(i);
                switch (c) {
                    case '*' -> {
                        if (i + 1 < source.length() && source.charAt(i + 1) == '*') {
                            // A globstar must stand alone between separators. `a**b` is
                            // ambiguous — it could mean two stars or one globstar — so it is
                            // refused rather than guessed at.
                            boolean atStart = i == 0 || source.charAt(i - 1) == '/';
                            boolean atEnd = i + 2 == source.length()
                                    || source.charAt(i + 2) == '/';
                            if (!atStart || !atEnd) {
                                throw new OqError("bad pattern: '**' must stand alone between"
                                        + " separators", span,
                                        "Write '**/*.oq', not 'a**b'.");
                            }
                            if (i + 2 == source.length()) {
                                // Trailing globstar: everything below, at any depth.
                                regex.append(".*");
                                i += 2;
                            } else {
                                // `**/` also matches zero directories, so the separator it
                                // spells is consumed here and must not be emitted as a
                                // literal. This is the case Java gets wrong.
                                regex.append("(?:[^/]*/)*");
                                i += 3;
                            }
                        } else {
                            regex.append("[^/]*");
                            i++;
                        }
                    }
                    case '?' -> {
                        regex.append("[^/]");
                        i++;
                    }
                    case '[' -> {
                        int close = source.indexOf(']', i + 1);
                        if (close < 0) {
                            throw new OqError("bad pattern: '[' has no closing ']'", span);
                        }
                        String body = source.substring(i + 1, close);
                        i = close + 1;
                        regex.append('[');
                        // A leading `!` negates, as in Python and fnmatch(3).
                        if (body.startsWith("!")) {
                            regex.append('^');
                            body = body.substring(1);
                        }
                        if (body.isEmpty()) {
                            throw new OqError("bad pattern: empty character class", span);
                        }
                        for (int j = 0; j < body.length(); j++) {
                            char member = body.charAt(j);
                            // Escape what would end or reopen the class; a `-` range passes
                            // through deliberately, since `[a-z]` is the reason the class is
                            // useful. `^` and `&` also pass through as literals in the dialect
                            // -- `fnmatch("a", "[^a]")` is Python's false, not "a literal ^ or
                            // a" -- but the regex engine gives both a meaning inside a class,
                            // so they have to be quoted here to keep the dialect's promise.
                            if (member == '\\' || member == ']' || member == '['
                                    || member == '^' || member == '&') {
                                regex.append('\\');
                            }
                            regex.append(member);
                        }
                        regex.append(']');
                    }
                    default -> {
                        regex.append(Pattern.quote(String.valueOf(c)));
                        i++;
                    }
                }
            }
            return regex.toString();
        }
    }

    private static Path resolve(OqHost host, Object value) {
        Path path = Path.of(Values.display(value));
        if (path.isAbsolute()) return path;
        Path base = host.workingDirectory();
        if (base == null) base = Path.of("").toAbsolutePath();
        return base.toAbsolutePath().resolve(path).normalize();
    }
}
