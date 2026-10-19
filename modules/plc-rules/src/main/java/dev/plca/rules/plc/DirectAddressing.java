package dev.plca.rules.plc;

import dev.plca.ast.Expression;
import dev.plca.core.Dialect;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;

/**
 * PLCA-P002 (PLCopen N1): a direct address ({@code %IX0.1}, {@code %QW4}, {@code %MD10}) used in code. Mapping a
 * symbolic variable with {@code AT %…} in its declaration is the compliant form and is not reported. Siemens: also
 * {@code %DB1.DBX0.0}, classic {@code DB10.DBW2} and ANY pointers {@code P#DB1.DBX0.0 BYTE 10}; the symbolic form there
 * is a PLC tag or a DB member.
 */
public final class DirectAddressing implements PouRule {

    @Override
    public String id() {
        return "PLCA-P002";
    }

    @Override
    public void check(PouContext ctx) {
        boolean siemens = ctx.pou().dialect() == Dialect.SIEMENS;
        ctx.forEachExpression(e -> {
            if (e instanceof Expression.DirectAddress a) {
                ctx.report(
                        a.span(),
                        siemens
                                ? "Absolute address " + a.text()
                                        + " used in code; use a PLC tag or a data block member instead"
                                : "Direct address " + a.text() + " used in code; declare a variable AT " + a.text()
                                        + " and use its name");
            }
        });
    }
}
