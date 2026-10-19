package dev.plca.rules.security;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Pragma;
import dev.plca.ast.Statement;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.rules.Walk;
import dev.plca.semantic.Access;
import dev.plca.semantic.GvlSymbol;
import dev.plca.semantic.IoTagSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import dev.plca.semantic.Symbol;
import dev.plca.semantic.VariableSymbol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PLCA-S002: a value an HMI, SCADA system or other network client can write drives a physical output, or indexes an
 * array, with no range or validity check in between (Top 20 Secure PLC Coding Practices, practices 8 and 9).
 *
 * <p>Externally writable: a global (Rockwell: controller or program tag) that the export marks as writable from outside
 * and that no code of the project writes (Rockwell {@code ExternalAccess="Read/Write"}, the default; Siemens data block
 * members unless {@code ExternalWritable := 'False'}); in CODESYS, TwinCAT and PLCopen, where every symbol may be
 * reachable, a variable with an OPC UA attribute or whose name or list name matches {@code externalPattern}.
 *
 * <p>A use counts as checked when, earlier in the body or in a condition around it, the value is compared, limited
 * ({@code LIMIT}, {@code MIN}, {@code MAX}, {@code SEL}, {@code MUX}), used as a CASE selector or overwritten. Only
 * pass-through is reported: the output is assigned an expression of the value and constants alone ({@code Out :=
 * HmiCmd}, {@code %QW4 := HmiSetpoint * 10}, ladder {@code XIC(HmiCmd)OTE(Out)}), or a constant under a condition on it
 * alone. Any interlock in the expression silences the finding.
 */
