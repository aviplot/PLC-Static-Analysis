package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;

/**
 * PLCA-L014: {@code x := x;} changes nothing; usually another variable was meant. Also in ladder, where {@code MOV(A,
 * A)} or {@code XIC(A) OTE(A)} lower to the same assignment. Calls on either side are left alone (they may have
 * effects).
 */
public final class SelfAssignment implements PouRule {

    @Override
    public String id() {
        return "PLCA-L014";
    }

    @Override
    public void check(PouContext ctx) {
        ctx.forEachStatement(s -> {
            if (s instanceof Statement.Assignment a
                    && (a.op() == Statement.AssignOp.ASSIGN || a.op() == Statement.AssignOp.NON_RETENTIVE)
                    && !Normalizer.hasCall(a.target())
                    && !Normalizer.hasCall(a.value())
                    && Structural.equal(a.target(), a.value())) {
                ctx.report(
                        a.span(), "'" + ctx.text(a.target()).trim() + "' is assigned to itself; this changes nothing");
            }
        });
    }
}
