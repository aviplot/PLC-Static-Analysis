package dev.plca.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.core.Dialect;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Inline and configured suppressions, per-dialect settings and the default rule set. */
class SuppressionsTest {

    private static List<String> lines(RuleTesting.Analysis a, List<Finding> findings) {
        return findings.stream()
                .map(f -> {
                    Location l = a.sources().resolve(f.span());
                    return l.startLine() + " " + f.ruleId();
                })
                .toList();
    }

    @Test
    void commentDirectives() {
        RuleTesting.Analysis a = RuleTesting.analyze("""
                PROGRAM P
                VAR
                    r : REAL;
                    b : BOOL;
                END_VAR
                // plca-disable-next-line PLCA-Q003 checked against a sentinel
                b := r = 1.0;
                b := r = 2.0; // plca-disable-line PLCA-Q003
                b := r = 3.0; (* plca-disable-line PLCA-Q001 other rule: still reported *)
                (* plca-disable quality *)
                b := r = 4.0;
                (* plca-enable quality *)
                b := r = 5.0;
                b := r = 6.0; // plca-disable-line
                END_PROGRAM
                """);
        RuleEngine.Result r = a.run(RuleConfig.only("PLCA-Q003"), 1);
        assertThat(lines(a, r.findings())).containsExactly("9 PLCA-Q003", "13 PLCA-Q003", "14 PLCA-Q003");
        assertThat(r.suppressed()).hasSize(3).allMatch(s -> s.kind().equals("inline"));
        assertThat(r.suppressed().getFirst().reason()).isEqualTo("checked against a sentinel");
        assertThat(r.diagnostics()).extracting(Diagnostic::code).containsExactly(Suppressions.BAD_DIRECTIVE);
    }

    @Test
    void logicRulesByIdAndCategory() {
        RuleTesting.Analysis a = RuleTesting.analyze("""
                PROGRAM P
                VAR n : INT; b : BOOL; END_VAR
                b := n > 5 AND n < 3; // plca-disable-line PLCA-L004 kept for the HMI test
                b := n > 6 AND n < 3; // plca-disable-line logic
                b := n > 7 AND n < 3;
                END_PROGRAM
                """);
        RuleEngine.Result r = a.run(RuleConfig.only("logic"), 1);
        assertThat(lines(a, r.findings())).containsExactly("5 PLCA-L004");
        assertThat(r.suppressed()).hasSize(2);
        assertThat(r.diagnostics()).isEmpty();
    }

    @Test
    void regionWithoutEnableRunsToTheEndOfTheFile() {
        RuleTesting.Analysis a = RuleTesting.analyze("""
                PROGRAM P
                VAR r : REAL; b : BOOL; END_VAR
                b := r = 1.0;
                // plca-disable PLCA-Q003
                b := r = 2.0;
                b := r = 3.0;
                END_PROGRAM
                """);
        RuleEngine.Result r = a.run(RuleConfig.only("PLCA-Q003"), 1);
        assertThat(lines(a, r.findings())).containsExactly("3 PLCA-Q003");
        assertThat(r.suppressed()).hasSize(2);
    }

    @Test
    void attributesOnPousAndVariables() {
        RuleTesting.Analysis a = RuleTesting.analyze("""
                {attribute 'plca-disable' := 'PLCA-Q003'}
                FUNCTION_BLOCK FB_Legacy
                VAR r : REAL; b : BOOL; END_VAR
                b := r = 1.0;
                END_FUNCTION_BLOCK

                FUNCTION_BLOCK FB_New
                VAR
                    {attribute 'plca-disable' := 'PLCA-Q001'}
                    nOld : INT;
                    nCount : INT;
                    r : REAL;
                    b : BOOL;
                END_VAR
                b := r = 1.0;
                END_FUNCTION_BLOCK
                """);
        RuleEngine.Result r = a.run(RuleConfig.only("PLCA-Q001", "PLCA-Q003"), 1);
        assertThat(lines(a, r.findings())).containsExactly("11 PLCA-Q001", "15 PLCA-Q003");
        assertThat(r.suppressed()).hasSize(2);
    }

