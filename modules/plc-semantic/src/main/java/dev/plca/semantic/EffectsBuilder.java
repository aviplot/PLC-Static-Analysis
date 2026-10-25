package dev.plca.semantic;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.ast.ladder.InstructionSet;
import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pass 4a: read/write sets and call sites per statement, and the call graph.
 *
 * <p>FB call semantics: {@code fb(IN := x, Q => y)} writes {@code fb.IN}, reads {@code x}, reads {@code fb.Q} and
 * writes {@code y}; VAR_IN_OUT arguments are read and written; {@code ADR(x)}/{@code REF(x)}/{@code r REF= x} take the
 * address of {@code x} (an alias, not a read). Property reads/writes are calls of their GET/SET accessor.
 */
final class EffectsBuilder {

    private static final Set<String> ADDRESS_OPERATORS = Set.of("adr", "ref", "bitadr");
    private static final Set<String> TYPE_OPERATORS =
            Set.of("sizeof", "__new", "indexof", "upper_bound", "lower_bound");

    private final SemanticModel model;

    EffectsBuilder(SemanticModel model) {
        this.model = model;
    }

    /** Effects of every statement of all POUs, and the call graph. */
    void build() {
        for (PouSymbol p : model.pous) {
            build(p);
            Statements.forEachDeep(
                    p.declaration().body(), st -> model.effects(st).calls().forEach(model.callGraph::add));
        }
    }

    /** Effects of every statement of {@code p}. */
    void build(PouSymbol p) {
        Statements.forEachDeep(p.declaration().body(), s -> {
            Collector c = new Collector(p);
            statement(s, c);
            model.effects.put(s, new Effects(c.accesses, c.calls));
        });
    }

    /** Accumulates the effects of one statement. */
    private static final class Collector {
        final PouSymbol pou;
        final List<Access> accesses = new ArrayList<>();
        final List<CallSite> calls = new ArrayList<>();

        Collector(PouSymbol pou) {
            this.pou = pou;
        }
    }

    private void statement(Statement s, Collector c) {
        switch (s) {
            case Statement.Assignment a -> {
                if (a.op() == Statement.AssignOp.REF) {
                    write(a.target(), c);
                    address(a.value(), c);
                } else if (a.op().isCompound()) {
                    // x += v has the effects of x := x + v
                    write(a.target(), c);
                    read(a.target(), c);
                    read(a.value(), c);
                } else {
                    write(a.target(), c);
                    read(a.value(), c);
                }
            }
            case Statement.CallStatement cs -> read(cs.call(), c);
            case Statement.If i -> i.branches().forEach(b -> read(b.condition(), c));
            case Statement.Case k -> read(k.selector(), c);
            case Statement.For f -> {
                write(f.variable(), c);
                read(f.from(), c);
                read(f.to(), c);
                if (f.by() != null) {
                    read(f.by(), c);
                }
            }
            case Statement.While w -> read(w.condition(), c);
            case Statement.Repeat r -> read(r.condition(), c);
            case Statement.Return r -> {
                if (r.value() != null) {
                    read(r.value(), c);
                }
            }
            default -> {}
        }
    }

    // ---- access paths ---------------------------------------------------------------------------

    /** Root variable and selector path of a designator, or null if it does not denote a variable. */
    private record PathRef(VariableSymbol variable, String path) {}

