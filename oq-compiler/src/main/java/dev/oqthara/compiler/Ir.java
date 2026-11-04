package dev.oqthara.compiler;

import java.util.ArrayList;
import java.util.List;

public final class Ir {
    private Ir() {
    }

    public sealed interface Instr permits Const, Load, Store, Call, CallValue, Print, Binary,
            Unary, Jump, JumpIfFalse, Return, Pop, MakeList, MakeMap, MakeSet, Index,
            SetIndex, MakeData, GetField, SetField, Range, TryStart, TryEnd, Throw, Halt {
    }

    public record Program(Ir.Function main, List<Ir.Function> functions) {
    }

    public record Function(String name, List<String> params, List<Instr> body) {
    }

    public record Const(Object value) implements Instr {
    }

    public record Load(String name) implements Instr {
    }

    public record Store(String name) implements Instr {
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
            case Call c -> "call " + c.name() + "/" + c.argc();
            case CallValue c -> "call_value/" + c.argc();
            case Print p -> "print " + p.count();
            case Binary b -> "binary " + b.op();
            case Unary u -> "unary " + u.op();
            case Jump j -> "jump " + j.target();
            case JumpIfFalse j -> "jump_if_false " + j.target();
            case Return r -> "return";
            case Pop p -> "pop";
            case MakeList m -> "make_list " + m.count();
            case MakeMap m -> "make_map " + m.count();
            case MakeSet m -> "make_set " + m.count();
            case Index i -> "index";
            case SetIndex s -> "set_index";
            case MakeData d -> "make_data " + d.type() + "/" + d.count();
            case GetField g -> "get_field " + g.name();
            case SetField s -> "set_field " + s.name();
            case Range r -> "range " + (r.inclusive() ? "to" : "until");
            case TryStart t -> "try_start " + t.handler();
            case TryEnd t -> "try_end";
            case Throw t -> "throw";
            case Halt h -> "halt";
        };
    }

    private static String repr(Object value) {
        if (value == null) return "nothing";
        if (value instanceof String s) return "\"" + s + "\"";
        return String.valueOf(value);
    }
}
