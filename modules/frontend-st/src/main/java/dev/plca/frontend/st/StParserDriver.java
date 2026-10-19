package dev.plca.frontend.st;

import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.frontend.st.kit.TwoStageParser;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.CodesysStLexer;
import dev.plca.grammar.CodesysStParser;
import java.util.function.Function;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * The CODESYS lexer/parser pair behind a {@link TwoStageParser}: entry rules and the recovery terminators of the
 * dialect.
 *
 * <p>Not thread-safe. Keep one instance per thread.
 */
final class StParserDriver {

    enum Entry {
        COMPILATION_UNIT,
        DECLARATION_PART,
        STATEMENTS,
        /** COMPILATION_UNIT if the text starts with a library element keyword, else STATEMENTS. */
        AUTO
    }

    /** Tokens that start a library element; an empty text (EOF) is a compilation unit too. */
    private static final IntervalSet LIBRARY_START = withEof(AstBuilder.TOKENS.set(
            "PROGRAM",
            "FUNCTION",
            "FUNCTION_BLOCK",
            "CLASS",
            "INTERFACE",
            "METHOD",
            "PROPERTY",
            "ACTION",
            "TYPE",
            "NAMESPACE",
            "USING",
            "CONFIGURATION",
            "VAR",
            "VAR_INPUT",
            "VAR_OUTPUT",
            "VAR_IN_OUT",
            "VAR_GLOBAL",
            "VAR_EXTERNAL",
            "VAR_TEMP",
            "VAR_STAT",
            "VAR_INST",
            "VAR_ACCESS",
            "VAR_CONFIG"));

    /** {@code ;} and every block terminator (see {@link dev.plca.frontend.st.kit.RecoveryStrategy}). */
    static final IntervalSet SYNC = AstBuilder.TOKENS.set(
            "SEMI",
            "END_IF",
            "END_CASE",
            "END_FOR",
            "END_WHILE",
            "END_REPEAT",
            "END_VAR",
            "END_STRUCT",
            "END_UNION",
            "END_TYPE",
            "END_FUNCTION_BLOCK",
            "END_FUNCTION",
            "END_PROGRAM",
            "END_METHOD",
            "END_PROPERTY",
            "END_GET",
            "END_SET",
            "END_ACTION",
            "END_INTERFACE",
            "END_CLASS",
            "END_NAMESPACE",
            "ENDTRY");

    private final TwoStageParser<CodesysStParser> parser;

    StParserDriver() {
        this(true);
    }

    /** {@code sllFirst = false} skips stage 1 (always full LL): only for benchmarking the two-stage gain. */
    StParserDriver(boolean sllFirst) {
        parser = new TwoStageParser<>(
                new CodesysStLexer(null), new CodesysStParser(null), AstBuilder.TOKENS, SYNC, sllFirst);
    }

    Parsed parse(SourceText text, Entry entry, DiagnosticSink sink) {
        CommonTokenStream tokens = parser.lex(text);
        if (entry == Entry.AUTO) {
            entry = LIBRARY_START.contains(tokens.LA(1)) ? Entry.COMPILATION_UNIT : Entry.STATEMENTS;
        }
        Function<CodesysStParser, ParserRuleContext> rule = switch (entry) {
            case COMPILATION_UNIT -> CodesysStParser::compilationUnit;
            case DECLARATION_PART -> CodesysStParser::declarationPart;
            case STATEMENTS, AUTO -> CodesysStParser::statementsOnly;
        };
        return parser.parse(text, tokens, rule, sink);
    }

    private static IntervalSet withEof(IntervalSet set) {
        IntervalSet s = new IntervalSet(set);
        s.add(Token.EOF);
        s.setReadonly(true);
        return s;
    }
}
