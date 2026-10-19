package dev.plca.frontend.st;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.kit.Frontend;

/**
 * Jazzer fuzz targets. In a normal {@code ./gradlew test} run they replay the seed corpus under
 * {@code src/test/resources/dev/plca/frontend/st/StFrontendFuzzTestInputs/<method>/} (regression mode). To fuzz:
 * {@code JAZZER_FUZZ=1 ./gradlew :frontend-st:test --tests '*StFrontendFuzzTest*'}; crashes are written next to the
 * seeds and then replay as regression tests.
 *
 * <p>Invariants: nothing escapes {@link StFrontend#parse} (design rule 2) and every diagnostic span resolves to a
 * position inside the file.
 */
class StFrontendFuzzTest {

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/st.dict")
    void fuzzStText(byte[] input) {
        check("fuzz.st", input);
    }

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/st.dict")
    void fuzzTwinCat(byte[] input) {
        check("fuzz.TcPOU", input);
    }

    private static void check(String path, byte[] input) {
        SourceFile file = SourceFile.fromBytes(path, input);
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        Frontend.Result result = new StFrontend().parse(file, registry, diags);
        assertThat(result.unit()).isNotNull();
        int lines = file.lineIndex().lineCount();
        for (Diagnostic d : diags.diagnostics()) {
            var loc = registry.resolve(d.span());
            assertThat(loc.startLine()).isBetween(1, lines);
            assertThat(loc.endLine()).isBetween(loc.startLine(), lines);
        }
    }
}
