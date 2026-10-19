package dev.plca.cli;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontends;
import dev.plca.report.AnalysisResult;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleEngine;
import dev.plca.rules.RuleRegistry;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One analysis run: load (parallel parse), build the model, run the rules. The pipeline a future LSP or web backend
 * reuses; reports only format its {@link AnalysisResult}.
 */
public final class Analyzer {

    private static final Frontends FRONTENDS = Frontends.of(StFrontend::new, SclFrontend::new);

    /** Extensions of dialects whose frontends come in M5–M6; such files are listed as skipped, not ignored silently. */
    private static final Set<String> LATER_DIALECTS = Set.of(".l5x", ".l5k");

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
        Path path = input.toAbsolutePath().normalize();
        Path root = Files.isDirectory(path) ? path : path.getParent();
        List<Path> files = Files.isDirectory(path) ? ProjectLoader.sources(root, FRONTENDS) : List.of(path);
        config.resolve(registry); // fail fast on configuration errors, before parsing

        long t0 = System.nanoTime();
        ProjectLoader.Project project = ProjectLoader.load(root, files, FRONTENDS, threads);
        long t1 = System.nanoTime();
        DiagnosticCollector semantic = new DiagnosticCollector();
        SemanticModel model = SemanticBuilder.build(project.units(), semantic);
        long t2 = System.nanoTime();
        RuleEngine.Result rules = new RuleEngine(registry).run(model, project.registry(), config, threads);
        long t3 = System.nanoTime();

        List<Diagnostic> diagnostics = new ArrayList<>(project.diagnostics());
        diagnostics.addAll(semantic.diagnostics());
        diagnostics.addAll(rules.diagnostics());
        int lines = project.registry().files().stream()
                .mapToInt(f -> f.lineIndex().lineCount())
                .sum();
        AnalysisResult.Stats stats = new AnalysisResult.Stats(
                files.size(),
                lines,
                model.pous().size(),
                (t1 - t0) / 1_000_000,
                (t2 - t1) / 1_000_000,
                (t3 - t2) / 1_000_000,
                model.resolutionStats().rate(),
                Files.isDirectory(path) ? skipped(root) : List.of());
        String rootUri = root.toUri().toString();
        return new AnalysisResult(
                rootUri.endsWith("/") ? rootUri : rootUri + "/",
                config.resolve(registry).stream()
                        .map(id -> registry.descriptor(id).orElseThrow())
                        .toList(),
                rules.findings(),
                diagnostics,
                project.registry(),
                stats);
    }

    /** Files of dialects we cannot analyse yet: L5X/L5K by extension, PLCopen XML by its namespace. */
    static List<String> skipped(Path root) {
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
                    if (LATER_DIALECTS.contains(ext) || ext.equals(".xml") && isPlcOpen(file)) {
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

    private static boolean isPlcOpen(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            String head = new String(in.readNBytes(4096), StandardCharsets.ISO_8859_1);
            return head.contains("plcopen.org/xml/tc6");
        } catch (IOException e) {
            return false;
        }
    }
}
