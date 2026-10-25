package dev.plca.devtools;

import dev.plca.devtools.CorpusCheck.FileResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The M12 fix loop's ranking ({@code triage.md}, {@code triage.tsv}): failing files clustered by family and their
 * <em>first</em> error (later errors are mostly cascades of it), ranked by repositories, then files. Noise is removed
 * before this runs.
 */
final class CorpusTriage {

    static final int EXAMPLES = 5;
    private static final int TOP = 15;

    record Cluster(String family, String error, Set<String> repos, List<FileResult> files) {
        Cluster(String family, String error) {
            this(family, error, new LinkedHashSet<>(), new ArrayList<>());
        }
    }

    private CorpusTriage() {}

    /** CODESYS {@code .st}, TwinCAT, Siemens, PLCopen or Rockwell, from the corpus format of a file. */
    static String family(String format) {
        if (format.matches("scl.*|db.*|udt.*")) {
            return "Siemens";
        }
        if (format.startsWith("Tc")) {
            return "TwinCAT";
        }
        if (format.startsWith("plcopen")) {
            return "PLCopen";
        }
        return format.equals("L5X") ? "Rockwell" : "st";
    }

    static List<Cluster> clusters(List<FileResult> results) {
        Map<String, Cluster> byKey = new LinkedHashMap<>();
        for (FileResult r : results) {
            if (r.clean() || r.errors().isEmpty()) {
                continue;
            }
            String family = family(r.format());
            String error = r.errors().getFirst();
            Cluster c = byKey.computeIfAbsent(family + "\t" + error, k -> new Cluster(family, error));
            c.repos().add(r.repo());
            c.files().add(r);
        }
        List<Cluster> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparingInt((Cluster c) -> -c.repos().size())
                .thenComparingInt(c -> -c.files().size())
                .thenComparing(Cluster::family)
                .thenComparing(Cluster::error));
        return out;
    }

    /** Up to {@value #EXAMPLES} first-error locations, each from a different repository. */
    static List<String> examples(Cluster c) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FileResult r : c.files()) {
            if (out.size() == EXAMPLES) {
                break;
            }
            if (seen.add(r.repo())) {
                String line = r.firstError() == null ? "" : ":" + r.firstError().split(" ", 2)[0];
                out.add(r.repo() + "/" + r.path() + line);
            }
        }
        return out;
    }

    static void write(List<FileResult> results, Path reportDir) throws IOException {
        List<Cluster> clusters = clusters(results);
        StringBuilder tsv = new StringBuilder("rank\tfamily\trepos\tfiles\tfirst_error\texamples\n");
        for (int i = 0; i < clusters.size(); i++) {
            Cluster c = clusters.get(i);
            tsv.append(i + 1)
                    .append('\t')
                    .append(c.family())
                    .append('\t')
                    .append(c.repos().size())
                    .append('\t')
                    .append(c.files().size())
                    .append('\t')
                    .append(CorpusCheck.tsvField(c.error()))
                    .append('\t')
                    .append(String.join(" ; ", examples(c)))
                    .append('\n');
        }
        Files.writeString(reportDir.resolve("triage.tsv"), tsv, StandardCharsets.UTF_8);

        Map<String, List<Cluster>> byFamily = new TreeMap<>();
        clusters.forEach(c ->
                byFamily.computeIfAbsent(c.family(), k -> new ArrayList<>()).add(c));
        StringBuilder md = new StringBuilder("# Corpus triage (M12)\n\n");
        md.append("Failing files without noise, clustered by their first error, ranked by repos then files.")
                .append(" All clusters with examples: `triage.tsv`.\n");
        for (var e : byFamily.entrySet()) {
            List<Cluster> cs = e.getValue();
            long files = cs.stream().mapToLong(c -> c.files().size()).sum();
            long repos = cs.stream().flatMap(c -> c.repos().stream()).distinct().count();
            md.append(String.format(
                    Locale.ROOT,
                    "%n## %s — %,d failing files in %,d repos, %,d clusters%n%n| repos | files | first error | e.g. |%n"
                            + "|---|---|---|---|%n",
                    e.getKey(),
                    files,
                    repos,
                    cs.size()));
            cs.stream()
                    .limit(TOP)
                    .forEach(c -> md.append(String.format(
                            Locale.ROOT,
                            "| %d | %d | `%s` | %s |%n",
                            c.repos().size(),
                            c.files().size(),
                            c.error().replace("|", "\\|").replace("`", "'"),
                            examples(c).getFirst())));
        }
        Files.writeString(reportDir.resolve("triage.md"), md, StandardCharsets.UTF_8);
    }
}
