package dev.plca.frontend.scl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.AstPrinter;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The SCL and CODESYS builders duplicate the glue for the shared core rules (ANTLR generates separate context classes
 * per grammar). This test keeps the copies equivalent: core ST written the same way in both dialects must give the same
 * AST and diagnostics, apart from the dialect and body-language tags.
 *
 * <p>Fixtures in {@code src/test/resources/conformance/} use only constructs both dialects share — no {@code "…"}, no
 * nested comments, no names one dialect reserves.
 */
class SclConformanceTest {

    private static final Path DIR = Path.of(System.getProperty("golden.root"), "conformance");

    static Stream<String> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void sameAstAsCodesys(String fixture) throws IOException {
        byte[] bytes = Files.readAllBytes(DIR.resolve(fixture));
        String codesys = dump(new StFrontend(), fixture, bytes);
        String scl = dump(new SclFrontend(), fixture, bytes);

        assertThat(normalise(scl)).isEqualTo(normalise(codesys));
        assertThat(codesys).as("fixture %s must parse cleanly", fixture).doesNotContain("ERROR ");
    }

    private static String dump(Frontend frontend, String name, byte[] bytes) {
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        Frontend.Result r = frontend.parse(SourceFile.fromBytes(name, bytes), registry, diags);
        StringBuilder out = new StringBuilder(AstPrinter.print(r.unit(), registry::resolve));
        for (Diagnostic d : diags.diagnostics()) {
            Location l = registry.resolve(d.span());
            out.append(d.severity())
                    .append(' ')
                    .append(d.code())
                    .append(" @")
                    .append(l.startLine())
                    .append(':')
                    .append(l.startColumn())
                    .append(' ')
                    .append(d.message())
                    .append('\n');
        }
        return out.toString();
    }

    private static String normalise(String dump) {
        return dump.replaceFirst("dialect=\\w+", "dialect=*").replace("bodyLanguage=\"SCL\"", "bodyLanguage=\"ST\"");
    }
}
