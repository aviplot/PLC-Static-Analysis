package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.core.source.Span;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Constant;
import dev.plca.semantic.EnumValueSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** PLCA-Q002: CASE without ELSE, unless the labels cover every value of the selector's enumeration. */
public final class CaseWithoutElse implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q002";
    }

    @Override
    public void check(PouContext ctx) {
        boolean allowFullCoverage = ctx.boolParam("allowFullEnumCoverage");
        ctx.forEachStatement(s -> {
            if (!(s instanceof Statement.Case c) || c.elseBody() != null) {
                return;
            }
            String message = "CASE statement has no ELSE branch";
            if (ctx.model().type(c.selector()).resolved() instanceof Type.Enum e) {
                List<String> missing = missingValues(ctx.model(), c, e);
                if (missing.isEmpty() && allowFullCoverage) {
                    return;
                }
                if (!missing.isEmpty()) {
                    message += " and does not handle " + e.display() + " value" + (missing.size() == 1 ? " " : "s ")
                            + String.join(", ", missing);
                }
            }
            // the CASE … OF header, not the whole statement
            ctx.report(
                    Span.of(
                            c.span().textId(),
                            c.span().start(),
                            c.selector().span().end()),
                    message);
        });
    }

    private static List<String> missingValues(SemanticModel model, Statement.Case c, Type.Enum e) {
        Set<Long> covered = new HashSet<>();
        List<long[]> ranges = new ArrayList<>();
        for (Statement.CaseBranch b : c.branches()) {
            for (Statement.CaseLabel l : b.labels()) {
                Optional<Long> low = value(model, l.low());
                if (l.high() == null) {
                    low.ifPresent(covered::add);
                } else {
                    Optional<Long> high = value(model, l.high());
                    if (low.isPresent() && high.isPresent()) {
                        ranges.add(new long[] {low.get(), high.get()});
                    }
                }
            }
        }
        return e.symbol().values().values().stream()
                .filter(v -> !covered.contains(v.value())
                        && ranges.stream().noneMatch(r -> v.value() >= r[0] && v.value() <= r[1]))
                .map(EnumValueSymbol::name)
                .toList();
    }

    private static Optional<Long> value(SemanticModel model, Expression label) {
        if (model.symbol(label).orElse(null) instanceof EnumValueSymbol v) {
            return Optional.of(v.value());
        }
        return model.constant(label).filter(Constant.Int.class::isInstance).map(k -> ((Constant.Int) k).value());
    }
}
