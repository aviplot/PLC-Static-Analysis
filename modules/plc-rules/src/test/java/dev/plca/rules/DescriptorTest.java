package dev.plca.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.plca.core.diag.Severity;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every rule is documented, and the examples in its documentation behave as documented. */
class DescriptorTest {

    static Stream<String> ruleIds() {
        return RuleTesting.REGISTRY.descriptors().stream().map(RuleDescriptor::id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleIds")
    void descriptorIsComplete(String id) {
        RuleDescriptor d = RuleTesting.REGISTRY.descriptor(id).orElseThrow();
        assertThat(d.id()).matches("PLCA-[QPSL]\\d{3}");
        assertThat(d.id().charAt(5))
                .isEqualTo(
                        switch (d.category()) {
                            case QUALITY -> 'Q';
                            case PLC -> 'P';
                            case SECURITY -> 'S';
                            case LOGIC -> 'L';
                        });
        assertThat(d.title()).isNotBlank();
        assertThat(d.description()).isNotBlank();
        assertThat(d.markdown()).contains("## Rationale");
        assertThat(d.examples()).anyMatch(e -> !e.compliant()).anyMatch(RuleDescriptor.Example::compliant);
        if (d.plcopen() != null) {
            assertThat(d.plcopen()).matches("(N|C|CP|L|E)\\d{1,2}");
        }
        if (d.cwe() != null) {
            assertThat(d.cwe()).matches("CWE-\\d+");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleIds")
    void examplesBehaveAsDocumented(String id) {
        RuleDescriptor d = RuleTesting.REGISTRY.descriptor(id).orElseThrow();
        for (RuleDescriptor.Example e : d.examples()) {
            RuleTesting.Analysis a = RuleTesting.analyze(e.code());
            assertThat(a.diagnostics())
                    .as("example parses cleanly:%n%s", e.code())
                    .noneMatch(x -> x.severity() == Severity.ERROR);
            RuleEngine.Result r = a.run(RuleConfig.only(id), 1);
            if (e.compliant()) {
                assertThat(r.findings())
                        .as("compliant example of %s:%n%s", id, e.code())
                        .isEmpty();
            } else {
                assertThat(r.findings())
                        .as("noncompliant example of %s:%n%s", id, e.code())
                        .isNotEmpty();
            }
        }
    }

    @Test
    void parsesFrontMatterAndSections() {
        RuleDescriptor d = DescriptorParser.parse("""
                ---
                id: PLCA-Q999
                title: Test rule
                severity: info
                category: quality
                dialects: [codesys, twincat]
                tags: [a, b]
                plcopen: CP1
                parameters:
                  limit: int = 3 | Maximum depth
                  names: regex = (a|b) | Alternatives keep their pipe
                ---
                ## Description

                First paragraph
                continues here.

                Second paragraph.

                ## Noncompliant

                ```st
                PROGRAM P
                END_PROGRAM
                ```
                """);
        assertThat(d.severity()).isEqualTo(Severity.INFO);
        assertThat(d.dialects()).hasSize(2);
        assertThat(d.tags()).containsExactly("a", "b");
        assertThat(d.description()).isEqualTo("First paragraph continues here.");
        assertThat(d.parameter("limit").defaultValue()).isEqualTo("3");
        assertThat(d.parameter("names").defaultValue()).isEqualTo("(a|b)");
        assertThat(d.examples())
                .singleElement()
                .satisfies(e -> assertThat(e.compliant()).isFalse());
        assertThat(d.cwe()).isNull();
    }

    @Test
    void rejectsBadParameterDefaults() {
        assertThatThrownBy(() -> DescriptorParser.parse("""
                        ---
                        id: X
                        title: T
                        severity: warning
                        category: quality
                        parameters:
                          n: int = many | Not a number
                        ---
                        """)).hasMessageContaining("expected an integer");
    }
}
