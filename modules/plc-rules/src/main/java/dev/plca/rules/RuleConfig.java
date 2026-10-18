package dev.plca.rules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which rules run and with which parameters. Built by the CLI today and from {@code plca.yaml} later (M7).
 *
 * @param select rule ids, categories ({@code quality}, {@code plc}, {@code security}) or {@code all}
 * @param disable rule ids or categories removed from the selection
 * @param parameters per rule id: parameter name to value
 */
public record RuleConfig(List<String> select, List<String> disable, Map<String, Map<String, String>> parameters) {

    public RuleConfig {
        select = List.copyOf(select);
        disable = List.copyOf(disable);
        parameters = Map.copyOf(parameters);
    }

    public static RuleConfig defaults() {
        return new RuleConfig(List.of("all"), List.of(), Map.of());
    }

    public static RuleConfig only(String... ruleIds) {
        return new RuleConfig(List.of(ruleIds), List.of(), Map.of());
    }

    public RuleConfig withParameters(Map<String, Map<String, String>> params) {
        return new RuleConfig(select, disable, params);
    }

    /** The enabled rule ids, in registry order. Unknown ids, categories or parameters throw. */
    public Set<String> resolve(RuleRegistry registry) {
        Set<String> selected = new LinkedHashSet<>();
        Set<String> disabled = expand(registry, disable);
        Set<String> wanted = expand(registry, select);
        for (RuleDescriptor d : registry.descriptors()) {
            if (wanted.contains(d.id()) && !disabled.contains(d.id())) {
                selected.add(d.id());
            }
        }
        parameters.forEach((rule, params) -> {
            RuleDescriptor d =
                    registry.descriptor(rule).orElseThrow(() -> new IllegalArgumentException("unknown rule " + rule));
            params.forEach((name, value) -> {
                if (d.parameters().stream().noneMatch(p -> p.name().equals(name))) {
                    throw new IllegalArgumentException(rule + " has no parameter " + name);
                }
                String error = d.parameter(name).validate(value);
                if (error != null) {
                    throw new IllegalArgumentException(rule + "." + name + ": " + error);
                }
            });
        });
        return selected;
    }

    private static Set<String> expand(RuleRegistry registry, List<String> selectors) {
        Set<String> r = new LinkedHashSet<>();
        for (String s : selectors) {
            String key = s.strip();
            if (key.equalsIgnoreCase("all")) {
                registry.descriptors().forEach(d -> r.add(d.id()));
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
