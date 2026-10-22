package dev.plca.frontend.st.kit;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Identifier;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceMap;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instruction List (IEC 61131-3 2nd ed. §3.2; removed in the 4th) lowered to ST statements by following the current
 * result (CR) symbolically ({@code docs/grammar/codesys.md} §10): {@code LD a / AND b / ST c} becomes {@code c := a AND
 * b;}. Lines are scanned by hand (IL is one instruction per line: {@code [label:] op[N][C][(] [operand]}); operands are
 * ST expressions, parsed by the ST grammar with spans in the file. Where the CR crosses a label or a jump it is kept in
 * the synthetic variable {@code cr}.
 */
public final class IlTranslator {

    /** An IL line that is not an instruction, or an operator without its operand. */
    public static final String IL_SYNTAX = "PLC-IL-001";

    /** Operators whose operand is combined with the CR. */
    private static final Set<String> BINARY =
            Set.of("AND", "&", "OR", "XOR", "ADD", "SUB", "MUL", "DIV", "MOD", "GT", "GE", "EQ", "NE", "LE", "LT");
    /** Function block input operators ({@code S1 rs}: {@code rs.S1 := CR}). */
    private static final Set<String> FB_INPUTS = Set.of("S1", "R1", "CLK", "CU", "CD", "PV", "IN", "PT");
    /** Words that start an IL instruction; used to tell an IL body from ST. */
    public static final Set<String> OPERATORS = Set.of(
            "LD", "LDN", "ST", "STN", "S", "R", "AND", "ANDN", "OR", "ORN", "XOR", "XORN", "NOT", "ADD", "SUB", "MUL",
            "DIV", "MOD", "GT", "GE", "EQ", "NE", "LE", "LT", "JMP", "JMPC", "JMPCN", "CAL", "CALC", "CALCN", "RET",
            "RETC", "RETCN", "S1", "R1", "CLK", "CU", "CD", "PV", "IN", "PT");

    private static final Pattern LABEL = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:(?!=)");
    private static final Pattern CAL_LINE =
            Pattern.compile("\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\s*:(?!=))?\\s*CALC?N?\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPERATOR = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*|&N?|&)\\s*(\\()?");

    private final SourceText text;
    private final SourceRegistry registry;
    private final DiagnosticSink sink;
    private final Function<SourceText, Expression> parser;

    private final List<Statement> out = new ArrayList<>();
    private final Deque<Deferred> deferred = new ArrayDeque<>();
    private Expression cr;

    /** A deferred operation {@code ADD(}: the operator and the CR it applies to once {@code )} is reached. */
    private record Deferred(BinaryOp op, boolean negate, Expression left, Span span) {}

    /**
     * {@code text}: the IL body (a registered text; its map leads to the file). {@code parser}: an ST expression parser
     * for one registered text.
     */
    public IlTranslator(
            SourceText text, SourceRegistry registry, DiagnosticSink sink, Function<SourceText, Expression> parser) {
        this.text = text;
        this.registry = registry;
        this.sink = sink;
        this.parser = parser;
    }

    /** True when most instruction-like lines of {@code body} start with an IL operator and none is an ST statement. */
    public static boolean looksLikeIl(String body) {
        String code = blankComments(body);
        int il = 0;
        int other = 0;
        for (String line : code.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty()) {
                continue;
            }
            Matcher lm = LABEL.matcher(l);
            if (lm.lookingAt()) {
                l = l.substring(lm.end()).strip();
                if (l.isEmpty()) {
                    continue;
                }
            }
            if (l.startsWith(")")) {
                il++;
                continue;
            }
            Matcher om = OPERATOR.matcher(l);
            boolean matched = om.lookingAt();
            String op = matched ? om.group(1).toUpperCase(Locale.ROOT) : "";
            boolean assignment = l.matches("[^(]*:=.*") && !op.startsWith("CAL");
            // only a binary operator may be followed by `(` (a deferral): `s();` is an ST call
            boolean call = matched && om.group(2) != null && binary(strip(op)) == null;
            boolean separated = matched
                    && (om.group(2) != null
                            || om.end() == l.length()
                            || Character.isWhitespace(l.charAt(om.end() - 1))
                            || l.charAt(om.end()) == ';');
            if (OPERATORS.contains(op) && !assignment && !call && separated) {
                il++;
            } else {
                other++;
            }
        }
        return il >= 2 && il >= 4 * other;
    }

    /** The body's statements. */
    public List<Statement> translate() {
        String code = blankComments(text.text());
        int lineStart = 0;
        int n = code.length();
        while (lineStart <= n) {
            int lineEnd = code.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = n;
            }
            // a CAL with formal arguments may continue over several lines until its parentheses close
            int end = lineEnd;
            boolean cal = CAL_LINE.matcher(code).region(lineStart, lineEnd).lookingAt();
            while (cal && end < n && depth(code, lineStart, end) > 0) {
                int next = code.indexOf('\n', end + 1);
                end = next < 0 ? n : next;
            }
            line(code, lineStart, end);
            lineStart = end + 1;
        }
        return List.copyOf(out);
    }

    private void line(String code, int s, int e) {
        int i = s;
        Matcher lm = LABEL.matcher(code).region(s, e);
        if (lm.lookingAt()) {
            Span ls = text.span(lm.start(1), lm.end(1));
            keepCr(ls);
            out.add(new Statement.Label(new Identifier(lm.group(1), ls), ls));
            cr = crRef(ls);
            i = lm.end();
        }
        String rest = code.substring(i, e);
        String trimmed = rest.strip();
        if (trimmed.isEmpty() || trimmed.equals(";")) {
            return;
        }
        int at = i + rest.indexOf(trimmed.charAt(0));
        if (trimmed.startsWith(")")) {
            close(text.span(at, at + 1));
            return;
        }
        Matcher om = OPERATOR.matcher(code).region(at, e);
        if (!om.lookingAt()) {
            syntax("not an IL instruction", text.span(at, e));
            return;
        }
        String op = om.group(1).toUpperCase(Locale.ROOT);
        boolean open = om.group(2) != null;
        int os = om.end();
        int oe = e;
        while (oe > os && (Character.isWhitespace(code.charAt(oe - 1)) || code.charAt(oe - 1) == ';')) {
            oe--;
        }
        while (os < oe && Character.isWhitespace(code.charAt(os))) {
            os++;
        }
        // `AND (a OR b)` on one line: a parenthesised operand, not a deferral
        if (open && oe > os && code.charAt(oe - 1) == ')' && depth(code, os, oe) < 0) {
            open = false;
            os = om.start(2);
        }
        Span span = text.span(om.start(1), Math.max(om.end(1), oe));
        instruction(op, open, os, oe, span);
    }

    private void instruction(String op, boolean open, int os, int oe, Span span) {
        boolean hasOperand = oe > os;
        if (open) {
            BinaryOp bop = binary(strip(op));
            if (bop == null) {
                syntax("'" + op + "(' cannot be deferred", span);
                return;
            }
            deferred.push(new Deferred(bop, negated(op), current(span), span));
            cr = hasOperand ? operand(os, oe, span) : null;
            return;
        }
        switch (op) {
            case "LD", "LDN" -> cr = negate(op.equals("LDN"), need(os, oe, op, span), span);
            case "ST", "STN" -> {
                Expression target = need(os, oe, op, span);
                out.add(assign(target, negate(op.equals("STN"), current(span), span), span));
                // the CR is unchanged and now also in the stored variable: read that instead of repeating the
                // expression
                if (op.equals("ST") && !(target instanceof Expression.Invalid)) {
                    cr = target;
                }
            }
            case "S", "R" ->
                out.add(guard(current(span), assign(need(os, oe, op, span), bool(op.equals("S"), span), span), span));
            case "NOT" -> cr = new Expression.Unary(Expression.UnaryOp.NOT, current(span), span);
            case "JMP", "JMPC", "JMPCN" -> {
                keepCr(span);
                Expression cond = op.equals("JMP") ? null : current(span);
                Statement jump = new Statement.Jump(label(os, oe, span), span);
                out.add(cond == null ? jump : guard(negate(op.equals("JMPCN"), cond, span), jump, span));
                cr = crRef(span);
            }
            case "RET", "RETC", "RETCN" -> {
                Statement ret = new Statement.Return(null, span);
                out.add(op.equals("RET") ? ret : guard(negate(op.equals("RETCN"), current(span), span), ret, span));
            }
            case "CAL", "CALC", "CALCN" -> {
                Expression callee = need(os, oe, op, span);
                Expression call =
                        callee instanceof Expression.Call ? callee : new Expression.Call(callee, List.of(), span);
                Statement st = new Statement.CallStatement(call, span);
                out.add(op.equals("CAL") ? st : guard(negate(op.equals("CALCN"), current(span), span), st, span));
            }
            default -> {
                BinaryOp bop = binary(strip(op));
                if (bop != null) {
                    Expression right = negate(negated(op), need(os, oe, op, span), span);
                    cr = new Expression.Binary(bop, current(span), right, span);
                } else if (FB_INPUTS.contains(op)) {
                    Expression fb = need(os, oe, op, span);
                    Expression member = new Expression.MemberAccess(fb, new Identifier(op, span), span);
                    out.add(assign(member, current(span), span));
                } else {
                    // a function: the CR is its first argument (`LIMIT 0, 100`)
                    List<Argument> args = new ArrayList<>();
                    args.add(positional(current(span)));
                    if (hasOperand) {
                        for (int[] r : split(os, oe)) {
                            args.add(positional(operand(r[0], r[1], span)));
                        }
                    }
                    Identifier f = new Identifier(op, span);
                    cr = new Expression.Call(new Expression.NameRef(f, false, span), args, span);
                }
            }
        }
    }

    private void close(Span span) {
        if (deferred.isEmpty()) {
            syntax("')' without a deferred operation", span);
            return;
        }
        Deferred d = deferred.pop();
        Expression right = negate(d.negate(), current(span), span);
        cr = new Expression.Binary(d.op(), d.left(), right, d.span());
    }

    // ---- operands -------------------------------------------------------------------------------

    private Expression need(int s, int e, String op, Span span) {
        if (e <= s) {
            syntax("'" + op + "' needs an operand", span);
            return new Expression.Invalid(span);
        }
        return operand(s, e, span);
    }

    /** The ST expression in {@code text[s, e)}. */
    private Expression operand(int s, int e, Span fallback) {
        Expression fast = SimpleExpressions.simple(text, s, e);
        if (fast != null) {
            return fast;
        }
        int fs = text.map().toFileStart(s);
        int fe = text.map().toFileEnd(e);
        String piece = text.text().substring(s, e);
        boolean verbatim = fe - fs == e - s;
        SourceMap map = verbatim
                ? SourceMap.verbatim(fs, e - s)
                : SourceMap.builder()
                        .replacement(fs, Math.max(0, fe - fs), e - s)
                        .build();
        SourceText t = registry.registerFragment(text.file(), piece, map);
        Expression ex = parser.apply(t);
        return ex == null ? new Expression.Invalid(fallback) : ex;
    }

    private Identifier label(int s, int e, Span span) {
        String l = text.text().substring(s, Math.max(s, e)).strip();
        if (l.isEmpty()) {
            syntax("jump without a label", span);
            return new Identifier("<missing>", span);
        }
        return new Identifier(l, text.span(s, e));
    }

    /** Top-level comma-separated parts of {@code [s, e)}. */
    private List<int[]> split(int s, int e) {
        List<int[]> parts = new ArrayList<>();
        String c = text.text();
        int d = 0;
        int from = s;
        boolean quoted = false;
        for (int i = s; i < e; i++) {
            char ch = c.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
            } else if (!quoted && (ch == '(' || ch == '[')) {
                d++;
            } else if (!quoted && (ch == ')' || ch == ']')) {
                d--;
            } else if (!quoted && ch == ',' && d == 0) {
                parts.add(trim(from, i));
                from = i + 1;
            }
        }
        parts.add(trim(from, e));
        return parts;
    }

    private int[] trim(int s, int e) {
        String c = text.text();
        while (s < e && Character.isWhitespace(c.charAt(s))) {
            s++;
        }
        while (e > s && Character.isWhitespace(c.charAt(e - 1))) {
            e--;
        }
        return new int[] {s, e};
    }

    // ---- the current result ---------------------------------------------------------------------

    /** The CR; the synthetic {@code cr} when nothing has been loaded on this path. */
    private Expression current(Span span) {
        return cr != null ? cr : crRef(span);
    }

    /** Before a label or jump: the CR goes into {@code cr}, where the other path finds it. */
    private void keepCr(Span span) {
        if (cr != null && !isCrRef(cr)) {
            out.add(assign(crRef(span), cr, span));
            cr = crRef(span);
        }
    }

    private static Expression crRef(Span span) {
        return new Expression.NameRef(new Identifier("cr", Identifier.Form.SYNTHETIC, span), false, span);
    }

    private static boolean isCrRef(Expression e) {
        return e instanceof Expression.NameRef n
                && n.name().form() == Identifier.Form.SYNTHETIC
                && n.name().name().equals("cr");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String strip(String op) {
        if (op.equals("&N")) {
            return "&";
        }
        return op.length() > 2 && op.endsWith("N") && BINARY.contains(op.substring(0, op.length() - 1))
                ? op.substring(0, op.length() - 1)
                : op;
    }

    private static boolean negated(String op) {
        return !strip(op).equals(op);
    }

    private static BinaryOp binary(String op) {
        return switch (op) {
            case "AND", "&" -> BinaryOp.AND;
            case "OR" -> BinaryOp.OR;
            case "XOR" -> BinaryOp.XOR;
            case "ADD" -> BinaryOp.ADD;
            case "SUB" -> BinaryOp.SUB;
            case "MUL" -> BinaryOp.MUL;
            case "DIV" -> BinaryOp.DIV;
            case "MOD" -> BinaryOp.MOD;
            case "GT" -> BinaryOp.GT;
            case "GE" -> BinaryOp.GE;
            case "EQ" -> BinaryOp.EQ;
            case "NE" -> BinaryOp.NE;
            case "LE" -> BinaryOp.LE;
            case "LT" -> BinaryOp.LT;
            default -> null;
        };
    }

    private static Expression negate(boolean n, Expression e, Span s) {
        return n ? new Expression.Unary(Expression.UnaryOp.NOT, e, s) : e;
    }

    private static Statement assign(Expression target, Expression value, Span s) {
        return new Statement.Assignment(target, Statement.AssignOp.ASSIGN, value, s);
    }

    private static Statement guard(Expression cond, Statement s, Span span) {
        return new Statement.If(List.of(new Statement.Branch(cond, List.of(s), span)), null, span);
    }

    private static Expression bool(boolean v, Span s) {
        return new Expression.Literal(Expression.LiteralKind.BOOL, v ? "TRUE" : "FALSE", null, s);
    }

    private static Argument positional(Expression e) {
        return new Argument(ArgumentKind.POSITIONAL, null, false, e, e.span());
    }

    /** Parenthesis depth of {@code code[s, e)} (outside strings); negative when it closes more than it opens. */
    private static int depth(String code, int s, int e) {
        int d = 0;
        int min = 0;
        boolean quoted = false;
        for (int i = s; i < e; i++) {
            char c = code.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                d++;
            } else if (!quoted && c == ')') {
                d--;
                min = Math.min(min, d);
            }
        }
        return min < 0 ? min : d;
    }

    /** Comments replaced by spaces (newlines kept), so offsets stay those of the text. */
    public static String blankComments(String s) {
        StringBuilder b = new StringBuilder(s);
        int i = 0;
        boolean quoted = false;
        while (i < b.length()) {
            char c = b.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                i++;
            } else if (!quoted && c == '(' && i + 1 < b.length() && b.charAt(i + 1) == '*') {
                int end = s.indexOf("*)", i + 2);
                end = end < 0 ? b.length() : end + 2;
                blank(b, i, end);
                i = end;
            } else if (!quoted && c == '/' && i + 1 < b.length() && b.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                end = end < 0 ? b.length() : end;
                blank(b, i, end);
                i = end;
            } else {
                i++;
            }
        }
        return b.toString();
    }

    private static void blank(StringBuilder b, int s, int e) {
        for (int i = s; i < e; i++) {
            if (b.charAt(i) != '\n' && b.charAt(i) != '\r') {
                b.setCharAt(i, ' ');
            }
        }
    }

    private void syntax(String message, Span span) {
        sink.report(Diagnostic.warning(IL_SYNTAX, message, span));
    }
}
