package dev.plca.rules.quality;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.core.Dialect;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Access;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;

/**
 * PLCA-Q006 (PLCopen CP17): a VAR_INPUT written inside its own POU (or a method/action of it). Writes through a pointer
 * or reference input change the caller's data, not the input, and are not reported.
 *
 * <p>Rockwell AOIs: writing a constant to an input is a documented idiom — clearing a command an HMI or program set
 * ({@code OTU(PCmd_Start)}, the PlantPAx convention) or replacing an invalid setting by a default ({@code LEQ(Rate,0)
 * MOV(1000,Rate)}) — and is not reported; other writes are.
 */
public final class InputWrite implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q006";
    }

    @Override
    public void check(PouContext ctx) {
        PouSymbol pou = ctx.pou();
        boolean rockwell = pou.dialect() == Dialect.ROCKWELL;
        ctx.forEachStatement(s -> {
            if (rockwell && s instanceof Statement.Assignment as && as.value() instanceof Expression.Literal) {
                return;
            }
            for (Access a : ctx.model().effects(s).accesses()) {
                if (a.kind() != Access.Kind.WRITE || a.path().contains("^")) {
                    continue;
                }
                VariableSymbol v = a.variable();
                if (v.section() != VarBlock.Section.INPUT
                        || !(v.owner() instanceof PouSymbol owner)
                        || !sees(pou, owner)
                        || v.type().resolved() instanceof Type.Pointer p && p.kind() == TypeRef.PointerKind.REFERENCE) {
                    continue;
                }
                ctx.report(
                        a.span(),
                        "Input '" + v.name() + "' of " + owner.qualifiedName()
                                + " is written inside the POU; inputs should only be read (copy it to a local)");
            }
        });
    }

    /** True if {@code pou} is {@code owner}, a member of it, or of a POU extending it. */
    private static boolean sees(PouSymbol pou, PouSymbol owner) {
        if (pou == owner) {
            return true;
        }
        for (PouSymbol p = pou.instanceOwner(); p != null; p = p.extendsPou()) {
            if (p == owner) {
                return true;
            }
        }
        return false;
    }
}
