package dev.plca.devtools.bench;

import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Single-thread parse throughput, two-stage (SLL first, LL on failure) vs LL-only (design rule 1). One operation parses
 * the whole input set; divide its line count (printed at setup) by the time per operation for lines/s.
 *
 * <p>Input: a generated project, or {@code -Pjmh.corpus=<dir>} for real files.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ParseBenchmark {

    @Param({"true", "false"})
    public boolean twoStage;

    private List<SourceFile> files;
    private StFrontend frontend;

    @Setup(Level.Trial)
    public void setup() {
        files = inputs();
        frontend = new StFrontend(twoStage);
        long lines = files.stream().mapToLong(f -> f.lineIndex().lineCount()).sum();
        System.out.printf("%n[ParseBenchmark] %d files, %d lines, twoStage=%s%n", files.size(), lines, twoStage);
    }

    static List<SourceFile> inputs() {
        String corpus = System.getProperty("plca.bench.corpus");
        List<SourceFile> out = new ArrayList<>();
        if (corpus != null) {
            Path root = Path.of(corpus);
            for (Path p : ProjectLoader.sources(root)) {
                try {
                    out.add(SourceFile.fromBytes(root.relativize(p).toString(), Files.readAllBytes(p)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return out;
        }
        for (Map.Entry<String, String> e : SyntheticProject.generate(200, 42).entrySet()) {
            out.add(SourceFile.of(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Benchmark
    public void parseAll(Blackhole bh) {
        SourceRegistry registry = new SourceRegistry();
        for (SourceFile f : files) {
            bh.consume(frontend.parse(f, registry, DiagnosticSink.discard()));
        }
    }
}
