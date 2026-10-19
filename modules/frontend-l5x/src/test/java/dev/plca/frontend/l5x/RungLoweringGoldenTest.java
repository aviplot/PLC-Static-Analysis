package dev.plca.frontend.l5x;

import dev.plca.ast.Ladder;
import dev.plca.ast.StText;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.core.source.SourceText;
import dev.plca.core.testing.Golden;
import dev.plca.frontend.l5x.RungLowering.AoiSignature;
import dev.plca.frontend.st.kit.TwoStageParser.Parsed;
import dev.plca.grammar.RungParser.RungContext;
import dev.plca.grammar.RungParser.RungsContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lowering goldens: every {@code src/test/resources/lowering/*.rungs} holds rung texts, one per line, and is compared
 * with {@code <file>.lowered}: each rung's normalised text followed by its lowered statements as ST. Lines starting
 * with {@code #} are comments; {@code #aoi Name Param:Usage …} declares an AOI with its required parameters.
 */
class RungLoweringGoldenTest {

    private static final Path DIR = Path.of(System.getProperty("golden.root"), "lowering");

    static Stream<String> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".rungs"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void matchesGolden(String fixture) throws IOException {
        Map<String, AoiSignature> aois = new HashMap<>();
        List<String> rungs = new ArrayList<>();
        for (String line : Files.readAllLines(DIR.resolve(fixture))) {
            if (line.startsWith("#aoi ")) {
                String[] parts = line.substring(5).trim().split("\\s+");
                List<AoiSignature.Param> params = new ArrayList<>();
                for (int k = 1; k < parts.length; k++) {
                    String[] p = parts[k].split(":");
                    params.add(new AoiSignature.Param(p[0], p[1]));
                }
                aois.put(parts[0].toUpperCase(Locale.ROOT), new AoiSignature(parts[0], params));
            } else if (!line.isBlank() && !line.startsWith("#")) {
                rungs.add(line);
            }
        }
        StringBuilder out = new StringBuilder();
        RungParserDriver driver = new RungParserDriver(true);
        for (String rung : rungs) {
            SourceRegistry registry = new SourceRegistry();
            SourceText text = registry.register(SourceFile.of(fixture, rung));
            DiagnosticCollector diags = new DiagnosticCollector();
            Parsed p = driver.parse(text, diags);
            RungAstBuilder b = new RungAstBuilder(text, p.tokens(), diags);
            out.append("rung: ").append(rung).append('\n');
            for (RungContext rc : ((RungsContext) p.tree()).rung()) {
                Ladder l = b.rung(rc);
                out.append("text: ").append(l.text()).append('\n');
                out.append(StText.statements(new RungLowering(aois).lower(l)).indent(2));
            }
            for (Diagnostic d : diags.diagnostics()) {
                out.append("  ! ")
                        .append(d.code())
                        .append(' ')
                        .append(d.message())
                        .append('\n');
            }
            out.append('\n');
        }
        Golden.assertMatches(out.toString(), "lowering/" + fixture.replace(".rungs", ".lowered"));
    }
}
