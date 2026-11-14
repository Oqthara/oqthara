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
import java.util.zip.GZIPInputStream;
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
 * <p>Symbolic links are a different case for each format, because the formats differ. A ZIP
 * entry that is a link is refused outright: the JDK ZIP is the Windows archive and contains none,
 * so supporting one would be a requirement that does not exist. A tar entry that is a link is
 * created, but only when its target is inside the root — ADR-0048 D4 permits exactly that, and
 * the Linux and macOS JDK tarballs require it, because they carry 205 relative {@code legal/}
 * cross-references between modules. Refusing every link there would make every non-Windows install
 * impossible.
 *
 * <p>The containment test for a tar link is not a string comparison, and getting this wrong is
 * easy enough that it is worth recording. {@link Path#normalize} is textual, so it cancels a
 * component against a following {@code ..} without asking whether that component is a link. Two
 * entries are then enough to escape: {@code bin/q -> .} is accepted because {@code bin/q/.} is
 * {@code bin}, and {@code bin/java -> q/../../victim} is accepted because {@code normalize} cancels
 * {@code q}, while the kernel resolves {@code q} to {@code bin} and the {@code ../..} leaves. The
 * same escape works with the two entries in the opposite order. Entry order belongs to the
 * attacker, so containment is decided once, after the whole archive has been written, by
 * resolving every link with {@link Path#toRealPath} and requiring the result to lie inside the
 * resolved root. A per-entry check is also applied, but only as an early refusal.
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

    /**
     * The largest central directory this will read, in bytes.
     *
     * <p>CRC-bearing entry names and extras for {@link #MAX_ENTRIES} entries fit under this; the
     * point of the cap is that the directory is read into an array, so a figure is needed. The
     * archive as a whole is never read into memory: only this tail is.
     */
    private static final long MAX_CENTRAL_DIRECTORY_BYTES = 64L * 1024 * 1024;

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
        // The format is read from the bytes, not from the file name. A name is a claim made by
        // whoever wrote the file, and this method exists because that claim is not trustworthy:
        // dispatching on the suffix would let a gzip stream named `.zip` reach the ZIP reader,
        // which would find no entries and report a corrupt archive rather than the real problem.
        if (isGzip(archive)) {
            extractTarGz(archive, root);
        } else {
            extractZip(archive, root);
        }
    }

    /** True when {@code archive} begins with the two-byte gzip magic number. */
    private static boolean isGzip(Path archive) throws IOException {
        byte[] magic = new byte[2];
        try (InputStream in = Files.newInputStream(archive)) {
            int read = in.readNBytes(magic, 0, 2);
            return read == 2 && (magic[0] & 0xFF) == 0x1F && (magic[1] & 0xFF) == 0x8B;
        }
    }

    /**
     * Extracts a ZIP archive.
     *
     * <p>ZIP is what the Windows artefact is, and the JDK ships no others.
     */
    private static void extractZip(Path archive, Path root) throws IOException {

        // Every path already claimed, so a later entry cannot overwrite an earlier one. Two
        // spellings of one path are the same collision, which is why the key is lowercased on
        // Windows and macOS: those filesystems would treat `Foo` and `foo` as one file, and an
        // archive could use that to slip a second payload past a name check.
        // Links are found from the central directory, before any entry is written, because
        // `ZipInputStream` discards the Unix mode that identifies them.
        Directory directory = centralDirectory(archive);
        Set<String> linkNames = directory.links();
        Set<String> executables = directory.executables();
        Set<Path> executableTargets = new java.util.LinkedHashSet<>();
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
                if (executables.contains(entry.getName())) {
                    executableTargets.add(target);
                }
            }
            // Applied last, so a tree that failed partway is never mistaken for one that has
            // been fully prepared. On a filesystem without the POSIX bits this is a no-op and
            // the runtime's launcher is left as the extractor wrote it.
            for (Path target : executableTargets) {
                makeExecutable(target);
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
     * Extracts a gzipped tar archive.
     *
     * <p>Four of the five pinned artefacts are tarballs, so this is the common path rather than an
     * afterthought: the ZIP reader alone could install on Windows and nowhere else.
     *
     * <p>The same rule applies as for ZIP — every entry's resolved destination must be inside the
     * root — with one difference. The Temurin tarballs contain 205 symbolic links, all of them
     * relative references between the {@code legal/} directories, so refusing links outright
     * would make every non-Windows install impossible. ADR-0048 D4 allows exactly this: links may
     * be created "only when their target is inside the destination". The containment test cannot
     * be done per entry — see {@link #refuseLinksThatEscape} for why entry order defeats any such
     * check — so it is done once, after the archive is fully written, by resolving each link the
     * way the kernel will and comparing against the resolved root. The write-through check still
     * runs for every later entry, so an escaping link cannot be used as a foothold before that
     * final pass runs either.
     *
     * <p>Hard links are refused. They are the same escape (a name for a file outside the root) and
     * no pinned archive contains one, so there is no case to support.
     *
     * <p>Every header checksum is verified. A tar header carries its own checksum and a corrupt
     * one would make the field offsets — including the size — meaningless, so a mismatch is
     * refused rather than trusted.
     */
    private static void extractTarGz(Path archive, Path root) throws IOException {
        Set<String> claimed = new HashSet<>();
        Set<Path> executableTargets = new java.util.LinkedHashSet<>();
        List<Path> written = new ArrayList<>();
        List<Path> links = new ArrayList<>();
        long total = 0;
        int count = 0;

        try (InputStream raw = Files.newInputStream(archive);
             GZIPInputStream gz = new GZIPInputStream(raw, 64 * 1024)) {
            byte[] header = new byte[512];
            String pendingName = null;
            while (true) {
                int read = gz.readNBytes(header, 0, header.length);
                if (read == 0) {
                    break;
                }
                if (read < header.length) {
                    throw new ArchiveRejected("tar header is truncated: " + read
                            + " of 512 bytes were present");
                }
                if (isZeroBlock(header)) {
                    // The end-of-archive marker. Reading on would parse padding, or a second
                    // concatenated member's contents, as if it were an entry.
                    break;
                }
                verifyHeaderChecksum(header);

                char type = (char) (header[156] & 0xFF);
                long size = octal(header, 124, 12, "size");
                String name = pendingName != null ? pendingName : entryName(header);
                pendingName = null;

                if (type == 'L') {
                    // GNU long name: the payload is the name of the *next* entry.
                    if (size <= 0 || size > 8192) {
                        throw new ArchiveRejected("tar long-name entry has an implausible size: "
                                + size);
                    }
                    pendingName = new String(readExactly(gz, (int) size),
                            java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\0", "").strip();
                    skipPadding(gz, size);
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new ArchiveRejected("archive has more than " + MAX_ENTRIES + " entries");
                }
                if (type == '1') {
                    throw new ArchiveRejected("archive contains a hard link: " + name);
                }
                if (type != '0' && type != '\0' && type != '5' && type != '2') {
                    throw new ArchiveRejected("archive contains an unsupported entry type '"
                            + type + "': " + name);
                }

                Path target = destinationOf(root, name);
                String key = collisionKey(root, target);
                if (!claimed.add(key)) {
                    throw new ArchiveRejected("archive contains a duplicate path: " + name);
                }
                refuseSymlinkOnPath(root, target, name);
                Files.createDirectories(target.getParent());
                refuseSymlinkOnPath(root, target, name);

                if (type == '5') {
                    Files.createDirectories(target);
                    skipPadding(gz, size);
                    continue;
                }
                if (type == '2') {
                    Path linkTarget = linkTargetOf(root, target,
                            text(header, 157, 100), name);
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, linkTarget);
                    written.add(target);
                    links.add(target);
                    skipPadding(gz, size);
                    continue;
                }

                long n = copyExactly(gz, target, size);
                total += n;
                if (total > MAX_TOTAL_BYTES) {
                    throw new ArchiveRejected("archive expands beyond " + MAX_TOTAL_BYTES + " bytes");
                }
                written.add(target);
                if ((octal(header, 100, 8, "mode") & 0100) != 0) {
                    executableTargets.add(target);
                }
                skipPadding(gz, size);
            }

            refuseLinksThatEscape(root, links);
            for (Path target : executableTargets) {
                makeExecutable(target);
            }
        } catch (IOException | RuntimeException failure) {
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

    /** The entry name, joining the ustar {@code prefix} field when one is present. */
    private static String entryName(byte[] header) {
        String name = text(header, 0, 100);
        String prefix = text(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /**
     * Refuses any created link whose physical target is outside the root, and any that does not
     * resolve at all.
     *
     * <p>{@link #linkTargetOf} refuses a target that traverses a link that already exists, which
     * stops the obvious two-entry chain. It cannot stop the reverse order: a link {@code q -> ../..}
     * is created while {@code q} is the only link, and a later entry {@code a -> q/jdk} is then free
     * to point through it, because at the moment {@code q/jdk} was checked the segment walk found
     * no link on it. Entry order is the attacker's to choose, so no per-entry check can be
     * order-independent.
     *
     * <p>This pass therefore runs once, after every entry has been written, when the tree is
     * complete and the answer no longer depends on order. Each link is resolved with
     * {@link Path#toRealPath}, which follows links the way the kernel does, and the result must lie
     * inside the root. The root is resolved first, because on macOS a temporary directory is behind
     * {@code /private} and comparing a resolved path against an unresolved root would fail for
     * every link. A link that does not resolve is refused too: no vendor archive produces a
     * dangling link, and accepting one would leave a name whose meaning is decided later.
     *
     * <p>This is why the class can create links at all. ADR-0048 D4 permits a link whose target is
     * inside the destination, and that cannot be established correctly one entry at a time — so it
     * is established at the first moment it can be, when the archive is fully unpacked and nothing
     * has been activated.
     */
    private static void refuseLinksThatEscape(Path root, List<Path> links) throws IOException {
        if (links.isEmpty()) {
            return;
        }
        Path realRoot = root.toRealPath();
        for (Path link : links) {
            String name = root.relativize(link).toString().replace('\\', '/');
            Path real;
            try {
                real = link.toRealPath();
            } catch (IOException unresolved) {
                throw new ArchiveRejected("archive contains a symbolic link that does not resolve"
                        + " inside the extraction root: " + name);
            }
            if (!real.startsWith(realRoot)) {
                throw new ArchiveRejected("archive contains a symbolic link that resolves outside"
                        + " the extraction root: " + name);
            }
        }
    }

    /**
     * The target a symbolic link may be created with, or a refusal.
     *
     * <p>ADR-0048 D4 allows a link only when its target is inside the destination. Testing that
     * containment by normalising the joined path is <em>not</em> sufficient, and the reason is
     * worth stating because the obvious implementation is wrong: {@link Path#normalize} is purely
     * textual, so it cancels a component against a following {@code ..} without ever asking the
     * filesystem whether that component is a directory. Two entries are enough to abuse it. A link
     * {@code bin/q -> .} is accepted, because {@code bin/q/.} normalises to {@code bin}. A second
     * link {@code bin/java -> q/../../../victim} also normalises to a path inside the root, since
     * {@code q} is cancelled by the first {@code ..} — while the kernel resolves {@code q} to
     * {@code bin} and the remaining {@code ../..} climb out. The stored text then means something
     * different again once the staging directory is renamed into place.
     *
     * <p>So the target is walked segment by segment from the link's own directory, and any segment
     * that exists as a symbolic link is refused rather than followed. A target is therefore a
     * path through ordinary directories only, which makes lexical and physical resolution agree
     * and makes the meaning independent of when the link is created. This does not refuse the
     * links a real JDK carries: those are relative {@code legal/java.base/...} references into
     * ordinary directories, 205 of them in the Linux tarball and none escaping the root.
     *
     * <p>Following a link that points inside would be sound in principle, but proving it needs a
     * resolution loop that re-checks each hop against the root within a bounded number of hops.
     * Refusing is the stronger rule and costs nothing here, because no vendor archive needs it.
     */
    private static Path linkTargetOf(Path root, Path link, String linkName, String entryName)
            throws ArchiveRejected {
        if (linkName.isEmpty()) {
            throw new ArchiveRejected("archive contains a symbolic link with no target: "
                    + entryName);
        }
        Path declared = Path.of(linkName);
        if (declared.isAbsolute()) {
            throw new ArchiveRejected("archive contains a symbolic link to an absolute path: "
                    + entryName + " -> " + linkName);
        }
        Path parent = link.getParent();
        if (parent == null) {
            throw new ArchiveRejected("archive symbolic link has no parent: " + entryName);
        }

        Path current = parent;
        for (Path segment : declared) {
            String name = segment.toString();
            if (name.isEmpty() || name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                current = current.getParent();
                if (current == null || !current.startsWith(root)) {
                    throw new ArchiveRejected("archive contains a symbolic link that leaves the"
                            + " extraction root: " + entryName + " -> " + linkName);
                }
                continue;
            }
            current = current.resolve(name);
            if (!current.startsWith(root)) {
                throw new ArchiveRejected("archive contains a symbolic link that leaves the"
                        + " extraction root: " + entryName + " -> " + linkName);
            }
            if (Files.isSymbolicLink(current)) {
                throw new ArchiveRejected("archive contains a symbolic link whose target"
                        + " traverses another symbolic link, which cannot be shown to stay inside"
                        + " the extraction root: " + entryName + " -> " + linkName);
            }
        }
        return declared;
    }

    /** True when every byte of {@code block} is zero, which marks the end of a tar archive. */
    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses a header whose recorded checksum does not match its contents.
     *
     * <p>Everything after this point trusts the header's offsets, including the size used to
     * advance the stream, so an unverified header is a stream-position error as much as an
     * integrity one.
     */
    private static void verifyHeaderChecksum(byte[] header) throws ArchiveRejected {
        long recorded = octal(header, 148, 8, "checksum");
        long actual = 0;
        for (int i = 0; i < header.length; i++) {
            actual += (i >= 148 && i < 156) ? 0x20 : (header[i] & 0xFF);
        }
        if (recorded != actual) {
            throw new ArchiveRejected("tar header checksum mismatch: recorded " + recorded
                    + ", computed " + actual);
        }
    }

    /**
     * Reads a fixed-width octal field.
     *
     * <p>A GNU base-256 field is refused rather than decoded: it exists for sizes above 8 GiB,
     * no JDK archive uses one, and accepting it would mean this parser had a second numeric
     * format to be wrong about.
     */
    private static long octal(byte[] header, int offset, int length, String field)
            throws ArchiveRejected {
        if ((header[offset] & 0x80) != 0) {
            throw new ArchiveRejected("tar " + field + " field uses base-256, which is not supported");
        }
        String raw = text(header, offset, length).strip();
        if (raw.isEmpty()) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '7') {
                throw new ArchiveRejected("tar " + field + " field is not octal: '" + raw + "'");
            }
            value = value * 8 + (c - '0');
            if (value < 0) {
                throw new ArchiveRejected("tar " + field + " field overflows a long");
            }
        }
        return value;
    }

    /** Reads a NUL-terminated field as text. */
    private static String text(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Reads exactly {@code n} bytes, refusing a stream that ends early. */
    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] data = in.readNBytes(n);
        if (data.length != n) {
            throw new ArchiveRejected("tar entry is truncated: expected " + n
                    + " bytes, read " + data.length);
        }
        return data;
    }

    /** Writes exactly {@code size} bytes from the stream, bounded by the per-entry limit. */
    private static long copyExactly(InputStream in, Path target, long size) throws IOException {
        if (size > MAX_ENTRY_BYTES) {
            throw new ArchiveRejected("archive entry declares " + size
                    + " bytes, beyond the " + MAX_ENTRY_BYTES + " limit");
        }
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        try (java.io.OutputStream out = Files.newOutputStream(target)) {
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int n = in.readNBytes(buffer, 0, want);
                if (n <= 0) {
                    throw new ArchiveRejected("tar entry is truncated: " + remaining
                            + " bytes missing from " + target.getFileName());
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
        return size;
    }

    /** Discards the padding that brings an entry's payload up to a whole block. */
    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        if (padding > 0) {
            readExactly(in, (int) padding);
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
     * Adds the owner-execute bit, and only that bit.
     *
     * <p>Called for entries the archive marked executable. {@code setExecutable} rather than a
     * POSIX permission set because it degrades to a no-op on a filesystem that has no such bits,
     * and an installer that fails because a filesystem lacks a permission model is worse than one
     * that installs a runtime the user can start with {@code java -jar}.
     */
    private static void makeExecutable(Path target) {
        // Deliberately ignored: if the bit cannot be set, the install still completes and the
        // runtime's own launcher reports the problem more clearly than a wrapping exception.
        target.toFile().setExecutable(true, true);
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
    /**
     * What the central directory says about entries, which {@link ZipEntry} discards.
     *
     * @param links entries the archive marks as symbolic links
     * @param executables entries the archive marks with any execute bit
     */
    private record Directory(Set<String> links, Set<String> executables) {
    }

    /**
     * Reads the two attributes that matter from the central directory.
     *
     * <p>The Unix mode lives in external attributes, which {@link ZipEntry} does not expose, so a
     * JDK archive's {@code bin/java} arrives looking like any other file. Extraction that ignored
     * the mode would install a runtime whose launcher cannot be executed — the install would
     * succeed and every later command would fail, which is the wrong place to discover it.
     *
     * <p>Only the execute bits are read. Writing the full mode would mean honouring setuid and
     * setgid from an untrusted archive, which is not something an installer should ever do.
     */
    private static Directory centralDirectory(Path archive) throws IOException {
        Tail tail = readCentralDirectory(archive);
        byte[] bytes = tail.bytes();
        Set<String> names = new HashSet<>();
        Set<String> executables = new HashSet<>();

        int eocd = -1;
        // The end-of-central-directory record is last, followed by a comment of at most 64 KiB.
        for (int i = bytes.length - 22; i >= 0 && i >= bytes.length - 22 - 0xFFFF; i--) {
            if (readInt(bytes, i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return new Directory(names, executables);
        }

        long count = Integer.toUnsignedLong(readShort(bytes, eocd + 10));
        long offset = Integer.toUnsignedLong(readInt(bytes, eocd + 16)) - tail.base();
        if (offset < 0 || offset >= bytes.length) {
            return new Directory(names, executables);
        }
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
            } else if ((mode & 0111) != 0) {
                executables.add(name);
            }
            at += 46 + nameLength + extraLength + commentLength;
        }
        return new Directory(names, executables);
    }

    /**
     * Reads the tail that holds the central directory, without reading the archive.
     *
     * <p>{@code Files.readAllBytes} made the size of the archive a matter of heap: a file of any
     * length was loaded whole before a single cap applied, so the caps below the call could not
     * be reached by the one input that most needs them. The end-of-central-directory record is
     * in the last 64 KiB plus a comment, and the directory before it is bounded here, so a
     * bounded read of the tail is enough. The empty array means the archive had no directory to
     * read, which the caller treats as "no modes to honour".
     */
    static Tail readCentralDirectory(Path archive) throws IOException {
        long length = Files.size(archive);
        if (length == 0) {
            return new Tail(new byte[0], 0);
        }
        long tail = Math.min(length, MAX_CENTRAL_DIRECTORY_BYTES + 0xFFFF + 22);
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(archive)) {
            channel.position(length - tail);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) tail);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Fill to the end; a short read is retried rather than truncated, because a
                // truncated tail would lose the directory without saying so.
            }
            return new Tail(buffer.array(), length - tail);
        }
    }

    /**
     * The bytes read from the end of an archive, and where they start in it.
     *
     * <p>The central directory's start is stored as an offset from the beginning of the file, so
     * a parser working on a tail has to subtract {@code base} to get back to a position in
     * {@code bytes}. Keeping the two together is what stops the subtraction being forgotten.
     */
    record Tail(byte[] bytes, long base) {
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
