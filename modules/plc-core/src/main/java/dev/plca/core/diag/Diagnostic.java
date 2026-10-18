package dev.plca.core.diag;

import dev.plca.core.source.Span;
import java.util.List;
import java.util.Objects;

/**
 * A problem found while reading, parsing or modelling the input ({@code PLC-SYN-…}, {@code PLC-XML-…}). Rule findings
 * (M3) reuse this shape with a rule id. {@code related} points at other relevant places, e.g. the first write of a
 * double coil.
 */
public record Diagnostic(String code, Severity severity, String message, Span span, List<Related> related) {

    /** A secondary location with its own explanation. */
    public record Related(Span span, String message) {
        public Related {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(message, "message");
        }
    }

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
        related = List.copyOf(related);
    }

    public static Diagnostic of(String code, Severity severity, String message, Span span) {
        return new Diagnostic(code, severity, message, span, List.of());
    }

    public static Diagnostic error(String code, String message, Span span) {
        return of(code, Severity.ERROR, message, span);
    }

    public static Diagnostic warning(String code, String message, Span span) {
        return of(code, Severity.WARNING, message, span);
    }
}
