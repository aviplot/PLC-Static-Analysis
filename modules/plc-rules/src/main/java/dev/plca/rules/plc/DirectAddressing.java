package dev.plca.rules.plc;

import dev.plca.ast.Declaration;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Ladder;
import dev.plca.ast.Statement;
import dev.plca.core.Dialect;
import dev.plca.rules.PouContext;
import dev.plca.rules.PouRule;
import dev.plca.semantic.IoTagSymbol;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * PLCA-P002 (PLCopen N1): a direct address ({@code %IX0.1}, {@code %QW4}, {@code %MD10}) used in code. Mapping a
 * symbolic variable with {@code AT %…} in its declaration is the compliant form and is not reported. Siemens: also
 * {@code %DB1.DBX0.0}, classic {@code DB10.DBW2} and ANY pointers {@code P#DB1.DBX0.0 BYTE 10}; the symbolic form there
 * is a PLC tag or a DB member.
 *
 * <p>Rockwell has no direct addresses; the equivalent is logic on raw module tags ({@code Local:1:I.Data.5}) instead of
 * alias or mapped tags. Those are reported, except in routines that only map I/O (every rung a contact driving a coil,
 * a MOV/COP, or every ST statement a plain assignment): the usual "map I/O in one place" convention. A whole module tag
 * passed to a call ({@code PF755_Drive(Pump, Pump:I, Pump:O, …)}) is the device-object form Rockwell recommends and is
 * not reported either. Controller status flags ({@code S:FS}) are no I/O.
 */
public final class DirectAddressing implements PouRule {

    @Override
    public String id() {
        return "PLCA-P002";
    }

    @Override
    public void check(PouContext ctx) {
        Dialect dialect = ctx.pou().dialect();
        if (dialect == Dialect.ROCKWELL) {
            rockwell(ctx);
            return;
        }
        boolean siemens = dialect == Dialect.SIEMENS;
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

    private static void rockwell(PouContext ctx) {
        Declaration.Pou d = ctx.pou().declaration();
        if (d == null || d.body() == null || mappingOnly(d.body())) {
            return;
        }
        // whole module tags handed to a device AOI or instruction: the recommended device-object form
        Set<Expression> wholeArguments = Collections.newSetFromMap(new IdentityHashMap<>());
        ctx.forEachExpression(e -> {
            if (e instanceof Expression.Call c) {
                c.arguments().forEach(a -> {
                    if (a.value() instanceof Expression.NameRef) {
                        wholeArguments.add(a.value());
                    }
                });
            }
        });
        ctx.forEachExpression(e -> {
            if (e instanceof Expression.NameRef n
                    && !wholeArguments.contains(e)
                    && n.name().form() == Identifier.Form.PLAIN
                    && ctx.model().symbol(e).orElse(null) instanceof IoTagSymbol io
                    && !io.status()) {
                ctx.report(
                        n.span(),
                        "Module I/O tag " + io.name() + " used in logic; map it to an alias or program tag in one I/O"
                                + " routine and use that name");
            }
        });
    }

    /** A body that only copies values: I/O mapping, where module tags belong. */
    static boolean mappingOnly(List<Statement> body) {
        boolean any = false;
        for (Statement s : body) {
            switch (s) {
                case Statement.Rung r -> {
                    if (r.ladder() == null) {
                        continue;
                    }
                    if (!mappingRung(r.ladder().elements())) {
                        return false;
                    }
                    any |= !r.ladder().elements().isEmpty();
                }
                case Statement.Assignment a -> {
                    if (!(a.value() instanceof Expression.NameRef
                            || a.value() instanceof Expression.MemberAccess
                            || a.value() instanceof Expression.Index
                            || a.value() instanceof Expression.BitAccess
                            || a.value() instanceof Expression.Literal)) {
                        return false;
                    }
                    any = true;
                }
                case Statement.Empty e -> {}
                default -> {
                    return false;
                }
            }
        }
        return any;
    }

    /** {@code XIC(a)OTE(b)}, {@code XIO(a)OTE(b)}, {@code MOV(a,b)}, {@code COP(a,b,n)}, {@code CPS(a,b,n)}, NOP. */
    private static boolean mappingRung(List<Ladder.Element> elements) {
        if (elements.isEmpty()) {
            return true;
        }
        if (elements.size() == 1 && elements.getFirst() instanceof Ladder.Instruction i) {
            return i.mnemonic().is("MOV")
                    || i.mnemonic().is("COP")
                    || i.mnemonic().is("CPS")
                    || i.mnemonic().is("NOP");
        }
        return elements.size() == 2
                && elements.get(0) instanceof Ladder.Instruction contact
                && (contact.mnemonic().is("XIC") || contact.mnemonic().is("XIO"))
                && elements.get(1) instanceof Ladder.Instruction coil
                && coil.mnemonic().is("OTE");
    }
}
