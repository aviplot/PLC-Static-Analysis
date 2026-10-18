package dev.plca.rules;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Span;
import java.util.List;
import java.util.Objects;

/**
 * A rule violation. {@code pou} is the qualified name of the POU the finding belongs to (null outside POUs, e.g. in a
 * GVL); reports use it for stable fingerprints that do not depend on line numbers.
 */
public record Finding(
        String ruleId, Severity severity, String message, Span span, List<Diagnostic.Related> related, String pou) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
        related = List.copyOf(related);
    }
}
