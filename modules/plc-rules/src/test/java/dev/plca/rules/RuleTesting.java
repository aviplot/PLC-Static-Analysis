package dev.plca.rules;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.plcopen.PlcOpenFrontend;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontends;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers: load a project, run rules, print findings, read inline expectation markers. */
final class RuleTesting {

    private static final Frontends FRONTENDS =
            Frontends.of(StFrontend::new, SclFrontend::new, L5xFrontend::new, PlcOpenFrontend::new);

    static final RuleRegistry REGISTRY = RuleRegistry.standard();

    /** A model plus everything needed to resolve and print its findings. */
    record Analysis(SourceRegistry sources, SemanticModel model, List<Diagnostic> diagnostics) {

        RuleEngine.Result run(RuleConfig config, int threads) {
            return new RuleEngine(REGISTRY).run(model, sources, config, threads);
        }
    }

    private RuleTesting() {}

    /** Source files directly in {@code dir}; subdirectories are separate test projects. */
    static List<Path> directSources(Path dir) {
        return ProjectLoader.sources(dir, FRONTENDS).stream()
                .filter(p -> p.getParent().equals(dir))
                .toList();
    }

    static Analysis load(Path dir) {
        ProjectLoader.Project p = ProjectLoader.load(dir, directSources(dir), FRONTENDS, 2);
        DiagnosticCollector sem = new DiagnosticCollector();
        SemanticModel m = SemanticBuilder.build(p.units(), sem);
        List<Diagnostic> all = new ArrayList<>(p.diagnostics());
        all.addAll(sem.diagnostics());
        return new Analysis(p.registry(), m, all);
    }

    /** Parses in-memory {@code .st} texts (descriptor examples, unit tests). */
    static Analysis analyze(String... texts) {
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        StFrontend fe = new StFrontend();
        List<SourceUnit> units = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            units.add(fe.parse(SourceFile.of("t" + i + ".st", texts[i]), registry, diags)
                    .unit());
        }
        SemanticModel m = SemanticBuilder.build(units, diags);
        return new Analysis(registry, m, diags.diagnostics());
    }

    /** {@code path:line:col-line:col RULE severity: message}, related locations indented below. */
    static String print(SourceRegistry sources, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            Location l = sources.resolve(f.span());
            sb.append(l)
                    .append(' ')
                    .append(f.ruleId())
                    .append(' ')
                    .append(f.severity().name().toLowerCase(Locale.ROOT))
                    .append(": ")
                    .append(f.message())
                    .append(f.pou() == null ? "" : " [" + f.pou() + "]")
                    .append(f.place() == null ? "" : " {" + f.place() + "}")
                    .append('\n');
            for (Diagnostic.Related r : f.related()) {
                sb.append("    ")
                        .append(sources.resolve(r.span()))
                        .append(' ')
                        .append(r.message())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /** {@code path:line RULE} for each finding, in report order. */
    static List<String> actualMarkers(SourceRegistry sources, List<Finding> findings) {
        return findings.stream()
                .map(f -> {
                    Location l = sources.resolve(f.span());
                    return l.path() + ":" + l.startLine() + " " + f.ruleId();
                })
                .sorted()
                .toList();
    }

    private static final Pattern MARKER = Pattern.compile("(?://|\\(\\*|<!--).*?\\b(PLCA-[QPSL]\\d{3})\\b");
    private static final Pattern MORE = Pattern.compile("\\b(PLCA-[QPSL]\\d{3})\\b");

    /**
     * Expected findings from comments: every rule id inside a {@code //}, {@code (* *)} or XML {@code <!-- -->} comment
     * on a line expects one finding of that rule starting on that line ({@code // PLCA-Q003 PLCA-Q003} expects two).
     */
    static List<String> expectedMarkers(Path dir) {
        List<String> r = new ArrayList<>();
        for (Path f : directSources(dir)) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            List<String> lines;
            try {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = MARKER.matcher(lines.get(i));
                if (m.find()) {
                    Matcher all = MORE.matcher(lines.get(i).substring(m.start()));
                    while (all.find()) {
                        r.add(rel + ":" + (i + 1) + " " + all.group(1));
                    }
                }
            }
        }
        return r.stream().sorted().toList();
    }
}
