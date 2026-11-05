package dev.oqthara.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Emits real JVM bytecode for a subset of Oqthara: constants, variables, top-level functions
 * with parameters, calls, return values and recursion, arithmetic, comparisons, {@code print},
 * {@code if}, {@code while}, {@code for} and the boolean operators.
 *
 * <p>Everything compiled here runs without the interpreter. Anything else is refused with a
 * {@link NotSupportedException} so {@code oq build} can fall back to the launcher and print
 * why. Refusing is a feature: a jar that behaves differently from {@code oq run} would be
 * worse than no jar at all.
 *
 * <h2>Value representation</h2>
 * Every Oqthara value on the operand stack and in a local variable is a reference:
 * {@code Long}, {@code Double}, {@code Boolean}, {@code String} or {@code null}. One
 * representation means every local is an {@code astore}/{@code aload} slot and every stack map
 * frame is {@code java/lang/Object}, which keeps this emitter small enough to audit.
 * Arithmetic, comparison and truthiness live in {@code dev.oqthara.runtime.Arithmetic}, which
 * delegates to the same {@code Values} helpers the interpreter uses, so the two backends
 * cannot drift.
 *
 * <h2>Scopes</h2>
 * The interpreter creates a fresh environment for every if-branch, loop iteration and handler,
 * so a name assigned inside a block disappears at the end of it and may shadow an outer name.
 * The planner below reproduces that with a scope stack: each scope gets its own slots, and
 * slots are never reused, so shadowing cannot alias.
 *
 * <h2>Stack maps</h2>
 * Class files at version 50 and above are verified by type checking, so a method with any
 * branch must carry a {@code StackMapTable}. A frame is emitted at every label, and the
 * planner guarantees the operand stack is empty at each one, so every frame is a
 * {@code full_frame} with the same locals and an empty stack.
 */
public final class BytecodeCompiler {
    private static final int JAVA_21_MAJOR = 65;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;

    private static final int ACONST_NULL = 0x01;
    private static final int ICONST_0 = 0x03;
    private static final int ICONST_1 = 0x04;
    private static final int LDC_W = 0x13;
    private static final int LDC2_W = 0x14;
    private static final int ALOAD = 0x19;
    private static final int ASTORE = 0x3A;
    private static final int DUP = 0x59;
    private static final int POP = 0x57;
    private static final int IFEQ = 0x99;
    private static final int GOTO = 0xA7;
    private static final int INVOKESTATIC = 0xB8;
    /** JVMS: {@code areturn} is 0xB0 ({@code 0xAC} is {@code ireturn}). */
    private static final int ARETURN = 0xB0;
    private static final int RETURN = 0xB1;

    private static final String ARITHMETIC = "dev/oqthara/runtime/Arithmetic";
    private static final String COLLECTIONS = "dev/oqthara/runtime/Collections";

