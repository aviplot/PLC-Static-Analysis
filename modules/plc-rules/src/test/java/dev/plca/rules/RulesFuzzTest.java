package dev.plca.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.util.List;

/**
 * Fuzzes parse + model + all rules on arbitrary text (seeds: the rule fixtures). Invariants: no rule fails
 * ({@link RuleEngine#INTERNAL}) and every finding resolves to a file location. {@code JAZZER_FUZZ=1} to fuzz.
 */
class RulesFuzzTest {

    private static final RuleEngine ENGINE = new RuleEngine(RuleRegistry.standard());

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/st.dict")
    void fuzzRules(byte[] input) {
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        SourceUnit unit = new StFrontend()
                .parse(SourceFile.fromBytes("fuzz.st", input), registry, diags)
                .unit();
        SemanticModel m = SemanticBuilder.build(List.of(unit), diags);
        RuleEngine.Result r = ENGINE.run(m, registry, RuleConfig.defaults(), 1);
        assertThat(r.diagnostics()).isEmpty();
        for (Finding f : r.findings()) {
            registry.resolve(f.span());
            f.related().forEach(rel -> registry.resolve(rel.span()));
        }
    }
}
