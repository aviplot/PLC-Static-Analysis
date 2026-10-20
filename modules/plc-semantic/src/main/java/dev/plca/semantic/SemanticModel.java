package dev.plca.semantic;

import dev.plca.ast.Expression;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The semantic model of a project (design rule 4: one model for all languages). Built by {@link SemanticBuilder};
 * immutable afterwards and safe to share between rule threads.
 *
 * <p>Side tables are keyed by node identity: AST records compare by value, and identity maps avoid hashing whole
 * subtrees.
 */
public final class SemanticModel {

    /** How many name occurrences in bodies were bound, and how the rest split. */
    /**
     * Name-resolution counts. {@code library}: names of libraries or types the project does not contain;
     * {@code external}: Siemens {@code "quoted"} names the project does not define (PLC tags from tag tables, blocks
     * outside the export). Both count as resolved for {@link #rate()}: the model cannot know them, but they are not
     * errors.
     */
    public record ResolutionStats(int names, int resolved, int library, int unresolved, int external) {
        public static final ResolutionStats NONE = new ResolutionStats(0, 0, 0, 0, 0);

        public double rate() {
            return names == 0 ? 1.0 : (resolved + library + external) / (double) names;
        }
    }

    /**
     * How a model was built. {@code incremental}: from the previous model, declaring {@code declared} and binding
     * {@code bound} of {@code units} units again; otherwise a full build, with {@code reason} saying why when an
     * incremental one was asked for.
     */
    public record BuildInfo(boolean incremental, int units, int declared, int bound, String reason) {
        static BuildInfo full(int units, String reason) {
            return new BuildInfo(false, units, units, units, reason);
        }
    }

    final List<SourceUnit> units;
    final GlobalScope global;
    final List<PouSymbol> pous = new ArrayList<>();
    final Map<Expression, Symbol> symbols;
    final Map<Expression, Type> types;
    final Map<Expression.Argument, VariableSymbol> parameters;
    final Map<Statement, Effects> effects;
    final Map<Statement, PouSymbol> owners;
    final List<Expression> unresolved = new ArrayList<>();
    /** Unresolved names per binding owner (a POU or member, a global variable), assembled into {@link #unresolved}. */
    final Map<Object, List<Expression>> unresolvedBy;
    /** Per unit, in unit order, when built for incremental rebuilds; null otherwise. */
    Map<String, UnitState> states;
    /** With {@link #states}: which unit declared each type, variable list and global variable. */
    Map<Object, UnitState> declaredBy;

    BuildInfo buildInfo;
    final CallGraph callGraph = new CallGraph();
    InstanceGraph instanceGraph = new InstanceGraph();
    TaskModel taskModel;
    ResolutionStats stats = ResolutionStats.NONE;
    ConstantEvaluator constants;

    SemanticModel(List<SourceUnit> units, GlobalScope global) {
        this(units, global, null);
    }

    /**
     * Side tables pre-sized from {@code like} (a model of nearly the same units, e.g. before an edit): growing identity
     * maps by rehashing is a large part of the build time of big models.
     */
    SemanticModel(List<SourceUnit> units, GlobalScope global, SemanticModel like) {
        this.units = List.copyOf(units);
        this.global = global;
        this.symbols = new IdentityHashMap<>(like == null ? 32 : like.symbols.size());
        this.types = new IdentityHashMap<>(like == null ? 32 : like.types.size());
        this.parameters = new IdentityHashMap<>(like == null ? 32 : like.parameters.size());
        this.effects = new IdentityHashMap<>(like == null ? 32 : like.effects.size());
        this.owners = new IdentityHashMap<>(like == null ? 32 : like.owners.size());
        this.unresolvedBy = new IdentityHashMap<>();
    }

