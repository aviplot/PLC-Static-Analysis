package dev.plca.semantic;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Identifier;
import dev.plca.ast.Initializer;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef.PointerKind;
import dev.plca.ast.VarBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pass 3: binds every name, member access and call in every body to its {@link Symbol} and computes expression types.
 * Scoping follows CODESYS: locals of the body, then the enclosing property (for accessors), then the instance the body
 * runs in (FB/class/program, along EXTENDS) with its methods/actions/properties, then the global scope and builtins.
 * Unknown library prefixes ({@code Tc2_MC2.MC_Power}) become {@link LibrarySymbol}s.
 */
final class Binder {

    private final SemanticModel model;
    private final GlobalScope global;
    private int names;
    private int resolved;
    private int library;
    private int unresolved;
    private int external;

    /** Per unit, when the model keeps bookkeeping for incremental rebuilds; null otherwise. */
    private final Map<String, UnitState> states;
    /** Which unit declared a global variable. */
    private final Map<Object, UnitState> declaredBy;
    /** The unit being bound (with bookkeeping) and the unresolved names of the POU or variable being bound. */
    private UnitState unit;

    private List<Expression> unresolvedHere;

    Binder(SemanticModel model, Map<String, UnitState> states, Map<Object, UnitState> declaredBy) {
        this.model = model;
        this.global = model.global;
        this.states = states;
        this.declaredBy = declaredBy;
    }

    /** Binds the POUs and global variables of the units in {@code only} (by path), or of all units (null). */
    void bind(Set<String> only) {
        if (only == null) {
            for (PouSymbol p : List.copyOf(model.pous)) {
                bindPou(p);
            }
            for (GvlSymbol g : global.gvls()) {
                for (VariableSymbol v : g.variables().values()) {
                    bindGlobal(v);
                }
            }
            model.stats = new SemanticModel.ResolutionStats(names, resolved, library, unresolved, external);
        } else {
            // unresolved names and counts are kept per POU / variable and per unit: any order will do
            for (String path : only) {
                UnitState st = states.get(path);
                st.pous.forEach(this::bindPou);
                for (VariableSymbol v : st.globals) {
                    // as a full build: only what its list holds (a later file's variable of the same name replaces it)
                    if (v.owner() instanceof GvlSymbol g && g.variable(v.name()).orElse(null) == v) {
                        bindGlobal(v);
                    }
                }
            }
        }
    }

    private void bindPou(PouSymbol p) {
        String path = p.unit() == null ? null : p.unit().path();
        begin(p, states == null || path == null ? null : states.get(path));
        for (VariableSymbol v : p.variables().values()) {
            bindInitializer(v.initializer(), p);
        }
        if (p.declaration().body() != null) {
            for (Statement s : p.declaration().body()) {
                statement(s, p);
            }
        }
        end(p);
    }

    private void bindGlobal(VariableSymbol v) {
        begin(v, states == null ? null : declaredBy.get(v));
        bindInitializer(v.initializer(), null);
        end(v);
    }

    private final int[] before = new int[5];

    private void begin(Object owner, UnitState st) {
        unit = st;
        unresolvedHere = new ArrayList<>();
        before[0] = names;
        before[1] = resolved;
        before[2] = library;
        before[3] = unresolved;
        before[4] = external;
    }

    private void end(Object owner) {
        if (unresolvedHere.isEmpty()) {
            model.unresolvedBy.remove(owner);
        } else {
            model.unresolvedBy.put(owner, unresolvedHere);
        }
        if (unit != null) {
            unit.stats[0] += names - before[0];
            unit.stats[1] += resolved - before[1];
            unit.stats[2] += library - before[2];
            unit.stats[3] += unresolved - before[3];
            unit.stats[4] += external - before[4];
        }
        unit = null;
    }

    // ---- side tables, with the keys each unit wrote ----

    private void putSymbol(Expression e, Symbol s) {
        model.symbols.put(e, s);
        if (unit != null) {
            unit.symbolKeys.add(e);
        }
    }

    private void putType(Expression e, Type t) {
        model.types.put(e, t);
        if (unit != null) {
            unit.typeKeys.add(e);
        }
    }

