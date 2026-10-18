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
}
