package dev.plca.cli;

import dev.plca.core.diag.Severity;
import dev.plca.report.AnalysisResult;
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

    @Option(
            names = {"-o", "--output"},
            description = "Write the report to this file instead of standard output.")
    Path output;

    @Option(
            names = "--rules",
            split = ",",
            defaultValue = "all",
            description = "Rules to run: ids, categories (quality, plc, security) or all (default).")
    List<String> rules;

    @Option(names = "--disable", split = ",", description = "Rule ids or categories to leave out.")
    List<String> disable = new ArrayList<>();

    @Option(
            names = "--param",
            paramLabel = "RULE.NAME=VALUE",
            description = "Rule parameter, e.g. PLCA-Q001.includeGlobals=true (repeatable).")
    List<String> params = new ArrayList<>();

    @Option(
            names = "--fail-on",
            defaultValue = "error",
            description =
                    "Lowest severity that gives exit code 1: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
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
        RuleConfig config;
        try {
            config = new RuleConfig(rules, disable, parameters());
        } catch (IllegalArgumentException e) {
            err.println("plca: " + e.getMessage());
            return USAGE;
        }
        AnalysisResult result;
        try {
            result = new Analyzer(RuleRegistry.standard()).analyze(path, config, Math.max(1, threads));
        } catch (IllegalArgumentException e) {
            err.println("plca: " + e.getMessage());
            return USAGE;
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
        return failing(result) ? FINDINGS : 0;
    }

    private void write(AnalysisResult result, Writer w, boolean colored) throws IOException {
        if (format == ReportFormat.CONSOLE) {
            new ConsoleReport(colored).write(result, w);
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

    private boolean failing(AnalysisResult result) {
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
