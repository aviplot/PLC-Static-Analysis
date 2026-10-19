package dev.plca.frontend.scl;

import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.frontend.st.kit.TwoStageParser;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.SiemensSclLexer;
import dev.plca.grammar.SiemensSclParser;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * The SCL lexer/parser pair behind a {@link TwoStageParser}: entry rule and the recovery terminators of the dialect.
 *
 * <p>Not thread-safe. Keep one instance per thread.
 */
final class SclParserDriver {

    /** Tokens that start a block; an empty text (EOF) is a compilation unit too. */
    private static final IntervalSet BLOCK_START = withEof(SclAstBuilder.TOKENS.set(
            "ORGANIZATION_BLOCK", "FUNCTION_BLOCK", "FUNCTION", "DATA_BLOCK", "TYPE", "NAMESPACE"));

    /**
     * {@code ;}, block terminators, {@code BEGIN}, and the keywords that end a statement header ({@code THEN},
     * {@code DO}, {@code OF}), so a broken condition never swallows its keyword (see
     * {@link dev.plca.frontend.st.kit.RecoveryStrategy}).
     */
    static final IntervalSet SYNC = SclAstBuilder.TOKENS.set(
            "SEMI",
            "BEGIN",
            "THEN",
            "DO",
            "OF",
            "END_IF",
            "END_CASE",
            "END_FOR",
            "END_WHILE",
            "END_REPEAT",
            "END_REGION",
            "END_VAR",
            "END_CONST",
            "END_LABEL",
            "END_STRUCT",
            "END_TYPE",
            "END_FUNCTION_BLOCK",
            "END_FUNCTION",
            "END_ORGANIZATION_BLOCK",
            "END_DATA_BLOCK",
            "END_NAMESPACE");

    private final TwoStageParser<SiemensSclParser> parser;

    SclParserDriver(boolean sllFirst) {
        parser = new TwoStageParser<>(
                new SiemensSclLexer(null), new SiemensSclParser(null), SclAstBuilder.TOKENS, SYNC, sllFirst);
    }

    /**
     * A full source ({@code compilationUnit}), or a bare statement list when the text does not start with a block
     * keyword (snippets, generated examples).
     */
    Parsed parse(SourceText text, DiagnosticSink sink) {
        CommonTokenStream tokens = parser.lex(text);
        if (BLOCK_START.contains(tokens.LA(1))) {
            return parser.parse(text, tokens, SiemensSclParser::compilationUnit, sink);
        }
        return parser.parse(text, tokens, SiemensSclParser::statementsOnly, sink);
    }

    private static IntervalSet withEof(IntervalSet set) {
        IntervalSet s = new IntervalSet(set);
        s.add(Token.EOF);
        s.setReadonly(true);
        return s;
    }
}