    /** Thrown when a program uses a construct outside the compiled subset. */
    public static final class NotSupportedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotSupportedException(String reason) {
            super(reason);
        }
    }

    private BytecodeCompiler() {
    }

    /** True when the IR can be compiled to real bytecode. */
    public static boolean canCompile(Ir.Program program) {
        try {
            compile(program, "oq.generated.Program");
            return true;
        } catch (NotSupportedException e) {
            return false;
        }
    }

    /** Human-readable reason a program cannot be compiled, or {@code null} when it can. */
    public static String whyNot(Ir.Program program) {
        try {
            compile(program, "oq.generated.Program");
            return null;
        } catch (NotSupportedException e) {
            return e.getMessage();
        }
    }

    /** Compiles the program into a class with a {@code main} that matches the interpreter. */
    public static byte[] compile(Ir.Program program, String className) {
        String internalName = className.replace('.', '/');
        // One pool for the whole class: every method's constants, every function's method
        // reference and the class's own entries share one index space.
        ConstantPool pool = new ConstantPool();

        Map<String, Integer> functionArities = new LinkedHashMap<>();
        for (Ir.Function function : program.functions()) {
            if (function.name().equals("print") || function.name().equals("write")) {
                throw new NotSupportedException("function '" + function.name()
                        + "' collides with the built-in command '" + function.name() + "'");
            }
            functionArities.put(function.name(), function.params().size());
        }

        // Main is planned first so its top-level variable names are known. A function that
        // mentions one of them is refused instead of compiled, which keeps closure and
        // global-mutation semantics on the interpreter where they cannot diverge.
        Method main = new Method(program.main().body(), 1, pool, internalName, functionArities);
        main.compileBody();
        Set<String> outerNames = main.topLevelNames();

        List<Method> methods = new ArrayList<>();
        methods.add(main);
        for (Ir.Function function : program.functions()) {
            Method method = new Method(function.body(), function.params().size(), pool,
                    internalName, functionArities);
            method.paramNames = function.params();
            method.outerNames = outerNames;
            method.isFunction = true;
            method.compileBody();
            methods.add(method);
        }

        int thisClass = pool.classRef(pool.utf8(internalName));
        int objectClass = pool.classRef(pool.utf8("java/lang/Object"));
        int codeAttribute = pool.utf8("Code");
        // Every constant must exist before the pool is serialized; adding one afterwards
        // leaves the pool's declared entry count stale and produces a ClassFormatError.
        int stackMapAttribute = pool.utf8("StackMapTable");
        int sourceFile = pool.utf8("SourceFile");
        int fileName = pool.utf8(className.substring(className.lastIndexOf('.') + 1) + ".java");

        int[] nameIndices = new int[methods.size()];
        int[] descriptorIndices = new int[methods.size()];
        byte[][] stackMaps = new byte[methods.size()][];
        nameIndices[0] = pool.utf8("main");
        descriptorIndices[0] = pool.utf8("([Ljava/lang/String;)V");
        stackMaps[0] = stackMap(pool, objectClass, main.frameOffsets(), main.localCount());
        for (int i = 1; i < methods.size(); i++) {
            Ir.Function function = program.functions().get(i - 1);
            nameIndices[i] = pool.utf8(function.name());
            descriptorIndices[i] = pool.utf8(functionDescriptor(function.params().size()));
            Method method = methods.get(i);
            stackMaps[i] = stackMap(pool, objectClass, method.frameOffsets(),
                    method.localCount());
        }

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(JAVA_21_MAJOR);
            pool.write(out);
            out.writeShort(ACC_PUBLIC);
            out.writeShort(thisClass);
            out.writeShort(objectClass);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(methods.size());
            for (int i = 0; i < methods.size(); i++) {
                writeMethod(out, nameIndices[i], descriptorIndices[i], codeAttribute,
                        stackMapAttribute, methods.get(i), stackMaps[i]);
            }
            out.writeShort(1); // class attributes
            out.writeShort(sourceFile);
            out.writeInt(2);
            out.writeShort(fileName);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes one method with its own {@code Code} attribute. The attribute length counts only
     * this method's bytecode and its own stack map, never another method's.
     */
    private static void writeMethod(DataOutputStream out, int nameIndex, int descriptorIndex,
            int codeAttribute, int stackMapAttribute, Method method, byte[] stackMap)
            throws IOException {
        byte[] code = method.code();
        int nested = stackMap == null ? 0 : 6 + stackMap.length;
        out.writeShort(ACC_PUBLIC | ACC_STATIC);
        out.writeShort(nameIndex);
        out.writeShort(descriptorIndex);
        out.writeShort(1);
        out.writeShort(codeAttribute);
        out.writeInt(12 + code.length + nested);
        out.writeShort(Math.max(2, method.maxStack()));
        out.writeShort(Math.max(1, method.localCount()));
        out.writeInt(code.length);
        out.write(code);
        out.writeShort(0); // exception table
        if (stackMap == null) {
            out.writeShort(0);
        } else {
            out.writeShort(1);
            out.writeShort(stackMapAttribute);
            out.writeInt(stackMap.length);
            out.write(stackMap);
        }
    }

    /** Every parameter is one boxed {@code Object}; the result is always an {@code Object}. */
    private static String functionDescriptor(int paramCount) {
        StringBuilder descriptor = new StringBuilder("(");
        for (int i = 0; i < paramCount; i++) {
            descriptor.append("Ljava/lang/Object;");
        }
        return descriptor.append(")Ljava/lang/Object;").toString();
    }

    /**
     * Emits one {@code full_frame} per label: the same locals, an empty stack. The first
     * frame's {@code offset_delta} is its byte offset; later ones are relative to the previous
     * frame minus one, as JVMS 4.7.4 requires.
     */
    private static byte[] stackMap(ConstantPool pool, int objectType, int[] offsets, int locals) {
        if (offsets.length == 0) return null;
        int objectInfo = 7; // Object_variable_info tag
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(offsets.length);
            int previous = -1;
            for (int offset : offsets) {
                out.writeByte(255); // full_frame
                int delta = previous < 0 ? offset : offset - previous - 1;
                out.writeShort(delta);
                out.writeShort(locals);
                for (int i = 0; i < locals; i++) {
                    out.writeByte(objectInfo);
                    out.writeShort(objectType);
                }
                out.writeShort(0); // empty operand stack
                previous = offset;
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A variable binding the planner resolved to a slot. */
    private record Binding(int slot, boolean fixed) {
    }

    /**
     * Compiles one method body: plans slots, then emits instructions and patches jump offsets.
     * Instruction sizes never depend on jump distances because every branch is emitted as a
     * three-byte 16-bit form, so one emission pass is enough.
     */
    private static final class Method {
        private final List<Ir.Instr> body;
        private final int paramCount;
        private final ConstantPool pool;
        private final String ownerInternalName;
        private final Map<String, Integer> functionArities;
        private final int startSlot;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();
        private final Map<String, Integer> labels = new LinkedHashMap<>();
        private final TreeSet<Integer> frames = new TreeSet<>();
        private int[] slots;
        private int localCount;
        private int depth = 0;
        private int peak = 0;
        private boolean reachable = true;
        private boolean isFunction = false;
        private List<String> paramNames = List.of();
        private Set<String> outerNames = Set.of();
        private Set<String> topLevelNames = Set.of();

        Method(List<Ir.Instr> body, int paramCount, ConstantPool pool, String ownerInternalName,
                Map<String, Integer> functionArities) {
            this.body = body;
            this.paramCount = paramCount;
            this.pool = pool;
            this.ownerInternalName = ownerInternalName;
            this.functionArities = functionArities;
            // Main's slot 0 holds the unused args array; function parameters occupy
            // 0..paramCount-1, so planned locals start after them.
            this.startSlot = paramCount;
            this.localCount = paramCount;
        }

        ConstantPool pool() {
            return pool;
        }

        int localCount() {
            return localCount;
        }

        int maxStack() {
            return peak + 2;
        }

        byte[] code() {
            return bytes.toByteArray();
        }

        int[] frameOffsets() {
            return frames.stream().mapToInt(Integer::intValue).filter(o -> o > 0).toArray();
        }

        Set<String> topLevelNames() {
            return topLevelNames;
        }

        /**
         * Resolves every load and store to a slot before anything is emitted, so the method's
         * local count is known up front and errors are reported before a class exists.
         */
        private int[] plan() {
            int[] resolved = new int[body.size()];
            Map<String, Binding> root = new LinkedHashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                root.put(paramNames.get(i), new Binding(i, false));
            }
            scopes.push(root);
            int next = startSlot;
            for (int i = 0; i < body.size(); i++) {
                resolved[i] = -1;
                switch (body.get(i)) {
                    case Ir.ScopeEnter ignored -> scopes.push(new LinkedHashMap<>());
                    case Ir.ScopeExit ignored -> {
                        if (scopes.size() <= 1) {
                            throw new NotSupportedException("unbalanced scope markers");
                        }
                        scopes.pop();
                    }
                    case Ir.Declare declare ->
                            resolved[i] = declare(declare.name(), declare.fixed(), next++);
                    case Ir.Store store -> resolved[i] = assign(store.name(), next++);
                    case Ir.Load load -> resolved[i] = lookup(load.name()).slot();
                    // Collection literals need one scratch slot per item (two per map
                    // entry); resolved[i] records the first slot.
                    case Ir.MakeList make -> {
                        resolved[i] = next;
                        next += make.count();
                        checkSlotLimit(next);
                    }
                    case Ir.MakeSet make -> {
                        resolved[i] = next;
                        next += make.count();
                        checkSlotLimit(next);
                    }
                    case Ir.MakeMap make -> {
                        resolved[i] = next;
                        next += make.count() * 2;
                        checkSlotLimit(next);
                    }
                    default -> {
                    }
                }
            }
            localCount = next;
            if (!isFunction) {
                // The root scope holds exactly the names declared at the top level of main;
                // nested blocks and loop bodies have their own scopes.
                topLevelNames = new LinkedHashSet<>(root.keySet());
            }
            return resolved;
        }

        private void checkSlotLimit(int next) {
            if (next > 255) {
                throw new NotSupportedException("too many variables for one method");
            }
        }

        /** VarDecl: always a new binding in the current scope, even if one is visible. */
        private int declare(String name, boolean fixed, int nextSlot) {
            if (nextSlot > 255) {
                throw new NotSupportedException("too many variables for one method");
            }
            scopes.peek().put(name, new Binding(nextSlot, fixed));
            return nextSlot;
        }

        /**
         * Assignment: update the nearest visible binding, or define one in the current scope.
         * That is exactly what the interpreter's {@code assignOrDefine} does, so a loop
         * counter introduced by the lowering behaves identically on both backends.
         */
        private int assign(String name, int nextSlot) {
            Binding visible = lookupOrNull(name);
            if (visible != null) {
                if (visible.fixed()) {
                    throw new NotSupportedException(
                            "'" + name + "' is a fixed value and the interpreter would reject"
                                    + " changing it, so this is not compiled");
                }
                return visible.slot();
            }
            refuseEnclosing(name);
            return declare(name, false, nextSlot);
        }

        private Binding lookup(String name) {
            Binding binding = lookupOrNull(name);
            if (binding == null) {
                refuseEnclosing(name);
                throw new NotSupportedException(
                        "variable '" + name + "' is used before assignment");
            }
            return binding;
        }

        /**
         * A function may only touch its own parameters and locals. Reading or writing a
         * top-level variable of main would be a closure or a global mutation, which the
         * interpreter handles differently, so it is refused instead of compiled.
         */
        private void refuseEnclosing(String name) {
            if (isFunction && outerNames.contains(name)) {
                throw new NotSupportedException("'" + name + "' is read from an enclosing scope;"
                        + " only functions that use their own parameters and locals are"
                        + " compiled");
            }
        }

        private Binding lookupOrNull(String name) {
            for (Map<String, Binding> scope : scopes) {
                Binding binding = scope.get(name);
                if (binding != null) return binding;
            }
            return null;
        }

        void compileBody() {
            slots = plan();
            // Every planned local is set to null before any branch target. A stack map frame
            // must describe the exact state at a label, and an unassigned slot is `top`, which
            // no frame may claim is a reference. Starting every slot as a real null reference
            // makes one frame shape valid everywhere, instead of requiring a different frame
            // per control-flow path. Parameters (and main's args slot) already hold values.
            for (int slot = startSlot; slot < localCount; slot++) {
                bytes.write(ACONST_NULL);
                if (slot <= 3) {
                    bytes.write(0x4B + slot); // astore_0..3
                } else {
                    bytes.write(ASTORE);
                    bytes.write(slot);
                }
            }
            for (int index = 0; index < body.size(); index++) {
                emit(body.get(index), slots[index]);
            }
            if (reachable) {
                if (isFunction) {
                    // A function must push an Object; the lowering appends an explicit
                    // `const nothing; return` so this is only a safety net.
                    bytes.write(ACONST_NULL);
                    push();
                    bytes.write(ARETURN);
                } else {
                    bytes.write(RETURN);
                }
            }
            patch();
        }

        private void emit(Ir.Instr instr, int slot) {
            if (instr instanceof Ir.Return && !isFunction) {
                // `oq run` rejects this; falling back lets the interpreter report it with a
                // real span instead of failing inside a generated class.
                throw new NotSupportedException("'return' outside a function");
            }
            if (!reachable && !(instr instanceof Ir.Label)) {
                // Code between an unconditional jump and the next label cannot be reached.
                // Emitting it would also place an instruction after a jump with no stack map
                // frame, which the verifier rejects.
                return;
            }
            switch (instr) {
                case Ir.Label label -> {
                    labels.put(label.name(), bytes.size());
                    if (reachable) {
                        requireEmptyStack("label " + label.name());
                    }
                    frames.add(bytes.size());
                    reachable = true;
                }
                case Ir.JumpLabel jump -> branch(GOTO, jump.label());
                case Ir.JumpIfFalseLabel jump -> {
                    call(ARITHMETIC, "truthy", "(Ljava/lang/Object;)Z");
                    branch(IFEQ, jump.label());
                }
                case Ir.Break brk -> branch(GOTO, brk.loopId() + "_end");
                case Ir.Continue cont -> branch(GOTO, cont.loopId() + "_step");
                case Ir.Const constant -> {
                    if (reachable) emitConstant(constant.value());
                }
                case Ir.Load ignored -> {
                    if (reachable) load(slot);
                }
                case Ir.Store ignored -> {
                    if (reachable) store(slot);
                }
                case Ir.Declare ignored -> {
                    if (reachable) store(slot);
                }
                case Ir.Binary binary -> {
                    if (reachable) emitBinary(binary.op());
                }
                case Ir.Unary unary -> {
                    if (reachable) emitUnary(unary.op());
                }
                case Ir.ToBool ignored -> {
                    if (reachable) {
                        call(ARITHMETIC, "toBoolean", "(Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.Call callValue -> {
                    if (reachable) emitCall(callValue);
                }
                case Ir.Pop ignored -> {
                    if (reachable) {
                        bytes.write(POP);
                        pop();
                    }
                }
                case Ir.Return ignored -> {
                    if (reachable) {
                        // The lowering leaves the return value on the stack; main cannot
                        // reach here because a Return there is refused before emission.
                        bytes.write(ARETURN);
                        pop();
                        reachable = false;
                    }
                }
                case Ir.Halt ignored -> {
                    if (reachable) {
                        bytes.write(RETURN);
                        reachable = false;
                    }
                }
                case Ir.ScopeEnter ignored -> {
                }
                case Ir.ScopeExit ignored -> {
                }
                case Ir.MakeList make -> {
                    if (reachable) emitMakeCollection(make.count(), slot, false);
                }
                case Ir.MakeSet make -> {
                    if (reachable) emitMakeCollection(make.count(), slot, true);
                }
                case Ir.MakeMap make -> {
                    if (reachable) emitMakeMap(make.count(), slot);
                }
                case Ir.Index ignored -> {
                    if (reachable) {
                        call(COLLECTIONS, "index",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.SetIndex ignored -> {
                    if (reachable) {
                        call(COLLECTIONS, "setIndex",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
                    }
                }
                case Ir.Range range -> {
                    if (reachable) {
                        call(COLLECTIONS, range.inclusive() ? "rangeInclusive" : "rangeExclusive",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.IterNew ignored -> {
                    if (reachable) {
                        call(COLLECTIONS, "iterator",
                                "(Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.IterHasNext ignored -> {
                    if (reachable) {
                        call(COLLECTIONS, "hasNext",
                                "(Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.IterNext ignored -> {
                    if (reachable) {
                        call(COLLECTIONS, "next",
                                "(Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.Text ignored -> {
                    if (reachable) {
                        call(ARITHMETIC, "text",
                                "(Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.Contains ignored -> {
                    if (reachable) {
                        call(COLLECTIONS, "contains",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                    }
                }
                case Ir.MethodCall methodCall -> throw new NotSupportedException(
                        "method call '." + methodCall.name() + "()' is not compiled yet");
                default -> throw new NotSupportedException(
                        "instruction " + instr.getClass().getSimpleName() + " is not compiled yet");
            }
        }

        private void requireEmptyStack(String where) {
            if (depth != 0) {
                throw new NotSupportedException(
                        "operand stack is not empty at " + where + " (depth " + depth
                                + "); refusing to emit bytecode that could not be verified");
            }
        }

        private void branch(int opcode, String label) {
            int at = bytes.size();
            bytes.write(opcode);
            bytes.write(0);
            bytes.write(0);
            jumpLabels.put(at, label);
            if (opcode == IFEQ) pop();
            if (opcode == GOTO) reachable = false;
        }

        private final Map<Integer, String> jumpLabels = new LinkedHashMap<>();

        private void emitConstant(Object value) {
            if (value == null) {
                bytes.write(ACONST_NULL);
                push();
                return;
            }
            if (value instanceof Long number) {
                ldc2(pool.longRef(number));
                call("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
                return;
            }
            if (value instanceof Double number) {
                ldc2(pool.doubleRef(number));
                call("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
                return;
            }
            if (value instanceof Boolean bool) {
                bytes.write(bool ? ICONST_1 : ICONST_0);
                push();
                call("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;");
                return;
            }
            if (value instanceof String text) {
                int index = pool.string(text);
                bytes.write(LDC_W);
                bytes.write(hi(index));
                bytes.write(lo(index));
                push();
                return;
            }
            throw new NotSupportedException("constant of type "
                    + value.getClass().getSimpleName() + " is not compiled yet");
        }

        private void ldc2(int index) {
            bytes.write(LDC2_W);
            bytes.write(hi(index));
            bytes.write(lo(index));
            push();
        }

        private void load(int slot) {
            if (slot <= 3) {
                bytes.write(0x2A + slot); // aload_0..3
            } else {
                bytes.write(ALOAD);
                bytes.write(slot);
            }
            push();
        }

        private void store(int slot) {
            if (slot <= 3) {
                bytes.write(0x4B + slot); // astore_0..3
            } else {
                bytes.write(ASTORE);
                bytes.write(slot);
            }
            pop();
        }

        private void emitBinary(String op) {
            String name = switch (op) {
                case "+" -> "add";
                case "-" -> "subtract";
                case "*" -> "multiply";
                case "/" -> "divide";
                case "%" -> "modulo";
                case "**" -> "power";
                case "==" -> "equal";
                case "!=" -> "notEqual";
                case "<" -> "less";
                case "<=" -> "lessOrEqual";
                case ">" -> "greater";
                case ">=" -> "greaterOrEqual";
                default -> throw new NotSupportedException(
                        "operator '" + op + "' is not compiled yet");
            };
            call(ARITHMETIC, name, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        }

        private void emitUnary(String op) {
            switch (op) {
                case "-" -> call(ARITHMETIC, "negate",
                        "(Ljava/lang/Object;)Ljava/lang/Object;");
                case "+" -> {
                    // Unary plus is a no-op on numbers, which is the only thing it accepts.
                }
                case "not" -> {
                    call(ARITHMETIC, "not", "(Ljava/lang/Object;)Ljava/lang/Object;");
                }
                default -> throw new NotSupportedException(
                        "unary '" + op + "' is not compiled yet");
            }
        }

        private void emitCall(Ir.Call call) {
            // The interpreter hoists user functions over the builtins, so a user `fun length`
            // must win here too. Builtin resolution is only the fallback.
            Integer arity = functionArities.get(call.name());
            if (arity != null) {
                if (call.argc() != arity) {
                    throw new NotSupportedException("call to '" + call.name() + "' with "
                            + call.argc() + " argument(s), but the function declares "
                            + arity + " parameter(s)");
                }
                call(ownerInternalName, call.name(), functionDescriptor(arity));
                return;
            }
            switch (call.name()) {
                case "print" -> {
                    requireArgc(call, 1);
                    call(ARITHMETIC, "print", "(Ljava/lang/Object;)V");
                }
                case "write" -> {
                    requireArgc(call, 1);
                    call(ARITHMETIC, "write", "(Ljava/lang/Object;)V");
                }
                case "length" -> {
                    requireArgc(call, 1);
                    call(COLLECTIONS, "length", "(Ljava/lang/Object;)Ljava/lang/Object;");
                }
                default -> throw new NotSupportedException(
                        "call to '" + call.name() + "' with " + call.argc()
                                + " argument(s) is not compiled yet");
            }
        }

        /**
         * Builds a list or set from {@code count} values on the stack, last one on top.
         * The values are parked in planned scratch slots because the container constructor
         * itself needs the stack; every scratch slot was null-initialised like any local.
         */
        private void emitMakeCollection(int count, int firstSlot, boolean set) {
            for (int i = count - 1; i >= 0; i--) {
                store(firstSlot + i);
            }
            call(COLLECTIONS, set ? "newSet" : "newList", "()Ljava/lang/Object;");
            for (int i = 0; i < count; i++) {
                bytes.write(DUP);
                push();
                load(firstSlot + i);
                call(COLLECTIONS, set ? "addToSet" : "addToList",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V");
            }
        }

        /** Builds a map from {@code count} key/value pairs, value on top of each pair. */
        private void emitMakeMap(int count, int firstSlot) {
            for (int i = count - 1; i >= 0; i--) {
                store(firstSlot + 2 * i + 1);
                store(firstSlot + 2 * i);
            }
            call(COLLECTIONS, "newMap", "()Ljava/lang/Object;");
            for (int i = 0; i < count; i++) {
                bytes.write(DUP);
                push();
                load(firstSlot + 2 * i);
                load(firstSlot + 2 * i + 1);
                call(COLLECTIONS, "putInMap",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
            }
        }

        private void requireArgc(Ir.Call call, int expected) {
            if (call.argc() != expected) {
                throw new NotSupportedException(
                        "call to '" + call.name() + "' with " + call.argc()
                                + " argument(s) is not compiled yet");
            }
        }

        private void call(String owner, String name, String descriptor) {
            for (int i = 0; i < parameterCount(descriptor); i++) pop();
            if (!descriptor.endsWith("V")) push();
            int index = pool.methodRef(owner, name, descriptor);
            bytes.write(INVOKESTATIC);
            bytes.write(hi(index));
            bytes.write(lo(index));
        }

        private void push() {
            depth++;
            peak = Math.max(peak, depth);
        }

        private void pop() {
            depth--;
            if (depth < 0) {
                throw new NotSupportedException("generated code would underflow the stack");
            }
        }

        /** Patches every recorded jump now that label offsets are known. */
        void patch() {
            byte[] code = bytes.toByteArray();
            for (Map.Entry<Integer, String> entry : jumpLabels.entrySet()) {
                int at = entry.getKey();
                Integer target = labels.get(entry.getValue());
                if (target == null) {
                    throw new NotSupportedException("unresolved label " + entry.getValue());
                }
                int offset = target - at;
                if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
                    throw new NotSupportedException(
                            "method is too large for 16-bit jump offsets");
                }
                code[at + 1] = hi(offset);
                code[at + 2] = lo(offset);
            }
            bytes.reset();
            bytes.writeBytes(code);
        }
    }

    /** Constant pool with the entry kinds this compiler needs. */
    private static final class ConstantPool {
        private static final int TAG_UTF8 = 1;
        private static final int TAG_LONG = 5;
        private static final int TAG_DOUBLE = 6;
        private static final int TAG_CLASS = 7;
        private static final int TAG_STRING = 8;
        private static final int TAG_METHODREF = 10;
        private static final int TAG_NAME_AND_TYPE = 12;

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream data = new DataOutputStream(bytes);
        private final Map<String, Integer> utf8Cache = new LinkedHashMap<>();
        private final Map<String, Integer> classCache = new LinkedHashMap<>();
        private final Map<String, Integer> methodCache = new LinkedHashMap<>();
        private final Map<String, Integer> stringCache = new LinkedHashMap<>();
        private int next = 1;

        int utf8(String text) {
            Integer cached = utf8Cache.get(text);
            if (cached != null) return cached;
            byte[] encoded = ClassFileWriter.modifiedUtf8(text);
            if (encoded.length > 65535) throw new IllegalArgumentException("constant too long");
            write(TAG_UTF8, () -> {
                data.writeShort(encoded.length);
                data.write(encoded);
            });
            int index = next - 1;
            utf8Cache.put(text, index);
            return index;
        }

        int classRef(int nameIndex) {
            write(TAG_CLASS, () -> data.writeShort(nameIndex));
            return next - 1;
        }

        int classRef(String internalName) {
            Integer cached = classCache.get(internalName);
            if (cached != null) return cached;
            int index = classRef(utf8(internalName));
            classCache.put(internalName, index);
            return index;
        }

        int string(String text) {
            Integer cached = stringCache.get(text);
            if (cached != null) return cached;
            int utf8 = utf8(text);
            write(TAG_STRING, () -> data.writeShort(utf8));
            int index = next - 1;
            stringCache.put(text, index);
            return index;
        }

        int longRef(long value) {
            write(TAG_LONG, () -> data.writeLong(value));
            next++; // longs take two constant-pool slots
            return next - 2;
        }

        int doubleRef(double value) {
            write(TAG_DOUBLE, () -> data.writeDouble(value));
            next++; // doubles take two constant-pool slots
            return next - 2;
        }

        int methodRef(String owner, String name, String descriptor) {
            String key = owner + "." + name + descriptor;
            Integer cached = methodCache.get(key);
            if (cached != null) return cached;
            int ownerIndex = classRef(owner);
            int nameIndex = utf8(name);
            int descriptorIndex = utf8(descriptor);
            int nameAndType = writeAndGet(TAG_NAME_AND_TYPE, () -> {
                data.writeShort(nameIndex);
                data.writeShort(descriptorIndex);
            });
            write(TAG_METHODREF, () -> {
                data.writeShort(ownerIndex);
                data.writeShort(nameAndType);
            });
            int index = next - 1;
            methodCache.put(key, index);
            return index;
        }

        private interface Writer {
            void write() throws IOException;
        }

        private void write(int tag, Writer writer) {
            try {
                data.writeByte(tag);
                writer.write();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            next++;
        }

        private int writeAndGet(int tag, Writer writer) {
            write(tag, writer);
            return next - 1;
        }

        void write(DataOutputStream out) throws IOException {
            data.flush();
            out.writeShort(next);
            bytes.writeTo(out);
        }
    }

    /**
     * Counts parameters in a method descriptor. Every parameter this compiler uses is a
     * reference, so each occupies exactly one stack slot.
     */
    static int parameterCount(String descriptor) {
        int count = 0;
        int index = 1; // skip '('
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            char c = descriptor.charAt(index);
            if (c == '[') {
                while (descriptor.charAt(index) == '[') index++;
                if (descriptor.charAt(index) == 'L') {
                    while (descriptor.charAt(index) != ';') index++;
                }
            } else if (c == 'L') {
                while (descriptor.charAt(index) != ';') index++;
            }
            count++;
            index++;
        }
        return count;
    }

    private static byte hi(int value) {
        return (byte) ((value >>> 8) & 0xFF);
    }

    private static byte lo(int value) {
        return (byte) (value & 0xFF);
    }
}
