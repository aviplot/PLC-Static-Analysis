package dev.plca.report;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleDescriptor;
import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SARIF 2.1.0 (OASIS). Findings are {@code results}; input problems (syntax errors, unreadable files, rule failures)
 * are {@code toolExecutionNotifications}, the SARIF place for "could not fully analyse this". Regions use UTF-16
 * columns resolved through the source map, so text inside TwinCAT CDATA points at the physical file line.
 */
public final class SarifReport {

    public static final String SCHEMA =
            "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json";
    static final String SRCROOT = "SRCROOT";

    private SarifReport() {}

    public static void write(AnalysisResult r, Writer out) throws IOException {
        ObjectNode root = Json.object();
        root.put("$schema", SCHEMA);
        root.put("version", "2.1.0");
        ObjectNode run = root.putArray("runs").addObject();

        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", ToolInfo.NAME);
        driver.put("version", ToolInfo.VERSION);
        ArrayNode rules = driver.putArray("rules");
        Map<String, Integer> ruleIndex = new HashMap<>();
        for (RuleDescriptor d : r.rules()) {
            ruleIndex.put(d.id(), ruleIndex.size());
            rules.add(rule(d));
        }

        run.putObject("originalUriBaseIds").putObject(SRCROOT).put("uri", r.rootUri());
        run.put("columnKind", "utf16CodeUnits");

        ObjectNode invocation = run.putArray("invocations").addObject();
        invocation.put("executionSuccessful", true);
        ArrayNode notifications = invocation.putArray("toolExecutionNotifications");
        for (Diagnostic d : r.diagnostics()) {
            ObjectNode n = notifications.addObject();
            n.put("level", level(d.severity()));
            n.putObject("message").put("text", d.message());
            n.putObject("descriptor").put("id", d.code());
            n.putArray("locations").add(location(r.sources().resolve(d.span()), null));
        }

        ArrayNode results = run.putArray("results");
        for (Finding f : r.findings()) {
            ObjectNode res = result(r, f, ruleIndex);
            if (r.baseline() != null) {
                res.put("baselineState", "new");
            }
            results.add(res);
        }
        for (AnalysisResult.Suppressed sup : r.suppressed()) {
            ObjectNode res = result(r, sup.finding(), ruleIndex);
            if (sup.kind() == AnalysisResult.SuppressionKind.BASELINE) {
                // SARIF 2.1.0 §3.27.24: existing findings stay in the log, marked as known
                res.put("baselineState", "unchanged");
            } else {
                ObjectNode s = res.putArray("suppressions").addObject();
                s.put("kind", sup.kind() == AnalysisResult.SuppressionKind.INLINE ? "inSource" : "external");
                if (sup.reason() != null && !sup.reason().isEmpty()) {
                    s.put("justification", sup.reason());
                }
                if (r.baseline() != null) {
                    res.put("baselineState", "new");
                }
            }
            results.add(res);
        }

        Json.write(root, out);
        out.write('\n');
        out.flush();
    }

    private static ObjectNode result(AnalysisResult r, Finding f, Map<String, Integer> ruleIndex) {
        ObjectNode res = Json.object();
        res.put("ruleId", f.ruleId());
        Integer idx = ruleIndex.get(f.ruleId());
        if (idx != null) {
            res.put("ruleIndex", idx);
        }
        res.put("level", level(f.severity()));
        res.putObject("message").put("text", f.message());
        ObjectNode loc = location(r.sources().resolve(f.span()), null);
        if (f.place() != null || f.pou() != null) {
            // Rockwell: program → routine → rung, as Studio 5000 shows it; else the POU
            ObjectNode logical = loc.putArray("logicalLocations").addObject();
            logical.put("fullyQualifiedName", f.place() != null ? f.place() : f.pou());
            logical.put("kind", "function");
        }
        res.putArray("locations").add(loc);
        if (!f.related().isEmpty()) {
            ArrayNode related = res.putArray("relatedLocations");
            int id = 1;
            for (Diagnostic.Related rel : f.related()) {
                ObjectNode rl = location(r.sources().resolve(rel.span()), rel.message());
                rl.put("id", id++);
                related.add(rl);
            }
        }
        res.putObject("partialFingerprints").put(Fingerprints.KEY, r.fingerprint(f));
        return res;
    }

    private static ObjectNode rule(RuleDescriptor d) {
        ObjectNode n = Json.object();
        n.put("id", d.id());
        n.put("name", pascalCase(d.title()));
        n.putObject("shortDescription").put("text", d.title());
        n.putObject("fullDescription").put("text", d.description());
        ObjectNode help = n.putObject("help");
        help.put("text", d.description());
        help.put("markdown", d.markdown());
        n.putObject("defaultConfiguration").put("level", level(d.severity()));
        ObjectNode props = n.putObject("properties");
        Set<String> tags = new LinkedHashSet<>();
        tags.add(d.category().key());
        tags.addAll(d.tags());
        if (d.cwe() != null) {
            // the tag form GitHub code scanning recognises
            tags.add("external/cwe/" + d.cwe().toLowerCase(Locale.ROOT));
        }
        ArrayNode tagArray = props.putArray("tags"); // SARIF requires unique tags
        tags.forEach(tagArray::add);
        if (d.plcopen() != null) {
            props.put("plcopen", d.plcopen());
        }
        return n;
    }

    private static ObjectNode location(Location l, String message) {
        ObjectNode n = Json.object();
        ObjectNode physical = n.putObject("physicalLocation");
        ObjectNode artifact = physical.putObject("artifactLocation");
        artifact.put("uri", relativeUri(l.path()));
        artifact.put("uriBaseId", SRCROOT);
        ObjectNode region = physical.putObject("region");
        region.put("startLine", l.startLine());
        region.put("startColumn", l.startColumn());
        region.put("endLine", l.endLine());
        region.put("endColumn", l.endColumn());
        if (message != null) {
            n.putObject("message").put("text", message);
        }
        return n;
    }

    /** Percent-encodes each segment of a relative path ({@code a b/c.st} becomes {@code a%20b/c.st}). */
    static String relativeUri(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.replace('\\', '/').split("/", -1)) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    static String level(Severity s) {
        return switch (s) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO, HINT -> "note";
        };
    }

    private static String pascalCase(String title) {
        StringBuilder sb = new StringBuilder();
        for (String w : title.split("[^A-Za-z0-9]+")) {
            if (!w.isEmpty()) {
                String rest = w.substring(1);
                // CASE, ELSE, VAR_INPUT: keywords read as words
                sb.append(Character.toUpperCase(w.charAt(0)))
                        .append(w.equals(w.toUpperCase(Locale.ROOT)) ? rest.toLowerCase(Locale.ROOT) : rest);
            }
        }
        return sb.toString();
    }
}
