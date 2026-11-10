package dev.oqthara.core;

import dev.oqthara.runtime.Span;

import java.util.List;

public final class Ast {
    private Ast() {
    }

    public sealed interface Node permits Stmt, Expr {
        Span span();
    }

    public sealed interface Stmt extends Node permits
            VarDecl, Assign, CompoundAssign, If, While, ForEach, ForRange,
            FuncDecl, Return, Break, Continue, DataDecl, ExprStmt, IndexAssign, FieldAssign,
            UseStmt, ImportStmt, Command, TryStmt, Destructure, ClassDecl, ContractDecl {
    }

    public sealed interface Expr extends Node permits
            NumberLit, StringLit, BoolLit, NothingLit, Var, ListLit, TupleLit, MapLit, SetLit,
            Unary, Binary, Logical, Call, MethodCall, Index, Slice, Field, Lambda, Grouping,
            InExpr, RangeExpr, TryExpr {
    }

    public record Program(List<Stmt> statements, String sourceName) {
    }

    public record VarDecl(String name, boolean mutable, Expr value, Span span) implements Stmt {
    }

    public record Assign(String name, Expr value, Span span) implements Stmt {
    }

    /**
     * A comma-separated target list bound from one value. {@code mutable} is per statement,
     * so {@code const a, b = pair} fixes both names; there is no per-target modifier.
     */
    public record Destructure(List<String> names, boolean mutable, Expr value, Span span)
            implements Stmt {
    }

    public record CompoundAssign(String name, Token.Kind op, Expr value, Span span) implements Stmt {
    }

    public record If(List<Branch> branches, List<Stmt> elseBranch, Span span) implements Stmt {
    }

    public record Branch(Expr condition, List<Stmt> body, Span span) {
    }

    public record While(Expr condition, List<Stmt> body, Span span) implements Stmt {
    }

    public record ForEach(String name, boolean mutable, Expr iterable, List<Stmt> body, Span span) implements Stmt {
    }

    public record ForRange(String name, boolean mutable, Expr from, Expr to, boolean inclusive,
                           List<Stmt> body, Span span) implements Stmt {
    }

    public record FuncDecl(String name, List<String> params, List<Stmt> body, String doc,
                           Span span) implements Stmt {
        public FuncDecl(String name, List<String> params, List<Stmt> body, Span span) {
            this(name, params, body, null, span);
        }
    }

    public record Return(Expr value, Span span) implements Stmt {
    }

    public record Break(Span span) implements Stmt {
    }

    public record Continue(Span span) implements Stmt {
    }

    /**
     * {@code data Name(field, ...)} with an optional trailing {@code immutable}. An immutable
     * declaration fixes its fields at construction and lets the value be used as a map key or
     * set member, where a mutable one is refused (ADR-0028).
     */
    public record DataDecl(String name, List<FieldDef> fields, boolean immutable, String doc,
                           Span span) implements Stmt {
        public DataDecl(String name, List<FieldDef> fields, boolean immutable, Span span) {
            this(name, fields, immutable, null, span);
        }
    }

    public record FieldDef(String name, Expr defaultValue, Span span) {
    }

    /**
     * {@code class Name(field, ...)} ... {@code end}. A class is a reference type with
     * identity; see ADR-0027. {@code implementsName} is null when no contract is named, and
     * {@code init} is null when the class declares no initializer body.
     */
    public record ClassDecl(String name, List<FieldDef> fields, String implementsName,
                            List<MethodDecl> methods, List<Stmt> init, String doc, Span span)
            implements Stmt {
        public ClassDecl(String name, List<FieldDef> fields, String implementsName,
                         List<MethodDecl> methods, List<Stmt> init, Span span) {
            this(name, fields, implementsName, methods, init, null, span);
        }
    }

    public record MethodDecl(String name, List<String> params, List<Stmt> body, Span span) {
    }

    /** {@code contract Name} followed by method signatures and {@code end}. No bodies. */
    public record ContractDecl(String name, List<Signature> methods, String doc, Span span)
            implements Stmt {
        public ContractDecl(String name, List<Signature> methods, Span span) {
            this(name, methods, null, span);
        }
    }

    public record Signature(String name, int arity, Span span) {
    }

    public record ExprStmt(Expr expr, Span span) implements Stmt {
    }

    public record IndexAssign(Expr target, Expr index, Expr value, Span span) implements Stmt {
    }

    public record FieldAssign(Expr target, String field, Expr value, Span span) implements Stmt {
    }

    public record Command(String name, List<Expr> args, Span span) implements Stmt {
    }

    public record TryStmt(List<Stmt> body, String errorName, List<Stmt> handler, Span span) implements Stmt {
    }

    /**
     * {@code try ... catch e ... end} in value position. The value is whatever the branch that
     * runs returns, or {@code nothing} when it falls off the end; see ADR-0014.
     */
    public record TryExpr(List<Stmt> body, String errorName, List<Stmt> handler, Span span) implements Expr {
    }

    public record UseStmt(String path, Span span) implements Stmt {
    }

    public record ImportStmt(String path, Span span) implements Stmt {
    }

    public record NumberLit(Object value, Span span) implements Expr {
    }

    public record StringLit(List<StringPart> parts, Span span) implements Expr {
    }

    public sealed interface StringPart permits TextPart, ExprPart {
    }

    public record TextPart(String text) implements StringPart {
    }

    public record ExprPart(Expr expr) implements StringPart {
    }

    public record BoolLit(boolean value, Span span) implements Expr {
    }

    public record NothingLit(Span span) implements Expr {
    }

    public record Var(String name, Span span) implements Expr {
    }

    public record ListLit(List<Expr> items, Span span) implements Expr {
    }

    public record TupleLit(List<Expr> items, Span span) implements Expr {
    }

    public record MapLit(List<Entry> entries, Span span) implements Expr {
    }

    public record Entry(Expr key, Expr value) {
    }

    public record SetLit(List<Expr> items, Span span) implements Expr {
    }

    public record Unary(Token.Kind op, Expr operand, Span span) implements Expr {
    }

    public record Binary(Expr left, Token.Kind op, Expr right, Span span) implements Expr {
    }

    public record Logical(Expr left, String op, Expr right, Span span) implements Expr {
    }

    public record Call(Expr callee, List<Expr> args, Span span) implements Expr {
    }

    public record MethodCall(Expr receiver, String method, List<Expr> args, Span span) implements Expr {
    }

    public record Index(Expr target, Expr index, Span span) implements Expr {
    }

    /** A slice. Any of {@code from}, {@code to} and {@code step} may be null when omitted. */
    public record Slice(Expr target, Expr from, Expr to, Expr step, Span span) implements Expr {
    }

    public record Field(Expr target, String name, Span span) implements Expr {
    }

    public record Lambda(List<String> params, Expr body, List<Stmt> blockBody, Span span) implements Expr {
    }

    public record Grouping(Expr expr, Span span) implements Expr {
    }

    public record InExpr(Expr item, Expr container, Span span) implements Expr {
    }

    public record RangeExpr(Expr from, Expr to, boolean inclusive, Span span) implements Expr {
    }
}
