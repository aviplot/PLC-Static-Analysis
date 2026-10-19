package dev.plca.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** End-to-end: spans in lexer coordinates resolve to what the engineer sees in the IDE. Expected values by hand. */
class SourceRegistryTest {

    private final SourceRegistry registry = new SourceRegistry();

    @Test
    void plainStFile() {
        SourceText t = registry.register(SourceFile.of("src/MAIN.st", "PROGRAM MAIN\r\nVAR\r\n  x : INT;\r\n"));
        int x = t.text().indexOf("x :");
        assertThat(registry.resolve(t.span(x, x + 1))).isEqualTo(new Location("src/MAIN.st", 3, 3, 3, 4));
    }

    @Test
    void twinCatCdataFragment() {
        String file = """
                <?xml version="1.0" encoding="utf-8"?>\r
                <TcPlcObject Version="1.1.0.1">\r
                  <POU Name="MAIN">\r
                    <Declaration><![CDATA[PROGRAM MAIN\r
                VAR\r
                  bStart : BOOL;\r
                END_VAR]]></Declaration>\r
                """;
        SourceFile sf = SourceFile.of("PLC/POUs/MAIN.TcPOU", file);
        int start = file.indexOf("<Declaration>") + "<Declaration>".length();
        SourceText decl = registry.registerXmlContent(sf, start, file.indexOf("</Declaration>"));
        assertThat(decl.text()).isEqualTo("PROGRAM MAIN\nVAR\n  bStart : BOOL;\nEND_VAR");

        int b = decl.text().indexOf("bStart");
        assertThat(registry.resolve(decl.span(b, b + 6))).isEqualTo(new Location("PLC/POUs/MAIN.TcPOU", 6, 3, 6, 9));

        // "    <Declaration><![CDATA[" is 26 chars, so PROGRAM starts at column 27
        assertThat(registry.resolve(decl.span(0, 7))).isEqualTo(new Location("PLC/POUs/MAIN.TcPOU", 4, 27, 4, 34));

        // a span across the normalised line break: "MAIN\nVAR"
        int main = decl.text().indexOf("MAIN");
        assertThat(registry.resolve(decl.span(main, main + 8)))
                .isEqualTo(new Location("PLC/POUs/MAIN.TcPOU", 4, 35, 5, 4));
    }

    @Test
    void plcOpenEntityEscapedBody() {
        String file = """
                <body>
                  <ST>
                    <xhtml xmlns="http://www.w3.org/1999/xhtml">IF x &lt;&gt; 0 THEN
                  y := 1;
                END_IF;</xhtml>
                  </ST>
                </body>
                """;
        SourceFile sf = SourceFile.of("project.xml", file);
        String open = "<xhtml xmlns=\"http://www.w3.org/1999/xhtml\">";
        SourceText st = registry.registerXmlContent(sf, file.indexOf(open) + open.length(), file.indexOf("</xhtml>"));
        assertThat(st.text()).isEqualTo("IF x <> 0 THEN\n  y := 1;\nEND_IF;");

        // "    " + the 44-char start tag = 48 columns; "IF x " puts "&lt;" at column 54 and "&gt;" ends before 62
        int op = st.text().indexOf("<>");
        assertThat(registry.resolve(st.span(op, op + 2))).isEqualTo(new Location("project.xml", 3, 54, 3, 62));
        int y = st.text().indexOf('y');
        assertThat(registry.resolve(st.span(y, y + 1))).isEqualTo(new Location("project.xml", 4, 3, 4, 4));
    }

    @Test
    void emptySpanResolvesToAPoint() {
        SourceText t = registry.register(SourceFile.of("a.st", "x := ;"));
        assertThat(registry.resolve(Span.at(t.id(), 5))).isEqualTo(new Location("a.st", 1, 6, 1, 6));
    }

    @Test
    void idsAreUniqueAndLookupWorks() {
        SourceFile f = SourceFile.of("a.st", "abc");
        SourceText a = registry.register(f);
        SourceText b = registry.registerFragment(f, "b", SourceMap.verbatim(1, 1));
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(registry.text(b.id())).isSameAs(b);
        assertThat(registry.size()).isEqualTo(2);
        assertThatThrownBy(() -> registry.text(99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.registerFragment(f, "bc", SourceMap.verbatim(1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positionAtFindsTheFragmentUnderTheCursor() {
        String file = "<POU><Declaration><![CDATA[VAR x : INT; END_VAR]]></Declaration>"
                + "<ST><![CDATA[x := 1;]]></ST></POU>";
        SourceFile sf = SourceFile.of("P.TcPOU", file);
        registry.register(sf); // the whole file, as XML readers register it for their own diagnostics
        int d = file.indexOf("VAR x");
        SourceText decl = registry.registerXmlContent(sf, d - "<![CDATA[".length(), file.indexOf("</Declaration>"));
        int b = file.indexOf("x := 1");
        SourceText body = registry.registerXmlContent(sf, b - "<![CDATA[".length(), file.indexOf("</ST>"));

        SourceRegistry.Position p = registry.positionAt("P.TcPOU", file.indexOf("x : INT"));
        assertThat(p.text()).isSameAs(decl);
        assertThat(p.offset()).isEqualTo(4);
        p = registry.positionAt("P.TcPOU", b + 1); // directly after the x of the body
        assertThat(p.text()).isSameAs(body);
        assertThat(p.offset()).isEqualTo(1);
        assertThat(registry.positionAt("P.TcPOU", 2).text().id()).isNotIn(decl.id(), body.id()); // in the XML
        assertThat(registry.positionAt("other.st", 0)).isNull();
    }

    @Test
    void withoutKeepsOtherFilesAndNeverReusesIds() {
        SourceText a = registry.register(SourceFile.of("a.st", "PROGRAM A END_PROGRAM"));
        SourceText b = registry.register(SourceFile.of("b.st", "PROGRAM B END_PROGRAM"));
        SourceRegistry next = registry.without(java.util.Set.of("a.st"));
        SourceText a2 = next.register(SourceFile.of("a.st", "PROGRAM A2 END_PROGRAM"));

        assertThat(next.text(b.id())).isSameAs(b);
        assertThat(a2.id()).isNotIn(a.id(), b.id());
        assertThatThrownBy(() -> next.text(a.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.text(a.id())).isSameAs(a); // the older snapshot is unchanged
        assertThat(next.textsOf("a.st")).containsExactly(a2);
    }
}
