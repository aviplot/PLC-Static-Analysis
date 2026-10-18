package dev.plca.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.plca.core.diag.Diagnostic;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private static final String CODE = """
            PROGRAM P
            VAR
                r : REAL;
                n : INT;
                unused : INT;
            END_VAR
            IF r = 1.0 THEN
                n := n / 0;
            END_IF
            CASE n OF 1: r := 0.0; END_CASE
            END_PROGRAM
            """;

    @Test
    void parallelAndSequentialRunsAgree() {
        Path plant = Path.of(System.getProperty("golden.root"))
                .getParent()
                .getParent()
                .getParent()
                .getParent()
                .resolve("plc-semantic/src/test/resources/projects/plant");
        RuleTesting.Analysis a = RuleTesting.load(plant);
        RuleEngine.Result seq = a.run(RuleConfig.defaults(), 1);
        RuleEngine.Result par = a.run(RuleConfig.defaults(), 8);
        assertThat(RuleTesting.print(a.sources(), par.findings()))
                .isEqualTo(RuleTesting.print(a.sources(), seq.findings()));
        assertThat(seq.nanosPerRule())
                .containsOnlyKeys(RuleTesting.REGISTRY.descriptors().stream()
                        .map(RuleDescriptor::id)
                        .toList());
    }

    @Test
    void selectsByIdCategoryAndDisable() {
        RuleTesting.Analysis a = RuleTesting.analyze(CODE);
        assertThat(a.run(RuleConfig.only("plca-q003"), 1).findings())
                .extracting(Finding::ruleId)
                .containsExactly("PLCA-Q003");
        assertThat(a.run(new RuleConfig(List.of("quality"), List.of("PLCA-Q001"), Map.of()), 1)
                        .findings())
                .extracting(Finding::ruleId)
                .containsExactly("PLCA-Q003", "PLCA-Q004", "PLCA-Q002");
        assertThat(a.run(RuleConfig.only("security"), 1).findings()).isEmpty();
    }

    @Test
    void rejectsUnknownRulesAndParameters() {
        RuleTesting.Analysis a = RuleTesting.analyze(CODE);
        assertThatThrownBy(() -> a.run(RuleConfig.only("PLCA-X999"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLCA-X999");
        assertThatThrownBy(() ->
                        a.run(RuleConfig.defaults().withParameters(Map.of("PLCA-Q001", Map.of("nope", "1"))), 1))
                .hasMessageContaining("no parameter nope");
        assertThatThrownBy(() -> a.run(
                        RuleConfig.defaults().withParameters(Map.of("PLCA-Q001", Map.of("includeGlobals", "yes"))), 1))
                .hasMessageContaining("expected true or false");
    }

    @Test
    void failingRuleBecomesDiagnosticAndRunContinues() {
        PouRule broken = new PouRule() {
            @Override
            public String id() {
                return "PLCA-Q003";
            }

            @Override
            public void check(PouContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        RuleRegistry registry = new RuleRegistry(List.of(broken, new dev.plca.rules.quality.DivisionByZero()));
        RuleTesting.Analysis a = RuleTesting.analyze(CODE);
        RuleEngine.Result r = new RuleEngine(registry).run(a.model(), a.sources(), RuleConfig.defaults(), 1);
        assertThat(r.findings()).extracting(Finding::ruleId).containsExactly("PLCA-Q004");
        assertThat(r.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(RuleEngine.INTERNAL);
            assertThat(d.message()).contains("PLCA-Q003", "POU P", "boom");
        });
        assertThat(r.diagnostics()).extracting(Diagnostic::span).doesNotContainNull();
    }
}
