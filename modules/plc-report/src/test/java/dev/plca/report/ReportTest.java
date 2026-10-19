package dev.plca.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.testing.Golden;
import dev.plca.frontend.st.ProjectLoader;
import dev.plca.frontend.st.StFrontend;
import dev.plca.frontend.st.kit.Frontends;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleEngine;
import dev.plca.rules.RuleRegistry;
import dev.plca.semantic.SemanticBuilder;
import dev.plca.semantic.SemanticModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {

    private static final Frontends FRONTENDS = Frontends.of(StFrontend::new);

    private static final Path RESOURCES = Path.of(System.getProperty("golden.root"));
    /** modules/plc-report/src/test/resources -> repository root */
    private static final Path REPOSITORY = RESOURCES.resolve("../../../../..").normalize();

    /** Runs all rules on a directory; root URI and timings fixed so goldens are machine-independent. */
    static AnalysisResult analyze(Path dir) {
        ProjectLoader.Project p = ProjectLoader.load(dir, FRONTENDS, 2);
        DiagnosticCollector sem = new DiagnosticCollector();
        SemanticModel m = SemanticBuilder.build(p.units(), sem);
        RuleRegistry registry = RuleRegistry.standard();
        RuleEngine.Result r = new RuleEngine(registry).run(m, p.registry(), RuleConfig.defaults(), 2);
        List<Diagnostic> diags = new ArrayList<>(p.diagnostics());
        diags.addAll(sem.diagnostics());
        diags.addAll(r.diagnostics());
        int lines = p.registry().files().stream()
                .mapToInt(f -> f.lineIndex().lineCount())
                .sum();
        return new AnalysisResult(
                "file:///project/",
                registry.descriptors(),
                r.findings(),
                diags,
                p.registry(),
                new AnalysisResult.Stats(
                        p.units().size(),
                        lines,
                        m.pous().size(),
                        0,
                        0,
                        0,
                        m.resolutionStats().rate(),
                        List.of()));
    }

    private static String render(ReportFormat format, AnalysisResult r) throws IOException {
        StringWriter w = new StringWriter();
        format.write(r, w);
        return w.toString();
    }

    private static List<String> errors(Schema schema, String document) {
        return schema.validate(document, InputFormat.JSON).stream()
                .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                .toList();
    }

    @Test
    void consoleGolden() throws IOException {
        Golden.assertMatches(render(ReportFormat.CONSOLE, analyze(RESOURCES.resolve("project"))), "report.console.txt");
    }

    @Test
    void jsonGoldenAndSchema() throws IOException {
        String json = render(ReportFormat.JSON, analyze(RESOURCES.resolve("project")));
        Golden.assertMatches(json, "report.json");
        Path schema = RESOURCES
                .getParent()
                .getParent()
                .getParent()
                .getParent()
                .getParent()
                .resolve("docs/report-json.schema.json");
        Schema s = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(schema), InputFormat.JSON);
        assertThat(errors(s, json)).isEmpty();
    }

    @Test
    void sarifGoldenAndOfficialSchema() throws IOException {
        String sarif = render(ReportFormat.SARIF, analyze(RESOURCES.resolve("project")));
        Golden.assertMatches(sarif, "report.sarif.json");
        try (InputStream in = Files.newInputStream(RESOURCES.resolve("sarif/sarif-schema-2.1.0.json"))) {
            Schema s = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4)
                    .getSchema(in, InputFormat.JSON);
            assertThat(errors(s, sarif)).isEmpty();
        }
    }

    @Test
    void htmlGoldenAndWellFormed() throws IOException {
        String html = render(ReportFormat.HTML, analyze(RESOURCES.resolve("project")));
        Golden.assertMatches(html, "report.html");
        assertThat(HtmlCheck.problems(html)).isEmpty();
        // self-contained: no external resources of any kind
        assertThat(html).doesNotContainPattern("(?i)(src|href)\\s*=\\s*\"(https?:)?//");
        assertThat(html).doesNotContainPattern("(?i)<link\\b|@import|url\\(");
    }

    @Test
    void fingerprintsSurviveCodeMovingDown(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        String code = Files.readString(RESOURCES.resolve("project/MAIN.st"));
        Files.writeString(a.resolve("MAIN.st"), code);
        Files.writeString(
                b.resolve("MAIN.st"),
                "(* a new header comment *)\n\n"
                        + code.replace("    nStep : INT;", "    nStep : INT;\n    nNew : INT := 1;"));
        List<String> fa = Fingerprints.of(analyze(a));
        List<String> fb = Fingerprints.of(analyze(b));
        assertThat(fa).isNotEmpty();
        assertThat(fb).containsAll(fa);
    }

    @Test
    void relativeUrisArePercentEncoded() {
        assertThat(SarifReport.relativeUri("dir/with space.st")).isEqualTo("dir/with%20space.st");
        assertThat(SarifReport.relativeUri("POUs\\Ä.TcPOU")).isEqualTo("POUs/%C3%84.TcPOU");
    }
}
