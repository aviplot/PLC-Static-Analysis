package dev.plca.semantic;

import dev.plca.ast.Initializer;
import dev.plca.ast.Pragma;
import dev.plca.ast.VarBlock;
import dev.plca.ast.VarDecl;
import java.util.List;

/**
 * A declared variable: POU local/parameter, GVL entry, struct/union member, or the implicit result variable of a
 * function/method/property (named like the POU, {@link #isResult()}).
 */
public final class VariableSymbol implements Symbol {

    private final String name;
    private final VarDecl declaration;
    private final VarBlock.Section section;
    private final VarBlock block;
    private final Symbol owner;
    private final boolean result;
    private Type type = Type.UNKNOWN;
    private VariableSymbol overlayOf;
    private String overlayPath = "";
    private IoTagSymbol ioAlias;

    VariableSymbol(
            String name, VarDecl declaration, VarBlock block, VarBlock.Section section, Symbol owner, boolean result) {
        this.name = name;
        this.declaration = declaration;
        this.block = block;
        this.section = section;
        this.owner = owner;
        this.result = result;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public VarDecl declaration() {
        return declaration;
    }

    public VarBlock.Section section() {
        return section;
    }

    /** The VAR block (null for struct members, results and builtin parameters). */
    public VarBlock block() {
        return block;
    }

    /** PouSymbol, GvlSymbol or TypeSymbol (struct/union). */
    public Symbol owner() {
        return owner;
    }

    public boolean isResult() {
        return result;
    }

    public Type type() {
        return type;
    }

    public Initializer initializer() {
        return declaration == null ? null : declaration.init();
    }

    public List<Pragma> pragmas() {
        return declaration == null ? List.of() : declaration.pragmas();
    }

    public boolean is(VarBlock.Qualifier q) {
        return block != null && block.is(q);
    }

    public boolean isConstant() {
        return is(VarBlock.Qualifier.CONSTANT);
    }

    /** {@code AT %…} address, or null. */
    public String address() {
        return declaration == null ? null : declaration.address();
    }

    public boolean isGlobal() {
        return owner instanceof GvlSymbol || section == VarBlock.Section.GLOBAL;
    }

    void setType(Type t) {
        this.type = t;
    }

    /** The variable a Siemens {@code name AT other : T;} view overlays (accesses through the view hit it), or null. */
    public VariableSymbol overlayOf() {
        return overlayOf;
    }

    void setOverlayOf(VariableSymbol base) {
        this.overlayOf = base;
    }

    /**
     * Inside {@link #overlayOf()}: the selector a Rockwell alias adds ({@code [3].Run}, {@code .5} for {@code AliasFor
     * = "Arr[3].Run"}); {@code ""} for whole-variable overlays.
     */
    public String overlayPath() {
        return overlayPath;
    }

    void setOverlay(VariableSymbol base, String path) {
        this.overlayOf = base;
        this.overlayPath = path;
    }

    /** The module I/O tag a Rockwell alias names ({@code AliasFor = "Local:2:O.Data.0"}), or null. */
    public IoTagSymbol ioAlias() {
        return ioAlias;
    }

    void setIoAlias(IoTagSymbol io) {
        this.ioAlias = io;
    }

    @Override
    public String toString() {
        String o = owner instanceof PouSymbol p ? p.qualifiedName() : owner.name();
        return o + "." + name;
    }
}
