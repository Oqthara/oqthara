package dev.oqthara.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a runtime archive without letting the archive choose where its files land (ADR-0048 D4).
 *
 * <p>An archive is attacker-controlled input whenever the URL, the mirror or the TLS terminator
 * is, and the classic archive attacks are all variants of one idea: make the extractor write
 * somewhere other than the directory it was given. A traversal entry ({@code ../../etc/passwd}),
 * an absolute path ({@code /etc/passwd}), a Windows drive path ({@code C:\Windows\...}), a UNC
 * path ({@code \\\\host\\share}), a symbolic link pointing outside the root, and a hard link to a
 * file outside the root all reach the same end.
 *
 * <p>The rule here is therefore one sentence: <em>every entry's resolved destination must be
 * inside the extraction root, and a path that is not is refused rather than repaired.</em>
 * Silently normalising {@code ../../x} into {@code x} would keep the extraction "safe" while
 * installing a file the archive's author did not ask for, which is a different defect that is
 * harder to notice. This class refuses.
 *
 * <p>Symbolic links are refused outright. The JDK distribution archives contain none, so the
 * only reason to support them would be a requirement that does not exist, and a link whose target
 * is inside the root can still be written through after creation. Refusing is the policy the ADR
 * prefers; supporting links is a change to the ADR, not to this class.
 *
 * <p>Extraction is all-or-nothing in the sense that matters: a refused archive leaves no
 * installable runtime, because the caller extracts into a temporary directory and this class
 * deletes everything it wrote before rethrowing. A partial cache is what makes a later run
 * believe a broken runtime is installed.
 */
final class SafeArchive {

    /** The largest single entry this will write, in bytes. */
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    /** The largest total this will write across all entries, in bytes. */
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    /** The largest number of entries this will consider. */
    private static final int MAX_ENTRIES = 100_000;

    private SafeArchive() {
    }

