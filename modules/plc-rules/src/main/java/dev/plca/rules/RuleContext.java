package dev.plca.rules;

import dev.plca.ast.Node;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** What a rule sees: the model, its own descriptor and parameters, and a place to report. Not thread-safe. */
public abstract sealed class RuleContext permits PouContext, ProjectContext {

    private final SemanticModel model;
    private final SourceRegistry sources;
    private final RuleDescriptor descriptor;
    private final Map<String, String> parameters;
    private final RuleEngine.Settings settings;
    final List<Finding> findings = new ArrayList<>();

    RuleContext(
            SemanticModel model,
            SourceRegistry sources,
            RuleDescriptor descriptor,
            Map<String, String> parameters,
            RuleEngine.Settings settings) {
        this.model = model;
        this.sources = sources;
        this.descriptor = descriptor;
        this.parameters = parameters;
        this.settings = settings;
    }

    public SemanticModel model() {
        return model;
    }

    /** The source text of a node, as the lexer saw it (CDATA content for TwinCAT files). */
    public String text(Node node) {
        return text(node.span());
    }

    /** The source text of a span. */
    public String text(Span s) {
        return sources.text(s.textId()).text().substring(s.start(), s.end());
    }

    public RuleDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Reports a finding with the rule's configured severity for the dialect of the span's file; {@code pou} as in
     * {@link Finding#pou()}. Dropped when the configuration disables the rule for that dialect.
     */
    public void report(String pou, Span span, String message, List<Diagnostic.Related> related) {
        Dialect d = settings.dialectOf(span);
        if (settings.enabled(d)) {
            findings.add(new Finding(descriptor.id(), settings.severity(d), message, span, related, pou));
        }
    }

    /** Reports a finding in {@code pou} (null outside POUs), with its IDE location where the dialect has one. */
    void report(PouSymbol pou, Span span, String message, List<Diagnostic.Related> related, boolean unused) {
        report(pou, span, message, related, null);
    }

    /** As above; {@code atMost}, when not null, caps the severity ({@code INFO} turns a warning into info). */
    void report(PouSymbol pou, Span span, String message, List<Diagnostic.Related> related, Severity atMost) {
        Dialect d = pou != null && pou.dialect() != null ? pou.dialect() : settings.dialectOf(span);
        if (!settings.enabled(d)) {
            return;
        }
        Severity severity = settings.severity(d);
        if (atMost != null && severity.ordinal() < atMost.ordinal()) {
            severity = atMost;
        }
        findings.add(new Finding(
                descriptor.id(),
                severity,
                message,
                span,
                related,
                pou == null ? null : pou.qualifiedName(),
                pou == null ? null : Places.of(pou, span, sources)));
    }

    public String stringParam(String name) {
        String v = parameters.get(name);
        if (v == null) {
            v = descriptor.parameter(name).defaultValue();
        }
        return v;
    }

    public boolean boolParam(String name) {
        return Boolean.parseBoolean(stringParam(name));
    }

    public int intParam(String name) {
        return Integer.parseInt(stringParam(name));
    }

    /** A {@code regex} parameter; empty means "matches nothing". */
    public Pattern patternParam(String name) {
        String v = stringParam(name);
        return v.isEmpty() ? Pattern.compile("(?!)") : Pattern.compile(v);
    }

    /** A comma-separated {@code list} parameter. */
    public List<String> listParam(String name) {
        String v = stringParam(name);
        return v.isBlank() ? List.of() : List.of(v.trim().split("\\s*,\\s*"));
    }
}