    private void bindInitializer(Initializer init, PouSymbol pou) {
        switch (init) {
            case null -> {}
            case Expression e -> expression(e, pou);
            case Initializer.ArrayInit a -> a.elements().forEach(el -> bindInitializer(el.value(), pou));
            case Initializer.StructInit s -> s.fields().forEach(f -> bindInitializer(f.value(), pou));
        }
    }

    // ---- statements -----------------------------------------------------------------------------

    private void statement(Statement s, PouSymbol pou) {
        model.owners.put(s, pou);
        if (unit != null) {
            unit.statementKeys.add(s);
        }
        switch (s) {
            case Statement.Assignment a -> {
                expression(a.target(), pou);
                expression(a.value(), pou);
            }
            case Statement.CallStatement c -> expression(c.call(), pou);
            case Statement.If i -> {
                for (Statement.Branch b : i.branches()) {
                    expression(b.condition(), pou);
                    body(b.body(), pou);
                }
                body(i.elseBody(), pou);
            }
            case Statement.Case c -> {
                expression(c.selector(), pou);
                for (Statement.CaseBranch b : c.branches()) {
                    for (Statement.CaseLabel l : b.labels()) {
                        caseLabel(l.low(), c.selector(), pou);
                        if (l.high() != null) {
                            caseLabel(l.high(), c.selector(), pou);
                        }
                    }
                    body(b.body(), pou);
                }
                body(c.elseBody(), pou);
            }
            case Statement.For f -> {
                expression(f.variable(), pou);
                expression(f.from(), pou);
                expression(f.to(), pou);
                if (f.by() != null) {
                    expression(f.by(), pou);
                }
                body(f.body(), pou);
            }
            case Statement.While w -> {
                expression(w.condition(), pou);
                body(w.body(), pou);
            }
            case Statement.Repeat r -> {
                body(r.body(), pou);
                expression(r.condition(), pou);
            }
            case Statement.Return r -> {
                if (r.value() != null) {
                    expression(r.value(), pou);
                }
            }
            case Statement.Try t -> {
                body(t.body(), pou);
                body(t.catchBody(), pou);
                body(t.finallyBody(), pou);
            }
            case Statement.Region r -> body(r.body(), pou);
            case Statement.Rung r -> body(r.body(), pou);
            case Statement.Network n -> body(n.body(), pou);
            case Statement.Chart c -> body(c.body(), pou);
            default -> {}
        }
    }

    private void body(List<Statement> body, PouSymbol pou) {
        if (body != null) {
            for (Statement s : body) {
                statement(s, pou);
            }
        }
    }

    /** CASE labels may name values of the selector's enum without qualification even for strict enums. */
    private void caseLabel(Expression label, Expression selector, PouSymbol pou) {
        if (label instanceof Expression.NameRef n
                && model.type(selector).resolved() instanceof Type.Enum en
                && lookup(n.name().name(), pou).isEmpty()) {
            Optional<EnumValueSymbol> v = en.symbol().value(n.name().name());
            if (v.isPresent()) {
                names++;
                resolved++;
                record(n, v.get(), new Type.Enum(en.symbol()));
                return;
            }
        }
        expression(label, pou);
    }

    // ---- expressions ----------------------------------------------------------------------------

    private Type expression(Expression e, PouSymbol pou) {
        Type t = switch (e) {
            case Expression.Literal l -> literal(l);
            case Expression.NameRef n -> name(n, pou);
            case Expression.ThisRef t1 -> thisType(pou, false);
            case Expression.SuperRef s -> thisType(pou, true);
            case Expression.DirectAddress d -> directAddress(d.text());
            case Expression.MemberAccess m -> member(m, pou);
            case Expression.BitAccess b -> {
                expression(b.target(), pou);
                yield Type.BOOL;
            }
            case Expression.IndirectBit b -> {
                expression(b.target(), pou);
                expression(b.bit(), pou);
                yield Type.BOOL;
            }
            case Expression.Slice s -> {
                expression(s.target(), pou);
                yield switch (s.width()) {
                    case BIT -> Type.BOOL;
                    case BYTE -> Type.of(ElementaryKind.BYTE);
                    case WORD -> Type.of(ElementaryKind.WORD);
                    case DWORD -> Type.of(ElementaryKind.DWORD);
                };
            }
            case Expression.Deref d -> {
                Type target = expression(d.target(), pou).resolved();
                yield target instanceof Type.Pointer p ? p.target() : Type.UNKNOWN;
            }
            case Expression.Index i -> index(i, pou);
            case Expression.Call c -> call(c, pou);
            case Expression.Unary u -> unary(u, pou);
            case Expression.Binary b -> binary(b, pou);
            case Expression.Assign a -> {
                // ExST: as a statement assignment; the expression has the type of its target
                Type target = expression(a.target(), pou);
                expression(a.value(), pou);
                yield target;
            }
            case Expression.TypeOperand t2 -> Type.UNKNOWN;
            case Expression.Invalid inv -> Type.UNKNOWN;
        };
        putType(e, t);
        return t;
    }

