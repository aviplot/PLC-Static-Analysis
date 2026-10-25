package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import java.util.List;

/** PLCA-L015: an ELSE whose only statement is an IF; ELSIF says the same one level less deep. */
public final class ElseOnlyIf implements PouRule {

    @Override
    public String id() {
        return "PLCA-L015";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            if (s instanceof Statement.If i && i.elseBody() != null) {
                List<Statement> body = Structured.real(i.elseBody());
                if (body.size() == 1
                        && body.getFirst() instanceof Statement.If
                        && !Structured.commentBetween(
                                ctx,
                                i,
                                Structured.elseSpan(ctx, i).end(),
                                body.getFirst().span().start())) {
                    ctx.report(Structured.elseSpan(ctx, i), "ELSE holds only an IF; write ELSIF");
                }
            }
        });
    }
}
