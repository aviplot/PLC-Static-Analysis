package dev.plca.analysis;

import dev.plca.core.Dialect;
import dev.plca.core.diag.Severity;
import dev.plca.rules.RuleConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code plca.yaml}: rule selection, severities, parameters, per-dialect overrides, suppressions, baseline and
 * {@code fail-on}. Read with Jackson's streaming YAML parser so every error names its line. Unknown keys are errors: a
 * misspelt key silently ignored would leave a rule on that the user meant to turn off.
 *
 * <pre>
 * rules:
 *   select: [default]
 *   disable: [PLCA-Q014]
 *   severity: {PLCA-Q013: warning}
 *   parameters: {PLCA-Q013: {maxNesting: 4}}
 *   dialects:
 *     rockwell: {severity: {PLCA-Q003: info}, disable: [PLCA-Q015]}
 * suppress:
 *   - {rule: PLCA-P002, path: "io_mapping/**", reason: generated}
 * baseline: plca-baseline.json
 * fail-on: error
 * </pre>
 */
public record ConfigFile(
        Path path, Rules rules, List<RuleConfig.Suppression> suppressions, Path baseline, String failOn) {

    public static final String NAME = "plca.yaml";

    /** The {@code rules:} section; null fields were not given. */
    public record Rules(
            List<String> select,
            List<String> disable,
            Map<String, Map<String, String>> parameters,
            Map<String, Severity> severity,
            Map<Dialect, RuleConfig.DialectConfig> dialects) {}

    /** A configuration error, with the file and line. */
    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;

        public ConfigException(String message) {
            super(message);
        }
    }

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    /** Parsed YAML with the line of every value. */
    private sealed interface Node permits MapNode, ListNode, Scalar {
        int line();
    }

    private record MapNode(Map<String, Node> entries, Map<String, Integer> keyLines, int line) implements Node {}

    private record ListNode(List<Node> items, int line) implements Node {}

    private record Scalar(String value, int line) implements Node {}

    public static ConfigFile read(Path file) throws ConfigException, IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return parse(file, text);
    }

    public static ConfigFile parse(Path file, String text) throws ConfigException {
        Node root;
        try (JsonParser p = YAML.createParser(text)) {
            JsonToken t = p.nextToken();
            root = t == null ? new MapNode(Map.of(), Map.of(), 1) : node(p);
        } catch (JacksonException e) {
            int line = e.getLocation() == null ? 0 : e.getLocation().getLineNr();
            throw new ConfigException(file + ":" + line + ": invalid YAML: " + e.getOriginalMessage());
        }
        return new Reader(file).config(root);
    }

    /**
     * The rule configuration of {@code file} (null: none) with command-line options on top: {@code rules} replaces the
     * selection and the file's disable list, {@code disable} adds, {@code parameters} override.
     *
     * @param rules null: the file's selection, else {@code default}
     */
    public static RuleConfig ruleConfig(
            ConfigFile file, List<String> rules, List<String> disable, Map<String, Map<String, String>> parameters) {
        Rules r = file == null ? null : file.rules();
        List<String> select = rules != null ? rules : r != null && r.select() != null ? r.select() : List.of("default");
        // an explicit --rules is the whole selection: the file's disable list does not apply to it
        List<String> off = new ArrayList<>(rules == null && r != null && r.disable() != null ? r.disable() : List.of());
        off.addAll(disable);
        Map<String, Map<String, String>> params = new LinkedHashMap<>();
        if (r != null) {
            r.parameters().forEach((k, v) -> params.put(k, new LinkedHashMap<>(v)));
        }
        parameters.forEach(
                (k, v) -> params.computeIfAbsent(k, x -> new LinkedHashMap<>()).putAll(v));
        return new RuleConfig(
                select,
                off,
                params,
                r == null ? Map.of() : r.severity(),
                r == null ? Map.of() : r.dialects(),
                file == null ? List.of() : file.suppressions());
    }

    private static Node node(JsonParser p) {
        int line = p.currentTokenLocation().getLineNr();
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_OBJECT) {
            Map<String, Node> entries = new LinkedHashMap<>();
            Map<String, Integer> keyLines = new LinkedHashMap<>();
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String key = p.currentName();
                int keyLine = p.currentTokenLocation().getLineNr();
                p.nextToken();
                entries.put(key, node(p));
                keyLines.put(key, keyLine);
            }
            return new MapNode(entries, keyLines, line);
        }
        if (t == JsonToken.START_ARRAY) {
            List<Node> items = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                items.add(node(p));
            }
            return new ListNode(items, line);
        }
        return new Scalar(t == JsonToken.VALUE_NULL ? null : p.getString(), line);
    }

    /** Turns the tree into a configuration; knows the file for error messages. */
    private record Reader(Path file) {

        ConfigFile config(Node root) throws ConfigException {
            MapNode m = map(root, "the top level");
            keys(m, "the top level", Set.of("rules", "suppress", "baseline", "fail-on"));
            Rules rules = m.entries().containsKey("rules") ? rules(m.entries().get("rules")) : null;
            List<RuleConfig.Suppression> suppress = new ArrayList<>();
            if (m.entries().containsKey("suppress")) {
                for (Node n : list(m.entries().get("suppress"), "suppress")) {
                    suppress.add(suppression(n));
                }
            }
            Path baseline = null;
            if (m.entries().containsKey("baseline")) {
                String b = scalar(m.entries().get("baseline"), "baseline");
                baseline = b == null
                        ? null
                        : file.toAbsolutePath().getParent().resolve(b).normalize();
            }
            String failOn = null;
            if (m.entries().containsKey("fail-on")) {
                Node n = m.entries().get("fail-on");
                failOn = scalar(n, "fail-on");
                if (failOn == null
                        || !Set.of("error", "warning", "info", "never").contains(failOn.toLowerCase(Locale.ROOT))) {
                    throw error(n, "fail-on must be error, warning, info or never");
                }
            }
            return new ConfigFile(file, rules, suppress, baseline, failOn);
        }

        private Rules rules(Node n) throws ConfigException {
            MapNode m = map(n, "rules");
            keys(m, "rules", Set.of("select", "disable", "severity", "parameters", "dialects"));
            Map<Dialect, RuleConfig.DialectConfig> dialects = new LinkedHashMap<>();
            if (m.entries().containsKey("dialects")) {
                MapNode dm = map(m.entries().get("dialects"), "rules.dialects");
                for (Map.Entry<String, Node> e : dm.entries().entrySet()) {
                    Dialect d = dialect(e.getKey(), dm.keyLines().get(e.getKey()));
                    String where = "rules.dialects." + e.getKey();
                    MapNode one = map(e.getValue(), where);
                    keys(one, where, Set.of("select", "disable", "severity", "parameters"));
                    dialects.put(
                            d,
                            new RuleConfig.DialectConfig(
                                    strings(one, "select", where),
                                    strings(one, "disable", where),
                                    parameters(one, where),
                                    severities(one, where)));
                }
            }
            return new Rules(
                    m.entries().containsKey("select") ? strings(m, "select", "rules") : null,
                    m.entries().containsKey("disable") ? strings(m, "disable", "rules") : null,
                    parameters(m, "rules"),
                    severities(m, "rules"),
                    dialects);
        }

        private RuleConfig.Suppression suppression(Node n) throws ConfigException {
            MapNode m = map(n, "a suppress entry");
            keys(m, "a suppress entry", Set.of("rule", "path", "pou", "reason"));
            String rule = m.entries().containsKey("rule") ? scalar(m.entries().get("rule"), "rule") : null;
            String path = m.entries().containsKey("path") ? scalar(m.entries().get("path"), "path") : null;
            String pou = m.entries().containsKey("pou") ? scalar(m.entries().get("pou"), "pou") : null;
            String reason =
                    m.entries().containsKey("reason") ? scalar(m.entries().get("reason"), "reason") : null;
            try {
                return new RuleConfig.Suppression(rule, path, pou, reason);
            } catch (IllegalArgumentException e) {
                throw error(n, e.getMessage());
            }
        }

        private Map<String, Map<String, String>> parameters(MapNode parent, String where) throws ConfigException {
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            Node n = parent.entries().get("parameters");
            if (n == null) {
                return out;
            }
            MapNode m = map(n, where + ".parameters");
            for (Map.Entry<String, Node> e : m.entries().entrySet()) {
                MapNode params = map(e.getValue(), where + ".parameters." + e.getKey());
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, Node> p : params.entries().entrySet()) {
                    Node v = p.getValue();
                    if (v instanceof ListNode l) {
                        // list parameters may be written as YAML sequences
                        List<String> items = new ArrayList<>();
                        for (Node i : l.items()) {
                            items.add(scalar(i, p.getKey()));
                        }
                        values.put(p.getKey(), String.join(",", items));
                    } else {
                        String s = scalar(v, p.getKey());
                        values.put(p.getKey(), s == null ? "" : s);
                    }
                }
                out.put(e.getKey().toUpperCase(Locale.ROOT), values);
            }
            return out;
        }

        private Map<String, Severity> severities(MapNode parent, String where) throws ConfigException {
            Map<String, Severity> out = new LinkedHashMap<>();
            Node n = parent.entries().get("severity");
            if (n == null) {
                return out;
            }
            MapNode m = map(n, where + ".severity");
            for (Map.Entry<String, Node> e : m.entries().entrySet()) {
                String s = scalar(e.getValue(), e.getKey());
                Severity sev = switch (s == null ? "" : s.toLowerCase(Locale.ROOT)) {
                    case "error" -> Severity.ERROR;
                    case "warning" -> Severity.WARNING;
                    case "info" -> Severity.INFO;
                    case "hint" -> Severity.HINT;
                    default -> throw error(e.getValue(), "severity must be error, warning, info or hint, got " + s);
                };
                out.put(e.getKey().toUpperCase(Locale.ROOT), sev);
            }
            return out;
        }

        private List<String> strings(MapNode parent, String key, String where) throws ConfigException {
            Node n = parent.entries().get(key);
            if (n == null) {
                return List.of();
            }
            if (n instanceof Scalar s) {
                return s.value() == null ? List.of() : List.of(s.value().split("\\s*,\\s*"));
            }
            List<String> out = new ArrayList<>();
            for (Node i : list(n, where + "." + key)) {
                out.add(scalar(i, where + "." + key));
            }
            return out;
        }

        private Dialect dialect(String name, int line) throws ConfigException {
            try {
                return Dialect.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(file + ":" + line + ": unknown dialect " + name
                        + " (codesys, twincat, siemens, rockwell, plcopen)");
            }
        }

        private void keys(MapNode m, String where, Set<String> allowed) throws ConfigException {
            for (String k : m.entries().keySet()) {
                if (!allowed.contains(k)) {
                    throw new ConfigException(file + ":" + m.keyLines().get(k) + ": unknown key '" + k + "' in " + where
                            + " (expected one of "
                            + String.join(", ", allowed.stream().sorted().toList()) + ")");
                }
            }
        }

        private MapNode map(Node n, String where) throws ConfigException {
            if (n instanceof MapNode m) {
                return m;
            }
            if (n instanceof Scalar s && s.value() == null) {
                return new MapNode(Map.of(), Map.of(), s.line());
            }
            throw error(n, where + " must be a mapping");
        }

        private List<Node> list(Node n, String where) throws ConfigException {
            if (n instanceof ListNode l) {
                return l.items();
            }
            throw error(n, where + " must be a list");
        }

        private String scalar(Node n, String where) throws ConfigException {
            if (n instanceof Scalar s) {
                return s.value();
            }
            throw error(n, where + " must be a single value");
        }

        private ConfigException error(Node n, String message) {
            return new ConfigException(file + ":" + n.line() + ": " + message);
        }
    }
}
