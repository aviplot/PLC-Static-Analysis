package dev.plca.frontend.l5x;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.AstPrinter;
import dev.plca.ast.Declaration;
import dev.plca.ast.Statement;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.RockwellStParser.StatementsOnlyContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Rockwell ST and CODESYS builders duplicate the glue for the shared core rules (ANTLR generates separate context
 * classes per grammar). Core statements written the same way must give the same AST in both, so the copies cannot
 * drift. Fixtures in {@code src/test/resources/conformance/} use only constructs both dialects share.
 */
class RockwellStConformanceTest {

    private static final Path DIR = Path.of(System.getProperty("golden.root"), "conformance");

    static Stream<String> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void sameStatementsAsCodesys(String fixture) throws IOException {
        String text = Files.readString(DIR.resolve(fixture));

        SourceRegistry cr = new SourceRegistry();
        DiagnosticCollector cd = new DiagnosticCollector();
        Declaration.Pou snippet = (Declaration.Pou) new StFrontend()
                .parse(SourceFile.of(fixture, text), cr, cd)
                .unit()
                .declarations()
                .getFirst();

        SourceRegistry rr = new SourceRegistry();
        DiagnosticCollector rd = new DiagnosticCollector();
        SourceText rt = rr.register(SourceFile.of(fixture, text));
        Parsed p = new RockwellStParserDriver(true).parse(rt, rd);
        List<Statement> rockwell =
                new RockwellStAstBuilder(rt, p.tokens(), rd).statementsOnly((StatementsOnlyContext) p.tree());

        assertThat(dump(rockwell, rr)).isEqualTo(dump(snippet.body(), cr));
        assertThat(cd.diagnostics()).isEmpty();
        assertThat(rd.diagnostics()).isEmpty();
    }

    private static String dump(List<Statement> body, SourceRegistry registry) {
        StringBuilder sb = new StringBuilder();
        for (Statement s : body) {
            sb.append(AstPrinter.print(s, registry::resolve));
        }
        return sb.toString();
    }
}