    @Test
    void configuredSuppressionsByPathAndPou() {
        RuleTesting.Analysis a = RuleTesting.analyze("""
                FUNCTION_BLOCK FB_Legacy
                VAR r : REAL; b : BOOL; END_VAR
                b := r = 1.0;
                END_FUNCTION_BLOCK
                PROGRAM P
                VAR r : REAL; b : BOOL; END_VAR
                b := r = 1.0;
                END_PROGRAM
                """);
        RuleConfig byPou = new RuleConfig(
                List.of("PLCA-Q003"),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(new RuleConfig.Suppression("quality", null, "fb_legacy*", "vendor code")));
        RuleEngine.Result r = a.run(byPou, 1);
        assertThat(lines(a, r.findings())).containsExactly("7 PLCA-Q003");
        assertThat(r.suppressed()).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo("config");
            assertThat(s.reason()).isEqualTo("vendor code");
        });
        RuleConfig byPath = new RuleConfig(
                List.of("PLCA-Q003"),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(new RuleConfig.Suppression("PLCA-Q003", "t*.st", null, null)));
        assertThat(a.run(byPath, 1).findings()).isEmpty();
    }

    @Test
    void rockwellRungCommentSuppressesItsRung() {
        Path dir = Path.of(System.getProperty("golden.root")).resolve("dev/suppress/rockwell");
        RuleTesting.Analysis a = RuleTesting.load(dir);
        RuleEngine.Result r = a.run(RuleConfig.only("PLCA-P001"), 1);
        assertThat(r.findings()).extracting(Finding::place).containsExactly("Program:MainProgram/Routine:Main/Rung:3");
        assertThat(r.suppressed())
                .singleElement()
                .satisfies(s -> assertThat(s.finding().place()).isEqualTo("Program:MainProgram/Routine:Main/Rung:2"));
    }

    @Test
    void perDialectSeverityAndSelection() {
        Path dir = Path.of(System.getProperty("golden.root")).resolve("PLCA-Q003/rockwell");
        RuleTesting.Analysis a = RuleTesting.load(dir);
        // shipped default: ladder REAL equality is info
        assertThat(a.run(RuleConfig.only("PLCA-Q003"), 1).findings())
                .isNotEmpty()
                .allMatch(f -> f.severity() == Severity.INFO);
        RuleConfig raised = new RuleConfig(
                List.of("PLCA-Q003"),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(
                        Dialect.ROCKWELL,
                        new RuleConfig.DialectConfig(
                                List.of(), List.of(), Map.of(), Map.of("PLCA-Q003", Severity.ERROR))),
                List.of());
        assertThat(a.run(raised, 1).findings()).allMatch(f -> f.severity() == Severity.ERROR);
        RuleConfig off = new RuleConfig(
                List.of("PLCA-Q003"),
                List.of(),
                Map.of(),
                Map.of("PLCA-Q003", Severity.ERROR),
                Map.of(
                        Dialect.ROCKWELL,
                        new RuleConfig.DialectConfig(List.of(), List.of("quality"), Map.of(), Map.of())),
                List.of());
        assertThat(a.run(off, 1).findings()).isEmpty();
        // the global override wins over the shipped dialect default
        RuleConfig global = new RuleConfig(
                List.of("PLCA-Q003"), List.of(), Map.of(), Map.of("PLCA-Q003", Severity.ERROR), Map.of(), List.of());
        assertThat(a.run(global, 1).findings()).allMatch(f -> f.severity() == Severity.ERROR);
    }

    @Test
    void defaultSetLeavesOutOptInRules() {
        RuleRegistry reg = RuleTesting.REGISTRY;
        assertThat(RuleConfig.defaults().resolve(reg))
                .allMatch(id -> reg.descriptor(id).orElseThrow().enabledByDefault());
        assertThat(RuleConfig.only("all").resolve(reg))
                .hasSize(reg.descriptors().size());
    }
}
