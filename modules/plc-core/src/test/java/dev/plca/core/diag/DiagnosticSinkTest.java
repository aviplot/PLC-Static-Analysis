package dev.plca.core.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.plca.core.source.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticSinkTest {

    @Test
    void collectorCountsErrors() {
        DiagnosticCollector c = new DiagnosticCollector();
        c.report(Diagnostic.error("PLC-SYN-001", "missing ';'", Span.at(0, 4)));
        c.report(Diagnostic.warning("PLC-SYN-002", "odd", Span.at(0, 9)));
        assertThat(c.size()).isEqualTo(2);
        assertThat(c.errorCount()).isEqualTo(1);
        assertThat(c.hasErrors()).isTrue();

        DiagnosticCollector target = new DiagnosticCollector();
        c.drainTo(target);
        assertThat(target.size()).isEqualTo(2);
        assertThat(c.size()).isZero();
        assertThat(c.hasErrors()).isFalse();
    }

    @Test
    void limitingSinkReportsOverflowOnce() {
        DiagnosticCollector c = new DiagnosticCollector();
        DiagnosticSink sink = c.limit(3);
        for (int i = 0; i < 10; i++) {
            sink.report(Diagnostic.error("PLC-SYN-001", "e" + i, Span.at(0, i)));
        }
        assertThat(c.diagnostics())
                .extracting(Diagnostic::code)
                .containsExactly("PLC-SYN-001", "PLC-SYN-001", "PLC-SYN-001", LimitingSink.TOO_MANY);
        assertThat(c.diagnostics().get(3).span()).isEqualTo(Span.at(0, 3));
        assertThat(((LimitingSink) sink).received()).isEqualTo(10);
    }

    @Test
    void diagnosticIsImmutable() {
        var related = new java.util.ArrayList<Diagnostic.Related>();
        related.add(new Diagnostic.Related(Span.at(0, 1), "first write"));
        Diagnostic d = new Diagnostic("X", Severity.INFO, "m", Span.at(0, 0), related);
        related.clear();
        assertThat(d.related()).hasSize(1);
        assertThatThrownBy(() -> d.related().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Diagnostic.of("X", Severity.INFO, "m", null)).isInstanceOf(NullPointerException.class);
        assertThat(DiagnosticSink.discard()).isNotNull();
        assertThat(List.of(Severity.values())).hasSize(4);
    }
}
