package dev.oqthara.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A set of callables sharing one name, resolved by argument count.
 *
 * <p>Signature identity is (name, parameter count); see ADR-0012. Resolution does not
 * depend on the order candidates were added, and a call that matches nothing is an
 * error rather than a fall-through to an unrelated candidate.
 */
public final class OverloadedFunction implements OqCallable {
    private final String name;
    private final List<OqCallable> candidates;

    public OverloadedFunction(String name, List<OqCallable> candidates) {
        this.name = name;
        this.candidates = List.copyOf(candidates);
    }

    /** Builds a set, or returns the single callable unchanged when there is nothing to choose. */
    public static OqCallable of(String name, List<OqCallable> callables) {
        if (callables.size() == 1) {
            return callables.get(0);
        }
        return new OverloadedFunction(name, callables);
    }

    /**
     * Adds one callable to a name, flattening an existing set so that a third declaration
     * extends the set instead of nesting inside it.
     */
    public static OqCallable combine(OqCallable existing, OqCallable added) {
        List<OqCallable> merged = new ArrayList<>();
        if (existing instanceof OverloadedFunction set) {
            merged.addAll(set.candidates);
        } else {
            merged.add(existing);
        }
        if (added instanceof OverloadedFunction set) {
            merged.addAll(set.candidates);
        } else {
            merged.add(added);
        }
        return new OverloadedFunction(existing.name(), merged);
    }

    /**
     * Returns the one callable an argument count resolves to.
     *
     * <p>A count that matches nothing, or matches more than one distinct definition, is an
     * error. Neither case is resolved by picking a candidate.
     */
    public OqCallable resolve(int argc, Span span) {
        List<OqCallable> matches = new ArrayList<>();
        for (OqCallable candidate : candidates) {
            if (candidate.arity() == argc) {
                matches.add(candidate);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new OqError(name + "() does not accept " + argc + " argument"
                    + (argc == 1 ? "" : "s"), span,
                    "Accepted: " + describeArities() + ".");
        }
        throw new OqError("call to '" + name + "' is ambiguous for " + argc + " argument"
                + (argc == 1 ? "" : "s"), span,
                "More than one definition accepts that many arguments. "
                        + "Give them different numbers of parameters, or call a different name.");
    }

    /** Reports the parameter counts this set accepts, smallest first. */
    public String describeArities() {
        Set<Integer> arities = new LinkedHashSet<>();
        boolean variadic = false;
        for (OqCallable candidate : candidates) {
            int arity = candidate.arity();
            if (arity == VARIADIC) {
                variadic = true;
            } else {
                arities.add(arity);
            }
        }
        List<Integer> sorted = new ArrayList<>(arities);
        sorted.sort(null);
        StringBuilder sb = new StringBuilder();
        for (int arity : sorted) {
            if (sb.length() > 0) {
                sb.append(" or ");
            }
            sb.append(arity).append(" argument").append(arity == 1 ? "" : "s");
        }
        if (variadic) {
            if (sb.length() > 0) {
                sb.append(" or ");
            }
            sb.append("more");
        }
        return sb.length() == 0 ? "no arguments" : sb.toString();
    }

    @Override
    public String name() {
        return name;
    }

    public List<OqCallable> candidates() {
        return candidates;
    }

    @Override
    public Object call(OqHost host, List<Object> args, Span span) {
        return resolve(args.size(), span).call(host, args, span);
    }
}
