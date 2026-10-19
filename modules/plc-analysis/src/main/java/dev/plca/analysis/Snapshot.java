package dev.plca.analysis;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.Span;
import dev.plca.report.Baseline;
import dev.plca.report.Fingerprints;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleEngine;
import dev.plca.rules.RuleRegistry;
import dev.plca.semantic.SemanticModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * An immutable state of a {@link Workspace}: files, units, the registry their spans resolve in, and one semantic model
 * per model group. Safe to query from any thread.
 */
public final class Snapshot {

    /** One semantic model and the units it was built from; {@code diagnostics} are the model builder's. */
    public record Group(List<SourceUnit> units, SemanticModel model, List<Diagnostic> diagnostics) {
        public Group {
            units = List.copyOf(units);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * The result of a rule run: findings that are neither suppressed nor in the baseline, and the diagnostics of the
     * model builder and the rule engine. {@code paths} are the files the run covered (all files for a full run).
     */
    public record Analysis(List<Finding> findings, List<Diagnostic> diagnostics, Set<String> paths) {
        public Analysis {
            findings = List.copyOf(findings);
            diagnostics = List.copyOf(diagnostics);
            paths = Set.copyOf(paths);
        }
    }

    private final Path root;
    private final SourceRegistry registry;
    private final Map<String, Workspace.FileState> files;
    private final List<Group> groups;

    Snapshot(Path root, SourceRegistry registry, Map<String, Workspace.FileState> files, List<Group> groups) {
        this.root = root;
        this.registry = registry;
        this.files = Collections.unmodifiableMap(new TreeMap<>(files));
        this.groups = List.copyOf(groups);
    }

    public Path root() {
        return root;
    }

    public SourceRegistry registry() {
        return registry;
    }

    /** Display paths of all files, sorted. */
    public Set<String> paths() {
        return files.keySet();
    }

    public Optional<Workspace.FileState> file(String path) {
        return Optional.ofNullable(files.get(path));
    }

    public List<Group> groups() {
        return groups;
    }

    /** The group whose model contains the unit of {@code path}. */
    public Optional<Group> groupOf(String path) {
        Workspace.FileState f = files.get(path);
        if (f == null || f.unit() == null) {
            return Optional.empty();
        }
        for (Group g : groups) {
            for (SourceUnit u : g.units()) {
                if (u == f.unit()) {
                    return Optional.of(g);
                }
            }
        }
        return Optional.empty();
    }

    /** The display path of the physical file a span lies in. */
    public String pathOf(Span span) {
        return Workspace.pathOf(registry, span);
    }

    /**
     * Runs the rules. With {@code paths} null, all rules on all groups (what {@code plca analyze} reports); otherwise
     * only the POU rules, on the POUs declared in those files: the editor's fast tier. Project rules depend on the
     * whole model and wait for a full run. Inline and configured suppressions and the baseline apply in both cases, so
     * the editor shows what CI reports.
     *
     * @throws java.util.concurrent.CancellationException when {@code cancelled} turns true
     * @throws IllegalArgumentException for an invalid rule configuration
     */
    public Analysis analyze(
            RuleRegistry rules,
            RuleConfig config,
            Baseline baseline,
            Set<String> paths,
            int threads,
            BooleanSupplier cancelled) {
        RuleEngine.Scope scope = paths == null
                ? new RuleEngine.Scope(p -> true, true, cancelled)
                : new RuleEngine.Scope(
                        p -> p.unit() != null && paths.contains(p.unit().path()), false, cancelled);
        List<RuleEngine.Result> results = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Group g : groups) {
            if (paths != null && g.units().stream().noneMatch(u -> paths.contains(u.path()))) {
                continue;
            }
            for (Diagnostic d : g.diagnostics()) {
                if (paths == null || paths.contains(pathOf(d.span()))) {
                    diagnostics.add(d);
                }
            }
            results.add(new RuleEngine(rules).run(g.model(), registry, config, threads, scope));
        }
        RuleEngine.Result merged = results.isEmpty()
                ? new RuleEngine.Result(List.of(), List.of(), List.of(), Map.of())
                : RuleEngine.Result.merge(results, registry);
        diagnostics.addAll(merged.diagnostics());
        List<Finding> findings = merged.findings();
        if (baseline != null) {
            List<String> fps = Fingerprints.of(findings, registry);
            List<Finding> kept = new ArrayList<>();
            for (int i = 0; i < findings.size(); i++) {
                if (!baseline.contains(fps.get(i))) {
                    kept.add(findings.get(i));
                }
            }
            findings = kept;
        }
        return new Analysis(findings, diagnostics, paths == null ? files.keySet() : paths);
    }

    /** This snapshot's group with exactly these units (same objects, same order), or null. */
    Group sameGroup(List<SourceUnit> units) {
        for (Group g : groups) {
            if (g.units().size() == units.size()) {
                boolean same = true;
                for (int i = 0; i < units.size() && same; i++) {
                    same = g.units().get(i) == units.get(i);
                }
                if (same) {
                    return g;
                }
            }
        }
        return null;
    }
}
