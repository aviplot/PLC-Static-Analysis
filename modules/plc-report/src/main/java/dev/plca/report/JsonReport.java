package dev.plca.report;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleDescriptor;
import java.io.IOException;
import java.io.Writer;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Our own JSON report ({@code schemaVersion} 1, schema in {@code docs/report-json.schema.json}). Stable for scripts:
 * fields are only added, never renamed, without a schema version bump.
 */
public final class JsonReport {

    public static final int SCHEMA_VERSION = 1;

    private JsonReport() {}

    public static void write(AnalysisResult r, Writer out) throws IOException {
        ObjectNode root = Json.object();
        root.put("schemaVersion", SCHEMA_VERSION);
        ObjectNode tool = root.putObject("tool");
        tool.put("name", ToolInfo.NAME);
        tool.put("version", ToolInfo.VERSION);
        root.put("root", r.rootUri());

        ArrayNode rules = root.putArray("rules");
        for (RuleDescriptor d : r.rules()) {
            ObjectNode n = rules.addObject();
            n.put("id", d.id());
            n.put("title", d.title());
            n.put("severity", Json.severity(d.severity()));
            n.put("category", d.category().key());
            if (d.plcopen() != null) {
                n.put("plcopen", d.plcopen());
            }
            if (d.cwe() != null) {
                n.put("cwe", d.cwe());
            }
            ArrayNode tags = n.putArray("tags");
            d.tags().forEach(tags::add);
        }

        ArrayNode findings = root.putArray("findings");
        Map<Severity, Integer> bySeverity = new EnumMap<>(Severity.class);
        for (Finding f : r.findings()) {
            bySeverity.merge(f.severity(), 1, Integer::sum);
            findings.add(finding(r, f));
        }
        ArrayNode suppressed = root.putArray("suppressed");
        for (AnalysisResult.Suppressed s : r.suppressed()) {
            ObjectNode n = finding(r, s.finding());
            n.put("suppression", s.kind().name().toLowerCase(Locale.ROOT));
            if (s.reason() != null && !s.reason().isEmpty()) {
                n.put("reason", s.reason());
            }
            suppressed.add(n);
        }
        if (r.baseline() != null) {
            ObjectNode b = root.putObject("baseline");
            b.put("file", r.baseline().file());
            b.put("unchanged", r.baseline().unchanged());
            b.put("fixed", r.baseline().fixed());
        }

        ArrayNode diagnostics = root.putArray("diagnostics");
        for (Diagnostic d : r.diagnostics()) {
            ObjectNode n = diagnostics.addObject();
            n.put("code", d.code());
            n.put("severity", Json.severity(d.severity()));
            n.put("message", d.message());
            n.set("location", Json.location(r.sources().resolve(d.span())));
        }

        AnalysisResult.Stats s = r.stats();
        ObjectNode stats = root.putObject("stats");
        stats.put("files", s.files());
        stats.put("lines", s.lines());
        stats.put("pous", s.pous());
        stats.put("findings", r.findings().size());
        ObjectNode sup = stats.putObject("suppressed");
        for (AnalysisResult.SuppressionKind k : AnalysisResult.SuppressionKind.values()) {
            sup.put(k.name().toLowerCase(Locale.ROOT), r.suppressed(k).size());
        }
        ObjectNode sev = stats.putObject("findingsBySeverity");
        for (Severity v : Severity.values()) {
            sev.put(Json.severity(v), bySeverity.getOrDefault(v, 0));
        }
        ObjectNode time = stats.putObject("timeMillis");
        time.put("parse", s.parseMillis());
        time.put("model", s.modelMillis());
        time.put("rules", s.rulesMillis());
        stats.put("resolutionRate", Math.round(s.resolutionRate() * 10000) / 10000.0);
        ArrayNode skipped = stats.putArray("skippedFiles");
        s.skippedFiles().forEach(skipped::add);

        Json.write(root, out);
        out.write('\n');
        out.flush();
    }

    private static ObjectNode finding(AnalysisResult r, Finding f) {
        ObjectNode n = Json.object();
        n.put("ruleId", f.ruleId());
        n.put("severity", Json.severity(f.severity()));
        n.put("message", f.message());
        if (f.place() != null) {
            n.put("place", f.place());
        }
        if (f.pou() != null) {
            n.put("pou", f.pou());
        }
        n.set("location", Json.location(r.sources().resolve(f.span())));
        ArrayNode related = n.putArray("related");
        for (Diagnostic.Related rel : f.related()) {
            ObjectNode x = related.addObject();
            x.put("message", rel.message());
            x.set("location", Json.location(r.sources().resolve(rel.span())));
        }
        n.put("fingerprint", r.fingerprint(f));
        return n;
    }
}
