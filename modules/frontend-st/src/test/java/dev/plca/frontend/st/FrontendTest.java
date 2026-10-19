package dev.plca.frontend.st;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.ast.Declaration;
import dev.plca.ast.Declaration.Pou;
import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticCollector;
import dev.plca.core.diag.Severity;
import dev.plca.core.source.Location;
import dev.plca.core.source.SourceFile;
import dev.plca.core.source.SourceRegistry;
import dev.plca.frontend.st.kit.Frontend;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrontendTest {

    private final SourceRegistry registry = new SourceRegistry();
    private final DiagnosticCollector diags = new DiagnosticCollector();

    private Frontend.Result parse(String path, String text) {
        return new StFrontend().parse(SourceFile.of(path, text), registry, diags);
    }

    private static Pou onlyPou(Frontend.Result r) {
        assertThat(r.unit().declarations()).hasSize(1);
        return (Pou) r.unit().declarations().getFirst();
    }

    @Test
    void spansInTwinCatImplementationResolveToXmlLines() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <TcPlcObject Version="1.1.0.1">
                  <POU Name="MAIN" Id="{1}" SpecialFunc="None">
                    <Declaration><![CDATA[PROGRAM MAIN
                VAR
                    n : INT;
                END_VAR]]></Declaration>
                    <Implementation>
                      <ST><![CDATA[n := n + 1;
                IF n > 3 THEN n := 0; END_IF]]></ST>
                    </Implementation>
                  </POU>
                </TcPlcObject>
                """;
        Pou main = onlyPou(parse("MAIN.TcPOU", xml));
        assertThat(diags.diagnostics()).isEmpty();
        // `n := n + 1;` starts right after "      <ST><![CDATA[" (19 chars) on line 9
        Statement first = main.body().getFirst();
        assertThat(registry.resolve(first.span())).isEqualTo(new Location("MAIN.TcPOU", 9, 20, 9, 31));
        // inside CDATA '>' needs no escaping; the IF is on line 10
        assertThat(registry.resolve(main.body().get(1).span()).startLine()).isEqualTo(10);
        // the declaration of n is on line 6, column 5
        var n = main.varBlocks().getFirst().variables().getFirst();
        assertThat(registry.resolve(n.name().span())).isEqualTo(new Location("MAIN.TcPOU", 6, 5, 6, 6));
    }

    @Test
    void entityEscapedStImplementationIsDecoded() {
        String xml = """
                <TcPlcObject>
                  <POU Name="P">
                    <Declaration>PROGRAM P</Declaration>
                    <Implementation>
                      <ST>IF a &lt; b THEN c := 1; END_IF</ST>
                    </Implementation>
                  </POU>
                </TcPlcObject>
                """;
        Pou p = onlyPou(parse("P.TcPOU", xml));
        assertThat(diags.diagnostics()).isEmpty();
        Statement.If iff = (Statement.If) p.body().getFirst();
        Expression.Binary cond = (Expression.Binary) iff.branches().getFirst().condition();
        assertThat(cond.op()).isEqualTo(Expression.BinaryOp.LT);
        // line 5 is "      <ST>IF a &lt; b THEN …": `a` is column 14, and the condition `a &lt; b` ends before 22
        assertThat(registry.resolve(cond.span())).isEqualTo(new Location("P.TcPOU", 5, 14, 5, 22));
    }

    @Test
    void columnsCountUtf16UnitsAfterAstralCharacters() {
        // the emoji in the comment is two UTF-16 units; `x` must be found at column 16 (1 + 13 + 2)
        String text = "(* 😀 smile *) x := 1;";
        Frontend.Result r = parse("a.st", text);
        Statement.Assignment a = (Statement.Assignment) onlyPou(r).body().getFirst();
        assertThat(registry.resolve(a.target().span()).startColumn()).isEqualTo(text.indexOf('x') + 1);
    }

    @Test
    void oneBadStatementCostsOneDiagnostic() {
        String text = """
                PROGRAM P
                VAR a : INT; END_VAR
                a := 1 +;
                a := 2;
                a := ) 3;
                a := 4;
                END_PROGRAM
                """;
        Pou p = onlyPou(parse("p.st", text));
        List<Diagnostic> errors = diags.diagnostics().stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .toList();
        assertThat(errors).hasSize(2);
        assertThat(errors).allSatisfy(d -> assertThat(d.code()).isEqualTo(SyntaxCodes.SYNTAX));
        // the good statements on lines 4 and 6 survive
        assertThat(p.body().stream().map(s -> registry.resolve(s.span()).startLine()))
                .contains(4, 6);
    }

    @Test
    void missingEndIfIsInsertedNotFatal() {
        Pou p = onlyPou(parse("p.st", "PROGRAM P\nIF a THEN b := 1;\nEND_PROGRAM\n"));
        assertThat(diags.diagnostics()).extracting(Diagnostic::message).anyMatch(m -> m.contains("END_IF"));
        assertThat(p.body()).first().isInstanceOf(Statement.If.class);
    }

    @Test
    void validInputTakesTheSllPathAndErrorsFallBack() {
        StFrontend fe = new StFrontend();
        Frontend.Result ok = fe.parse(SourceFile.of("a.st", "PROGRAM P\na := b;\nEND_PROGRAM"), registry, diags);
        assertThat(ok.stats().sllFallbacks()).isZero();
        Frontend.Result bad = fe.parse(SourceFile.of("b.st", "PROGRAM P\na := ;\nEND_PROGRAM"), registry, diags);
        assertThat(bad.stats().sllFallbacks()).isEqualTo(1);
        assertThat(bad.stats().syntaxErrors()).isEqualTo(1);
    }

    @Test
    void pathologicalNestingIsReportedNotThrown() {
        String deep = "PROGRAM P\nx := " + "(".repeat(200_000) + "1" + ")".repeat(200_000) + ";\nEND_PROGRAM";
        Frontend.Result r = parse("deep.st", deep);
        assertThat(r.unit()).isNotNull();
        assertThat(diags.diagnostics()).extracting(Diagnostic::code).contains(SyntaxCodes.INTERNAL);
    }

    @Test
    void malformedXmlKeepsWhatWasRead() {
        String xml = "<TcPlcObject><DUT Name=\"T\"><Declaration><![CDATA[TYPE T : INT; END_TYPE]]></Declaration>"
                + "</DUT><GVL Name=\"G\"><Declaration>";
        Frontend.Result r = parse("T.TcDUT", xml);
        assertThat(r.unit().declarations()).hasSize(1);
        assertThat(r.unit().declarations().getFirst()).isInstanceOf(Declaration.TypeDecl.class);
        assertThat(diags.diagnostics()).extracting(Diagnostic::code).contains(SyntaxCodes.MALFORMED_XML);
    }

    @Test
    void dtdAndExternalEntitiesAreNotResolved() {
        // XXE: an external entity must never be fetched or expanded, whatever happens to the rest of the file
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<TcPlcObject><POU Name=\"P\"><Declaration>PROGRAM P</Declaration>"
                + "<Implementation><ST>a := 1; (* &e; *)</ST></Implementation></POU></TcPlcObject>";
        parse("P.TcPOU", xml);
        for (int id = 0; id < registry.size(); id++) {
            assertThat(registry.text(id).text()).doesNotContain("root:");
        }
        assertThat(diags.diagnostics()).extracting(Diagnostic::message).noneMatch(m -> m.contains("root:"));
    }

    @Test
    void graphicalBodiesAreReportedAsInfo() {
        String xml = "<TcPlcObject><POU Name=\"L\"><Declaration>PROGRAM L</Declaration>"
                + "<Implementation><NWL><XmlArchive/></NWL></Implementation></POU></TcPlcObject>";
        Pou p = onlyPou(parse("L.TcPOU", xml));
        assertThat(p.body()).isNull();
        assertThat(p.bodyLanguage()).isEqualTo("NWL");
        assertThat(diags.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(SyntaxCodes.UNPARSED_LANGUAGE);
            assertThat(d.severity()).isEqualTo(Severity.INFO);
        });
    }

    @Test
    void methodsAfterEndFunctionBlockAttachToIt() {
        String text = "FUNCTION_BLOCK FB\nEND_FUNCTION_BLOCK\nMETHOD M : BOOL\nM := TRUE;\nEND_METHOD\n";
        Pou fb = onlyPou(parse("fb.st", text));
        assertThat(fb.members())
                .singleElement()
                .satisfies(m -> assertThat(m.name().name()).isEqualTo("M"));
    }

    @Test
    void ifSEqualsOneIsAComparison() {
        // `S` is an ordinary identifier, so `IF S=1` must not be read as a set-assignment
        Pou p = onlyPou(parse("p.st", "PROGRAM P\nIF S=1 THEN S := 2; END_IF\nEND_PROGRAM"));
        assertThat(diags.diagnostics()).isEmpty();
        assertThat(p.body()).singleElement().isInstanceOf(Statement.If.class);
    }
}
