package dev.plca.rules.plc;

import dev.plca.ast.Expression;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;

/**
 * PLCA-P002 (PLCopen N1): a direct address ({@code %IX0.1}, {@code %QW4}, {@code %MD10}) used in code. Mapping a
 * symbolic variable with {@code AT %…} in its declaration is the compliant form and is not reported.
 */
public final class DirectAddressing implements PouRule {

    @Override
    public String id() {
        return "PLCA-P002";
    }

    @Override
    public void check(PouContext ctx) {
        ctx.forEachExpression(e -> {
            if (e instanceof Expression.DirectAddress a) {
                ctx.report(
                        a.span(),
                        "Direct address " + a.text() + " used in code; declare a variable AT " + a.text()
                                + " and use its name");
            }
        });
    }
}
