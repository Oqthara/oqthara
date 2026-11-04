package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Parser {
    private static final Set<String> COMMANDS = Set.of("print", "write", "assert");

    private final List<Token> tokens;
    private final String sourceName;
    private int current = 0;

    public Parser(String sourceName, List<Token> tokens) {
        this.sourceName = sourceName;
        this.tokens = tokens;
    }

    public static Ast.Program parse(String sourceName, String source) {
        return new Parser(sourceName, Lexer.lex(sourceName, source)).program();
    }

    public Ast.Program program() {
        List<Ast.Stmt> statements = new ArrayList<>();
        skipSeparators();
        while (!check(Token.Kind.EOF)) {
            statements.add(statement());
            skipSeparators();
        }
        return new Ast.Program(statements, sourceName);
    }

    private Ast.Stmt statement() {
        if (check(Token.Kind.KEYWORD)) {
            return keywordStatement();
        }
        if (check(Token.Kind.IDENT) && COMMANDS.contains(peek().lexeme())
                && !(checkNext(Token.Kind.LPAREN) && isAdjacent(peek(), tokens.get(current + 1)))) {
            return commandStatement();
        }
        return expressionStatement();
    }

    private Ast.Stmt keywordStatement() {
        Token token = advance();
        return switch (token.lexeme()) {
            case "if" -> ifStatement(token);
            case "while" -> whileStatement(token);
            case "for" -> forStatement(token);
            case "fun" -> functionStatement(token);
            case "data" -> dataStatement(token);
            case "return" -> returnStatement(token);
            case "break" -> {
                endStatement();
                yield new Ast.Break(token.span());
            }
            case "continue" -> {
                endStatement();
                yield new Ast.Continue(token.span());
            }
            case "use" -> useStatement(token);
            case "try" -> tryStatement(token);
            default -> throw error(token, "unexpected keyword '" + token.lexeme() + "'",
                    "This keyword cannot start a statement.");
        };
    }

    private Ast.Stmt commandStatement() {
        Token keyword = advance();
        List<Ast.Expr> args = new ArrayList<>();
        if (check(Token.Kind.LPAREN) && isAdjacent(previous(), peek())) {
            advance();
            args = arguments();
        } else if (!atStatementEnd()) {
            do {
                args.add(expression());
            } while (match(Token.Kind.COMMA));
        }
        endStatement();
        return new Ast.Command(keyword.lexeme(), args, keyword.span());
    }

    private Ast.Stmt expressionStatement() {
        Ast.Expr expr = expression();
        if (match(Token.Kind.ASSIGN)) {
            Ast.Expr value = expression();
            endStatement();
            return assignmentFrom(expr, value);
        }
        if (check(Token.Kind.PLUS_ASSIGN) || check(Token.Kind.MINUS_ASSIGN)
                || check(Token.Kind.STAR_ASSIGN) || check(Token.Kind.SLASH_ASSIGN)) {
            Token op = advance();
            Ast.Expr value = expression();
            endStatement();
            if (!(expr instanceof Ast.Var var)) {
                throw error(op, "compound assignment needs a variable on the left", null);
            }
            Token.Kind kind = switch (op.kind()) {
                case PLUS_ASSIGN -> Token.Kind.PLUS;
                case MINUS_ASSIGN -> Token.Kind.MINUS;
                case STAR_ASSIGN -> Token.Kind.STAR;
                default -> Token.Kind.SLASH;
            };
            return new Ast.CompoundAssign(var.name(), kind, value, op.span());
        }
        endStatement();
        return new Ast.ExprStmt(expr, expr.span());
    }

    private Ast.Stmt assignmentFrom(Ast.Expr target, Ast.Expr value) {
        if (target instanceof Ast.Var var) {
            return new Ast.Assign(var.name(), value, var.span());
        }
        if (target instanceof Ast.Index index) {
            return new Ast.IndexAssign(index.target(), index.index(), value, index.span());
        }
        if (target instanceof Ast.Field field) {
            return new Ast.FieldAssign(field.target(), field.name(), value, field.span());
        }
        throw error(peek(), "cannot assign to this expression",
                "The left side of '=' must be a variable, an item in a list/map, or a field.");
    }

    private Ast.Stmt ifStatement(Token keyword) {
        List<Ast.Branch> branches = new ArrayList<>();
        Ast.Expr condition = expression();
        endStatement();
        List<Ast.Stmt> body = block();
        branches.add(new Ast.Branch(condition, body, keyword.span()));
        List<Ast.Stmt> elseBranch = List.of();
        while (matchKeyword("else")) {
            if (matchKeyword("if")) {
                Ast.Expr nextCondition = expression();
                endStatement();
                List<Ast.Stmt> nextBody = block();
                branches.add(new Ast.Branch(nextCondition, nextBody, previous().span()));
                continue;
            }
            endStatement();
            elseBranch = block();
            break;
        }
        expectKeyword("end", "to close this 'if'");
        endStatement();
        return new Ast.If(branches, elseBranch, keyword.span());
    }

    private Ast.Stmt whileStatement(Token keyword) {
        Ast.Expr condition = expression();
        endStatement();
        List<Ast.Stmt> body = block();
        expectKeyword("end", "to close this 'while'");
        endStatement();
        return new Ast.While(condition, body, keyword.span());
    }

    private Ast.Stmt forStatement(Token keyword) {
        Token name = expect(Token.Kind.IDENT, "a loop variable name");
        expectKeyword("in", "after the loop variable");
        Ast.Expr from = expression();
        boolean isRange = from instanceof Ast.RangeExpr;
        Ast.RangeExpr range = isRange ? (Ast.RangeExpr) from : null;
        endStatement();
        List<Ast.Stmt> body = block();
        expectKeyword("end", "to close this 'for'");
        endStatement();
        if (isRange) {
            return new Ast.ForRange(name.lexeme(), false, range.from(), range.to(), range.inclusive(),
                    body, keyword.span());
        }
        return new Ast.ForEach(name.lexeme(), false, from, body, keyword.span());
    }

    private Ast.Stmt functionStatement(Token keyword) {
        Token name = expect(Token.Kind.IDENT, "a function name");
        expect(Token.Kind.LPAREN, "after the function name");
        List<String> params = new ArrayList<>();
        if (!check(Token.Kind.RPAREN)) {
            do {
                params.add(expect(Token.Kind.IDENT, "a parameter name").lexeme());
            } while (match(Token.Kind.COMMA));
        }
        expect(Token.Kind.RPAREN, "to close the parameter list");
        endStatement();
        List<Ast.Stmt> body = block();
        expectKeyword("end", "to close this 'fun'");
        endStatement();
        return new Ast.FuncDecl(name.lexeme(), params, body, keyword.span());
    }

    private Ast.Stmt dataStatement(Token keyword) {
        Token name = expect(Token.Kind.IDENT, "a data type name");
        expect(Token.Kind.LPAREN, "after the type name");
        List<Ast.FieldDef> fields = new ArrayList<>();
        if (!check(Token.Kind.RPAREN)) {
            do {
                Token field = expect(Token.Kind.IDENT, "a field name");
                Ast.Expr defaultValue = null;
                if (match(Token.Kind.ASSIGN)) {
                    defaultValue = expression();
                }
                fields.add(new Ast.FieldDef(field.lexeme(), defaultValue, field.span()));
            } while (match(Token.Kind.COMMA));
        }
        expect(Token.Kind.RPAREN, "to close the field list");
        endStatement();
        return new Ast.DataDecl(name.lexeme(), fields, keyword.span());
    }

    private Ast.Stmt returnStatement(Token keyword) {
        if (atStatementEnd()) {
            endStatement();
            return new Ast.Return(null, keyword.span());
        }
        Ast.Expr value = expression();
        endStatement();
        return new Ast.Return(value, keyword.span());
    }

    private Ast.Stmt tryStatement(Token keyword) {
        endStatement();
        List<Ast.Stmt> body = block();
        expectKeyword("catch", "after the try block");
        String errorName = "error";
        if (check(Token.Kind.IDENT)) {
            errorName = advance().lexeme();
        }
        endStatement();
        List<Ast.Stmt> handler = block();
        expectKeyword("end", "to close this 'try'");
        endStatement();
        return new Ast.TryStmt(body, errorName, handler, keyword.span());
    }

    private Ast.Stmt useStatement(Token keyword) {
        StringBuilder path = new StringBuilder();
        if (check(Token.Kind.STRING)) {
            path.append(advance().literal());
        } else {
            path.append(expect(Token.Kind.IDENT, "a Java class or module name").lexeme());
            while (match(Token.Kind.DOT)) {
                path.append('.').append(expect(Token.Kind.IDENT, "a name after '.'").lexeme());
            }
        }
        endStatement();
        String text = path.toString();
        if (text.startsWith("oq:")) {
            return new Ast.ImportStmt(text.substring(3), keyword.span());
        }
        return new Ast.UseStmt(text, keyword.span());
    }

    private List<Ast.Stmt> block() {
        List<Ast.Stmt> statements = new ArrayList<>();
        skipSeparators();
        while (!check(Token.Kind.EOF) && !(check(Token.Kind.KEYWORD)
                && (peek().lexeme().equals("end") || peek().lexeme().equals("else")
                || peek().lexeme().equals("catch")))) {
            statements.add(statement());
            skipSeparators();
        }
        return statements;
    }

    private Ast.Expr expression() {
        Ast.Expr expr = or();
        if (checkRangeOperator()) {
            Token op = advance();
            Ast.Expr to = or();
            return new Ast.RangeExpr(expr, to, op.lexeme().equals("to"), op.span());
        }
        return expr;
    }

    /**
     * {@code to} and {@code until} are contextual: they only act as range operators in
     * expression position, so they remain usable as variable and parameter names.
     */
    private boolean checkRangeOperator() {
        if (check(Token.Kind.IDENT)) {
            String text = peek().lexeme();
            return text.equals("to") || text.equals("until");
        }
        if (check(Token.Kind.KEYWORD)) {
            String text = peek().lexeme();
            return text.equals("to") || text.equals("until");
        }
        return false;
    }

    private Ast.Expr or() {
        Ast.Expr expr = and();
        while (matchKeyword("or")) {
            Token op = previous();
            Ast.Expr right = and();
            expr = new Ast.Logical(expr, "or", right, op.span());
        }
        return expr;
    }

    private Ast.Expr and() {
        Ast.Expr expr = equality();
        while (matchKeyword("and")) {
            Token op = previous();
            Ast.Expr right = equality();
            expr = new Ast.Logical(expr, "and", right, op.span());
        }
        return expr;
    }

    private Ast.Expr equality() {
        Ast.Expr expr = comparison();
        while (match(Token.Kind.EQEQ, Token.Kind.NOTEQ)) {
            Token op = previous();
            Ast.Expr right = comparison();
            expr = new Ast.Binary(expr, op.kind(), right, op.span());
        }
        return expr;
    }

    private Ast.Expr comparison() {
        Ast.Expr expr = additive();
        while (true) {
            if (match(Token.Kind.LT, Token.Kind.LE, Token.Kind.GT, Token.Kind.GE)) {
                Token op = previous();
                Ast.Expr right = additive();
                expr = new Ast.Binary(expr, op.kind(), right, op.span());
            } else if (matchKeyword("in")) {
                Token op = previous();
                Ast.Expr right = additive();
                expr = new Ast.InExpr(expr, right, op.span());
            } else {
                break;
            }
        }
        return expr;
    }

    private Ast.Expr additive() {
        Ast.Expr expr = multiplicative();
        while (match(Token.Kind.PLUS, Token.Kind.MINUS)) {
            Token op = previous();
            Ast.Expr right = multiplicative();
            expr = new Ast.Binary(expr, op.kind(), right, op.span());
        }
        return expr;
    }

    private Ast.Expr multiplicative() {
        Ast.Expr expr = power();
        while (match(Token.Kind.STAR, Token.Kind.SLASH, Token.Kind.PERCENT)) {
            Token op = previous();
            Ast.Expr right = power();
            expr = new Ast.Binary(expr, op.kind(), right, op.span());
        }
        return expr;
    }

    private Ast.Expr power() {
        Ast.Expr expr = unary();
        if (match(Token.Kind.STARSTAR)) {
            Token op = previous();
            Ast.Expr right = power();
            expr = new Ast.Binary(expr, op.kind(), right, op.span());
        }
        return expr;
    }

    private Ast.Expr unary() {
        if (match(Token.Kind.MINUS, Token.Kind.PLUS)) {
            Token op = previous();
            Ast.Expr operand = unary();
            return new Ast.Unary(op.kind(), operand, op.span());
        }
        if (matchKeyword("not")) {
            Token op = previous();
            Ast.Expr operand = unary();
            return new Ast.Unary(Token.Kind.KEYWORD, operand, op.span());
        }
        return postfix();
    }

    private Ast.Expr postfix() {
        Ast.Expr expr = primary();
        while (true) {
            if (match(Token.Kind.LPAREN)) {
                Token paren = previous();
                List<Ast.Expr> args = arguments();
                expr = new Ast.Call(expr, args, paren.span());
            } else if (match(Token.Kind.LBRACKET)) {
                Token bracket = previous();
                Ast.Expr index = expression();
                expect(Token.Kind.RBRACKET, "to close the index");
                expr = new Ast.Index(expr, index, bracket.span());
            } else if (match(Token.Kind.DOT)) {
                Token dot = previous();
                Token name = expect(Token.Kind.IDENT, "a field or method name after '.'");
                if (check(Token.Kind.LPAREN)) {
                    advance();
                    List<Ast.Expr> args = arguments();
                    expr = new Ast.MethodCall(expr, name.lexeme(), args, dot.span());
                } else {
                    expr = new Ast.Field(expr, name.lexeme(), dot.span());
                }
            } else {
                break;
            }
        }
        return expr;
    }

    private List<Ast.Expr> arguments() {
        List<Ast.Expr> args = new ArrayList<>();
        skipNewlines();
        if (!check(Token.Kind.RPAREN)) {
            while (true) {
                args.add(expression());
                skipNewlines();
                if (!match(Token.Kind.COMMA)) break;
                skipNewlines();
                if (check(Token.Kind.RPAREN)) break;
            }
        }
        expect(Token.Kind.RPAREN, "to close the argument list");
        return args;
    }

    private Ast.Expr primary() {
        if (match(Token.Kind.NUMBER)) {
            Token token = previous();
            return new Ast.NumberLit(token.literal(), token.span());
        }
        if (match(Token.Kind.STRING)) {
            Token token = previous();
            return stringLiteral(token);
        }
        if (matchKeyword("true")) {
            return new Ast.BoolLit(true, previous().span());
        }
        if (matchKeyword("false")) {
            return new Ast.BoolLit(false, previous().span());
        }
        if (matchKeyword("nothing")) {
            return new Ast.NothingLit(previous().span());
        }
        if (matchKeyword("fun")) {
            return lambdaFromFun(previous());
        }
        if (match(Token.Kind.LBRACKET)) {
            return listLiteral(previous());
        }
        if (match(Token.Kind.LBRACE)) {
            return collectionLiteral(previous());
        }
        if (match(Token.Kind.LPAREN)) {
            Token paren = previous();
            List<String> lambdaParams = lambdaParametersAhead();
            if (lambdaParams != null) {
                List<String> params = new ArrayList<>();
                if (!check(Token.Kind.RPAREN)) {
                    do {
                        params.add(expect(Token.Kind.IDENT, "a parameter name").lexeme());
                    } while (match(Token.Kind.COMMA));
                }
                expect(Token.Kind.RPAREN, "to close the parameter list");
                expect(Token.Kind.MINUS, "before '>' in a lambda");
                expect(Token.Kind.GT, "to finish the lambda arrow");
                Ast.Expr body = expression();
                return new Ast.Lambda(params, body, null, paren.span());
            }
            Ast.Expr inner = expression();
            expect(Token.Kind.RPAREN, "to close the expression");
            return new Ast.Grouping(inner, paren.span());
        }
        if (check(Token.Kind.IDENT)) {
            Token name = advance();
            if (check(Token.Kind.MINUS) && checkNext(Token.Kind.GT)) {
                advance();
                advance();
                Ast.Expr body = expression();
                return new Ast.Lambda(List.of(name.lexeme()), body, null, name.span());
            }
            return new Ast.Var(name.lexeme(), name.span());
        }
        throw error(peek(), "expected an expression", "Found " + describe(peek()) + " instead.");
    }

    private Ast.Lambda lambdaFromFun(Token keyword) {
        expect(Token.Kind.LPAREN, "after 'fun' in an anonymous function");
        List<String> params = new ArrayList<>();
        if (!check(Token.Kind.RPAREN)) {
            do {
                params.add(expect(Token.Kind.IDENT, "a parameter name").lexeme());
            } while (match(Token.Kind.COMMA));
        }
        expect(Token.Kind.RPAREN, "to close the parameter list");
        if (atStatementEnd()) {
            endStatement();
            List<Ast.Stmt> body = block();
            expectKeyword("end", "to close this anonymous function");
            return new Ast.Lambda(params, null, body, keyword.span());
        }
        expect(Token.Kind.MINUS, "before '>' in an anonymous function");
        expect(Token.Kind.GT, "to finish the arrow");
        Ast.Expr body = expression();
        return new Ast.Lambda(params, body, null, keyword.span());
    }

    private Ast.Expr listLiteral(Token bracket) {
        List<Ast.Expr> items = new ArrayList<>();
        skipNewlines();
        if (!check(Token.Kind.RBRACKET)) {
            while (true) {
                items.add(expression());
                skipNewlines();
                if (!match(Token.Kind.COMMA)) break;
                skipNewlines();
                if (check(Token.Kind.RBRACKET)) break;
            }
        }
        expect(Token.Kind.RBRACKET, "to close the list");
        return new Ast.ListLit(items, bracket.span());
    }

    private Ast.Expr collectionLiteral(Token brace) {
        skipNewlines();
        if (check(Token.Kind.RBRACE)) {
            advance();
            return new Ast.MapLit(List.of(), brace.span());
        }
        Ast.Expr first = expression();
        skipNewlines();
        if (match(Token.Kind.COLON)) {
            List<Ast.Entry> entries = new ArrayList<>();
            entries.add(new Ast.Entry(first, expression()));
            skipNewlines();
            while (match(Token.Kind.COMMA)) {
                skipNewlines();
                if (check(Token.Kind.RBRACE)) break;
                Ast.Expr key = expression();
                expect(Token.Kind.COLON, "between a map key and its value");
                entries.add(new Ast.Entry(key, expression()));
                skipNewlines();
            }
            expect(Token.Kind.RBRACE, "to close the map");
            return new Ast.MapLit(entries, brace.span());
        }
        List<Ast.Expr> items = new ArrayList<>();
        items.add(first);
        skipNewlines();
        while (match(Token.Kind.COMMA)) {
            skipNewlines();
            if (check(Token.Kind.RBRACE)) break;
            items.add(expression());
            skipNewlines();
        }
        expect(Token.Kind.RBRACE, "to close the set");
        return new Ast.SetLit(items, brace.span());
    }

    private List<String> lambdaParametersAhead() {
        int i = current;
        List<String> params = new ArrayList<>();
        if (i < tokens.size() && tokens.get(i).is(Token.Kind.RPAREN)) {
            i++;
        } else {
            while (i < tokens.size()) {
                Token token = tokens.get(i);
                if (!token.is(Token.Kind.IDENT)) return null;
                params.add(token.lexeme());
                i++;
                if (i >= tokens.size()) return null;
                Token sep = tokens.get(i);
                if (sep.is(Token.Kind.COMMA)) {
                    i++;
                    continue;
                }
                if (sep.is(Token.Kind.RPAREN)) {
                    i++;
                    break;
                }
                return null;
            }
        }
        if (i + 1 < tokens.size()
                && tokens.get(i).is(Token.Kind.MINUS)
                && tokens.get(i + 1).is(Token.Kind.GT)) {
            return params;
        }
        return null;
    }

    private Ast.Expr stringLiteral(Token token) {
        String raw = (String) token.literal();
        if (raw.contains("\n")) {
            raw = dedent(raw);
        }
        List<Ast.StringPart> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int escapedOpens = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> text.append('\n');
                    case 't' -> text.append('\t');
                    case 'r' -> text.append('\r');
                    case '\\' -> text.append('\\');
                    case '"' -> text.append('"');
                    case '\'' -> text.append('\'');
                    case '{' -> text.append('{');
                    case '}' -> text.append('}');
                    case '0' -> text.append('\0');
                    default -> text.append('\\').append(next);
                }
                continue;
            }
            if (c == '{' && i + 1 < raw.length() && raw.charAt(i + 1) == '{') {
                text.append('{');
                escapedOpens++;
                i++;
                continue;
            }
            if (c == '}' && i + 1 < raw.length() && raw.charAt(i + 1) == '}' && escapedOpens > 0) {
                text.append('}');
                escapedOpens--;
                i++;
                continue;
            }
            if (c == '{' && startsInterpolation(raw, i)) {
                int depth = 1;
                int start = i + 1;
                int j = start;
                boolean inString = false;
                char quote = 0;
                for (; j < raw.length(); j++) {
                    char inner = raw.charAt(j);
                    if (!inString && inner == '\\') {
                        j++;
                        continue;
                    }
                    if (inString) {
                        if (inner == '\\') {
                            j++;
                        } else if (inner == quote) {
                            inString = false;
                        }
                        continue;
                    }
                    if (inner == '"' || inner == '\'') {
                        inString = true;
                        quote = inner;
                    } else if (inner == '{') {
                        depth++;
                    } else if (inner == '}') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                if (depth != 0) {
                    throw new OqError("interpolation is missing a closing '}'", token.span(),
                            "Write {expression} inside text.");
                }
                String exprSource = raw.substring(start, j);
                if (!text.isEmpty()) {
                    parts.add(new Ast.TextPart(text.toString()));
                    text.setLength(0);
                }
                Ast.Expr expr = parseInterpolated(exprSource, token.span());
                parts.add(new Ast.ExprPart(expr));
                i = j;
                continue;
            }
            text.append(c);
        }
        if (!text.isEmpty() || parts.isEmpty()) {
            parts.add(new Ast.TextPart(text.toString()));
        }
        return new Ast.StringLit(parts, token.span());
    }

    private static String dedent(String raw) {
        String normalized = raw.replace("\r\n", "\n");
        if (normalized.startsWith("\n")) {
            normalized = normalized.substring(1);
        }
        String[] lines = normalized.split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            common = Math.min(common, indent);
        }
        if (common == Integer.MAX_VALUE) common = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) sb.append('\n');
            if (line.length() >= common) sb.append(line.substring(common));
            else sb.append(line.stripLeading());
        }
        String result = sb.toString();
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static boolean startsInterpolation(String raw, int index) {
        if (index + 1 >= raw.length()) return false;
        char next = raw.charAt(index + 1);
        return Character.isLetterOrDigit(next) || next == '_' || next == '(' || next == '['
                || next == '!' || next == '-';
    }

    private Ast.Expr parseInterpolated(String source, Span span) {
        source = source.replace("\\\"", "\"").replace("\\'", "'");
        List<Token> innerTokens;
        try {
            innerTokens = Lexer.lex(sourceName, source);
        } catch (OqError e) {
            throw new OqError("invalid expression inside text: " + e.getMessage(), span, e.hint());
        }
        Parser inner = new Parser(sourceName, innerTokens);
        try {
            Ast.Expr expr = inner.expression();
            if (!inner.check(Token.Kind.EOF) && !inner.check(Token.Kind.NEWLINE)) {
                throw new OqError("invalid expression inside text", span,
                        "Only a single expression is allowed inside { }.");
            }
            return expr;
        } catch (OqError e) {
            throw new OqError("invalid expression inside text: " + e.getMessage(), span, e.hint());
        }
    }

    private void endStatement() {
        if (match(Token.Kind.SEMICOLON)) {
            while (match(Token.Kind.SEMICOLON)) {
            }
            return;
        }
        if (check(Token.Kind.NEWLINE)) {
            advance();
            while (check(Token.Kind.NEWLINE)) advance();
            return;
        }
        if (check(Token.Kind.EOF)) return;
        throw error(peek(), "unexpected " + describe(peek()) + " after a statement",
                "Statements end at the end of the line.");
    }

    private static boolean isAdjacent(Token left, Token right) {
        return left.span().endLine() == right.span().line() && left.span().endColumn() == right.span().column();
    }

    private boolean atStatementEnd() {
        return check(Token.Kind.NEWLINE) || check(Token.Kind.EOF) || check(Token.Kind.SEMICOLON);
    }

    private void skipSeparators() {
        while (match(Token.Kind.NEWLINE) || match(Token.Kind.SEMICOLON)) {
        }
    }

    private void skipNewlines() {
        while (match(Token.Kind.NEWLINE)) {
        }
    }

    private boolean match(Token.Kind... kinds) {
        for (Token.Kind kind : kinds) {
            if (check(kind)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean matchKeyword(String keyword) {
        if (check(Token.Kind.KEYWORD) && peek().lexeme().equals(keyword)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(Token.Kind kind, String what) {
        if (check(kind)) return advance();
        throw error(peek(), "expected " + what, "Found " + describe(peek()) + " instead.");
    }

    private void expectKeyword(String keyword, String reason) {
        if (matchKeyword(keyword)) return;
        throw error(peek(), "expected '" + keyword + "' " + reason, "Found " + describe(peek()) + " instead.");
    }

    private boolean check(Token.Kind kind) {
        return peek().kind() == kind;
    }

    private boolean checkNext(Token.Kind kind) {
        return current + 1 < tokens.size() && tokens.get(current + 1).kind() == kind;
    }

    private Token advance() {
        if (!check(Token.Kind.EOF)) current++;
        return previous();
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private static String describe(Token token) {
        return switch (token.kind()) {
            case EOF -> "the end of the file";
            case NEWLINE -> "the end of the line";
            case STRING -> "text";
            case NUMBER -> "number " + token.lexeme();
            case IDENT -> "'" + token.lexeme() + "'";
            case KEYWORD -> "keyword '" + token.lexeme() + "'";
            default -> "'" + token.lexeme() + "'";
        };
    }

    private static OqError error(Token token, String message, String hint) {
        return new OqError(message, token.span(), hint);
    }
}
