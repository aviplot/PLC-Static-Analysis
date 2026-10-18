package dev.plca.frontend.st;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Loads every ST/TwinCAT source under a directory, parsing files in parallel (one {@link StFrontend} per thread, design
 * rule 7). Units and diagnostics come back in path order so results do not depend on scheduling.
 */
public final class ProjectLoader {

    /** Parsed project: units in path order, the registry their spans resolve in, and all diagnostics. */
    public record Project(
            List<SourceUnit> units, SourceRegistry registry, List<Diagnostic> diagnostics, StFrontend.Stats stats) {}

    /** Directories that never contain project sources (VCS, build output, TwinCAT caches). */
    private static final Set<String> SKIP_DIRS =
            Set.of(".git", ".svn", "build", "_boot", "_compileinfo", "_libraries", "node_modules", ".vs");

    private ProjectLoader() {}

    public static Project load(Path root, int threads) {
        List<Path> files = sources(root);
        return load(root, files, threads);
    }

    public static Project load(Path root, List<Path> files, int threads) {
        SourceRegistry registry = new SourceRegistry();
        ThreadLocal<StFrontend> frontends = ThreadLocal.withInitial(StFrontend::new);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "plca-parse");
            t.setDaemon(true);
            return t;
        });
        record Parsed(SourceUnit unit, DiagnosticCollector diags, StFrontend.Stats stats) {}
        try {
            List<Future<Parsed>> futures = new ArrayList<>();
            for (Path f : files) {
                futures.add(pool.submit(() -> {
                    String display = root.relativize(f).toString().replace('\\', '/');
                    DiagnosticCollector diags = new DiagnosticCollector();
                    SourceFile sf;
                    try {
                        sf = SourceFile.fromBytes(display, Files.readAllBytes(f));
                    } catch (IOException e) {
                        SourceFile empty = SourceFile.of(display, "");
                        diags.report(Diagnostic.of(
                                "PLC-IO-001",
                                Severity.ERROR,
                                "cannot read file: " + e.getMessage(),
                                Span.at(registry.register(empty).id(), 0)));
                        return new Parsed(null, diags, new StFrontend.Stats(0, 0, 0, 0));
                    }
                    StFrontend.Result r = frontends.get().parse(sf, registry, diags);
                    return new Parsed(r.unit(), diags, r.stats());
                }));
            }
            List<SourceUnit> units = new ArrayList<>();
            List<Diagnostic> diagnostics = new ArrayList<>();
            StFrontend.Stats stats = new StFrontend.Stats(0, 0, 0, 0);
            for (Future<Parsed> fu : futures) {
                Parsed p = fu.get();
                if (p.unit() != null) {
                    units.add(p.unit());
                }
                diagnostics.addAll(p.diags().diagnostics());
                stats = stats.plus(p.stats());
            }
            return new Project(units, registry, diagnostics, stats);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while loading " + root, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("unexpected failure while loading " + root, e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Source files under {@code root}, sorted by path. */
    public static List<Path> sources(Path root) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null
                            ? ""
                            : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    return !dir.equals(root) && SKIP_DIRS.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()
                            && StFrontend.EXTENSIONS.contains(StFrontend.extension(file.toString()))) {
                        out.add(file);
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
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }
}
