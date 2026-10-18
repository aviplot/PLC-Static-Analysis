package dev.plca.ast;

import dev.plca.core.Dialect;
import java.util.List;

/**
 * Everything parsed from one physical file. A TwinCAT file yields one unit built from several text fragments, so the
 * unit has no single span; its nodes' spans resolve through the {@code SourceRegistry}.
 */
public record SourceUnit(
        String path, Dialect dialect, List<Declaration> declarations, List<Comment> comments, List<Pragma> pragmas) {

    public SourceUnit {
        declarations = List.copyOf(declarations);
        comments = List.copyOf(comments);
        pragmas = List.copyOf(pragmas);
    }
}
