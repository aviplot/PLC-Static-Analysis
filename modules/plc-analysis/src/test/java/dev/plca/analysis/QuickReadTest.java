package dev.plca.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.SourceUnit;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.report.Diagrams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A quick read draws exactly what the full parse draws, for the routine it parses, and lists every routine. */
class QuickReadTest {

    private static final Path PROJECTS = Path.of("../plc-semantic/src/test/resources/projects");

    @ParameterizedTest
    @ValueSource(strings = {"rockwell_line/Line1.L5X", "plcopen_mixer/Mixer.xml"})
    void drawsLikeTheFullParse(String path) throws Exception {
        Path file = PROJECTS.resolve(path);
        SourceRegistry fullRegistry = new SourceRegistry();
        SourceUnit full = Analyzer.FRONTENDS
                .forPath(path)
                .parse(SourceFile.fromBytes(path, Files.readAllBytes(file)), fullRegistry, new DiagnosticCollector())
                .unit();
        List<Diagrams.Diagram> expected = Diagrams.of(full);
        assertThat(expected).isNotEmpty();

        QuickRead.Result list = QuickRead.read(file, path, null);
        assertThat(Diagrams.of(list.unit()))
                .extracting(d -> d.name() + ":" + d.parts().size())
                .containsExactlyElementsOf(expected.stream()
                        .map(d -> d.name() + ":" + d.parts().size())
                        .toList());

        for (Diagrams.Diagram d : expected) {
            QuickRead.Result one = QuickRead.read(file, path, d.name());
            Diagrams.Diagram drawn = Diagrams.of(one.unit()).stream()
                    .filter(x -> x.name().equals(d.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(Diagrams.render(drawn, one.registry())).as(d.name()).isEqualTo(Diagrams.render(d, fullRegistry));
        }
    }
}
