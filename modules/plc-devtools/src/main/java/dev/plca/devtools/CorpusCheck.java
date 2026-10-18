package dev.plca.frontend.st.corpus;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
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
 * Parses every CODESYS/TwinCAT file of the harvested corpus ({@code corpus/MANIFEST.csv}) and reports how much of
 * real-world code the frontend accepts. Run with {@code ./gradlew :frontend-st:corpusCheck}.
 *
 * <p>Fails (exit 1) when the SLL-fallback rate on error-free texts exceeds {@value #MAX_CLEAN_FALLBACK_RATE}, or when a
 * format's repo-weighted error-free rate drops below {@code corpus-baseline.properties}. With {@code --update-baseline}
 * the current rates become the new baseline.
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
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "corpus-worker");
            t.setDaemon(true);
            return t;
        });
        ThreadLocal<StFrontend> frontends = ThreadLocal.withInitial(StFrontend::new);
        long t0 = System.nanoTime();
        List<Future<FileResult>> futures = new ArrayList<>();
        for (String[] r : rows) {
            futures.add(pool.submit(() -> {
                try {
                    return parseOne(corpus, r, frontends.get());
                } catch (Throwable e) {
                    // the frontend must never throw: record it as a failed file with the escape as its error
                    String err = "ESCAPED " + e;
                    return new FileResult(
                            r[0],
                            r[2],
                            r[4],
                            parseLongOr(r[8]),
                            false,
                            new StFrontend.Stats(0, 0, 0, 0),
                            List.of(err),
                            err);
                }
            }));
        }
        List<FileResult> results = new ArrayList<>();
        for (Future<FileResult> f : futures) {
            FileResult r = f.get();
            if (r != null) {
                results.add(r);
            }
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - t0) / 1e9;

        Files.createDirectories(reportDir);
        Map<String, Double> rates = report(results, seconds, threads, out, reportDir);
        boolean ok = checkThresholds(results, rates, baseline, update, out);
        if (!ok) {
            System.exit(1);
        }
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
                    || (f[3].equals("TwinCat") && format.matches("TcPOU|TcDUT|TcGVL|TcIO"))) {
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

    private static FileResult parseOne(Path corpus, String[] r, StFrontend frontend) {
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
        SourceRegistry registry = new SourceRegistry();
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
        return new FileResult(repoUrl, r[2], r[4], lines, errors.isEmpty(), result.stats(), errors, first);
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
            rates.put(e.getKey(), repoRate);
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
            String key = "errorFree.repoWeighted." + e.getKey();
            String base = baseline.getProperty(key);
            // allow 0.2 points of noise from corpus changes
            if (!update && base != null && e.getValue() < Double.parseDouble(base) - 0.002) {
                out.printf(
                        Locale.ROOT,
                        "FAIL: %s error-free rate %.2f%% < baseline %.2f%%%n",
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
            StringBuilder sb =
                    new StringBuilder("# frontend-st corpus baseline: repo-weighted error-free rate per format.\n"
                            + "# Regenerate with ./gradlew :frontend-st:corpusCheck -PupdateBaseline\n");
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
