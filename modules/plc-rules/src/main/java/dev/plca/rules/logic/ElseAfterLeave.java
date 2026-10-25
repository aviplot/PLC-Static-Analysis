package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import java.util.List;

/**
 * PLCA-L017: every branch before ELSE ends with RETURN, EXIT, CONTINUE or JMP, so the ELSE is not needed: its
 * statements can follow END_IF one level less deep.
 */
public final class ElseAfterLeave implements PouRule {

    @Override
    public String id() {
        return "PLCA-L017";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            if (!(s instanceof Statement.If i) || Structured.isEmpty(i.elseBody())) {
                return;
            }
            for (Statement.Branch b : i.branches()) {
                List<Statement> body = Structured.real(b.body());
                if (body.isEmpty() || !Structured.leaves(body.getLast())) {
                    return;
                }
            }
            ctx.report(
                    Structured.elseSpan(ctx, i),
                    "The branches before ELSE always leave (RETURN, EXIT, CONTINUE or JMP); the ELSE is not needed,"
                            + " its statements can follow END_IF");
        });
    }
}