    /** A refusal, naming the entry that caused it. */
    static final class ArchiveRejected extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveRejected(String message) {
            super(message);
        }
    }

    /**
     * Extracts {@code archive} into {@code destination}, which must exist and be empty.
     *
     * @throws ArchiveRejected when an entry would escape the root or violate a limit
     * @throws IOException when reading or writing fails
     */
    static void extract(Path archive, Path destination) throws IOException {
        if (!Files.isDirectory(destination)) {
            throw new ArchiveRejected("extraction root is not a directory: " + destination);
        }
        Path root = destination.toRealPath();

        // Every path already claimed, so a later entry cannot overwrite an earlier one. Two
        // spellings of one path are the same collision, which is why the key is lowercased on
        // Windows and macOS: those filesystems would treat `Foo` and `foo` as one file, and an
        // archive could use that to slip a second payload past a name check.
        // Links are found from the central directory, before any entry is written, because
        // `ZipInputStream` discards the Unix mode that identifies them.
        Set<String> linkNames = symbolicLinkNames(archive);
        Set<String> claimed = new HashSet<>();
        List<Path> written = new ArrayList<>();
        long total = 0;
        int count = 0;

        try (InputStream raw = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new ArchiveRejected("archive has more than " + MAX_ENTRIES + " entries");
                }
                if (linkNames.contains(entry.getName())) {
                    throw new ArchiveRejected(
                            "archive contains a symbolic link: " + entry.getName());
                }
                Path target = destinationOf(root, entry.getName());
                String key = collisionKey(root, target);
                if (!claimed.add(key)) {
                    throw new ArchiveRejected("archive contains a duplicate path: " + entry.getName());
                }
                // A previously written file is not the only way to occupy a path: an existing
                // directory, or a file this loop did not create, both make the write unsafe.
                refuseSymlinkOnPath(root, target, entry.getName());

                Files.createDirectories(target.getParent());
                refuseSymlinkOnPath(root, target, entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                long n = copyBounded(zip, target);
                total += n;
                if (total > MAX_TOTAL_BYTES) {
                    throw new ArchiveRejected("archive expands beyond " + MAX_TOTAL_BYTES + " bytes");
                }
                written.add(target);
            }
        } catch (IOException | RuntimeException failure) {
            // Nothing half-written may survive: a later run reads the directory, not a flag.
            for (int i = written.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(written.get(i));
                } catch (IOException ignored) {
                    // Best effort; the caller deletes the temporary root regardless.
                }
            }
            throw failure;
        }

        if (count == 0) {
            throw new ArchiveRejected("archive contains no entries");
        }
    }

    /**
     * The destination for an entry name, or a refusal.
     *
     * <p>The name is checked before it is resolved, because resolving it first would discard the
     * very information that identifies the attack: {@code ../x} and {@code x} resolve to the same
     * path only after the traversal has already been applied.
     */
    private static Path destinationOf(Path root, String name) throws ArchiveRejected {
        if (name == null || name.isEmpty()) {
            throw new ArchiveRejected("archive contains an entry with no name");
        }
        if (name.indexOf('\0') >= 0) {
            throw new ArchiveRejected("archive entry name contains a NUL byte");
        }

        // Windows path forms are checked on every platform. An archive is not trusted to know
        // which operating system will read it, and a `C:\...` entry extracted on Linux would land
        // as a file literally named `C:\...` -- harmless there, but the same archive is published
        // for Windows, where it would not be.
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new ArchiveRejected("archive contains an absolute path: " + name);
        }
        if (normalized.length() >= 2 && normalized.charAt(1) == ':'
                && Character.isLetter(normalized.charAt(0))) {
            throw new ArchiveRejected("archive contains a drive-qualified path: " + name);
        }
        if (normalized.startsWith("//")) {
            throw new ArchiveRejected("archive contains a UNC path: " + name);
        }

        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new ArchiveRejected("archive contains a traversal path: " + name);
            }
        }

        Path target = root.resolve(normalized).normalize();
        if (!target.startsWith(root)) {
            throw new ArchiveRejected("archive entry escapes the extraction root: " + name);
        }
        return target;
    }

    /** The key two entries share when they name one file on this filesystem. */
    private static String collisionKey(Path root, Path target) {
        String relative = root.relativize(target).toString();
        return caseInsensitive() ? relative.toLowerCase(Locale.ROOT) : relative;
    }

    /**
     * Whether the default filesystem treats two names differing only in case as one file.
     *
     * <p>Windows and macOS do, and on those systems {@code Foo.class} and {@code foo.class} are
     * one entry, so an archive could otherwise ship two payloads under names a byte comparison
     * calls distinct.
     */
    private static boolean caseInsensitive() {
        String os = System.getProperty("os.name", "");
        String lower = os.toLowerCase(Locale.ROOT);
        return lower.startsWith("windows") || lower.startsWith("mac");
    }

    /**
     * Refuses when any existing component of {@code target} is a symbolic link.
     *
     * <p>Writing through a link that already exists is the attack this closes: an archive cannot
     * create the link itself, because links are refused, but the extraction root is a directory
     * the caller chose and a previous failed run -- or another process -- may have left one.
     */
    private static void refuseSymlinkOnPath(Path root, Path target, String name)
            throws ArchiveRejected, IOException {
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new ArchiveRejected(
                        "archive entry would be written through a symbolic link: " + name);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
        }
    }

    /**
     * Copies one entry, refusing to write more than the per-entry limit.
     *
     * <p>The stream is deliberately not closed here: it is the archive, and closing it would end
     * the loop after the first entry. {@code ZipInputStream} reads a limited number of bytes per
     * entry and returns -1 at the entry's end, which is what bounds this copy.
     */
    private static long copyBounded(ZipInputStream in, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        while (true) {
            int n = in.read(buffer);
            if (n < 0) {
                break;
            }
            written += n;
            if (written > MAX_ENTRY_BYTES) {
                throw new ArchiveRejected(
                        "archive entry expands beyond " + MAX_ENTRY_BYTES + " bytes");
            }
            Files.write(target,
                    java.util.Arrays.copyOf(buffer, n),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        return written;
    }

    /**
     * The names of entries the archive marks as symbolic links.
     *
     * <p>The Unix mode lives in the central directory's external-attributes field, which
     * {@link ZipEntry} does not expose, so the record is read here directly. This matters because
     * a link entry carries its target as ordinary contents: an extractor that only knows
     * "directory or file" would write `/etc/passwd` into a file called `link.txt`, which is not
     * the escape the attacker wanted but is still not the archive's meaning. The extraction must
     * refuse rather than quietly produce a broken runtime.
     *
     * <p>A malformed archive yields no names, and the traversal and duplicate checks still apply;
     * this method is one gate, not the only one.
     */
    private static Set<String> symbolicLinkNames(Path archive) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        Set<String> names = new HashSet<>();

        int eocd = -1;
        // The end-of-central-directory record is last, followed by a comment of at most 64 KiB.
        for (int i = bytes.length - 22; i >= 0 && i >= bytes.length - 22 - 0xFFFF; i--) {
            if (readInt(bytes, i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return names;
        }

        long count = Integer.toUnsignedLong(readShort(bytes, eocd + 10));
        long offset = Integer.toUnsignedLong(readInt(bytes, eocd + 16));
        int at = (int) offset;
        for (long n = 0; n < count; n++) {
            if (at < 0 || at + 46 > bytes.length || readInt(bytes, at) != 0x02014b50) {
                break;
            }
            int nameLength = readShort(bytes, at + 28);
            int extraLength = readShort(bytes, at + 30);
            int commentLength = readShort(bytes, at + 32);
            int externalAttributes = readInt(bytes, at + 38);
            String name = new String(bytes, at + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8);

            // The high 16 bits hold the Unix mode; 0xA000 masked over the file-type bits is a link.
            int mode = externalAttributes >>> 16;
            if ((mode & 0xF000) == 0xA000) {
                names.add(name);
            }
            at += 46 + nameLength + extraLength + commentLength;
        }
        return names;
    }

    private static int readShort(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF)
                | ((bytes[at + 1] & 0xFF) << 8)
                | ((bytes[at + 2] & 0xFF) << 16)
                | ((bytes[at + 3] & 0xFF) << 24);
    }
}
