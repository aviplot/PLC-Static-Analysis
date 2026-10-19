package dev.plca.frontend.st.kit;

import dev.plca.ast.Expression.BinaryOp;
import dev.plca.ast.Expression.UnaryOp;
import java.util.HashMap;
import java.util.Map;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * Token types of the shared ST core ({@code StLexer.g4}) in one dialect's vocabulary.
 *
 * <p>Every dialect lexer imports the core, but ANTLR numbers a root grammar's own rules first, so {@code SEMI} or
 * {@code IDENT} have a different type in {@code CodesysStLexer} and {@code SiemensSclLexer}. Shared code looks types up
 * here — by symbolic name, once per dialect — instead of using one lexer's constants. A name the dialect does not
 * define maps to {@link #ABSENT}, which no token ever has.
 */
public final class CoreTokens {

    /** Type of a token the dialect does not define. */
    public static final int ABSENT = -100;

    private final Map<String, Integer> types = new HashMap<>();
    private final Map<String, Integer> channels = new HashMap<>();
    private final Vocabulary vocabulary;

    public final int ident;
    public final int integer;
    public final int basedInteger;
    public final int real;
    public final int trueKw;
    public final int falseKw;
    public final int stringLiteral;
    public final int wstringLiteral;
    public final int unterminatedString;
    public final int timeLiteral;
    public final int dateLiteral;
    public final int todLiteral;
    public final int dtLiteral;
    public final int directAddress;
    public final int semi;
    public final int lineComment;
    public final int blockComment;
    public final int cComment;

    public final int commentsChannel;
    public final int pragmasChannel;
    public final int errorsChannel;

    private final Map<Integer, BinaryOp> binaryOps = new HashMap<>();
    private final Map<Integer, UnaryOp> unaryOps = new HashMap<>();

    /**
     * @param vocabulary the generated lexer's {@code VOCABULARY}
     * @param channelNames the generated lexer's {@code channelNames}
     */
    public CoreTokens(Vocabulary vocabulary, String[] channelNames) {
        this.vocabulary = vocabulary;
        for (int t = 0; t <= vocabulary.getMaxTokenType(); t++) {
            String name = vocabulary.getSymbolicName(t);
            if (name != null) {
                types.put(name, t);
            }
        }
        for (int c = 0; c < channelNames.length; c++) {
            channels.put(channelNames[c], c);
        }
        ident = type("IDENT");
        integer = type("INTEGER");
        basedInteger = type("BASED_INTEGER");
        real = type("REAL");
        trueKw = type("TRUE");
        falseKw = type("FALSE");
        stringLiteral = type("STRING_LITERAL");
        wstringLiteral = type("WSTRING_LITERAL");
        unterminatedString = type("UNTERMINATED_STRING");
        timeLiteral = type("TIME_LITERAL");
        dateLiteral = type("DATE_LITERAL");
        todLiteral = type("TOD_LITERAL");
        dtLiteral = type("DT_LITERAL");
        directAddress = type("DIRECT_ADDRESS");
        semi = type("SEMI");
        lineComment = type("LINE_COMMENT");
        blockComment = type("BLOCK_COMMENT");
        cComment = type("C_COMMENT");
        commentsChannel = channels.getOrDefault("COMMENTS", ABSENT);
        pragmasChannel = channels.getOrDefault("PRAGMAS", ABSENT);
        errorsChannel = channels.getOrDefault("ERRORS", ABSENT);

        binary("POWER", BinaryOp.POWER);
        binary("STAR", BinaryOp.MUL);
        binary("SLASH", BinaryOp.DIV);
        binary("MOD", BinaryOp.MOD);
        binary("PLUS", BinaryOp.ADD);
        binary("MINUS", BinaryOp.SUB);
        binary("LT", BinaryOp.LT);
        binary("GT", BinaryOp.GT);
        binary("LE", BinaryOp.LE);
        binary("GE", BinaryOp.GE);
        binary("EQ", BinaryOp.EQ);
        binary("NE", BinaryOp.NE);
        binary("AND", BinaryOp.AND);
        binary("AMPERSAND", BinaryOp.AND);
        binary("AND_THEN", BinaryOp.AND_THEN);
        binary("XOR", BinaryOp.XOR);
        binary("OR", BinaryOp.OR);
        binary("OR_ELSE", BinaryOp.OR_ELSE);
        unary("MINUS", UnaryOp.NEGATE);
        unary("PLUS", UnaryOp.PLUS);
        unary("NOT", UnaryOp.NOT);
    }

    private void binary(String name, BinaryOp op) {
        int t = type(name);
        if (t != ABSENT) {
            binaryOps.put(t, op);
        }
    }

    private void unary(String name, UnaryOp op) {
        int t = type(name);
        if (t != ABSENT) {
            unaryOps.put(t, op);
        }
    }

    /** The type of the token named {@code symbolicName}, or {@link #ABSENT}. */
    public int type(String symbolicName) {
        return types.getOrDefault(symbolicName, ABSENT);
    }

    /** A read-only set of the named token types; names the dialect does not define are skipped. */
    public IntervalSet set(String... symbolicNames) {
        IntervalSet s = new IntervalSet();
        for (String n : symbolicNames) {
            int t = type(n);
            if (t != ABSENT) {
                s.add(t);
            }
        }
        s.setReadonly(true);
        return s;
    }

    public Vocabulary vocabulary() {
        return vocabulary;
    }

    /** The binary operator of an operator token (the {@code op} of a binary expression), or null. */
    public BinaryOp binaryOp(Token op) {
        return op == null ? null : binaryOps.get(op.getType());
    }

    /** The unary operator of an operator token, or null. */
    public UnaryOp unaryOp(Token op) {
        return op == null ? null : unaryOps.get(op.getType());
    }

    /** User-facing spelling of a token type for messages: {@code 'END_IF'}, {@code identifier}. */
    public String displayName(int type) {
        if (type == Token.EOF) {
            return "end of input";
        }
        if (type == ident) {
            return "identifier";
        }
        if (type == integer || type == basedInteger) {
            return "integer";
        }
        if (type == real) {
            return "real number";
        }
        if (type == stringLiteral || type == wstringLiteral) {
            return "string";
        }
        if (type == directAddress) {
            return "direct address";
        }
        String literal = vocabulary.getLiteralName(type);
        return literal != null ? literal : vocabulary.getDisplayName(type);
    }
}
