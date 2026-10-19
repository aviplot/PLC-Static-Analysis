package dev.plca.rules.plc;

import dev.plca.ast.Ladder;
import dev.plca.ast.Statement;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import java.util.List;

/**
 * PLCA-P006 (PLCopen CP19): a jump — ST {@code JMP}, SCL {@code GOTO}, a PLCopen LD/FBD jump, or a Rockwell ladder
 * {@code JMP}. The label is not reported separately: one finding per jump.
 */
public final class JumpUsage implements PouRule {

    @Override
    public String id() {
        return "PLCA-P006";
    }

    @Override
    public void check(PouContext ctx) {
        ctx.forEachStatement(s -> {
            switch (s) {
                case Statement.Jump j ->
                    ctx.report(j.span(), "Jump to label " + j.label().name() + "; use IF, CASE or loops instead");
                case Statement.Rung r
                when r.ladder() != null -> ladder(ctx, r.ladder().elements());
                default -> {}
            }
        });
    }

    private static void ladder(PouContext ctx, List<Ladder.Element> elements) {
        for (Ladder.Element e : elements) {
            switch (e) {
                case Ladder.Instruction i
                when i.mnemonic().name().equalsIgnoreCase("JMP") ->
                    ctx.report(
                            i.span(),
                            "Ladder jump"
                                    + (i.operands().isEmpty()
                                            ? ""
                                            : " to label "
                                                    + ctx.text(i.operands().getFirst()))
                                    + "; skipped rungs keep their outputs frozen, use conditions instead");
                case Ladder.Branch b -> b.legs().forEach(l -> ladder(ctx, l.elements()));
                default -> {}
            }
        }
    }
}
