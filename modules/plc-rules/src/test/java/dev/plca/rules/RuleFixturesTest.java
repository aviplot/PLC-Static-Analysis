package dev.plca.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.Severity;
import dev.plca.core.testing.Golden;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One case per directory under {@code src/test/resources/<RULE_ID>/} (the rule directory itself and each subdirectory
 * holding sources). Only the directory's rule runs; an optional {@code config.properties} sets its parameters. Findings
 * must match the inline markers exactly, and their full text the {@code expected.txt} golden.
 */
class RuleFixturesTest {

    static Stream<String> cases() throws IOException {
        Path root = Path.of(System.getProperty("golden.root"));
        try (Stream<Path> s = Files.walk(root)) {
            return s
                    .filter(Files::isDirectory)
                    .filter(d -> root.relativize(d).toString().startsWith("PLCA-"))
                    .filter(d -> !RuleTesting.directSources(d).isEmpty())
                    .map(d -> root.relativize(d).toString().replace('\\', '/'))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void findingsMatchMarkersAndGolden(String relative) {
        Path dir = Path.of(System.getProperty("golden.root")).resolve(relative);
        String ruleId = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
        RuleTesting.Analysis a = RuleTesting.load(dir);
        assertThat(a.diagnostics())
                .as("fixture must parse and model cleanly")
                .filteredOn(d -> d.severity() == Severity.ERROR)
                .isEmpty();

        RuleConfig config = RuleConfig.only(ruleId).withParameters(Map.of(ruleId, parameters(dir)));
        RuleEngine.Result r = a.run(config, 2);
        assertThat(r.diagnostics()).extracting(Diagnostic::message).isEmpty();

        List<String> expected = RuleTesting.expectedMarkers(dir);
        assertThat(RuleTesting.actualMarkers(a.sources(), r.findings()))
                .as("findings vs. inline markers (path:line RULE)")
                .isEqualTo(expected);
        Golden.assertMatches(RuleTesting.print(a.sources(), r.findings()), relative + "/expected.txt");
    }

    private static Map<String, String> parameters(Path dir) {
        Path f = dir.resolve("config.properties");
        Map<String, String> r = new HashMap<>();
        if (Files.exists(f)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            p.stringPropertyNames().forEach(k -> r.put(k, p.getProperty(k)));
        }
        return r;
    }
}
