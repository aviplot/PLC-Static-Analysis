package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;
import java.util.Set;

/** A {@code VAR_xxx … END_VAR} section. */
public record VarBlock(
        Section section, Set<Qualifier> qualifiers, List<VarDecl> variables, List<Pragma> pragmas, Span span)
        implements Node {

    public enum Section {
        VAR,
        INPUT,
        OUTPUT,
        IN_OUT,
        GLOBAL,
        EXTERNAL,
        TEMP,
        STAT,
        INST,
        ACCESS,
        CONFIG
    }

    /** Storage qualifiers, plus the IEC Ed. 3 access specifiers of {@code VAR PUBLIC} etc. */
    public enum Qualifier {
        CONSTANT,
        RETAIN,
        NON_RETAIN,
        PERSISTENT,
        PUBLIC,
        PRIVATE,
        PROTECTED,
        INTERNAL,
        /** Siemens {@code VAR DB_SPECIFIC}: values kept per instance DB (verify exact semantics). */
        DB_SPECIFIC
    }

    public VarBlock {
        qualifiers = Set.copyOf(qualifiers);
        variables = List.copyOf(variables);
        pragmas = List.copyOf(pragmas);
    }

    public boolean is(Qualifier q) {
        return qualifiers.contains(q);
    }
}
