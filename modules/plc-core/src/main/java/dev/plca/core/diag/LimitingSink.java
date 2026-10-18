package dev.plca.core.diag;

/**
 * Passes on at most {@code max} diagnostics, then reports a single {@value #TOO_MANY} warning at the position of the
 * first dropped one. One badly broken export (wrong dialect, truncated file) must not drown a whole report.
 */
public final class LimitingSink implements DiagnosticSink {

    public static final String TOO_MANY = "PLC-CORE-001";

    private final DiagnosticSink delegate;
    private final int max;
    private int seen;

    public LimitingSink(DiagnosticSink delegate, int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be positive: " + max);
        }
        this.delegate = delegate;
        this.max = max;
    }

    @Override
    public void report(Diagnostic diagnostic) {
        seen++;
        if (seen <= max) {
            delegate.report(diagnostic);
        } else if (seen == max + 1) {
            delegate.report(Diagnostic.warning(
                    TOO_MANY,
                    "Too many diagnostics; further ones in this input are suppressed (limit " + max + ")",
                    diagnostic.span()));
        }
    }

    /** Total number of diagnostics received, including suppressed ones. */
    public int received() {
        return seen;
    }
}
