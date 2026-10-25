package dev.plca.rules.logic;

import dev.plca.core.diag.Severity;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;

/**
 * The rules that {@link LogicAnalysis} decides: PLCA-L001 (contradictory nested condition), L002 (unreachable ELSIF),
 * L003 (unreachable ELSE), L004 (self-contradictory condition), L005 (duplicate condition in a chain), L006 (duplicate
 * or overlapping CASE label), L007 (tautology), L008 (condition always true where it is), L009 (ELSIF that should be
 * ELSE), L010 (redundant part of a condition), L011 (chain that misses a boundary) and L012 (comparison outside the
 * operand's range). The analysis runs once per POU; each rule reports its share. A proof that assumes another task does
 * not change a global in between is reported at {@code info} at most.
 */
public final class ConditionRule implements PouRule {

    private final String id;

    public ConditionRule(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void check(PouContext ctx) {
        for (LogicAnalysis.Issue i : LogicAnalysis.of(ctx).issues(id)) {
            ctx.report(i.span(), i.message(), i.race() ? Severity.INFO : null, i.related());
        }
    }
}
