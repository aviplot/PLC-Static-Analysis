package dev.plca.devtools;

import dev.plca.ast.Expression;
import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import dev.plca.rules.RuleEngine;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Parses every CODESYS/TwinCAT file of the harvested corpus ({@code corpus/MANIFEST.csv}), builds the semantic model
 * per project (a TwinCAT {@code .plcproj} directory, else the repository) and reports how much real-world code the
 * frontend accepts, how many names the model resolves, and what the rules report (see {@link RuleCorpusStats}). Run
 * with {@code ./gradlew :plc-devtools:corpusCheck}.
 *
 * <p>Fails (exit 1) when a rule fails on any project, when the SLL-fallback rate on error-free texts exceeds
 * {@value #MAX_CLEAN_FALLBACK_RATE}, or when a rate drops below {@code corpus-baseline.properties}. With
 * {@code --update-baseline} the current rates become the new baseline.
 */
public final class CorpusCheck {

    static final double MAX_CLEAN_FALLBACK_RATE = 0.02;
    private static final Pattern QUOTED = Pattern.compile("'[^']*'");

    record FileResult(
            String repo,
            String path,
            String format,
            long lines,
            boolean clean,
            StFrontend.Stats stats,
            List<String> errors,
            String firstError) {}

    /** Semantic results of one project. */
    record ProjectResult(
            String repo,
            String key,
            String group,
            int files,
            SemanticModel.ResolutionStats stats,
            long nanos,
            String escaped,
            List<String> unresolved,
            long parseNanos,
            RuleCorpusStats.Outcome rules) {}

    private CorpusCheck() {}

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args[0]);
        Path baseline = Path.of(args[1]);
        Path reportDir = Path.of(args[2]);
        boolean update = List.of(args).contains("--update-baseline");
        Path manifest = corpus.resolve("MANIFEST.csv");
        PrintStream out = System.out;
        if (!Files.exists(manifest)) {
            out.println("corpusCheck: no " + manifest + " — skipped (run the corpus harvester first)");
            return;
        }
        List<String[]> rows = readManifest(manifest);
        Map<String, List<String[]>> projects = groupByProject(rows);
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "corpus-worker");
            t.setDaemon(true);
            return t;
        });
        ThreadLocal<StFrontend> frontends = ThreadLocal.withInitial(StFrontend::new);
        long t0 = System.nanoTime();
        List<Future<Map.Entry<List<FileResult>, ProjectResult>>> futures = new ArrayList<>();
        // largest projects first so one huge repository does not finish last on its own
        List<Map.Entry<String, List<String[]>>> ordered = new ArrayList<>(projects.entrySet());
        ordered.sort(Comparator.comparingInt(
                (Map.Entry<String, List<String[]>> e) -> -e.getValue().size()));
        for (var project : ordered) {
            futures.add(pool.submit(() -> parseProject(corpus, project.getKey(), project.getValue(), frontends.get())));
        }
        List<FileResult> results = new ArrayList<>();
        List<ProjectResult> projectResults = new ArrayList<>();
        for (var f : futures) {
            var r = f.get();
            results.addAll(r.getKey());
            projectResults.add(r.getValue());
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - t0) / 1e9;

        Files.createDirectories(reportDir);
        Map<String, Double> rates = report(results, seconds, threads, out, reportDir);
        rates.putAll(semanticReport(projectResults, out, reportDir));
        long lines = results.stream().mapToLong(FileResult::lines).sum();
        long parseAndModel = projectResults.stream()
                .mapToLong(p -> p.parseNanos() + p.nanos())
                .sum();
        int ruleFailures = RuleCorpusStats.report(
                projectResults.stream().map(ProjectResult::rules).toList(), lines, parseAndModel, out, reportDir);
        boolean ok = checkThresholds(results, rates, baseline, update, out);
        if (ruleFailures > 0) {
            out.println("FAIL: " + ruleFailures + " rule failures (" + RuleEngine.INTERNAL + ")");
            ok = false;
        }
        if (!ok) {
            System.exit(1);
        }
    }

    /** Project = deepest directory holding a .plcproj above the file, otherwise the repository. */
    static Map<String, List<String[]>> groupByProject(List<String[]> rows) {
        Map<String, List<String>> plcprojDirs = new HashMap<>();
        for (String[] r : rows) {
            if (r[4].equals("plcproj")) {
                int slash = r[2].lastIndexOf('/');
                plcprojDirs
                        .computeIfAbsent(r[0], k -> new ArrayList<>())
                        .add(slash < 0 ? "" : r[2].substring(0, slash + 1));
            }
        }
        Map<String, List<String[]>> out = new TreeMap<>();
        for (String[] r : rows) {
            if (r[4].equals("plcproj")) {
                continue;
            }
            String best = "";
            for (String dir : plcprojDirs.getOrDefault(r[0], List.of())) {
                if (r[2].startsWith(dir) && dir.length() > best.length()) {
                    best = dir;
                }
            }
            out.computeIfAbsent(r[0] + "|" + best, k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static Map.Entry<List<FileResult>, ProjectResult> parseProject(
            Path corpus, String key, List<String[]> rows, StFrontend frontend) {
        SourceRegistry registry = new SourceRegistry();
        List<FileResult> results = new ArrayList<>();
        List<SourceUnit> units = new ArrayList<>();
        rows.sort(Comparator.comparing(r -> r[2]));
        boolean twinCat = false;
        long parseStart = System.nanoTime();
        for (String[] r : rows) {
            twinCat |= r[3].equals("TwinCat");
            try {
                var parsed = parseOne(corpus, r, frontend, registry);
                if (parsed != null) {
                    results.add(parsed.getKey());
                    units.add(parsed.getValue());
                }
            } catch (Throwable e) {
                // the frontend must never throw: record it as a failed file with the escape as its error
                String err = "ESCAPED " + e;
                results.add(new FileResult(
                        r[0],
                        r[2],
                        r[4],
                        parseLongOr(r[8]),
                        false,
                        new StFrontend.Stats(0, 0, 0, 0),
                        List.of(err),
                        err));
            }
        }
        long t = System.nanoTime();
        long parseNanos = t - parseStart;
        RuleCorpusStats.Outcome rules = RuleCorpusStats.Outcome.NONE;
        long modelNanos = 0;
        SemanticModel.ResolutionStats stats = new SemanticModel.ResolutionStats(0, 0, 0, 0);
        String escaped = null;
        List<String> unresolved = new ArrayList<>();
        try {
            DiagnosticCollector diags = new DiagnosticCollector();
            SemanticModel model = SemanticBuilder.build(units, diags);
            stats = model.resolutionStats();
            for (Expression e : model.unresolved()) {
                String name = switch (e) {
                    case Expression.NameRef n -> n.name().key();
                    case Expression.MemberAccess m -> "." + m.member().key();
                    default -> "?";
                };
                var l = registry.resolve(e.span());
                unresolved.add(name + "|" + l.path() + ":" + l.startLine());
            }
            for (Diagnostic d : diags.diagnostics()) {
                if (d.code().equals(SemanticBuilder.INTERNAL)) {
                    escaped = d.message();
                }
            }
            modelNanos = System.nanoTime() - t;
            try {
                rules = RuleCorpusStats.run(rows.getFirst()[0], key, model, registry);
            } catch (Throwable e) {
                rules = new RuleCorpusStats.Outcome(Map.of(), Map.of(), List.of("ESCAPED " + e), List.of());
            }
        } catch (Throwable e) {
            escaped = "ESCAPED " + e;
            modelNanos = System.nanoTime() - t;
        }
        String repo = rows.getFirst()[0];
        return Map.entry(
                results,
                new ProjectResult(
                        repo,
                        key,
                        twinCat ? "TwinCat" : "st",
                        rows.size(),
                        stats,
                        modelNanos,
                        escaped,
                        unresolved,
                        parseNanos,
                        rules));
    }

    private static Map<String, Double> semanticReport(List<ProjectResult> projects, PrintStream out, Path reportDir)
            throws IOException {
        StringBuilder md = new StringBuilder("\n## Semantic model (per project)\n\n");
        md.append("| group | projects | names | resolved | library | unresolved | resolved+library (repo-weighted) |"
                + " model time |\n|---|---|---|---|---|---|---|---|\n");
        Map<String, Double> rates = new LinkedHashMap<>();
        Map<String, List<ProjectResult>> byGroup = new TreeMap<>();
        projects.forEach(
                p -> byGroup.computeIfAbsent(p.group(), k -> new ArrayList<>()).add(p));
        for (var e : byGroup.entrySet()) {
            List<ProjectResult> ps = e.getValue();
            long names = ps.stream().mapToLong(p -> p.stats().names()).sum();
            long resolved = ps.stream().mapToLong(p -> p.stats().resolved()).sum();
            long library = ps.stream().mapToLong(p -> p.stats().library()).sum();
            long unresolved = ps.stream().mapToLong(p -> p.stats().unresolved()).sum();
            Map<String, double[]> perRepo = new HashMap<>();
            for (ProjectResult p : ps) {
                if (p.stats().names() > 0) {
                    double[] a = perRepo.computeIfAbsent(p.repo(), k -> new double[2]);
                    a[0] += p.stats().rate();
                    a[1]++;
                }
            }
            double repoRate = perRepo.values().stream()
                    .mapToDouble(a -> a[0] / a[1])
                    .average()
                    .orElse(1);
            rates.put("resolved.repoWeighted." + e.getKey(), repoRate);
            double ms = ps.stream().mapToLong(ProjectResult::nanos).sum() / 1e6;
            md.append(String.format(
                    Locale.ROOT,
                    "| %s | %,d | %,d | %.2f%% | %.2f%% | %.2f%% | %.2f%% | %,.0f ms |%n",
                    e.getKey(),
                    ps.size(),
                    names,
                    pct(resolved, names),
                    pct(library, names),
                    pct(unresolved, names),
                    100 * repoRate,
                    ms));
        }
        List<ProjectResult> escaped =
                projects.stream().filter(p -> p.escaped() != null).toList();
        md.append(String.format(Locale.ROOT, "%nProjects with internal errors: %d%n", escaped.size()));
        escaped.stream()
                .limit(10)
                .forEach(p -> md.append("- ")
                        .append(p.key())
                        .append(": ")
                        .append(p.escaped())
                        .append('\n'));

        Map<String, int[]> names = new HashMap<>();
        Map<String, java.util.Set<String>> repos = new HashMap<>();
        Map<String, String> examples = new HashMap<>();
        for (ProjectResult p : projects) {
            for (String entry : p.unresolved()) {
                String n = entry.substring(0, entry.indexOf('|'));
                names.computeIfAbsent(n, k -> new int[1])[0]++;
                repos.computeIfAbsent(n, k -> new java.util.HashSet<>()).add(p.repo());
                examples.putIfAbsent(n, p.repo() + "/" + entry.substring(entry.indexOf('|') + 1));
            }
        }
        md.append("\n## Top unresolved names (occurrences, repos)\n\n");
        repos.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, java.util.Set<String>> x) ->
                                -x.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .limit(40)
                .forEach(x -> md.append(String.format(
                        Locale.ROOT,
                        "- `%s` × %,d in %,d repos — e.g. %s%n",
                        x.getKey(),
                        names.get(x.getKey())[0],
                        x.getValue().size(),
                        examples.get(x.getKey()))));
        out.print(md);
        Files.writeString(reportDir.resolve("summary.md"), md, java.nio.file.StandardOpenOption.APPEND);
        return rates;
    }

    private static double pct(long a, long b) {
        return b == 0 ? 100 : 100.0 * a / b;
    }

    private static List<String[]> readManifest(Path manifest) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        // repo_url,commit_sha,path,dialect,format,spdx_license,policy,size_bytes,lines,sha256
        for (String line : lines.subList(1, lines.size())) {
            String[] f = parseCsv(line);
            if (f.length < 10) {
                continue;
            }
            String format = f[4];
            if ((f[3].equals("Codesys") && format.equals("st"))
                    || (f[3].equals("TwinCat") && format.matches("TcPOU|TcDUT|TcGVL|TcIO|TcTTO|plcproj"))) {
                rows.add(f);
            }
        }
        return rows;
    }

    static String[] parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }

    private static Map.Entry<FileResult, SourceUnit> parseOne(
            Path corpus, String[] r, StFrontend frontend, SourceRegistry registry) {
        String repoUrl = r[0];
        String rel = repoUrl.replace("https://github.com/", "github/").replace("https://gitlab.com/", "gitlab/");
        Path file = corpus.resolve("external").resolve(rel).resolve(r[2]);
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
        SourceFile sf = SourceFile.fromBytes(r[2], data);
        DiagnosticCollector diags = new DiagnosticCollector();
        StFrontend.Result result = frontend.parse(sf, registry, diags);
        List<String> errors = new ArrayList<>();
        String first = null;
        for (Diagnostic d : diags.diagnostics()) {
            if (d.severity() != Severity.ERROR) {
                continue;
            }
            errors.add(d.code() + " " + normalise(d.message()));
            if (first == null) {
                Location l = registry.resolve(d.span());
                first = l.startLine() + ":" + l.startColumn() + " " + d.message();
            }
        }
        long lines = parseLongOr(r[8]);
        return Map.entry(
                new FileResult(repoUrl, r[2], r[4], lines, errors.isEmpty(), result.stats(), errors, first),
                result.unit());
    }

    private static Map<String, Double> report(
            List<FileResult> results, double seconds, int threads, PrintStream out, Path reportDir) throws IOException {
        Map<String, List<FileResult>> byFormat = new TreeMap<>();
        for (FileResult r : results) {
            byFormat.computeIfAbsent(r.format(), k -> new ArrayList<>()).add(r);
        }
        long totalLines = results.stream().mapToLong(FileResult::lines).sum();
        StFrontend.Stats total = results.stream()
                .map(FileResult::stats)
                .reduce(new StFrontend.Stats(0, 0, 0, 0), StFrontend.Stats::plus);

        StringBuilder md = new StringBuilder("# frontend-st corpus check\n\n");
        md.append(String.format(
                Locale.ROOT,
                "%,d files, %,d lines in %.1f s on %d threads (%,.0f lines/s)%n%n",
                results.size(),
                totalLines,
                seconds,
                threads,
                totalLines / seconds));
        md.append(
                "| format | files | error-free (files) | error-free (repo-weighted) | repos |\n|---|---|---|---|---|\n");
        Map<String, Double> rates = new LinkedHashMap<>();
        for (var e : byFormat.entrySet()) {
            List<FileResult> rs = e.getValue();
            double fileRate = rs.stream().filter(FileResult::clean).count() / (double) rs.size();
            Map<String, double[]> perRepo = new HashMap<>();
            for (FileResult r : rs) {
                double[] a = perRepo.computeIfAbsent(r.repo(), k -> new double[2]);
                a[0] += r.clean() ? 1 : 0;
                a[1]++;
            }
            double repoRate = perRepo.values().stream()
                    .mapToDouble(a -> a[0] / a[1])
                    .average()
                    .orElse(1);
            rates.put("errorFree.repoWeighted." + e.getKey(), repoRate);
            md.append(String.format(
                    Locale.ROOT,
                    "| %s | %,d | %.2f%% | %.2f%% | %,d |%n",
                    e.getKey(),
                    rs.size(),
                    100 * fileRate,
                    100 * repoRate,
                    perRepo.size()));
        }
        md.append(String.format(
                Locale.ROOT,
                "%nTexts parsed: %,d; SLL fallbacks: %,d (%.2f%%); clean fallbacks (SLL failed, LL no error): %,d (%.3f%%)%n",
                total.texts(),
                total.sllFallbacks(),
                100.0 * total.sllFallbacks() / Math.max(1, total.texts()),
                total.cleanFallbacks(),
                100.0 * total.cleanFallbacks() / Math.max(1, total.texts())));

        Map<String, int[]> messages = new HashMap<>();
        Map<String, String> examples = new HashMap<>();
        for (FileResult r : results) {
            for (String m : r.errors().stream().distinct().toList()) {
                messages.computeIfAbsent(m, k -> new int[1])[0]++;
                examples.putIfAbsent(m, r.repo() + "/" + r.path());
            }
        }
        md.append("\n## Top error messages (files affected)\n\n");
        messages.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> x) -> -x.getValue()[0]))
                .limit(40)
                .forEach(x -> md.append(String.format(
                        Locale.ROOT,
                        "- %,d × `%s` — e.g. %s%n",
                        x.getValue()[0],
                        x.getKey(),
                        examples.get(x.getKey()))));
        out.print(md);
        Files.writeString(reportDir.resolve("summary.md"), md);

        StringBuilder tsv = new StringBuilder("format\trepo\tpath\tfirst_error\n");
        for (FileResult r : results) {
            if (!r.clean()) {
                tsv.append(r.format())
                        .append('\t')
                        .append(r.repo())
                        .append('\t')
                        .append(r.path())
                        .append('\t')
                        .append(r.firstError().replace('\t', ' '))
                        .append('\n');
            }
        }
        Files.writeString(reportDir.resolve("failures.tsv"), tsv);
        out.println("\nReports: " + reportDir.toAbsolutePath());
        return rates;
    }

    private static boolean checkThresholds(
            List<FileResult> results, Map<String, Double> rates, Path baselineFile, boolean update, PrintStream out)
            throws IOException {
        boolean ok = true;
        long cleanTexts = 0;
        long cleanFallbacks = 0;
        for (FileResult r : results) {
            if (r.clean()) {
                cleanTexts += r.stats().texts();
                cleanFallbacks += r.stats().cleanFallbacks();
            }
        }
        double fallbackRate = cleanFallbacks / (double) Math.max(1, cleanTexts);
        if (fallbackRate > MAX_CLEAN_FALLBACK_RATE) {
            out.printf(
                    Locale.ROOT,
                    "FAIL: SLL fallback rate on error-free texts %.3f%% > %.1f%%%n",
                    100 * fallbackRate,
                    100 * MAX_CLEAN_FALLBACK_RATE);
            ok = false;
        }
        Properties baseline = new Properties();
        if (Files.exists(baselineFile)) {
            try (var in = Files.newBufferedReader(baselineFile)) {
                baseline.load(in);
            }
        }
        for (var e : rates.entrySet()) {
            String key = e.getKey();
            String base = baseline.getProperty(key);
            // allow 0.2 points of noise from corpus changes
            if (!update && base != null && e.getValue() < Double.parseDouble(base) - 0.002) {
                out.printf(
                        Locale.ROOT,
                        "FAIL: %s = %.2f%% < baseline %.2f%%%n",
                        e.getKey(),
                        100 * e.getValue(),
                        100 * Double.parseDouble(base));
                ok = false;
            }
            if (update) {
                baseline.setProperty(key, String.format(Locale.ROOT, "%.4f", e.getValue()));
            }
        }
        if (update) {
            // written by hand (not Properties.store) so the file has no timestamp and diffs stay clean
            StringBuilder sb = new StringBuilder(
                    "# Corpus baseline: repo-weighted error-free rate per format and name-resolution rate per group.\n"
                            + "# Regenerate with ./gradlew :plc-devtools:corpusCheck -PupdateBaseline\n");
            new TreeMap<>(baseline)
                    .forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            Files.writeString(baselineFile, sb);
            out.println("baseline updated: " + baselineFile);
        }
        out.println(ok ? "corpusCheck OK" : "corpusCheck FAILED");
        return ok;
    }

    /** Keeps keyword-like quoted tokens ('END_IF', ':='), hides names and literals so messages group. */
    static String normalise(String message) {
        return QUOTED.matcher(message).replaceAll(m -> {
            String q = m.group();
            String inner = q.substring(1, q.length() - 1);
            boolean keyword = inner.length() <= 18 && inner.matches("[A-Z_]+|[^A-Za-z0-9]+");
            return keyword ? java.util.regex.Matcher.quoteReplacement(q) : "'…'";
        });
    }

    private static long parseLongOr(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
