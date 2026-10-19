package dev.plca.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.testing.Golden;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontends;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Golden dump of the whole model for each project under {@code src/test/resources/projects/}. */
class ProjectGoldenTest {

    private static final Frontends FRONTENDS = Frontends.of(StFrontend::new);

    @ParameterizedTest
    @ValueSource(strings = {"plant"})
    void modelMatchesGolden(String project) {
        Path root = Path.of(System.getProperty("golden.root"), "projects", project);
        ProjectLoader.Project p = ProjectLoader.load(root, FRONTENDS, 2);
        assertThat(p.diagnostics()).extracting(Diagnostic::message).isEmpty();
        DiagnosticCollector diags = new DiagnosticCollector();
        SemanticModel model = SemanticBuilder.build(p.units(), diags);
        StringBuilder out = new StringBuilder(ModelPrinter.print(model, p.registry()::resolve));
        out.append("diagnostics:\n");
        diags.diagnostics()
                .forEach(d -> out.append("  ")
                        .append(d.code())
                        .append(' ')
                        .append(d.message())
                        .append('\n'));
        Golden.assertMatches(out.toString(), "projects/" + project + ".model");
    }
}
