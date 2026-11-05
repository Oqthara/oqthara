package dev.oqthara.bench;

import java.util.List;

/**
 * Fixed interpreter workloads.
 *
 * <p>The programs are inlined rather than read from {@code bench/programs} because
 * those files use much larger sizes (5,000,000 iterations, {@code fib(28)}) tuned
 * for whole-process timing. JMH measures steady-state throughput in-process, so a
 * workload has to finish in a fraction of a second per invocation.
 *
 * <p>Every workload carries its exact expected stdout. The benchmark setup and the
 * unit test both compare against it, so a benchmark can never silently measure a
 * program that errors or prints the wrong thing.
 */
public final class Workloads {

    public record Workload(String name, String source, String expected) {}

    public static final Workload NUMERIC_LOOP = new Workload(
            "numericLoop",
            """
            sum = 0
            for i in 1 to 1000
              sum = sum + i
            end
            print sum
            """,
            "500500\n");

    public static final Workload FIB = new Workload(
            "fib",
            """
            fun fib(n)
              if n < 2
                return n
              end
              return fib(n - 1) + fib(n - 2)
            end
            print fib(20)
            """,
            "6765\n");

    public static final Workload LIST_OPS = new Workload(
            "listOps",
            """
            xs = []
            for i in 1 to 500
              xs.push((i * 37) % 500)
            end
            print sum(xs)
            print sort(xs)[0]
            print sort(xs)[-1]
            """,
            // (i * 37) % 500 for i in 1..500 is a permutation of 0..499.
            "124750\n0\n499\n");

    public static final Workload STRING_BUILD = new Workload(
            "stringBuild",
            """
            text = ""
            for i in 1 to 200
              text = text + "x"
            end
            print length(text)
            """,
            "200\n");

    public static final List<Workload> ALL =
            List.of(NUMERIC_LOOP, FIB, LIST_OPS, STRING_BUILD);

    public static List<Workload> all() {
        return ALL;
    }

    private Workloads() {}
}
