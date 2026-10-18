package dev.plca.report;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.SourceRegistry;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleDescriptor;
import java.util.List;

/**
 * Everything a report shows about one run.
 *
 * @param rootUri absolute {@code file:} URI of the analysed directory, ending in {@code /}; paths in spans are relative
 *     to it (SARIF {@code %SRCROOT%})
 * @param rules the rules that ran, in registry order
 * @param diagnostics problems reading, parsing or modelling the input, and rule failures
 */
public record AnalysisResult(
        String rootUri,
        List<RuleDescriptor> rules,
        List<Finding> findings,
        List<Diagnostic> diagnostics,
        SourceRegistry sources,
        Stats stats) {

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
    }
}
