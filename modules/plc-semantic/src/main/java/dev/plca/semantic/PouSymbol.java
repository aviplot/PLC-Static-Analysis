package dev.plca.semantic;

import dev.plca.ast.Declaration.Modifier;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.SourceUnit;
import dev.plca.core.Dialect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A POU or a member of one (method, property, property accessor, action). Builtin function blocks (TON, …) are
 * PouSymbols with {@link #isBuiltin()} and no declaration.
 */
public final class PouSymbol implements Symbol {

    private final PouKind kind;
    private final String name;
    private final Pou declaration;
    private final PouSymbol owner;
    private final SourceUnit unit;
    private final boolean builtin;
    private final Map<String, VariableSymbol> variables = new LinkedHashMap<>();
    private final Map<String, PouSymbol> members = new LinkedHashMap<>();
    private final List<PouSymbol> implementsList = new ArrayList<>();
    private PouSymbol extendsPou;
    private Type returnType = Type.VOID;

    PouSymbol(PouKind kind, String name, Pou declaration, PouSymbol owner, SourceUnit unit, boolean builtin) {
        this.kind = kind;
        this.name = name;
        this.declaration = declaration;
        this.owner = owner;
        this.unit = unit;
        this.builtin = builtin;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Pou declaration() {
        return declaration;
    }

    public PouKind kind() {
        return kind;
    }

    /** The FB/class/interface/property a member belongs to; null for top-level POUs. */
    public PouSymbol owner() {
        return owner;
    }

    /** The source unit (file) this POU comes from; null for builtins. */
    public SourceUnit unit() {
        return unit;
    }

    public Dialect dialect() {
        return unit == null ? null : unit.dialect();
    }

    public boolean isBuiltin() {
        return builtin;
    }

    /** {@code Owner.Member} for members, otherwise the name. */
    public String qualifiedName() {
        return owner == null ? name : owner.qualifiedName() + "." + name;
    }

    public boolean has(Modifier m) {
        return declaration != null && declaration.has(m);
    }

    /** Variables in declaration order (excluding inherited ones). */
    public Map<String, VariableSymbol> variables() {
        return Collections.unmodifiableMap(variables);
    }

    public Optional<VariableSymbol> variable(String name) {
        return Optional.ofNullable(variables.get(key(name)));
    }

    /** Methods, properties, actions; for properties the GET/SET accessors. */
    public Map<String, PouSymbol> members() {
        return Collections.unmodifiableMap(members);
    }

    public Optional<PouSymbol> member(String name) {
        return Optional.ofNullable(members.get(key(name)));
    }

    public PouSymbol extendsPou() {
        return extendsPou;
    }

    public List<PouSymbol> implementsList() {
        return Collections.unmodifiableList(implementsList);
    }

    /** Result type of functions and methods, type of properties; VOID otherwise. */
    public Type returnType() {
        return returnType;
    }

    /** Looks up a variable here and along the EXTENDS chain. */
    public Optional<VariableSymbol> findVariable(String name) {
        for (PouSymbol p = this; p != null; p = p.extendsPou) {
            VariableSymbol v = p.variables.get(key(name));
            if (v != null) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /** Looks up a member here, along the EXTENDS chain and (for interfaces) the extended interfaces. */
    public Optional<PouSymbol> findMember(String name) {
        return findMember(key(name), 0);
    }

    private Optional<PouSymbol> findMember(String key, int depth) {
        if (depth > 64) {
            return Optional.empty();
        }
        PouSymbol m = members.get(key);
        if (m != null) {
            return Optional.of(m);
        }
        if (extendsPou != null) {
            Optional<PouSymbol> r = extendsPou.findMember(key, depth + 1);
            if (r.isPresent()) {
                return r;
            }
        }
        for (PouSymbol i : implementsList) {
            Optional<PouSymbol> r = i.findMember(key, depth + 1);
            if (r.isPresent()) {
                return r;
            }
        }
        return Optional.empty();
    }

    /** True for kinds whose instances hold state (FB, class, program). */
    public boolean isInstantiable() {
        return kind == PouKind.FUNCTION_BLOCK || kind == PouKind.CLASS || kind == PouKind.PROGRAM;
    }

    /** The FB/class/program whose instance variables a body sees (itself, or the owner of a member). */
    public PouSymbol instanceOwner() {
        PouSymbol p = this;
        while (p != null && !p.isInstantiable() && p.kind() != PouKind.INTERFACE) {
            p = p.owner;
        }
        return p;
    }

    void addVariable(VariableSymbol v) {
        variables.putIfAbsent(key(v.name()), v);
    }

    void addMember(PouSymbol m) {
        members.putIfAbsent(key(m.name()), m);
    }

    void setExtends(PouSymbol p) {
        this.extendsPou = p;
    }

    void addImplements(PouSymbol p) {
        implementsList.add(p);
    }

    void setReturnType(Type t) {
        this.returnType = t;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return kind + " " + qualifiedName();
    }
}
