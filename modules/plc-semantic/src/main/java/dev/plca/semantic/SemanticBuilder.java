package dev.plca.semantic;

import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Node;
import dev.plca.ast.Pragma;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Span;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a {@link SemanticModel} from the source units of one project in four passes (declare, resolve types, bind
 * bodies, derive effects/graphs/tasks). Missing library declarations are expected and never fatal; nothing here throws
 * on bad input.
 */
public final class SemanticBuilder {

    /** Diagnostic codes of the semantic model (PLC-SEM-001 is reserved for unresolved names, reported by rules). */
    public static final String DUPLICATE = "PLC-SEM-002";

    public static final String CYCLIC_DECLARATION = "PLC-SEM-003";
    public static final String INTERNAL = "PLC-SEM-999";

    static final int MAX_INSTANCE_DEPTH = 32;

    /** Above this share of the units an incremental build is no faster than a full one (tests raise it). */
    static double maxRebuiltShare = 1 / 3.0;

    /**
     * Sets the share of a group's units above which {@link #buildForEditing} does a full build (default 1/3); 1.0 lets
     * tests of small projects take the incremental path. Returns the previous value.
     */
    public static double setMaxRebuiltShare(double share) {
        double old = maxRebuiltShare;
        maxRebuiltShare = share;
        return old;
    }

    static final int MAX_INSTANCES = 200_000;

    private final DiagnosticSink sink;
    private SemanticModel model;
    private GlobalScope global;
    private final List<Declaration.Configuration> configurations = new ArrayList<>();
    private final Set<TypeSymbol> resolvingTypes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<PendingType> pendingTypes = new ArrayList<>();

    /**
     * Something whose TypeRef is resolved in pass 2, in the context of a POU (for local constants) and of the unit that
     * declared it; {@code rockwell} when that unit is Rockwell's (Rockwell's predefined types apply).
     */
    private record PendingType(Object target, TypeRef ref, PouSymbol context, boolean rockwell, UnitState unit) {}

    /** Declarations of the unit being declared (pass 1) or the type being resolved (pass 2) are Rockwell's. */
    private boolean rockwell;

    private PendingType pending(Object target, TypeRef ref, PouSymbol context) {
        return new PendingType(target, ref, context, rockwell, unit);
    }

    private SemanticModel previous;

    // ---- per-unit bookkeeping (M10: incremental rebuilds) ----

    /** Binding keys and dependencies are recorded (a model for an editor session). */
    private boolean track;
    /** The unit being declared, or whose declaration is being resolved. */
    private UnitState unit;

    private final Map<String, UnitState> states = new LinkedHashMap<>();
    /** Which unit declared a type, variable list or global variable. */
    private final Map<Object, UnitState> declaredBy = new IdentityHashMap<>();
    /** Incremental build: the states declared anew (all others come from the previous model); null in a full build. */
    private Set<UnitState> fresh;

