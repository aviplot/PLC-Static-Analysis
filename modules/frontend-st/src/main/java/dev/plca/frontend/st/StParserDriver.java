package dev.plca.frontend.st;

import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.grammar.CodesysStLexer;
import dev.plca.grammar.CodesysStParser;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.ParseCancellationException;

/**
 * Two-stage parse of one text (design rule 1). Stage 1: SLL prediction with {@link BailErrorStrategy} — fast, and exact
 * for any input SLL can decide. On failure, stage 2 rewinds and re-parses with full LL and {@link StErrorStrategy},
 * reporting diagnostics. A failure in stage 1 that parses cleanly in stage 2 is an <em>SLL fallback</em> — a sign of
 * grammar ambiguity, tracked by the corpus check.
 *
 * <p>Not thread-safe. Keep one instance per thread: lexer and parser objects are reused, while ANTLR's DFA cache is
 * shared (and thread-safe) across all instances.
 */
final class StParserDriver {

    enum Entry {
        COMPILATION_UNIT,
        DECLARATION_PART,
        STATEMENTS,
        /** COMPILATION_UNIT if the text starts with a library element keyword, else STATEMENTS. */
        AUTO
    }

    private static final IntervalSet LIBRARY_START = IntervalSet.of(Token.EOF);

    static {
        int[] starts = {
            CodesysStLexer.PROGRAM, CodesysStLexer.FUNCTION, CodesysStLexer.FUNCTION_BLOCK, CodesysStLexer.CLASS,
            CodesysStLexer.INTERFACE, CodesysStLexer.METHOD, CodesysStLexer.PROPERTY, CodesysStLexer.ACTION,
            CodesysStLexer.TYPE, CodesysStLexer.NAMESPACE, CodesysStLexer.USING, CodesysStLexer.CONFIGURATION,
            CodesysStLexer.VAR, CodesysStLexer.VAR_INPUT, CodesysStLexer.VAR_OUTPUT, CodesysStLexer.VAR_IN_OUT,
            CodesysStLexer.VAR_GLOBAL, CodesysStLexer.VAR_EXTERNAL, CodesysStLexer.VAR_TEMP, CodesysStLexer.VAR_STAT,
            CodesysStLexer.VAR_INST, CodesysStLexer.VAR_ACCESS, CodesysStLexer.VAR_CONFIG,
        };
        for (int t : starts) {
            LIBRARY_START.add(t);
        }
        LIBRARY_START.setReadonly(true);
    }

    /** Parse tree plus the tokens (incl. hidden channels) the AST builder needs. */
    record Parsed(ParserRuleContext tree, CommonTokenStream tokens, boolean fallback, int syntaxErrors) {}

    private final CodesysStLexer lexer = new CodesysStLexer(null);
    private final CodesysStParser parser = new CodesysStParser(null);
    private final BailErrorStrategy bail = new BailErrorStrategy();

    private final boolean sllFirst;
    private long parses;
    private long fallbacks;
    private long cleanFallbacks;

    StParserDriver() {
        this(true);
    }

    /** {@code sllFirst = false} skips stage 1 (always full LL): only for benchmarking the two-stage gain. */
    StParserDriver(boolean sllFirst) {
        this.sllFirst = sllFirst;
        lexer.removeErrorListeners();
    }

    Parsed parse(SourceText text, Entry entry, DiagnosticSink sink) {
        parses++;
        lexer.setInputStream(new Utf16CharStream(text.text(), text.file().path()));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        if (entry == Entry.AUTO) {
            entry = LIBRARY_START.contains(tokens.LA(1)) ? Entry.COMPILATION_UNIT : Entry.STATEMENTS;
        }

        if (sllFirst) {
            parser.setTokenStream(tokens);
            parser.removeErrorListeners();
            parser.setErrorHandler(bail);
            parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
            try {
                return new Parsed(invoke(entry), tokens, false, 0);
            } catch (ParseCancellationException sllFailed) {
                fallbacks++;
            }
        }

        tokens.seek(0);
        parser.setTokenStream(tokens);
        SyntaxErrorListener listener = new SyntaxErrorListener(text, sink);
        parser.addErrorListener(listener);
        parser.setErrorHandler(new StErrorStrategy());
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        ParserRuleContext tree = invoke(entry);
        if (listener.count() == 0) {
            cleanFallbacks++;
        }
        return new Parsed(tree, tokens, true, listener.count());
    }

    private ParserRuleContext invoke(Entry entry) {
        return switch (entry) {
            case COMPILATION_UNIT -> parser.compilationUnit();
            case DECLARATION_PART -> parser.declarationPart();
            case STATEMENTS, AUTO -> parser.statementsOnly();
        };
    }

    long parses() {
        return parses;
    }

    /** Texts where SLL failed (syntax errors or SLL weakness). */
    long fallbacks() {
        return fallbacks;
    }

    /** Texts where SLL failed but LL found no error: the grammar-ambiguity signal. */
    long cleanFallbacks() {
        return cleanFallbacks;
    }
}