    private PathRef path(Expression e) {
        return switch (e) {
            case Expression.NameRef n -> {
                if (!(model.symbols.get(n) instanceof VariableSymbol v)) {
                    yield null;
                }
                // an overlay view (Siemens `x AT y`, a Rockwell alias) shares its base's storage
                yield v.overlayOf() != null ? new PathRef(v.overlayOf(), v.overlayPath()) : new PathRef(v, "");
            }
            case Expression.MemberAccess m -> {
                Symbol s = model.symbols.get(m);
                if (!(s instanceof VariableSymbol v)) {
                    yield null;
                }
                PathRef base = path(m.target());
                // GVL.x and THIS^.x name the variable itself
                if (base == null || v.isGlobal() && model.symbols.get(m.target()) instanceof GvlSymbol) {
                    yield new PathRef(v, "");
                }
                yield new PathRef(base.variable(), base.path() + "." + v.name());
            }
            case Expression.Index i -> {
                PathRef base = path(i.target());
                if (base == null) {
                    yield null;
                }
                StringBuilder sb = new StringBuilder("[");
                for (int k = 0; k < i.indices().size(); k++) {
                    Constant c = model.constants.evaluate(i.indices().get(k));
                    sb.append(k > 0 ? "," : "").append(c instanceof Constant.Int ci ? String.valueOf(ci.value()) : "*");
                }
                yield new PathRef(base.variable(), base.path() + sb.append(']'));
            }
            case Expression.Deref d -> {
                PathRef base = path(d.target());
                yield base == null ? null : new PathRef(base.variable(), base.path() + "^");
            }
            case Expression.BitAccess b -> {
                PathRef base = path(b.target());
                yield base == null ? null : new PathRef(base.variable(), base.path() + "." + b.bit());
            }
            case Expression.Slice sl -> {
                PathRef base = path(sl.target());
                yield base == null ? null : new PathRef(base.variable(), base.path() + "." + slicePath(sl));
            }
            case Expression.IndirectBit b -> {
                PathRef base = path(b.target());
                yield base == null ? null : new PathRef(base.variable(), base.path() + ".[*]");
            }
            default -> null;
        };
    }

    private void access(Expression e, Access.Kind kind, Collector c) {
        PathRef p = path(e);
        if (p != null) {
            c.accesses.add(new Access(p.variable(), p.path(), kind, e, e.span()));
        }
    }

    // ---- reads, writes ----------------------------------------------------------------------------

    private void write(Expression target, Collector c) {
        Symbol s = model.symbols.get(target);
        if (s instanceof PouSymbol prop && prop.kind() == PouKind.PROPERTY) {
            accessor(prop, PouKind.PROPERTY_SET, target, c);
            readSubexpressions(target, c);
            return;
        }
        access(target, Access.Kind.WRITE, c);
        readSubexpressions(target, c);
    }

    /** Reads inside a designator that are not the designated variable: indices, call arguments, the object. */
    private void readSubexpressions(Expression e, Collector c) {
        switch (e) {
            case Expression.MemberAccess m -> {
                if (path(m.target()) == null) {
                    read(m.target(), c);
                } else {
                    readSubexpressions(m.target(), c);
                }
            }
            case Expression.Index i -> {
                readSubexpressions(i.target(), c);
                i.indices().forEach(x -> read(x, c));
            }
            case Expression.Deref d -> readSubexpressions(d.target(), c);
            case Expression.BitAccess b -> readSubexpressions(b.target(), c);
            case Expression.Slice sl -> readSubexpressions(sl.target(), c);
            case Expression.IndirectBit b -> {
                readSubexpressions(b.target(), c);
                read(b.bit(), c);
            }
            default -> {}
        }
    }

    private void read(Expression e, Collector c) {
        switch (e) {
            case null -> {}
            case Expression.Call call -> call(call, c);
            case Expression.Unary u -> read(u.operand(), c);
            case Expression.Binary b -> {
                read(b.left(), c);
                read(b.right(), c);
            }
            // ExST `(x := v)`: the enclosing statement writes x (and the expression's value is v)
            case Expression.Assign a -> {
                read(a.value(), c);
                write(a.target(), c);
            }
            case Expression.Literal l -> {}
            default -> {
                Symbol s = model.symbols.get(e);
                if (s instanceof PouSymbol prop && prop.kind() == PouKind.PROPERTY) {
                    accessor(prop, PouKind.PROPERTY_GET, e, c);
                    readSubexpressions(e, c);
                } else if (path(e) != null) {
                    access(e, Access.Kind.READ, c);
                    readSubexpressions(e, c);
                } else if (e instanceof Expression.MemberAccess m) {
                    read(m.target(), c);
                } else if (e instanceof Expression.Index i) {
                    read(i.target(), c);
                    i.indices().forEach(x -> read(x, c));
                } else if (e instanceof Expression.Deref d) {
                    read(d.target(), c);
                } else if (e instanceof Expression.BitAccess b) {
                    read(b.target(), c);
                } else if (e instanceof Expression.Slice sl) {
                    read(sl.target(), c);
                } else if (e instanceof Expression.IndirectBit b) {
                    read(b.target(), c);
                    read(b.bit(), c);
                }
            }
        }
    }

