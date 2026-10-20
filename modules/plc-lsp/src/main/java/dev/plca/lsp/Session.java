package dev.plca.lsp;

import dev.plca.analysis.ConfigFile;
import dev.plca.analysis.QuickRead;
import dev.plca.analysis.Snapshot;
import dev.plca.analysis.Workspace;
import dev.plca.analysis.nav.Navigator;
import dev.plca.ast.SourceUnit;
import dev.plca.core.source.SourceRegistry;
import dev.plca.report.Baseline;
import dev.plca.report.Diagrams;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * The state of one editor session and its threads.
 *
 * <ul>
 *   <li>The <em>analysis thread</em> owns the {@link Workspace}: edits are applied at once (parsing only the edited
 *       file), snapshots are built lazily, and queries run there after every edit sent before them, so they always see
 *       the buffer the user sees.
 *   <li>The <em>fast tier</em> runs on the analysis thread {@link Settings#fastDelayMs} after the last edit: POU rules
 *       of the edited files only.
 *   <li>The <em>project tier</em> runs on its own thread {@link Settings#fullDelayMs} after the last edit, on save and
 *       after loading: every rule on an immutable snapshot, so typing never waits for it.
 * </ul>
 *
 * Every edit increments a generation; a run checks it between POUs and rules and its results are published only if no
 * edit came in meanwhile, so diagnostics never describe an older text than the editor shows.
 */
final class Session {

    private static final Logger LOG = Logger.getLogger(Session.class.getName());

    /** Tunables; {@code initializationOptions} of the client can set them. */
    record Settings(Path config, int threads, long fastDelayMs, long fullDelayMs, long maxFileBytes) {
        static Settings defaults() {
            return new Settings(null, Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 100, 750, 20L << 20);
        }
    }

    private final RuleRegistry rules = RuleRegistry.standard();
    private final Uris uris;
    /** Web workbench: configuration and baseline only from inside the root, paths masked in messages. */
    private final boolean sandboxed;

    private final ExecutorService analysis = Executors.newSingleThreadExecutor(r -> thread(r, "plca-analysis"));
    private final ExecutorService project = Executors.newSingleThreadExecutor(r -> thread(r, "plca-project"));
    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(r -> thread(r, "plca-timer"));
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();

    private volatile LanguageClient client;
    private volatile Settings settings = Settings.defaults();
    private volatile Navigator navigator;
    private volatile boolean progressSupported;
    /** From {@link #open} until the files are parsed: drawings come from {@link QuickRead}s meanwhile. */
    private volatile boolean loading;

    private volatile Path root;
    private final ExecutorService quick = Executors.newSingleThreadExecutor(r -> thread(r, "plca-quick-read"));
    /** The last quick reads, by path and routine (a list and a routine's pages). Guarded by itself. */
    private final Map<String, QuickRead.Result> quickReads = new java.util.LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, QuickRead.Result> eldest) {
            return size() > 2;
        }
    };
    /** The parsed files while the first model is built (drawings need no model). */
    private volatile Loaded loaded;

    /** An immutable view of the workspace's units right after loading. */
    private record Loaded(SourceRegistry registry, Map<String, SourceUnit> units) {
        static Loaded of(Workspace w) {
            Map<String, SourceUnit> units = new HashMap<>();
            for (String path : w.paths()) {
                w.state(path).map(Workspace.FileState::unit).ifPresent(u -> units.put(path, u));
            }
            return new Loaded(w.registry(), units);
        }
    }

    /** The load's progress, ended by the first project tier after it (analysis thread, then the project thread). */
    private volatile LoadProgress loadProgress;

    // analysis thread only
    private Workspace workspace;
    private boolean stale = true;
    private final Set<String> dirty = new LinkedHashSet<>();
    private final Set<String> open = new java.util.HashSet<>();
    private ScheduledFuture<?> fastTimer;
    private ScheduledFuture<?> fullTimer;
    private RuleConfig ruleConfig = RuleConfig.defaults();
    private Baseline baseline;
    private Path baselineFile;
    private Path configFile;

    /** Last published diagnostics per URI. Guarded by {@code this}. */
    private final Map<String, List<Diagnostic>> published = new HashMap<>();

    private static Thread thread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    Session() {
        this(Uris.files(), false);
    }

    Session(Uris uris, boolean sandboxed) {
        this.uris = uris;
        this.sandboxed = sandboxed;
    }

    Uris uris() {
        return uris;
    }

    void connect(LanguageClient client) {
        this.client = client;
    }

    void configure(Settings settings) {
        this.settings = settings;
    }

    Settings settings() {
        return settings;
    }

    /** The client shows work-done progress ({@code window.workDoneProgress}). */
    void progressSupported(boolean supported) {
        this.progressSupported = supported;
    }

    /** Loads the workspace from disk and starts a full analysis; queued behind nothing, so call it first. */
    void open(Path root) {
        this.root = root.toAbsolutePath().normalize();
        loading = true;
        analysis.execute(guard(() -> {
            LoadProgress progress = LoadProgress.begin(progressSupported ? client : null, "Loading the project");
            loadProgress = progress;
            try {
                workspace = new Workspace(root);
                loadConfig();
                long t0 = System.nanoTime();
                workspace.loadAll(
                        settings.threads(),
                        (done, total, bytes) ->
                                progress.report("Reading files: " + done + " of " + total, done * 80 / total));
                log(
                        MessageType.Log,
                        "plca: loaded " + workspace.root() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                // drawings from the parsed units while the first model is built
                loaded = Loaded.of(workspace);
                stale = true;
                generation.incrementAndGet();
                startFull();
            } finally {
                loading = false;
                loaded = null;
                synchronized (quickReads) {
                    quickReads.clear();
                }
            }
        }));
    }

    // ---- documents ------------------------------------------------------------------------------

    /** An editor buffer opened or changed: {@code text} is its full content. */
    void edit(Path file, int version, String text) {
        analysis.execute(guard(() -> {
            if (workspace == null) {
                return;
            }
            String path = workspace.displayPath(file);
            if (!workspace.accepts(path) || text.length() > settings.maxFileBytes()) {
                return;
            }
            versions.put(path, version);
            open.add(path);
            if (workspace.update(path, text)) {
                changed(path);
            }
        }));
    }

    /** A buffer closed: the file's content is what is on disk again. */
    void close(Path file) {
        analysis.execute(guard(() -> {
            if (workspace != null && open.remove(workspace.displayPath(file))) {
                reloadNow(file);
            }
        }));
    }

    /** A file was created, changed or deleted on disk; ignored while an editor holds it open. */
    void changedOnDisk(Path file) {
        analysis.execute(guard(() -> {
            if (workspace != null && !open.contains(workspace.displayPath(file))) {
                reloadNow(file);
            }
        }));
    }

    private void reloadNow(Path file) throws IOException {
        if (file.equals(configFile) || file.equals(baselineFile) || isDefaultConfig(file)) {
            loadConfig();
            generation.incrementAndGet();
            scheduleFull(0);
            return;
        }
        String path = workspace.displayPath(file);
        versions.remove(path);
        boolean known = workspace.contains(path);
        if (!workspace.accepts(path) || !known && !Files.isRegularFile(file)) {
            return;
        }
        if (Files.isRegularFile(file) && Files.size(file) > settings.maxFileBytes()) {
            if (workspace.remove(path)) {
                changed(path);
            }
            return;
        }
        if (workspace.reload(path)) {
            changed(path);
        }
    }

    /** The user saved: run the project tier now instead of waiting for the idle delay. */
    void saved() {
        analysis.execute(guard(() -> scheduleFull(0)));
    }

    /** {@code plca.analyzeWorkspace}: re-read everything from disk and analyse it. */
    CompletableFuture<Object> reanalyze() {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (workspace != null) {
                        open(workspace.root());
                    }
                    return null;
                },
                analysis);
    }

    private void changed(String path) {
        stale = true;
        dirty.add(path);
        generation.incrementAndGet();
        if (fastTimer != null) {
            fastTimer.cancel(false);
        }
        fastTimer = timers.schedule(
                () -> analysis.execute(guard(this::fastRun)), settings.fastDelayMs(), TimeUnit.MILLISECONDS);
        scheduleFull(settings.fullDelayMs());
    }

    private void scheduleFull(long delayMs) {
        if (fullTimer != null) {
            fullTimer.cancel(false);
        }
        fullTimer = timers.schedule(() -> analysis.execute(guard(this::startFull)), delayMs, TimeUnit.MILLISECONDS);
    }

    // ---- analysis -------------------------------------------------------------------------------

    /** The navigator of the current workspace state (analysis thread). */
    private Navigator current() {
        if (stale || navigator == null) {
            navigator = new Navigator(workspace.snapshot(), navigator);
            stale = false;
        }
        return navigator;
    }

    private void fastRun() {
        if (dirty.isEmpty() || workspace == null) {
            return;
        }
        Navigator nav = current();
        Set<String> paths = Set.copyOf(dirty);
        dirty.clear();
        long g = generation.get();
        try {
            Snapshot s = nav.snapshot();
            Snapshot.Analysis a = s.analyze(rules, ruleConfig, baseline, paths, 1, () -> generation.get() != g);
            publish(s, a, g, false);
        } catch (CancellationException e) {
            dirty.addAll(paths);
        }
    }

    private void startFull() {
        if (workspace == null) {
            return;
        }
        LoadProgress progress = loadProgress;
        loadProgress = null;
        if (progress != null) {
            progress.phase("Building the semantic model", 85);
        }
        Navigator nav = current();
        long g = generation.get();
        RuleConfig config = ruleConfig;
        Baseline base = baseline;
        project.execute(guard(() -> {
            if (generation.get() != g) {
                if (progress != null) {
                    progress.end("Interrupted by an edit");
                }
                return;
            }
            Snapshot s = nav.snapshot();
            try {
                if (progress != null) {
                    progress.phase("Checking the rules", 90);
                }
                long t0 = System.nanoTime();
                Snapshot.Analysis a =
                        s.analyze(rules, config, base, null, settings.threads(), () -> generation.get() != g);
                publish(s, a, g, true);
                log(
                        MessageType.Log,
                        "plca: analysed " + s.paths().size() + " files in " + (System.nanoTime() - t0) / 1_000_000
                                + " ms");
                if (progress != null) {
                    progress.end("Analysed " + s.paths().size() + " files");
                }
            } catch (CancellationException e) {
                // a newer edit started its own run
                if (progress != null) {
                    progress.end("Interrupted by an edit");
                }
            }
        }));
    }

    /**
     * Publishes the analysis for the files it covers: their parse diagnostics plus the run's diagnostics and findings
     * in them. A full run also clears files that are gone.
     */
    private synchronized void publish(Snapshot s, Snapshot.Analysis a, long g, boolean full) {
        if (generation.get() != g || client == null) {
            return;
        }
        Map<String, List<Diagnostic>> byPath = new HashMap<>();
        for (String path : a.paths()) {
            List<Diagnostic> list = new ArrayList<>();
            s.file(path).ifPresent(f -> f.diagnostics().forEach(d -> list.add(Convert.diagnostic(uris, s, d))));
            byPath.put(path, list);
        }
        for (dev.plca.core.diag.Diagnostic d : a.diagnostics()) {
            List<Diagnostic> list = byPath.get(s.pathOf(d.span()));
            if (list != null) {
                list.add(Convert.diagnostic(uris, s, d));
            }
        }
        for (Finding f : a.findings()) {
            List<Diagnostic> list = byPath.get(s.pathOf(f.span()));
            if (list != null) {
                list.add(Convert.diagnostic(uris, s, f));
            }
        }
        Set<String> sent = new LinkedHashSet<>();
        byPath.forEach((path, list) -> {
            String uri = uris.uri(s.root(), path);
            sent.add(uri);
            send(uri, list, versions.get(path));
        });
        if (full) {
            for (String uri : List.copyOf(published.keySet())) {
                if (!sent.contains(uri) && !uri.equals(configUri())) {
                    send(uri, List.of(), null);
                    published.remove(uri);
                }
            }
        }
    }

    private void send(String uri, List<Diagnostic> list, Integer version) {
        // nothing to say about a file that never had diagnostics: a large workspace would flood the client at start
        if (list.equals(published.getOrDefault(uri, List.of()))) {
            return;
        }
        if (list.isEmpty()) {
            published.remove(uri);
        } else {
            published.put(uri, list);
        }
        PublishDiagnosticsParams p = new PublishDiagnosticsParams(uri, list);
        p.setVersion(version);
        client.publishDiagnostics(p);
    }

    // ---- queries --------------------------------------------------------------------------------

    /** Runs a query on the current state, after every edit received before it; null before the workspace loaded. */
    <T> CompletableFuture<T> query(Function<Navigator, T> q) {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (workspace == null) {
                        return null;
                    }
                    try {
                        return q.apply(current());
                    } catch (RuntimeException | StackOverflowError e) {
                        LOG.log(Level.WARNING, "query failed", e);
                        return null;
                    }
                },
                analysis);
    }

    /**
     * A query on one file's unit that needs no model (drawings). While the files are parsed it runs at once on a
     * {@link QuickRead} of the file alone ({@code routine}: the ladder routine to parse, null for none), while the
     * first model is built on the parsed unit, afterwards on the workspace's unit after every edit received before it.
     * Null when the file has no unit.
     */
    <T> CompletableFuture<T> fileQuery(Path file, String routine, BiFunction<SourceRegistry, SourceUnit, T> q) {
        if (loading && root != null) {
            Path f = file.toAbsolutePath().normalize();
            if (!f.startsWith(root)) {
                return CompletableFuture.completedFuture(null);
            }
            String path = root.relativize(f).toString().replace('\\', '/');
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            Loaded l = loaded;
                            if (l != null) {
                                SourceUnit u = l.units().get(path);
                                return u == null ? null : q.apply(l.registry(), u);
                            }
                            QuickRead.Result r;
                            String key = path + "\0" + routine;
                            synchronized (quickReads) {
                                r = quickReads.get(key);
                            }
                            if (r == null) {
                                r = QuickRead.read(f, path, routine);
                                // the list's read also parsed the first routine: the page asked for next
                                List<Diagrams.Diagram> ds = routine == null ? Diagrams.of(r.unit()) : List.of();
                                synchronized (quickReads) {
                                    quickReads.put(key, r);
                                    if (!ds.isEmpty()) {
                                        quickReads.put(
                                                path + "\0" + ds.getFirst().name(), r);
                                    }
                                }
                            }
                            return r.unit() == null ? null : q.apply(r.registry(), r.unit());
                        } catch (IOException | RuntimeException | StackOverflowError e) {
                            LOG.log(Level.WARNING, "quick read failed", e);
                            return null;
                        }
                    },
                    quick);
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    if (workspace == null) {
                        return null;
                    }
                    try {
                        SourceUnit unit = workspace
                                .state(workspace.displayPath(file))
                                .map(Workspace.FileState::unit)
                                .orElse(null);
                        return unit == null ? null : q.apply(workspace.registry(), unit);
                    } catch (RuntimeException | StackOverflowError e) {
                        LOG.log(Level.WARNING, "query failed", e);
                        return null;
                    }
                },
                analysis);
    }

    /** The display path of a file in the current workspace (analysis thread or a query). */
    String displayPath(Path file) {
        return workspace.displayPath(file);
    }

    // ---- configuration --------------------------------------------------------------------------

    private boolean isDefaultConfig(Path file) {
        return settings.config() == null && file.equals(workspace.root().resolve(ConfigFile.NAME));
    }

    private String configUri() {
        if (configFile == null) {
            return null;
        }
        Path f = configFile.toAbsolutePath().normalize();
        return f.startsWith(workspace.root())
                ? uris.uri(workspace.root(), workspace.displayPath(f))
                : f.toUri().toString();
    }

    /** Reads {@code plca.yaml} and its baseline; a broken file is reported on its line and the defaults apply. */
    private void loadConfig() {
        Path file = settings.config() != null && !sandboxed
                ? workspace.root().resolve(settings.config())
                : workspace.root().resolve(ConfigFile.NAME);
        String previous = configUri();
        configFile = file;
        ruleConfig = RuleConfig.defaults();
        baseline = null;
        baselineFile = null;
        List<Diagnostic> problems = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            try {
                ConfigFile c = ConfigFile.read(file);
                RuleConfig rc = ConfigFile.ruleConfig(c, null, List.of(), Map.of());
                rc.resolve(rules);
                ruleConfig = rc;
                if (c.baseline() != null) {
                    if (sandboxed && !c.baseline().toAbsolutePath().normalize().startsWith(workspace.root())) {
                        throw new ConfigFile.ConfigException("the baseline must be a file of the project");
                    }
                    baselineFile = c.baseline();
                    baseline = Baseline.read(c.baseline());
                }
            } catch (ConfigFile.ConfigException | Baseline.BaselineException | IllegalArgumentException e) {
                problems.add(configProblem(uris.mask(e.getMessage())));
            } catch (IOException e) {
                problems.add(configProblem(uris.mask("cannot read " + e.getMessage())));
            }
        }
        synchronized (this) {
            if (previous != null && !previous.equals(configUri()) && published.containsKey(previous)) {
                send(previous, List.of(), null);
            }
            if (client != null) {
                send(configUri(), problems, null);
            }
        }
        if (!problems.isEmpty()) {
            log(MessageType.Warning, "plca: " + problems.getFirst().getMessage() + " (using the default rules)");
        }
    }

    private static final Pattern LINE = Pattern.compile(":(\\d+): ");

    private static Diagnostic configProblem(String message) {
        Matcher m = LINE.matcher(message);
        int line = m.find() ? Math.max(0, Integer.parseInt(m.group(1)) - 1) : 0;
        Range r = new Range(new Position(line, 0), new Position(line, 1000));
        return new Diagnostic(r, message, DiagnosticSeverity.Error, Convert.SOURCE, "PLC-CFG-001");
    }

    private void log(MessageType type, String text) {
        String message = uris.mask(text);
        LanguageClient c = client;
        if (c != null) {
            if (type == MessageType.Log) {
                c.logMessage(new MessageParams(type, message));
            } else {
                c.showMessage(new MessageParams(type, message));
            }
        }
    }

    /** Keeps the analysis threads alive whatever a task throws (design rule 2, for the server itself). */
    private Runnable guard(ThrowingRunnable r) {
        return () -> {
            try {
                r.run();
            } catch (Exception | StackOverflowError e) {
                LOG.log(Level.SEVERE, "plca analysis task failed", e);
                log(MessageType.Error, "plca: internal error: " + e);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Waits until queued work is done (tests): the analysis queue, pending timers, and the project tier. */
    void awaitIdle(long timeoutMs) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            CompletableFuture.runAsync(() -> {}, analysis).get(timeoutMs, TimeUnit.MILLISECONDS);
            boolean timersDone = CompletableFuture.supplyAsync(
                            () -> (fastTimer == null || fastTimer.isDone())
                                    && (fullTimer == null || fullTimer.isDone()),
                            analysis)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            CompletableFuture.runAsync(() -> {}, project).get(timeoutMs, TimeUnit.MILLISECONDS);
            CompletableFuture.runAsync(() -> {}, analysis).get(timeoutMs, TimeUnit.MILLISECONDS);
            if (timersDone) {
                CompletableFuture.runAsync(() -> {}, project).get(timeoutMs, TimeUnit.MILLISECONDS);
                return;
            }
            Thread.sleep(10);
        }
        throw new java.util.concurrent.TimeoutException("session not idle after " + timeoutMs + " ms");
    }

    void shutdown() {
        quick.shutdownNow();
        timers.shutdownNow();
        analysis.shutdownNow();
        project.shutdownNow();
    }
}
