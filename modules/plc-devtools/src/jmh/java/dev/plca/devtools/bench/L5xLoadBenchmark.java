package dev.plca.devtools.bench;

import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.st.kit.Frontend;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Whole L5X frontend, single thread: StAX reading, rung and ST parsing (two-stage vs LL-only), AST building and ladder
 * lowering. Input: a generated controller (100 programs × 10 routines × 100 rungs = 100k rungs), or
 * {@code -Pjmh.corpus=<file.L5X>} for a real export. Setup prints the heap the parsed unit retains.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class L5xLoadBenchmark {

    @Param({"true", "false"})
    public boolean twoStage;

    private SourceFile file;
    private L5xFrontend frontend;

    @Setup(Level.Trial)
    public void setup() {
        file = input();
        frontend = new L5xFrontend(twoStage);
        DiagnosticCollector diags = new DiagnosticCollector();
        long before = usedHeap();
        SourceRegistry registry = new SourceRegistry();
        Frontend.Result r = new L5xFrontend().parse(file, registry, diags);
        long retained = usedHeap() - before;
        if (System.getProperty("plca.bench.corpus") == null
                && (diags.hasErrors() || r.stats().sllFallbacks() > 0)) {
            throw new IllegalStateException("generated L5X is not clean: " + diags.diagnostics());
        }
        System.out.printf(
                "%n[L5xLoadBenchmark] %s: %,d chars, %,d lines, %d texts; unit + registry retain ~%,d MB (text %,d MB)%n",
                file.path(),
                file.text().length(),
                file.lineIndex().lineCount(),
                r.stats().texts(),
                retained >> 20,
                (2L * file.text().length()) >> 20);
        if (r.unit() == null || registry.size() == 0) {
            throw new IllegalStateException("nothing parsed");
        }
    }

    static SourceFile input() {
        String corpus = System.getProperty("plca.bench.corpus");
        if (corpus != null && corpus.toLowerCase(java.util.Locale.ROOT).endsWith(".l5x")) {
            Path p = Path.of(corpus);
            try {
                return SourceFile.fromBytes(p.getFileName().toString(), Files.readAllBytes(p));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return SourceFile.of("Bench.L5X", SyntheticL5x.generate(100, 10, 100, 42));
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    @Benchmark
    public Object load() {
        return frontend.parse(file, new SourceRegistry(), DiagnosticSink.discard());
    }
}
