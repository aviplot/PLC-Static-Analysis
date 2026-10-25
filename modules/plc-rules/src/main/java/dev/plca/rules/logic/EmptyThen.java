package dev.plca.rules.logic;

import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.rules.Spans;

/**
 * PLCA-L018: {@code IF c THEN ELSE … END_IF}: negate the condition and drop the ELSE. Not reported when THEN holds a
 * comment (it says why the case does nothing).
 */
public final class EmptyThen implements PouRule {

    @Override
    public String id() {
        return "PLCA-L018";
    }

    @Override
    public void check(PouContext ctx) {
        Structured.forEach(ctx.body(), s -> {
            if (s instanceof Statement.If i
                    && i.branches().size() == 1
                    && Structured.isEmpty(i.branches().getFirst().body())
                    && !Structured.isEmpty(i.elseBody())
                    && !Structured.commentBetween(
                            ctx,
                            i,
                            i.branches().getFirst().condition().span().end(),
                            Structured.elseSpan(ctx, i).start())) {
                ctx.report(Spans.header(i), "Empty THEN with an ELSE; negate the condition and drop the ELSE");
            }
        });
    }
}
