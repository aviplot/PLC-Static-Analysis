package dev.plca.frontend.st.kit;

import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * Error recovery for real-world exports. ANTLR's single-token insertion/deletion runs first; when a rule has to give
 * up, input is consumed up to the rule's follow set <em>or</em> a statement/block terminator of the dialect ({@code ;},
 * {@code END_IF}, {@code END_VAR}, …), so one bad statement costs one diagnostic instead of the rest of the POU.
 * Messages use token spellings ({@code 'END_IF'}, {@code identifier}) instead of ANTLR's internal names.
 *
 * <p>A missing operand is reported where it is missing: {@code x :=} followed by {@code ELSIF} gives "expected an
 * expression after ':='" on the {@code :=}, not "unexpected 'ELSIF'" lines later; {@code E_X.;} gives "expected a
 * member name after '.'". Each offending token is reported once: recovery may re-parse a statement from an earlier
 * token and fail on the same token again.
 */
public final class RecoveryStrategy extends DefaultErrorStrategy {

    private final IntervalSet sync;
    private final CoreTokens tokens;
    /** Tokens after which an operand must follow: {@code :=}, {@code ?=}, binary and unary operators. */
    private final IntervalSet operandBefore;
    /** FIRST sets of the parser's {@code expression} and {@code identifier} rules; computed on the first error. */
    private IntervalSet expressionStart;

    private IntervalSet memberStart;
    private int lastReported = -1;

    /** @param sync the dialect's terminators: recovery never deletes them and stops at them */
    public RecoveryStrategy(IntervalSet sync, CoreTokens tokens) {
        this.sync = sync;
        this.tokens = tokens;
        this.operandBefore = tokens.set(
                "ASSIGN",
                "ATTEMPT_ASSIGN",
                "POWER",
                "STAR",
                "SLASH",
                "MOD",
                "PLUS",
                "MINUS",
                "LT",
                "GT",
                "LE",
                "GE",
                "EQ",
                "NE",
                "AND",
                "AMPERSAND",
                "AND_THEN",
                "XOR",
                "OR",
                "OR_ELSE",
                "NOT");
    }

    /**
     * Never repair by deleting a {@code ;} or {@code END_*}: that glues the next statement or block onto the broken one
     * (e.g. {@code a := 1 +; b := 2;} would become {@code a := 1 + b := 2;}). Recovery stops at them instead.
     */
    @Override
    protected Token singleTokenDeletion(Parser recognizer) {
        int la = recognizer.getInputStream().LA(1);
        // Nor a token an enclosing rule still expects: deleting the ELSIF of `x := ELSIF c THEN` parses `x := c` and
        // fails at THEN; stopping at ELSIF reports the missing operand and keeps the IF intact.
        if (sync.contains(la) || getErrorRecoverySet(recognizer).contains(la)) {
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
        report(recognizer, offending, "unexpected " + describe(offending), e);
    }

    @Override
    protected void reportInputMismatch(Parser recognizer, InputMismatchException e) {
        report(
                recognizer,
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
        report(recognizer, t, "unexpected " + describe(t) + expecting(getExpectedTokens(recognizer)), null);
    }

    @Override
    protected void reportMissingToken(Parser recognizer) {
        if (inErrorRecoveryMode(recognizer)) {
            return;
        }
        beginErrorCondition(recognizer);
        Token t = recognizer.getCurrentToken();
        IntervalSet expected = getExpectedTokens(recognizer);
        report(recognizer, t, "missing " + names(expected) + " before " + describe(t), null);
    }

    /** Reports at most once per offending token, as a missing operand when the previous token needs one. */
    private void report(Parser recognizer, Token offending, String message, RecognitionException e) {
        if (offending.getTokenIndex() >= 0) {
            if (offending.getTokenIndex() <= lastReported) {
                return;
            }
            lastReported = offending.getTokenIndex();
        }
        Token before = previousOnChannel(recognizer.getInputStream(), offending);
        if (before != null) {
            if (expressionStart == null) {
                expressionStart = first(recognizer, "expression");
                memberStart = first(recognizer, "identifier");
                memberStart.add(tokens.integer);
            }
            int type = offending.getType();
            if (operandBefore.contains(before.getType())
                    && !expressionStart.isNil()
                    && !expressionStart.contains(type)) {
                recognizer.notifyErrorListeners(
                        before,
                        "expected an expression after " + describe(before) + ", found " + describe(offending),
                        e);
                return;
            }
            if (before.getType() == tokens.type("DOT") && !memberStart.isNil() && !memberStart.contains(type)) {
                recognizer.notifyErrorListeners(
                        before, "expected a member name after '.', found " + describe(offending), e);
                return;
            }
        }
        recognizer.notifyErrorListeners(offending, message, e);
    }

    /** The default-channel token before {@code t}, or null. */
    private static Token previousOnChannel(TokenStream input, Token t) {
        for (int i = t.getTokenIndex() - 1; i >= 0; i--) {
            Token p = input.get(i);
            if (p.getChannel() == Token.DEFAULT_CHANNEL) {
                return p;
            }
        }
        return null;
    }

    /** Tokens that can start {@code rule}; empty if the grammar has no such rule. */
    private static IntervalSet first(Parser recognizer, String rule) {
        Integer index = recognizer.getRuleIndexMap().get(rule);
        if (index == null) {
            return new IntervalSet();
        }
        ATN atn = recognizer.getATN();
        return new IntervalSet(atn.nextTokens(atn.ruleToStartState[index]));
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
