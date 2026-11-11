package dev.oqthara.lsp;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Docstring;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqCallable;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers {@code textDocument/hover} from the document's own declarations and, failing
 * that, from the builtin table the interpreter installs.
 *
 * <p>The reply is markdown. A hover that resolved nothing returns null, which is how the
 * protocol says "nothing to show" — an empty string would draw an empty box in every
 * editor.
 *
 * <p>Resolution is lexical, not semantic: the identifier under the cursor is matched
 * against declaration names in the same file first. Shadowing is not modelled, so the answer
 * describes the declaration the reader can see rather than the one the program would actually
 * call.
 *
 * <p>Failing that, {@code use "oq:name"} imports <em>are</em> followed, through the same
 * {@code Modules.resolveModule} the interpreter uses, so a name imported from a module is
 * described as richly as one declared locally. Following only happens for a document that
 * came from a {@code file:} URI, because the search order is defined relative to the importing
 * file's directory and an untitled buffer has none.
 */
final class Hover {
    private Hover() {
    }

    /**
     * Returns a markdown hover for the identifier at {@code line}/{@code character}, or null
     * when the position holds no identifier or nothing declares it.
     *
     * @param source the whole document, as the editor last sent it
     * @param line   zero-based line, as LSP numbers it
     * @param character zero-based UTF-16 offset into that line
     */
    static String hover(String uri, String source, int line, int character) {
        Word word = wordAt(source, line, character);
        if (word == null) return null;

        Ast.Program program;
        try {
            program = Parser.parse(uri, source);
        } catch (RuntimeException e) {
            // A document that does not parse has no declarations to describe. Falling through
            // to the builtin table would describe a name the file never defines.
            return null;
        }
        String declared = describeDeclaration(program, word.text());
        if (declared != null) return declared;
        String imported = describeImported(uri, program, word.text());
        if (imported != null) return imported;
        return describeBuiltin(word.text());
    }

    /**
     * Renders a declaration in the same shape {@code oq doc --source} uses, so the editor and
     * the generated page agree. Declarations are searched at top level and inside class and
     * contract bodies, because those are where a reader hovers.
     */
    private static String describeDeclaration(Ast.Program program, String name) {
        for (Ast.Stmt statement : program.statements()) {
            String rendered = describe(statement, name);
            if (rendered != null) return rendered;
        }
        return null;
    }

    /**
     * Describes a name the document imports rather than declares.
     *
     * <p>Imports are walked in the order the interpreter would load them and in dependency
     * order, so a module that imports another is searched before the importer's own list is
     * exhausted. A name found in more than one module resolves to the first, which is what
     * "loaded once, in order" means; the reply does not claim to disambiguate shadowing.
     *
     * <p>Any failure -- an untitled buffer, a module that moved, a module that no longer
     * parses -- yields null rather than an error. A hover is a hint, and an editor asking
     * about a half-typed file must not receive a fault for asking.
     */
    private static String describeImported(String uri, Ast.Program program, String name) {
        Path base = Symbols.directoryOf(uri);
        if (base == null) return null;
        return Symbols.searchImports(program, base, module -> describeDeclaration(module, name));
    }

    private static String describe(Ast.Stmt statement, String name) {
        if (statement instanceof Ast.FuncDecl func && func.name().equals(name)) {
            return render("fun", func.name() + "(" + String.join(", ", func.params()) + ")",
                    func.doc(), func.params());
        }
        if (statement instanceof Ast.DataDecl data && data.name().equals(name)) {
            List<String> fields = fieldNames(data.fields());
            return render("data", data.name() + "(" + String.join(", ", fields) + ")"
                    + (data.immutable() ? " immutable" : ""), data.doc(), fields);
        }
        if (statement instanceof Ast.ClassDecl type && type.name().equals(name)) {
            List<String> fields = fieldNames(type.fields());
            String heading = "class " + type.name() + "(" + String.join(", ", fields) + ")";
            if (type.implementsName() != null) heading += " implements " + type.implementsName();
            return render("class", heading.substring("class ".length()), type.doc(), fields);
        }
        if (statement instanceof Ast.ContractDecl contract && contract.name().equals(name)) {
            return render("contract", contract.name(), contract.doc(), List.of());
        }
        return null;
    }

    private static List<String> fieldNames(List<Ast.FieldDef> fields) {
        List<String> names = new ArrayList<>();
        for (Ast.FieldDef field : fields) names.add(field.name());
        return names;
    }