    /** {@code %X3} for bit 3, {@code %B1} for byte 1: the selector spelled as in SCL, so paths read like the source. */
    private static String slicePath(Expression.Slice s) {
        char w = switch (s.width()) {
            case BIT -> 'X';
            case BYTE -> 'B';
            case WORD -> 'W';
            case DWORD -> 'D';
        };
        return "%" + w + s.index();
    }

    private void address(Expression e, Collector c) {
        if (path(e) != null) {
            access(e, Access.Kind.ADDRESS, c);
            readSubexpressions(e, c);
        } else {
            read(e, c);
        }
    }

    private void accessor(PouSymbol property, PouKind accessorKind, Expression at, Collector c) {
        PouSymbol accessor = property.members().values().stream()
                .filter(m -> m.kind() == accessorKind)
                .findFirst()
                .orElse(property);
        Access instance = at instanceof Expression.MemberAccess m ? instanceAccess(m.target()) : null;
        add(
                c,
                new CallSite(
                        c.pou,
                        accessor,
                        accessorKind == PouKind.PROPERTY_GET ? CallSite.Kind.PROPERTY_GET : CallSite.Kind.PROPERTY_SET,
                        instance,
                        at.span()));
    }

    // ---- calls ----------------------------------------------------------------------------------

    private void call(Expression.Call call, Collector c) {
        Expression callee = call.callee();
        Symbol s = model.symbols.get(callee);
        Span span = call.span();

        if (s instanceof InstructionSymbol instr) {
            instruction(call, instr, c);
            return;
        }
        if (s instanceof BuiltinFunction f) {
            String key = f.name().toLowerCase(java.util.Locale.ROOT);
            for (Argument a : call.arguments()) {
                if (a.value() == null || TYPE_OPERATORS.contains(key)) {
                    continue;
                }
                if (ADDRESS_OPERATORS.contains(key)) {
                    address(a.value(), c);
                } else {
                    read(a.value(), c);
                }
            }
            return;
        }

        PouSymbol target = null;
        CallSite.Kind kind = null;
        Access instance = null;
        if (s instanceof PouSymbol p) {
            switch (p.kind()) {
                case FUNCTION -> {
                    target = p;
                    kind = CallSite.Kind.FUNCTION;
                }
                case METHOD -> {
                    target = p;
                    kind = CallSite.Kind.METHOD;
                    instance = callee instanceof Expression.MemberAccess m ? instanceAccess(m.target()) : null;
                }
                case ACTION -> {
                    target = p;
                    kind = CallSite.Kind.ACTION;
                    instance = callee instanceof Expression.MemberAccess m ? instanceAccess(m.target()) : null;
                }
                default -> {}
            }
            if (callee instanceof Expression.MemberAccess m && instance == null) {
                read(m.target(), c);
            }
        } else if (model.type(callee).resolved() instanceof Type.Instance i) {
            // fbInstance(...): runs the FB body on that instance
            target = i.pou();
            kind = CallSite.Kind.FB_INSTANCE;
            instance = instanceAccess(callee);
            readSubexpressions(callee, c);
        } else {
            read(callee, c); // unknown/library callee: at least its own subexpressions
        }

        for (Argument a : call.arguments()) {
            VariableSymbol param = model.parameters.get(a);
            if (a.kind() == ArgumentKind.OUTPUT) {
                if (instance != null && param != null) {
                    c.accesses.add(new Access(
                            instance.variable(),
                            instance.path() + "." + param.name(),
                            Access.Kind.READ,
                            callee,
                            a.span()));
                }
                if (a.value() != null) {
                    write(a.value(), c);
                }
                continue;
            }
            if (a.value() == null) {
                continue;
            }
            if (param != null && param.section() == VarBlock.Section.IN_OUT) {
                access(a.value(), Access.Kind.READ_WRITE, c);
                readSubexpressions(a.value(), c);
                continue;
            }
            if (param != null && param.section() == VarBlock.Section.OUTPUT && kind != CallSite.Kind.FB_INSTANCE) {
                // classic S7 SCL passes outputs of functions with ':=' (READ_CLK(CDT := t)): the callee writes them
                write(a.value(), c);
                continue;
            }
            if (instance != null && param != null && kind == CallSite.Kind.FB_INSTANCE) {
                c.accesses.add(new Access(
                        instance.variable(),
                        instance.path() + "." + param.name(),
                        Access.Kind.WRITE,
                        callee,
                        a.span()));
            }
            read(a.value(), c);
        }

        if (target != null) {
            add(c, new CallSite(c.pou, target, kind, instance, span));
        }
    }

