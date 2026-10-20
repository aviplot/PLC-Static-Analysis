package dev.plca.lsp;

import dev.plca.analysis.Snapshot;
import dev.plca.analysis.nav.Outline;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.LineIndex;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.rules.Finding;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Between plca and LSP: paths and URIs, spans and ranges, diagnostics, outline items. Offsets are UTF-16 code units on
 * both sides (LSP's default position encoding), so only lines and columns change (1-based here, 0-based there).
 */
final class Convert {

    static final String SOURCE = "plca";

    private Convert() {}

    /** The file of a {@code file:} URI, or null for other schemes ({@code untitled:}, {@code git:}). */
    static Path path(String uri) {
        try {
            URI u = URI.create(uri);
            return "file".equalsIgnoreCase(u.getScheme()) ? Path.of(u) : null;
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    static Range range(Snapshot s, Span span) {
        return range(s.registry(), span);
    }

    static Range range(SourceRegistry registry, Span span) {
        Location l = registry.resolve(span);
        return new Range(
                new Position(l.startLine() - 1, l.startColumn() - 1), new Position(l.endLine() - 1, l.endColumn() - 1));
    }

    static org.eclipse.lsp4j.Location location(Uris uris, Snapshot s, Span span) {
        return new org.eclipse.lsp4j.Location(uris.uri(s.root(), s.pathOf(span)), range(s, span));
    }

    /** The file offset of an LSP position, clamped to the text (clients may send positions past a line's end). */
    static int offset(LineIndex lines, int textLength, Position p) {
        if (p.getLine() >= lines.lineCount()) {
            return textLength;
        }
        int start = lines.lineStart(p.getLine() + 1);
        int next = p.getLine() + 1 < lines.lineCount() ? lines.lineStart(p.getLine() + 2) : textLength;
        return Math.max(start, Math.min(start + p.getCharacter(), next));
    }

    static DiagnosticSeverity severity(Severity s) {
        return switch (s) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case INFO -> DiagnosticSeverity.Information;
            case HINT -> DiagnosticSeverity.Hint;
        };
    }

    static Diagnostic diagnostic(Uris uris, Snapshot s, dev.plca.core.diag.Diagnostic d) {
        Diagnostic out =
                new Diagnostic(range(s, d.span()), uris.mask(d.message()), severity(d.severity()), SOURCE, d.code());
        related(uris, s, d.related(), out);
        return out;
    }

    static Diagnostic diagnostic(Uris uris, Snapshot s, Finding f) {
        String message = f.place() == null ? f.message() : f.message() + " (" + f.place() + ")";
        Diagnostic out = new Diagnostic(range(s, f.span()), message, severity(f.severity()), SOURCE, f.ruleId());
        related(uris, s, f.related(), out);
        return out;
    }

    private static void related(
            Uris uris, Snapshot s, List<dev.plca.core.diag.Diagnostic.Related> related, Diagnostic out) {
        if (related.isEmpty()) {
            return;
        }
        List<DiagnosticRelatedInformation> info = new ArrayList<>();
        for (dev.plca.core.diag.Diagnostic.Related r : related) {
            info.add(new DiagnosticRelatedInformation(location(uris, s, r.span()), r.message()));
        }
        out.setRelatedInformation(info);
    }

    static DocumentSymbol symbol(Snapshot s, Outline.Item item) {
        Range selection = range(s, item.selection());
        // an XML export's declaration and name can lie in different fragments: the range must contain the selection
        Range range = cover(range(s, item.range()), selection);
        List<DocumentSymbol> children = new ArrayList<>();
        for (Outline.Item c : item.children()) {
            DocumentSymbol child = symbol(s, c);
            range = cover(range, child.getRange());
            children.add(child);
        }
        DocumentSymbol d = new DocumentSymbol(item.name(), kind(item.kind()), range, selection, item.detail());
        d.setChildren(children);
        return d;
    }

    static WorkspaceSymbol symbol(Uris uris, Snapshot s, Outline.Symbol symbol) {
        WorkspaceSymbol w = new WorkspaceSymbol(
                symbol.name(), kind(symbol.kind()), Either.forLeft(location(uris, s, symbol.selection())));
        w.setContainerName(symbol.container());
        return w;
    }

    static Range cover(Range a, Range b) {
        Position start = before(a.getStart(), b.getStart()) ? a.getStart() : b.getStart();
        Position end = before(a.getEnd(), b.getEnd()) ? b.getEnd() : a.getEnd();
        return new Range(start, end);
    }

    private static boolean before(Position a, Position b) {
        return a.getLine() < b.getLine() || (a.getLine() == b.getLine() && a.getCharacter() <= b.getCharacter());
    }

    static SymbolKind kind(Outline.Kind k) {
        return switch (k) {
            case PROGRAM -> SymbolKind.Module;
            case FUNCTION_BLOCK, CLASS -> SymbolKind.Class;
            case FUNCTION, ROUTINE -> SymbolKind.Function;
            case INTERFACE -> SymbolKind.Interface;
            case METHOD, ACTION -> SymbolKind.Method;
            case PROPERTY -> SymbolKind.Property;
            case VARIABLE -> SymbolKind.Variable;
            case CONSTANT -> SymbolKind.Constant;
            case STRUCT -> SymbolKind.Struct;
            case ENUM -> SymbolKind.Enum;
            case ENUM_VALUE -> SymbolKind.EnumMember;
            case TYPE, DATA_BLOCK -> SymbolKind.Object;
            case NAMESPACE, VARIABLE_LIST -> SymbolKind.Namespace;
            case CONFIGURATION -> SymbolKind.Package;
            case TASK -> SymbolKind.Event;
        };
    }
}
