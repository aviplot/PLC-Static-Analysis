package dev.plca.frontend.scl;

/** Diagnostic codes reported by the Siemens SCL frontend (syntax errors use the shared {@code PLC-SYN-*} codes). */
public final class SclCodes {

    /** A code block without {@code BEGIN} between its declarations and its statements. */
    public static final String MISSING_BEGIN = "PLC-SCL-001";
    /** A block attribute ({@code TITLE}, {@code VERSION}, …) given twice; the first one is kept. */
    public static final String DUPLICATE_ATTRIBUTE = "PLC-SCL-002";
    /** A statement other than an assignment in the {@code BEGIN} part of a data block. */
    public static final String DB_INIT_NOT_ASSIGNMENT = "PLC-SCL-003";
    /** {@code # x}: the local prefix is separated from its name. */
    public static final String DETACHED_LOCAL_PREFIX = "PLC-SCL-004";
    /** A block attribute that does not belong to this kind of block (e.g. {@code NON_RETAIN} on an FB). */
    public static final String MISPLACED_ATTRIBUTE = "PLC-SCL-005";
    /** Text between blocks that belongs to no block (often the rest of a block broken by a syntax error). */
    public static final String OUTSIDE_BLOCK = "PLC-SCL-006";

    private SclCodes() {}
}