    /**
     * A Rockwell instruction: what it does with each operand comes from the instruction table. JSR-style parameters:
     * the first {@code n} (the count operand) are inputs, the rest returns. Operands of an unknown instruction may be
     * read or written ({@link Access.Kind#UNKNOWN}). The rung condition ({@code EnableIn := …}) is read.
     */
    private void instruction(Expression.Call call, InstructionSymbol instr, Collector c) {
        int index = 0;
        long inputs = -1;
        int parameter = 0;
        for (Argument a : call.arguments()) {
            if (a.kind() != ArgumentKind.POSITIONAL) {
                read(a.value(), c);
                continue;
            }
            // ladder loop FOR(index, initial, terminal, step) … NXT has no routine operand: shift past it
            // TODO(verify-format): seen in corpus exports, not yet checked against 1756-RM003
            int roleIndex = loopFor(instr, call) ? index + 1 : index;
            InstructionSet.Role role = instr.isKnown() ? instr.definition().role(roleIndex) : null;
            index++;
            Expression v = a.value();
            if (v == null) {
                continue;
            }
            if (!instr.isKnown()) {
                accessOrRead(v, Access.Kind.UNKNOWN, c);
                continue;
            }
            if (role == null) {
                accessOrRead(v, Access.Kind.UNKNOWN, c); // more operands than the table lists
                continue;
            }
            switch (role) {
                case READ, EXPRESSION -> read(v, c);
                case WRITE -> write(v, c);
                case READ_WRITE, INSTANCE -> accessOrRead(v, Access.Kind.READ_WRITE, c);
                case COUNT -> {
                    Constant k = model.constants.evaluate(v);
                    inputs = k instanceof Constant.Int i ? i.value() : -1;
                }
                case PARAMETER -> {
                    if (inputs < 0) {
                        accessOrRead(v, Access.Kind.UNKNOWN, c);
                    } else if (parameter++ < inputs) {
                        read(v, c);
                    } else {
                        write(v, c);
                    }
                }
                case ROUTINE -> {
                    if (model.symbols.get(v) instanceof PouSymbol routine) {
                        add(c, new CallSite(c.pou, routine, CallSite.Kind.ROUTINE, null, call.span()));
                    }
                }
                case NAME, LABEL, DISPLAY -> {}
            }
        }
    }

    /** {@code FOR} with four operands is the ladder loop, not the routine call {@code FOR(routine, index, …)}. */
    private static boolean loopFor(InstructionSymbol instr, Expression.Call call) {
        return instr.name().equalsIgnoreCase("FOR")
                && call.arguments().stream()
                                .filter(a -> a.kind() == ArgumentKind.POSITIONAL)
                                .count()
                        == 4;
    }

    private void accessOrRead(Expression e, Access.Kind kind, Collector c) {
        if (path(e) != null) {
            access(e, kind, c);
            readSubexpressions(e, c);
        } else {
            read(e, c);
        }
    }

    /** The instance an FB call, method call or property access operates on, as an access path. */
    private Access instanceAccess(Expression e) {
        PathRef p = path(e);
        return p == null ? null : new Access(p.variable(), p.path(), Access.Kind.READ, e, e.span());
    }

    private static void add(Collector c, CallSite call) {
        if (!call.callee().isBuiltin()) {
            c.calls.add(call);
        }
    }
}
