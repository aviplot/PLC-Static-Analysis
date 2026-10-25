package dev.plca.ast;

import java.util.List;

/**
 * Prints expressions and statements as Structured Text, with parentheses only where precedence needs them. Used for
 * ladder rung text, lowering goldens and messages; not a formatter for user code (comments and layout are not kept).
 */
public final class StText {

    private StText() {}

    public static String expression(Expression e) {
        StringBuilder sb = new StringBuilder();
        expr(e, sb);
        return sb.toString();
    }

    /** Statements, one per line (compound statements indented by two blanks per level). */
    public static String statements(List<Statement> body) {
        StringBuilder sb = new StringBuilder();
        body(body, 0, sb);
        return sb.toString();
    }

    // ---- statements -----------------------------------------------------------------------------

    private static void body(List<Statement> body, int depth, StringBuilder sb) {
        if (body != null) {
            for (Statement s : body) {
                statement(s, depth, sb);
            }
        }
    }

    private static void statement(Statement s, int depth, StringBuilder sb) {
        String in = "  ".repeat(depth);
        switch (s) {
            case Statement.Assignment a -> {
                sb.append(in);
                expr(a.target(), sb);
                sb.append(' ').append(assignOp(a.op())).append(' ');
                expr(a.value(), sb);
                sb.append(";\n");
            }
            case Statement.CallStatement c -> {
                sb.append(in);
                expr(c.call(), sb);
                sb.append(";\n");
            }
            case Statement.If i -> {
                for (int k = 0; k < i.branches().size(); k++) {
                    Statement.Branch b = i.branches().get(k);
                    sb.append(in).append(k == 0 ? "IF " : "ELSIF ");
                    expr(b.condition(), sb);
                    sb.append(" THEN\n");
                    body(b.body(), depth + 1, sb);
                }
                if (i.elseBody() != null) {
                    sb.append(in).append("ELSE\n");
                    body(i.elseBody(), depth + 1, sb);
                }
                sb.append(in).append("END_IF\n");
            }
            case Statement.Case c -> {
                sb.append(in).append("CASE ");
                expr(c.selector(), sb);
                sb.append(" OF\n");
                for (Statement.CaseBranch b : c.branches()) {
                    sb.append(in).append("  ");
                    for (int k = 0; k < b.labels().size(); k++) {
                        Statement.CaseLabel l = b.labels().get(k);
                        sb.append(k > 0 ? ", " : "");
                        expr(l.low(), sb);
                        if (l.high() != null) {
                            sb.append("..");
                            expr(l.high(), sb);
                        }
                    }
                    sb.append(":\n");
                    body(b.body(), depth + 2, sb);
                }
                if (c.elseBody() != null) {
                    sb.append(in).append("ELSE\n");
                    body(c.elseBody(), depth + 1, sb);
                }
                sb.append(in).append("END_CASE\n");
            }
            case Statement.For f -> {
                sb.append(in).append("FOR ");
                expr(f.variable(), sb);
                sb.append(" := ");
                expr(f.from(), sb);
                sb.append(" TO ");
                expr(f.to(), sb);
                if (f.by() != null) {
                    sb.append(" BY ");
                    expr(f.by(), sb);
                }
                sb.append(" DO\n");
                body(f.body(), depth + 1, sb);
                sb.append(in).append("END_FOR\n");
            }
            case Statement.While w -> {
                sb.append(in).append("WHILE ");
                expr(w.condition(), sb);
                sb.append(" DO\n");
                body(w.body(), depth + 1, sb);
                sb.append(in).append("END_WHILE\n");
            }
            case Statement.Repeat r -> {
                sb.append(in).append("REPEAT\n");
                body(r.body(), depth + 1, sb);
                sb.append(in).append("UNTIL ");
                expr(r.condition(), sb);
                sb.append("\n").append(in).append("END_REPEAT\n");
            }
            case Statement.Exit e -> sb.append(in).append("EXIT;\n");
            case Statement.Continue c -> sb.append(in).append("CONTINUE;\n");
            case Statement.Return r -> {
                sb.append(in).append("RETURN");
                if (r.value() != null) {
                    sb.append(' ');
                    expr(r.value(), sb);
                }
                sb.append(";\n");
            }
            case Statement.Jump j ->
                sb.append(in).append("JMP ").append(j.label()).append(";\n");
            case Statement.Label l -> sb.append(in).append(l.name()).append(":\n");
            case Statement.Try t -> {
                sb.append(in).append("__TRY\n");
                body(t.body(), depth + 1, sb);
                if (t.catchBody() != null) {
                    sb.append(in).append("__CATCH\n");
                    body(t.catchBody(), depth + 1, sb);
                }
                if (t.finallyBody() != null) {
                    sb.append(in).append("__FINALLY\n");
                    body(t.finallyBody(), depth + 1, sb);
                }
                sb.append(in).append("__ENDTRY\n");
            }
            case Statement.Region r -> {
                sb.append(in).append("REGION ").append(r.name()).append('\n');
                body(r.body(), depth + 1, sb);
                sb.append(in).append("END_REGION\n");
            }
            case Statement.Rung r -> {
                sb.append(in).append("// rung ").append(r.number());
                if (r.ladder() != null) {
                    sb.append(": ").append(r.ladder().text());
                }
                sb.append('\n');
                body(r.body(), depth, sb);
            }
            case Statement.Network n -> {
                sb.append(in)
                        .append("// network ")
                        .append(n.number())
                        .append(" (")
                        .append(n.network().summary())
                        .append(")\n");
                body(n.body(), depth, sb);
            }
            case Statement.Chart c -> {
                sb.append(in).append("// chart (").append(c.chart().summary()).append(")\n");
                body(c.body(), depth, sb);
            }
            case Statement.Empty e -> sb.append(in).append(";\n");
            case Statement.Invalid i -> sb.append(in).append("<invalid>;\n");
        }
    }

