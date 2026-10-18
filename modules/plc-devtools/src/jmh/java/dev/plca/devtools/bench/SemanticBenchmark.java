package dev.plca.devtools.bench;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import dev.plca.semantic.SemanticBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Semantic model build time for the generated 200-FB project (or {@code -Pjmh.corpus}), ASTs parsed once in setup. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SemanticBenchmark {

    private List<SourceUnit> units;

    @Setup(Level.Trial)
    public void setup() {
        StFrontend fe = new StFrontend();
        SourceRegistry registry = new SourceRegistry();
        units = new ArrayList<>();
        for (SourceFile f : ParseBenchmark.inputs()) {
            units.add(fe.parse(f, registry, DiagnosticSink.discard()).unit());
        }
    }

    @Benchmark
    public Object buildModel() {
        return SemanticBuilder.build(units, DiagnosticSink.discard());
    }
}
