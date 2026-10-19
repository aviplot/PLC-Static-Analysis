package dev.plca.report;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.SourceRegistry;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleDescriptor;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a report shows about one run.
 *
 * @param rootUri absolute {@code file:} URI of the analysed directory, ending in {@code /}; paths in spans are relative
 *     to it (SARIF {@code %SRCROOT%})
 * @param rules the rules that ran, in registry order
 * @param findings the findings to act on: not suppressed and not in the baseline
 * @param diagnostics problems reading, parsing or modelling the input, and rule failures
 * @param suppressed findings left out of {@code findings}: suppressed inline or by the configuration, or in the
 *     baseline
 * @param baseline the baseline comparison, or null when no baseline was given
 * @param fingerprints fingerprint of each finding (in {@code findings} and {@code suppressed}), by identity; computed
 *     by {@link Fingerprints} when absent
 */
public record AnalysisResult(
        String rootUri,
        List<RuleDescriptor> rules,
        List<Finding> findings,
        List<Diagnostic> diagnostics,
        SourceRegistry sources,
        Stats stats,
        List<Suppressed> suppressed,
        BaselineSummary baseline,
        Map<Finding, String> fingerprints) {

    /** Why a finding is not reported. */
    public enum SuppressionKind {
        /** A comment or attribute in the source. */
        INLINE,
        /** A {@code suppress} entry of {@code plca.yaml}. */
        CONFIG,
        /** The finding is in the baseline (existing debt). */
        BASELINE
    }

    public record Suppressed(Finding finding, SuppressionKind kind, String reason) {}

    /**
     * @param file the baseline file, as given
     * @param unchanged findings that matched a baseline entry
     * @param fixed baseline entries no finding matched any more
     */
    public record BaselineSummary(String file, int unchanged, int fixed) {}

    /** @param skippedFiles files of dialects not supported yet (relative paths) */
    public record Stats(
            int files,
            int lines,
            int pous,
            long parseMillis,
            long modelMillis,
            long rulesMillis,
            double resolutionRate,
            List<String> skippedFiles) {
        public Stats {
            skippedFiles = List.copyOf(skippedFiles);
        }
    }

    public AnalysisResult {
        rules = List.copyOf(rules);
        findings = List.copyOf(findings);
        diagnostics = List.copyOf(diagnostics);
        suppressed = List.copyOf(suppressed);
        if (fingerprints == null) {
            fingerprints = new IdentityHashMap<>();
            List<String> f = Fingerprints.of(findings, sources);
            for (int i = 0; i < findings.size(); i++) {
                fingerprints.put(findings.get(i), f.get(i));
            }
            List<Finding> rest = suppressed.stream().map(Suppressed::finding).toList();
            List<String> g = Fingerprints.of(rest, sources);
            for (int i = 0; i < rest.size(); i++) {
                fingerprints.put(rest.get(i), g.get(i));
            }
        }
    }

    public AnalysisResult(
            String rootUri,
            List<RuleDescriptor> rules,
            List<Finding> findings,
            List<Diagnostic> diagnostics,
            SourceRegistry sources,
            Stats stats) {
        this(rootUri, rules, findings, diagnostics, sources, stats, List.of(), null, null);
    }

    /** The finding's fingerprint ({@link Fingerprints}). */
    public String fingerprint(Finding f) {
        return fingerprints.get(f);
    }

    /** Suppressed findings of one kind. */
    public List<Suppressed> suppressed(SuppressionKind kind) {
        return suppressed.stream().filter(s -> s.kind() == kind).toList();
    }
}
