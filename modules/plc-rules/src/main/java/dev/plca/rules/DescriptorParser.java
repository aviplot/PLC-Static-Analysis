package dev.plca.rules;

import dev.plca.core.Dialect;
import dev.plca.core.diag.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads rule descriptors: a front matter block of {@code key: value} lines between {@code ---} lines, followed by
 * Markdown. Parameters are indented lines under {@code parameters:} of the form {@code name: type = default |
 * description} (split at the last {@code " | "}, so regex defaults may use {@code |}). Deliberately not YAML: the
 * format is ours and small.
 */
public final class DescriptorParser {

    private static final Pattern PARAMETER =
            Pattern.compile("^\\s+([A-Za-z]\\w*)\\s*:\\s*(\\w+)\\s*=\\s*(.*)\\s+\\|\\s+(.+)$");
    private static final Pattern CODE_BLOCK = Pattern.compile("(?ms)^```st\\s*\\n(.*?)^```");

    private DescriptorParser() {}

    /** Loads {@code dev/plca/rules/<id>.md} from the class path. */
    public static RuleDescriptor load(String id) {
        String resource = "/dev/plca/rules/" + id + ".md";
        try (InputStream in = DescriptorParser.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing rule descriptor " + resource);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static RuleDescriptor parse(String text) {
        String t = text.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) {
            throw new IllegalArgumentException("descriptor must start with a --- front matter line");
        }
        int end = t.indexOf("\n---\n", 3);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated front matter");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        List<RuleDescriptor.Parameter> parameters = new ArrayList<>();
        boolean inParameters = false;
        for (String line : t.substring(4, end).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (inParameters && Character.isWhitespace(line.charAt(0))) {
                Matcher m = PARAMETER.matcher(line);
                if (!m.matches()) {
                    throw new IllegalArgumentException("bad parameter line: " + line);
                }
                RuleDescriptor.Parameter p = new RuleDescriptor.Parameter(
                        m.group(1),
                        RuleDescriptor.ParameterType.valueOf(m.group(2).toUpperCase(Locale.ROOT)),
                        m.group(3),
                        m.group(4));
                String error = p.validate(p.defaultValue());
                if (error != null) {
                    throw new IllegalArgumentException("default of " + p.name() + ": " + error);
                }
                parameters.add(p);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("bad front matter line: " + line);
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            inParameters = key.equals("parameters");
            if (!inParameters) {
                fields.put(key, value);
            }
        }
        String markdown = t.substring(end + 5).strip() + "\n";
        return new RuleDescriptor(
                required(fields, "id"),
                required(fields, "title"),
                severity(required(fields, "severity")),
                RuleDescriptor.Category.valueOf(required(fields, "category").toUpperCase(Locale.ROOT)),
                dialects(fields.getOrDefault("dialects", "all")),
                list(fields.getOrDefault("tags", "")),
                emptyToNull(fields.get("plcopen")),
                emptyToNull(fields.get("cwe")),
                parameters,
                firstParagraph(section(markdown, "Description")),
                markdown,
                examples(markdown),
                !fields.getOrDefault("enabled", "true").equalsIgnoreCase("false"),
                dialectSeverity(fields.getOrDefault("dialectSeverity", "")));
    }

    private static String required(Map<String, String> fields, String key) {
        String v = fields.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("front matter lacks " + key);
        }
        return v;
    }

    private static Severity severity(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "error" -> Severity.ERROR;
            case "warning" -> Severity.WARNING;
            case "info" -> Severity.INFO;
            case "hint" -> Severity.HINT;
            default -> throw new IllegalArgumentException("unknown severity " + s);
        };
    }

    /** {@code rockwell=info, siemens=warning}. */
    private static Map<Dialect, Severity> dialectSeverity(String s) {
        Map<Dialect, Severity> r = new java.util.EnumMap<>(Dialect.class);
        for (String pair : list(s)) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("dialectSeverity expects dialect=severity, got " + pair);
            }
            r.put(
                    Dialect.valueOf(pair.substring(0, eq).strip().toUpperCase(Locale.ROOT)),
                    severity(pair.substring(eq + 1).strip()));
        }
        return r;
    }

    private static Set<Dialect> dialects(String s) {
        if (s.equalsIgnoreCase("all")) {
            return EnumSet.allOf(Dialect.class);
        }
        Set<Dialect> r = EnumSet.noneOf(Dialect.class);
        list(s).forEach(d -> r.add(Dialect.valueOf(d.toUpperCase(Locale.ROOT))));
        return r;
    }

    /** {@code [a, b]} or {@code a, b}. */
    private static List<String> list(String s) {
        String v = s.strip();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isBlank()
                ? List.of()
                : Arrays.stream(v.split(",")).map(String::strip).toList();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || s.equals("-") ? null : s;
    }

    /** Text of {@code ## name} up to the next {@code ## } heading, or "". */
    static String section(String markdown, String name) {
        Matcher m = Pattern.compile("(?m)^## " + Pattern.quote(name) + "\\s*$").matcher(markdown);
        if (!m.find()) {
            return "";
        }
        int start = m.end();
        Matcher next = Pattern.compile("(?m)^## ").matcher(markdown);
        int end = next.find(start) ? next.start() : markdown.length();
        return markdown.substring(start, end).strip();
    }

    private static String firstParagraph(String s) {
        int i = s.indexOf("\n\n");
        return (i < 0 ? s : s.substring(0, i)).replace('\n', ' ').strip();
    }

    private static List<RuleDescriptor.Example> examples(String markdown) {
        List<RuleDescriptor.Example> r = new ArrayList<>();
        for (boolean compliant : new boolean[] {false, true}) {
            Matcher m = CODE_BLOCK.matcher(section(markdown, compliant ? "Compliant" : "Noncompliant"));
            while (m.find()) {
                r.add(new RuleDescriptor.Example(compliant, m.group(1)));
            }
        }
        return r;
    }
}
