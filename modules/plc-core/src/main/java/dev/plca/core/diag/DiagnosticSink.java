package dev.plca.core.diag;

/**
 * Where frontends and analyses report problems instead of throwing (design rule 2: never throw out of the analyzer on
 * bad input). Implementations need not be thread-safe; use one per file or per worker and merge afterwards.
 */
@FunctionalInterface
public interface DiagnosticSink {

    void report(Diagnostic diagnostic);

    /** A sink that drops everything, for callers that only need the result. */
    static DiagnosticSink discard() {
        return d -> {};
    }

    /** Caps this sink at {@code max} diagnostics; see {@link LimitingSink}. */
    default DiagnosticSink limit(int max) {
        return new LimitingSink(this, max);
    }
}
