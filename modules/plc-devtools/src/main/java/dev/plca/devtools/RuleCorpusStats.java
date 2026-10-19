package dev.plca.devtools;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleDescriptor;
import dev.plca.rules.RuleEngine;
import dev.plca.rules.RuleRegistry;
import dev.plca.semantic.SemanticModel;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Rules on the corpus: crash-freedom ({@link RuleEngine#INTERNAL} must never occur), findings per rule per 1k lines,
 * time per rule, and random samples per rule for manual false-positive review ({@code rule-samples/<id>.md}).
 */
final class RuleCorpusStats {

    static final RuleRegistry REGISTRY = RuleRegistry.standard();
    private static final RuleEngine ENGINE = new RuleEngine(REGISTRY);
    static final int SAMPLES_PER_PROJECT = 3;
    static final int SAMPLES_PER_RULE = 20;

    /** One finding prepared for review. */
    record Sample(String ruleId, String where, String message, String line) {}

    /** Rule results of one project; {@code group} is the corpus-check group (TwinCat, st, Siemens). */
    record Outcome(
            String group,
            Map<String, Integer> counts,
            Map<String, Long> nanos,
            List<String> failures,
            List<Sample> samples) {
        static final Outcome NONE = new Outcome("", Map.of(), Map.of(), List.of(), List.of());
    }

    private RuleCorpusStats() {}

    /** Runs all rules single-threaded (projects already run in parallel) and samples findings. */
    static Outcome run(String repo, String key, String group, SemanticModel model, SourceRegistry sources) {
        RuleEngine.Result r = ENGINE.run(model, sources, RuleConfig.defaults(), 1);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<Finding>> byRule = new LinkedHashMap<>();
        for (Finding f : r.findings()) {
            counts.merge(f.ruleId(), 1, Integer::sum);
            byRule.computeIfAbsent(f.ruleId(), k -> new ArrayList<>()).add(f);
        }
        List<Sample> samples = new ArrayList<>();
        Random random = new Random(key.hashCode());
        byRule.forEach((rule, fs) -> {
            List<Finding> pick = new ArrayList<>(fs);
            Collections.shuffle(pick, random);
            for (Finding f : pick.subList(0, Math.min(SAMPLES_PER_PROJECT, pick.size()))) {
                Location l = sources.resolve(f.span());
                SourceFile file = sources.text(f.span().textId()).file();
                samples.add(new Sample(
                        rule, repo + "/" + l.path() + ":" + l.startLine(), f.message(), line(file, l.startLine())));
            }
        });
        List<String> failures = r.diagnostics().stream()
                .filter(d -> d.code().equals(RuleEngine.INTERNAL))
                .map(Diagnostic::message)
                .toList();
        return new Outcome(group, counts, r.nanosPerRule(), failures, samples);
    }

    private static String line(SourceFile file, int line) {
        String text = file.text();
        int start = file.lineIndex().lineStart(line);
        int end = start;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        String s = text.substring(start, end).strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * Appends the rules section to {@code summary.md} and writes samples: one table for the whole corpus, one per group
     * with its own samples ({@code rule-samples/<group>/}), so each dialect gets its own false-positive review.
     *
     * @return the number of rule failures (must be 0)
     */
    static int report(
            List<Outcome> outcomes,
            long totalLines,
            Map<String, Long> linesByGroup,
            long parseAndModelNanos,
            PrintStream out,
            Path reportDir)
            throws IOException {
        int failures = report("", outcomes, totalLines, parseAndModelNanos, out, reportDir);
        Map<String, List<Outcome>> byGroup = new java.util.TreeMap<>();
        outcomes.forEach(
                o -> byGroup.computeIfAbsent(o.group(), k -> new ArrayList<>()).add(o));
        for (var e : byGroup.entrySet()) {
            if (!e.getKey().isEmpty()) {
                report(e.getKey(), e.getValue(), linesByGroup.getOrDefault(e.getKey(), 0L), 0, out, reportDir);
            }
        }
        return failures;
    }

    private static int report(
            String group,
            List<Outcome> outcomes,
            long totalLines,
            long parseAndModelNanos,
            PrintStream out,
            Path reportDir)
            throws IOException {
        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> nanos = new HashMap<>();
        Map<String, Integer> projectsWith = new HashMap<>();
        Map<String, List<Sample>> samples = new HashMap<>();
        List<String> failures = new ArrayList<>();
        for (Outcome o : outcomes) {
            o.counts().forEach((k, v) -> {
                counts.merge(k, (long) v, Long::sum);
                projectsWith.merge(k, 1, Integer::sum);
            });
            o.nanos().forEach((k, v) -> nanos.merge(k, v, Long::sum));
            o.samples()
                    .forEach(s -> samples.computeIfAbsent(s.ruleId(), k -> new ArrayList<>())
                            .add(s));
            failures.addAll(o.failures());
        }
        long ruleNanos = nanos.values().stream().mapToLong(Long::longValue).sum();
        StringBuilder md = new StringBuilder(group.isEmpty() ? "\n## Rules\n\n" : "\n## Rules in " + group + "\n\n");
        md.append("| rule | title | findings | per 1k lines | projects | time |\n|---|---|---|---|---|---|\n");
        for (RuleDescriptor d : REGISTRY.descriptors()) {
            long n = counts.getOrDefault(d.id(), 0L);
            md.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %,d | %.2f | %,d | %,.0f ms |%n",
                    d.id(),
                    d.title(),
                    n,
                    1000.0 * n / Math.max(1, totalLines),
                    projectsWith.getOrDefault(d.id(), 0),
                    nanos.getOrDefault(d.id(), 0L) / 1e6));
        }
        if (group.isEmpty()) {
            md.append(String.format(
                    Locale.ROOT,
                    "%nRule time (CPU, all threads): %,.0f ms = %.1f%% of parse + model time (%,.0f ms)%n",
                    ruleNanos / 1e6,
                    100.0 * ruleNanos / Math.max(1, parseAndModelNanos),
                    parseAndModelNanos / 1e6));
            md.append(String.format(Locale.ROOT, "Rule failures (%s): %d%n", RuleEngine.INTERNAL, failures.size()));
            failures.stream().limit(10).forEach(f -> md.append("- ").append(f).append('\n'));
        }

        Path dir = group.isEmpty()
                ? reportDir.resolve("rule-samples")
                : reportDir.resolve("rule-samples").resolve(group);
        Files.createDirectories(dir);
        Random random = new Random(42);
        for (RuleDescriptor d : REGISTRY.descriptors()) {
            List<Sample> all = new ArrayList<>(samples.getOrDefault(d.id(), List.of()));
            all.sort((a, b) -> a.where().compareTo(b.where()));
            Collections.shuffle(all, random);
            StringBuilder s = new StringBuilder("# " + d.id() + " " + d.title() + "\n\n");
            s.append("Review: mark each sample TP (true positive) or FP (false positive).\n\n");
            int i = 0;
            for (Sample x : all.subList(0, Math.min(SAMPLES_PER_RULE, all.size()))) {
                s.append(++i)
                        .append(". `")
                        .append(x.where())
                        .append("`  \n   ")
                        .append(x.message())
                        .append("  \n   `")
                        .append(x.line().replace("`", "'"))
                        .append("`\n");
            }
            Files.writeString(dir.resolve(d.id() + ".md"), s);
        }
        md.append("Samples for review: ").append(dir.toAbsolutePath()).append('\n');
        out.print(md);
        Files.writeString(reportDir.resolve("summary.md"), md, StandardOpenOption.APPEND);
        return failures.size();
    }
}
