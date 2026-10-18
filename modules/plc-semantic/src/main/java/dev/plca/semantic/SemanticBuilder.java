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
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Span;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    static final int MAX_INSTANCES = 200_000;

    private final DiagnosticSink sink;
    private SemanticModel model;
    private GlobalScope global;
    private final List<Declaration.Configuration> configurations = new ArrayList<>();
    private final Set<TypeSymbol> resolvingTypes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<PendingType> pendingTypes = new ArrayList<>();
    private int anonymous;

    /** Something whose TypeRef is resolved in pass 2, in the context of a POU (for local constants). */
    private record PendingType(Object target, TypeRef ref, PouSymbol context) {}

    private SemanticBuilder(DiagnosticSink sink) {
        this.sink = sink;
    }

    public static SemanticModel build(List<SourceUnit> units, DiagnosticSink sink) {
        SemanticBuilder b = new SemanticBuilder(sink);
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
        model = new SemanticModel(units, global);
        model.constants = new ConstantEvaluator(model.symbols::get);

        for (SourceUnit u : units) {
            declare(u, u.declarations(), "");
        }
        resolveTypes();
        new Binder(model, sink).bindAll();
        new EffectsBuilder(model).buildAll();
        buildTasks();
        buildInstances();
        return model;
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
                    reportDuplicate(global.addPou(qn, s), s, p.name());
                    if (!namespace.isEmpty()) {
                        global.addPou(p.name().name(), s);
                    }
                }
                case Declaration.TypeDecl t -> {
                    TypeSymbol s = declareType(t.name().name(), t, t.type(), t.pragmas(), null);
                    if (t.extendsType() != null) {
                        pendingTypes.add(new PendingType(new StructExtends(s, t.extendsType()), null, null));
                    }
                    reportDuplicate(global.addType(namespace + t.name().name(), s), s, t.name());
                    if (!namespace.isEmpty()) {
                        global.addType(t.name().name(), s);
                    }
                }
                case Declaration.GlobalVars g -> declareGlobals(unit, g);
                case Declaration.Namespace n -> declare(unit, n.members(), namespace + n.name() + ".");
                case Declaration.Configuration c -> {
                    configurations.add(c);
                    for (VarBlock b : c.varBlocks()) {
                        declareGlobals(unit, new Declaration.GlobalVars(null, b, b.span()));
                    }
                }
                case Declaration.Using u -> {}
            }
        }
    }

    private record StructExtends(TypeSymbol type, QualifiedName base) {}

    private PouSymbol declarePou(SourceUnit unit, Pou p, PouSymbol owner) {
        PouSymbol s = new PouSymbol(p.kind(), p.name().name(), p, owner, unit, false);
        model.pous.add(s);
        for (VarBlock b : p.varBlocks()) {
            for (VarDecl v : b.variables()) {
                VariableSymbol vs = new VariableSymbol(v.name().name(), v, b, b.section(), s, false);
                s.addVariable(vs);
                pendingTypes.add(new PendingType(vs, v.type(), s));
            }
        }
        boolean hasResult = p.returnType() != null
                && (p.kind() == PouKind.FUNCTION || p.kind() == PouKind.METHOD || p.kind() == PouKind.PROPERTY);
        if (hasResult) {
            VariableSymbol result = new VariableSymbol(p.name().name(), null, null, VarBlock.Section.OUTPUT, s, true);
            s.addVariable(result);
            pendingTypes.add(new PendingType(new ResultOf(s, result), p.returnType(), s));
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

    private TypeSymbol declareType(String name, Node decl, TypeRef ref, List<Pragma> pragmas, PouSymbol context) {
        TypeSymbol.Kind kind = switch (ref) {
            case TypeRef.StructType st -> TypeSymbol.Kind.STRUCT;
            case TypeRef.UnionType ut -> TypeSymbol.Kind.UNION;
            case TypeRef.EnumType et -> TypeSymbol.Kind.ENUM;
            case TypeRef.Named n when n.isSubrange() -> TypeSymbol.Kind.SUBRANGE;
            default -> TypeSymbol.Kind.ALIAS;
        };
        TypeSymbol s = new TypeSymbol(name, kind, decl, ref, pragmas);
        switch (ref) {
            case TypeRef.StructType st -> declareMembers(s, st.members(), context);
            case TypeRef.UnionType ut -> declareMembers(s, ut.members(), context);
            case TypeRef.EnumType et -> {
                boolean strict = s.hasAttribute("strict") || s.hasAttribute("qualified_only");
                for (TypeRef.EnumValue v : et.values()) {
                    EnumValueSymbol ev = new EnumValueSymbol(s, v);
                    s.addValue(ev);
                    if (!strict) {
                        global.addEnumValue(ev);
                    }
                }
            }
            default -> {}
        }
        pendingTypes.add(new PendingType(s, ref, context));
        return s;
    }

    private void declareMembers(TypeSymbol owner, List<VarDecl> members, PouSymbol context) {
        for (VarDecl v : members) {
            VariableSymbol vs = new VariableSymbol(v.name().name(), v, null, VarBlock.Section.VAR, owner, false);
            owner.addMember(vs);
            pendingTypes.add(new PendingType(vs, v.type(), context));
        }
    }

    private void declareGlobals(SourceUnit unit, Declaration.GlobalVars g) {
        VarBlock b = g.block();
        boolean qualifiedOnly = b.pragmas().stream().anyMatch(p -> p.isAttribute("qualified_only"));
        String name = g.listName() != null ? g.listName().name() : "<" + unit.path() + ">";
        GvlSymbol gvl = global.gvlOrCreate(name, qualifiedOnly, g);
        for (VarDecl v : b.variables()) {
            VariableSymbol vs = new VariableSymbol(v.name().name(), v, b, b.section(), gvl, false);
            gvl.addVariable(vs);
            pendingTypes.add(new PendingType(vs, v.type(), null));
            if (!gvl.qualifiedOnly()) {
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
        List<Diagnostic.Related> related = new ArrayList<>();
        if (previous.declaration() != null) {
            related.add(new Diagnostic.Related(previous.declaration().span(), "first declared here"));
        }
        sink.report(new Diagnostic(
                DUPLICATE, Severity.WARNING, "'" + name.name() + "' is declared more than once", name.span(), related));
    }

    // ============================================================================================
    // Pass 2: types
    // ============================================================================================

    private void resolveTypes() {
        // POU inheritance first: member lookups during type resolution may need it
        for (PouSymbol p : model.pous) {
            Pou d = p.declaration();
            for (QualifiedName q : d.extendsTypes()) {
                lookupPou(q).ifPresent(base -> {
                    if (p.kind() == PouKind.INTERFACE) {
                        p.addImplements(base);
                    } else if (p.extendsPou() == null && !inheritsFrom(base, p)) {
                        p.setExtends(base);
                    } else if (inheritsFrom(base, p)) {
                        sink.report(Diagnostic.error(
                                CYCLIC_DECLARATION,
                                "cyclic EXTENDS between '" + p.name() + "' and '" + base.name() + "'",
                                q.span()));
                    }
                });
            }
            for (QualifiedName q : d.implementsTypes()) {
                lookupPou(q).ifPresent(p::addImplements);
            }
        }
        // a loop with an index: resolving may add anonymous inline types to the list
        for (int i = 0; i < pendingTypes.size(); i++) {
            PendingType t = pendingTypes.get(i);
            switch (t.target()) {
                case VariableSymbol v -> v.setType(resolve(t.ref(), t.context()));
                case ResultOf r -> {
                    Type rt = resolve(t.ref(), t.context());
                    r.pou().setReturnType(rt);
                    r.result().setType(rt);
                }
                case TypeSymbol s -> defineType(s, t.context());
                case StructExtends se -> {
                    Optional<TypeSymbol> base = lookupType(se.base());
                    base.filter(b -> b != se.type()).ifPresent(se.type()::setExtends);
                }
                default -> {}
            }
        }
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

    private void defineType(TypeSymbol s, PouSymbol context) {
        if (!(s.type() instanceof Type.Unknown)) {
            return; // already defined (alias chains resolve their targets eagerly)
        }
        if (!resolvingTypes.add(s)) {
            sink.report(Diagnostic.error(
                    CYCLIC_DECLARATION,
                    "type '" + s.name() + "' is defined in terms of itself",
                    s.declaration().span()));
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
        TypeSymbol s = declareType("<anonymous " + (++anonymous) + ">", ref, ref, List.of(), context);
        defineType(s, context);
        return s.type();
    }

    private Type named(TypeRef.Named n, PouSymbol context) {
        Type base = namedBase(n.name());
        if (n.isSubrange() && Type.elementary(base) != null) {
            Long lo = model.constants.integer(n.rangeLow(), e -> constantScope(e, context));
            Long hi = model.constants.integer(n.rangeHigh(), e -> constantScope(e, context));
            if (lo != null && hi != null) {
                return new Type.Subrange(base, lo, hi);
            }
        }
        return base;
    }

    private Type namedBase(QualifiedName q) {
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
        Optional<TypeSymbol> t = lookupType(q);
        if (t.isPresent()) {
            TypeSymbol s = t.get();
            if (s.type() instanceof Type.Unknown && s.kind() == TypeSymbol.Kind.ALIAS) {
                defineType(s, null); // resolve alias chains in any order
            }
            return s.kind() == TypeSymbol.Kind.STRUCT || s.kind() == TypeSymbol.Kind.UNION
                    ? new Type.Struct(s)
                    : s.kind() == TypeSymbol.Kind.ENUM ? new Type.Enum(s) : s.type();
        }
        Optional<PouSymbol> p = lookupPou(q);
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
        Optional<PouSymbol> p = global.pou(q.toString());
        if (p.isEmpty() && q.parts().size() > 1) {
            p = global.pou(q.last().name());
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

    // ============================================================================================
    // Pass 4b: tasks and instances
    // ============================================================================================

    private void buildTasks() {
        List<TaskModel.Task> tasks = new ArrayList<>();
        for (Declaration.Configuration c : configurations) {
            collectTasks(c.elements(), tasks);
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