    private void record(Expression e, Symbol s, Type t) {
        if (s != null) {
            putSymbol(e, s);
        }
        putType(e, t);
    }

    private Type name(Expression.NameRef n, PouSymbol pou) {
        if (n.name().form() == Identifier.Form.SYNTHETIC) {
            // ladder power flow ($rc1): made up by the frontend, no variable behind it and not a name of the source
            return Type.BOOL;
        }
        names++;
        String name = n.name().name();
        // Siemens: #x is a local of the block, "x" a global block/DB/UDT/tag; plain names follow the IEC chain
        Optional<Symbol> s = switch (n.name().form()) {
            case LOCAL -> localLookup(name, pou);
            case QUOTED -> global.lookup(name);
            case PROGRAM ->
                global.pou(name).filter(p -> p.kind() == PouKind.PROGRAM).map(Symbol.class::cast);
            case PLAIN -> n.globalScope() ? global.lookup(name) : lookup(name, pou);
            case SYNTHETIC -> Optional.<Symbol>empty();
        };
        if (s.isEmpty() && n.name().form() == Identifier.Form.PLAIN && isTypeName(name)) {
            // a type used as an operand: Siemens TypeOf comparisons, TypeOf(#v) = Real
            resolved++;
            return Type.UNKNOWN;
        }
        if (s.isEmpty() && rockwell(pou) && name.indexOf(':') > 0) {
            // module and status tags (Local:1:I, S:FS) come from the I/O configuration, not from the export's tags
            external++;
            IoTagSymbol io = IoTagSymbol.of(name);
            putSymbol(n, io);
            return io.status() && name.length() <= 4 ? Type.BOOL : new Type.Unknown(name);
        }
        if (s.isEmpty() && rockwell(pou) && partialExport(pou)) {
            // a routine or AOI exported on its own refers to tags of the project it came from
            external++;
            putSymbol(n, new LibrarySymbol(name));
            return new Type.Unknown(name);
        }
        if (s.isEmpty() && n.name().form() == Identifier.Form.QUOTED) {
            // a PLC tag (tag tables are not part of SCL exports) or a block outside the export: never guessed
            external++;
            putSymbol(n, new LibrarySymbol(name));
            return new Type.Unknown(name);
        }
        if (s.isEmpty()) {
            unresolved++;
            unresolvedHere.add(n);
            return new Type.Unknown(name);
        }
        resolved++;
        putSymbol(n, s.get());
        return typeOfSymbol(s.get());
    }

    private static boolean isTypeName(String name) {
        return ElementaryKind.byName(name).isPresent() || name.equalsIgnoreCase("DTL");
    }

    /** Siemens {@code #name}: a variable of the block itself (or of the FB a member belongs to), nothing global. */
    private static Optional<Symbol> localLookup(String name, PouSymbol pou) {
        for (PouSymbol p = pou; p != null; p = p.owner()) {
            Optional<VariableSymbol> v = p.isInstantiable() ? p.findVariable(name) : p.variable(name);
            if (v.isPresent()) {
                return Optional.of(v.get());
            }
        }
        return Optional.empty();
    }

