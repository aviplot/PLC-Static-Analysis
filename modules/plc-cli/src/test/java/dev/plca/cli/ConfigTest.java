package dev.plca.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.plca.core.Dialect;
import dev.plca.core.diag.Severity;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code plca.yaml}, baselines and suppressions end to end. */
class ConfigTest {

    @TempDir
    Path tmp;

    private record Run(int exit, String out, String err) {}

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = Main.commandLine();
        cl.setOut(new PrintWriter(out));
        cl.setErr(new PrintWriter(err));
        int exit = cl.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    private static final String CODE = """
            PROGRAM P
            VAR
                r : REAL;
                b : BOOL;
                n : INT;
            END_VAR
            b := r = 1.0;
            b := r = 2.0; // plca-disable-line PLCA-Q003 sentinel value
            CASE n OF 1: b := TRUE; END_CASE
            END_PROGRAM
            """;

    private Path project() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(dir.resolve("P.st"), CODE);
        return dir;
    }

    private static JsonNode json(Path file) throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(file));
    }

    @Test
    void parsesEverySection() throws Exception {
        ConfigFile c = ConfigFile.parse(Path.of("plca.yaml"), """
                rules:
                  select: [default, PLCA-Q014]
                  disable: PLCA-Q002, PLCA-Q001
                  severity: {PLCA-Q003: error}
                  parameters:
                    PLCA-Q013: {maxNesting: 4}
                    plca-q014: {allowed: [10, 100]}
                  dialects:
                    rockwell:
                      severity: {PLCA-Q003: warning}
                      disable: [PLCA-Q015]
                suppress:
                  - {rule: PLCA-P002, path: "io/**", reason: generated mapping}
                  - rule: quality
                    pou: "FB_Vendor*"
                baseline: base/plca-baseline.json
                fail-on: warning
                """);
        assertThat(c.rules().select()).containsExactly("default", "PLCA-Q014");
        assertThat(c.rules().disable()).containsExactly("PLCA-Q002", "PLCA-Q001");
        assertThat(c.rules().severity()).containsEntry("PLCA-Q003", Severity.ERROR);
        assertThat(c.rules().parameters().get("PLCA-Q014")).containsEntry("allowed", "10,100");
        assertThat(c.rules().dialects().get(Dialect.ROCKWELL).severity()).containsEntry("PLCA-Q003", Severity.WARNING);
        assertThat(c.suppressions()).hasSize(2);
        assertThat(c.suppressions().get(1).pou()).isEqualTo("FB_Vendor*");
        assertThat(c.baseline().toString().replace('\\', '/')).endsWith("base/plca-baseline.json");
        assertThat(c.failOn()).isEqualTo("warning");
    }

    @Test
    void errorsNameTheLine() {
        assertThatThrownBy(() -> ConfigFile.parse(Path.of("plca.yaml"), "rules:\n  slect: [all]\n"))
                .hasMessage("plca.yaml:2: unknown key 'slect' in rules (expected one of dialects, disable, parameters,"
                        + " select, severity)");
        assertThatThrownBy(() -> ConfigFile.parse(Path.of("plca.yaml"), "rules:\n  dialects:\n    s7: {}\n"))
                .hasMessageStartingWith("plca.yaml:3: unknown dialect s7");
        assertThatThrownBy(() -> ConfigFile.parse(Path.of("plca.yaml"), "rules:\n  severity:\n    PLCA-Q003: loud\n"))
                .hasMessageStartingWith("plca.yaml:3: severity must be");
        assertThatThrownBy(() -> ConfigFile.parse(Path.of("plca.yaml"), "suppress:\n  - {path: a.st}\n"))
                .hasMessage("plca.yaml:2: a suppression needs a rule");
        assertThatThrownBy(() -> ConfigFile.parse(Path.of("plca.yaml"), "fail-on: sometimes\n"))
                .hasMessageStartingWith("plca.yaml:1: fail-on must be");
        assertThatThrownBy(() -> ConfigFile.parse(Path.of("plca.yaml"), "rules: [\n"))
                .hasMessageContaining("invalid YAML");
    }

    @Test
    void configFileIsFoundAndCommandLineWins() throws IOException {
        Path p = project();
        Path out = tmp.resolve("r.json");
        Files.writeString(p.resolve("plca.yaml"), """
                rules:
                  disable: [PLCA-Q002]
                  severity: {PLCA-Q003: error}
                fail-on: error
                """);
        Run r = run("analyze", p.toString(), "--format", "json", "-o", out.toString());
        assertThat(r.exit()).isEqualTo(1);
        JsonNode j = json(out);
        assertThat(j.get("findings").findValuesAsString("ruleId")).containsExactly("PLCA-Q003");
        assertThat(j.get("findings").get(0).get("severity").asString()).isEqualTo("error");
        assertThat(j.get("stats").get("suppressed").get("inline").asInt()).isEqualTo(1);
        assertThat(j.get("suppressed").get(0).get("reason").asString()).isEqualTo("sentinel value");

        assertThat(run("analyze", p.toString(), "-o", out.toString(), "--fail-on", "never")
                        .exit())
                .isZero();
        run("analyze", p.toString(), "--format", "json", "-o", out.toString(), "--rules", "PLCA-Q002");
        assertThat(json(out).get("findings").findValuesAsString("ruleId")).containsExactly("PLCA-Q002");
        run("analyze", p.toString(), "--format", "json", "-o", out.toString(), "--no-config");
        assertThat(json(out).get("findings").findValuesAsString("ruleId")).contains("PLCA-Q002", "PLCA-Q003");

        Files.writeString(p.resolve("plca.yaml"), "rules:\n  disable: [PLCA-Q999]\n");
        Run bad = run("analyze", p.toString());
        assertThat(bad.exit()).isEqualTo(2);
        assertThat(bad.err()).contains("PLCA-Q999");
    }

    private static String controller(String name) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <RSLogix5000Content SchemaRevision="1.0" SoftwareRevision="35.00" TargetName="%s" TargetType="Controller">
                <Controller Use="Target" Name="%s">
                <Tags>
                <Tag Name="Start" TagType="Base" DataType="BOOL" ExternalAccess="None"/>
                <Tag Name="Motor" TagType="Base" DataType="BOOL" ExternalAccess="None"/>
                </Tags>
                <Programs>
                <Program Name="Main" MainRoutineName="R">
                <Routines>
                <Routine Name="R" Type="RLL">
                <RLLContent>
                <Rung Number="0" Type="N"><Text><![CDATA[XIC(Start)OTE(Motor);]]></Text></Rung>
                </RLLContent>
                </Routine>
                </Routines>
                </Program>
                </Programs>
                <Tasks>
                <Task Name="T" Type="CONTINUOUS" Priority="10"><ScheduledPrograms><ScheduledProgram Name="Main"/></ScheduledPrograms></Task>
                </Tasks>
                </Controller>
                </RSLogix5000Content>
                """.formatted(name, name);
    }

    @Test
    void eachControllerIsItsOwnProject() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("ctl"));
        // two versions of one controller: one model each, so no double coil across the files
        Files.writeString(dir.resolve("Line_v1.L5X"), controller("Line"));
        Files.writeString(dir.resolve("Line_v2.L5X"), controller("Line"));
        Path out = tmp.resolve("c.json");
        assertThat(run("analyze", dir.toString(), "--format", "json", "-o", out.toString())
                        .exit())
                .isZero();
        JsonNode j = json(out);
        assertThat(j.get("findings").findValuesAsString("ruleId")).doesNotContain("PLCA-P001");
        // one shared model would declare program Main twice (PLC-SEM-002)
        assertThat(j.get("diagnostics").findValuesAsString("code")).isEmpty();
        assertThat(j.get("stats").get("pous").asInt()).isEqualTo(4);
    }

    @Test
    void writesSelfContainedHtml() throws IOException {
        Path p = project();
        Path out = tmp.resolve("r.html");
        assertThat(run("analyze", p.toString(), "--format", "html", "-o", out.toString(), "--fail-on", "never")
                        .exit())
                .isZero();
        String html = Files.readString(out);
        assertThat(html)
                .startsWith("<!DOCTYPE html>")
                .contains("PLCA-Q003", "sentinel value")
                .doesNotContain("http://");
    }

    @Test
    void baselineRoundTrip() throws IOException {
        Path p = project();
        Path base = tmp.resolve("plca-baseline.json");
        Path out = tmp.resolve("r.sarif");
        Run w = run(
                "analyze", p.toString(), "-o", tmp.resolve("x.txt").toString(), "--write-baseline", base.toString());
        assertThat(w.err()).contains("wrote 2 findings to baseline");
        assertThat(json(base).get("findings")).hasSize(2);

        // move the code down and add a new finding: only the new one is reported
        Files.writeString(p.resolve("P.st"), "\n\n" + CODE.replace("END_PROGRAM", "b := r = 7.0;\nEND_PROGRAM"));
        Path console = tmp.resolve("c.txt");
        Run r = run(
                "analyze",
                p.toString(),
                "-o",
                console.toString(),
                "--baseline",
                base.toString(),
                "--fail-on",
                "warning");
        assertThat(r.exit()).isEqualTo(1);
        assertThat(Files.readString(console))
                .contains("1 finding", "2 in the baseline", "0 fixed since", "1 suppressed");
        run("analyze", p.toString(), "--format", "sarif", "-o", out.toString(), "--baseline", base.toString());
        JsonNode results = json(out).get("runs").get(0).get("results");
        assertThat(results.findValuesAsString("baselineState"))
                .containsExactlyInAnyOrder("new", "unchanged", "unchanged", "new");
        assertThat(results.findValues("suppressions")).hasSize(1);

        // a fixed finding shows up in the summary
        Files.writeString(p.resolve("P.st"), CODE.replace("b := r = 1.0;", ""));
        run("analyze", p.toString(), "-o", console.toString(), "--baseline", base.toString());
        assertThat(Files.readString(console)).contains("1 in the baseline", "1 fixed since");

        Files.writeString(base, "{}");
        assertThat(run("analyze", p.toString(), "--baseline", base.toString()).exit())
                .isEqualTo(2);
    }
}
