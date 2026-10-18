package dev.plca.rules.quality;

import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.Access;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.Type;
import dev.plca.semantic.VariableSymbol;

/**
 * PLCA-Q006 (PLCopen CP17): a VAR_INPUT written inside its own POU (or a method/action of it). Writes through a pointer
 * or reference input change the caller's data, not the input, and are not reported.
 */
public final class InputWrite implements PouRule {

    @Override
    public String id() {
        return "PLCA-Q006";
    }

    @Override
    public void check(PouContext ctx) {
        PouSymbol pou = ctx.pou();
        ctx.forEachStatement(s -> {
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
