package dev.plca.frontend.plcopen;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.kit.Frontend;

/**
 * Jazzer fuzz target for the PLCopen frontend (XML reading, graph lowering, SFC). In a normal {@code ./gradlew test}
 * run it replays the seeds under {@code src/test/resources/dev/plca/frontend/plcopen/PlcOpenFrontendFuzzTestInputs/}.
 * To fuzz: {@code JAZZER_FUZZ=1 ./gradlew :frontend-plcopen:test --tests '*PlcOpenFrontendFuzzTest*'}.
 *
 * <p>Invariants: nothing escapes the frontend (design rule 2), and every diagnostic span resolves inside the file.
 */
class PlcOpenFrontendFuzzTest {

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/plcopen.dict")
    void fuzzPlcOpen(byte[] input) {
        SourceFile file = SourceFile.fromBytes("fuzz.xml", input);
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        Frontend.Result result = new PlcOpenFrontend().parse(file, registry, diags);
        assertThat(result.unit()).isNotNull();
        int lines = file.lineIndex().lineCount();
        for (Diagnostic d : diags.diagnostics()) {
            var loc = registry.resolve(d.span());
            assertThat(loc.startLine()).isBetween(1, lines);
            assertThat(loc.endLine()).isBetween(loc.startLine(), lines);
        }
    }
}
