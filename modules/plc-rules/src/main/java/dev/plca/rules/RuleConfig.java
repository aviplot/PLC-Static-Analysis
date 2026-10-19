package dev.plca.rules;

import dev.plca.core.Dialect;
import dev.plca.core.diag.Severity;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which rules run, with which parameters and severities, and which findings are suppressed. Built from
 * {@code plca.yaml} and the CLI options.
 *
 * <p>Resolution for a POU of dialect {@code d}: a rule runs if the global selection or {@code dialects[d].select} has
 * it and neither {@code disable} nor {@code dialects[d].disable} removes it. Its severity is the first of
 * {@code dialects[d].severity}, {@code severity}, the descriptor's {@code dialectSeverity[d]} and the descriptor's
 * severity. Parameters are the descriptor defaults, overridden by {@code parameters}, then by
 * {@code dialects[d].parameters}.
 *
 * @param select rule ids, categories ({@code quality}, {@code plc}, {@code security}), {@code default} (the rules on by
 *     default) or {@code all}
 * @param disable rule ids or categories removed from the selection
 * @param parameters per rule id: parameter name to value
 * @param severity per rule id: severity override
 * @param dialects per dialect overrides
 * @param suppressions findings to suppress by rule and path or POU glob
 */
public record RuleConfig(
        List<String> select,
        List<String> disable,
        Map<String, Map<String, String>> parameters,
        Map<String, Severity> severity,
        Map<Dialect, DialectConfig> dialects,
        List<Suppression> suppressions) {

    /** Overrides for one dialect; same meaning as the global fields. */
    public record DialectConfig(
            List<String> select,
            List<String> disable,
            Map<String, Map<String, String>> parameters,
            Map<String, Severity> severity) {
        public DialectConfig {
            select = List.copyOf(select);
            disable = List.copyOf(disable);
            parameters = Map.copyOf(parameters);
            severity = Map.copyOf(severity);
        }
    }

    /**
     * Suppresses findings of {@code rule} (an id, category or {@code all}) in files matching {@code path} and/or POUs
     * matching {@code pou} (globs; null matches anything, but not both null). {@code reason} is shown in reports.
     */
    public record Suppression(String rule, String path, String pou, String reason) {
        public Suppression {
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("a suppression needs a rule");
            }
            if (path == null && pou == null) {
                throw new IllegalArgumentException("a suppression needs a path or a pou pattern");
            }
        }
    }

    public RuleConfig {
        select = List.copyOf(select);
        disable = List.copyOf(disable);
        parameters = Map.copyOf(parameters);
        severity = Map.copyOf(severity);
        dialects = Map.copyOf(dialects);
        suppressions = List.copyOf(suppressions);
    }

    public RuleConfig(List<String> select, List<String> disable, Map<String, Map<String, String>> parameters) {
        this(select, disable, parameters, Map.of(), Map.of(), List.of());
    }

    /** The rules that are on by default, with their default settings. */
    public static RuleConfig defaults() {
        return new RuleConfig(List.of("default"), List.of(), Map.of());
    }

    public static RuleConfig only(String... ruleIds) {
        return new RuleConfig(List.of(ruleIds), List.of(), Map.of());
    }

    public RuleConfig withParameters(Map<String, Map<String, String>> params) {
        return new RuleConfig(select, disable, params, severity, dialects, suppressions);
    }

    /**
     * The rule ids that run on at least one dialect, in registry order. Unknown ids, categories, dialect settings or
     * parameters throw {@link IllegalArgumentException}.
     */
    public Set<String> resolve(RuleRegistry registry) {
        Set<String> base = selection(registry, select, disable);
        validate(registry, parameters, severity, "");
        Set<String> any = new LinkedHashSet<>(base);
        dialects.forEach((dialect, dc) -> {
            String where = "dialects." + dialect.name().toLowerCase(java.util.Locale.ROOT) + ".";
            expand(registry, dc.disable());
            any.addAll(expand(registry, dc.select()));
            validate(registry, dc.parameters(), dc.severity(), where);
        });
        for (Suppression s : suppressions) {
            if (!s.rule().equalsIgnoreCase("all")) {
                expand(registry, List.of(s.rule()));
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (RuleDescriptor d : registry.descriptors()) {
            if (any.contains(d.id())) {
                ordered.add(d.id());
            }
        }
        return ordered;
    }

    /** Whether rule {@code id} runs on code of {@code dialect} (null: the global setting). */
    public boolean enabled(RuleRegistry registry, String id, Dialect dialect) {
        DialectConfig dc = dialect == null ? null : dialects.get(dialect);
        boolean on = selection(registry, select, disable).contains(id);
        if (dc != null) {
            on = (on || expand(registry, dc.select()).contains(id))
                    && !expand(registry, dc.disable()).contains(id);
        }
        return on;
    }

    /** The severity of rule {@code d}'s findings in code of {@code dialect} (null: the global setting). */
    public Severity severity(RuleDescriptor d, Dialect dialect) {
        DialectConfig dc = dialect == null ? null : dialects.get(dialect);
        Severity s = dc == null ? null : get(dc.severity(), d.id());
        if (s == null) {
            s = get(severity, d.id());
        }
        if (s == null && dialect != null) {
            s = d.dialectSeverity().get(dialect);
        }
        return s == null ? d.severity() : s;
    }

    /** Parameter overrides for rule {@code id} in code of {@code dialect} (descriptor defaults not included). */
    public Map<String, String> parameters(String id, Dialect dialect) {
        Map<String, String> r = new LinkedHashMap<>();
        Map<String, String> g = get(parameters, id);
        if (g != null) {
            r.putAll(g);
        }
        DialectConfig dc = dialect == null ? null : dialects.get(dialect);
        Map<String, String> p = dc == null ? null : get(dc.parameters(), id);
        if (p != null) {
            r.putAll(p);
        }
        return r;
    }

    private static <T> T get(Map<String, T> byRule, String id) {
        T v = byRule.get(id);
        if (v == null) {
            for (Map.Entry<String, T> e : byRule.entrySet()) {
                if (e.getKey().equalsIgnoreCase(id)) {
                    return e.getValue();
                }
            }
        }
        return v;
    }

    private static Set<String> selection(RuleRegistry registry, List<String> select, List<String> disable) {
        Set<String> wanted = expand(registry, select);
        wanted.removeAll(expand(registry, disable));
        return wanted;
    }

    private static void validate(
            RuleRegistry registry,
            Map<String, Map<String, String>> parameters,
            Map<String, Severity> severity,
            String where) {
        severity.keySet()
                .forEach(rule -> registry.descriptor(rule)
                        .orElseThrow(() -> new IllegalArgumentException(where + "severity: unknown rule " + rule)));
        parameters.forEach((rule, params) -> {
            RuleDescriptor d = registry.descriptor(rule)
                    .orElseThrow(() -> new IllegalArgumentException(where + "parameters: unknown rule " + rule));
            params.forEach((name, value) -> {
                if (d.parameters().stream().noneMatch(p -> p.name().equals(name))) {
                    throw new IllegalArgumentException(where + rule + " has no parameter " + name);
                }
                String error = d.parameter(name).validate(value);
                if (error != null) {
                    throw new IllegalArgumentException(where + rule + "." + name + ": " + error);
                }
            });
        });
    }

    /** Rule ids for selectors (ids, categories, {@code default}, {@code all}); unknown selectors throw. */
    static Set<String> expand(RuleRegistry registry, List<String> selectors) {
        Set<String> r = new LinkedHashSet<>();
        for (String s : selectors) {
            String key = s.strip();
            if (key.equalsIgnoreCase("all")) {
                registry.descriptors().forEach(d -> r.add(d.id()));
                continue;
            }
            if (key.equalsIgnoreCase("default")) {
                registry.descriptors().stream()
                        .filter(RuleDescriptor::enabledByDefault)
                        .forEach(d -> r.add(d.id()));
                continue;
            }
            boolean category = false;
            for (RuleDescriptor d : registry.descriptors()) {
                if (d.category().key().equalsIgnoreCase(key)) {
                    r.add(d.id());
                    category = true;
                }
            }
            if (category) {
                continue;
            }
            RuleDescriptor d = registry.descriptor(key)
                    .orElseThrow(() -> new IllegalArgumentException("unknown rule or category: " + key));
            r.add(d.id());
        }
        return r;
    }
}
