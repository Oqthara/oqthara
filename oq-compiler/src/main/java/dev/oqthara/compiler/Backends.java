package dev.oqthara.compiler;

import java.util.List;

/**
 * The compiler exposes which bytecode backend is usable on the current JDK.
 *
 * <p>{@code java.lang.classfile} only exists on JDK 24 and later (finalised in 24).
 * JDK 21, the project baseline, does not ship it, so the classfile backend is resolved
 * at runtime rather than linked at compile time.
 */
public final class Backends {
    public static final String CLASSFILE_API = "java.lang.classfile.ClassFile";

    public enum Kind {
        CLASSFILE("classfile"),
        DIAGNOSTIC("diagnostic");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private Backends() {
    }

    public static boolean classfileAvailable() {
        try {
            Class.forName(CLASSFILE_API);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static Kind select() {
        return classfileAvailable() ? Kind.CLASSFILE : Kind.DIAGNOSTIC;
    }

    public static String diagnostic(Kind kind, int feature) {
        if (kind == Kind.CLASSFILE) {
            return "backend " + kind.id() + " is available";
        }
        if (feature < 24) {
            return "oq build needs JDK 24 or newer for java.lang.classfile (found feature "
                    + feature + "). Run oq on JDK 25, or use 'oq run' to interpret instead.";
        }
        return "oq build needs JDK 24 or newer for java.lang.classfile (found feature "
                + feature + ").";
    }

    public static int featureVersion() {
        String version = System.getProperty("java.specification.version", "21");
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 21;
        }
    }

    public static String describe() {
        Kind kind = select();
        return "backend=" + kind.id() + " jdk=" + featureVersion();
    }

    public static List<String> allBackendIds() {
        return List.of(Kind.CLASSFILE.id(), Kind.DIAGNOSTIC.id());
    }
}
