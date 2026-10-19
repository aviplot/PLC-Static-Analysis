package dev.plca.frontend.st.kit;

import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import java.util.function.Function;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.ParseCancellationException;

/**
 * Two-stage parse of one text (design rule 1), for any dialect's lexer/parser pair. Stage 1: SLL prediction with
 * {@link BailErrorStrategy} — fast, and exact for any input SLL can decide. On failure, stage 2 rewinds and re-parses
 * with full LL and {@link RecoveryStrategy}, reporting diagnostics. A failure in stage 1 that parses cleanly in stage 2
 * is an <em>SLL fallback</em> — a sign of grammar ambiguity, tracked by the corpus check.
 *
 * <p>Not thread-safe. Keep one instance per thread: lexer and parser objects are reused, while ANTLR's DFA cache is
 * shared (and thread-safe) across all instances.
 */
public final class TwoStageParser<P extends Parser> {

    /** Parse tree plus the tokens (incl. hidden channels) the AST builder needs. */
    public record Parsed(ParserRuleContext tree, CommonTokenStream tokens, boolean fallback, int syntaxErrors) {}

    private final Lexer lexer;
    private final P parser;
    private final CoreTokens coreTokens;
    private final IntervalSet sync;
    private final BailErrorStrategy bail = new BailErrorStrategy();
    private final boolean sllFirst;
    private long parses;
    private long fallbacks;
    private long cleanFallbacks;

    /**
     * @param sync the dialect's recovery terminators (see {@link RecoveryStrategy})
     * @param sllFirst {@code false} skips stage 1 (always full LL): only for benchmarking the two-stage gain
     */
    public TwoStageParser(Lexer lexer, P parser, CoreTokens coreTokens, IntervalSet sync, boolean sllFirst) {
        this.lexer = lexer;
        this.parser = parser;
        this.coreTokens = coreTokens;
        this.sync = sync;
        this.sllFirst = sllFirst;
        lexer.removeErrorListeners();
    }

    /** Lexes the whole text; hidden-channel tokens stay in the stream. */
    public CommonTokenStream lex(SourceText text) {
        lexer.setInputStream(new Utf16CharStream(text.text(), text.file().path()));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens;
    }

    /** Parses {@code tokens} (from {@link #lex}) with the entry rule {@code entry}. */
    public Parsed parse(
            SourceText text, CommonTokenStream tokens, Function<P, ParserRuleContext> entry, DiagnosticSink sink) {
        parses++;
        if (sllFirst) {
            parser.setTokenStream(tokens);
            parser.removeErrorListeners();
            parser.setErrorHandler(bail);
            parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
            try {
                return new Parsed(entry.apply(parser), tokens, false, 0);
            } catch (ParseCancellationException sllFailed) {
                fallbacks++;
            }
        }

        tokens.seek(0);
        parser.setTokenStream(tokens);
        parser.removeErrorListeners();
        SyntaxErrorListener listener = new SyntaxErrorListener(text, sink);
        parser.addErrorListener(listener);
        parser.setErrorHandler(new RecoveryStrategy(sync, coreTokens));
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        ParserRuleContext tree = entry.apply(parser);
        if (listener.count() == 0) {
            cleanFallbacks++;
        }
        return new Parsed(tree, tokens, true, listener.count());
    }

    public long parses() {
        return parses;
    }

    /** Texts where SLL failed (syntax errors or SLL weakness). */
    public long fallbacks() {
        return fallbacks;
    }

    /** Texts where SLL failed but LL found no error: the grammar-ambiguity signal. */
    public long cleanFallbacks() {
        return cleanFallbacks;
    }
}
