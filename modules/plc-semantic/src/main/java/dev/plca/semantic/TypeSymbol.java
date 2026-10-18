package dev.plca.semantic;

import dev.plca.ast.Node;
import dev.plca.ast.Pragma;
import dev.plca.ast.TypeRef;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A user-defined type: STRUCT, UNION, ENUM, alias or subrange (named in a TYPE block, or anonymous inline). */
public final class TypeSymbol implements Symbol {

    public enum Kind {
        STRUCT,
        UNION,
        ENUM,
        ALIAS,
        SUBRANGE
    }

    private final String name;
    private final Kind kind;
    private final Node declaration;
    private final TypeRef typeRef;
    private final List<Pragma> pragmas;
    private final Map<String, VariableSymbol> members = new LinkedHashMap<>();
    private final Map<String, EnumValueSymbol> values = new LinkedHashMap<>();
    private TypeSymbol extendsType;
    private Type type = Type.UNKNOWN;

    TypeSymbol(String name, Kind kind, Node declaration, TypeRef typeRef, List<Pragma> pragmas) {
        this.name = name;
        this.kind = kind;
        this.declaration = declaration;
        this.typeRef = typeRef;
        this.pragmas = List.copyOf(pragmas);
    }

    @Override
    public String name() {
        return name;
    }

    /** The TypeDecl, or the TypeRef of an anonymous inline type. */
    @Override
    public Node declaration() {
        return declaration;
    }

    public Kind kind() {
        return kind;
    }

    TypeRef typeRef() {
        return typeRef;
    }

    public List<Pragma> pragmas() {
        return pragmas;
    }

    public boolean hasAttribute(String attribute) {
        return pragmas.stream().anyMatch(p -> p.isAttribute(attribute));
    }

    /** The type this symbol defines (an Alias/Subrange/Enum/Struct view). */
    public Type type() {
        return type;
    }

    /** Base type of an enum ({@code INT} when not declared) or target of an alias. */
    public Type baseType() {
        return type instanceof Type.Alias a ? a.target() : enumBase;
    }

    public TypeSymbol extendsType() {
        return extendsType;
    }

    public Map<String, VariableSymbol> members() {
        return Collections.unmodifiableMap(members);
    }

    public Optional<VariableSymbol> findMember(String name) {
        for (TypeSymbol t = this; t != null; t = t.extendsType) {
            VariableSymbol v = t.members.get(PouSymbol.key(name));
            if (v != null) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public Map<String, EnumValueSymbol> values() {
        return Collections.unmodifiableMap(values);
    }

    public Optional<EnumValueSymbol> value(String name) {
        return Optional.ofNullable(values.get(PouSymbol.key(name)));
    }

    private Type enumBase = Type.of(ElementaryKind.INT);

    void setEnumBase(Type t) {
        this.enumBase = t;
    }

    void addMember(VariableSymbol v) {
        members.putIfAbsent(PouSymbol.key(v.name()), v);
    }

    void addValue(EnumValueSymbol v) {
        values.putIfAbsent(PouSymbol.key(v.name()), v);
    }

    void setExtends(TypeSymbol t) {
        this.extendsType = t;
    }

    void setType(Type t) {
        this.type = t;
    }

    @Override
    public String toString() {
        return kind + " " + name;
    }
}
