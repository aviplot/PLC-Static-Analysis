package dev.plca.frontend.plcopen;

import dev.plca.ast.Declaration;
import dev.plca.ast.StText;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.testing.Golden;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lowering goldens: every POU body of the fixtures in {@code src/test/resources/golden/} and {@code lowering/}, printed
 * as ST ({@code StText}) in {@code lowering/<fixture>.lowered} — the readable check of LD/FBD/SFC lowering.
 */
class PlcOpenLoweringGoldenTest {

    private static final Path ROOT = Path.of(System.getProperty("golden.root"));

    static Stream<String> fixtures() throws IOException {
        try (Stream<Path> a = Files.list(ROOT.resolve("golden"));
                Stream<Path> b = Files.list(ROOT.resolve("lowering"))) {
            return Stream.concat(a, b)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .map(p -> ROOT.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void matchesGolden(String fixture) throws IOException {
        SourceFile file = SourceFile.fromBytes(fixture, Files.readAllBytes(ROOT.resolve(fixture)));
        SourceRegistry registry = new SourceRegistry();
        DiagnosticCollector diags = new DiagnosticCollector();
        var unit = new PlcOpenFrontend().parse(file, registry, diags).unit();
        StringBuilder out = new StringBuilder();
        for (Declaration d : unit.declarations()) {
            if (d instanceof Declaration.Pou p) {
                pou(p, "", out);
            }
        }
        for (Diagnostic d : diags.diagnostics()) {
            out.append("! ")
                    .append(d.code())
                    .append(" @")
                    .append(registry.resolve(d.span()).startLine())
                    .append(' ')
                    .append(d.message())
                    .append('\n');
        }
        String name = Path.of(fixture).getFileName().toString();
        Golden.assertMatches(out.toString(), "lowering/" + name + ".lowered");
    }

    private static void pou(Declaration.Pou p, String prefix, StringBuilder out) {
        out.append("== ")
                .append(prefix)
                .append(p.kind())
                .append(' ')
                .append(p.name().name())
                .append(p.bodyLanguage() == null ? "" : " (" + p.bodyLanguage() + ")")
                .append('\n');
        if (p.body() != null) {
            out.append(StText.statements(p.body()).indent(2));
        }
        for (Declaration.Pou m : p.members()) {
            pou(m, prefix + p.name().name() + ".", out);
        }
    }
}
