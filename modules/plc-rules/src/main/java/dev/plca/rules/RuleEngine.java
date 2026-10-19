package dev.plca.rules;

import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the enabled rules over a model: {@link PouRule}s once per POU body (parallel over POUs), {@link ProjectRule}s
 * once each. A rule that throws is isolated: the failure becomes {@link #INTERNAL} for that rule and POU, and the run
 * continues (design rule 2). Findings come back in a fixed order — file path, offset in the file, rule id — whatever
 * the thread schedule.
 */
public final class RuleEngine {

    /** A rule failed on a POU (or on the project); the rule's results there are missing. */
    public static final String INTERNAL = "PLC-INT-002";

    /**
     * Findings, suppressed findings, engine diagnostics, and time spent per rule id (nanoseconds, summed over threads).
     */
    public record Result(
            List<Finding> findings,
            List<Suppressions.Suppressed> suppressed,
            List<Diagnostic> diagnostics,
            Map<String, Long> nanosPerRule) {
        public Result {
            findings = List.copyOf(findings);
            suppressed = List.copyOf(suppressed);
            diagnostics = List.copyOf(diagnostics);
            nanosPerRule = Map.copyOf(nanosPerRule);
        }
    }

    /** A rule's resolved settings per dialect, and the dialect of a span's file. */
    static final class Settings {
        private final Map<Dialect, Boolean> enabled = new EnumMap<>(Dialect.class);
        private final Map<Dialect, Severity> severity = new EnumMap<>(Dialect.class);
        private final boolean globalEnabled;
        private final Severity globalSeverity;
        private final SourceRegistry sources;
        private final Map<String, Dialect> fileDialects;

        Settings(
                RuleRegistry registry,
                RuleConfig config,
                RuleDescriptor d,
                SourceRegistry sources,
                Map<String, Dialect> fileDialects) {
            for (Dialect dialect : Dialect.values()) {
                enabled.put(dialect, d.appliesTo(dialect) && config.enabled(registry, d.id(), dialect));
                severity.put(dialect, config.severity(d, dialect));
            }
            this.globalEnabled = config.enabled(registry, d.id(), null);
            this.globalSeverity = config.severity(d, null);
            this.sources = sources;
            this.fileDialects = fileDialects;
        }

        boolean enabled(Dialect d) {
            return d == null ? globalEnabled : enabled.get(d);
        }

        Severity severity(Dialect d) {
            return d == null ? globalSeverity : severity.get(d);
        }

        Dialect dialectOf(Span span) {
            return fileDialects.get(sources.text(span.textId()).file().path());
        }
    }

    private final RuleRegistry registry;

    public RuleEngine(RuleRegistry registry) {
        this.registry = registry;
    }

    public RuleRegistry registry() {
        return registry;
    }

    /** @throws IllegalArgumentException for an invalid configuration (unknown rule or parameter) */
    public Result run(SemanticModel model, SourceRegistry sources, RuleConfig config, int threads) {
        Set<String> enabled = config.resolve(registry);
        List<PouRule> pouRules = new ArrayList<>();
        List<ProjectRule> projectRules = new ArrayList<>();
        for (String id : enabled) {
            switch (registry.rule(id).orElseThrow()) {
                case PouRule r -> pouRules.add(r);
                case ProjectRule r -> projectRules.add(r);
            }
        }

        Map<String, Dialect> fileDialects = new HashMap<>();
        model.units().forEach(u -> fileDialects.putIfAbsent(u.path(), u.dialect()));
        Map<String, Settings> settings = new HashMap<>();
        for (String id : enabled) {
            settings.put(
                    id, new Settings(registry, config, registry.descriptor(id).orElseThrow(), sources, fileDialects));
        }

        List<Callable<Partial>> tasks = new ArrayList<>();
        if (!pouRules.isEmpty()) {
            for (PouSymbol p : model.pous()) {
                if (!p.isBuiltin() && p.declaration() != null && p.declaration().body() != null) {
                    tasks.add(() -> runPou(model, sources, config, settings, pouRules, p));
                }
            }
        }
        for (ProjectRule r : projectRules) {
            tasks.add(() -> runProject(model, sources, config, settings.get(r.id()), r));
        }

        List<Partial> parts = execute(tasks, threads);
        List<Finding> findings = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Long> nanos = new LinkedHashMap<>();
        enabled.forEach(id -> nanos.put(id, 0L));
        for (Partial p : parts) {
            findings.addAll(p.findings);
            diagnostics.addAll(p.diagnostics);
            p.nanos.forEach((k, v) -> nanos.merge(k, v, Long::sum));
        }
        findings.sort(order(sources));
        Suppressions suppressions = new Suppressions(registry, model, sources, config);
        diagnostics.addAll(suppressions.diagnostics());
        List<Finding> kept = new ArrayList<>(findings.size());
        List<Suppressions.Suppressed> suppressed = new ArrayList<>();
        for (Finding f : findings) {
            Suppressions.Suppressed s = suppressions.check(f);
            if (s == null) {
                kept.add(f);
            } else {
                suppressed.add(s);
            }
        }
        return new Result(kept, suppressed, diagnostics, nanos);
    }

    private static final class Partial {
        final List<Finding> findings = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final Map<String, Long> nanos = new LinkedHashMap<>();
    }

    private Partial runPou(
            SemanticModel model,
            SourceRegistry sources,
            RuleConfig config,
            Map<String, Settings> settings,
            List<PouRule> rules,
            PouSymbol pou) {
        Partial out = new Partial();
        for (PouRule r : rules) {
            RuleDescriptor d = registry.descriptor(r.id()).orElseThrow();
            Settings s = settings.get(r.id());
            if (!d.appliesTo(pou.dialect()) || !s.enabled(pou.dialect())) {
                continue;
            }
            PouContext ctx = new PouContext(model, sources, d, config.parameters(d.id(), pou.dialect()), s, pou);
            long t0 = System.nanoTime();
            try {
                r.check(ctx);
                out.findings.addAll(ctx.findings);
            } catch (RuntimeException | StackOverflowError e) {
                out.diagnostics.add(failure(
                        d,
                        "POU " + pou.qualifiedName(),
                        e,
                        pou.declaration().name().span()));
            }
            out.nanos.merge(r.id(), System.nanoTime() - t0, Long::sum);
        }
        return out;
    }

    private Partial runProject(
            SemanticModel model, SourceRegistry sources, RuleConfig config, Settings settings, ProjectRule r) {
        Partial out = new Partial();
        RuleDescriptor d = registry.descriptor(r.id()).orElseThrow();
        ProjectContext ctx = new ProjectContext(model, sources, d, config.parameters(d.id(), null), settings);
        long t0 = System.nanoTime();
        try {
            r.check(ctx);
            out.findings.addAll(ctx.findings);
        } catch (RuntimeException | StackOverflowError e) {
            Span anchor = model.pous().stream()
                    .filter(p -> p.declaration() != null)
                    .map(p -> p.declaration().name().span())
                    .findFirst()
                    .orElse(sources.size() > 0 ? Span.at(0, 0) : null);
            if (anchor != null) {
                out.diagnostics.add(failure(d, "the project", e, anchor));
            }
        }
        out.nanos.merge(r.id(), System.nanoTime() - t0, Long::sum);
        return out;
    }

    private static Diagnostic failure(RuleDescriptor d, String where, Throwable e, Span span) {
        StackTraceElement[] st = e.getStackTrace();
        String at = st.length == 0 ? "" : " at " + st[0];
        return Diagnostic.of(
                INTERNAL,
                Severity.WARNING,
                "rule " + d.id() + " failed on " + where + ": " + e + at + " (results of this rule there are missing)",
                span);
    }

    private static List<Partial> execute(List<Callable<Partial>> tasks, int threads) {
        List<Partial> out = new ArrayList<>(tasks.size());
        if (threads <= 1 || tasks.size() <= 1) {
            for (Callable<Partial> t : tasks) {
                try {
                    out.add(t.call());
                } catch (Exception e) {
                    throw new IllegalStateException("unexpected rule engine failure", e);
                }
            }
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "plca-rules");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Partial>> futures = new ArrayList<>();
            for (Callable<Partial> t : tasks) {
                futures.add(pool.submit(t));
            }
            for (Future<Partial> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running rules", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("unexpected rule engine failure", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Path, offset in the physical file, rule id, then message: independent of text ids and scheduling. */
    static Comparator<Finding> order(SourceRegistry sources) {
        return Comparator.<Finding, String>comparing(
                        f -> text(sources, f).file().path())
                .thenComparingInt(
                        f -> text(sources, f).map().toFileStart(f.span().start()))
                .thenComparing(Finding::ruleId)
                .thenComparingInt(f -> f.span().end())
                .thenComparing(Finding::message);
    }

    private static SourceText text(SourceRegistry sources, Finding f) {
        return sources.text(f.span().textId());
    }
}
