package dev.plca.devtools;

import dev.plca.analysis.Snapshot;
import dev.plca.analysis.Workspace;
import dev.plca.analysis.nav.Navigator;
import dev.plca.analysis.nav.Outline;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Editor queries on real projects (M8): for each directory given, loads it as the language server does and asks symbol,
 * definition, references and hover at sampled positions of every file, plus the outline of every file. Reports failures
 * (any exception is a bug), how often a position names a symbol, and query times. Run with {@code ./gradlew
 * :plc-devtools:navigationCheck --args="<dir> ..."}; exits 1 on a failure.
 */
public final class NavigationCheck {

    private static final int SAMPLES_PER_FILE = 200;

    private NavigationCheck() {}

    public static void main(String[] args) {
        int failures = 0;
        for (String arg : args) {
            failures += check(Path.of(arg));
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int check(Path root) {
        long t0 = System.nanoTime();
        Workspace w = new Workspace(root);
        w.loadAll(Runtime.getRuntime().availableProcessors());
        Snapshot s = w.snapshot();
        Navigator nav = new Navigator(s);
        long loaded = System.nanoTime() - t0;
        Random random = new Random(1);
        int queries = 0;
        int named = 0;
        int failures = 0;
        List<Long> times = new ArrayList<>();
        Map<String, String> firstFailures = new LinkedHashMap<>();
        Outline outline = new Outline(s);
        for (String path : s.paths()) {
            String text = s.file(path).orElseThrow().file().text();
            try {
                outline.document(path).forEach(i -> s.registry().resolve(i.selection()));
            } catch (RuntimeException | StackOverflowError e) {
                failures++;
                firstFailures.putIfAbsent(path + " (outline)", e.toString());
            }
            for (int k = 0; k < SAMPLES_PER_FILE && !text.isEmpty(); k++) {
                int offset = random.nextInt(text.length());
                long q0 = System.nanoTime();
                try {
                    queries++;
                    var t = nav.symbolAt(path, offset);
                    if (t.isPresent()) {
                        named++;
                        nav.definition(t.get().symbol())
                                .ifPresent(sp -> s.registry().resolve(sp));
                        nav.references(t.get(), true).forEach(sp -> s.registry().resolve(sp));
                    }
                    nav.hover(path, offset);
                } catch (RuntimeException | StackOverflowError e) {
                    failures++;
                    firstFailures.putIfAbsent(path + "@" + offset, e.toString());
                }
                times.add(System.nanoTime() - q0);
            }
        }
        long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf(
                "%s: %d files, loaded in %d ms; %d queries, %.0f%% name a symbol, p50 %.2f ms, p99 %.2f ms, max %.1f ms;"
                        + " %d failures%n",
                root,
                s.paths().size(),
                loaded / 1_000_000,
                queries,
                queries == 0 ? 0.0 : 100.0 * named / queries,
                percentile(sorted, 0.50),
                percentile(sorted, 0.99),
                sorted.length == 0 ? 0.0 : sorted[sorted.length - 1] / 1e6,
                failures);
        firstFailures.entrySet().stream()
                .limit(10)
                .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));
        return failures;
    }

    private static double percentile(long[] sorted, double p) {
        return sorted.length == 0
                ? 0.0
                : sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))] / 1e6;
    }
}
