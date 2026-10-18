package dev.plca.frontend.st;

/** Diagnostic codes reported by the ST frontend. */
public final class SyntaxCodes {

    /** Syntax error reported by the parser. */
    public static final String SYNTAX = "PLC-SYN-001";
    /** Block comment without closing {@code *)} / {@code *}{@code /}. */
    public static final String UNTERMINATED_COMMENT = "PLC-SYN-002";
    /** String literal without closing quote (strings may span lines, so it runs to the end of the text). */
    public static final String UNTERMINATED_STRING = "PLC-SYN-003";
    /** Character that cannot start any token. */
    public static final String UNEXPECTED_CHARACTER = "PLC-SYN-004";
    /** {@code x FOO= y;}: only {@code S=}, {@code R=} and {@code REF=} exist. */
    public static final String UNKNOWN_ASSIGNMENT_OPERATOR = "PLC-SYN-005";
    /** A statement that is neither an assignment nor a call, e.g. {@code fbTimer;}. */
    public static final String NOT_A_STATEMENT = "PLC-SYN-006";
    /** Malformed XML in a TwinCAT file. */
    public static final String MALFORMED_XML = "PLC-XML-001";
    /** TwinCAT implementation in a graphical language (not parsed). */
    public static final String UNPARSED_LANGUAGE = "PLC-TC-001";
    /** Internal failure or pathological input (e.g. nesting too deep); the file is skipped. */
    public static final String INTERNAL = "PLC-INT-001";

    private SyntaxCodes() {}
}
