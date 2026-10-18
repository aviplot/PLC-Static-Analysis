package dev.plca.core;

/** Source dialect of a POU. Travels with every POU so rules can opt in or out per dialect. */
public enum Dialect {
    /** CODESYS IEC 61131-3 Structured Text (also Schneider Machine Expert, WAGO, ABB, ...). */
    CODESYS,
    /** Beckhoff TwinCAT 3: CODESYS-based ST, usually in {@code .TcPOU}/{@code .TcDUT}/... XML. */
    TWINCAT,
    /** Siemens SCL exported from TIA Portal. */
    SIEMENS,
    /** Rockwell Studio 5000: ladder rung text and ST routines from L5X. */
    ROCKWELL,
    /** PLCopen TC6 XML exchange format. */
    PLCOPEN
}