    /** CODESYS scope chain for an unqualified name used in the body of {@code pou}. */
    Optional<Symbol> lookup(String name, PouSymbol pou) {
        for (PouSymbol p = pou; p != null; p = p.owner()) {
            Optional<VariableSymbol> v = p.isInstantiable() ? p.findVariable(name) : p.variable(name);
            if (v.isPresent()) {
                return Optional.of(v.get());
            }
            if (p.isInstantiable() || p.kind() == PouKind.INTERFACE) {
                Optional<PouSymbol> m = p.findMember(name);
                if (m.isPresent()) {
                    return Optional.of(m.get());
                }
            }
        }
        return global.lookup(name);
    }

    private Type typeOfSymbol(Symbol s) {
        return switch (s) {
            case VariableSymbol v -> v.type();
            case EnumValueSymbol v -> new Type.Enum(v.enumType());
            case PouSymbol p ->
                switch (p.kind()) {
                    // a PROGRAM name is a global instance; a property name reads its value
                    case PROGRAM, FUNCTION_BLOCK, CLASS, INTERFACE -> new Type.Instance(p);
                    case PROPERTY -> p.returnType();
                    default -> Type.VOID;
                };
            case TypeSymbol t -> t.type();
            default -> Type.UNKNOWN;
        };
    }

    private Type thisType(PouSymbol pou, boolean superRef) {
        PouSymbol owner = pou == null ? null : pou.instanceOwner();
        if (owner != null && superRef) {
            owner = owner.extendsPou();
        }
        return owner == null ? Type.UNKNOWN : new Type.Pointer(PointerKind.POINTER, new Type.Instance(owner));
    }

    private Type member(Expression.MemberAccess m, PouSymbol pou) {
        names++;
        Type targetType = expression(m.target(), pou);
        Symbol targetSymbol = model.symbols.get(m.target());
        String name = m.member().name();
        Symbol s = null;
        if (targetSymbol instanceof GvlSymbol g) {
            s = g.variable(name).orElse(null);
        } else if (targetSymbol instanceof TypeSymbol ts && ts.kind() == TypeSymbol.Kind.ENUM) {
            s = ts.value(name).orElse(null);
        } else if (targetSymbol instanceof LibrarySymbol lib) {
            s = new LibrarySymbol(lib.name() + "." + name);
        } else if (targetSymbol == null && m.target() instanceof Expression.NameRef prefix) {
            // unknown prefix: a namespace/library qualifier, or a typo
            names--; // the prefix was already counted as unresolved
            unresolved--;
            if (!unresolvedHere.isEmpty() && unresolvedHere.getLast() == prefix) {
                unresolvedHere.removeLast();
            }
            s = qualified(prefix.name().name(), name, pou);
        } else {
            s = memberOf(targetType, name);
        }
        if (s == null) {
            if (Type.isUnknown(targetType)) {
                library++; // member of a library (or otherwise unknown) type: cannot know
            } else {
                unresolved++;
                unresolvedHere.add(m);
            }
            return new Type.Unknown(name);
        }
        if (s instanceof LibrarySymbol) {
            library++;
        } else {
            resolved++;
        }
        putSymbol(m, s);
        return typeOfSymbol(s);
    }

    /**
     * {@code prefix.name} with an unknown prefix: a namespace, or a library the project does not contain. The last-part
     * fallback never yields a POU enclosing the reference ({@code AxUnit.Assert.Equal} inside {@code Equal} is not a
     * recursive call).
     */
    private Symbol qualified(String prefix, String name, PouSymbol pou) {
        Optional<Symbol> s = global.pou(prefix + "." + name).map(Symbol.class::cast);
        if (s.isEmpty()) {
            s = global.type(prefix + "." + name).map(Symbol.class::cast);
        }
        if (s.isEmpty()) {
            s = global.pou(name).filter(c -> !SemanticBuilder.encloses(c, pou)).map(Symbol.class::cast);
        }
        if (s.isEmpty()) {
            s = global.type(name).map(Symbol.class::cast);
        }
        if (s.isEmpty()) {
            s = global.builtin(name);
        }
        return s.orElseGet(() -> new LibrarySymbol(prefix + "." + name));
    }

