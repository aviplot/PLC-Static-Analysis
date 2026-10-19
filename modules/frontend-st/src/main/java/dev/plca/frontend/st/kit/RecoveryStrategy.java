package dev.plca.frontend.st.kit;

import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * Error recovery for real-world exports. ANTLR's single-token insertion/deletion runs first; when a rule has to give
 * up, input is consumed up to the rule's follow set <em>or</em> a statement/block terminator of the dialect ({@code ;},
 * {@code END_IF}, {@code END_VAR}, …), so one bad statement costs one diagnostic instead of the rest of the POU.
 * Messages use token spellings ({@code 'END_IF'}, {@code identifier}) instead of ANTLR's internal names.
 */
public final class RecoveryStrategy extends DefaultErrorStrategy {

    private final IntervalSet sync;
    private final CoreTokens tokens;

    /** @param sync the dialect's terminators: recovery never deletes them and stops at them */
    public RecoveryStrategy(IntervalSet sync, CoreTokens tokens) {
        this.sync = sync;
        this.tokens = tokens;
    }

    /**
     * Never repair by deleting a {@code ;} or {@code END_*}: that glues the next statement or block onto the broken one
     * (e.g. {@code a := 1 +; b := 2;} would become {@code a := 1 + b := 2;}). Recovery stops at them instead.
     */
    @Override
    protected Token singleTokenDeletion(Parser recognizer) {
        if (sync.contains(recognizer.getInputStream().LA(1))) {
            return null;
        }
        return super.singleTokenDeletion(recognizer);
    }

    @Override
    public void recover(Parser recognizer, RecognitionException e) {
        if (lastErrorIndex == recognizer.getInputStream().index()
                && lastErrorStates != null
                && lastErrorStates.contains(recognizer.getState())) {
            // no progress since the last error: skip a token so we cannot loop forever
            recognizer.consume();
        }
        lastErrorIndex = recognizer.getInputStream().index();
        if (lastErrorStates == null) {
            lastErrorStates = new IntervalSet();
        }
        lastErrorStates.add(recognizer.getState());
        IntervalSet stop = new IntervalSet(getErrorRecoverySet(recognizer));
        stop.addAll(sync);
        consumeUntil(recognizer, stop);
    }

    @Override
    protected void reportNoViableAlternative(Parser recognizer, NoViableAltException e) {
        Token offending = e.getOffendingToken();
        recognizer.notifyErrorListeners(offending, "unexpected " + describe(offending), e);
    }

    @Override
    protected void reportInputMismatch(Parser recognizer, InputMismatchException e) {
        recognizer.notifyErrorListeners(
                e.getOffendingToken(),
                "unexpected " + describe(e.getOffendingToken()) + expecting(e.getExpectedTokens()),
                e);
    }

    @Override
    protected void reportUnwantedToken(Parser recognizer) {
        if (inErrorRecoveryMode(recognizer)) {
            return;
        }
        beginErrorCondition(recognizer);
        Token t = recognizer.getCurrentToken();
        recognizer.notifyErrorListeners(
                t, "unexpected " + describe(t) + expecting(getExpectedTokens(recognizer)), null);
    }

    @Override
    protected void reportMissingToken(Parser recognizer) {
        if (inErrorRecoveryMode(recognizer)) {
            return;
        }
        beginErrorCondition(recognizer);
        Token t = recognizer.getCurrentToken();
        IntervalSet expected = getExpectedTokens(recognizer);
        recognizer.notifyErrorListeners(t, "missing " + names(expected) + " before " + describe(t), null);
    }

    private String expecting(IntervalSet expected) {
        if (expected == null || expected.isNil() || expected.size() > 4) {
            return "";
        }
        return "; expected " + names(expected);
    }

    private String names(IntervalSet set) {
        StringBuilder sb = new StringBuilder();
        for (int type : set.toArray()) {
            if (!sb.isEmpty()) {
                sb.append(" or ");
            }
            sb.append(tokens.displayName(type));
        }
        return sb.toString();
    }

    private static String describe(Token t) {
        if (t.getType() == Token.EOF) {
            return "end of input";
        }
        String text = t.getText();
        if (text.length() > 40) {
            text = text.substring(0, 37) + "...";
        }
        return "'" + text.replace("\n", "\\n") + "'";
    }
}
