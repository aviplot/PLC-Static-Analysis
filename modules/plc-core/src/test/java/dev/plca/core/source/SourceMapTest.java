package dev.plca.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SourceMapTest {

    @Test
    void verbatimIsAnOffsetShift() {
        SourceMap m = SourceMap.verbatim(100, 10);
        assertThat(m.fragmentLength()).isEqualTo(10);
        assertThat(m.toFileStart(0)).isEqualTo(100);
        assertThat(m.toFileStart(9)).isEqualTo(109);
        assertThat(m.toFileEnd(10)).isEqualTo(110);
        assertThat(m.toFileStart(10)).isEqualTo(110);
    }

    @Test
    void replacementMapsToWholeConstruct() {
        // fragment "a<b" from file "a&lt;b" at offset 0
        SourceMap m = SourceMap.builder()
                .verbatim(0, 1)
                .replacement(1, 4, 1)
                .verbatim(5, 1)
                .build();
        assertThat(m.fragmentLength()).isEqualTo(3);
        assertThat(m.toFileStart(1)).isEqualTo(1);
        assertThat(m.toFileEnd(2)).isEqualTo(5);
        assertThat(m.toFileStart(2)).isEqualTo(5);
        assertThat(m.toFileEnd(3)).isEqualTo(6);
    }

    @Test
    void endInsideMultiCharReplacementCoversItAll() {
        // two fragment chars (a surrogate pair) produced by a 10-char character reference at file offset 20
        SourceMap m = SourceMap.builder().replacement(20, 10, 2).build();
        assertThat(m.toFileStart(1)).isEqualTo(20);
        assertThat(m.toFileEnd(1)).isEqualTo(30);
    }

    @Test
    void gapsBetweenSegmentsAreSkipped() {
        // "ab" from file offsets 3 and 10 (e.g. two CDATA sections)
        SourceMap m = SourceMap.builder().verbatim(3, 1).verbatim(10, 1).build();
        assertThat(m.toFileStart(1)).isEqualTo(10);
        assertThat(m.toFileEnd(1)).isEqualTo(4);
        assertThat(m.toFileEnd(2)).isEqualTo(11);
    }

    @Test
    void emptyMap() {
        SourceMap m = SourceMap.builder().build();
        assertThat(m.fragmentLength()).isZero();
        assertThat(m.toFileStart(0)).isZero();
    }

    @Test
    void rejectsBackwardsFileOffsetsAndOutOfRange() {
        assertThatThrownBy(() -> SourceMap.builder().verbatim(10, 2).replacement(5, 4, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceMap.verbatim(0, 3).toFileStart(4)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void inverseOfVerbatimReplacementAndGaps() {
        // fragment "a<b" from "a&lt;b" at file offset 10, then "c" from file offset 30 (a second CDATA section)
        SourceMap m = SourceMap.builder()
                .verbatim(10, 1)
                .replacement(11, 4, 1)
                .verbatim(15, 1)
                .verbatim(30, 1)
                .build();
        assertThat(m.toFragment(10)).isZero();
        assertThat(m.toFragment(11)).isEqualTo(1);
        assertThat(m.toFragment(13)).isEqualTo(1); // inside "&lt;"
        assertThat(m.toFragment(15)).isEqualTo(2);
        assertThat(m.toFragment(16)).isEqualTo(3); // directly after "b": the end of its segment
        assertThat(m.toFragment(20)).isEqualTo(-1); // between the sections
        assertThat(m.toFragment(30)).isEqualTo(3);
        assertThat(m.toFragment(31)).isEqualTo(4);
        assertThat(m.toFragment(9)).isEqualTo(-1);
        assertThat(m.toFragment(32)).isEqualTo(-1);
        assertThat(m.fileFrom()).isEqualTo(10);
        assertThat(m.fileTo()).isEqualTo(31);
        assertThat(SourceMap.builder().build().toFragment(0)).isEqualTo(-1);
    }

    @Test
    void inverseRoundTripsOverDecodedXml() {
        // every fragment offset that starts a character of its own maps back to itself
        java.util.Random random = new java.util.Random(42);
        String[] pieces = {"a", "b", "\n", "\r\n", "&lt;", "&amp;", "&#x1F600;", "<![CDATA[x:=1;]]>", "<!-- c -->", " "
        };
        for (int round = 0; round < 500; round++) {
            StringBuilder xml = new StringBuilder("<t>");
            int n = random.nextInt(20);
            for (int i = 0; i < n; i++) {
                xml.append(pieces[random.nextInt(pieces.length)]);
            }
            XmlText.Fragment f = XmlText.decode(xml.toString(), 3, xml.length());
            SourceMap m = f.map();
            for (int o = 0; o < m.fragmentLength(); o++) {
                int file = m.toFileStart(o);
                int back = m.toFragment(file);
                // inside a replacement every produced char maps to the construct start, so back is its first char
                assertThat(back).as("round %d offset %d in %s", round, o, xml).isBetween(0, o);
                assertThat(m.toFileStart(back)).isEqualTo(file);
            }
        }
    }
}
