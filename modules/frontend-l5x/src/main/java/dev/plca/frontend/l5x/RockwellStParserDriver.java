package dev.plca.frontend.l5x;

import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.frontend.st.kit.TwoStageParser;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.RockwellStLexer;
import dev.plca.grammar.RockwellStParser;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * The Rockwell ST lexer/parser pair behind a {@link TwoStageParser}. Routines are statement lists (entry rule
 * {@code statementsOnly}).
 *
 * <p>Not thread-safe. Keep one instance per thread.
 */
final class RockwellStParserDriver {

    /**
     * {@code ;}, statement terminators and the keywords that end a statement header ({@code THEN}, {@code DO},
     * {@code OF}), so a broken condition never swallows its keyword.
     */
    static final IntervalSet SYNC = RockwellStAstBuilder.TOKENS.set(
            "SEMI", "THEN", "DO", "OF", "END_IF", "END_CASE", "END_FOR", "END_WHILE", "END_REPEAT");

    private final TwoStageParser<RockwellStParser> parser;

    RockwellStParserDriver(boolean sllFirst) {
        parser = new TwoStageParser<>(
                new RockwellStLexer(null), new RockwellStParser(null), RockwellStAstBuilder.TOKENS, SYNC, sllFirst);
    }

    Parsed parse(SourceText text, DiagnosticSink sink) {
        return parser.parse(text, parser.lex(text), RockwellStParser::statementsOnly, sink);
    }
}
