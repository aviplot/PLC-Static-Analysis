package dev.plca.frontend.scl;

import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Identifier;
import dev.plca.ast.Pragma;
import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.SyntaxCodes;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.SiemensSclParser.CompilationUnitContext;
import dev.plca.grammar.SiemensSclParser.StatementsOnlyContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Entry point of the Siemens SCL frontend: TIA Portal external sources ({@code .scl} with one or more blocks,
 * {@code .db}, {@code .udt}) to one {@link SourceUnit} of dialect {@link Dialect#SIEMENS}. Never throws for bad input
 * (design rule 2).
 *
 * <p>Not thread-safe: use one instance per thread (it reuses lexer and parser objects).
 */
public final class SclFrontend implements Frontend {

    /** Every extension this frontend reads (lower case, with dot). */
    public static final Set<String> EXTENSIONS = Set.of(".scl", ".db", ".udt");

    private final SclParserDriver driver;

    public SclFrontend() {
        this(true);
    }

    /** {@code twoStage = false} parses with full LL only; results are identical (for benchmarks). */
    public SclFrontend(boolean twoStage) {
        this.driver = new SclParserDriver(twoStage);
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public Result parse(SourceFile file, SourceRegistry registry, DiagnosticSink sink) {
        List<Declaration> declarations = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        List<Pragma> pragmas = new ArrayList<>();
        Stats stats = Stats.NONE;
        SourceText text = registry.register(file);
        if (!looksLikeScl(file)) {
            sink.report(Diagnostic.of(
                    SclCodes.NOT_SCL,
                    Severity.INFO,
                    "'" + file.path() + "' is not a Siemens data block source; skipped",
                    Span.at(text.id(), 0)));
            return new Result(new SourceUnit(file.path(), Dialect.SIEMENS, declarations, comments, pragmas), stats);
        }
        try {
            Parsed p = driver.parse(text, sink);
            stats = new Stats(1, p.fallback() ? 1 : 0, p.cleanFallback() ? 1 : 0, p.syntaxErrors());
            SclAstBuilder b = new SclAstBuilder(text, p.tokens(), sink);
            if (p.tree() instanceof CompilationUnitContext cu) {
                declarations.addAll(b.compilationUnit(cu));
            } else {
                declarations.add(snippet(file, text, b.statementsOnly((StatementsOnlyContext) p.tree())));
            }
            b.collectHidden(comments, pragmas);
        } catch (StackOverflowError e) {
            internal(text, sink, "input is nested too deeply to analyse");
        } catch (RuntimeException e) {
            internal(text, sink, "internal error while parsing: " + e);
        }
        return new Result(new SourceUnit(file.path(), Dialect.SIEMENS, declarations, comments, pragmas), stats);
    }

    /**
     * {@code .db} is also the extension of SQLite databases and Windows' {@code Thumbs.db}: such a file is parsed only
     * if it declares a data block. {@code .scl}/{@code .udt} are always parsed.
     */
    private static boolean looksLikeScl(SourceFile file) {
        if (!Frontend.extension(file.path()).equals(".db")) {
            return true;
        }
        String head = file.text().substring(0, Math.min(file.text().length(), 8192));
        return DATA_BLOCK.matcher(head).find();
    }

    private static final java.util.regex.Pattern DATA_BLOCK = java.util.regex.Pattern.compile("(?i)\\bDATA_BLOCK\\b");

    /** A bare statement list: a nameless body named after the file, as in the ST frontend. */
    private static Pou snippet(SourceFile file, SourceText text, List<Statement> body) {
        return new Pou(
                PouKind.ACTION,
                new Identifier(baseName(file.path()), Span.at(text.id(), 0)),
                Set.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                body,
                "SCL",
                List.of(),
                text.span(0, text.text().length()));
    }

    private static void internal(SourceText text, DiagnosticSink sink, String message) {
        sink.report(Diagnostic.of(SyntaxCodes.INTERNAL, Severity.ERROR, message, Span.at(text.id(), 0)));
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }
}
