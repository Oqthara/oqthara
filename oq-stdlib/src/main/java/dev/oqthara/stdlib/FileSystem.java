package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Files and directories: the parts of the surface that need more than one call to
 * {@code java.nio.file}, kept here instead of in {@code Stdlib}.
 *
 * <p>{@code Stdlib.installFiles} and {@code Stdlib.installFileOps} already register the
 * single-call names: {@code read}, {@code save}, {@code exists}, {@code walk} and the rest.
 * What is left over is the group whose implementation is a sequence of steps with a refusal in
 * the middle, namely inspecting metadata, copying a tree, and creating a temporary directory.
 * Keeping them together makes the refusals reviewable side by side.
 *
 * <p>Every path is plain text, joined with the platform separator by the program, and resolved
 * against the working directory the host reports. No function here takes or returns a
 * {@code Path}, for the reason recorded in ADR-0036: a path value would need a type, an
 * equality, and an answer to what happens when the file moves.
 *
 * <p>Two things are deliberately absent. {@code delete} is not recursive and nothing here adds
 * a recursive form: ADR-0036 records that deleting a tree must never happen without an explicit
 * opt-in flag, and a flag is a language decision rather than a library one, so the recursive
 * form stays unimplemented rather than growing a boolean the inventory does not describe.
 *
 * <p>{@code temp_dir} returns a directory the caller owns and must delete. It is not scoped,
 * because the language has no {@code close} and no {@code with} block to hang a lifetime on
 * (ADR-0036). A scoped version would need a construct the language does not have, so this
 * module gives the unscoped one and says so where it is called rather than pretending the
 * cleanup happens by itself.
 *
 * <p>Both copy operations are bounded by bytes as well as by names: {@code copy_file} by
 * {@code MAX_TEXT_BYTES} for one file, and {@code copy_tree} by the same figure for the whole
 * tree. The tree is bounded by one budget rather than one per file because an entry cap alone
 * allows a hundred thousand files of sixteen megabytes each, and a per-file bound inside a
 * tree that size allows the same 1.6 TiB. The bound is counted as the bytes move -- see
 * {@code Stdlib.copyBounded} -- so the size the filesystem reports can only shorten a copy
 * early, never admit one past the limit.
 */
final class FileSystem {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private FileSystem() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("stat", 1, (host, args, span) ->
                stat(resolve(host, args.get(0), "stat", span), args.get(0), span)).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("touch", 1, (host, args, span) -> {
            touch(resolve(host, args.get(0), "touch", span), span);
            return null;
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("temp_dir", 1, (host, args, span) ->
                tempDir(text(args.get(0), "temp_dir", span), span)).named("prefix"));
        interpreter.defineBuiltin(NativeFunction.of("copy_tree", 2, (host, args, span) -> {
            copyTree(host, resolve(host, args.get(0), "copy_tree", span),
                    resolve(host, args.get(1), "copy_tree", span), span);
            return null;
        }).named("from", "to"));
        interpreter.defineBuiltin(NativeFunction.of("read_lines_or_empty", 1, (host, args, span) ->
                readLinesOrEmpty(host, resolve(host, args.get(0), "read_lines_or_empty", span),
                        span)).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("path_absolute", 1, (host, args, span) ->
                absolute(host, args.get(0), span)).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("path_normalize", 1, (host, args, span) ->
                normalize(args.get(0), span)).named("path"));
    }

