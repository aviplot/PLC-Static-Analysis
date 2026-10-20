package dev.plca.analysis;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.SyntaxCodes;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.Frontends;
import dev.plca.semantic.ModelPrinter;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The incremental state of an editor session (M8): every analysed file under a root directory, parsed once and then
 * re-parsed one at a time as it changes. {@link #snapshot()} builds the semantic models, rebuilding only the model
 * groups ({@link Analyzer#modelGroups}) whose units changed.
 *
 * <p>Not thread-safe: one thread owns the workspace. {@link Snapshot}s are immutable and may be queried from any
 * thread; each has its own {@link SourceRegistry} ({@link SourceRegistry#without}), so a newer edit never invalidates
 * the spans of an older snapshot.
 */
public final class Workspace {

    /** One file: its text, its unit (null when its frontend gave none) and its parse diagnostics. */
    public record FileState(SourceFile file, SourceUnit unit, List<Diagnostic> diagnostics) {
        public FileState {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** {@code -Dplca.model.verify=true}: every incremental model is checked against a full build (slow; for tests). */
    private static final boolean VERIFY = Boolean.getBoolean("plca.model.verify");

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(Workspace.class.getName());

    private final Path root;
    private final Frontends frontends;
    private SourceRegistry registry = new SourceRegistry();
    private final Map<String, FileState> files = new TreeMap<>();
    private Snapshot last;

    public Workspace(Path root) {
        this(root, Analyzer.FRONTENDS);
    }

    Workspace(Path root, Frontends frontends) {
        this.root = root.toAbsolutePath().normalize();
        this.frontends = frontends;
    }

    public Path root() {
        return root;
    }

    /** The display path of {@code file}: relative to the root with {@code /}, or absolute when outside it. */
    public String displayPath(Path file) {
        Path p = file.toAbsolutePath().normalize();
        return (p.startsWith(root) ? root.relativize(p) : p).toString().replace('\\', '/');
    }

    /** The file a display path names. */
    public Path file(String displayPath) {
        return root.resolve(displayPath);
    }

    /** True if some frontend reads files with this path's extension. */
    public boolean accepts(String path) {
        return frontends.accepts(path);
    }

    public boolean contains(String path) {
        return files.containsKey(path);
    }

    /** Loads every source under the root from disk, replacing the current state; parses in parallel. */
    public void loadAll(int threads) {
        loadAll(threads, ProjectLoader.Progress.NONE);
    }

    /** As {@link #loadAll(int)}, reporting each parsed file (from parser threads). */
    public void loadAll(int threads, ProjectLoader.Progress progress) {
        ProjectLoader.Project p =
                ProjectLoader.load(root, ProjectLoader.sources(root, frontends), frontends, threads, progress);
        registry = p.registry();
        files.clear();
        Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        for (Diagnostic d : p.diagnostics()) {
            diagnostics
                    .computeIfAbsent(pathOf(registry, d.span()), k -> new ArrayList<>())
                    .add(d);
        }
        for (SourceFile f : registry.files()) {
            files.put(f.path(), new FileState(f, null, diagnostics.getOrDefault(f.path(), List.of())));
        }
        for (SourceUnit u : p.units()) {
            FileState s = files.get(u.path());
            files.put(u.path(), new FileState(s.file(), u, s.diagnostics()));
        }
        last = null;
    }

    /**
     * Parses {@code text} as the content of {@code path} (an editor buffer, or the file re-read from disk).
     *
     * @return false if no frontend reads the path; nothing changes then
     */
    public boolean update(String path, String text) {
        Frontend frontend = frontends.forPath(path);
        if (frontend == null) {
            return false;
        }
        registry = registry.without(Set.of(path));
        SourceFile file = SourceFile.of(path, text);
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SourceUnit unit;
        try {
            unit = frontend.parse(file, registry, diagnostics).unit();
        } catch (RuntimeException | StackOverflowError e) {
            // frontends never throw by contract (design rule 2); an editor must survive one that does
            unit = null;
            diagnostics.report(Diagnostic.of(
                    SyntaxCodes.INTERNAL,
                    Severity.ERROR,
                    "internal error while parsing: " + e,
                    Span.at(registry.register(file).id(), 0)));
        }
        files.put(path, new FileState(file, unit, diagnostics.diagnostics()));
        return true;
    }

    /**
     * Re-reads {@code path} from disk (after an external change), or forgets it if the file is gone.
     *
     * @return false if no frontend reads the path
     */
    public boolean reload(String path) {
        if (!accepts(path)) {
            return false;
        }
        try {
            byte[] data = Files.readAllBytes(file(path));
            return update(path, SourceFile.fromBytes(path, data).text());
        } catch (NoSuchFileException e) {
            remove(path);
            return true;
        } catch (IOException e) {
            registry = registry.without(Set.of(path));
            SourceFile empty = SourceFile.of(path, "");
            files.put(
                    path,
                    new FileState(
                            empty,
                            null,
                            List.of(Diagnostic.error(
                                    "PLC-IO-001",
                                    "cannot read file: " + e.getMessage(),
                                    Span.at(registry.register(empty).id(), 0)))));
            return true;
        }
    }

    /** The loaded files' display paths, sorted. */
    public List<String> paths() {
        return List.copyOf(files.keySet());
    }

    /** A file's current state, without building any model (drawings need only the unit). */
    public Optional<FileState> state(String path) {
        return Optional.ofNullable(files.get(path));
    }

    /** The registry the current units' spans resolve in. */
    public SourceRegistry registry() {
        return registry;
    }

    /** Forgets {@code path}; returns false if it was not loaded. */
    public boolean remove(String path) {
        if (files.remove(path) == null) {
            return false;
        }
        registry = registry.without(Set.of(path));
        return true;
    }

    /** The current state with its semantic models; groups whose units did not change keep their previous model. */
    public Snapshot snapshot() {
        List<SourceUnit> units = new ArrayList<>();
        for (FileState f : files.values()) {
            if (f.unit() != null) {
                units.add(f.unit());
            }
        }
        List<Snapshot.Group> groups = new ArrayList<>();
        for (List<SourceUnit> g : Analyzer.modelGroups(units, registry)) {
            Snapshot.Group reused = last == null ? null : last.sameGroup(g);
            if (reused != null) {
                groups.add(reused);
            } else {
                DiagnosticCollector d = new DiagnosticCollector();
                SemanticModel model = SemanticBuilder.buildForEditing(g, d, last == null ? null : last.similarGroup(g));
                if (VERIFY && model.buildInfo().incremental()) {
                    DiagnosticCollector fd = new DiagnosticCollector();
                    SemanticModel full = SemanticBuilder.build(g, fd);
                    SourceRegistry r = registry;
                    if (!ModelPrinter.printAll(model, r::resolve).equals(ModelPrinter.printAll(full, r::resolve))) {
                        LOG.warning("plca: the incremental model differs from a full build (" + model.buildInfo()
                                + "); using the full build");
                        model = full;
                        d = fd;
                    }
                }
                groups.add(new Snapshot.Group(g, model, d.diagnostics()));
            }
        }
        last = new Snapshot(root, registry, files, groups);
        return last;
    }

    static String pathOf(SourceRegistry registry, Span span) {
        return registry.text(span.textId()).file().path();
    }
}
