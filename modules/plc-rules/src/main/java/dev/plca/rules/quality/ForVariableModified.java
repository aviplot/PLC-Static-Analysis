package dev.plca.rules.quality;

import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Access;
import dev.plca.semantic.Statements;
import dev.plca.semantic.VariableSymbol;

/**
 * PLCA-Q005 (PLCopen L12): the control variable of a FOR loop is written inside the loop body — by assignment, as an
 * output or VAR_IN_OUT argument, or as the variable of a nested FOR.
 */
public final class ForVariableModified implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q005";
    }

    @Override
    public void check(PouContext ctx) {
        ctx.forEachStatement(s -> {
            if (!(s instanceof Statement.For f)
                    || !(ctx.model().symbol(f.variable()).orElse(null) instanceof VariableSymbol v)) {
                return;
            }
            Statements.forEachDeep(f.body(), inner -> {
                for (Access a : ctx.model().effects(inner).accesses()) {
                    if (a.variable() == v && a.path().isEmpty() && a.isWrite()) {
                        ctx.report(
                                a.span(),
                                "FOR loop variable '" + v.name() + "' is modified inside the loop body",
                                new Diagnostic.Related(f.variable().span(), "loop header"));
                    }
                }
            });
        });
    }
}
