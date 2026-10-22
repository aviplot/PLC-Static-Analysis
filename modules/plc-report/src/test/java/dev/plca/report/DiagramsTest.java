package dev.plca.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.SourceUnit;
import dev.plca.core.testing.Golden;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.plcopen.PlcOpenFrontend;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontends;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden SVG of every rung and network of the Rockwell and PLCopen fixtures (frontend goldens and the semantic test
 * projects); every drawing must be well-formed XML.
 */
class DiagramsTest {

    private static final Path RESOURCES = Path.of(System.getProperty("golden.root"));
    private static final Path MODULES = RESOURCES.resolve("../../../..").normalize();
    private static final Frontends FRONTENDS = Frontends.of(StFrontend::new, L5xFrontend::new, PlcOpenFrontend::new);

    static Stream<String> fixtures() {
        return Stream.of(
                "frontend-st/src/test/resources/golden",
                "frontend-l5x/src/test/resources/golden",
                "frontend-plcopen/src/test/resources/golden",
                "plc-semantic/src/test/resources/projects/rockwell_line",
                "plc-semantic/src/test/resources/projects/plcopen_mixer");
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void drawingsMatchGolden(String dir) throws Exception {
        ProjectLoader.Project p = ProjectLoader.load(MODULES.resolve(dir), FRONTENDS, 1);
        StringBuilder out = new StringBuilder();
        List<SourceUnit> units = p.units().stream()
                .sorted(Comparator.comparing(SourceUnit::path))
                .toList();
        int parts = 0;
        for (SourceUnit u : units) {
            for (Diagrams.Diagram d : Diagrams.of(u)) {
                out.append("== ")
                        .append(u.path())
                        .append(" ")
                        .append(d.name())
                        .append(" (")
                        .append(d.kind())
                        .append(", ")
                        .append(d.language())
                        .append(")\n");
                for (Diagrams.Part part : Diagrams.render(d, p.registry())) {
                    wellFormed(part.svg());
                    out.append("-- ")
                            .append(part.number())
                            .append('\n')
                            .append(part.svg())
                            .append('\n');
                    parts++;
                }
            }
        }
        assertThat(parts).as("drawn parts in %s", dir).isPositive();
        String name = dir.substring(0, dir.indexOf('/')) + "-" + Path.of(dir).getFileName() + ".svg.txt";
        Golden.assertMatches(out.toString(), "svg/" + name);
    }

    private static void wellFormed(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.newDocumentBuilder().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    /** Writes the drawings of a fixture as an HTML page under build/, to look at them in a browser. */
    static void preview(String dir, Path html) throws IOException {
        ProjectLoader.Project p = ProjectLoader.load(MODULES.resolve(dir), FRONTENDS, 1);
        StringBuilder h = new StringBuilder("<!DOCTYPE html><meta charset=utf-8><body>");
        for (SourceUnit u : p.units()) {
            for (Diagrams.Diagram d : Diagrams.of(u)) {
                h.append("<h3>").append(Html.escape(d.name())).append("</h3>");
                for (Diagrams.Part part : Diagrams.render(d, p.registry())) {
                    h.append("<div>").append(part.svg()).append("</div>");
                }
            }
        }
        Files.writeString(html, h);
    }
}
