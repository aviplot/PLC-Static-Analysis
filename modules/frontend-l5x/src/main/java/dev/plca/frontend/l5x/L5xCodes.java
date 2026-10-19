package dev.plca.frontend.l5x;

/** Diagnostic codes reported by the L5X frontend (syntax errors use the shared {@code PLC-SYN-*} codes). */
public final class L5xCodes {

    /** An {@code .L5X} file without an {@code RSLogix5000Content} root element; not analysed. */
    public static final String NOT_L5X = "PLC-L5X-001";
    /** A routine in a language not analysed yet (FBD, SFC); its body is skipped. */
    public static final String UNPARSED_ROUTINE = "PLC-L5X-002";
    /** A source-protected AOI or routine ({@code EncodedData}): only its interface is analysed. */
    public static final String PROTECTED = "PLC-L5X-003";

    private L5xCodes() {}
}