    private static String render(String keyword, String signature, String doc,
                                 List<String> params) {
        StringBuilder text = new StringBuilder("```oq\n").append(keyword).append(' ')
                .append(signature).append("\n```\n");
        Docstring parsed = Docstring.parse(doc);
        if (parsed.summary() != null && !parsed.summary().isBlank()) {
            text.append('\n').append(parsed.summary()).append('\n');
        }
        Map<String, String> described = new LinkedHashMap<>();
        for (String param : params) {
            String description = parsed.param(param);
            if (description != null) described.put(param, description);
        }
        if (!described.isEmpty()) {
            text.append('\n');
            described.forEach((param, description) ->
                    text.append("- **").append(param).append("** ").append(description).append('\n'));
        }
        appendTag(text, "Returns", parsed.tag("return"));
        for (String name : parsed.tagNames()) {
            if (name.equals("param") || name.equals("return")) continue;
            appendTag(text, capitalize(name), parsed.tag(name));
        }
        return text.toString();
    }

    private static void appendTag(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) return;
        text.append('\n').append(label).append(": ").append(value).append('\n');
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Describes a builtin from the interpreter's own table. Arity comes from probing the
     * function with {@code accepts}, so the hint is the one the call site will be checked
     * against; a name the table does not hold returns null.
     */
    private static String describeBuiltin(String name) {
        OqCallable callable = Symbols.builtins().get(name);
        if (callable == null) return null;
        StringBuilder text = new StringBuilder();
        if (callable instanceof dev.oqthara.runtime.OverloadedFunction overload) {
            for (OqCallable candidate : overload.candidates()) {
                text.append("```oq\n").append(name).append('(').append(arityHint(candidate))
                        .append(")\n```\n");
            }
        } else {
            text.append("```oq\n").append(name).append('(').append(arityHint(callable))
                    .append(")\n```\n");
        }
        return text.append("\nStandard library function.\n").toString();
    }

    /** Finds the arity range by asking the function, which is where the check actually happens. */
    private static String arityHint(OqCallable callable) {
        if (!(callable instanceof dev.oqthara.runtime.NativeFunction function)) return "...";
        // Probe a range wider than the standard library needs, then describe what was found.
        int probe = 12;
        int min = -1;
        int max = -1;
        for (int arity = 0; arity <= probe; arity++) {
            if (!function.accepts(arity)) continue;
            if (min < 0) min = arity;
            max = arity;
        }
        if (min < 0) return "...";
        if (min == max) return args(min);
        if (max == probe) return args(min) + ", ...";
        return args(min) + ", ... up to " + max;
    }

    private static String args(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) text.append(", ");
            text.append("arg").append(i);
        }
        return text.toString();
    }

    /**
     * The identifier containing the position. Line and column are clamped, because an editor
     * may report a position one past the end of a line or of the file.
     */
    static Word wordAt(String source, int line, int character) {
        if (line < 0 || character < 0) return null;
        int start = 0;
        int current = 0;
        while (current < line) {
            int newline = source.indexOf('\n', start);
            if (newline < 0) return null;
            start = newline + 1;
            current++;
        }
        int end = source.indexOf('\n', start);
        if (end < 0) end = source.length();
        int lineLength = end - start;
        int column = Math.min(character, lineLength);
        int from = column;
        while (from > 0 && isWordChar(source.charAt(start + from - 1))) from--;
        int to = column;
        while (to < lineLength && isWordChar(source.charAt(start + to))) to++;
        if (from == to) return null;
        String text = source.substring(start + from, start + to);
        if (!Character.isJavaIdentifierStart(text.charAt(0))) return null;
        return new Word(text, start + from, start + to);
    }

    /**
     * The LSP range covering the identifier at the position, or null when there is none.
     * Offsets are converted back to line/character pairs because the editor never sees
     * document offsets.
     */
    static Map<String, Object> rangeAt(String source, int line, int character) {
        Word word = wordAt(source, line, character);
        if (word == null) return null;
        int lineStart = lineStart(source, line);
        int startCharacter = word.start() - lineStart;
        int endCharacter = word.end() - lineStart;
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("line", (long) line);
        start.put("character", (long) startCharacter);
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("line", (long) line);
        end.put("character", (long) endCharacter);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", start);
        range.put("end", end);
        return range;
    }

    private static int lineStart(String source, int line) {
        int start = 0;
        for (int i = 0; i < line; i++) {
            int newline = source.indexOf('\n', start);
            if (newline < 0) return source.length();
            start = newline + 1;
        }
        return start;
    }

    private static boolean isWordChar(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /** An identifier and its absolute offsets into the document. */
    record Word(String text, int start, int end) {
    }
}
