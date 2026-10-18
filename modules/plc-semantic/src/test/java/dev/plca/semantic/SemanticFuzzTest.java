package dev.plca.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import java.util.List;

/**
 * Fuzzes parse + model build on arbitrary text (seeds: the test project). Invariant: the builder never throws and never
 * reports an internal error; all queries work on the result. {@code JAZZER_FUZZ=1} to fuzz.
 */
class SemanticFuzzTest {

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/st.dict")
    void fuzzModel(byte[] input) {
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        SourceUnit unit = new StFrontend()
                .parse(SourceFile.fromBytes("fuzz.st", input), registry, diags)
                .unit();
        DiagnosticCollector semantic = new DiagnosticCollector();
        SemanticModel m = SemanticBuilder.build(List.of(unit), semantic);
        assertThat(semantic.diagnostics()).extracting(Diagnostic::code).doesNotContain(SemanticBuilder.INTERNAL);
        for (PouSymbol p : m.pous()) {
            Statements.forEachDeep(p.declaration().body(), s -> m.deepEffects(s));
        }
        m.callGraph().cycles();
        m.instanceGraph().all();
    }
}