public final class UncheckedExternalInput implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-S002";
    }

    /** Marker for a Rockwell module output tag; the finding shows the target as written. */
    private static final String MODULE_OUTPUT = "\0module";

    private static final Set<String> LIMITERS = Set.of("LIMIT", "MIN", "MAX", "SEL", "MUX", "LIM");

    /** A variable, or one top-level member of a structured one (a data block or UDT field is its own value). */
    private record Key(VariableSymbol variable, String member) {}

    private static Key key(Access a) {
        String p = a.path();
        if (p.startsWith(".")) {
            int end = 1;
            while (end < p.length() && p.charAt(end) != '.' && p.charAt(end) != '[') {
                end++;
            }
            return new Key(a.variable(), p.substring(1, end).toLowerCase(Locale.ROOT));
        }
        return new Key(a.variable(), "");
    }

    /** {@code v} or {@code v.member…}: the variable and its first member, with the member's symbol if resolved. */
    private record Source(Key key, VariableSymbol member, String display) {}

    private static Source source(SemanticModel m, Expression e) {
        List<Expression> chain = new ArrayList<>();
        Expression x = e;
        while (true) {
            chain.add(x);
            switch (x) {
                case Expression.MemberAccess ma -> x = ma.target();
                case Expression.BitAccess b -> x = b.target();
                case Expression.Index i -> x = i.target();
                case Expression.NameRef n -> {
                    if (!(m.symbol(n).orElse(null) instanceof VariableSymbol v)) {
                        return null;
                    }
                    // chain: e … first selector, NameRef; the element before the NameRef is the first selector
                    Expression first = chain.size() >= 2 ? chain.get(chain.size() - 2) : null;
                    if (first instanceof Expression.MemberAccess ma) {
                        VariableSymbol member = m.symbol(ma).orElse(null) instanceof VariableSymbol mv ? mv : null;
                        return new Source(
                                new Key(v, ma.member().name().toLowerCase(Locale.ROOT)),
                                member,
                                v.name() + "." + ma.member().name());
                    }
                    return new Source(new Key(v, ""), null, v.name());
                }
                default -> {
                    return null;
                }
            }
        }
    }

    @Override
    public void check(ProjectContext ctx) {
        SemanticModel m = ctx.model();
        Pattern names = ctx.patternParam("externalPattern");
        Set<Key> written = new HashSet<>();
        for (PouSymbol p : m.pous()) {
            if (p.declaration() != null && p.declaration().body() != null) {
                Statements.forEachDeep(p.declaration().body(), s -> {
                    for (Access a : m.effects(s).accesses()) {
                        if (a.isWrite() || a.kind() == Access.Kind.ADDRESS || a.kind() == Access.Kind.UNKNOWN) {
                            written.add(key(a));
                        }
                    }
                });
            }
        }
        for (PouSymbol p : m.pous()) {
            if (p.declaration() == null || p.declaration().body() == null) {
                continue;
            }
            if (!hasSink(m, p.declaration().body())) {
                continue; // no physical output written and no array indexed: nothing to check
            }
            Set<Key> checked = new HashSet<>();
            body(ctx, p, p.declaration().body(), new ArrayList<>(), checked, written, names);
        }
    }

    private void body(
            ProjectContext ctx,
            PouSymbol pou,
            List<Statement> body,
            List<Expression> conditions,
            Set<Key> checked,
            Set<Key> written,
            Pattern names) {
        SemanticModel m = ctx.model();
        for (Statement s : body) {
            Walk.ownExpressions(s, e -> checks(m, e, checked));
            if (s instanceof Statement.Case c) {
                Source sel = source(m, c.selector());
                if (sel != null) {
                    checked.add(sel.key());
                }
            }
            if (s instanceof Statement.Assignment a) {
                sink(ctx, pou, a, conditions, checked, written, names);
                for (Access w : m.effects(a).accesses()) {
                    if (w.isWrite()) {
                        checked.add(key(w)); // overwritten here: later uses see our value
                    }
                }
            }
            Walk.ownExpressions(
                    s,
                    e -> Walk.deep(e, x -> {
                        if (x instanceof Expression.Index i) {
                            for (Expression ix : i.indices()) {
                                Source v = soleSource(m, ix, pou, written, names);
                                if (v != null && !checked.contains(v.key()) && !guardedBy(m, conditions, v.key())) {
                                    checked.add(v.key()); // once per body
                                    ctx.report(
                                            pou,
                                            ix.span(),
                                            "'" + ctx.text(ix).strip()
                                                    + "' can be written from outside the PLC (HMI, SCADA)"
                                                    + " and indexes an array without a bounds check; an out-of-range value"
                                                    + " faults the controller or overwrites other data",
                                            List.of());
                                }
                            }
                        }
                    }));
            switch (s) {
                case Statement.If i -> {
                    for (Statement.Branch b : i.branches()) {
                        List<Expression> inner = new ArrayList<>(conditions);
                        inner.add(b.condition());
                        body(ctx, pou, b.body(), inner, checked, written, names);
                    }
                    if (i.elseBody() != null) {
                        body(ctx, pou, i.elseBody(), conditions, checked, written, names);
                    }
                }
                case Statement.While w -> {
                    List<Expression> inner = new ArrayList<>(conditions);
                    inner.add(w.condition());
                    body(ctx, pou, w.body(), inner, checked, written, names);
                }
                default -> Statements.forEachBody(s, b -> body(ctx, pou, b, conditions, checked, written, names));
            }
        }
    }

    private void sink(
            ProjectContext ctx,
            PouSymbol pou,
            Statement.Assignment a,
            List<Expression> conditions,
            Set<Key> checked,
            Set<Key> written,
            Pattern names) {
        SemanticModel m = ctx.model();
        String output = output(m, a.target());
        if (output == null) {
            return;
        }
        if (output.equals(MODULE_OUTPUT)) {
            output = ctx.text(a.target()).strip();
        }
        Source source = soleSource(m, a.value(), pou, written, names);
        Expression via = a.value();
        if (source == null && m.constant(a.value()).isPresent() && !conditions.isEmpty()) {
            // OTL/OTU and IF HmiCmd THEN Out := TRUE: a constant under a condition on the value alone
            Expression last = conditions.getLast();
            source = soleSource(m, last, pou, written, names);
            via = last;
            if (conditions.size() > 1) {
                source = null; // nested conditions add interlocks
            }
        }
        if (source == null || checked.contains(source.key()) || guardedBy(m, conditions, source.key())) {
            return;
        }
        ctx.report(
                pou,
                a.span(),
                "Output " + output + " is driven directly by '" + source.display() + "', which can be written from"
                        + " outside the PLC (HMI, SCADA), with no range or validity check",
                List.of(new Diagnostic.Related(via.span(), "externally writable value")));
    }

    private static boolean hasSink(SemanticModel m, List<Statement> body) {
        boolean[] any = {false};
        Statements.forEachDeep(body, s -> {
            if (any[0]) {
                return;
            }
            if (s instanceof Statement.Assignment a && output(m, a.target()) != null) {
                any[0] = true;
                return;
            }
            Walk.ownExpressions(s, e -> Walk.deep(e, x -> any[0] |= x instanceof Expression.Index));
        });
        return any[0];
    }

    /** The physical output an assignment target writes, as text, or null. */
    private static String output(SemanticModel m, Expression target) {
        Expression root = target;
        while (true) {
            switch (root) {
                case Expression.MemberAccess ma -> root = ma.target();
                case Expression.BitAccess b -> root = b.target();
                case Expression.Index i -> root = i.target();
                default -> {
                    return switch (root) {
                        case Expression.DirectAddress d
                        when d.text().toUpperCase(Locale.ROOT).startsWith("%Q") -> d.text();
                        case Expression.NameRef n -> {
                            Symbol s = m.symbol(n).orElse(null);
                            if (s instanceof IoTagSymbol io && io.isOutput()) {
                                yield MODULE_OUTPUT; // shown as the target's own text
                            }
                            if (s instanceof VariableSymbol v) {
                                if (v.ioAlias() != null && v.ioAlias().isOutput()) {
                                    yield v.name();
                                }
                                if (v.address() != null
                                        && v.address().toUpperCase(Locale.ROOT).startsWith("%Q")) {
                                    yield v.name() + " (" + v.address() + ")";
                                }
                            }
                            yield null;
                        }
                        default -> null;
                    };
                }
            }
        }
    }

    /**
     * The externally writable variable an expression is built from, when it is the only variable in it (constants
     * aside); null otherwise.
     */
    private static Source soleSource(SemanticModel m, Expression e, PouSymbol pou, Set<Key> written, Pattern names) {
        // cheap structural pass first: the expression must hold exactly one designator rooted at an external variable
        if (!hasExternalRoot(m, e, pou, written, names)) {
            return null;
        }
        Source[] found = {null};
        boolean[] other = {false};
        Set<Expression> inside = Collections.newSetFromMap(new IdentityHashMap<>());
        Walk.deep(e, x -> {
            if (inside.contains(x) || other[0]) {
                return;
            }
            switch (x) {
                case Expression.Call c -> other[0] = true;
                case Expression.Literal l -> {}
                case Expression.Unary u -> {}
                case Expression.Binary b -> {}
                default -> {
                    if (m.constant(x).isPresent()) {
                        Walk.deep(x, inside::add);
                        return;
                    }
                    Source s = source(m, x);
                    if (s == null) {
                        other[0] = true;
                        return;
                    }
                    Walk.deep(x, inside::add); // its own subexpressions are part of it
                    if (found[0] != null && !found[0].key().equals(s.key())) {
                        other[0] = true;
                    }
                    found[0] = s;
                }
            }
        });
        if (other[0] || found[0] == null) {
            return null;
        }
        return external(found[0], pou, written, names) ? found[0] : null;
    }

    /** Some name in {@code e} resolves to a variable that may be external (no member resolution, no constants). */
    private static boolean hasExternalRoot(
            SemanticModel m, Expression e, PouSymbol pou, Set<Key> written, Pattern names) {
        boolean[] any = {false};
        Walk.deep(e, x -> {
            if (!any[0] && x instanceof Expression.NameRef n && m.symbol(n).orElse(null) instanceof VariableSymbol v) {
                Source s = source(m, x);
                any[0] = s != null && candidate(v, pou);
            }
        });
        return any[0];
    }

    /** Cheap filters that hold for every external variable of the dialect. */
    private static boolean candidate(VariableSymbol v, PouSymbol pou) {
        if (v.address() != null || v.ioAlias() != null || v.isConstant()) {
            return false;
        }
        // function block and AOI instances: their members are written by the block's own logic
        if (v.type().resolved() instanceof dev.plca.semantic.Type.Instance) {
            return false;
        }
        return switch (pou.dialect()) {
            case ROCKWELL ->
                v.isGlobal()
                        || v.owner() instanceof PouSymbol owner
                                && owner.kind() == dev.plca.ast.Declaration.PouKind.PROGRAM;
            case SIEMENS -> v.isGlobal();
            case null -> false;
            default -> true;
        };
    }

    private static boolean external(Source src, PouSymbol pou, Set<Key> written, Pattern names) {
        VariableSymbol v = src.key().variable();
        if (!candidate(v, pou)) {
            return false;
        }
        boolean unwritten = !written.contains(src.key()) && !written.contains(new Key(v, ""));
        List<Pragma> pragmas = src.member() != null ? src.member().pragmas() : v.pragmas();
        Dialect d = pou.dialect();
        if (d == Dialect.ROCKWELL) {
            boolean scoped = v.isGlobal()
                    || v.owner() instanceof PouSymbol owner && owner.kind() == dev.plca.ast.Declaration.PouKind.PROGRAM;
            String access = attribute(v.pragmas(), "ExternalAccess");
            return scoped && unwritten && (access == null || access.equalsIgnoreCase("Read/Write"));
        }
        if (d == Dialect.SIEMENS) {
            String writable = attribute(pragmas, "ExternalWritable");
            String accessible = attribute(pragmas, "ExternalAccessible");
            return v.isGlobal()
                    && unwritten
                    && !"false".equalsIgnoreCase(writable)
                    && !"false".equalsIgnoreCase(accessible);
        }
        for (Pragma p : pragmas) {
            if (p.kind() == Pragma.Kind.ATTRIBUTE
                    && p.name().toLowerCase(Locale.ROOT).startsWith("opc.ua.da")
                    && !"0".equals(p.value())) {
                return true;
            }
        }
        String list = v.owner() instanceof GvlSymbol g && g.isNamed() ? g.name() + "." : "";
        return (v.isGlobal()
                        || v.section() == dev.plca.ast.VarBlock.Section.INPUT
                                && pou.kind() == dev.plca.ast.Declaration.PouKind.PROGRAM)
                && names.matcher(list + src.display()).find();
    }

    private static String attribute(List<Pragma> pragmas, String name) {
        for (Pragma p : pragmas) {
            if (p.isAttribute(name)) {
                return p.value();
            }
        }
        return null;
    }

    /** Variables this expression compares or limits. */
    private static void checks(SemanticModel m, Expression root, Set<Key> checked) {
        Walk.deep(root, e -> {
            switch (e) {
                case Expression.Binary b
                when b.op() == BinaryOp.LT
                        || b.op() == BinaryOp.GT
                        || b.op() == BinaryOp.LE
                        || b.op() == BinaryOp.GE
                        || b.op() == BinaryOp.EQ
                        || b.op() == BinaryOp.NE -> {
                    Walk.deep(b.left(), x -> add(m, x, checked));
                    Walk.deep(b.right(), x -> add(m, x, checked));
                }
                case Expression.Call c
                when LIMITERS.contains(String.valueOf(Walk.lastName(c.callee())).toUpperCase(Locale.ROOT)) ->
                    c.arguments().forEach(a -> Walk.deep(a.value(), x -> add(m, x, checked)));
                default -> {}
            }
        });
    }

    private static void add(SemanticModel m, Expression e, Set<Key> checked) {
        Source s = source(m, e);
        if (s != null) {
            checked.add(s.key());
        }
    }

    private static boolean guardedBy(SemanticModel m, List<Expression> conditions, Key k) {
        Set<Key> c = new HashSet<>();
        conditions.forEach(e -> checks(m, e, c));
        return c.contains(k);
    }
}
