package dev.plca.semantic;

import dev.plca.ast.Node;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A global variable list. TwinCAT GVLs have a name ({@code GVL_IO}); VAR_GLOBAL blocks in plain text files are
 * collected in an unnamed list per file. {@code qualifiedOnly} lists ({@code {attribute 'qualified_only'}}) are only
 * reachable as {@code GVL.var}.
 */
public final class GvlSymbol implements Symbol {

    private final String name;
    private final boolean qualifiedOnly;
    private final Node declaration;
    private final Map<String, VariableSymbol> variables = new LinkedHashMap<>();

    GvlSymbol(String name, boolean qualifiedOnly, Node declaration) {
        this.name = name;
        this.qualifiedOnly = qualifiedOnly;
        this.declaration = declaration;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Node declaration() {
        return declaration;
    }

    public boolean isNamed() {
        return !name.startsWith("<");
    }

    public boolean qualifiedOnly() {
        return qualifiedOnly;
    }

    public Map<String, VariableSymbol> variables() {
        return Collections.unmodifiableMap(variables);
    }

    public Optional<VariableSymbol> variable(String name) {
        return Optional.ofNullable(variables.get(PouSymbol.key(name)));
    }

    void addVariable(VariableSymbol v) {
        variables.putIfAbsent(PouSymbol.key(v.name()), v);
    }

    @Override
    public String toString() {
        return "GVL " + name;
    }
}
