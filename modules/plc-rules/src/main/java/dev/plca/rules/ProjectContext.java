package dev.plca.rules;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import java.util.List;
import java.util.Map;

/** Context of a {@link ProjectRule}: the whole model. */
public final class ProjectContext extends RuleContext {

    ProjectContext(
            SemanticModel model, SourceRegistry sources, RuleDescriptor descriptor, Map<String, String> parameters) {
        super(model, sources, descriptor, parameters);
    }

    /** Reports a finding in {@code pou} (null for findings outside POUs). */
    public void report(PouSymbol pou, Span span, String message, List<Diagnostic.Related> related) {
        report(pou, span, message, related, false);
    }
}
