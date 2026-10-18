package dev.plca.core.diag;

/** Diagnostic severity. Reports map it to SARIF {@code error/warning/note/none} and to LSP severities. */
public enum Severity {
    ERROR,
    WARNING,
    INFO,
    HINT
}
