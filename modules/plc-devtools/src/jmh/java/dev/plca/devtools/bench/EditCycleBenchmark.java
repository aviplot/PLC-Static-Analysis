package dev.plca.devtools.bench;

import dev.plca.analysis.Snapshot;
import dev.plca.analysis.Workspace;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The language server's edit cycle (M8): one keystroke in one POU file, then what each tier does. {@code fastTier}:
 * re-parse the file, rebuild its model group, POU rules of that file (target: well under 200 ms on the generated 200-FB
 * project). {@code projectTier}: every rule on the new snapshot, as after a pause in typing. Single-threaded, like the
 * fast tier in the server. {@code functionBlocks} scales the generated project (about 120 lines per FB);
 * {@code -Pjmh.corpus=<dir>} uses a real project instead. The edited file is the median-size analysed file.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class EditCycleBenchmark {

    @Param({"200", "2000"})
    public int functionBlocks;

    private static final RuleRegistry RULES = RuleRegistry.standard();
    private final RuleConfig config = RuleConfig.defaults();
    private Path dir;
    private Workspace workspace;
    private String path;
    private String text;
    private int edits;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        String corpus = System.getProperty("plca.bench.corpus");
        Path root;
        if (corpus != null) {
            root = Path.of(corpus);
        } else {
            dir = Files.createTempDirectory("plca-edit-bench");
            for (Map.Entry<String, String> e :
                    SyntheticProject.generate(functionBlocks, 42).entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue());
            }
            root = dir;
        }
        workspace = new Workspace(root);
        workspace.loadAll(Runtime.getRuntime().availableProcessors());
        Snapshot s = workspace.snapshot();
        // a typical POU file: the median-size file with a unit (real projects have generated multi-MB files too)
        List<String> sized = s.paths().stream()
                .filter(p -> s.file(p).orElseThrow().unit() != null)
                .filter(p -> corpus != null || p.startsWith("FB_"))
                .sorted(Comparator.comparingInt(
                        p -> s.file(p).orElseThrow().file().text().length()))
                .toList();
        path = sized.get(sized.size() / 2);
        text = s.file(path).orElseThrow().file().text();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (dir != null) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** A different one-character edit each time (a trailing space or none), so nothing can be cached. */
    private Snapshot keystroke() {
        edits++;
        workspace.update(path, (edits & 1) == 0 ? text : text + " ");
        return workspace.snapshot();
    }

    @Benchmark
    public Object fastTier() {
        Snapshot s = keystroke();
        return s.analyze(RULES, config, null, Set.of(path), 1, () -> false);
    }

    @Benchmark
    public Object projectTier() {
        Snapshot s = keystroke();
        return s.analyze(RULES, config, null, null, 1, () -> false);
    }
}
