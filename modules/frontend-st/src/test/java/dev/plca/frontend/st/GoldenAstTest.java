package dev.plca.frontend.st;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.AstPrinter;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.testing.Golden;
import dev.plca.frontend.st.kit.Frontend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden (snapshot) tests: every fixture in {@code src/test/resources/golden/} is parsed and its AST plus diagnostics
 * compared with {@code <fixture>.ast}. Update with {@code ./gradlew :frontend-st:test -Pgolden.update} and review the
 * diff.
 */
class GoldenAstTest {

    private static final Path DIR = Path.of(System.getProperty("golden.root"), "golden");

    static Stream<String> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.endsWith(".ast"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void matchesGolden(String fixture) throws IOException {
        SourceFile file = SourceFile.fromBytes(fixture, Files.readAllBytes(DIR.resolve(fixture)));
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        Frontend.Result result = new StFrontend().parse(file, registry, diags);

        StringBuilder out = new StringBuilder(AstPrinter.print(result.unit(), registry::resolve));
        out.append("diagnostics:\n");
        for (Diagnostic d : diags.diagnostics()) {
            Location l = registry.resolve(d.span());
            out.append("  ")
                    .append(d.severity())
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
        Golden.assertMatches(out.toString(), "golden/" + fixture + ".ast");

        // well-formed fixtures must take the fast path: no SLL fallback
        if (!fixture.startsWith("error_")) {
            assertThat(diags.hasErrors()).as("errors in %s", fixture).isFalse();
            assertThat(result.stats().sllFallbacks())
                    .as("SLL fallbacks in %s", fixture)
                    .isZero();
        }
    }
}
