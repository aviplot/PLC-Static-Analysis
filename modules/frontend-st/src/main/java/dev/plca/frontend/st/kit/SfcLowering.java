package dev.plca.frontend.st.kit;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Identifier;
import dev.plca.ast.Initializer;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.Sfc;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** SFC lowered to statements for analysis, shared by the PLCopen and L5X frontends. */
public final class SfcLowering {

    private SfcLowering() {}

    /**
     * The classic SFC-to-ST lowering ({@code docs/grammar/plcopen.md} §6): per step its actions under the step flag
     * {@code S.X} (stored actions with their own flag, pulse actions on the flag's edge), per transition {@code IF
     * from.X AND condition THEN from.X := FALSE; to.X := TRUE; END_IF}. {@code body}: an action's statements.
     */
    public static List<Statement> lower(Sfc chart, Function<Sfc.Action, List<Statement>> body) {
        List<Statement> out = new ArrayList<>();
        Map<String, List<Statement>> stored = new LinkedHashMap<>();
        Map<String, Span> storedSpans = new HashMap<>();
        for (Sfc.Step step : chart.steps()) {
            Expression active = flag(step.name(), "X");
            int index = 0;
            for (Sfc.Action a : step.actions()) {
                Span s = a.span();
                List<Statement> action = body.apply(a);
                index++;
                // a referenced action is one flag wherever it is stored; an inline one belongs to its step
                String key = a.reference() != null
                        ? a.reference().key()
                        : step.name().key() + "_" + index;
                switch (a.qualifier()) {
                    case "S", "SD", "DS", "SL" -> {
                        Expression f = storedFlag(key, s);
                        out.add(guard(active, assign(f, bool(true, s), s), s));
                        stored.putIfAbsent(key, action);
                        storedSpans.putIfAbsent(key, s);
                    }
                    case "R" -> out.add(guard(active, assign(storedFlag(key, s), bool(false, s), s), s));
                    case "P", "P1" -> out.add(guard(edge(false, active, s), action, s));
                    case "P0" -> out.add(guard(edge(true, active, s), action, s));
                    // N, none, and the timed L/D (timing not modelled: notes §9.3)
                    default -> out.add(guard(active, action, s));
                }
            }
        }
        stored.forEach((key, action) -> {
            Span s = storedSpans.get(key);
            out.add(guard(storedFlag(key, s), action, s));
        });
        for (Sfc.Transition t : chart.transitions()) {
            Span s = t.span();
            Expression cond = null;
            for (Identifier from : t.from()) {
                Expression x = flag(from, "X");
                cond = cond == null ? x : new Expression.Binary(BinaryOp.AND, cond, x, s);
            }
            Expression c = t.condition();
            cond = cond == null ? c : new Expression.Binary(BinaryOp.AND, cond, c, s);
            List<Statement> fire = new ArrayList<>();
            for (Identifier from : t.from()) {
                fire.add(assign(flag(from, "X"), bool(false, s), s));
            }
            for (Identifier to : t.to()) {
                fire.add(assign(flag(to, "X"), bool(true, s), s));
            }
            out.add(new Statement.If(List.of(new Statement.Branch(cond, fire, s)), null, s));
        }
        return out;
    }

    /** {@code R_TRIG(x)} / {@code F_TRIG(x)}: the edge of a step flag (pulse actions). */
    static Expression edge(boolean falling, Expression x, Span s) {
        Identifier id = new Identifier(falling ? "F_TRIG" : "R_TRIG", Identifier.Form.SYNTHETIC, s);
        return new Expression.Call(
                new Expression.NameRef(id, false, s),
                List.of(new Argument(ArgumentKind.POSITIONAL, null, false, x, x.span())),
                s);
    }

    private static Expression storedFlag(String key, Span s) {
        String name = "act_" + key.replaceAll("[^A-Za-z0-9_]", "_");
        return new Expression.NameRef(new Identifier(name, Identifier.Form.SYNTHETIC, s), false, s);
    }

    /** {@code S.X}: the step's implicit flag. */
    private static Expression flag(Identifier step, String member) {
        Expression s = new Expression.NameRef(step, false, step.span());
        return new Expression.MemberAccess(s, new Identifier(member, step.span()), step.span());
    }

    /**
     * PLCopen's implicit step variables {@code S : STRUCT X : BOOL; T : TIME; END_STRUCT} (initial step: X := TRUE).
     */
    public static VarBlock stepVariables(List<Sfc.Step> steps, Span span) {
        List<VarDecl> vars = new ArrayList<>();
        for (Sfc.Step st : steps) {
            Span s = st.span();
            List<VarDecl> members = List.of(member("X", "BOOL", s), member("T", "TIME", s));
            Initializer init = st.initial()
                    ? new Initializer.StructInit(
                            List.of(new Initializer.FieldInit(
                                    new Identifier("X", s),
                                    new Expression.Literal(Expression.LiteralKind.BOOL, "TRUE", null, s),
                                    s)),
                            s)
                    : null;
            vars.add(new VarDecl(st.name(), null, null, new TypeRef.StructType(members, s), init, false, List.of(), s));
        }
        return new VarBlock(VarBlock.Section.VAR, Set.of(), vars, List.of(), span);
    }

    private static VarDecl member(String name, String type, Span s) {
        TypeRef t = new TypeRef.Named(
                new QualifiedName(List.of(new Identifier(type, s)), s), List.of(), null, null, null, s);
        return new VarDecl(new Identifier(name, s), null, null, t, null, false, List.of(), s);
    }

    private static Statement guard(Expression cond, Statement s, Span span) {
        return new Statement.If(List.of(new Statement.Branch(cond, List.of(s), span)), null, span);
    }

    private static Statement guard(Expression cond, List<Statement> body, Span span) {
        return new Statement.If(List.of(new Statement.Branch(cond, body, span)), null, span);
    }

    private static Statement assign(Expression t, Expression v, Span s) {
        return new Statement.Assignment(t, Statement.AssignOp.ASSIGN, v, s);
    }

    private static Expression bool(boolean v, Span s) {
        return new Expression.Literal(Expression.LiteralKind.BOOL, v ? "TRUE" : "FALSE", null, s);
    }
}