    /** Member of a value of type {@code t}: FB variables/members, struct members; references dereference. */
    private Symbol memberOf(Type t, String name) {
        Type r = t.resolved();
        if (r instanceof Type.Pointer p && (p.kind() == PointerKind.REFERENCE || p.kind() == PointerKind.POINTER)) {
            r = p.target().resolved(); // REFERENCE TO is implicit; tolerate missing ^ on pointers
        }
        if (r instanceof Type.Instance i) {
            Optional<VariableSymbol> v = i.pou().findVariable(name);
            if (v.isPresent()) {
                return v.get();
            }
            return i.pou().findMember(name).orElse(null);
        }
        if (r instanceof Type.Struct s) {
            return s.symbol().findMember(name).orElse(null);
        }
        return null;
    }

    private Type index(Expression.Index i, PouSymbol pou) {
        Type target = expression(i.target(), pou).resolved();
        for (Expression x : i.indices()) {
            expression(x, pou);
        }
        if (target instanceof Type.Pointer p) {
            target = p.target().resolved();
        }
        if (target instanceof Type.Array a) {
            int n = i.indices().size();
            if (n >= a.dimensions().size()) {
                return a.element();
            }
            return new Type.Array(a.dimensions().subList(n, a.dimensions().size()), a.element());
        }
        if (target instanceof Type.StringType s) {
            return Type.of(s.wide() ? ElementaryKind.WORD : ElementaryKind.BYTE);
        }
        return Type.UNKNOWN;
    }

    /** An L5X export of less than a controller ({@code TargetType} Routine, Rung, Program, AOI, …). */
    private static boolean partialExport(PouSymbol pou) {
        return pou.unit() != null
                && pou.unit().pragmas().stream()
                        .anyMatch(p -> p.isAttribute("TargetType") && !"Controller".equalsIgnoreCase(p.value()));
    }

    private static boolean rockwell(PouSymbol pou) {
        return pou != null && pou.dialect() == dev.plca.core.Dialect.ROCKWELL;
    }

    /**
     * Rockwell: a called name that is no tag, routine or AOI of the project is an instruction ({@code TON},
     * {@code COP}, {@code JSR}) — known from the instruction table or not — even where IEC defines a builtin of the
     * same name ({@code TON} is a TIMER instruction here, not the IEC function block).
     */
    private InstructionSymbol instruction(Expression.Call c, PouSymbol pou) {
        if (!rockwell(pou)
                || !(c.callee() instanceof Expression.NameRef n)
                || n.name().form() != Identifier.Form.PLAIN) {
            return null;
        }
        Optional<Symbol> s = lookup(n.name().name(), pou);
        boolean builtin = s.isPresent()
                && (s.get() instanceof BuiltinFunction || s.get() instanceof PouSymbol p && p.isBuiltin());
        if (s.isPresent() && !builtin) {
            return null;
        }
        Optional<dev.plca.ast.ladder.InstructionSet.Instruction> def =
                dev.plca.ast.ladder.InstructionSet.lookup(n.name().name());
        if (def.isEmpty() && builtin) {
            return null; // MIN, MAX, LIMIT: IEC functions Rockwell ST also offers
        }
        return new InstructionSymbol(n.name().name(), def.orElse(null));
    }

    private Type instructionCall(Expression.Call c, InstructionSymbol instr, PouSymbol pou) {
        names++;
        if (instr.isKnown()) {
            resolved++;
        } else {
            library++; // an AOI or instruction outside the export: cannot know
        }
        putSymbol(c.callee(), instr);
        putType(c.callee(), Type.VOID);
        List<Type> argTypes = new ArrayList<>();
        int index = 0;
        for (Argument a : c.arguments()) {
            dev.plca.ast.ladder.InstructionSet.Role role =
                    a.kind() == Expression.ArgumentKind.POSITIONAL && instr.isKnown()
                            ? instr.definition().role(index)
                            : null;
            if (a.kind() == Expression.ArgumentKind.POSITIONAL) {
                index++;
            }
            if (a.value() == null) {
                argTypes.add(Type.UNKNOWN);
                continue;
            }
            if (role == dev.plca.ast.ladder.InstructionSet.Role.ROUTINE && a.value() instanceof Expression.NameRef r) {
                routine(r, pou);
                argTypes.add(Type.VOID);
            } else if (role == dev.plca.ast.ladder.InstructionSet.Role.NAME
                    || role == dev.plca.ast.ladder.InstructionSet.Role.LABEL
                    || role == dev.plca.ast.ladder.InstructionSet.Role.DISPLAY
                    || setting(a.value(), pou)) {
                argTypes.add(Type.UNKNOWN); // a word, a jump label or a display value: not a tag
            } else {
                argTypes.add(expression(a.value(), pou));
            }
        }
        if (!instr.isKnown()) {
            return Type.UNKNOWN;
        }
        dev.plca.ast.ladder.InstructionSet.Instruction d = instr.definition();
        if (d.kind() == dev.plca.ast.ladder.InstructionSet.Kind.INPUT) {
            return Type.BOOL;
        }
        // ST function form of a ladder instruction without its destination: ABS(x), SQRT(x)
        int positional = (int) c.arguments().stream()
                .filter(a -> a.kind() == Expression.ArgumentKind.POSITIONAL)
                .count();
        if (!d.repeatLast()
                && positional == d.roles().size() - 1
                && d.roles().getLast() == dev.plca.ast.ladder.InstructionSet.Role.WRITE
                && !argTypes.isEmpty()) {
            return Builtins.concrete(argTypes.getFirst());
        }
        return Type.VOID;
    }

