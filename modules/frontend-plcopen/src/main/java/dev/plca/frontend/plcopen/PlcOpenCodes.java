package dev.plca.frontend.plcopen;

/** Diagnostic codes reported by the PLCopen frontend (syntax errors use the shared {@code PLC-SYN-*} codes). */
public final class PlcOpenCodes {

    /** A POU without a known {@code pouType}; read as a function block. */
    public static final String UNKNOWN_POU_TYPE = "PLC-XML-101";
    /** A body in a language not analysed (IL, CFC). */
    public static final String UNPARSED_BODY = "PLC-XML-102";
    /** A connection to an element that does not exist, or an input left open where a value is needed. */
    public static final String BROKEN_CONNECTION = "PLC-XML-103";
    /** A feedback loop through a function, which has no value from the previous cycle. */
    public static final String FEEDBACK = "PLC-XML-104";
    /** An SFC element the lowering does not handle (macro steps, graphical inline conditions). */
    public static final String SFC_UNSUPPORTED = "PLC-XML-105";

    private PlcOpenCodes() {}
}
