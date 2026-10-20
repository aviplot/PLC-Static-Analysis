package dev.plca.cli;

import dev.plca.analysis.Analyzer;
import dev.plca.analysis.ConfigFile;
import dev.plca.core.diag.Severity;
import dev.plca.report.AnalysisResult;
import dev.plca.report.Baseline;
import dev.plca.report.ConsoleReport;
import dev.plca.report.ReportFormat;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleRegistry;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code plca analyze <path>}. Exit code 0: nothing at or above {@code --fail-on}; 1: findings; 2: usage error; 3:
 * internal error. Bad input content alone never gives a non-zero exit (design rule 2).
 */
@Command(
        name = "analyze",
        mixinStandardHelpOptions = true,
        description = "Analyse a project directory or a single file.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:no finding at or above --fail-on",
            "1:findings at or above --fail-on",
            "2:usage error, unreadable path or invalid rule configuration",
            "3:internal error (please report it)"
        })
final class AnalyzeCommand implements Callable<Integer> {

    static final int FINDINGS = 1;
    static final int USAGE = 2;
    static final int INTERNAL = 3;

    enum FailOn {
        ERROR,
        WARNING,
        INFO,
        NEVER
    }

    enum Color {
        AUTO,
        ALWAYS,
        NEVER
    }

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<path>", description = "Project directory or source file.")
    Path path;

