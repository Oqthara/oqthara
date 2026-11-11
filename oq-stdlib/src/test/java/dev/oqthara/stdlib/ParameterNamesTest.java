package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OverloadedFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published parameter names every builtin may carry for signature help (ADR-0044).
 *
 * <p>The names are a second copy of information the body already encodes, so the risk is not
 * that a name is missing but that one is wrong: a name list longer or shorter than the arity
 * would silently shift every label by one, and a duplicate would make two labels
 * indistinguishable. Both are structural, so they are checked mechanically over the whole
 * builtin table rather than spot-checked on a few functions.
 *
 * <p>Naming is optional because some builtins have nothing to name. The exception is only
 * accepted where the arity explains it, so a fixed-arity builtin cannot quietly ship unnamed.
 */
class ParameterNamesTest {

    private static Map<String, OqCallable> builtins() {
        Interpreter interpreter = Interpreter.headless();
        Stdlib.install(interpreter);
        return interpreter.builtins();
    }

    private static List<NativeFunction> candidates(OqCallable callable) {
        List<NativeFunction> found = new ArrayList<>();
        if (callable instanceof NativeFunction single) {
            found.add(single);
        } else if (callable instanceof OverloadedFunction set) {
            for (OqCallable candidate : set.candidates()) {
                if (candidate instanceof NativeFunction nativeFunction) {
                    found.add(nativeFunction);
                }
            }
        }
        return found;
    }

    @Test
    void everyNameListMatchesTheArityItLabels() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, OqCallable> entry : builtins().entrySet()) {
            for (NativeFunction function : candidates(entry.getValue())) {
                List<String> names = function.parameterNames();
                if (names.isEmpty()) continue;
                Set<String> distinct = new LinkedHashSet<>(names);
                if (distinct.size() != names.size()) {
                    problems.add(function.name() + ": duplicate parameter name in " + names);
                }
                if (function.maxArity() == Integer.MAX_VALUE) {
                    if (names.size() < function.minArity()) {
                        problems.add(function.name() + ": " + names.size()
                                + " names cannot label " + function.minArity() + " required parameters");
                    }
                } else if (names.size() != function.maxArity()) {
                    problems.add(function.name() + ": " + names.size()
                            + " names for an arity of " + function.maxArity());
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void onlyBuiltinsWithoutNamesAreTheOnesWithNothingToName() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, OqCallable> entry : builtins().entrySet()) {
            for (NativeFunction function : candidates(entry.getValue())) {
                if (!function.parameterNames().isEmpty()) continue;
                boolean nothingToName = function.maxArity() == 0
                        || function.maxArity() == Integer.MAX_VALUE;
                if (!nothingToName) {
                    problems.add(function.name() + ": accepts " + function.minArity()
                            + " to " + function.maxArity() + " arguments but publishes no names");
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void namesAreReachableThroughTheOverloadSet() {
        // range is registered three times, so it exercises the set path rather than the
        // single-callable one; its one-argument form is the smallest useful check.
        OqCallable range = builtins().get("range");
        assertTrue(range instanceof OverloadedFunction,
                "range should be an overload set, but was " + range.getClass().getSimpleName());
        List<List<String>> forms = new ArrayList<>();
        for (NativeFunction function : candidates(range)) {
            forms.add(function.parameterNames());
        }
        assertTrue(forms.contains(List.of("stop")), "range(stop) names were " + forms);
        assertTrue(forms.contains(List.of("start", "stop")), "range(start, stop) names were " + forms);
        assertTrue(forms.contains(List.of("start", "stop", "step")),
                "range(start, stop, step) names were " + forms);
    }

    @Test
    void aNamedFunctionIsStillTheSameCallable() {
        NativeFunction plain = NativeFunction.of("probe", 2, (host, args, span) -> null);
        NativeFunction named = plain.named("left", "right");
        assertEquals("probe", named.name());
        assertEquals(2, named.maxArity());
        assertEquals(List.of("left", "right"), named.parameterNames());
        assertEquals(List.of(), plain.parameterNames(),
                "naming a function must not mutate the original");
    }
}
