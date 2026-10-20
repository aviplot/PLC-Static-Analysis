package dev.plca.web;

import dev.plca.analysis.Analyzer;
import dev.plca.analysis.ConfigFile;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceRegistry;
import dev.plca.report.AnalysisResult;
import dev.plca.report.Baseline;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Whole-project analyses for the API: report downloads and the comparison of two projects. Runs {@link Analyzer} as
 * {@code plca analyze} does, with the project's {@code plca.yaml} and baseline (only from inside the project), and with
 * the project's root shown as {@code file:///project/}.
 */
final class Analyses {

    private Analyses() {}

    /** A finding in API form: 0-based LSP-style range. */
    record Item(
            String status,
            String rule,
            String severity,
            String message,
            String path,
            int startLine,
            int startCharacter,
            int endLine,
            int endCharacter) {}

    record RuleDelta(String rule, int added, int fixed, int unchanged) {}

    record FileDelta(String path, String change, int added, int fixed) {}

    record Comparison(
            String base,
            String head,
            int added,
            int fixed,
            int unchanged,
            List<RuleDelta> rules,
            List<FileDelta> files,
            List<Item> findings) {}

    static AnalysisResult analyze(Path dir, RuleRegistry registry, int threads)
            throws ConfigFile.ConfigException, Baseline.BaselineException, IOException {
        Path file = dir.resolve(ConfigFile.NAME);
        ConfigFile config = Files.isRegularFile(file) ? ConfigFile.read(file) : null;
        RuleConfig rules = ConfigFile.ruleConfig(config, null, List.of(), Map.of());
        Baseline baseline = null;
        String baselineName = null;
        if (config != null && config.baseline() != null) {
            Path b = config.baseline().toAbsolutePath().normalize();
            if (!b.startsWith(dir.toAbsolutePath().normalize())) {
                throw new ConfigFile.ConfigException("the baseline must be a file of the project");
            }
            baseline = Baseline.read(b);
            baselineName = dir.toAbsolutePath().normalize().relativize(b).toString();
        }
        AnalysisResult r =
                new Analyzer(registry).withDrawings(true).analyze(dir, rules, threads, baseline, baselineName);
        return new AnalysisResult(
                LspSocket.PREFIX,
                r.rules(),
                r.findings(),
                r.diagnostics(),
                r.sources(),
                r.stats(),
                r.suppressed(),
                r.baseline(),
                r.fingerprints(),
                r.drawings());
    }

    /** Findings of {@code head} classified against {@code base} by fingerprint (line-number independent). */
    static Comparison compare(Projects.Project base, AnalysisResult b, Projects.Project head, AnalysisResult h)
            throws IOException {
        Set<String> inBase = new HashSet<>();
        for (Finding f : b.findings()) {
            inBase.add(b.fingerprint(f));
        }
        Set<String> inHead = new HashSet<>();
        for (Finding f : h.findings()) {
            inHead.add(h.fingerprint(f));
        }
        List<Item> items = new ArrayList<>();
        Map<String, int[]> perRule = new TreeMap<>();
        Map<String, int[]> perFile = new HashMap<>();
        int added = 0;
        int fixed = 0;
        int unchanged = 0;
        for (Finding f : h.findings()) {
            boolean known = inBase.contains(h.fingerprint(f));
            String status = known ? "unchanged" : "new";
            if (known) {
                unchanged++;
            } else {
                added++;
            }
            Item item = item(status, f, h.sources());
            items.add(item);
            perRule.computeIfAbsent(f.ruleId(), k -> new int[3])[known ? 2 : 0]++;
            if (!known) {
                perFile.computeIfAbsent(item.path(), k -> new int[2])[0]++;
            }
        }
        for (Finding f : b.findings()) {
            if (!inHead.contains(b.fingerprint(f))) {
                fixed++;
                Item item = item("fixed", f, b.sources());
                items.add(item);
                perRule.computeIfAbsent(f.ruleId(), k -> new int[3])[1]++;
                perFile.computeIfAbsent(item.path(), k -> new int[2])[1]++;
            }
        }
        List<RuleDelta> rules = new ArrayList<>();
        perRule.forEach((rule, n) -> rules.add(new RuleDelta(rule, n[0], n[1], n[2])));

        Map<String, String> changes = fileChanges(base, head);
        List<FileDelta> files = new ArrayList<>();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            int[] n = perFile.getOrDefault(e.getKey(), new int[2]);
            files.add(new FileDelta(e.getKey(), e.getValue(), n[0], n[1]));
        }
        return new Comparison(base.id, head.id, added, fixed, unchanged, rules, files, items);
    }

    private static Item item(String status, Finding f, SourceRegistry sources) {
        Location l = sources.resolve(f.span());
        String message = f.place() == null ? f.message() : f.message() + " (" + f.place() + ")";
        return new Item(
                status,
                f.ruleId(),
                f.severity().name().toLowerCase(java.util.Locale.ROOT),
                message,
                l.path(),
                l.startLine() - 1,
                l.startColumn() - 1,
                l.endLine() - 1,
                l.endColumn() - 1);
    }

    /** Every file of either project: added, removed, changed or unchanged (by content). */
    static Map<String, String> fileChanges(Projects.Project base, Projects.Project head) throws IOException {
        Set<String> all = new TreeSet<>(base.files);
        all.addAll(head.files);
        Set<String> inBase = new HashSet<>(base.files);
        Set<String> inHead = new HashSet<>(head.files);
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : all) {
            if (!inBase.contains(path)) {
                out.put(path, "added");
            } else if (!inHead.contains(path)) {
                out.put(path, "removed");
            } else {
                boolean same = hash(base.dir.resolve(path)).equals(hash(head.dir.resolve(path)));
                out.put(path, same ? "unchanged" : "changed");
            }
        }
        return out;
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
