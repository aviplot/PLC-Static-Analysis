package dev.plca.semantic;

import dev.plca.ast.Declaration;
import dev.plca.ast.Expression;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What one unit contributed to a model built for incremental rebuilds (M10): its declarations (symbols, global
 * registrations to replay), the side-table keys its binding wrote (to drop them exactly), and what it depends on, by
 * path: <em>declaration</em> dependencies (its symbols' types, bases and constants use another unit's symbols) and
 * <em>body</em> dependencies (its bodies and initialisers bind another unit's symbols).
 *
 * <p>Written only while its model is built; read-only once the model is published, so later builds share the states of
 * units they do not touch.
 */
final class UnitState {

    /** A name the unit put into the global scope, replayed in the same order by later builds. */
    record Registration(Kind kind, String key, Object symbol) {
        enum Kind {
            POU,
            TYPE,
            GVL,
            GLOBAL,
            ENUM_VALUE
        }
    }

    final SourceUnit unit;
    final String path;

    // ---- declarations (passes 1 and 2) ----
    final List<PouSymbol> pous = new ArrayList<>();
    final List<Registration> registrations = new ArrayList<>();
    final List<TypeSymbol> types = new ArrayList<>();
    final List<GvlSymbol> gvls = new ArrayList<>();
    /** Variables of global variable lists and data blocks the unit declared. */
    final List<VariableSymbol> globals = new ArrayList<>();

    final List<Declaration.Configuration> configurations = new ArrayList<>();
    /** Diagnostics of passes 1 and 2 about this unit. */
    final List<Diagnostic> diagnostics = new ArrayList<>();

    final Set<String> declarationDeps = new HashSet<>();
    /**
     * The unit takes part in something a rebuild of it and its dependents could not redo: the list of all data blocks,
     * a configuration.
     */
    boolean shared;

    int anonymous;

    // ---- binding (passes 3 and 4) ----
    final List<Expression> symbolKeys = new ArrayList<>();
    final List<Expression> typeKeys = new ArrayList<>();
    final List<Expression.Argument> parameterKeys = new ArrayList<>();
    final List<Statement> statementKeys = new ArrayList<>();
    /** Worked out when first needed ({@code SemanticBuilder.bodyDependencies}); null until then. */
    Set<String> bodyDeps;
    /** names, resolved, library, unresolved, external. */
    final int[] stats = new int[5];

    UnitState(SourceUnit unit) {
        this.unit = unit;
        this.path = unit.path();
    }

    /** A state for re-binding {@code old}'s unit: its declarations kept, its binding to be redone. */
    static UnitState rebinding(UnitState old) {
        UnitState s = new UnitState(old.unit);
        s.pous.addAll(old.pous);
        s.registrations.addAll(old.registrations);
        s.types.addAll(old.types);
        s.gvls.addAll(old.gvls);
        s.globals.addAll(old.globals);
        s.configurations.addAll(old.configurations);
        s.diagnostics.addAll(old.diagnostics);
        s.declarationDeps.addAll(old.declarationDeps);
        s.shared = old.shared;
        s.anonymous = old.anonymous;
        return s;
    }

    /** Global names, for telling whether an edit changed what other units can see. */
    List<String> names() {
        List<String> out = new ArrayList<>(registrations.size());
        for (Registration r : registrations) {
            out.add(r.kind() + ":" + r.key());
        }
        return out;
    }
}
