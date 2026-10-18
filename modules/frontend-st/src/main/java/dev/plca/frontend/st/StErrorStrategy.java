package dev.plca.frontend.st;

import dev.plca.grammar.CodesysStLexer;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * Error recovery for real-world exports. ANTLR's single-token insertion/deletion runs first; when a rule has to give
 * up, input is consumed up to the rule's follow set <em>or</em> a statement/block terminator ({@code ;},
 * {@code END_IF}, {@code END_VAR}, …), so one bad statement costs one diagnostic instead of the rest of the POU.
 * Messages use token spellings ({@code 'END_IF'}, {@code identifier}) instead of ANTLR's internal names.
 */
final class StErrorStrategy extends DefaultErrorStrategy {

    private static final IntervalSet SYNC = IntervalSet.of(CodesysStLexer.SEMI);

    static {
        int[] terminators = {
            CodesysStLexer.END_IF,
            CodesysStLexer.END_CASE,
            CodesysStLexer.END_FOR,
            CodesysStLexer.END_WHILE,
            CodesysStLexer.END_REPEAT,
            CodesysStLexer.END_VAR,
            CodesysStLexer.END_STRUCT,
            CodesysStLexer.END_UNION,
            CodesysStLexer.END_TYPE,
            CodesysStLexer.END_FUNCTION_BLOCK,
            CodesysStLexer.END_FUNCTION,
            CodesysStLexer.END_PROGRAM,
            CodesysStLexer.END_METHOD,
            CodesysStLexer.END_PROPERTY,
            CodesysStLexer.END_GET,
            CodesysStLexer.END_SET,
            CodesysStLexer.END_ACTION,
            CodesysStLexer.END_INTERFACE,
            CodesysStLexer.END_CLASS,
            CodesysStLexer.END_NAMESPACE,
            CodesysStLexer.ENDTRY,
        };
        for (int t : terminators) {
            SYNC.add(t);
        }
        SYNC.setReadonly(true);
    }

    /**
     * Never repair by deleting a {@code ;} or {@code END_*}: that glues the next statement or block onto the broken one
     * (e.g. {@code a := 1 +; b := 2;} would become {@code a := 1 + b := 2;}). Recovery stops at them instead.
     */
    @Override
    protected Token singleTokenDeletion(Parser recognizer) {
        if (SYNC.contains(recognizer.getInputStream().LA(1))) {
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
        stop.addAll(SYNC);
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
                "unexpected " + describe(e.getOffendingToken()) + expecting(recognizer, e.getExpectedTokens()),
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
                t, "unexpected " + describe(t) + expecting(recognizer, getExpectedTokens(recognizer)), null);
    }

    @Override
    protected void reportMissingToken(Parser recognizer) {
        if (inErrorRecoveryMode(recognizer)) {
            return;
        }
        beginErrorCondition(recognizer);
        Token t = recognizer.getCurrentToken();
        IntervalSet expected = getExpectedTokens(recognizer);
        recognizer.notifyErrorListeners(
                t, "missing " + names(recognizer.getVocabulary(), expected) + " before " + describe(t), null);
    }

    private static String expecting(Parser recognizer, IntervalSet expected) {
        if (expected == null || expected.isNil() || expected.size() > 4) {
            return "";
        }
        return "; expected " + names(recognizer.getVocabulary(), expected);
    }

    static String names(Vocabulary vocabulary, IntervalSet set) {
        StringBuilder sb = new StringBuilder();
        for (int type : set.toArray()) {
            if (!sb.isEmpty()) {
                sb.append(" or ");
            }
            sb.append(displayName(vocabulary, type));
        }
        return sb.toString();
    }

    static String displayName(Vocabulary vocabulary, int type) {
        return switch (type) {
            case Token.EOF -> "end of input";
            case CodesysStLexer.IDENT -> "identifier";
            case CodesysStLexer.INTEGER, CodesysStLexer.BASED_INTEGER -> "integer";
            case CodesysStLexer.REAL -> "real number";
            case CodesysStLexer.STRING_LITERAL, CodesysStLexer.WSTRING_LITERAL -> "string";
            case CodesysStLexer.DIRECT_ADDRESS -> "direct address";
            default -> {
                String literal = vocabulary.getLiteralName(type);
                yield literal != null ? literal : vocabulary.getDisplayName(type);
            }
        };
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