    /** Gives up an incremental build: the full build takes over. */
    private static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Abort(String reason) {
            super(reason, null, false, false);
        }
    }

    private SemanticBuilder(DiagnosticSink sink) {
        this.sink = sink;
    }

    public static SemanticModel build(List<SourceUnit> units, DiagnosticSink sink) {
        return build(units, sink, null);
    }

    /**
     * As {@link #build(List, DiagnosticSink)}; {@code previous} (a model of an earlier version of the same project, or
     * null) only sizes the new model's tables.
     */
    public static SemanticModel build(List<SourceUnit> units, DiagnosticSink sink, SemanticModel previous) {
        return build(units, sink, previous, false);
    }

    /**
     * A model for an editor session, which keeps per-unit bookkeeping so that the next edit can rebuild only what it
     * affects. With a {@code previous} model built this way, the units it shares with {@code units} (same objects) are
     * kept: only the changed units, the units whose declarations use theirs, and the units whose bodies use either are
     * declared or bound again. Whenever that cannot be done exactly (names added or removed, shared variable lists,
     * configurations, Rockwell controllers, most of the project affected) it is a full build;
     * {@link SemanticModel#buildInfo()} says which. Either way the result equals a full build.
     */
    public static SemanticModel buildForEditing(List<SourceUnit> units, DiagnosticSink sink, SemanticModel previous) {
        if (units.stream().anyMatch(u -> u.dialect() == Dialect.ROCKWELL)) {
            // a controller export is one unit: nothing to keep (and its bookkeeping would be millions of keys)
            SemanticModel m = build(units, sink, previous, false);
            m.buildInfo =
                    SemanticModel.BuildInfo.full(units.size(), "a Rockwell export is one unit with everything in it");
            return m;
        }
        if (previous != null && previous.states != null) {
            SemanticBuilder b = new SemanticBuilder(sink);
            b.track = true;
            b.previous = previous;
            String reason;
            try {
                reason = b.plan(units);
                if (reason == null) {
                    return b.runIncremental(units);
                }
            } catch (Abort a) {
                reason = a.getMessage();
            } catch (StackOverflowError | RuntimeException e) {
                reason = "internal error: " + e;
            }
            SemanticModel m = build(units, sink, previous, true);
            m.buildInfo = SemanticModel.BuildInfo.full(units.size(), reason);
            return m;
        }
        return build(units, sink, previous, true);
    }

    private static SemanticModel build(
            List<SourceUnit> units, DiagnosticSink sink, SemanticModel previous, boolean track) {
        SemanticBuilder b = new SemanticBuilder(sink);
        b.previous = previous;
        b.track = track;
        try {
            return b.run(units);
        } catch (StackOverflowError | RuntimeException e) {
            // design rule 2: report and return what we have
            sink.report(Diagnostic.of(
                    INTERNAL, Severity.ERROR, "internal error in semantic analysis: " + e, firstSpan(units)));
            return b.model != null ? b.model : new SemanticModel(units, new GlobalScope());
        }
    }

    private SemanticModel run(List<SourceUnit> units) {
        global = new GlobalScope();
        model = new SemanticModel(units, global, previous);
        model.constants = new ConstantEvaluator(model.symbols::get);

        for (SourceUnit u : units) {
            unit = new UnitState(u);
            states.put(unit.path, unit);
            rockwell = u.dialect() == Dialect.ROCKWELL;
            declare(u, u.declarations(), "");
        }
        unit = null;
        rockwell = false;
        RockwellAliases aliases = new RockwellAliases(model, global);
        aliases.link();
        resolveTypes(model.pous);
        aliases.typeAliases();
        new Binder(model, track ? states : null, declaredBy).bind(null);
        new EffectsBuilder(model).build();
        finish(null);
        model.buildInfo = SemanticModel.BuildInfo.full(units.size(), null);
        return model;
    }

    /** Passes after binding, shared by both builds: call graph, tasks, instances, bookkeeping. */
    private void finish(Set<String> bound) {
        if (bound != null) {
            // in POU order, as a full build adds them; untouched POUs keep their call sites
            for (PouSymbol p : model.pous) {
                if (!bound.contains(p.unit().path())) {
                    previous.callGraph.callsFrom(p).forEach(model.callGraph::add);
                } else {
                    Statements.forEachDeep(
                            p.declaration().body(),
                            st -> model.effects(st).calls().forEach(model.callGraph::add));
                }
            }
        }
        rockwellEntryPoints();
        buildTasks();
        buildInstances();
        model.assembleUnresolved();
        if (track) {
            model.states = Collections.unmodifiableMap(states);
            model.declaredBy = declaredBy;
        }
    }

    // ============================================================================================
    // Incremental build
    // ============================================================================================

    /** The units to declare again (R) and to bind again (B, a superset of R). */
    private Set<String> redeclare;

    private Set<String> rebind;

    /** Decides whether the edit can be rebuilt incrementally: null if so, else why not. */
    private String plan(List<SourceUnit> units) {
        Map<String, UnitState> old = previous.states;
        if (old.size() != units.size()) {
            return "files were added or removed";
        }
        List<String> changed = new ArrayList<>();
        int i = 0;
        for (UnitState st : old.values()) {
            SourceUnit u = units.get(i++);
            if (!st.path.equals(u.path())) {
                return "files were added, removed or reordered";
            }
            if (u.dialect() == Dialect.ROCKWELL) {
                return "a Rockwell export is one unit with everything in it";
            }
            if (st.unit != u) {
                changed.add(st.path);
            }
        }
        if (changed.isEmpty()) {
            return "nothing changed";
        }
        for (UnitState st : old.values()) {
            for (Diagnostic d : st.diagnostics) {
                if (d.code().equals(CYCLIC_DECLARATION)) {
                    return "the project has cyclic declarations";
                }
            }
        }
        // R: the changed units and, transitively, the units whose declarations use theirs
        Map<String, List<String>> declaredUsers = new HashMap<>();
        Map<String, List<String>> boundUsers = new HashMap<>();
        for (UnitState st : old.values()) {
            st.declarationDeps.forEach(d ->
                    declaredUsers.computeIfAbsent(d, k -> new ArrayList<>()).add(st.path));
            bodyDependencies(st, previous)
                    .forEach(d -> boundUsers
                            .computeIfAbsent(d, k -> new ArrayList<>())
                            .add(st.path));
        }
        redeclare = new LinkedHashSet<>(changed);
        ArrayDeque<String> work = new ArrayDeque<>(changed);
        while (!work.isEmpty()) {
            for (String user : declaredUsers.getOrDefault(work.pop(), List.of())) {
                if (redeclare.add(user)) {
                    work.push(user);
                }
            }
        }
        for (String path : redeclare) {
            if (old.get(path).shared) {
                return "'" + path + "' shares declarations with other files";
            }
        }
        // B: R and the units whose bodies use R's symbols
        rebind = new LinkedHashSet<>(redeclare);
        for (String path : redeclare) {
            rebind.addAll(boundUsers.getOrDefault(path, List.of()));
        }
        if (rebind.size() > units.size() * maxRebuiltShare) {
            return "the edit affects " + rebind.size() + " of " + units.size() + " files";
        }
        return null;
    }

    private SemanticModel runIncremental(List<SourceUnit> units) {
        Map<String, UnitState> old = previous.states;
        global = new GlobalScope(previous.global);
        fresh = Collections.newSetFromMap(new IdentityHashMap<>());
        // declare R anew, replay everything else in unit order: the global scope and the POU list come out as a full
        // build makes them
        model = new SemanticModel(units, global, previous, true);
        model.constants = new ConstantEvaluator(model.symbols::get);
        for (SourceUnit u : units) {
            UnitState was = old.get(u.path());
            if (redeclare.contains(u.path())) {
                unit = new UnitState(u);
                fresh.add(unit);
                states.put(unit.path, unit);
                declare(u, u.declarations(), "");
            } else {
                UnitState st = rebind.contains(u.path()) ? UnitState.rebinding(was) : was;
                states.put(st.path, st);
                for (UnitState.Registration r : st.registrations) {
                    global.replay(r);
                }
                model.pous.addAll(st.pous);
                configurations.addAll(st.configurations);
                st.types.forEach(t -> declaredBy.put(t, st));
                st.gvls.forEach(g -> declaredBy.put(g, st));
                st.globals.forEach(v -> declaredBy.put(v, st));
            }
        }
        unit = null;
        List<PouSymbol> declared = new ArrayList<>();
        for (UnitState st : fresh) {
            declared.addAll(st.pous);
        }
        resolveTypes(declared);
        for (UnitState st : fresh) {
            if (!st.names().equals(old.get(st.path).names())) {
                throw new Abort("'" + st.path + "' changed the names other files see");
            }
        }
        // the previous bindings of B go; B is bound again against the new symbols
        for (String path : rebind) {
            UnitState was = old.get(path);
            was.symbolKeys.forEach(model.symbols::remove);
            was.typeKeys.forEach(model.types::remove);
            was.parameterKeys.forEach(model.parameters::remove);
            for (Statement st : was.statementKeys) {
                model.effects.remove(st);
                model.owners.remove(st);
            }
            was.pous.forEach(model.unresolvedBy::remove);
            was.globals.forEach(model.unresolvedBy::remove);
        }
        new Binder(model, states, declaredBy).bind(rebind);
        EffectsBuilder effects = new EffectsBuilder(model);
        for (String path : rebind) {
            states.get(path).pous.forEach(effects::build);
        }
        int[] stats = new int[5];
        for (UnitState st : states.values()) {
            for (int k = 0; k < stats.length; k++) {
                stats[k] += st.stats[k];
            }
        }
        model.stats = new SemanticModel.ResolutionStats(stats[0], stats[1], stats[2], stats[3], stats[4]);
        finish(rebind);
        // the units declared again have new diagnostics, the others' still hold
        for (UnitState st : states.values()) {
            st.diagnostics.forEach(sink::report);
        }
        model.buildInfo = new SemanticModel.BuildInfo(true, units.size(), redeclare.size(), rebind.size(), null);
        return model;
    }

    // ---- bookkeeping helpers ----------------------------------------------------------------------

    /** Reports a diagnostic of passes 1 and 2, kept with the unit it is about. */
    private void report(Diagnostic d) {
        if (fresh != null && (unit == null || fresh.contains(unit))) {
            if (d.code().equals(CYCLIC_DECLARATION)) {
                throw new Abort(d.message());
            }
        }
        if (fresh == null) {
            sink.report(d);
        } // an incremental build reports at the end, once it cannot give up any more
        if (unit != null) {
            unit.diagnostics.add(d);
        }
    }

    /** The current unit takes part in something only a full build redoes ({@link UnitState#shared}). */
    private void shared(String what) {
        if (fresh != null) {
            throw new Abort("'" + unit.path + "' has " + what);
        }
        unit.shared = true;
    }

    private void register(UnitState.Registration.Kind kind, String name, Object symbol) {
        if (unit != null) {
            unit.registrations.add(new UnitState.Registration(kind, PouSymbol.key(name), symbol));
        }
    }

    /** {@code found}, a symbol pass 2 looked up for the current unit: a declaration dependency if another unit's. */
    private <T extends Symbol> T depend(T found) {
        if (track && unit != null && found != null) {
            String p = pathOf(found);
            if (p != null && !p.equals(unit.path)) {
                unit.declarationDeps.add(p);
            }
        }
        return found;
    }

    /** The unit that declared {@code s}, or null (builtins, library and instruction symbols). */
    String pathOf(Symbol s) {
        return pathOf(s, declaredBy);
    }

    private static String pathOf(Symbol s, Map<Object, UnitState> declaredBy) {
        return switch (s) {
            case PouSymbol p -> p.unit() == null ? null : p.unit().path();
            case VariableSymbol v -> {
                UnitState st = declaredBy.get(v);
                yield st != null ? st.path : v.owner() == null ? null : pathOf(v.owner(), declaredBy);
            }
            case EnumValueSymbol e -> pathOf(e.enumType(), declaredBy);
            case TypeSymbol t -> {
                UnitState st = declaredBy.get(t);
                yield st == null ? null : st.path;
            }
            case GvlSymbol g -> {
                UnitState st = declaredBy.get(g);
                yield st == null ? null : st.path;
            }
            default -> null;
        };
    }

    /** The user symbols a type is made of. */
    private static void typeSymbols(Type t, java.util.function.Consumer<Symbol> out) {
        switch (t) {
            case Type.Instance i -> out.accept(i.pou());
            case Type.Struct st -> out.accept(st.symbol());
            case Type.Enum en -> out.accept(en.symbol());
            case Type.Alias a -> {
                out.accept(a.symbol());
                typeSymbols(a.target(), out);
            }
            case Type.Array a -> typeSymbols(a.element(), out);
            case Type.Pointer p -> typeSymbols(p.target(), out);
            case Type.Subrange r -> typeSymbols(r.base(), out);
            default -> {}
        }
    }

    /**
     * The units whose symbols {@code st}'s bindings in {@code m} use; worked out the first time an incremental build
     * asks (a full build would spend a second pass over every binding on it), then kept with the state.
     */
    private static Set<String> bodyDependencies(UnitState st, SemanticModel m) {
        if (st.bodyDeps != null) {
            return st.bodyDeps;
        }
        Set<String> deps = new HashSet<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.function.Consumer<Symbol> use = sym -> {
            if (seen.add(sym)) {
                String p = pathOf(sym, m.declaredBy);
                if (p != null && !p.equals(st.path)) {
                    deps.add(p);
                }
            }
        };
        for (Expression e : st.symbolKeys) {
            Symbol sym = m.symbols.get(e);
            if (sym != null) {
                use.accept(sym);
            }
        }
        for (Expression.Argument a : st.parameterKeys) {
            VariableSymbol v = m.parameters.get(a);
            if (v != null) {
                use.accept(v);
            }
        }
        // types can name another unit's symbols without a binding (E_Color#Red with an unknown value)
        for (Expression e : st.typeKeys) {
            Type t = m.types.get(e);
            if (t != null && !(t instanceof Type.Elementary)) {
                typeSymbols(t, use);
            }
        }
        st.bodyDeps = deps;
        return deps;
    }

    // ============================================================================================
    // Pass 1: declarations
    // ============================================================================================

    private void declare(SourceUnit unit, List<Declaration> declarations, String namespace) {
        for (Declaration d : declarations) {
            switch (d) {
                case Pou p -> {
                    PouSymbol s = declarePou(unit, p, null);
                    String qn = namespace + p.name().name();
                    register(UnitState.Registration.Kind.POU, qn, s);
                    reportDuplicate(global.addPou(qn, s), s, p.name());
                    if (!namespace.isEmpty()) {
                        register(UnitState.Registration.Kind.POU, p.name().name(), s);
                        global.addPou(p.name().name(), s);
                    }
                }
                case Declaration.TypeDecl t -> {
                    TypeSymbol s = declareType(t.name().name(), t, t.type(), t.pragmas(), null);
                    if (t.extendsType() != null) {
                        pendingTypes.add(pending(new StructExtends(s, t.extendsType()), null, null));
                    }
                    register(
                            UnitState.Registration.Kind.TYPE,
                            namespace + t.name().name(),
                            s);
                    reportDuplicate(global.addType(namespace + t.name().name(), s), s, t.name());
                    if (!namespace.isEmpty()) {
                        register(UnitState.Registration.Kind.TYPE, t.name().name(), s);
                        global.addType(t.name().name(), s);
                    }
                }
                case Declaration.GlobalVars g -> declareGlobals(unit, g);
                case Declaration.Namespace n -> declare(unit, n.members(), namespace + n.name() + ".");
                case Declaration.Configuration c -> {
                    // tasks and configuration globals: always rebuilt with the whole project
                    shared("a configuration");
                    configurations.add(c);
                    this.unit.configurations.add(c);
                    for (VarBlock b : c.varBlocks()) {
                        declareGlobals(unit, new Declaration.GlobalVars(null, b, b.span()));
                    }
                }
                case Declaration.Using u -> {}
                case Declaration.DataBlock db -> declareDataBlock(db);
            }
        }
    }

    private record StructExtends(TypeSymbol type, QualifiedName base) {}

    /**
     * A Siemens DB becomes one global variable: of its own STRUCT (global DB), of its FB (instance DB: a global FB
     * instance) or of its UDT (typed DB). Which of the last two {@code basedOn} names is up to type resolution.
     */
    private void declareDataBlock(Declaration.DataBlock db) {
        TypeRef layout;
        if (db.basedOn() != null) {
            layout = new TypeRef.Named(
                    db.basedOn(), List.of(), null, null, null, db.basedOn().span());
        } else if (db.layout() != null) {
            layout = db.layout();
        } else {
            List<VarDecl> members = new ArrayList<>();
            db.varBlocks().forEach(b -> members.addAll(b.variables()));
            layout = new TypeRef.StructType(members, db.span());
        }
        VarDecl decl = new VarDecl(db.name(), null, null, layout, null, false, db.pragmas(), db.span());
        // one list of all data blocks, whichever file declares them
        shared("a data block");
        boolean existed = global.hasGvl(GlobalScope.DATA_BLOCKS);
        GvlSymbol dbs = global.dataBlocks();
        if (!existed) {
            register(UnitState.Registration.Kind.GVL, GlobalScope.DATA_BLOCKS, dbs);
        }
        VariableSymbol v = new VariableSymbol(db.name().name(), decl, null, VarBlock.Section.GLOBAL, dbs, false);
        dbs.addVariable(v);
        declaredBy.put(v, unit);
        unit.globals.add(v);
        pendingTypes.add(pending(v, layout, null));
        register(UnitState.Registration.Kind.GLOBAL, v.name(), v);
        reportDuplicate(global.addGlobal(v), v, db.name());
    }

    private PouSymbol declarePou(SourceUnit unit, Pou p, PouSymbol owner) {
        PouSymbol s = new PouSymbol(p.kind(), p.name().name(), p, owner, unit, false);
        model.pous.add(s);
        this.unit.pous.add(s);
        for (VarBlock b : p.varBlocks()) {
            for (VarDecl v : b.variables()) {
                VariableSymbol vs = new VariableSymbol(v.name().name(), v, b, b.section(), s, false);
                s.addVariable(vs);
                pendingTypes.add(pending(vs, v.type(), s));
            }
        }
        boolean siemens = unit != null && unit.dialect() == Dialect.SIEMENS;
        if (siemens) {
            siemensImplicits(p, s);
        }
        boolean hasResult = p.returnType() != null
                && !(siemens && isVoid(p.returnType()))
                && (p.kind() == PouKind.FUNCTION || p.kind() == PouKind.METHOD || p.kind() == PouKind.PROPERTY);
        if (hasResult) {
            VariableSymbol result = new VariableSymbol(p.name().name(), null, null, VarBlock.Section.OUTPUT, s, true);
            s.addVariable(result);
            pendingTypes.add(pending(new ResultOf(s, result), p.returnType(), s));
        }
        for (Pou m : p.members()) {
            PouSymbol ms = declarePou(unit, m, s);
            if (s.member(m.name().name()).isPresent()) {
                reportDuplicate(s.member(m.name().name()).get(), ms, m.name());
            }
            s.addMember(ms);
        }
        return s;
    }

    private record ResultOf(PouSymbol pou, VariableSymbol result) {}

    /** {@code FUNCTION "f" : Void} has no result. */
    private static boolean isVoid(TypeRef t) {
        return t instanceof TypeRef.Named n
                && n.name().parts().size() == 1
                && n.name().last().is("Void");
    }

    /**
     * Siemens blocks: overlay views ({@code x AT y}) point at their base variable, and FBs/FCs have the implicit
     * {@code EN} input and {@code ENO} output (TIA "EN/ENO mechanism") unless they declare them.
     */
    private static void siemensImplicits(Pou p, PouSymbol s) {
        for (VarBlock b : p.varBlocks()) {
            for (VarDecl v : b.variables()) {
                if (v.overlay() != null) {
                    s.variable(v.name().name())
                            .ifPresent(view -> s.variable(v.overlay().name())
                                    .filter(base -> base != view)
                                    .ifPresent(view::setOverlayOf));
                }
            }
        }
        if (p.kind() == PouKind.FUNCTION_BLOCK || p.kind() == PouKind.FUNCTION) {
            for (String name : List.of("EN", "ENO")) {
                if (s.variable(name).isEmpty()) {
                    VarBlock.Section section = name.equals("EN") ? VarBlock.Section.INPUT : VarBlock.Section.OUTPUT;
                    VariableSymbol v = new VariableSymbol(name, null, null, section, s, false);
                    v.setType(Type.BOOL);
                    s.addVariable(v);
                }
            }
        }
    }

    private TypeSymbol declareType(String name, Node decl, TypeRef ref, List<Pragma> pragmas, PouSymbol context) {
        TypeSymbol.Kind kind = switch (ref) {
            case TypeRef.StructType st -> TypeSymbol.Kind.STRUCT;
            case TypeRef.UnionType ut -> TypeSymbol.Kind.UNION;
            case TypeRef.EnumType et -> TypeSymbol.Kind.ENUM;
            case TypeRef.Named n when n.isSubrange() -> TypeSymbol.Kind.SUBRANGE;
            default -> TypeSymbol.Kind.ALIAS;
        };
        TypeSymbol s = new TypeSymbol(name, kind, decl, ref, pragmas);
        if (unit != null) {
            declaredBy.put(s, unit);
            unit.types.add(s);
        }
        switch (ref) {
            case TypeRef.StructType st -> declareMembers(s, st.members(), context);
            case TypeRef.UnionType ut -> declareMembers(s, ut.members(), context);
            case TypeRef.EnumType et -> {
                boolean strict = s.hasAttribute("strict") || s.hasAttribute("qualified_only");
                for (TypeRef.EnumValue v : et.values()) {
                    EnumValueSymbol ev = new EnumValueSymbol(s, v);
                    s.addValue(ev);
                    if (!strict) {
                        register(UnitState.Registration.Kind.ENUM_VALUE, ev.name(), ev);
                        global.addEnumValue(ev);
                    }
                }
            }
            default -> {}
        }
        pendingTypes.add(pending(s, ref, context));
        return s;
    }

    private void declareMembers(TypeSymbol owner, List<VarDecl> members, PouSymbol context) {
        for (VarDecl v : members) {
            VariableSymbol vs = new VariableSymbol(v.name().name(), v, null, VarBlock.Section.VAR, owner, false);
            owner.addMember(vs);
            pendingTypes.add(pending(vs, v.type(), context));
        }
    }

    private void declareGlobals(SourceUnit unit, Declaration.GlobalVars g) {
        VarBlock b = g.block();
        boolean qualifiedOnly = b.pragmas().stream().anyMatch(p -> p.isAttribute("qualified_only"));
        String name = g.listName() != null ? g.listName().name() : "<" + unit.path() + ">";
        boolean existed = global.hasGvl(name);
        GvlSymbol gvl = global.gvlOrCreate(name, qualifiedOnly, g);
        if (!existed) {
            register(UnitState.Registration.Kind.GVL, name, gvl);
            declaredBy.put(gvl, this.unit);
            this.unit.gvls.add(gvl);
        } else if (declaredBy.get(gvl) != this.unit) {
            // one list declared by several files (or several lists of one name): they are declared again together
            UnitState other = declaredBy.get(gvl);
            if (fresh != null && !fresh.contains(other)) {
                throw new Abort("variable list '" + name + "' is also declared by a file not declared again");
            }
            if (track && other != null) {
                this.unit.declarationDeps.add(other.path);
                other.declarationDeps.add(this.unit.path);
            }
        }
        for (VarDecl v : b.variables()) {
            VariableSymbol vs = new VariableSymbol(v.name().name(), v, b, b.section(), gvl, false);
            gvl.addVariable(vs);
            declaredBy.put(vs, this.unit);
            this.unit.globals.add(vs);
            pendingTypes.add(pending(vs, v.type(), null));
            if (!gvl.qualifiedOnly()) {
                register(UnitState.Registration.Kind.GLOBAL, vs.name(), vs);
                Symbol previous = global.addGlobal(vs);
                if (previous != null && !(previous instanceof VariableSymbol pv && pv.owner() == gvl)) {
                    reportDuplicate(previous, vs, v.name());
                }
            }
        }
    }

    private void reportDuplicate(Symbol previous, Symbol current, Identifier name) {
        if (previous == null || previous == current) {
            return;
        }
        // the diagnostic points at the first declaration: when that file is declared again, so is this one
        depend(previous);
        List<Diagnostic.Related> related = new ArrayList<>();
        if (previous.declaration() != null) {
            related.add(new Diagnostic.Related(previous.declaration().span(), "first declared here"));
        }
        report(new Diagnostic(
                DUPLICATE, Severity.WARNING, "'" + name.name() + "' is declared more than once", name.span(), related));
    }

    // ============================================================================================
    // Pass 2: types
    // ============================================================================================

    /** Pass 2 for {@code pous} (inheritance) and the pending types (all of them: what pass 1 declared). */
    private void resolveTypes(List<PouSymbol> pous) {
        // POU inheritance first: member lookups during type resolution may need it
        for (PouSymbol p : pous) {
            unit = p.unit() == null ? null : states.get(p.unit().path());
            Pou d = p.declaration();
            for (QualifiedName q : d.extendsTypes()) {
                lookupPou(q).map(this::depend).ifPresent(base -> {
                    if (p.kind() == PouKind.INTERFACE) {
                        p.addImplements(base);
                    } else if (p.extendsPou() == null && !inheritsFrom(base, p)) {
                        p.setExtends(base);
                    } else if (inheritsFrom(base, p)) {
                        report(Diagnostic.error(
                                CYCLIC_DECLARATION,
                                "cyclic EXTENDS between '" + p.name() + "' and '" + base.name() + "'",
                                q.span()));
                    }
                });
            }
            for (QualifiedName q : d.implementsTypes()) {
                lookupPou(q).map(this::depend).ifPresent(p::addImplements);
            }
        }
        // a loop with an index: resolving may add anonymous inline types to the list
        for (int i = 0; i < pendingTypes.size(); i++) {
            PendingType t = pendingTypes.get(i);
            rockwell = t.rockwell();
            unit = t.unit();
            switch (t.target()) {
                case VariableSymbol v ->
                    v.setType(t.ref() == null ? constantType(v, t.context()) : resolve(t.ref(), t.context()));
                case ResultOf r -> {
                    Type rt = resolve(t.ref(), t.context());
                    r.pou().setReturnType(rt);
                    r.result().setType(rt);
                }
                case TypeSymbol s -> defineType(s, t.context());
                case StructExtends se -> {
                    Optional<TypeSymbol> base = lookupType(se.base()).map(this::depend);
                    base.filter(b -> b != se.type()).ifPresent(se.type()::setExtends);
                }
                default -> {}
            }
        }
        rockwell = false;
        unit = null;
    }

    /** Classic SCL {@code CONST n := 10; END_CONST} has no declared type: it follows from the value. */
    private Type constantType(VariableSymbol v, PouSymbol context) {
        if (!(v.initializer() instanceof Expression e)) {
            return Type.UNKNOWN;
        }
        return switch (model.constants.evaluate(e, x -> constantScope(x, context))) {
            case Constant.Int i -> Builtins.concrete(new Type.IntLiteral(i.value()));
            case Constant.Real r -> Type.of(ElementaryKind.LREAL);
            case Constant.Bool b -> Type.BOOL;
            case Constant.Duration d -> Type.of(ElementaryKind.TIME);
            case null ->
                e instanceof Expression.Literal l && l.kind() == Expression.LiteralKind.STRING
                        ? new Type.StringType(false, 254)
                        : Type.UNKNOWN;
        };
    }

    private static boolean inheritsFrom(PouSymbol p, PouSymbol ancestor) {
        int guard = 0;
        for (PouSymbol x = p; x != null && guard++ < 64; x = x.extendsPou()) {
            if (x == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** {@code candidate} is {@code context} or one of its owners (the FB of a method, …). */
    static boolean encloses(PouSymbol candidate, PouSymbol context) {
        for (PouSymbol c = context; c != null; c = c.owner()) {
            if (c == candidate) {
                return true;
            }
        }
        return false;
    }

    private void defineType(TypeSymbol s, PouSymbol context) {
        if (!(s.type() instanceof Type.Unknown)) {
            return; // already defined (alias chains resolve their targets eagerly)
        }
        UnitState owner = declaredBy.get(s);
        if (fresh != null && !fresh.contains(owner)) {
            return; // the previous model's: left as it was (its definition does not depend on what is rebuilt)
        }
        UnitState outer = unit;
        if (owner != null) {
            unit = owner; // an alias of another unit, defined eagerly from here: its lookups are its own
        }
        if (!resolvingTypes.add(s)) {
            report(Diagnostic.error(
                    CYCLIC_DECLARATION,
                    "type '" + s.name() + "' is defined in terms of itself",
                    s.declaration().span()));
            unit = outer;
            return;
        }
        try {
            switch (s.kind()) {
                case STRUCT, UNION -> s.setType(new Type.Struct(s));
                case ENUM -> {
                    TypeRef.EnumType et = (TypeRef.EnumType) s.typeRef();
                    if (et.baseType() != null) {
                        s.setEnumBase(resolve(et.baseType(), context));
                    }
                    long next = 0;
                    for (EnumValueSymbol v : s.values().values()) {
                        Long explicit = v.declaration().value() == null
                                ? null
                                : model.constants.integer(v.declaration().value(), e -> enumScope(s, e, context));
                        long value = explicit != null ? explicit : next;
                        v.setValue(value);
                        next = value + 1;
                    }
                    s.setType(new Type.Enum(s));
                }
                case SUBRANGE -> s.setType(resolve(s.typeRef(), context));
                case ALIAS -> s.setType(new Type.Alias(s, resolve(s.typeRef(), context)));
            }
        } finally {
            resolvingTypes.remove(s);
            unit = outer;
        }
    }

    /** Enum value initialisers may refer to earlier values of the same enum. */
    private Symbol enumScope(TypeSymbol enumType, Expression e, PouSymbol context) {
        if (e instanceof Expression.NameRef n) {
            Optional<EnumValueSymbol> own = enumType.value(n.name().name());
            if (own.isPresent()) {
                return own.get();
            }
        }
        return constantScope(e, context);
    }

    Type resolve(TypeRef ref, PouSymbol context) {
        if (ref == null) {
            return Type.UNKNOWN;
        }
        return switch (ref) {
            case TypeRef.Named n -> named(n, context);
            case TypeRef.StringType s -> {
                Long len =
                        s.length() == null ? null : model.constants.integer(s.length(), e -> constantScope(e, context));
                yield new Type.StringType(s.wide(), len != null ? len : 80);
            }
            case TypeRef.ArrayType a -> {
                List<Type.Dimension> dims = new ArrayList<>();
                for (TypeRef.Dimension d : a.dimensions()) {
                    dims.add(
                            d.isVariable()
                                    ? new Type.Dimension(null, null)
                                    : new Type.Dimension(
                                            model.constants.integer(d.low(), e -> constantScope(e, context)),
                                            model.constants.integer(d.high(), e -> constantScope(e, context))));
                }
                yield new Type.Array(dims, resolve(a.element(), context));
            }
            case TypeRef.PointerType p -> new Type.Pointer(p.kind(), resolve(p.target(), context));
            case TypeRef.StructType st -> anonymousType(ref, context);
            case TypeRef.UnionType ut -> anonymousType(ref, context);
            case TypeRef.EnumType et -> anonymousType(ref, context);
            case TypeRef.Invalid i -> Type.UNKNOWN;
        };
    }

    private Type anonymousType(TypeRef ref, PouSymbol context) {
        // numbered per unit, so a rebuild of one unit names them as a full build does
        TypeSymbol s = declareType("<anonymous " + (++unit.anonymous) + ">", ref, ref, List.of(), context);
        defineType(s, context);
        return s.type();
    }

    private Type named(TypeRef.Named n, PouSymbol context) {
        Type base = namedBase(n.name(), context);
        if (n.isSubrange() && Type.elementary(base) != null) {
            Long lo = model.constants.integer(n.rangeLow(), e -> constantScope(e, context));
            Long hi = model.constants.integer(n.rangeHigh(), e -> constantScope(e, context));
            if (lo != null && hi != null) {
                return new Type.Subrange(base, lo, hi);
            }
        }
        return base;
    }

    private Type namedBase(QualifiedName q, PouSymbol context) {
        if (rockwell && q.parts().size() == 1) {
            // TIMER, COUNTER, CONTROL, and STRING as Logix defines it (a structure with LEN and DATA)
            Optional<TypeSymbol> predefined =
                    global.rockwellTypes().lookup(q.last().name());
            if (predefined.isPresent()) {
                return predefined.get().type();
            }
        }
        if (q.parts().size() == 1) {
            Optional<ElementaryKind> k = ElementaryKind.byName(q.last().name());
            if (k.isPresent()) {
                return Type.of(k.get());
            }
            String upper = q.last().name().toUpperCase(java.util.Locale.ROOT);
            if (upper.equals("STRING") || upper.equals("WSTRING")) {
                return new Type.StringType(upper.equals("WSTRING"), 80);
            }
        }
        Optional<TypeSymbol> t = lookupType(q).map(this::depend);
        if (t.isPresent()) {
            TypeSymbol s = t.get();
            if (s.type() instanceof Type.Unknown
                    && (s.kind() == TypeSymbol.Kind.ALIAS || s.kind() == TypeSymbol.Kind.SUBRANGE)) {
                defineType(s, null); // resolve alias and subrange chains in any order
            }
            return s.kind() == TypeSymbol.Kind.STRUCT || s.kind() == TypeSymbol.Kind.UNION
                    ? new Type.Struct(s)
                    : s.kind() == TypeSymbol.Kind.ENUM ? new Type.Enum(s) : s.type();
        }
        Optional<PouSymbol> p = lookupPou(q, context).map(this::depend);
        if (p.isPresent()) {
            return new Type.Instance(p.get());
        }
        Optional<Symbol> b = global.builtin(q.last().name());
        if (b.isPresent() && b.get() instanceof PouSymbol fb) {
            return new Type.Instance(fb);
        }
        return new Type.Unknown(q.toString());
    }

    /** Full qualified name first (namespaces), then the last part (library prefixes the project does not define). */
    private Optional<TypeSymbol> lookupType(QualifiedName q) {
        Optional<TypeSymbol> t = global.type(q.toString());
        return t.isPresent() || q.parts().size() == 1 ? t : global.type(q.last().name());
    }

    private Optional<PouSymbol> lookupPou(QualifiedName q) {
        return lookupPou(q, null);
    }

    /**
     * Like {@link #lookupType}; the last-part fallback never yields a POU enclosing {@code context}: inside
     * {@code FB_BasicPid}, {@code Tc2_Utilities.FB_BasicPID} is the library block, not the FB itself.
     */
    private Optional<PouSymbol> lookupPou(QualifiedName q, PouSymbol context) {
        Optional<PouSymbol> p = global.pou(q.toString());
        if (p.isEmpty() && q.parts().size() > 1) {
            p = global.pou(q.last().name()).filter(c -> !encloses(c, context));
        }
        if (p.isEmpty()) {
            Optional<Symbol> b = global.builtin(q.last().name());
            if (b.isPresent() && b.get() instanceof PouSymbol fb) {
                return Optional.of(fb);
            }
        }
        return p;
    }

    /** Name lookup for constants during pass 2 (before bodies are bound): locals, then globals, GVL.x, E.x. */
    private Symbol constantScope(Expression e, PouSymbol context) {
        Symbol s = depend(constantSymbol(e, context));
        if (s instanceof EnumValueSymbol v && v.enumType().type() instanceof Type.Unknown) {
            // an enum value in a constant expression (ARRAY[0..E_Station.Last]): its enum may come later in pass 2
            defineType(v.enumType(), null);
        }
        return s;
    }

    private Symbol constantSymbol(Expression e, PouSymbol context) {
        if (e instanceof Expression.NameRef n) {
            String name = n.name().name();
            for (PouSymbol p = context; p != null; p = p.owner()) {
                Optional<VariableSymbol> v = p.findVariable(name);
                if (v.isPresent()) {
                    return v.get();
                }
            }
            return global.lookup(name).orElse(null);
        }
        if (e instanceof Expression.MemberAccess m && m.target() instanceof Expression.NameRef t) {
            Symbol owner = global.lookup(t.name().name()).orElse(null);
            String member = m.member().name();
            if (owner instanceof GvlSymbol g) {
                return g.variable(member).orElse(null);
            }
            if (owner instanceof TypeSymbol ts && ts.kind() == TypeSymbol.Kind.ENUM) {
                return ts.value(member).orElse(null);
            }
        }
        return null;
    }

    /**
     * Rockwell routines that run without a JSR: a program's fault routine (on a major fault) and an AOI's Prescan,
     * Postscan and EnableInFalse routines (scan modes). They become call edges so reachability and task mapping see
     * them.
     */
    private void rockwellEntryPoints() {
        for (PouSymbol p : List.copyOf(model.pous)) {
            if (p.dialect() != Dialect.ROCKWELL || p.declaration() == null) {
                continue;
            }
            if (p.kind() == PouKind.PROGRAM) {
                for (Pragma pr : p.declaration().pragmas()) {
                    if (pr.isAttribute("FaultRoutine") && pr.value() != null) {
                        p.member(pr.value())
                                .ifPresent(r -> model.callGraph.add(
                                        new CallSite(p, r, CallSite.Kind.ROUTINE, null, pr.span())));
                    }
                }
            } else if (p.kind() == PouKind.FUNCTION_BLOCK) {
                for (PouSymbol m : p.members().values()) {
                    if (m.kind() == PouKind.ROUTINE) {
                        model.callGraph.add(new CallSite(
                                p,
                                m,
                                CallSite.Kind.ROUTINE,
                                null,
                                m.declaration().name().span()));
                    }
                }
            }
        }
    }

    // ============================================================================================
    // Pass 4b: tasks and instances
    // ============================================================================================

    private void buildTasks() {
        List<TaskModel.Task> tasks = new ArrayList<>();
        for (Declaration.Configuration c : configurations) {
            collectTasks(c.elements(), tasks);
        }
        // Siemens: every OB is an entry point the operating system calls. Its event class and priority are not part of
        // SCL exports (TIA keeps them in the OB's properties), so they stay unknown rather than guessed from the name.
        for (PouSymbol p : model.pous) {
            if (p.kind() == PouKind.ORGANIZATION_BLOCK) {
                tasks.add(new TaskModel.Task(
                        p.name(), null, null, List.of(p), p.declaration().name().span()));
            }
        }
        model.taskModel = new TaskModel(tasks, model.callGraph);
    }

    private void collectTasks(List<Declaration.ConfigElement> elements, List<TaskModel.Task> out) {
        List<Declaration.Task> tasks = new ArrayList<>();
        List<Declaration.ProgramInstance> programs = new ArrayList<>();
        for (Declaration.ConfigElement e : elements) {
            switch (e) {
                case Declaration.Resource r -> collectTasks(r.elements(), out);
                case Declaration.Task t -> tasks.add(t);
                case Declaration.ProgramInstance p -> programs.add(p);
            }
        }
        for (Declaration.Task t : tasks) {
            Integer priority = null;
            Long interval = null;
            for (Expression.Argument a : t.settings()) {
                if (a.name() == null || a.value() == null) {
                    continue;
                }
                Constant c = model.constants.evaluate(a.value(), x -> null);
                if (a.name().is("PRIORITY") && c instanceof Constant.Int i) {
                    priority = (int) i.value();
                } else if (a.name().is("INTERVAL") && c instanceof Constant.Duration d) {
                    interval = d.nanos();
                }
            }
            List<PouSymbol> taskPrograms = new ArrayList<>();
            for (Declaration.ProgramInstance p : programs) {
                if (p.task() != null && p.task().is(t.name().name()) && p.type() != null) {
                    lookupPou(p.type()).ifPresent(taskPrograms::add);
                }
            }
            out.add(new TaskModel.Task(t.name().name(), priority, interval, taskPrograms, t.span()));
        }
    }

    private void buildInstances() {
        InstanceGraph g = new InstanceGraph();
        Set<PouSymbol> roots = new LinkedHashSet<>();
        if (model.taskModel.isKnown()) {
            model.taskModel.tasks().forEach(t -> roots.addAll(t.programs()));
        } else {
            model.pous.stream().filter(p -> p.kind() == PouKind.PROGRAM).forEach(roots::add);
        }
        int[] budget = {MAX_INSTANCES};
        for (PouSymbol program : roots) {
            Instance root = new Instance(program.name(), program, null, null);
            g.addRoot(root);
            expand(root, 0, budget, g);
        }
        for (GvlSymbol gvl : global.gvls()) {
            for (VariableSymbol v : gvl.variables().values()) {
                PouSymbol type = instanceType(v.type());
                if (type != null && budget[0]-- > 0) {
                    String path = (gvl.isNamed() ? gvl.name() + "." : "") + v.name() + arraySuffix(v.type());
                    Instance i = new Instance(path, type, v, null);
                    g.addRoot(i);
                    expand(i, 0, budget, g);
                }
            }
        }
        model.instanceGraph = g;
    }

    private void expand(Instance parent, int depth, int[] budget, InstanceGraph g) {
        if (depth >= MAX_INSTANCE_DEPTH) {
            g.markTruncated();
            return;
        }
        for (PouSymbol p = parent.pou(); p != null; p = p.extendsPou()) {
            for (VariableSymbol v : p.variables().values()) {
                if (v.section() == VarBlock.Section.IN_OUT
                        || v.section() == VarBlock.Section.EXTERNAL
                        || v.isResult()) {
                    continue;
                }
                PouSymbol type = instanceType(v.type());
                if (type == null) {
                    continue;
                }
                if (onPath(parent, type) || budget[0]-- <= 0) {
                    g.markTruncated();
                    continue;
                }
                Instance child = new Instance(parent.path() + "." + v.name() + arraySuffix(v.type()), type, v, parent);
                parent.addChild(child);
                expand(child, depth + 1, budget, g);
            }
        }
    }

    private static boolean onPath(Instance i, PouSymbol type) {
        for (Instance x = i; x != null; x = x.parent()) {
            if (x.pou() == type) {
                return true;
            }
        }
        return false;
    }

    /** The FB/class type held by value (directly or as array element); interfaces and pointers hold no instance. */
    private static PouSymbol instanceType(Type t) {
        Type r = t.resolved();
        if (r instanceof Type.Array a) {
            r = a.element().resolved();
        }
        if (r instanceof Type.Instance i
                && (i.pou().kind() == PouKind.FUNCTION_BLOCK || i.pou().kind() == PouKind.CLASS)) {
            return i.pou();
        }
        return null;
    }

    private static String arraySuffix(Type t) {
        return t.resolved() instanceof Type.Array ? "[*]" : "";
    }

    private static Span firstSpan(List<SourceUnit> units) {
        for (SourceUnit u : units) {
            if (!u.declarations().isEmpty()) {
                return Span.at(u.declarations().getFirst().span().textId(), 0);
            }
        }
        return Span.at(0, 0);
    }
}
