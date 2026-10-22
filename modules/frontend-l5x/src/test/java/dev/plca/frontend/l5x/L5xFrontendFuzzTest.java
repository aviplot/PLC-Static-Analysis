package dev.plca.frontend.l5x;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryFile;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.plca.ast.Ladder;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.frontend.st.kit.Frontend;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.RungParser.RungContext;
import dev.plca.grammar.RungParser.RungsContext;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Jazzer fuzz targets for the L5X frontend: whole exports ({@code fuzzL5x}, {@code fuzzL5k}) and raw rung text through
 * parsing and lowering ({@code fuzzRungs}). In a normal {@code ./gradlew test} run they replay the seeds under
 * {@code src/test/resources/dev/plca/frontend/l5x/L5xFrontendFuzzTestInputs/}. To fuzz: {@code JAZZER_FUZZ=1 ./gradlew
 * :frontend-l5x:test --tests '*L5xFrontendFuzzTest*'}.
 *
 * <p>Invariants: nothing escapes the frontend (design rule 2), and every diagnostic span resolves inside the file.
 */
class L5xFrontendFuzzTest {

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/l5x.dict")
    void fuzzL5x(byte[] input) {
        SourceFile file = SourceFile.fromBytes("fuzz.L5X", input);
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        Frontend.Result result = new L5xFrontend().parse(file, registry, diags);
        assertThat(result.unit()).isNotNull();
        checkSpans(file, registry, diags);
    }

    /** L5K text exports (M11): the hand-written block scanner, then the shared pipeline. */
    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/l5x.dict")
    void fuzzL5k(byte[] input) {
        SourceFile file = SourceFile.fromBytes("fuzz.L5K", input);
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        Frontend.Result result = new L5xFrontend().parse(file, registry, diags);
        assertThat(result.unit()).isNotNull();
        checkSpans(file, registry, diags);
    }

    @FuzzTest(maxDuration = "5m")
    @DictionaryFile(resourcePath = "/l5x.dict")
    void fuzzRungs(byte[] input) {
        SourceFile file = SourceFile.of("fuzz.rungs", new String(input, StandardCharsets.UTF_8));
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        SourceText text = registry.register(file);
        Parsed p = new RungParserDriver(true).parse(text, diags);
        RungAstBuilder b = new RungAstBuilder(text, p.tokens(), diags);
        RungLowering lowering = new RungLowering(Map.of());
        for (RungContext rc : ((RungsContext) p.tree()).rung()) {
            Ladder l = b.rung(rc);
            assertThat(lowering.lower(l)).isNotNull();
            assertThat(l.text()).endsWith(";");
        }
        checkSpans(file, registry, diags);
    }

    private static void checkSpans(SourceFile file, SourceRegistry registry, DiagnosticCollector diags) {
        int lines = file.lineIndex().lineCount();
        for (Diagnostic d : diags.diagnostics()) {
            var loc = registry.resolve(d.span());
            assertThat(loc.startLine()).isBetween(1, lines);
            assertThat(loc.endLine()).isBetween(loc.startLine(), lines);
        }
    }
}
