package dev.plca.rules;

/**
 * A check. Rules read only the semantic model and the AST (design rule 3) and report through their context; they never
 * throw on odd input — the engine still isolates them ({@link RuleEngine#INTERNAL}).
 *
 * <p>Metadata (title, severity, parameters, documentation) lives in {@code dev/plca/rules/<id>.md}, see
 * {@link RuleDescriptor}.
 */
public sealed interface Rule permits PouRule, ProjectRule {

    /** Stable id, e.g. {@code PLCA-Q001}; also the name of the descriptor resource. */
    String id();
}
