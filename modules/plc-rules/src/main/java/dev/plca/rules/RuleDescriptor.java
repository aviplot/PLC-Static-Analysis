package dev.plca.rules;

import dev.plca.core.Dialect;
import dev.plca.core.diag.Severity;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Metadata and documentation of a rule, loaded from {@code dev/plca/rules/<id>.md} by {@link DescriptorParser}. The
 * same text feeds SARIF {@code rules[]}, {@code plca rules} and the HTML report.
 *
 * @param plcopen PLCopen Coding Guidelines v1.0 rule id (e.g. {@code CP8}), or null when none applies
 * @param cwe CWE id (e.g. {@code CWE-798}), or null
 * @param description the first paragraph of the {@code ## Description} section
 * @param markdown the documentation body after the front matter
 * @param enabledByDefault false for opt-in rules (front matter {@code enabled: false}), which run only when selected
 * @param dialectSeverity shipped severity per dialect where it differs from {@code severity} (front matter
 *     {@code dialectSeverity: rockwell=info})
 */
public record RuleDescriptor(
        String id,
        String title,
        Severity severity,
        Category category,
        Set<Dialect> dialects,
        List<String> tags,
        String plcopen,
        String cwe,
        List<Parameter> parameters,
        String description,
        String markdown,
        List<Example> examples,
        boolean enabledByDefault,
        Map<Dialect, Severity> dialectSeverity) {

    public enum Category {
        QUALITY,
        PLC,
        SECURITY,
        LOGIC;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ParameterType {
        BOOL,
        INT,
        STRING,
        /** A Java regular expression. */
        REGEX,
        /** Comma-separated values. */
        LIST
    }

    public record Parameter(String name, ParameterType type, String defaultValue, String description) {

        /** Checks that {@code value} is valid for this parameter; returns an error message or null. */
        public String validate(String value) {
            return switch (type) {
                case BOOL -> value.equals("true") || value.equals("false") ? null : "expected true or false";
                case INT -> {
                    try {
                        Integer.parseInt(value);
                        yield null;
                    } catch (NumberFormatException e) {
                        yield "expected an integer";
                    }
                }
                case REGEX -> {
                    try {
                        Pattern.compile(value);
                        yield null;
                    } catch (PatternSyntaxException e) {
                        yield "invalid regular expression: " + e.getDescription();
                    }
                }
                case STRING, LIST -> null;
            };
        }
    }

    /** A code block from the {@code Noncompliant} or {@code Compliant} section. */
    public record Example(boolean compliant, String code) {}

    public RuleDescriptor {
        dialects = Set.copyOf(dialects);
        tags = List.copyOf(tags);
        parameters = List.copyOf(parameters);
        examples = List.copyOf(examples);
        dialectSeverity = Map.copyOf(dialectSeverity);
    }

    public Parameter parameter(String name) {
        return parameters.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(id + " has no parameter " + name));
    }

    public boolean appliesTo(Dialect dialect) {
        return dialect == null || dialects.contains(dialect);
    }
}
