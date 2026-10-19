package dev.plca.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.plcopen.PlcOpenFrontend;
import dev.plca.frontend.scl.SclFrontend;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontend;
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
        check(new StFrontend(), "fuzz.st", input);
    }

    /** The same on Siemens SCL (seeds: the SCL rule fixtures and frontend goldens). */
    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/scl.dict")
    void fuzzSclRules(byte[] input) {
        check(new SclFrontend(), "fuzz.scl", input);
    }

    /** The same on Rockwell L5X exports (seeds: the Rockwell rule fixtures). */
    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/l5x.dict")
    void fuzzL5xRules(byte[] input) {
        check(new L5xFrontend(), "fuzz.L5X", input);
    }

    /** The same on PLCopen XML (seeds: the PLCopen rule fixtures). */
    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/plcopen.dict")
    void fuzzPlcOpenRules(byte[] input) {
        check(new PlcOpenFrontend(), "fuzz.xml", input);
    }

    private static void check(Frontend frontend, String path, byte[] input) {
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        SourceUnit unit = frontend.parse(SourceFile.fromBytes(path, input), registry, diags)
                .unit();
        SemanticModel m = SemanticBuilder.build(List.of(unit), diags);
        RuleEngine.Result r = ENGINE.run(m, registry, RuleConfig.only("all"), 1);
        // rule failures are bugs; other engine diagnostics (a suppression naming no rule) are valid output
        assertThat(r.diagnostics()).noneMatch(d -> d.code().equals(RuleEngine.INTERNAL));
        r.diagnostics().forEach(d -> registry.resolve(d.span()));
        r.suppressed().forEach(s -> registry.resolve(s.finding().span()));
        for (Finding f : r.findings()) {
            registry.resolve(f.span());
            f.related().forEach(rel -> registry.resolve(rel.span()));
        }
    }
}
