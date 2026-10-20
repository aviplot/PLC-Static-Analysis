package dev.plca.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.SourceUnit;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Severity;
import dev.plca.frontend.l5x.L5xFrontend;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.kit.Frontends;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which drawing a span belongs to (M10): a rung by its text, a chart by its elements and its actions' statements. */
class ReportDrawingsTest {

    private static final Path GOLDEN = Path.of(System.getProperty("golden.root"))
            .resolve("../../../../frontend-l5x/src/test/resources/golden")
            .normalize();

    @Test
    void anActionStatementBelongsToItsChart() {
        ProjectLoader.Project p = ProjectLoader.load(
                GOLDEN, List.of(GOLDEN.resolve("sfc_routine.L5X")), Frontends.of(L5xFrontend::new), 1);
        SourceUnit unit = p.units().getFirst();
        Statement.Chart chart =
                (Statement.Chart) Diagrams.of(unit).getFirst().parts().getFirst();
        ReportDrawings d = ReportDrawings.of(p.units(), p.registry());
        // the Idle step's action: `Valve := 0;`, in the routine's ST text, not in the chart's element spans
        Statement action = ((Statement.If) chart.body().getFirst())
                .branches()
                .getFirst()
                .body()
                .getFirst();
        assertThat(d.partOf(action.span())).isSameAs(chart);
        assertThat(d.label(chart)).isEqualTo("Line/Sequence, chart");
        String svg = d.svg(chart, List.of(new ReportDrawings.Mark(action.span(), Severity.WARNING)));
        assertThat(svg).contains("has-finding sev-warning");
    }
}
