package dev.plca.frontend.l5x;

import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.frontend.st.kit.CoreTokens;
import dev.plca.frontend.st.kit.TwoStageParser;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.RungLexer;
import dev.plca.grammar.RungParser;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * The rung lexer/parser pair behind a {@link TwoStageParser}. One parse covers a whole routine: its rungs joined with
 * newlines.
 *
 * <p>Not thread-safe. Keep one instance per thread.
 */
final class RungParserDriver {

    static final CoreTokens TOKENS = new CoreTokens(RungLexer.VOCABULARY, RungLexer.channelNames);

    /** {@code ;} ends a rung; inside one, recovery stops at the next operand or leg ({@code , ) ]}). */
    static final IntervalSet SYNC = TOKENS.set("SEMI", "COMMA", "RPAREN", "RBRACK");

    private final TwoStageParser<RungParser> parser;

    RungParserDriver(boolean sllFirst) {
        parser = new TwoStageParser<>(new RungLexer(null), new RungParser(null), TOKENS, SYNC, sllFirst);
    }

    Parsed parse(SourceText text, DiagnosticSink sink) {
        return parser.parse(text, parser.lex(text), RungParser::rungs, sink);
    }
}