    /**
     * The metadata a caller can act on, in one map.
     *
     * <p>Size, type and the two timestamps are read with {@link LinkOption#NOFOLLOW_LINKS}, so
     * the answer describes the entry at the path rather than what it points to. For a symbolic
     * link that means {@code is_symlink} is true while {@code is_file} and {@code is_dir} are
     * both false, and {@code size} is the length of the link's own target text. This is the
     * combination a caller can act on: it can see that a link is a link and decide for itself
     * whether to follow it. Merging the two views, taking link identity from one call and target
     * type from another, would produce a map true of no single filesystem entry, and a caller
     * could not tell which keys described which.
     *
     * <p>The three permission booleans are the exception, because they answer "would reading,
     * writing or executing this path work" rather than "what is stored here", and a link has no
     * permissions of its own on a POSIX filesystem. They follow the link, so a dangling link
     * reports {@code readable: false}.
     *
     * <p>{@code created} is whatever the filesystem reports as the creation time, which on some
     * Linux filesystems is the time the inode was written rather than the time the file was
     * made, and can equal {@code modified}. It is reported as found rather than guessed.
     *
     * <p>{@code mode} is present only where the platform reports POSIX permissions, and is read
     * without following a link like the size and type keys, because it describes the permission
     * bits stored on the entry at the path. On a filesystem without them the key is omitted,
     * because a fabricated {@code "644"} would be a claim about permissions the platform does
     * not enforce.
     *
     * <p>Both timestamps are ISO-8601 instants in UTC with three fractional digits, so two of
     * them can be compared as text.
     */
    private static OqMap stat(Path path, Object original, Span span) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new OqError("cannot stat '" + Values.display(original) + "'", span,
                    "stat() needs a path that exists. Check it with exists() first. ("
                            + e.getMessage() + ")");
        }
        OqMap result = new OqMap(10);
        result.put("size", attributes.size());
        result.put("is_file", attributes.isRegularFile());
        result.put("is_dir", attributes.isDirectory());
        result.put("is_symlink", attributes.isSymbolicLink());
        result.put("modified", instantText(attributes.lastModifiedTime()));
        result.put("created", instantText(attributes.creationTime()));
        result.put("readable", Files.isReadable(path));
        result.put("writable", Files.isWritable(path));
        result.put("executable", Files.isExecutable(path));
        String mode = posixMode(path);
        if (mode != null) result.put("mode", mode);
        return result;
    }

    /**
     * An ISO-8601 instant in UTC with exactly three fractional digits.
     *
     * <p>{@link Instant#toString} drops the fraction when it is zero, so a file written on a
     * whole second would produce {@code 2026-11-09T12:00:00Z} where every other file produces
     * {@code ...T12:00:00.123Z}. One shape means a caller can compare two timestamps as text,
     * and truncating to milliseconds matches the resolution the common filesystems store.
     */
    private static String instantText(FileTime time) {
        Instant instant = Instant.ofEpochMilli(time.toInstant().toEpochMilli());
        return TIMESTAMP.format(instant);
    }

    /** The permissions as octal such as {@code "644"}, or null where the platform has none. */
    private static String posixMode(Path path) {
        PosixFileAttributeView view = Files.getFileAttributeView(path,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return null;
        PosixFileAttributes attributes;
        try {
            attributes = view.readAttributes();
        } catch (IOException e) {
            // The entry exists, because stat() already read it, so a failure here means the
            // permissions are not reportable. Omitting the key is the honest answer.
            return null;
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        int bits = 0;
        if (permissions.contains(PosixFilePermission.OWNER_READ)) bits |= 0400;
        if (permissions.contains(PosixFilePermission.OWNER_WRITE)) bits |= 0200;
        if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) bits |= 0100;
        if (permissions.contains(PosixFilePermission.GROUP_READ)) bits |= 0040;
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)) bits |= 0020;
        if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) bits |= 0010;
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) bits |= 0004;
        if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) bits |= 0002;
        if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) bits |= 0001;
        return Integer.toOctalString(bits);
    }

    private static void touch(Path path, Span span) {
        try {
            // The existence test does not follow a link but the write does, which is what the
            // touch command means: the file a caller names is the file whose time they want
            // changed. A link pointing at nothing is therefore an error rather than an entry
            // whose own timestamp is rewritten.
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            } else {
                Files.createFile(path);
            }
        } catch (IOException e) {
            throw new OqError("cannot touch '" + path + "'", span,
                    "touch() creates the file if it is absent and updates the time if it is "
                            + "present, so the parent directory must exist and a symbolic link "
                            + "must point at something. (" + e.getMessage() + ")");
        }
    }

    private static String tempDir(String prefix, Span span) {
        if (prefix.isEmpty()) {
            throw new OqError("temp_dir() needs a non-empty prefix", span,
                    "The prefix says what the directory is for and makes a leftover findable. "
                            + "Use at least one character.");
        }
        try {
            // createTempDirectory adds its own random suffix, so the prefix only has to be a
            // name the platform accepts. It must not contain a separator, which is what the
            // IllegalArgumentException below catches.
            return Files.createTempDirectory(prefix).toString();
        } catch (IllegalArgumentException e) {
            throw new OqError("temp_dir() cannot use the prefix '" + prefix + "'", span,
                    "A prefix is one file name, so it cannot contain a path separator. ("
                            + e.getMessage() + ")");
        } catch (IOException e) {
            throw new OqError("cannot create a temporary directory", span,
                    "The system temporary directory must be writable. (" + e.getMessage() + ")");
        }
    }

    /**
     * Copies a directory tree, refusing the ways a recursive copy goes wrong quietly.
     *
     * <p>The first is merging into a target that already has content. A half-finished earlier
     * copy would leave a mixture of old and new files with no way to tell them apart, so a
     * non-empty target is refused rather than merged. An empty target is accepted, because
     * {@code make_dir} followed by {@code copy_tree} is an obvious thing to write.
     *
     * <p>The second is a symbolic link. {@link Files#walk} does not follow links, so a link is
     * seen as one entry and never descended into; but copying it as a file would produce a link
     * in the target that points somewhere the caller did not copy, and following it would read
     * files from outside the tree that was named. Links are refused rather than silently
     * skipped, because a copy that is missing files without saying so is worse than one that
     * stops.
     *
     * <p>The third is a target inside the source. The walk is materialised before anything is
     * written, so this cannot loop, but it would still produce a tree nobody asked for.
     *
     * <p>The fourth is the number of bytes. An entry cap alone bounds a tree by
     * {@code MAX_LIST_ENTRIES} times {@code MAX_TEXT_BYTES} -- a hundred thousand files of
     * sixteen megabytes each, 1.6 TiB -- so counting entries while ignoring bytes would make
     * {@code copy_tree} the way around the limit {@code copy_file} enforces. The budget is the
     * same 16 MiB {@code copy_file} allows for one file, applied once to the whole tree, so a
     * copy moves no more bytes than one read of one file and the bound composes instead of
     * multiplying. It is also what keeps the step charge affordable: the charge is one step per
     * byte moved, and 16 MiB is 16,777,216 of the default 50,000,000-step budget, whereas a
     * larger cap would be refused for running too long before the documented byte limit could
     * be reached, which is the mistake the per-byte multiplier made once already.
     *
     * <p>As in {@code copy_file}, the limit is enforced twice with the same figure: once
     * against the sizes the filesystem reports, before anything is written, and then against
     * the bytes actually transferred. The first is advisory -- for a device, a pipe, or a file
     * another process is still writing, the reported size is not what a read yields -- so it
     * cannot be the bound; the second is the bound, and it is why a refusal can come after
     * part of the tree has been written. That partial tree is left in place rather than removed:
     * {@code copy_file} deletes its half-written target because that is one deletion, while
     * removing a tree is a recursive delete, which this module does not implement, so the error
     * says the target must be deleted and names it.
     *
     * <p>The entry cap is the one {@code list_files} uses, and it raises rather than truncating
     * for the same reason: a caller that receives a prefix of a tree cannot tell that it did.
     */
    static void copyTree(OqHost host, Path from, Path to, Span span) {
        copyTree(host, from, to, span, Stdlib.MAX_LIST_ENTRIES, Stdlib.MAX_TEXT_BYTES);
    }

    /** The same copy, with the entry cap passed in and the byte budget left at its real figure. */
    static void copyTree(OqHost host, Path from, Path to, Span span, int limit) {
        copyTree(host, from, to, span, limit, Stdlib.MAX_TEXT_BYTES);
    }

    /**
     * The same copy, with both caps passed in.
     *
     * <p>They exist so a tree cannot be copied without a bound, which means the interesting case
     * is a tree just over one. Reaching either with the real figures takes a hundred thousand
     * files or sixteen megabytes, so the test drives this overload with a handful of bytes and
     * a handful of entries instead.
     */
    static void copyTree(OqHost host, Path from, Path to, Span span, int limit, long bytes) {
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            throw new OqError("cannot copy '" + from + "'", span,
                    "copy_tree() needs a source that exists. Check it with exists() first.");
        }
        if (Files.isSymbolicLink(from)) {
            throw new OqError("copy_tree() will not copy a symbolic link: " + from, span,
                    "Pass the directory the link points at, so the copy is of the tree named.");
        }
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            throw new OqError("copy_tree() copies a directory, not a file: " + from, span,
                    "Use copy_file for one file. It never overwrites either.");
        }
        if (to.equals(from)) {
            throw new OqError("copy_tree() cannot copy a directory onto itself: " + from, span,
                    "The target must be somewhere else. Copy to a sibling and move it afterwards "
                            + "if the copy belongs next to the source.");
        }
        if (to.startsWith(from)) {
            throw new OqError("copy_tree() cannot copy a directory into itself: " + to, span,
                    "The target must be outside the source. Copy to a sibling and move it "
                            + "afterwards if that is where it belongs.");
        }
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                throw new OqError("target already exists: " + to, span,
                        "copy_tree() never overwrites. Delete it first, or pick another name.");
            }
            try (Stream<Path> existing = Files.list(to)) {
                if (existing.findAny().isPresent()) {
                    throw new OqError("target directory is not empty: " + to, span,
                            "copy_tree() will not merge into an existing tree. Delete it first, "
                                    + "or name a directory that is empty or absent.");
                }
            } catch (IOException e) {
                throw new OqError("cannot read target directory '" + to + "'", span,
                        "copy_tree() checks that the target is empty before it copies. ("
                                + e.getMessage() + ")");
            }
        }

        List<Path> entries = new ArrayList<>();
        long reported = 0;
        try (Stream<Path> walk = Files.walk(from)) {
            Iterator<Path> each = walk.iterator();
            while (each.hasNext()) {
                Path entry = each.next();
                if (Files.isSymbolicLink(entry)) {
                    throw new OqError("copy_tree() will not copy a symbolic link: " + entry, span,
                            "Copy the tree without links, or copy what the link points at "
                                    + "separately. Following a link leaves the source tree.");
                }
                entries.add(entry);
                if (entries.size() > limit) {
                    throw new OqError("directory tree has too many entries: " + from, span,
                            "copy_tree() copies whole trees and stops above " + limit
                                    + " entries rather than copying part of one.");
                }
                // The size only steers the refusal below; it does not admit anything. A file
                // whose reported size is wrong is caught by the count in copyBounded instead.
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    reported += Files.size(entry);
                    if (reported > bytes) {
                        throw new OqError("directory tree is too large to copy: " + from, span,
                                "It is more than the " + bytes + " byte limit, counted as the "
                                        + "sizes the filesystem reports. Nothing was written.");
                    }
                }
            }
        } catch (OqError e) {
            throw e;
        } catch (IOException | UncheckedIOException e) {
            throw new OqError("cannot read directory tree '" + from + "'", span,
                    "Every entry under the source must be readable. (" + e.getMessage() + ")");
        }

        long copied = 0;
        try {
            Files.createDirectories(to);
            for (Path entry : entries) {
                Path destination = to.resolve(from.relativize(entry).toString());
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    // The walk always visits the source root first, and the root is a directory,
                    // so a destination here always has a parent that already exists.
                    //
                    // The remaining budget, not the tree-wide one, is passed on: copyBounded
                    // counts from zero for each file, so handing it the full figure would allow
                    // each file the whole tree's budget and let a tree of many files exceed it.
                    try (java.io.InputStream in = Files.newInputStream(entry);
                         java.io.OutputStream out = Files.newOutputStream(destination)) {
                        copied += Stdlib.copyBounded(in, out, bytes - copied, entry, span);
                    } catch (OqError tooLarge) {
                        // Restated against the tree, because "the 4,194,304 byte limit" is what
                        // was left rather than what was promised, and because the target this
                        // leaves behind is a whole tree, not one file that copy_file could
                        // delete on the way out.
                        throw new OqError("directory tree is too large to copy: " + from, span,
                                "Copying '" + entry + "' took the tree past the " + bytes
                                        + " byte limit. The copy stopped partway; delete the "
                                        + "partial target '" + to + "' before trying again.");
                    }
                }
            }
        } catch (IOException e) {
            throw new OqError("cannot copy '" + from + "' to '" + to + "'", span,
                    "The copy stopped partway; delete the partial target before trying again. ("
                            + e.getMessage() + ")");
        }
        // One charge per byte written, matching copy_file's one charge per byte copied. The
        // entry count is not what a caller pays for: a tree of one 16 MiB file and a tree of
        // a hundred thousand empty files are the same two entries as far as a walk is
        // concerned, and only the first costs memory.
        Stdlib.chargeTransfer(host, copied);
    }

    /**
     * {@code read_lines} for a file that may not be there.
     *
     * <p>Named this way on purpose. Two functions that differ only in whether a missing file
     * raises are a trap when the names do not say which is which, so the tolerant form carries
     * the tolerance in its name and {@code read_lines} keeps raising.
     */
    private static List<Object> readLinesOrEmpty(OqHost host, Path path, Span span) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        // Same cap and same charge as read_lines, so this is not a way around the limit.
        String content = Stdlib.readBounded(host, path, span, "the file");
        List<Object> lines = new ArrayList<>();
        content.lines().forEach(lines::add);
        return lines;
    }

    /**
     * The absolute form, with {@code ..} already collapsed.
     *
     * <p>A relative path is joined to the working directory and then normalized, so the answer
     * does not depend on which directory the process was started in and does not contain a
     * {@code ..} for a caller to trip over. An absolute path is normalized too, because an
     * absolute path that still says {@code /a/../b} is absolute but is not the form anyone
     * wants to pass to another call.
     */
    private static String absolute(OqHost host, Object value, Span span) {
        return resolve(host, value, "path_absolute", span).normalize().toString();
    }

    /**
     * The lexical form: empty segments and {@code .} removed, {@code ..} collapsed against a
     * preceding name. It never touches the filesystem and never makes the path absolute, so a
     * relative path stays relative and a path that does not exist still normalizes.
     *
     * <p>{@link Path#normalize} returns an empty path for {@code "."} and for {@code ""}, which
     * no other function would accept back, so those are reported as {@code "."}: the directory
     * the path names, spelled the way it is spelled everywhere else.
     */
    private static String normalize(Object value, Span span) {
        Path normalized;
        try {
            normalized = Path.of(Values.display(value)).normalize();
        } catch (InvalidPathException e) {
            throw new OqError("not a path: '" + Values.display(value) + "'", span,
                    "A path cannot contain a NUL character. (" + e.getMessage() + ")");
        }
        String text = normalized.toString();
        return text.isEmpty() ? "." : text;
    }

    private static Path resolve(OqHost host, Object value, String builtin, Span span) {
        Path path;
        try {
            path = Path.of(Values.display(value));
        } catch (InvalidPathException e) {
            throw new OqError(builtin + "() needs a path, and '" + Values.display(value)
                    + "' is not one", span,
                    "A path is text without a NUL character. (" + e.getMessage() + ")");
        }
        if (path.isAbsolute()) return path;
        Path base = host.workingDirectory();
        if (base == null) base = Path.of("").toAbsolutePath();
        return base.toAbsolutePath().resolve(path).normalize();
    }

    private static String text(Object value, String builtin, Span span) {
        if (value instanceof String s) return s;
        throw new OqError(builtin + "() needs text, got " + Values.typeName(value), span,
                "Quote the value, or convert it to text first.");
    }
}