    @Option(
            names = {"-f", "--format"},
            defaultValue = "console",
            description = "Report format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    ReportFormat format;

    /** What the HTML report draws (M10). */
    enum ReportDiagrams {
        FINDINGS,
        NONE
    }

    @Option(
            names = "--report-diagrams",
            defaultValue = "findings",
            description = "HTML report: draw the rung, network or SFC chart of each finding (findings, default) or no"
                    + " drawings (none).")
    ReportDiagrams reportDiagrams;

    @Option(
            names = {"-o", "--output"},
            description = "Write the report to this file instead of standard output.")
    Path output;

    @Option(
            names = "--rules",
            split = ",",
            description = "Rules to run: ids, categories (quality, plc, security; their rules that are on by default),"
                    + " default (all rules on by default; the default) or all. Replaces rules.select and rules.disable"
                    + " of plca.yaml.")
    List<String> rules;

    @Option(
            names = "--disable",
            split = ",",
            description = "Rule ids or categories to leave out (added to rules.disable of plca.yaml).")
    List<String> disable = new ArrayList<>();

    @Option(
            names = "--config",
            paramLabel = "FILE",
            description = "Configuration file (default: plca.yaml in the analysed directory, if present).")
    Path config;

    @Option(names = "--no-config", description = "Ignore plca.yaml in the analysed directory.")
    boolean noConfig;

    @Option(
            names = "--baseline",
            paramLabel = "FILE",
            description = "Report only findings not in this baseline (replaces baseline: of plca.yaml).")
    Path baseline;

    @Option(
            names = "--write-baseline",
            paramLabel = "FILE",
            description = "Write the fingerprints of all current (unsuppressed) findings to FILE as a new baseline.")
    Path writeBaseline;

    @Option(
            names = "--show-suppressed",
            description = "Also list suppressed and baselined findings in the console report.")
    boolean showSuppressed;

    @Option(
            names = "--param",
            paramLabel = "RULE.NAME=VALUE",
            description = "Rule parameter, e.g. PLCA-Q001.includeGlobals=true (repeatable).")
    List<String> params = new ArrayList<>();

    @Option(
            names = "--fail-on",
            description = "Lowest severity that gives exit code 1: ${COMPLETION-CANDIDATES} (default: fail-on of"
                    + " plca.yaml, else error).")
    FailOn failOn;

    @Option(names = "--threads", description = "Worker threads (default: available processors).")
    int threads = Runtime.getRuntime().availableProcessors();

    @Option(
            names = "--color",
            defaultValue = "auto",
            description = "Console colours: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    Color color;

    @Override
    public Integer call() throws IOException {
        PrintWriter err = spec.commandLine().getErr();
        if (!Files.exists(path)) {
            err.println("plca: no such file or directory: " + path);
            return USAGE;
        }
        ConfigFile file;
        RuleConfig ruleConfig;
        Baseline base = null;
        String baseName = null;
        try {
            file = configFile();
            ruleConfig = ruleConfig(file);
            Path b = baseline != null ? baseline : file == null ? null : file.baseline();
            if (b != null) {
                base = Baseline.read(b);
                baseName = b.toString();
            }
        } catch (ConfigFile.ConfigException | Baseline.BaselineException | IllegalArgumentException e) {
            err.println("plca: " + e.getMessage());
            return USAGE;
        } catch (IOException e) {
            err.println("plca: cannot read " + e.getMessage());
            return USAGE;
        }
        FailOn threshold = failOn != null
                ? failOn
                : file != null && file.failOn() != null
                        ? FailOn.valueOf(file.failOn().toUpperCase(Locale.ROOT))
                        : FailOn.ERROR;
        AnalysisResult result;
        try {
            result = new Analyzer(RuleRegistry.standard())
                    .withDrawings(format == ReportFormat.HTML && reportDiagrams == ReportDiagrams.FINDINGS)
                    .analyze(path, ruleConfig, Math.max(1, threads), base, baseName);
        } catch (IllegalArgumentException e) {
            err.println("plca: " + e.getMessage());
            return USAGE;
        }
        if (writeBaseline != null) {
            List<Finding> all = new ArrayList<>(result.findings());
            result.suppressed(AnalysisResult.SuppressionKind.BASELINE).forEach(s -> all.add(s.finding()));
            all.sort(java.util.Comparator.comparing(f -> result.fingerprint(f)));
            try (Writer w = Files.newBufferedWriter(writeBaseline, StandardCharsets.UTF_8)) {
                Baseline.write(all, all.stream().map(result::fingerprint).toList(), result.sources(), w);
            }
            err.println("plca: wrote " + all.size() + " finding" + (all.size() == 1 ? "" : "s") + " to baseline "
                    + writeBaseline);
        }

        if (output != null) {
            try (Writer w = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                write(result, w, false);
            }
        } else {
            Writer w = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            write(result, w, useColor());
            w.flush();
        }
        return failing(result, threshold) ? FINDINGS : 0;
    }

    /** {@code --config}, else {@code plca.yaml} in the analysed directory (for a file: its directory), else null. */
    private ConfigFile configFile() throws ConfigFile.ConfigException, IOException {
        if (config != null) {
            if (!Files.isRegularFile(config)) {
                throw new ConfigFile.ConfigException("no such configuration file: " + config);
            }
            return ConfigFile.read(config);
        }
        if (noConfig) {
            return null;
        }
        Path dir = Files.isDirectory(path) ? path : path.toAbsolutePath().getParent();
        Path found = dir == null ? null : dir.resolve(ConfigFile.NAME);
        return found != null && Files.isRegularFile(found) ? ConfigFile.read(found) : null;
    }

    private RuleConfig ruleConfig(ConfigFile file) {
        return ConfigFile.ruleConfig(file, rules, disable, parameters());
    }

    private void write(AnalysisResult result, Writer w, boolean colored) throws IOException {
        if (format == ReportFormat.CONSOLE) {
            new ConsoleReport(colored, showSuppressed).write(result, w);
        } else {
            format.write(result, w);
        }
    }

    private boolean useColor() {
        return switch (color) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO ->
                System.console() != null && !"dumb".equals(System.getenv("TERM")) && System.getenv("NO_COLOR") == null;
        };
    }

    private static boolean failing(AnalysisResult result, FailOn failOn) {
        if (failOn == FailOn.NEVER) {
            return false;
        }
        Severity threshold = Severity.valueOf(failOn.name());
        for (Finding f : result.findings()) {
            if (f.severity().ordinal() <= threshold.ordinal()) {
                return true;
            }
        }
        return false;
    }

    /** {@code RULE.NAME=VALUE} options grouped by rule. */
    private Map<String, Map<String, String>> parameters() {
        Map<String, Map<String, String>> r = new LinkedHashMap<>();
        for (String p : params) {
            int eq = p.indexOf('=');
            int dot = eq < 0 ? -1 : p.lastIndexOf('.', eq);
            if (eq < 0 || dot <= 0) {
                throw new IllegalArgumentException("--param expects RULE.NAME=VALUE, got " + p);
            }
            r.computeIfAbsent(p.substring(0, dot).toUpperCase(Locale.ROOT), k -> new LinkedHashMap<>())
                    .put(p.substring(dot + 1, eq), p.substring(eq + 1));
        }
        return r;
    }
}
