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
        }).named("name", "pattern"));
        interpreter.defineBuiltin(NativeFunction.of("glob", 2, (host, args, span) -> {
            Path directory = resolve(host, args.get(0));
            String pattern = Values.display(args.get(1));
            return search(directory, pattern, host, span);
        }).named("directory", "pattern"));
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
        List<Path> visited = new ArrayList<>();
        try (Stream<Path> entries = glob.recursive()
                ? Files.walk(directory)
                : Files.list(directory)) {
            // Drained into a bounded list, then sorted, rather than entries.sorted(): sorting the
            // stream is stateful and buffers every Path before yielding the first one, so a
            // pattern that matched nothing still walked and buffered the whole tree and could
            // exhaust the heap before any cap applied (issue 185). Counting matches alone cannot
            // bound that, because a non-matching entry is still an entry traversal paid for.
            Iterator<Path> iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (entry.equals(directory)) continue;
                if (visited.size() >= Stdlib.MAX_VISITED_ENTRIES) {
                    throw new OqError("patterns visited too many entries: " + directory, span,
                            "It visited at least " + Stdlib.MAX_VISITED_ENTRIES
                                    + " entries under " + directory
                                    + " without finishing the pattern. Narrow the pattern, or "
                                    + "search a smaller directory.");
                }
                visited.add(entry);
            }
        } catch (OqError error) {
            throw error;
        } catch (java.io.UncheckedIOException e) {
            // Files.walk and Files.list report a traversal failure as an unchecked wrapper, so
            // the IOException branch below never sees it and the failure would surface as an
            // internal error with exit 70. This is the same form walk() now uses.
            throw new OqError("cannot search '" + directory + "'", span, e.getCause().getMessage());
        } catch (IOException e) {
            throw new OqError("cannot search '" + directory + "'", span, e.getMessage());
        }
        // Sorted on Paths, not on the rendered strings, so the order is the one
        // Stream.sorted() produced before: Path.compareTo compares element by element.
        visited.sort(null);
        for (Path entry : visited) {
            Path relative = directory.relativize(entry);
            if (!glob.matches(relative)) continue;
            if (found.size() >= Stdlib.MAX_LIST_ENTRIES) {
                throw new OqError("pattern matched too many entries: " + pattern, span,
                        "It matched at least " + Stdlib.MAX_LIST_ENTRIES + " entries under "
                                + directory + ". Narrow the pattern.");
            }
            found.add(relative.toString().replace(java.io.File.separatorChar, '/'));
        }
        Stdlib.chargeTransfer(host, found.size());
        return found;
    }

    /**
     * One compiled pattern.
     *
     * <p>{@code *} matches any run of characters except a separator, {@code ?} one character
     * except a separator, {@code **} any run including separators, and {@code [abc]} a
     * character class. Everything else is literal.
     *
     * <p>Matching is a dynamic program over the pattern and the name, not a compiled regex. A
     * regex built from a user pattern is open to catastrophic backtracking: the name
     * {@code *a*a*a*...b} against a run of {@code a}s makes the engine try every way of
     * splitting the text between the stars, and the cost doubles for every two extra
     * characters. The table below visits each (pattern, name) pair once, so a hostile pattern
     * costs pattern×name steps and no more. Jazzer's regex-injection sanitizer flagged the old
     * translator for exactly this, which is how the defect was found.
     *
     * <p>Inside a class, {@code !} negates only as the first character, matching Python and
     * {@code fnmatch(3)}; every other member is a literal, so {@code [^a]} is "a caret or an
     * a" rather than a negated class, and {@code &&} is two ampersands rather than an
     * intersection.
     */
    static final class Glob {
        /** The pattern, one element per token. Built once; never a regex. */
        private final List<Token> tokens;
        private final boolean recursive;

        Glob(String source, Span span) {
            if (source.isEmpty()) {
                throw new OqError("a pattern cannot be empty", span);
            }
            String normalised = source.replace(java.io.File.separatorChar, '/');
            this.recursive = normalised.contains("**") || normalised.indexOf('/') >= 0;
            this.tokens = parse(normalised, span);
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
            String text = name.replace(java.io.File.separatorChar, '/');
            int[] namePoints = text.codePoints().toArray();
            // reach[j] is true when the tokens consumed so far can account for exactly the
            // first j code points of the name. Each token advances the whole row, so the work
            // is tokens×length and a pattern can never make the engine explore alternatives.
            boolean[] reach = new boolean[namePoints.length + 1];
            reach[0] = true;
            for (Token token : tokens) {
                boolean[] next = new boolean[reach.length];
                switch (token.kind()) {
                    case STAR -> {
                        // `*` is any run that stays inside one segment, so once a position is
                        // reachable every later position up to the next separator is too. The
                        // reset has to happen before the token can open a fresh run at the same
                        // position, or a position reachable in its own right would be lost just
                        // because the character before it is a separator.
                        boolean open = false;
                        for (int j = 0; j < reach.length; j++) {
                            if (open && j > 0 && namePoints[j - 1] == '/') open = false;
                            if (reach[j]) open = true;
                            next[j] = open;
                        }
                    }
                    case TRAILING_GLOBSTAR -> {
                        // A trailing `**` is "everything below, at any depth", so it crosses
                        // separators and simply absorbs the rest of the name.
                        boolean open = false;
                        for (int j = 0; j < reach.length; j++) {
                            if (reach[j]) open = true;
                            next[j] = open;
                        }
                    }
                    case GLOBSTAR -> {
                        // `**/` is `(?:[^/]*/)*`: zero or more *complete* segments. Because a
                        // segment cannot contain a separator, the only positions it reaches are
                        // where it started plus the position just past each later separator. It
                        // must not be modelled as `.*`, which would let `**/foo` match `a/bfoo`.
                        // Start from the zero-segment case, then add each position that a
                        // whole extra segment reaches. Writing both into the same array in one
                        // pass would clobber the lookahead write on the following iteration.
                        boolean[] segmentReach = reach.clone();
                        boolean pending = false;
                        for (int j = 0; j < reach.length; j++) {
                            if (reach[j]) pending = true;
                            if (pending && j < namePoints.length && namePoints[j] == '/') {
                                segmentReach[j + 1] = true;
                            }
                        }
                        next = segmentReach;
                    }
                    case ANY_ONE -> {
                        for (int j = 1; j < reach.length; j++) {
                            if (reach[j - 1] && namePoints[j - 1] != '/') next[j] = true;
                        }
                    }
                    case CLASS -> {
                        for (int j = 1; j < reach.length; j++) {
                            if (!reach[j - 1]) continue;
                            next[j] = token.matches(namePoints[j - 1]);
                        }
                    }
                    case LITERAL -> {
                        int literal = token.codePoint();
                        for (int j = 1; j < reach.length; j++) {
                            if (reach[j - 1] && namePoints[j - 1] == literal) next[j] = true;
                        }
                    }
                }
                reach = next;
            }
            return reach[namePoints.length];
        }

        /**
         * True when a match could reach past one path segment. A pattern with no separator and
         * no globstar describes names inside one directory, so the search does not descend.
         */
        boolean recursive() {
            return recursive;
        }

        /** One element of a pattern. Matching never looks at the source text again. */
        private enum Kind { LITERAL, ANY_ONE, CLASS, STAR, GLOBSTAR, TRAILING_GLOBSTAR }

        /**
         * A single pattern element.
         *
         * <p>{@code members} holds code points for a class. Ranges are expanded when the
         * pattern is built rather than kept as endpoints: a class is at most the size of the
         * Unicode range, and expanding it once turns every later test into a set lookup.
         */
        private record Token(Kind kind, int codePoint, int[] members, boolean negated) {
            boolean matches(int candidate) {
                for (int member : members) {
                    if (member == candidate) return !negated;
                }
                return negated;
            }
        }

        private static List<Token> parse(String source, Span span) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            while (i < source.length()) {
                int codePoint = source.codePointAt(i);
                // A character outside the BMP is one symbol to the user but two chars in a
                // String, so it has to advance by two and stay one token. Walking char by char
                // made an emoji pattern match neither its own halves nor itself.
                if (codePoint > Character.MAX_VALUE) {
                    tokens.add(new Token(Kind.LITERAL, codePoint, NO_MEMBERS, false));
                    i += Character.charCount(codePoint);
                    continue;
                }
                char c = (char) codePoint;
                switch (c) {
                    case '*' -> {
                        if (i + 1 < source.length() && source.charAt(i + 1) == '*') {
                            // A globstar must stand alone between separators. `a**b` is
                            // ambiguous -- it could mean two stars or one globstar -- so it is
                            // refused rather than guessed at.
                            boolean atStart = i == 0 || source.charAt(i - 1) == '/';
                            boolean atEnd = i + 2 == source.length()
                                    || source.charAt(i + 2) == '/';
                            if (!atStart || !atEnd) {
                                throw new OqError("bad pattern: '**' must stand alone between"
                                        + " separators", span,
                                        "Write '**/*.oq', not 'a**b'.");
                            }
                            // `**/` also matches zero directories, so the separator it spells
                            // belongs to the globstar rather than becoming a literal slash.
                            if (i + 2 == source.length()) {
                                tokens.add(new Token(Kind.TRAILING_GLOBSTAR, 0, NO_MEMBERS, false));
                                i += 2;
                            } else {
                                tokens.add(new Token(Kind.GLOBSTAR, 0, NO_MEMBERS, false));
                                i += 3;
                            }
                        } else {
                            tokens.add(new Token(Kind.STAR, 0, NO_MEMBERS, false));
                            i++;
                        }
                    }
                    case '?' -> {
                        tokens.add(new Token(Kind.ANY_ONE, 0, NO_MEMBERS, false));
                        i++;
                    }
                    case '[' -> {
                        int close = source.indexOf(']', i + 1);
                        if (close < 0) {
                            throw new OqError("bad pattern: '[' has no closing ']'", span);
                        }
                        String body = source.substring(i + 1, close);
                        i = close + 1;
                        // A leading `!` negates, as in Python and fnmatch(3). Every other
                        // member is a literal, so `[^a]` is "a caret or an a" rather than a
                        // negated class, and `&&` is two ampersands, not an intersection.
                        boolean negated = body.startsWith("!");
                        if (negated) body = body.substring(1);
                        if (body.isEmpty()) {
                            throw new OqError("bad pattern: empty character class", span);
                        }
                        tokens.add(new Token(Kind.CLASS, 0, expandClass(body, span), negated));
                    }
                    default -> {
                        tokens.add(new Token(Kind.LITERAL, codePoint, NO_MEMBERS, false));
                        i++;
                    }
                }
            }
            return tokens;
        }

        private static final int[] NO_MEMBERS = new int[0];

        private static int[] expandClass(String body, Span span) {
            List<Integer> members = new ArrayList<>();
            int at = 0;
            while (at < body.length()) {
                int from = body.codePointAt(at);
                at += Character.charCount(from);
                // `-` spells a range only when it sits between two members, as in fnmatch(3);
                // a leading or trailing `-` is the character itself.
                boolean hasTo = at + 1 < body.length() && body.charAt(at) == '-';
                if (!hasTo) {
                    members.add(from);
                    continue;
                }
                int to = body.codePointAt(at + 1);
                at += 1 + Character.charCount(to);
                if (to < from) {
                    throw new OqError("bad pattern: range '" + new String(Character.toChars(from))
                            + "-" + new String(Character.toChars(to)) + "' runs backwards", span,
                            "Character class ranges must run low to high, as in [a-z].");
                }
                for (int member = from; member <= to; member++) members.add(member);
            }
            int[] result = new int[members.size()];
            for (int k = 0; k < result.length; k++) result[k] = members.get(k);
            return result;
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
