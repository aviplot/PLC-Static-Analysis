package dev.plca.core.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XmlTextTest {

    @Test
    void cdataIsVerbatim() {
        String file = "<ST><![CDATA[x := 1;]]></ST>";
        XmlText.Fragment f = content(file, "<ST>", "</ST>");
        assertThat(f.text()).isEqualTo("x := 1;");
        assertThat(f.map().toFileStart(0)).isEqualTo(13); // after "<ST><![CDATA["
        assertThat(f.map().toFileStart(5)).isEqualTo(18);
        assertThat(f.map().toFileEnd(7)).isEqualTo(20);
    }

    @Test
    void entitiesAreDecodedAndMappedToTheirSource() {
        String file = "<ST>IF a &lt; b THEN</ST>";
        XmlText.Fragment f = content(file, "<ST>", "</ST>");
        assertThat(f.text()).isEqualTo("IF a < b THEN");
        assertThat(f.map().toFileStart(5)).isEqualTo(9); // '<' -> '&'
        assertThat(f.map().toFileEnd(6)).isEqualTo(13); // end of "&lt;"
        assertThat(f.map().toFileStart(7)).isEqualTo(14); // 'b'
    }

    @Test
    void lineEndingsAreNormalisedLikeAnXmlParser() {
        String file = "<T>a\r\nb\rc</T>";
        XmlText.Fragment f = content(file, "<T>", "</T>");
        assertThat(f.text()).isEqualTo("a\nb\nc");
        assertThat(f.map().toFileStart(1)).isEqualTo(4); // '\n' -> '\r' of "\r\n"
        assertThat(f.map().toFileStart(2)).isEqualTo(6); // 'b'
        assertThat(f.map().toFileStart(4)).isEqualTo(8); // 'c'
    }

    @Test
    void cdataLineEndingsAreNormalisedToo() {
        XmlText.Fragment f = content("<T><![CDATA[a\r\nb]]></T>", "<T>", "</T>");
        assertThat(f.text()).isEqualTo("a\nb");
    }

    @Test
    void characterReferences() {
        XmlText.Fragment f = content("<T>&#65;&#x42;&#x1F600;</T>", "<T>", "</T>");
        assertThat(f.text()).isEqualTo("AB😀");
        assertThat(f.map().toFileStart(1)).isEqualTo(8);
        // the low surrogate maps into "&#x1F600;" (file 14..23), and a span ending there covers the whole reference
        assertThat(f.map().toFileStart(3)).isEqualTo(14);
        assertThat(f.map().toFileEnd(4)).isEqualTo(23);
    }

    @Test
    void splitCdataAndCommentsAndMixedContent() {
        String file = "<T><![CDATA[x]]]]><![CDATA[>y]]><!-- note -->&amp;z</T>";
        XmlText.Fragment f = content(file, "<T>", "</T>");
        assertThat(f.text()).isEqualTo("x]]>y&z");
        assertThat(f.map().toFileStart(3)).isEqualTo(file.indexOf(">y"));
        assertThat(f.map().toFileStart(6)).isEqualTo(file.lastIndexOf('z'));
    }

    @Test
    void messyInputNeverThrows() {
        assertThat(content("<T><![CDATA[unterminated</T>", "<T>", "</T>").text())
                .isEqualTo("unterminated");
        assertThat(content("<T>a & b &foo; c&#xZZ;</T>", "<T>", "</T>").text()).isEqualTo("a & b &foo; c&#xZZ;");
        assertThat(content("<T>a<?pi x?>b<br/>c</T>", "<T>", "</T>").text()).isEqualTo("abc");
    }

    private static XmlText.Fragment content(String file, String open, String close) {
        int start = file.indexOf(open) + open.length();
        int end = file.lastIndexOf(close);
        XmlText.Fragment f = XmlText.decode(file, start, end);
        assertThat(f.map().fragmentLength()).isEqualTo(f.text().length());
        return f;
    }
}