    private static String assignOp(Statement.AssignOp op) {
        return switch (op) {
            case ASSIGN -> ":=";
            case ATTEMPT -> "?=";
            case SET -> "S=";
            case RESET -> "R=";
            case REF -> "REF=";
            case NON_RETENTIVE -> "[:=]";
            case ADD -> "+=";
            case SUB -> "-=";
            case MUL -> "*=";
            case DIV -> "/=";
        };
    }

    // ---- expressions ----------------------------------------------------------------------------

    /** Binding strength, higher binds tighter (StParser.g4 order). */
    private static int precedence(Expression e) {
        return switch (e) {
            case Expression.Binary b ->
                switch (b.op()) {
                    case POWER -> 9;
                    case MUL, DIV, MOD -> 7;
                    case ADD, SUB -> 6;
                    case LT, GT, LE, GE -> 5;
                    case EQ, NE -> 4;
                    case AND, AND_THEN -> 3;
                    case XOR -> 2;
                    case OR, OR_ELSE -> 1;
                };
            case Expression.Unary u -> 8;
            default -> 10;
        };
    }

    private static void operand(Expression e, int min, StringBuilder sb) {
        boolean parens = precedence(e) < min;
        sb.append(parens ? "(" : "");
        expr(e, sb);
        sb.append(parens ? ")" : "");
    }

    private static void expr(Expression e, StringBuilder sb) {
        switch (e) {
            case null -> sb.append("<missing>");
            case Expression.Literal l -> {
                if (l.typePrefix() != null) {
                    sb.append(l.typePrefix()).append('#');
                }
                sb.append(l.text());
            }
            case Expression.NameRef n -> sb.append(n.globalScope() ? "." : "").append(n.name());
            case Expression.TypeOperand t -> sb.append("<type>");
            case Expression.ThisRef t -> sb.append("THIS");
            case Expression.SuperRef s -> sb.append("SUPER");
            case Expression.DirectAddress d -> sb.append(d.text());
            case Expression.MemberAccess m -> {
                operand(m.target(), 10, sb);
                sb.append('.').append(m.member());
            }
            case Expression.BitAccess b -> {
                operand(b.target(), 10, sb);
                sb.append('.').append(b.bit());
            }
            case Expression.IndirectBit b -> {
                operand(b.target(), 10, sb);
                sb.append(".[");
                expr(b.bit(), sb);
                sb.append(']');
            }
            case Expression.Deref d -> {
                operand(d.target(), 10, sb);
                sb.append('^');
            }
            case Expression.Slice s -> {
                operand(s.target(), 10, sb);
                sb.append(".%")
                        .append(
                                switch (s.width()) {
                                    case BIT -> 'X';
                                    case BYTE -> 'B';
                                    case WORD -> 'W';
                                    case DWORD -> 'D';
                                })
                        .append(s.index());
            }
            case Expression.Index i -> {
                operand(i.target(), 10, sb);
                sb.append('[');
                for (int k = 0; k < i.indices().size(); k++) {
                    sb.append(k > 0 ? "," : "");
                    expr(i.indices().get(k), sb);
                }
                sb.append(']');
            }
            case Expression.Call c -> {
                operand(c.callee(), 10, sb);
                sb.append('(');
                for (int k = 0; k < c.arguments().size(); k++) {
                    Expression.Argument a = c.arguments().get(k);
                    sb.append(k > 0 ? ", " : "");
                    switch (a.kind()) {
                        case POSITIONAL -> {
                            if (a.value() == null) {
                                sb.append('?'); // an operand left out (ladder `?` or empty operand)
                            } else {
                                expr(a.value(), sb);
                            }
                        }
                        case INPUT -> {
                            sb.append(a.name()).append(" := ");
                            if (a.value() != null) {
                                expr(a.value(), sb);
                            }
                        }
                        case OUTPUT -> {
                            sb.append(a.negated() ? "NOT " : "")
                                    .append(a.name())
                                    .append(" =>");
                            if (a.value() != null) {
                                sb.append(' ');
                                expr(a.value(), sb);
                            }
                        }
                    }
                }
                sb.append(')');
            }
            case Expression.Unary u -> {
                sb.append(
                        switch (u.op()) {
                            case NEGATE -> "-";
                            case PLUS -> "+";
                            case NOT -> "NOT ";
                        });
                operand(u.operand(), 8, sb);
            }
            case Expression.Assign a -> {
                // always parenthesised: valid ExST in every position
                sb.append('(');
                expr(a.target(), sb);
                sb.append(" := ");
                expr(a.value(), sb);
                sb.append(')');
            }
            case Expression.Binary b -> {
                int p = precedence(b);
                boolean rightAssoc = b.op() == Expression.BinaryOp.POWER;
                operand(b.left(), rightAssoc ? p + 1 : p, sb);
                sb.append(' ').append(binaryOp(b.op())).append(' ');
                operand(b.right(), rightAssoc ? p : p + 1, sb);
            }
            case Expression.Invalid i -> sb.append("<invalid>");
        }
    }

    private static String binaryOp(Expression.BinaryOp op) {
        return switch (op) {
            case POWER -> "**";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "MOD";
            case ADD -> "+";
            case SUB -> "-";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case EQ -> "=";
            case NE -> "<>";
            case AND -> "AND";
            case AND_THEN -> "AND_THEN";
            case XOR -> "XOR";
            case OR -> "OR";
            case OR_ELSE -> "OR_ELSE";
        };
    }
}
