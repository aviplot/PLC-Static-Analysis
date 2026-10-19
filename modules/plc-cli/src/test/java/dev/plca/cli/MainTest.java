package dev.plca.cli;

import static org.assertj.core.api.Assertions.assertThat;

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

class MainTest {

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

    private Path project(String name, String code) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(dir.resolve(name), code);
        return dir;
    }

    private static final String WARNINGS_ONLY = """
            PROGRAM P
            VAR
                r : REAL;
                b : BOOL;
            END_VAR
            b := r = 1.0;
            END_PROGRAM
            """;

    @Test
    void exitCodeFollowsFailOn() throws IOException {
        Path p = project("P.st", WARNINGS_ONLY);
        Path report = tmp.resolve("r.txt");
        assertThat(run("analyze", p.toString(), "-o", report.toString()).exit()).isZero();
        assertThat(run("analyze", p.toString(), "-o", report.toString(), "--fail-on", "warning")
                        .exit())
                .isEqualTo(1);
        assertThat(run("analyze", p.toString(), "-o", report.toString(), "--fail-on", "WARNING", "--rules", "plc")
                        .exit())
                .isZero();
    }

    @Test
    void writesJsonAndSarif() throws IOException {
        Path p = project("P.st", WARNINGS_ONLY);
        Path json = tmp.resolve("r.json");
        Path sarif = tmp.resolve("r.sarif");
        run("analyze", p.toString(), "--format", "json", "-o", json.toString());
        run("analyze", p.resolve("P.st").toString(), "--format", "sarif", "-o", sarif.toString());
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode j = mapper.readTree(json.toFile());
        assertThat(j.get("findings").get(0).get("ruleId").asString()).isEqualTo("PLCA-Q003");
        assertThat(j.get("stats").get("files").asInt()).isEqualTo(1);
        JsonNode s = mapper.readTree(sarif.toFile());
        JsonNode result = s.get("runs").get(0).get("results").get(0);
        assertThat(result.get("ruleId").asString()).isEqualTo("PLCA-Q003");
        assertThat(result.get("locations")
                        .get(0)
                        .get("physicalLocation")
                        .get("artifactLocation")
                        .get("uri")
                        .asString())
                .isEqualTo("P.st");
        assertThat(s.get("runs")
                        .get(0)
                        .get("originalUriBaseIds")
                        .get("SRCROOT")
                        .get("uri")
                        .asString())
                .startsWith("file:")
                .endsWith("/p/");
    }

    @Test
    void parametersReachTheRules() throws IOException {
        Path p = project("G.st", """
                VAR_GLOBAL
                    gNobody : INT;
                END_VAR
                PROGRAM P
                END_PROGRAM
                """);
        Path json = tmp.resolve("r.json");
        run("analyze", p.toString(), "--format", "json", "-o", json.toString());
        assertThat(Files.readString(json)).doesNotContain("gNobody");
        run(
                "analyze",
                p.toString(),
                "--format",
                "json",
                "-o",
                json.toString(),
                "--param",
                "plca-q001.includeGlobals=true");
        assertThat(Files.readString(json)).contains("gNobody");
    }

    @Test
    void usageErrorsExitWithTwo() throws IOException {
        Path p = project("P.st", WARNINGS_ONLY);
        assertThat(run("analyze", tmp.resolve("missing").toString()).exit()).isEqualTo(2);
        Run unknown = run("analyze", p.toString(), "--rules", "PLCA-Z999");
        assertThat(unknown.exit()).isEqualTo(2);
        assertThat(unknown.err()).contains("PLCA-Z999");
        assertThat(run("analyze", p.toString(), "--param", "PLCA-Q001.nope=1").exit())
                .isEqualTo(2);
        assertThat(run("analyze", p.toString(), "--param", "garbage").exit()).isEqualTo(2);
        assertThat(run("analyze", p.toString(), "--format", "xml").exit()).isEqualTo(2);
        assertThat(run().exit()).isEqualTo(2);
    }

    @Test
    void brokenInputIsNotAnError() throws IOException {
        Path p = project("Broken.st", "PROGRAM P\nIF THEN := ;;\n");
        Path report = tmp.resolve("r.txt");
        Run r = run("analyze", p.toString(), "-o", report.toString());
        assertThat(r.exit()).isZero();
        assertThat(Files.readString(report)).contains("PLC-SYN-", "input problem");
    }

    @Test
    void listsAndDocumentsRules() {
        Run list = run("rules");
        assertThat(list.exit()).isZero();
        assertThat(list.out()).contains("PLCA-Q001", "PLCA-S001", "CP24");
        Run one = run("rules", "plca-p001");
        assertThat(one.out()).contains("Double coil", "PLCopen CP12", "includeLocals", "## Rationale");
        assertThat(run("rules", "PLCA-Z999").exit()).isEqualTo(2);
    }

    @Test
    void skippedDialectsAreReported() throws IOException {
        Path p = project("P.st", WARNINGS_ONLY);
        Files.writeString(p.resolve("Motor.scl"), "FUNCTION_BLOCK \"Motor\"\nEND_FUNCTION_BLOCK\n");
        Files.writeString(p.resolve("Line.L5X"), "<RSLogix5000Content/>");
        Files.writeString(p.resolve("Line.L5K"), "CONTROLLER Line\nEND_CONTROLLER\n");
        Files.writeString(p.resolve("other.xml"), "<root/>");
        assertThat(Analyzer.skipped(p)).containsExactly("Line.L5K");
    }

    @Test
    void analysesSiemensScl() throws IOException {
        Path p = project("FB_Cmd.scl", """
                FUNCTION_BLOCK "FB_Cmd"
                VERSION : 0.1
                   VAR_INPUT
                      Start : Bool;
                   END_VAR
                BEGIN
                	#Start := FALSE;
                	%Q0.0 := "di_Button";
                END_FUNCTION_BLOCK
                """);
        // not a data block source: skipped without syntax errors
        Files.write(p.resolve("Thumbs.db"), new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 1, 2});
        Path report = tmp.resolve("r.json");
        assertThat(run("analyze", p.toString(), "--format", "json", "-o", report.toString())
                        .exit())
                .isZero();
        JsonNode json = JsonMapper.builder().build().readTree(Files.readString(report));
        assertThat(json.get("findings").findValuesAsString("ruleId")).contains("PLCA-Q006", "PLCA-P002");
        assertThat(json.get("diagnostics").findValuesAsString("severity")).doesNotContain("error");
    }
}
