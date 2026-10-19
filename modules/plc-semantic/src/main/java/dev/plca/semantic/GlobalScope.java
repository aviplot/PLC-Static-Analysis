package dev.plca.semantic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Project-wide names: POUs, types, global variable lists and their variables, enum values, builtins. */
public final class GlobalScope {

    private final Map<String, PouSymbol> pous = new LinkedHashMap<>();
    private final Map<String, TypeSymbol> types = new LinkedHashMap<>();
    private final Map<String, GvlSymbol> gvls = new LinkedHashMap<>();
    private final Map<String, VariableSymbol> globals = new HashMap<>();
    private final Map<String, List<EnumValueSymbol>> enumValues = new HashMap<>();
    private final Builtins builtins = new Builtins();

    public Collection<PouSymbol> pous() {
        return Collections.unmodifiableCollection(pous.values());
    }

    public Collection<TypeSymbol> types() {
        return Collections.unmodifiableCollection(types.values());
    }

    public Collection<GvlSymbol> gvls() {
        return Collections.unmodifiableCollection(gvls.values());
    }

    public Optional<PouSymbol> pou(String name) {
        return Optional.ofNullable(pous.get(PouSymbol.key(name)));
    }

    public Optional<TypeSymbol> type(String name) {
        return Optional.ofNullable(types.get(PouSymbol.key(name)));
    }

    public Optional<GvlSymbol> gvl(String name) {
        return Optional.ofNullable(gvls.get(PouSymbol.key(name)));
    }

    /** A global variable reachable without qualification. */
    public Optional<VariableSymbol> globalVariable(String name) {
        return Optional.ofNullable(globals.get(PouSymbol.key(name)));
    }

    /** Standard FB/function by name ({@code TON}, {@code INT_TO_REAL}). */
    public Optional<Symbol> builtin(String name) {
        return builtins.lookup(name);
    }

    /**
     * Unqualified lookup in CODESYS order: global variables, POUs, types, GVL names, unique non-strict enum values,
     * builtins.
     */
    public Optional<Symbol> lookup(String name) {
        String k = PouSymbol.key(name);
        Symbol s = globals.get(k);
        if (s == null) {
            s = pous.get(k);
        }
        if (s == null) {
            s = types.get(k);
        }
        if (s == null) {
            s = gvls.get(k);
        }
        if (s == null) {
            List<EnumValueSymbol> vs = enumValues.get(k);
            if (vs != null && vs.size() == 1) {
                s = vs.getFirst();
            }
        }
        if (s != null) {
            return Optional.of(s);
        }
        return builtins.lookup(name);
    }

    /** Previous declaration with the same name, or null. */
    Symbol addPou(String qualifiedName, PouSymbol p) {
        return pous.putIfAbsent(PouSymbol.key(qualifiedName), p);
    }

    Symbol addType(String qualifiedName, TypeSymbol t) {
        return types.putIfAbsent(PouSymbol.key(qualifiedName), t);
    }

    /** The list holding one global variable per Siemens data block. */
    GvlSymbol dataBlocks() {
        return gvls.computeIfAbsent(PouSymbol.key(DATA_BLOCKS), k -> new GvlSymbol(DATA_BLOCKS, false, null, true));
    }

    static final String DATA_BLOCKS = "<data blocks>";

    GvlSymbol gvlOrCreate(String name, boolean qualifiedOnly, dev.plca.ast.Node declaration) {
        return gvls.computeIfAbsent(PouSymbol.key(name), k -> new GvlSymbol(name, qualifiedOnly, declaration));
    }

    Symbol addGlobal(VariableSymbol v) {
        return globals.putIfAbsent(PouSymbol.key(v.name()), v);
    }

    void addEnumValue(EnumValueSymbol v) {
        enumValues
                .computeIfAbsent(PouSymbol.key(v.name()), k -> new ArrayList<>())
                .add(v);
    }
}
