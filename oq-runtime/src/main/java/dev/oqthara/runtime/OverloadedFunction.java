package dev.oqthara.runtime;

import java.util.ArrayList;
import java.util.List;

public final class OverloadedFunction implements OqCallable {
    private final String name;
    private final List<OqCallable> candidates;

    public OverloadedFunction(String name, List<OqCallable> candidates) {
        this.name = name;
        this.candidates = new ArrayList<>(candidates);
    }

    public static OqCallable combine(OqCallable first, OqCallable second) {
        List<OqCallable> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add(first.name());
        names.add(second.name());
        candidates.add(first);
        candidates.add(second);
        return new OverloadedFunction(names.get(0), candidates);
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
        for (OqCallable candidate : candidates) {
            if (candidate instanceof NativeFunction function && function.accepts(args.size())) {
                return function.call(host, args, span);
            }
        }
        return candidates.get(0).call(host, args, span);
    }
}
