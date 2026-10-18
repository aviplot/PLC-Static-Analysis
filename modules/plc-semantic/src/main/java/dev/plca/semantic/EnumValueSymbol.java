package dev.plca.semantic;

import dev.plca.ast.TypeRef;

/** One value of an enumeration; {@code value} is its numeric value (explicit or implied). */
public final class EnumValueSymbol implements Symbol {

    private final TypeSymbol enumType;
    private final TypeRef.EnumValue declaration;
    private long value;

    EnumValueSymbol(TypeSymbol enumType, TypeRef.EnumValue declaration) {
        this.enumType = enumType;
        this.declaration = declaration;
    }

    @Override
    public String name() {
        return declaration.name().name();
    }

    @Override
    public TypeRef.EnumValue declaration() {
        return declaration;
    }

    public TypeSymbol enumType() {
        return enumType;
    }

    public long value() {
        return value;
    }

    void setValue(long v) {
        this.value = v;
    }

    @Override
    public String toString() {
        return enumType.name() + "." + name();
    }
}
