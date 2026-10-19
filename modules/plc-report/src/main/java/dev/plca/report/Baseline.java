package dev.plca.report;

import dev.plca.core.source.Location;
import dev.plca.core.source.SourceRegistry;
import dev.plca.rules.Finding;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A baseline: the fingerprints of the findings a project accepted as existing debt, so later runs report only new ones.
 * JSON, sorted by path, rule and fingerprint, so it diffs well under version control. Path, POU and message are stored
 * for the reader only; matching uses the fingerprint ({@link Fingerprints}: rule, POU, normalised line text,
 * occurrence), which survives code moving to other lines.
 */
public final class Baseline {

    static final int VERSION = 1;

    /** A baseline file that cannot be read or is not a baseline. */
    public static final class BaselineException extends Exception {
        private static final long serialVersionUID = 1L;

        BaselineException(String message) {
            super(message);
        }
    }

    private final Set<String> fingerprints;

    private Baseline(Set<String> fingerprints) {
        this.fingerprints = fingerprints;
    }

    public boolean contains(String fingerprint) {
        return fingerprints.contains(fingerprint);
    }

    public int size() {
        return fingerprints.size();
    }

    public static Baseline read(Path file) throws BaselineException, IOException {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new BaselineException(file + ": not valid JSON: " + e.getOriginalMessage());
        }
        if (!root.isObject() || !root.path("findings").isArray()) {
            throw new BaselineException(file + ": not a plca baseline (no findings array)");
        }
        if (!Fingerprints.KEY.equals(root.path("fingerprint").asString(""))) {
            throw new BaselineException(file + ": written with fingerprint recipe "
                    + root.path("fingerprint").asString("?") + "; this version uses " + Fingerprints.KEY
                    + ", write the baseline again");
        }
        Set<String> fps = new HashSet<>();
        for (JsonNode f : root.path("findings")) {
            String fp = f.path("fingerprint").asString("");
            if (!fp.isEmpty()) {
                fps.add(fp);
            }
        }
        return new Baseline(fps);
    }

    /** Writes the baseline of {@code findings} (with their fingerprints, in the same order). */
    public static void write(List<Finding> findings, List<String> fingerprints, SourceRegistry sources, Writer out)
            throws IOException {
        record Entry(String path, String rule, String fingerprint, String pou, String message) {}
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            Location l = sources.resolve(f.span());
            entries.add(new Entry(l.path(), f.ruleId(), fingerprints.get(i), f.pou(), f.message()));
        }
        entries.sort(
                Comparator.comparing(Entry::path).thenComparing(Entry::rule).thenComparing(Entry::fingerprint));
        ObjectNode root = Json.object();
        root.put("version", VERSION);
        root.put("tool", ToolInfo.NAME);
        root.put("fingerprint", Fingerprints.KEY);
        ArrayNode list = root.putArray("findings");
        for (Entry e : entries) {
            ObjectNode n = list.addObject();
            n.put("fingerprint", e.fingerprint());
            n.put("rule", e.rule());
            n.put("path", e.path());
            if (e.pou() != null) {
                n.put("pou", e.pou());
            }
            n.put("message", e.message());
        }
        Json.write(root, out);
        out.write('\n');
        out.flush();
    }
}
