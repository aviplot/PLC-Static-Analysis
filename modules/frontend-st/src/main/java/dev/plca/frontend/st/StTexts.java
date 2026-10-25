package dev.plca.frontend.st;

import dev.plca.ast.Comment;
import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Modifier;
import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.Expression;
import dev.plca.ast.Identifier;
import dev.plca.ast.Pragma;
import dev.plca.ast.QualifiedName;
import dev.plca.ast.Statement;
import dev.plca.ast.TypeRef;
import dev.plca.ast.VarBlock;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.CodesysStParser.DeclarationPartContext;
import dev.plca.grammar.CodesysStParser.ExpressionOnlyContext;
import dev.plca.grammar.CodesysStParser.StatementsOnlyContext;
import java.util.List;
import java.util.Set;

/**
 * CODESYS Structured Text pieces embedded in other formats (PLCopen XML bodies, declarations, initial values, graphical
 * variables): parses one text with the CODESYS grammar and builds its AST. Comments and pragmas go to the caller's
 * lists; parse statistics accumulate in {@link #stats()}.
 *
 * <p>Not thread-safe. Keep one instance per thread.
 */
public final class StTexts {

    /** The header of a POU or member declaration ({@code FUNCTION_BLOCK X EXTENDS Y …}); null parts were absent. */
    public record Header(
            PouKind kind,
            Identifier name,
            Set<Modifier> modifiers,
            TypeRef type,
            List<QualifiedName> extendsTypes,
            List<QualifiedName> implementsTypes,
            List<Pragma> pragmas) {}

    /** A declaration text: optional header, VAR blocks and TYPE declarations. */
    public record Declarations(
            Header header,
            Set<Modifier> modifiers,
            List<VarBlock> varBlocks,
            List<Declaration.TypeDecl> types,
            Span span) {}

    private final StParserDriver driver;
    private Frontend.Stats stats = Frontend.Stats.NONE;

    public StTexts(boolean twoStage) {
        this.driver = new StParserDriver(twoStage);
    }

    /** A statement list (a POU body). */
    public List<Statement> statements(
            SourceText text, DiagnosticSink sink, List<Comment> comments, List<Pragma> pragmas) {
        Parsed p = parse(text, StParserDriver.Entry.STATEMENTS, sink);
        AstBuilder b = new AstBuilder(text, p.tokens(), sink);
        List<Statement> body = b.statementsOnly((StatementsOnlyContext) p.tree());
        b.collectHidden(comments, pragmas);
        return body;
    }

    /** A declaration part: header and/or VAR blocks and TYPE blocks (CODESYS "interface as plain text"). */
    public Declarations declarations(
            SourceText text, DiagnosticSink sink, List<Comment> comments, List<Pragma> pragmas) {
        Parsed p = parse(text, StParserDriver.Entry.DECLARATION_PART, sink);
        AstBuilder b = new AstBuilder(text, p.tokens(), sink);
        AstBuilder.DeclarationPart d = b.declarationPart((DeclarationPartContext) p.tree());
        b.collectHidden(comments, pragmas);
        AstBuilder.Header h = d.header();
        Header header = h == null
                ? null
                : new Header(
                        h.kind(),
                        h.name(),
                        h.modifiers(),
                        h.type(),
                        h.extendsTypes(),
                        h.implementsTypes(),
                        h.pragmas());
        return new Declarations(header, d.modifiers(), d.varBlocks(), d.types(), d.span());
    }

    /** A lone expression (initial value, graphical variable, inline condition). */
    public Expression expression(SourceText text, DiagnosticSink sink) {
        Parsed p = parse(text, StParserDriver.Entry.EXPRESSION, sink);
        return new AstBuilder(text, p.tokens(), sink).expressionOnly((ExpressionOnlyContext) p.tree());
    }

    /** Statistics of every text parsed so far. */
    public Frontend.Stats stats() {
        return stats;
    }

    private Parsed parse(SourceText text, StParserDriver.Entry entry, DiagnosticSink sink) {
        Parsed p = driver.parse(text, entry, sink);
        stats = stats.plus(new Frontend.Stats(1, p.fallback() ? 1 : 0, p.cleanFallback() ? 1 : 0, p.syntaxErrors()));
        return p;
    }
}
