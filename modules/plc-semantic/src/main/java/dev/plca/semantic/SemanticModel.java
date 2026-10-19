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

    final List<SourceUnit> units;
    final GlobalScope global;
    final List<PouSymbol> pous = new ArrayList<>();
    final Map<Expression, Symbol> symbols = new IdentityHashMap<>();
    final Map<Expression, Type> types = new IdentityHashMap<>();
    final Map<Expression.Argument, VariableSymbol> parameters = new IdentityHashMap<>();
    final Map<Statement, Effects> effects = new IdentityHashMap<>();
    final Map<Statement, PouSymbol> owners = new IdentityHashMap<>();
    final List<Expression> unresolved = new ArrayList<>();
    final CallGraph callGraph = new CallGraph();
    InstanceGraph instanceGraph = new InstanceGraph();
    TaskModel taskModel;
    ResolutionStats stats = ResolutionStats.NONE;
    ConstantEvaluator constants;

    SemanticModel(List<SourceUnit> units, GlobalScope global) {
        this.units = List.copyOf(units);
        this.global = global;
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

    /** Names (NameRef) and member accesses that could not be bound, in body order. */
    public List<Expression> unresolved() {
        return Collections.unmodifiableList(unresolved);
    }
}