    /**
     * Motion and safety instructions take settings as words ({@code Trapezoidal}, {@code Yes}, {@code S-Curve}): an
     * operand made only of names that are no tag, routine or type of the project is such a setting, not an unresolved
     * name.
     */
    private boolean setting(Expression e, PouSymbol pou) {
        return switch (e) {
            case Expression.NameRef n ->
                n.name().form() == Identifier.Form.PLAIN
                        && n.name().name().indexOf(':') < 0
                        && lookup(n.name().name(), pou).isEmpty();
            case Expression.Binary b ->
                b.op() == Expression.BinaryOp.SUB && setting(b.left(), pou) && setting(b.right(), pou);
            default -> false;
        };
    }

    /** {@code JSR(Fill, 0)}: a routine of the program (or AOI) the calling routine belongs to. */
    private void routine(Expression.NameRef r, PouSymbol pou) {
        names++;
        PouSymbol owner = pou.instanceOwner() != null ? pou.instanceOwner() : pou;
        Optional<PouSymbol> routine = owner.member(r.name().name());
        if (routine.isEmpty() && owner.kind() == PouKind.ROUTINE && owner.owner() != null) {
            routine = owner.owner().member(r.name().name());
        }
        if (routine.isPresent()) {
            resolved++;
            record(r, routine.get(), Type.VOID);
        } else {
            library++; // a routine outside a partial export
            record(r, new LibrarySymbol(r.name().name()), Type.VOID);
        }
    }

    private Type call(Expression.Call c, PouSymbol pou) {
        InstructionSymbol instr = instruction(c, pou);
        if (instr != null) {
            return instructionCall(c, instr, pou);
        }
        Type calleeType = expression(c.callee(), pou);
        Symbol callee = model.symbols.get(c.callee());
        // inside F, `F` is the result variable, but `F(...)` calls F (recursion)
        if (callee instanceof VariableSymbol v && v.isResult() && v.owner() instanceof PouSymbol self) {
            callee = self;
            putSymbol(c.callee(), self);
            calleeType = Type.VOID;
        }
        List<VariableSymbol> params = parameters(callee, calleeType);
        List<Type> argTypes = new ArrayList<>();
        int positional = 0;
        for (Argument a : c.arguments()) {
            Type t = a.value() == null ? Type.UNKNOWN : expression(a.value(), pou);
            argTypes.add(t);
            VariableSymbol param = null;
            if (a.name() != null) {
                for (VariableSymbol p : params) {
                    if (p.name().equalsIgnoreCase(a.name().name())) {
                        param = p;
                        break;
                    }
                }
            } else {
                List<VariableSymbol> inputs = params.stream()
                        .filter(p -> p.section() == VarBlock.Section.INPUT || p.section() == VarBlock.Section.IN_OUT)
                        .toList();
                if (positional < inputs.size()) {
                    param = inputs.get(positional);
                }
                positional++;
            }
            if (param != null) {
                model.parameters.put(a, param);
                if (unit != null) {
                    unit.parameterKeys.add(a);
                }
            }
        }
        return switch (callee) {
            case PouSymbol p when p.kind() == PouKind.FUNCTION || p.kind() == PouKind.METHOD -> p.returnType();
            case BuiltinFunction f -> f.resultType().apply(argTypes);
            case null, default -> Type.VOID; // FB instance, action, property: no value (or unknown)
        };
    }

