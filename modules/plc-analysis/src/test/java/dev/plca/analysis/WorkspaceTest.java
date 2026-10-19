package dev.plca.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.SourceRegistry;
import dev.plca.report.AnalysisResult;
import dev.plca.rules.Finding;
import dev.plca.rules.RuleConfig;
import dev.plca.rules.RuleRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The editor's incremental state agrees with a fresh {@code plca analyze} after every edit. */
class WorkspaceTest {

    private static final Path PROJECTS = Path.of("../plc-semantic/src/test/resources/projects");
    private static final RuleRegistry RULES = RuleRegistry.standard();

    @TempDir
    Path tmp;

    private Path copy(String... projects) throws IOException {
        for (String project : projects) {
            Path from = PROJECTS.resolve(project);
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : s.toList()) {
                    Path to = tmp.resolve(project).resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(p, to);
                    }
                }
            }
        }
        return tmp;
    }

    /** Findings and diagnostics as "path:line:col code message", the way reports show them. */
    private static List<String> lines(List<Finding> findings, List<Diagnostic> diagnostics, SourceRegistry r) {
        List<String> out = new ArrayList<>();
        findings.forEach(f -> out.add(r.resolve(f.span()) + " " + f.ruleId() + " " + f.message()));
        diagnostics.forEach(d -> out.add(r.resolve(d.span()) + " " + d.code() + " " + d.message()));
        out.sort(null);
        return out;
    }

    private static List<String> fresh(Path root) {
        AnalysisResult r = new Analyzer(RULES).analyze(root, RuleConfig.defaults(), 2);
        return lines(r.findings(), r.diagnostics(), r.sources());
    }

    private static List<String> full(Workspace w) {
        Snapshot s = w.snapshot();
        Snapshot.Analysis a = s.analyze(RULES, RuleConfig.defaults(), null, null, 2, () -> false);
        List<Diagnostic> all = new ArrayList<>(a.diagnostics());
        s.paths().forEach(p -> all.addAll(s.file(p).orElseThrow().diagnostics()));
        return lines(a.findings(), all, s.registry());
    }

    @Test
    void fullRunAgreesWithTheCliOnEveryLanguage() throws IOException {
        Path root = copy("plant", "rockwell_line", "plcopen_mixer", "tia_line");
        Workspace w = new Workspace(root);
        w.loadAll(2);
        assertThat(full(w)).isNotEmpty().isEqualTo(fresh(root));
    }

    @Test
    void editsAgreeWithAFreshRunOfTheEditedFiles() throws IOException {
        Path root = copy("plant");
        Workspace w = new Workspace(root);
        w.loadAll(2);
        String main = Files.readString(root.resolve("plant/MAIN.st"));

        // a syntax error, a double coil and an unused variable, then a deleted and a new file
        String edited = main.replace("n := i + 1;", "n := i + ;\nn := 2;\nn := 3;")
                .replace("    eMode : E_Mode;", "    eMode : E_Mode;\n    xUnused : BOOL;");
        assertThat(w.update("plant/MAIN.st", edited)).isTrue();
        Files.writeString(root.resolve("plant/MAIN.st"), edited);
        assertThat(full(w)).isEqualTo(fresh(root));

        Files.delete(root.resolve("plant/functions.st"));
        assertThat(w.reload("plant/functions.st")).isTrue();
        Files.writeString(root.resolve("plant/F_New.st"), "FUNCTION F_New : INT\nF_New := 1 / 0;\nEND_FUNCTION\n");
        assertThat(w.reload("plant/F_New.st")).isTrue();
        assertThat(w.contains("plant/functions.st")).isFalse();
        assertThat(full(w)).isEqualTo(fresh(root));
    }

    @Test
    void unchangedGroupsKeepTheirModel() throws IOException {
        Path root = copy("plant", "rockwell_line");
        Workspace w = new Workspace(root);
        w.loadAll(2);
        Snapshot before = w.snapshot();
        Snapshot.Group rockwell = before.groupOf("rockwell_line/Line1.L5X").orElseThrow();
        Snapshot.Group plant = before.groupOf("plant/MAIN.st").orElseThrow();

        w.update("plant/MAIN.st", Files.readString(root.resolve("plant/MAIN.st")) + "\n");
        Snapshot after = w.snapshot();
        assertThat(after.groupOf("rockwell_line/Line1.L5X").orElseThrow()).isSameAs(rockwell);
        assertThat(after.groupOf("plant/MAIN.st").orElseThrow().model()).isNotSameAs(plant.model());
        // the older snapshot still resolves its own spans
        assertThat(before.registry()
                        .resolve(plant.units()
                                .getFirst()
                                .declarations()
                                .getFirst()
                                .span()))
                .isNotNull();
    }

    @Test
    void fastTierRunsOnlyPouRulesOfTheGivenFiles() throws IOException {
        Path root = copy("plant");
        Workspace w = new Workspace(root);
        w.loadAll(2);
        w.update(
                "plant/FB_Motor.st",
                Files.readString(root.resolve("plant/FB_Motor.st")).replace("END_VAR", "    rTest : REAL;\nEND_VAR"));
        w.update(
                "plant/MAIN.st",
                Files.readString(root.resolve("plant/MAIN.st"))
                        .replace("eMode := Auto;", "IF rSpeed = 1.0 THEN eMode := Auto; END_IF"));
        Snapshot s = w.snapshot();

        Snapshot.Analysis fast = s.analyze(RULES, RuleConfig.defaults(), null, Set.of("plant/MAIN.st"), 1, () -> false);
        Snapshot.Analysis all = s.analyze(RULES, RuleConfig.defaults(), null, null, 1, () -> false);
        assertThat(fast.findings()).extracting(f -> s.pathOf(f.span())).containsOnly("plant/MAIN.st");
        assertThat(fast.findings()).extracting(Finding::ruleId).contains("PLCA-Q003");
        // every fast finding is one of the full run's, in the same form
        assertThat(all.findings()).containsAll(fast.findings());
        assertThat(all.findings()).extracting(f -> s.pathOf(f.span())).contains("plant/FB_Motor.st");
    }

    @Test
    void cancelledRunThrows() throws IOException {
        Workspace w = new Workspace(copy("plant"));
        w.loadAll(1);
        Snapshot s = w.snapshot();
        assertThatThrownBy(() -> s.analyze(RULES, RuleConfig.defaults(), null, null, 1, () -> true))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void filesNoFrontendReadsAreIgnored() {
        Workspace w = new Workspace(tmp);
        assertThat(w.update("notes.txt", "hello")).isFalse();
        assertThat(w.update("P.st", "PROGRAM P\nx := ;\nEND_PROGRAM\n")).isTrue();
        assertThat(w.snapshot().file("P.st").orElseThrow().diagnostics())
                .extracting(Diagnostic::code)
                .contains("PLC-SYN-001");
        assertThat(w.displayPath(tmp.resolve("a/b.st"))).isEqualTo("a/b.st");
    }
}
