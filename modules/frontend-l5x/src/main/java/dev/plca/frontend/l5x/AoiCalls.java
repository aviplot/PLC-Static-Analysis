package dev.plca.frontend.l5x;

import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Statement;
import dev.plca.core.source.Span;
import dev.plca.frontend.l5x.RungLowering.AoiSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rewrites AOI calls in ST routines, {@code Aoi(tag, a, b);}, into the FB call they are, {@code tag(In := a, Out =>
 * b);}, exactly as ladder lowering does, so the semantic model handles both the same way.
 */
final class AoiCalls {

    private AoiCalls() {}

    static List<Statement> rewrite(List<Statement> body, Map<String, AoiSignature> aois) {
        if (aois.isEmpty() || body == null) {
            return body;
        }
        List<Statement> out = new ArrayList<>(body.size());
        for (Statement s : body) {
            out.add(statement(s, aois));
        }
        return out;
    }

    private static Statement statement(Statement s, Map<String, AoiSignature> aois) {
        return switch (s) {
            case Statement.CallStatement c -> {
                Expression call = call(c.call(), aois);
                yield call == c.call() ? c : new Statement.CallStatement(call, c.span());
            }
            case Statement.If i -> {
                List<Statement.Branch> branches = new ArrayList<>();
                for (Statement.Branch b : i.branches()) {
                    branches.add(new Statement.Branch(b.condition(), rewrite(b.body(), aois), b.span()));
                }
                yield new Statement.If(branches, rewrite(i.elseBody(), aois), i.span());
            }
            case Statement.Case k -> {
                List<Statement.CaseBranch> branches = new ArrayList<>();
                for (Statement.CaseBranch b : k.branches()) {
                    branches.add(new Statement.CaseBranch(b.labels(), rewrite(b.body(), aois), b.span()));
                }
                yield new Statement.Case(k.selector(), branches, rewrite(k.elseBody(), aois), k.span());
            }
            case Statement.For f ->
                new Statement.For(f.variable(), f.from(), f.to(), f.by(), rewrite(f.body(), aois), f.span());
            case Statement.While w -> new Statement.While(w.condition(), rewrite(w.body(), aois), w.span());
            case Statement.Repeat r -> new Statement.Repeat(rewrite(r.body(), aois), r.condition(), r.span());
            default -> s;
        };
    }

    private static Expression call(Expression e, Map<String, AoiSignature> aois) {
        if (!(e instanceof Expression.Call c)
                || !(c.callee() instanceof Expression.NameRef n)
                || n.name().form() != Identifier.Form.PLAIN
                || c.arguments().isEmpty()
                || c.arguments().stream().anyMatch(a -> a.kind() != Expression.ArgumentKind.POSITIONAL)) {
            return e;
        }
        AoiSignature aoi = aois.get(n.name().name().toUpperCase(Locale.ROOT));
        if (aoi == null) {
            return e;
        }
        List<Expression> values = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        for (Expression.Argument a : c.arguments()) {
            values.add(a.value());
            spans.add(a.span());
        }
        return RungLowering.aoiCall(aoi, values, spans, null, n.name(), c.span());
    }
}