    /** Parameters of what is being called: a function/method, or the FB of a called instance. */
    private static List<VariableSymbol> parameters(Symbol callee, Type calleeType) {
        PouSymbol target = null;
        if (callee instanceof PouSymbol p && (p.kind() == PouKind.FUNCTION || p.kind() == PouKind.METHOD)) {
            target = p;
        } else if (calleeType.resolved() instanceof Type.Instance i) {
            target = i.pou();
        }
        if (target == null) {
            return List.of();
        }
        List<VariableSymbol> out = new ArrayList<>();
        // inherited parameters first, as in CODESYS
        List<PouSymbol> chain = new ArrayList<>();
        for (PouSymbol p = target; p != null && chain.size() < 64; p = p.extendsPou()) {
            chain.addFirst(p);
        }
        for (PouSymbol p : chain) {
            for (VariableSymbol v : p.variables().values()) {
                if (!v.isResult()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private Type unary(Expression.Unary u, PouSymbol pou) {
        Type t = expression(u.operand(), pou);
        if (u.op() == Expression.UnaryOp.NEGATE) {
            if (t instanceof Type.IntLiteral l) {
                return new Type.IntLiteral(-l.value());
            }
            if (t instanceof Type.RealLiteral r) {
                return new Type.RealLiteral(-r.value());
            }
        }
        return t;
    }

    private Type binary(Expression.Binary b, PouSymbol pou) {
        Type l = expression(b.left(), pou);
        Type r = expression(b.right(), pou);
        return switch (b.op()) {
            case EQ, NE, LT, GT, LE, GE -> Type.BOOL;
            case AND, OR, XOR, AND_THEN, OR_ELSE -> {
                if (Type.elementary(l) == ElementaryKind.BOOL || Type.elementary(r) == ElementaryKind.BOOL) {
                    yield Type.BOOL;
                }
                yield promote(l, r);
            }
            default -> {
                Constant c = model.constants.evaluate(b, x -> model.symbols.get(x));
                Type p = promote(l, r);
                if (p instanceof Type.IntLiteral && c instanceof Constant.Int i) {
                    yield new Type.IntLiteral(i.value());
                }
                if (p instanceof Type.RealLiteral && c != null && c.asDouble() != null) {
                    yield new Type.RealLiteral(c.asDouble());
                }
                yield p;
            }
        };
    }

    /** IEC generic promotion: reals dominate integers, wider types dominate, untyped literals adapt. */
    static Type promote(Type a, Type b) {
        boolean aLit = a instanceof Type.IntLiteral || a instanceof Type.RealLiteral;
        boolean bLit = b instanceof Type.IntLiteral || b instanceof Type.RealLiteral;
        if (aLit && bLit) {
            // stays an untyped literal; the value is filled in by the caller when it folds
            return a instanceof Type.RealLiteral ? a : b instanceof Type.RealLiteral ? b : a;
        }
        if (a instanceof Type.IntLiteral || a instanceof Type.RealLiteral) {
            if (b instanceof Type.RealLiteral || a instanceof Type.RealLiteral && isInt(b)) {
                return isInt(b) ? Type.of(ElementaryKind.LREAL) : b;
            }
            return Type.isUnknown(b) ? Builtins.concrete(a) : b;
        }
        if (b instanceof Type.IntLiteral || b instanceof Type.RealLiteral) {
            return promote(b, a);
        }
        ElementaryKind x = Type.elementary(a);
        ElementaryKind y = Type.elementary(b);
        if (x == null || y == null) {
            return x != null ? a : y != null ? b : Type.UNKNOWN;
        }
        if (x.category() == ElementaryKind.Category.DURATION || y.category() == ElementaryKind.Category.DURATION) {
            return x.category() == ElementaryKind.Category.DURATION && y.category() == ElementaryKind.Category.DURATION
                    ? (x.bits() >= y.bits() ? a : b)
                    : x.category() == ElementaryKind.Category.DATE
                            ? a
                            : y.category() == ElementaryKind.Category.DATE
                                    ? b
                                    : x.category() == ElementaryKind.Category.DURATION ? a : b;
        }
        if (x.category() == ElementaryKind.Category.DATE && y.category() == ElementaryKind.Category.DATE) {
            return Type.of(x.bits() == 64 || y.bits() == 64 ? ElementaryKind.LTIME : ElementaryKind.TIME);
        }
        if (x.isReal() || y.isReal()) {
            return x == ElementaryKind.LREAL || y == ElementaryKind.LREAL
                    ? Type.of(ElementaryKind.LREAL)
                    : Type.of(ElementaryKind.REAL);
        }
        return x.bits() >= y.bits() ? a : b;
    }

    private static boolean isInt(Type t) {
        ElementaryKind k = Type.elementary(t);
        return t instanceof Type.IntLiteral || k != null && k.isInteger();
    }

    private Type literal(Expression.Literal l) {
        String prefix = l.typePrefix();
        if (prefix != null) {
            Optional<ElementaryKind> k = ElementaryKind.byName(prefix);
            if (k.isPresent()) {
                return Type.of(k.get());
            }
            String p = prefix.toUpperCase(Locale.ROOT);
            switch (p) {
                case "T", "TIME" -> {
                    return Type.of(ElementaryKind.TIME);
                }
                case "LT", "LTIME" -> {
                    return Type.of(ElementaryKind.LTIME);
                }
                case "D", "DATE" -> {
                    return Type.of(ElementaryKind.DATE);
                }
                case "LD", "LDATE" -> {
                    return Type.of(ElementaryKind.LDATE);
                }
                case "STRING" -> {
                    return new Type.StringType(false, Math.max(1, l.text().length() - 2));
                }
                case "WSTRING" -> {
                    return new Type.StringType(true, Math.max(1, l.text().length() - 2));
                }
                default -> {}
            }
            // E_Color#Red: enum-typed literal
            Optional<TypeSymbol> enumType = global.type(prefix).filter(t -> t.kind() == TypeSymbol.Kind.ENUM);
            if (enumType.isPresent()) {
                names++;
                Optional<EnumValueSymbol> v = enumType.get().value(l.text());
                if (v.isPresent()) {
                    resolved++;
                    putSymbol(l, v.get());
                } else {
                    unresolved++;
                }
                return new Type.Enum(enumType.get());
            }
            return new Type.Unknown(prefix);
        }
        return switch (l.kind()) {
            case INTEGER -> {
                Long v = Literals.integer(l.text());
                yield v == null ? Type.of(ElementaryKind.ULINT) : new Type.IntLiteral(v);
            }
            case REAL -> {
                Double d = Literals.real(l.text());
                yield new Type.RealLiteral(d == null ? 0 : d);
            }
            case BOOL -> Type.BOOL;
            case STRING -> new Type.StringType(false, Math.max(0, l.text().length() - 2));
            case WSTRING -> new Type.StringType(true, Math.max(0, l.text().length() - 2));
            case TIME -> Type.of(ElementaryKind.TIME);
            case DATE -> Type.of(ElementaryKind.DATE);
            case TIME_OF_DAY -> Type.of(ElementaryKind.TIME_OF_DAY);
            case DATE_AND_TIME -> Type.of(ElementaryKind.DATE_AND_TIME);
            case ENUM -> Type.UNKNOWN;
        };
    }

    /** {@code %IX0.1} → BOOL, {@code %QW2} → WORD, …; {@code %I*} (TwinCAT, linked later) → unknown. */
    private static Type directAddress(String text) {
        if (text.length() < 3 || text.contains("*")) {
            return Type.UNKNOWN;
        }
        return switch (Character.toUpperCase(text.charAt(2))) {
            case 'X' -> Type.BOOL;
            case 'B' -> Type.of(ElementaryKind.BYTE);
            case 'W' -> Type.of(ElementaryKind.WORD);
            case 'D' -> Type.of(ElementaryKind.DWORD);
            case 'L' -> Type.of(ElementaryKind.LWORD);
            default -> Type.BOOL; // %I0.1 without size prefix is a bit
        };
    }
}
