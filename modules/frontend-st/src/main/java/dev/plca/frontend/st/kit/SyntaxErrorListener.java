package dev.plca.frontend.st.kit;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.SyntaxCodes;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

/** Turns parser errors into diagnostics with spans in the parsed text. */
public final class SyntaxErrorListener extends BaseErrorListener {

    private final SourceText text;
    private final DiagnosticSink sink;
    private int count;
    private int accepted;

    public SyntaxErrorListener(SourceText text, DiagnosticSink sink) {
        this.text = text;
        this.sink = sink;
    }

    @Override
    public void syntaxError(
            Recognizer<?, ?> recognizer, Object offending, int line, int col, String msg, RecognitionException e) {
        if (e instanceof RecoveryStrategy.Accepted) {
            accepted++;
            sink.report(Diagnostic.warning(SyntaxCodes.MISSING_SEMICOLON, msg, tokenSpan(text, (Token) offending)));
            return;
        }
        count++;
        sink.report(Diagnostic.error(SyntaxCodes.SYNTAX, msg, tokenSpan(text, (Token) offending)));
    }

    /** Syntax errors reported. */
    public int count() {
        return count;
    }

    /** Accepted deviations reported as warnings ({@link RecoveryStrategy.Accepted}). */
    public int accepted() {
        return accepted;
    }

    public static Span tokenSpan(SourceText text, Token t) {
        int len = text.text().length();
        if (t == null || t.getType() == Token.EOF || t.getStartIndex() < 0) {
            int at = t == null || t.getStartIndex() < 0 ? len : Math.min(t.getStartIndex(), len);
            return text.span(at, at);
        }
        int start = Math.min(t.getStartIndex(), len);
        int end = Math.min(Math.max(t.getStopIndex() + 1, start), len);
        return text.span(start, end);
    }
}
