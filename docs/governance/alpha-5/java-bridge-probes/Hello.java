/**
 * The class {@code c11_user_classpath.oq} imports to check C11.1.
 *
 * C11.1 says a class on the user's own classpath is importable. That claim cannot be checked
 * with a class from inside `oq.jar`: the harness would be measuring the jar's own contents, not
 * the classpath. So this class must live in a jar of its own, built from this file and never
 * bundled with the CLI.
 *
 * The source is committed and the jar is built at run time. A committed jar would be invisible to
 * review and would need regenerating by hand whenever this changed; `run.py` compiles it into a
 * scratch directory each run instead. The `package` line is deliberately absent so the probe can
 * write `use Hello` rather than a qualified name, which is what C11.1 is about.
 */
public final class Hello {
    private Hello() {
    }

    public static String greet() {
        return "hi world";
    }
}
