package dev.plca.analysis;

import dev.plca.ast.SourceUnit;
import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.plcopen.PlcOpenFrontend;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontends;
import dev.plca.report.AnalysisResult;
import dev.plca.report.Baseline;
import dev.plca.report.Fingerprints;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleEngine;
import dev.plca.rules.RuleRegistry;
import dev.plca.rules.Suppressions;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One analysis run: load (parallel parse), build the model, run the rules. The pipeline a future LSP or web backend
 * reuses; reports only format its {@link AnalysisResult}.
 */
public final class Analyzer {

    static final Frontends FRONTENDS =
            Frontends.of(StFrontend::new, SclFrontend::new, L5xFrontend::new, PlcOpenFrontend::new);

    /** Extensions of formats not analysed yet; such files are listed as skipped, not ignored silently. */
    private static final Set<String> LATER_DIALECTS = Set.of(".l5k");

    private final RuleRegistry registry;

    public Analyzer(RuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * Analyses a directory or a single file. Paths in the result are relative to the directory (for a file: its
     * parent).
     *
     * @throws IllegalArgumentException for an invalid rule configuration
     */
    public AnalysisResult analyze(Path input, RuleConfig config, int threads) {
        return analyze(input, config, threads, null, null);
    }

    /**
     * As {@link #analyze(Path, RuleConfig, int)}, reporting only findings not in {@code baseline} (null: no baseline;
     * {@code baselineName} is how reports name it).
     */
    public AnalysisResult analyze(Path input, RuleConfig config, int threads, Baseline baseline, String baselineName) {
        Path path = input.toAbsolutePath().normalize();
        Path root = Files.isDirectory(path) ? path : path.getParent();
        List<Path> files = Files.isDirectory(path) ? ProjectLoader.sources(root, FRONTENDS) : List.of(path);
        config.resolve(registry); // fail fast on configuration errors, before parsing

        long t0 = System.nanoTime();
        ProjectLoader.Project project = ProjectLoader.load(root, files, FRONTENDS, threads);
        long t1 = System.nanoTime();
        DiagnosticCollector semantic = new DiagnosticCollector();
        List<SemanticModel> models = new ArrayList<>();
        for (List<SourceUnit> group : modelGroups(project.units(), project.registry())) {
            models.add(SemanticBuilder.build(group, semantic));
        }
        long t2 = System.nanoTime();
        List<RuleEngine.Result> results = new ArrayList<>();
        for (SemanticModel model : models) {
            results.add(new RuleEngine(registry).run(model, project.registry(), config, threads));
        }
        RuleEngine.Result rules = RuleEngine.Result.merge(results, project.registry());
        long t3 = System.nanoTime();

        List<Diagnostic> diagnostics = new ArrayList<>(project.diagnostics());
        diagnostics.addAll(semantic.diagnostics());
        diagnostics.addAll(rules.diagnostics());
        int lines = project.registry().files().stream()
                .mapToInt(f -> f.lineIndex().lineCount())
                .sum();
        int names = 0;
        int bound = 0;
        int pous = 0;
        for (SemanticModel model : models) {
            SemanticModel.ResolutionStats rs = model.resolutionStats();
            names += rs.names();
            bound += rs.resolved() + rs.library() + rs.external();
            pous += model.pous().size();
        }
        AnalysisResult.Stats stats = new AnalysisResult.Stats(
                files.size(),
                lines,
                pous,
                (t1 - t0) / 1_000_000,
                (t2 - t1) / 1_000_000,
                (t3 - t2) / 1_000_000,
                names == 0 ? 1.0 : bound / (double) names,
                Files.isDirectory(path) ? skipped(root) : List.of());
        String rootUri = root.toUri().toString();

        // fingerprints over every unsuppressed finding in report order: the recipe a baseline records
        Map<Finding, String> fingerprints = new IdentityHashMap<>();
        List<String> fps = Fingerprints.of(rules.findings(), project.registry());
        List<Finding> reported = new ArrayList<>();
        List<AnalysisResult.Suppressed> suppressed = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        for (int i = 0; i < fps.size(); i++) {
            Finding f = rules.findings().get(i);
            fingerprints.put(f, fps.get(i));
            if (baseline != null && baseline.contains(fps.get(i))) {
                matched.add(fps.get(i));
                suppressed.add(new AnalysisResult.Suppressed(f, AnalysisResult.SuppressionKind.BASELINE, ""));
            } else {
                reported.add(f);
            }
        }
        List<Finding> quiet = rules.suppressed().stream()
                .map(Suppressions.Suppressed::finding)
                .toList();
        List<String> quietFps = Fingerprints.of(quiet, project.registry());
        for (int i = 0; i < quiet.size(); i++) {
            Suppressions.Suppressed s = rules.suppressed().get(i);
            fingerprints.put(s.finding(), quietFps.get(i));
            suppressed.add(new AnalysisResult.Suppressed(
                    s.finding(),
                    s.kind().equals("config")
                            ? AnalysisResult.SuppressionKind.CONFIG
                            : AnalysisResult.SuppressionKind.INLINE,
                    s.reason()));
        }
        AnalysisResult.BaselineSummary summary = baseline == null
                ? null
                : new AnalysisResult.BaselineSummary(
                        baselineName, suppressed.size() - quiet.size(), baseline.size() - matched.size());
        return new AnalysisResult(
                rootUri.endsWith("/") ? rootUri : rootUri + "/",
                config.resolve(registry).stream()
                        .map(id -> registry.descriptor(id).orElseThrow())
                        .toList(),
                reported,
                diagnostics,
                project.registry(),
                stats,
                suppressed,
                summary,
                fingerprints);
    }

    /**
     * The units that form one semantic model each. Every Rockwell controller export (an L5X with
     * {@code TargetType="Controller"}) is a project of its own: two versions of one controller in a folder must not
     * merge into duplicate symbols and cross-file double coils. Partial Rockwell exports (routines, programs, AOIs)
     * join the controller when there is exactly one, else the shared model, which holds everything else.
     */
    public static List<List<SourceUnit>> modelGroups(List<SourceUnit> units, SourceRegistry registry) {
        Map<String, SourceFile> files = new HashMap<>();
        registry.files().forEach(f -> files.put(f.path(), f));
        List<List<SourceUnit>> controllers = new ArrayList<>();
        List<SourceUnit> partial = new ArrayList<>();
        List<SourceUnit> shared = new ArrayList<>();
        for (SourceUnit u : units) {
            if (u.dialect() != Dialect.ROCKWELL) {
                shared.add(u);
            } else if (isController(files.get(u.path()))) {
                controllers.add(new ArrayList<>(List.of(u)));
            } else {
                partial.add(u);
            }
        }
        if (controllers.size() == 1) {
            controllers.getFirst().addAll(partial);
        } else {
            shared.addAll(partial);
        }
        List<List<SourceUnit>> out = new ArrayList<>();
        if (!shared.isEmpty() || controllers.isEmpty()) {
            out.add(shared);
        }
        out.addAll(controllers);
        return out;
    }

    /** {@code TargetType="Controller"} on the root element, which comes first in every L5X. */
    private static boolean isController(SourceFile f) {
        if (f == null) {
            return false;
        }
        String head = f.text().substring(0, Math.min(f.text().length(), 4096));
        return CONTROLLER.matcher(head).find();
    }

    private static final Pattern CONTROLLER =
            Pattern.compile("<RSLogix5000Content\\b[^>]*\\bTargetType\\s*=\\s*\"Controller\"");

    /** Files we cannot analyse yet: L5K (Rockwell's text export). */
    public static List<String> skipped(Path root) {
        List<String> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name =
                            dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(root) && (name.startsWith(".") || name.equals("build"))
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    int dot = name.lastIndexOf('.');
                    String ext = dot < 0 ? "" : name.substring(dot);
                    if (LATER_DIALECTS.contains(ext)) {
                        out.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(null);
        return out;
    }
}