    /** A model starting from copies of {@code previous}'s side tables (an incremental build). */
    @SuppressWarnings("unchecked")
    SemanticModel(List<SourceUnit> units, GlobalScope global, SemanticModel previous, boolean copyTables) {
        this.units = List.copyOf(units);
        this.global = global;
        this.symbols = (Map<Expression, Symbol>) ((IdentityHashMap<Expression, Symbol>) previous.symbols).clone();
        this.types = (Map<Expression, Type>) ((IdentityHashMap<Expression, Type>) previous.types).clone();
        this.parameters = (Map<Expression.Argument, VariableSymbol>)
                ((IdentityHashMap<Expression.Argument, VariableSymbol>) previous.parameters).clone();
        this.effects = (Map<Statement, Effects>) ((IdentityHashMap<Statement, Effects>) previous.effects).clone();
        this.owners = (Map<Statement, PouSymbol>) ((IdentityHashMap<Statement, PouSymbol>) previous.owners).clone();
        this.unresolvedBy = new IdentityHashMap<>(previous.unresolvedBy);
    }

    public List<SourceUnit> units() {
        return units;
    }

    public GlobalScope global() {
        return global;
    }

    /** Every user POU and member (methods, properties, accessors, actions), parents before members. */
    public List<PouSymbol> pous() {
        return Collections.unmodifiableList(pous);
    }

    /** What a name, member access or call callee refers to; empty if unresolved. */
    public Optional<Symbol> symbol(Expression e) {
        return Optional.ofNullable(symbols.get(e));
    }

    /** Every bound expression with its symbol (names, member accesses, callees), in no particular order. */
    public void forEachBinding(BiConsumer<Expression, Symbol> action) {
        symbols.forEach(action);
    }

    /** Every argument bound to a callee parameter, in no particular order. */
    public void forEachParameterBinding(BiConsumer<Expression.Argument, VariableSymbol> action) {
        parameters.forEach(action);
    }

    /** Static type of an expression; {@link Type.Unknown} when it could not be determined. */
    public Type type(Expression e) {
        Type t = types.get(e);
        return t == null ? Type.UNKNOWN : t;
    }

    /** The callee parameter a named or positional argument binds to. */
    public Optional<VariableSymbol> parameter(Expression.Argument a) {
        return Optional.ofNullable(parameters.get(a));
    }

    /** Compile-time value of an expression (literals, constants, enum values, folded operators). */
    public Optional<Constant> constant(Expression e) {
        return Optional.ofNullable(constants.evaluate(e));
    }

    /** The statement's own accesses and calls (conditions for IF/WHILE/…, not their bodies). */
    public Effects effects(Statement s) {
        Effects e = effects.get(s);
        return e == null ? Effects.NONE : e;
    }

    /** Effects of a statement including all nested statements. */
    public Effects deepEffects(Statement s) {
        List<dev.plca.semantic.Access> accesses = new ArrayList<>();
        List<CallSite> calls = new ArrayList<>();
        Statements.forEachDeep(s, st -> {
            Effects e = effects(st);
            accesses.addAll(e.accesses());
            calls.addAll(e.calls());
        });
        return new Effects(accesses, calls);
    }

    /** The POU (or member) whose body contains the statement. */
    public Optional<PouSymbol> owner(Statement s) {
        return Optional.ofNullable(owners.get(s));
    }

    public CallGraph callGraph() {
        return callGraph;
    }

    public InstanceGraph instanceGraph() {
        return instanceGraph;
    }

    public TaskModel tasks() {
        return taskModel;
    }

    public ResolutionStats resolutionStats() {
        return stats;
    }

    /** Whether this model was built incrementally, and how much of it was redone. */
    public BuildInfo buildInfo() {
        return buildInfo;
    }

    /** Collects the unresolved names of every owner in binding order: POUs in order, then global variables. */
    void assembleUnresolved() {
        unresolved.clear();
        for (PouSymbol p : pous) {
            List<Expression> l = unresolvedBy.get(p);
            if (l != null) {
                unresolved.addAll(l);
            }
        }
        for (GvlSymbol g : global.gvls()) {
            for (VariableSymbol v : g.variables().values()) {
                List<Expression> l = unresolvedBy.get(v);
                if (l != null) {
                    unresolved.addAll(l);
                }
            }
        }
    }

    /** Names (NameRef) and member accesses that could not be bound, in body order. */
    public List<Expression> unresolved() {
        return Collections.unmodifiableList(unresolved);
    }
}
