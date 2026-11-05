package dev.oqthara.bench;

import dev.oqthara.core.Ast;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Addition and loop overhead in the interpreter: 1000 iterations, then print. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class NumericLoopBenchmark {

    private static final Workloads.Workload WORKLOAD = Workloads.NUMERIC_LOOP;

    private Ast.Program program;

    @Setup
    public void setUp() {
        // Parse once: parsing is not what this workload measures.
        program = Runner.parse(WORKLOAD);
        Runner.verify(WORKLOAD, program);
    }

    @Benchmark
    public void numericLoop(Blackhole blackhole) {
        blackhole.consume(Runner.run(WORKLOAD, program));
    }
}
