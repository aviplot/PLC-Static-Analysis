package dev.plca.devtools.bench;

import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.kit.Frontends;
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
 * Single-thread SCL parse throughput, two-stage vs LL-only, like {@link ParseBenchmark}. Input: a generated TIA-style
 * project, or {@code -Pjmh.corpus=<dir>} for real files (its {@code .scl/.db/.udt}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SclParseBenchmark {

    private static final Frontends FRONTENDS = Frontends.of(SclFrontend::new);

    @Param({"true", "false"})
    public boolean twoStage;

    private List<SourceFile> files;
    private SclFrontend frontend;

    @Setup(Level.Trial)
    public void setup() {
        files = inputs();
        frontend = new SclFrontend(twoStage);
        long lines = files.stream().mapToLong(f -> f.lineIndex().lineCount()).sum();
        if (System.getProperty("plca.bench.corpus") == null) {
            // the generated project must take the fast path, or the benchmark measures error recovery
            DiagnosticCollector diags = new DiagnosticCollector();
            int fallbacks = 0;
            SourceRegistry registry = new SourceRegistry();
            for (SourceFile f : files) {
                fallbacks += new SclFrontend().parse(f, registry, diags).stats().sllFallbacks();
            }
            if (diags.hasErrors() || fallbacks > 0) {
                String first = diags.diagnostics().isEmpty()
                        ? "no diagnostics"
                        : diags.diagnostics().getFirst().toString();
                throw new IllegalStateException(
                        "generated SCL is not clean: " + first + ", " + fallbacks + " SLL fallbacks");
            }
        }
        System.out.printf("%n[SclParseBenchmark] %d files, %d lines, twoStage=%s%n", files.size(), lines, twoStage);
    }

    static List<SourceFile> inputs() {
        String corpus = System.getProperty("plca.bench.corpus");
        List<SourceFile> out = new ArrayList<>();
        if (corpus != null) {
            Path root = Path.of(corpus);
            for (Path p : ProjectLoader.sources(root, FRONTENDS)) {
                try {
                    out.add(SourceFile.fromBytes(root.relativize(p).toString(), Files.readAllBytes(p)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return out;
        }
        for (Map.Entry<String, String> e : SyntheticSclProject.generate(200, 42).entrySet()) {
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
