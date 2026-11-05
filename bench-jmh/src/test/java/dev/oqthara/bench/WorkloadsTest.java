package dev.oqthara.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.oqthara.core.Ast;
import org.junit.jupiter.api.Test;

/**
 * Keeps the benchmarks honest without running JMH in CI: each benchmark source
 * must parse and produce exactly the output its setup asserts.
 */
class WorkloadsTest {

    @Test
    void everyWorkloadProducesItsExpectedOutput() {
        for (Workloads.Workload workload : Workloads.all()) {
            Ast.Program program = Runner.parse(workload);
            String output = Runner.run(workload, program);
            assertEquals(workload.expected(), output, workload.name());
            assertFalse(output.isBlank(), workload.name() + " printed nothing");
        }
    }

    @Test
    void verificationRejectsAWrongExpectation() {
        Workloads.Workload broken = new Workloads.Workload(
                "broken", Workloads.NUMERIC_LOOP.source(), "0\n");
        Ast.Program program = Runner.parse(broken);
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> Runner.verify(broken, program));
        assertFalse(error.getMessage().contains("500500\n"), "message should not leak passed output");
    }
}
