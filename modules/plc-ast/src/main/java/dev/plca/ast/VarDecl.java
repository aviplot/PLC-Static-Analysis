package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/**
 * One declared variable. {@code a, b : INT;} yields two {@code VarDecl}s sharing type and span. {@code address} is the
 * {@code AT %…} location (null when absent); {@code accessPath} is the instance path of VAR_CONFIG / VAR_ACCESS
 * entries. {@code init} may be null; {@code referenceInit} marks CODESYS {@code r : REFERENCE TO T REF= x;}.
 */
public record VarDecl(
        Identifier name,
        QualifiedName accessPath,
        String address,
        TypeRef type,
        Initializer init,
        boolean referenceInit,
        List<Pragma> pragmas,
        Span span)
        implements Node {

    public VarDecl {
        pragmas = List.copyOf(pragmas);
    }
}
