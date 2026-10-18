package dev.plca.core.diag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects diagnostics in report order. Not thread-safe: one per file or per worker thread. */
public final class DiagnosticCollector implements DiagnosticSink {

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int errors;

    @Override
    public void report(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
        if (diagnostic.severity() == Severity.ERROR) {
            errors++;
        }
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean hasErrors() {
        return errors > 0;
    }

    public int errorCount() {
        return errors;
    }

    public int size() {
        return diagnostics.size();
    }

    /** Forwards everything collected so far, e.g. when merging per-thread results. */
    public void drainTo(DiagnosticSink target) {
        diagnostics.forEach(target::report);
        diagnostics.clear();
        errors = 0;
    }
}
