package dev.oqthara.compiler;

import dev.oqthara.runtime.Span;

import java.util.ArrayList;
import java.util.List;

public final class Ir {
    private Ir() {
    }

    public sealed interface Instr permits Const, Load, Store, Declare, Call, CallValue, Print, Binary,
            Unary, Jump, JumpIfFalse, JumpIfFalseLabel, Return, Pop, MakeList, MakeTuple, MakeMap, MakeSet, Index,
            SetIndex, IterNew, IterHasNext, IterNext, Text, Contains, MethodCall, MakeData,
            GetField, SetField, Range, TryStart, TryEnd, HandlerLabel, CatchValue, Halt, Label,
            JumpLabel, Break, Continue, ScopeEnter, ScopeExit, ToBool, At, Refuse, Lambda,
            DestructureParts {
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

    public record CallValue(int argc, String name) implements Instr {
    }

    /**
     * Creates a function value: the name of a static method on the generated class, its
     * arity, and how many captured values are already on the stack.
     */
    public record Lambda(String method, int arity, int captures) implements Instr {
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

    public record MakeTuple(int count) implements Instr {
    }

    public record MakeMap(int count) implements Instr {
    }

    public record MakeSet(int count) implements Instr {
    }

    public record Index() implements Instr {
    }

    /**
     * Replaces a value on the stack with its destructuring parts, throwing when the value has
     * no such shape or does not hold exactly {@code count} of them. The operand is the number
     * of targets, which is both the expected count and the size of the result.
     */
    public record DestructureParts(int count) implements Instr {
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

    /**
     * Builds an {@code OqData} record: the stack holds one value per field, in declaration
     * order. The field names travel with the instruction so the generated code preserves
     * the interpreter's field order and rendering.
     */
    public record MakeData(String type, List<String> fields) implements Instr {
        public int count() {
            return fields.size();
        }
    }

    public record GetField(String name) implements Instr {
    }

    public record SetField(String name) implements Instr {
    }

    public record Range(boolean inclusive) implements Instr {
    }

    /**
     * Opens a protected region. Everything emitted until the matching {@link TryEnd} is
     * covered by one exception-table entry that jumps to {@code handler}. The handler label
     * is entered with the caught {@code OqError} on the operand stack, which is why it is a
     * label the backend must describe with a one-item stack map frame, not an empty one.
     */
    public record TryStart(String handler) implements Instr {
    }

    public record TryEnd(String handler) implements Instr {
    }

    /**
     * The sole entry point of an exception handler. Unlike {@link Label}, the operand stack
     * here holds the caught exception, which is why the backend must describe it with a
     * one-item stack map frame instead of the usual empty-stack frame.
     */
    public record HandlerLabel(String name) implements Instr {
    }

    /**
     * Converts the caught {@code OqError} on the stack into the value the interpreter binds:
     * {@code OqError.toValue()}, an {@code Error} record with message, hint, line, column and
     * file. Emitted immediately after the handler label.
     */
    public record CatchValue() implements Instr {
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

    /**
     * Carries the source position of the operation that follows it, so a helper that can
     * throw an {@code OqError} reports the same line, column and file as {@code oq run}.
     * The backend applies it to exactly one instruction and then clears it.
     */
    public record At(Span span) implements Instr {
    }

    /** Converts any value to an Oqthara boolean, exactly like the interpreter's truthiness. */
    public record ToBool() implements Instr {
    }

    /**
     * A construct the backend must not compile. The lowering emits this instead of a
     * placeholder whenever it can see that the interpreter's semantics cannot be reproduced,
     * so the refusal happens by name instead of producing a jar with different behaviour.
     */
    public record Refuse(String reason) implements Instr {
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
            case CallValue c -> "call_value/" + c.argc() + " " + c.name();
            case Lambda l -> "lambda " + l.method() + "/" + l.arity() + " captures " + l.captures();
            case Print p -> "print " + p.count();
            case Binary b -> "binary " + b.op();
            case Unary u -> "unary " + u.op();
            case Jump j -> "jump " + j.target();
            case JumpIfFalse j -> "jump_if_false " + j.target();
            case JumpIfFalseLabel j -> "jump_if_false " + j.label();
            case Return r -> "return";
            case Pop p -> "pop";
            case MakeList m -> "make_list " + m.count();
            case MakeTuple m -> "make_tuple " + m.count();
            case MakeMap m -> "make_map " + m.count();
            case MakeSet m -> "make_set " + m.count();
            case Index i -> "index";
            case DestructureParts d -> "destructure_parts " + d.count();
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
            case TryEnd t -> "try_end " + t.handler();
            case HandlerLabel h -> h.name() + ": (catch)";
            case CatchValue c -> "catch_value";
            case Halt h -> "halt";
            case Label l -> l.name() + ":";
            case JumpLabel j -> "jump " + j.label();
            case Break b -> "break " + b.loopId();
            case Continue c -> "continue " + c.loopId();
            case ToBool ignored -> "to_bool";
            case ScopeEnter ignored -> "scope_enter";
            case ScopeExit ignored -> "scope_exit";
            case At at -> "at " + at.span().line() + ":" + at.span().column();
            case Refuse r -> "refuse " + r.reason();
        };
    }

    private static String repr(Object value) {
        if (value == null) return "nothing";
        if (value instanceof String s) return "\"" + s + "\"";
        return String.valueOf(value);
    }
}
