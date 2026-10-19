package dev.plca.frontend.scl;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.kit.Frontend;

/**
 * Jazzer fuzz target for the SCL frontend. In a normal {@code ./gradlew test} run it replays the seed corpus under
 * {@code src/test/resources/dev/plca/frontend/scl/SclFrontendFuzzTestInputs/fuzzScl/} (regression mode; seeds are the
 * golden fixtures). To fuzz: {@code JAZZER_FUZZ=1 ./gradlew :frontend-scl:test --tests '*SclFrontendFuzzTest*'};
 * crashes are written next to the seeds and then replay as regression tests.
 *
 * <p>Invariants: nothing escapes {@link SclFrontend#parse} (design rule 2) and every diagnostic span resolves to a
 * position inside the file.
 */
class SclFrontendFuzzTest {

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/scl.dict")
    void fuzzScl(byte[] input) {
        SourceFile file = SourceFile.fromBytes("fuzz.scl", input);
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        Frontend.Result result = new SclFrontend().parse(file, registry, diags);
        assertThat(result.unit()).isNotNull();
        int lines = file.lineIndex().lineCount();
        for (Diagnostic d : diags.diagnostics()) {
            var loc = registry.resolve(d.span());
            assertThat(loc.startLine()).isBetween(1, lines);
            assertThat(loc.endLine()).isBetween(loc.startLine(), lines);
        }
    }
}
