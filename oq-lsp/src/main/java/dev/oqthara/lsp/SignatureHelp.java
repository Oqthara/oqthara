package dev.oqthara.lsp;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OverloadedFunction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers {@code textDocument/signatureHelp} for a call whose argument list the cursor is
 * inside.
 *
 * <p>Resolution is lexical, like hover: the callee name is matched against declarations in the
 * document, then against imported modules, then against the builtin table. Shadowing is not
 * modelled. A local declared later in the file answers for a call earlier in it, which is why
 * the reply describes what a reader can see rather than what the program would call.
 *
 * <p>Methods are deliberately not answered. The receiver's type is not knowable from the text
 * before the dot -- {@code x.slice(} says nothing about whether {@code x} is text or a list --
 * and the same method name means different things to different receivers, so a signature there
 * would be a guess presented as an answer.
 *
 * <p>Parameter names come from {@link NativeFunction#parameterNames()} for builtins and from
 * the declaration for user functions. A parameter with no published name is rendered as
 * {@code arg1}, {@code arg2} and so on, which is the same placeholder {@code oq doc} uses, so
 * the editor and the generated page never disagree.
 */
final class SignatureHelp {
    private SignatureHelp() {
    }

    /**
     * Returns a signature-help reply for the position, or null when the position is not inside
     * a call to something that can be described.
     *
     * @param source    the whole document, as the editor last sent it
     * @param line      zero-based line, as LSP numbers it
     * @param character zero-based UTF-16 offset into that line
     */
    static Reply signatureHelp(String uri, String source, int line, int character) {
        Call call = callAt(source, line, character);
        if (call == null) return null;

        List<Signature> signatures = new ArrayList<>();
        collectDeclared(call.name(), source, uri, signatures);
        if (signatures.isEmpty()) collectBuiltin(call.name(), signatures);
        if (signatures.isEmpty()) return null;

        // A call site names the arguments it is actually passing, so the arity is a better first
        // guess than registration order. Nothing is filtered out: an argument count that matches
        // no signature usually means the call is half-written, and hiding the alternatives at
        // that moment removes the help the user is asking for.
        int active = call.activeParameter();
        for (Signature signature : signatures) {
            if (signature.parameterCount() > active) {
                return new Reply(signatures, active);
            }
        }
        // Every signature is shorter than the cursor. The cursor is then past the last
        // parameter of the widest one, so point at its last parameter rather than nowhere.
        int last = 0;
        for (Signature signature : signatures) {
            last = Math.max(last, signature.parameterCount() - 1);
        }
        return new Reply(signatures, Math.max(0, last));
    }

    /** One rendered signature, with the arithmetic signature help needs. */
    record Signature(String label, List<String> parameters) {
        int parameterCount() {
            return parameters.size();
        }
    }

    /**
     * The reply, plus the parameter the cursor is in. The protocol counts the argument list's
     * top-level commas from zero, so the count of commas already passed is the index, and a
     * cursor before the first comma is parameter zero.
     */
    record Reply(List<Signature> signatures, int activeParameter) {
    }

    // ---- resolution -------------------------------------------------------------------

    /**
     * Collects declarations of {@code name} from the document, then from its imports.
     *
     * <p>The document is parsed properly first, which is what gets imports followed. A document
     * that does not parse falls back to a textual scan of {@code fun} headers: the moment a user
     * types an opening parenthesis their file is unbalanced, so a strict parse would decline to
     * help at exactly the point help was asked for. The scan reads only headers, and every
     * answer it produces is still just a name list taken from the source.
     */
    private static void collectDeclared(String name, String source, String uri,
                                        List<Signature> into) {
        Ast.Program program;
        try {
            program = Parser.parse(uri, source);
        } catch (RuntimeException e) {
            scanHeaders(source, name, into);
            return;
        }
        for (Ast.Stmt statement : program.statements()) {
            addDeclared(statement, name, into);
        }
        if (!into.isEmpty()) return;
        Path base = Symbols.directoryOf(uri);
        if (base == null) return;
        List<Signature> imported = Symbols.searchImports(program, base, module -> {
            List<Signature> found = new ArrayList<>();
            for (Ast.Stmt statement : module.statements()) {
                addDeclared(statement, name, found);
            }
            return found.isEmpty() ? null : found;
        });
        if (imported != null) into.addAll(imported);
    }

    private static void addDeclared(Ast.Stmt statement, String name, List<Signature> into) {
        if (statement instanceof Ast.FuncDecl function && function.name().equals(name)) {
            into.add(signature(name, function.params()));
        }
    }

    /**
     * Reads {@code fun name(a, b)} headers out of text that does not parse.
     *
     * <p>Only the header is read, and a header whose parameter list does not close before the
     * end of the line is skipped rather than guessed at. Text and comments are skipped using the
     * same position mask the cursor scan uses, so a {@code fun} inside a string is not a
     * declaration.
     */
    private static void scanHeaders(String source, String name, List<Signature> into) {
        boolean[] code = codePositions(source, source.length());
        for (int i = 0; i + 3 < source.length(); i++) {
            if (!code[i]) continue;
            if (!source.startsWith("fun", i)) continue;
            if (i > 0 && Character.isJavaIdentifierPart(source.charAt(i - 1))) continue;
            int at = i + 3;
            while (at < source.length() && (source.charAt(at) == ' ' || source.charAt(at) == '\t')) at++;
            int nameStart = at;
            while (at < source.length() && Character.isJavaIdentifierPart(source.charAt(at))) at++;
            if (at == nameStart || at >= source.length() || source.charAt(at) != '(') continue;
            if (!source.substring(nameStart, at).equals(name)) continue;
            int close = closingParen(source, at);
            if (close < 0) continue;
            List<String> params = new ArrayList<>();
            for (String part : source.substring(at + 1, close).split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) params.add(trimmed);
            }
            into.add(signature(name, params));
            return;
        }
    }

    /** The matching {@code )} on the same line, or -1 when the header is still being typed. */
    private static int closingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n') return -1;
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static void collectBuiltin(String name, List<Signature> into) {
        OqCallable callable = Symbols.builtins().get(name);
        if (callable == null) return;
        if (callable instanceof OverloadedFunction set) {
            for (OqCallable candidate : set.candidates()) {
                addBuiltin(candidate, into);
            }
        } else {
            addBuiltin(callable, into);
        }
    }

    private static void addBuiltin(OqCallable candidate, List<Signature> into) {
        if (!(candidate instanceof NativeFunction function)) return;
        List<String> names = function.parameterNames();
        int shown = names.size();
        if (shown == 0) {
            // No published names. Show the arity so the label still says something true rather
            // than inventing parameter names this function does not have.
            shown = function.maxArity() == Integer.MAX_VALUE ? function.minArity() : function.maxArity();
        }
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            placeholders.add(placeholder(names, i));
        }
        String label = function.name() + "(" + String.join(", ", placeholders) + ")";
        if (function.maxArity() == Integer.MAX_VALUE) {
            label = function.name() + "(" + String.join(", ", placeholders)
                    + (shown == 0 ? "" : ", ") + "...)";
        }
        into.add(new Signature(label, placeholders));
    }

    private static Signature signature(String name, List<String> params) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) names.add(placeholder(params, i));
        return new Signature(name + "(" + String.join(", ", names) + ")", names);
    }

    private static String placeholder(List<String> names, int index) {
        if (index < names.size()) {
            String name = names.get(index);
            if (name != null && !name.isBlank()) return name;
        }
        return "arg" + (index + 1);
    }

    // ---- cursor scanning ----------------------------------------------------------------

    /**
     * The call the cursor sits inside, as far as the text before the cursor can tell.
     *
     * @param name   the callee name
     * @param commas absolute offsets of the top-level commas before the cursor
     */
    record Call(String name, List<Integer> commas) {
        /** Which parameter the cursor is in: one per comma already passed. */
        int activeParameter() {
            return commas.size();
        }
    }

    /**
     * Scans backwards from the cursor for an unterminated call.
     *
     * <p>The scan has to know what is string and what is comment, or a {@code (} inside
     * {@code "("} would open a call that is not there. It is deliberately tolerant: it is
     * looking at a document that is being typed, so anything it cannot make sense of ends the
     * scan and returns null rather than reporting an error.
     *
     * <p>Nested calls resolve to the innermost one, which is the one the cursor is actually
     * being typed into: {@code outer(inner(} describes {@code inner}.
     */
    static Call callAt(String source, int line, int character) {
        int cursor = offsetOf(source, line, character);
        if (cursor < 0) return null;

        boolean[] code = codePositions(source, cursor);
        int depth = 0;
        List<Integer> commas = new ArrayList<>();
        int open = -1;
        for (int i = cursor - 1; i >= 0; i--) {
            if (!code[i]) continue;
            char c = source.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    open = i;
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas.add(0, i);
            }
        }
        if (open < 0) return null;

        String name = calleeBefore(source, open);
        if (name == null) return null;
        return new Call(name, commas);
    }

    /**
     * Marks the offsets before the cursor that are code rather than text or comment.
     *
     * <p>One forward pass, so a large document costs one scan rather than one per character.
     * A plain string ends at a newline, matching the lexer; an unterminated quote therefore
     * cannot swallow the rest of the file.
     */
    private static boolean[] codePositions(String source, int upTo) {
        boolean[] code = new boolean[upTo];
        boolean inString = false;
        char quote = 0;
        boolean inComment = false;
        for (int i = 0; i < upTo; i++) {
            char c = source.charAt(i);
            boolean isCode = !inString && !inComment;
            code[i] = isCode;
            if (inComment) {
                if (c == '\n') inComment = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    // The escaped character is part of the text, so it is not code either.
                    if (i + 1 < upTo) code[++i] = false;
                } else if (c == quote || c == '\n') {
                    inString = false;
                }
                continue;
            }
            if (c == '#') {
                inComment = true;
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            }
        }
        return code;
    }

    /**
     * The identifier immediately before a {@code (}, or null when the parenthesis is not a call.
     *
     * <p>A space between the name and the parenthesis is allowed, because the formatter keeps
     * none but a half-typed document may. A keyword before the parenthesis -- {@code if (},
     * {@code while (} -- is not a call and returns null: those are control flow, not functions,
     * and describing them as functions would be wrong.
     */
    private static String calleeBefore(String source, int open) {
        int end = open;
        while (end > 0 && (source.charAt(end - 1) == ' ' || source.charAt(end - 1) == '\t')) end--;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) start--;
        if (start == end) return null;
        String name = source.substring(start, end);
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return null;
        if (isKeyword(name)) return null;
        // A dot before the name makes it a method call whose receiver type is unknowable.
        if (start > 0 && source.charAt(start - 1) == '.') return null;
        // A declaration header is not a call. While a user writes "fun add(", the parameters
        // they are typing are the ones being defined, and describing them back to the user
        // would suggest a call that does not exist there.
        if (isDeclarationHeader(source, start)) return null;
        return name;
    }

    /** Whether the identifier at {@code start} is being declared rather than called. */
    private static boolean isDeclarationHeader(String source, int start) {
        int at = start;
        while (at > 0 && (source.charAt(at - 1) == ' ' || source.charAt(at - 1) == '\t')) at--;
        int wordStart = at;
        while (wordStart > 0 && Character.isJavaIdentifierPart(source.charAt(wordStart - 1))) wordStart--;
        if (wordStart == at) return false;
        String preceding = source.substring(wordStart, at);
        return preceding.equals("fun") || preceding.equals("data") || preceding.equals("class");
    }

    private static boolean isKeyword(String name) {
        return switch (name) {
            case "if", "while", "for", "fun", "data", "class", "contract", "return", "use",
                 "match", "case", "import", "and", "or", "not", "in", "is", "else", "break",
                 "continue", "try", "catch", "throw", "implements", "extends", "immutable",
                 "export", "where" -> true;
            default -> false;
        };
    }

    /** Converts a line/character pair to an absolute offset, clamping to the document. */
    private static int offsetOf(String source, int line, int character) {
        if (line < 0 || character < 0) return -1;
        int start = 0;
        for (int i = 0; i < line; i++) {
            int newline = source.indexOf('\n', start);
            if (newline < 0) return -1;
            start = newline + 1;
        }
        int end = source.indexOf('\n', start);
        if (end < 0) end = source.length();
        return start + Math.min(character, end - start);
    }
}
