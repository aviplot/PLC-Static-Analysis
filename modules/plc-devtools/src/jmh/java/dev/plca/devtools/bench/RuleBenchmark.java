package dev.plca.devtools.bench;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleEngine;
import dev.plca.rules.RuleRegistry;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.util.ArrayList;
import java.util.List;
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
 * All rules on the generated 200-FB project (or {@code -Pjmh.corpus}), single-threaded, model built once in setup.
 * Compare with {@link SemanticBenchmark} and {@link ParseBenchmark}: rules should stay well below parse + model time.
 * {@code -Pjmh.args="RuleBenchmark -p rule=PLCA-Q001,PLCA-S001"} measures single rules.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RuleBenchmark {

    /** {@code all}, or one rule id to see what each rule costs. */
    @Param({"all"})
    public String rule;

    private final RuleEngine engine = new RuleEngine(RuleRegistry.standard());
    private RuleConfig config;
    private SourceRegistry registry;
    private SemanticModel model;

    @Setup(Level.Trial)
    public void setup() {
        StFrontend fe = new StFrontend();
        registry = new SourceRegistry();
        List<SourceUnit> units = new ArrayList<>();
        for (SourceFile f : ParseBenchmark.inputs()) {
            units.add(fe.parse(f, registry, DiagnosticSink.discard()).unit());
        }
        model = SemanticBuilder.build(units, DiagnosticSink.discard());
        config = RuleConfig.only(rule);
    }

    @Benchmark
    public Object allRules() {
        return engine.run(model, registry, config, 1);
    }
}
