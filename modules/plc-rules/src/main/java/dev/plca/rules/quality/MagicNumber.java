package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.Statements;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PLCA-Q014: a decimal numeric literal in logic other than 0, 1, -1 and the {@code allowed} values. Not reported: CASE
 * labels, array indexes, FOR bounds, typed and based literals ({@code T#5S}, {@code 16#FF}, {@code INT#3}), and code
 * generated from graphical bodies. Opt-in: whether a number is "magic" is a team convention.
 */
public final class MagicNumber implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q014";
    }

    @Override
    public void check(PouContext ctx) {
        Set<String> allowed = new HashSet<>(List.of("0", "1", "-1", "0.0", "1.0", "-1.0"));
        for (String v : ctx.listParam("allowed")) {
            allowed.add(normal(v));
            allowed.add(normal(v.contains(".") ? v : v + ".0")); // 10 allows 10.0 too
        }
        Set<Expression> skipped = Collections.newSetFromMap(new IdentityHashMap<>());
        textual(ctx.body(), s -> {
            switch (s) {
                case Statement.Case c ->
                    c.branches()
                            .forEach(b -> b.labels().forEach(l -> {
                                Walk.deep(l.low(), skipped::add);
                                Walk.deep(l.high(), skipped::add);
                            }));
                case Statement.For f -> {
                    Walk.deep(f.from(), skipped::add);
                    Walk.deep(f.to(), skipped::add);
                    Walk.deep(f.by(), skipped::add);
                }
                default -> {}
            }
            Walk.ownExpressions(
                    s,
                    root -> Walk.deep(root, e -> {
                        if (e instanceof Expression.Index i) {
                            i.indices().forEach(x -> Walk.deep(x, skipped::add));
                        }
                    }));
            Walk.ownExpressions(
                    s,
                    root -> Walk.deep(root, e -> {
                        if (skipped.contains(e)) {
                            return;
                        }
                        Expression.Literal lit = null;
                        boolean negative = false;
                        if (e instanceof Expression.Unary u
                                && u.op() == Expression.UnaryOp.NEGATE
                                && u.operand() instanceof Expression.Literal l) {
                            lit = l;
                            negative = true;
                            skipped.add(l);
                        } else if (e instanceof Expression.Literal l) {
                            lit = l;
                        }
                        if (lit == null
                                || lit.typePrefix() != null
                                || lit.text().contains("#")
                                || !(lit.kind() == Expression.LiteralKind.INTEGER
                                        || lit.kind() == Expression.LiteralKind.REAL)) {
                            return;
                        }
                        String value = (negative ? "-" : "") + normal(lit.text());
                        if (!allowed.contains(value)) {
                            ctx.report(
                                    e.span(),
                                    "Magic number " + value
                                            + "; give it a name (a VAR CONSTANT or a parameter) that says what"
                                            + " it means");
                        }
                    }));
        });
    }

    /** Every statement outside rungs, networks and charts, pre-order. */
    private static void textual(List<Statement> body, java.util.function.Consumer<Statement> action) {
        for (Statement s : body) {
            if (s instanceof Statement.Rung || s instanceof Statement.Network || s instanceof Statement.Chart) {
                continue;
            }
            action.accept(s);
            Statements.forEachBody(s, b -> textual(b, action));
        }
    }

    private static String normal(String v) {
        String t = v.strip().replace("_", "").toLowerCase(Locale.ROOT);
        try {
            if (t.contains(".") || t.contains("e")) {
                double d = Double.parseDouble(t);
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? (long) d + ".0" : Double.toString(d);
            }
            return Long.toString(Long.parseLong(t));
        } catch (NumberFormatException e) {
            return t;
        }
    }
}
