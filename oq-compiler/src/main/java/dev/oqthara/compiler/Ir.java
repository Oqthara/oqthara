package dev.oqthara.compiler;

import java.util.ArrayList;
import java.util.List;

public final class Ir {
    private Ir() {
    }

    public sealed interface Instr permits Const, Load, Store, Declare, Call, CallValue, Print, Binary,
            Unary, Jump, JumpIfFalse, JumpIfFalseLabel, Return, Pop, MakeList, MakeMap, MakeSet, Index,
            SetIndex, IterNew, IterHasNext, IterNext, Text, Contains, MethodCall, MakeData,
            GetField, SetField, Range, TryStart, TryEnd, Throw, Halt, Label, JumpLabel, Break,
            Continue, ScopeEnter, ScopeExit, ToBool {
    }

    public record Program(Ir.Function main, List<Ir.Function> functions) {
    }

    public record Function(String name, List<String> params, List<Instr> body) {
    }

    public record Const(Object value) implements Instr {
    }

    public record Load(String name) implements Instr {
    }

    /**
     * Assigns to an existing visible variable, or defines it in the current scope when no
     * binding exists. This is the interpreter's {@code assignOrDefine}.
     */
    public record Store(String name) implements Instr {
    }

    /**
     * Defines a new variable in the current scope, shadowing any outer binding. A plain
     * assignment inside a block updates the outer variable instead, so the two cannot share
     * one instruction. {@code fixed} marks bindings such as loop variables, which the
     * language does not allow to be reassigned.
     */
    public record Declare(String name, boolean fixed) implements Instr {
    }

    public record Call(String name, int argc) implements Instr {
    }

    public record CallValue(int argc) implements Instr {
    }

    public record Print(int count) implements Instr {
    }

    public record Binary(String op) implements Instr {
    }

    public record Unary(String op) implements Instr {
    }

    public record Jump(int target) implements Instr {
    }

    public record JumpIfFalse(int target) implements Instr {
    }

    /** Conditional jump by label name, so synthetic labels do not need index fixups. */
    public record JumpIfFalseLabel(String label) implements Instr {
    }

    public record Return() implements Instr {
    }

    public record Pop() implements Instr {
    }

    public record MakeList(int count) implements Instr {
    }

    public record MakeMap(int count) implements Instr {
    }

    public record MakeSet(int count) implements Instr {
    }

    public record Index() implements Instr {
    }

    public record SetIndex() implements Instr {
    }

    /** Wraps the iterable on the stack in a real {@code java.util.Iterator}. */
    public record IterNew() implements Instr {
    }

    /** Pushes whether the iterator on the stack has another item. */
    public record IterHasNext() implements Instr {
    }

    /** Pushes the iterator's next item; the caller must have checked {@link IterHasNext}. */
    public record IterNext() implements Instr {
    }
    /** Renders the value on the stack as text, exactly like the interpreter's display. */
    public record Text() implements Instr {
    }

    /** Membership test; the stack holds the item and then the container. */
    public record Contains() implements Instr {
    }

    /**
     * A method call resolved at runtime. Kept distinct from {@link Call} so a user method
     * name cannot be confused with a builtin or a top-level function.
     */
    public record MethodCall(String name, int argc) implements Instr {
    }

    public record MakeData(String type, int count) implements Instr {
    }

    public record GetField(String name) implements Instr {
    }

    public record SetField(String name) implements Instr {
    }

    public record Range(boolean inclusive) implements Instr {
    }

    public record TryStart(int handler) implements Instr {
    }

    public record TryEnd() implements Instr {
    }

    public record Throw() implements Instr {
    }

    public record Halt() implements Instr {
    }

    /**
     * A named position in the instruction stream. The bytecode backend resolves it to a
     * byte offset; the disassembler prints it so tests can assert structure.
     */
    public record Label(String name) implements Instr {
    }

    public record JumpLabel(String label) implements Instr {
    }

    /** Jumps to the loop's break label. */
    public record Break(String loopId) implements Instr {
    }

    /** Jumps to the loop's continue label. */
    public record Continue(String loopId) implements Instr {
    }

    /** Converts any value to an Oqthara boolean, exactly like the interpreter's truthiness. */
    public record ToBool() implements Instr {
    }

    /** Opens a lexical scope. Has no runtime effect; the backend resets block-local slots here. */
    public record ScopeEnter() implements Instr {
    }

    /** Closes the matching {@link ScopeEnter}. */
    public record ScopeExit() implements Instr {
    }

    public static List<Instr> list() {
        return new ArrayList<>();
    }

    public static String disassemble(Program program) {
        StringBuilder sb = new StringBuilder();
        sb.append("function ").append(program.main().name()).append('\n');
        disassemble(program.main(), sb);
        for (Function function : program.functions()) {
            sb.append("function ").append(function.name()).append('\n');
            disassemble(function, sb);
        }
        return sb.toString();
    }

    private static void disassemble(Function function, StringBuilder sb) {
        List<Instr> body = function.body();
        for (int i = 0; i < body.size(); i++) {
            sb.append(String.format("  %04d  %s%n", i, format(body.get(i))));
        }
        if (body.isEmpty()) {
            sb.append("  (empty)\n");
        }
    }

    public static String format(Instr instr) {
        return switch (instr) {
            case Const c -> "const " + repr(c.value());
            case Load l -> "load " + l.name();
            case Store s -> "store " + s.name();
            case Declare d -> "declare " + d.name() + (d.fixed() ? " const" : "");
            case Call c -> "call " + c.name() + "/" + c.argc();
            case CallValue c -> "call_value/" + c.argc();
            case Print p -> "print " + p.count();
            case Binary b -> "binary " + b.op();
            case Unary u -> "unary " + u.op();
            case Jump j -> "jump " + j.target();
            case JumpIfFalse j -> "jump_if_false " + j.target();
            case JumpIfFalseLabel j -> "jump_if_false " + j.label();
            case Return r -> "return";
            case Pop p -> "pop";
            case MakeList m -> "make_list " + m.count();
            case MakeMap m -> "make_map " + m.count();
            case MakeSet m -> "make_set " + m.count();
            case Index i -> "index";
            case SetIndex s -> "set_index";
            case IterNew i -> "iter_new";
            case IterHasNext i -> "iter_has_next";
            case IterNext i -> "iter_next";
            case Text t -> "text";
            case Contains c -> "contains";
            case MethodCall m -> "method_call " + m.name() + "/" + m.argc();
            case MakeData d -> "make_data " + d.type() + "/" + d.count();
            case GetField g -> "get_field " + g.name();
            case SetField s -> "set_field " + s.name();
            case Range r -> "range " + (r.inclusive() ? "to" : "until");
            case TryStart t -> "try_start " + t.handler();
            case TryEnd t -> "try_end";
            case Throw t -> "throw";
            case Halt h -> "halt";
            case Label l -> l.name() + ":";
            case JumpLabel j -> "jump " + j.label();
            case Break b -> "break " + b.loopId();
            case Continue c -> "continue " + c.loopId();
            case ToBool ignored -> "to_bool";
            case ScopeEnter ignored -> "scope_enter";
            case ScopeExit ignored -> "scope_exit";
        };
    }

    private static String repr(Object value) {
        if (value == null) return "nothing";
        if (value instanceof String s) return "\"" + s + "\"";
        return String.valueOf(value);
    }
}
